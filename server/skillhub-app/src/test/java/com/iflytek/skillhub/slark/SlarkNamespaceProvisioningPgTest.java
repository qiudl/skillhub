package com.iflytek.skillhub.slark;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.skill.service.SkillSlugResolutionService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import com.iflytek.skillhub.infra.jpa.SlarkNamespaceBindingJdbcRepository;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** REQ-20261013-0021 M1-06: real migration, atomicity and concurrency, no repository mocks. */
@Testcontainers
class SlarkNamespaceProvisioningPgTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static SlarkNamespaceBindingJdbcRepository repository;
    static final SlarkNamespaceKey KEY = new SlarkNamespaceKey("registry", "project", "project-a");

    @BeforeAll static void initialize() {
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new SlarkNamespaceBindingJdbcRepository(jdbc, new DataSourceTransactionManager(dataSource));
    }
    @BeforeEach void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_binding ON slark_namespace_binding");
        jdbc.execute("TRUNCATE slark_namespace_binding, namespace CASCADE");
    }
    @Test void provisionsFourSeparateFrozenSpacesWithoutMembersOrAdoptingGlobal() {
        jdbc.update("INSERT INTO namespace(slug,display_name,type,status) VALUES ('global','Existing','GLOBAL','ACTIVE')");
        for (String kind : new String[]{"public", "organization", "project", "personal"}) {
            var key = new SlarkNamespaceKey("registry", kind, kind.equals("public") ? "public" : "owner");
            var binding = repository.provision(key, "slark");
            assertThat(binding.namespaceId()).matches("[1-9][0-9]*");
            assertThat(binding.namespaceSlug()).isEqualTo(key.namespaceSlug()).isNotEqualTo("global");
            assertThat(binding.provisionState()).isEqualTo("PREPARED");
            assertThat(jdbc.queryForObject("SELECT status FROM namespace WHERE id=?", String.class, Long.valueOf(binding.namespaceId()))).isEqualTo("FROZEN");
            assertThat(repository.provision(key, "slark")).isEqualTo(binding);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace_member", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM namespace WHERE slug='global'", String.class)).isEqualTo("ACTIVE");
    }
    @Test void concurrentRetriesCreateExactlyOneBindingAndNamespace() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<String>>();
            for (int i = 0; i < 16; i++) jobs.add(() -> repository.provision(KEY, "slark").namespaceId());
            var ids = new ArrayList<String>();
            for (var result : executor.invokeAll(jobs)) ids.add(result.get());
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_namespace_binding", Integer.class)).isEqualTo(1);
    }
    @Test void sameOwnerInDifferentScopesAndRegistriesCannotAlias() {
        var a = repository.provision(KEY, "slark");
        var b = repository.provision(new SlarkNamespaceKey("other", "project", "project-a"), "slark");
        var c = repository.provision(new SlarkNamespaceKey("registry", "organization", "project-a"), "slark");
        assertThat(java.util.Set.of(a.namespaceId(), b.namespaceId(), c.namespaceId())).hasSize(3);
    }
    @Test void neverAdoptsAnExistingSlugAndDoesNotLeaveABinding() {
        jdbc.update("INSERT INTO namespace(slug,display_name,type,status) VALUES (?,'Existing','TEAM','FROZEN')", KEY.namespaceSlug());
        assertThatThrownBy(() -> repository.provision(KEY, "slark")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_namespace_binding", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM namespace", String.class)).isEqualTo("Existing");
    }
    @Test void bindingFailureRollsBackNamespaceAndRetryCanRecover() {
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_test_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test fault'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_binding BEFORE INSERT ON slark_namespace_binding FOR EACH ROW EXECUTE FUNCTION reject_test_binding()");
        assertThatThrownBy(() -> repository.provision(KEY, "slark")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace", Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER reject_test_binding ON slark_namespace_binding");
        assertThat(repository.provision(KEY, "slark").provisionState()).isEqualTo("PREPARED");
    }
    @Test void bindingAndNamespaceIdentityCannotBeReassignedDeletedOrUnfrozen() {
        var binding = repository.provision(KEY, "slark");
        for (String sql : new String[]{
                "UPDATE slark_namespace_binding SET owner_id='other'", "DELETE FROM slark_namespace_binding",
                "UPDATE namespace SET slug='renamed'", "UPDATE namespace SET type='GLOBAL'",
                "UPDATE namespace SET status='ACTIVE'", "DELETE FROM namespace"}) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
        assertThat(repository.provision(KEY, "slark")).isEqualTo(binding);
        assertThat(jdbc.queryForObject("SELECT provisioned_by_client FROM slark_namespace_binding", String.class)).isEqualTo("slark");
    }
    @Test void namespaceLongIsReturnedWithoutJavascriptPrecisionLoss() {
        jdbc.execute("SELECT setval('namespace_id_seq',9007199254740993,false)");
        assertThat(repository.provision(KEY, "slark").namespaceId()).isEqualTo("9007199254740993");
    }
    @Test void lockWaitFailsWithoutCreatingPartialStateThenRecovers() throws Exception {
        try (var held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (var statement = held.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, KEY.namespaceSlug()); statement.execute();
            }
            assertThatThrownBy(() -> repository.provision(KEY, "slark")).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace", Integer.class)).isZero();
            held.rollback();
        }
        assertThat(repository.provision(KEY, "slark").provisionState()).isEqualTo("PREPARED");
    }

    @Test void everyPreparedScopeDeniesEveryPackageEntryBeforeStorageUrlsOrMetrics() {
        long nativeId = jdbc.queryForObject("INSERT INTO namespace(slug,display_name,type,status) VALUES ('slark-native','Native','TEAM','FROZEN') RETURNING id", Long.class);
        assertThat(repository.isBound(nativeId)).isFalse(); // Never infer binding from slug or FROZEN alone.
        for (String kind : new String[]{"public", "organization", "project", "personal"}) {
            var key = new SlarkNamespaceKey("registry", kind, kind.equals("public") ? "public" : "owner");
            var binding = repository.provision(key, "slark");
            long namespaceId = Long.parseLong(binding.namespaceId());
            assertThat(repository.isBound(namespaceId)).isTrue();
            var fixture = new DownloadFixture(namespaceId, binding.namespaceSlug());
            for (String actor : new String[]{null, "owner", "retained-native-admin"}) {
                var roles = actor == null ? java.util.Map.<Long, NamespaceRole>of() : java.util.Map.of(namespaceId, NamespaceRole.OWNER);
                assertThatThrownBy(() -> fixture.service.downloadLatest(binding.namespaceSlug(), "skill", actor, roles)).isInstanceOf(DomainForbiddenException.class);
                assertThatThrownBy(() -> fixture.service.downloadVersion(binding.namespaceSlug(), "skill", "1.0.0", actor, roles)).isInstanceOf(DomainForbiddenException.class);
                assertThatThrownBy(() -> fixture.service.downloadByTag(binding.namespaceSlug(), "skill", "latest", actor, roles)).isInstanceOf(DomainForbiddenException.class);
            }
            // Review packages bypass ordinary visibility/version eligibility, but never this boundary.
            for (var state : new SkillVersionStatus[]{SkillVersionStatus.PUBLISHED, SkillVersionStatus.PENDING_REVIEW, SkillVersionStatus.UPLOADED}) {
                fixture.version.setStatus(state);
                assertThatThrownBy(() -> fixture.service.downloadReviewVersion(fixture.skill, fixture.version)).isInstanceOf(DomainForbiddenException.class);
            }
            fixture.assertNoOutput();
        }
    }

    @Test void bindingReadFailureCannotBecomeNativeDownloadFallback() {
        var binding = repository.provision(KEY, "slark");
        var fixture = new DownloadFixture(Long.parseLong(binding.namespaceId()), binding.namespaceSlug());
        jdbc.execute("ALTER TABLE slark_namespace_binding RENAME TO slark_namespace_binding_unavailable");
        try {
            assertThatThrownBy(() -> fixture.service.downloadReviewVersion(fixture.skill, fixture.version)).isInstanceOf(DataAccessException.class);
            fixture.assertNoOutput();
        } finally { jdbc.execute("ALTER TABLE slark_namespace_binding_unavailable RENAME TO slark_namespace_binding"); }
    }

    @Test void controlledActivationIsAtomicReversibleAndReplaySafe() {
        var binding = repository.provision(KEY, "slark");
        long id = Long.parseLong(binding.namespaceId());
        String request = java.util.UUID.randomUUID().toString();
        assertThat(transition(KEY, id, 0, "ACTIVE", request)).isEqualTo(1L);
        assertThat(repository.findByNamespaceId(id).orElseThrow().provisionState()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM namespace WHERE id=?", String.class, id)).isEqualTo("ACTIVE");
        assertThat(transition(KEY, id, 0, "ACTIVE", request)).isEqualTo(1L);
        assertThatThrownBy(() -> transition(KEY, id, 0, "PREPARED", request)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transition(KEY, id, 0, "PREPARED", java.util.UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transition(new SlarkNamespaceKey("registry", "organization", "project-a"), id, 1,
                "PREPARED", java.util.UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
        for (String sql : new String[]{"UPDATE namespace SET status='FROZEN'", "UPDATE namespace SET slug='changed'",
                "UPDATE slark_namespace_binding SET provision_state='PREPARED'", "DELETE FROM slark_namespace_transition",
                "UPDATE slark_namespace_transition SET reason='tampered'"}) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
        assertThat(transition(KEY, id, 1, "PREPARED", java.util.UUID.randomUUID().toString())).isEqualTo(2L);
        assertThat(repository.findByNamespaceId(id).orElseThrow().provisionState()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT status FROM namespace WHERE id=?", String.class, id)).isEqualTo("FROZEN");
        // A delayed retry of the original activation must not reactivate a stopped namespace.
        assertThat(transition(KEY, id, 0, "ACTIVE", request)).isEqualTo(1L);
        assertThat(repository.findByNamespaceId(id).orElseThrow().provisionState()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_namespace_transition", Integer.class)).isEqualTo(2);
    }

    @Test void controlledActivationSerializesRetriesAndSeparatesAllFourScopes() throws Exception {
        for (String kind : new String[]{"public", "organization", "project", "personal"}) {
            var key = new SlarkNamespaceKey("registry", kind, kind.equals("public") ? "public" : "owner");
            var binding = repository.provision(key, "slark");
            long id = Long.parseLong(binding.namespaceId());
            String request = java.util.UUID.randomUUID().toString();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var jobs = new ArrayList<Callable<Long>>();
                for (int i = 0; i < 8; i++) jobs.add(() -> transition(key, id, 0, "ACTIVE", request));
                for (var result : executor.invokeAll(jobs)) assertThat(result.get()).isEqualTo(1L);
            }
            assertThat(repository.provision(key, "slark").provisionState()).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> transition(key, id, 0, "ACTIVE", java.util.UUID.randomUUID().toString()))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_namespace_transition", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM namespace_member", Integer.class)).isZero();
    }

    @Test void controlledActivationRollsBackAuditAndBothStatesOnFailure() {
        var binding = repository.provision(KEY, "slark");
        long id = Long.parseLong(binding.namespaceId());
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_activation_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test activation fault'; END $$");
        jdbc.execute("CREATE TRIGGER reject_activation_test BEFORE UPDATE ON namespace FOR EACH ROW EXECUTE FUNCTION reject_activation_test()");
        String request = java.util.UUID.randomUUID().toString();
        try {
            assertThatThrownBy(() -> transition(KEY, id, 0, "ACTIVE", request)).isInstanceOf(DataAccessException.class);
            assertThat(repository.findByNamespaceId(id).orElseThrow().provisionState()).isEqualTo("PREPARED");
            assertThat(jdbc.queryForObject("SELECT status FROM namespace WHERE id=?", String.class, id)).isEqualTo("FROZEN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_namespace_transition", Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_activation_test ON namespace"); }
        assertThat(transition(KEY, id, 0, "ACTIVE", request)).isEqualTo(1L);
    }

    @Test void controlledActivationRequiresExplicitOperatorGrantAndRecordsSessionIdentity() {
        jdbc.execute("CREATE ROLE slark_activation_application NOLOGIN");
        jdbc.execute("CREATE ROLE slark_activation_operator NOLOGIN");
        String signature = "public.slark_transition_namespace(text,text,text,bigint,text,bigint,text,text,text,text,text)";
        assertThat(jdbc.queryForObject("SELECT has_function_privilege('slark_activation_application',?,'EXECUTE')", Boolean.class, signature)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('slark_activation_application','public.slark_namespace_transition','INSERT')", Boolean.class)).isFalse();
        var binding = repository.provision(KEY, "slark");
        long id = Long.parseLong(binding.namespaceId());
        jdbc.execute("GRANT EXECUTE ON FUNCTION " + signature + " TO slark_activation_operator");
        for (String role : new String[]{"slark_activation_application", "slark_activation_operator"}) {
            Callable<Long> call = () -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
                try (var statement = connection.createStatement()) { statement.execute("SET SESSION AUTHORIZATION " + role); }
                try (var statement = connection.prepareStatement("SELECT public.slark_transition_namespace(?,?,?,?,?,?,?,?,?,?,?)")) {
                    Object[] values = {KEY.registryId(), KEY.scopeKind(), KEY.ownerId(), id, KEY.namespaceSlug(), 0L,
                            "ACTIVE", java.util.UUID.randomUUID().toString(), "a".repeat(40), "sha256:" + "b".repeat(64), "REQ-20261013-0021 operator acceptance"};
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
                }
            });
            if (role.equals("slark_activation_application")) assertThatThrownBy(call::call).isInstanceOf(DataAccessException.class);
            else assertThatCode(() -> assertThat(call.call()).isEqualTo(1L)).doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForObject("SELECT operator_name FROM slark_namespace_transition", String.class)).isEqualTo("slark_activation_operator");
    }

    private long transition(SlarkNamespaceKey key, long namespaceId, long revision, String target, String request) {
        return jdbc.queryForObject("SELECT slark_transition_namespace(?,?,?,?,?,?,?,?,?,?,?)", Long.class,
                key.registryId(), key.scopeKind(), key.ownerId(), namespaceId, key.namespaceSlug(), revision,
                target, request, "a".repeat(40), "sha256:" + "b".repeat(64), "REQ-20261013-0021 controlled acceptance");
    }

    private static final class DownloadFixture {
        final ObjectStorageService storage = mock(ObjectStorageService.class);
        final SkillRepository skills = mock(SkillRepository.class);
        final SkillVersionStatsRepository stats = mock(SkillVersionStatsRepository.class);
        final SkillFileRepository files = mock(SkillFileRepository.class);
        final org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
        final Skill skill;
        final SkillVersion version;
        final SkillDownloadService service;
        DownloadFixture(long namespaceId, String slug) {
            var namespaces = mock(NamespaceRepository.class);
            var versions = mock(SkillVersionRepository.class);
            var tags = mock(SkillTagRepository.class);
            var visibility = mock(VisibilityChecker.class);
            var namespace = new Namespace(slug, "Skills", "owner");
            org.springframework.test.util.ReflectionTestUtils.setField(namespace, "id", namespaceId);
            namespace.setStatus(NamespaceStatus.FROZEN);
            skill = new Skill(namespaceId, "skill", "owner", SkillVisibility.PUBLIC);
            org.springframework.test.util.ReflectionTestUtils.setField(skill, "id", 10L);
            skill.setStatus(SkillStatus.ACTIVE); skill.setLatestVersionId(20L);
            version = new SkillVersion(10L, "1.0.0", "owner");
            org.springframework.test.util.ReflectionTestUtils.setField(version, "id", 20L);
            version.setStatus(SkillVersionStatus.PUBLISHED); version.setDownloadReady(true);
            when(namespaces.findBySlug(slug)).thenReturn(java.util.Optional.of(namespace));
            when(skills.findByNamespaceIdAndSlug(namespaceId, "skill")).thenReturn(java.util.List.of(skill));
            when(versions.findById(20L)).thenReturn(java.util.Optional.of(version));
            when(versions.findBySkillIdAndVersion(10L, "1.0.0")).thenReturn(java.util.Optional.of(version));
            when(tags.findBySkillIdAndTagName(10L, "latest")).thenReturn(java.util.Optional.of(new SkillTag(10L, "latest", 20L, "owner")));
            when(visibility.canAccess(any(), any(), any())).thenReturn(true);
            service = new SkillDownloadService(namespaces, skills, versions, stats, files, tags, storage,
                    visibility, events, new SkillSlugResolutionService(skills), repository);
        }
        void assertNoOutput() {
            verifyNoInteractions(storage, stats, files, events);
            verify(skills, never()).incrementDownloadCount(any());
        }
    }
}
