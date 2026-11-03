package com.iflytek.skillhub.slark;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import com.iflytek.skillhub.infra.jpa.SlarkAssetMaintainerJdbcRepository;
import com.iflytek.skillhub.infra.jpa.SlarkNamespaceBindingJdbcRepository;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL identity, audit and transaction boundaries; no repository substitutes. */
@Testcontainers
class SlarkAssetMaintainerPgTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static SlarkAssetMaintainerJdbcRepository maintainers;
    static SlarkNamespaceBindingJdbcRepository bindings;
    long namespaceId;
    long skillId;
    @BeforeAll static void initialize() {
        source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        maintainers = new SlarkAssetMaintainerJdbcRepository(jdbc);
        bindings = new SlarkNamespaceBindingJdbcRepository(jdbc, manager);
    }
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE slark_asset_maintainer_event,user_account,namespace CASCADE");
        jdbc.update("INSERT INTO user_account(id,display_name) VALUES ('local','Human'),('admin-local','Admin'),('other-local','Other')");
        jdbc.update("INSERT INTO identity_binding(user_id,provider_code,subject) VALUES ('local','slark','subject'),('admin-local','slark','admin-subject'),('other-local','slark','other-subject')");
        namespaceId = Long.parseLong(bindings.provision(new SlarkNamespaceKey("registry", "project", "project"), "slark").namespaceId());
        var bound = bindings.findByNamespaceId(namespaceId).orElseThrow();
        assertThat(bound.scopeKind()).isEqualTo("project");
        assertThat(bound.ownerId()).isEqualTo("project");
        assertThat(bound.provisionState()).isEqualTo("PREPARED");
        skillId = newSkill();
    }
    long newSkill() {
        return jdbc.queryForObject("INSERT INTO skill(namespace_id,slug,owner_id) VALUES (?,'asset-'||nextval('skill_id_seq'),'local') RETURNING id", Long.class, namespaceId);
    }
    void initial() { tx.executeWithoutResult(s -> maintainers.initializeCreator(namespaceId, skillId, "subject", "local")); }
    void active(boolean value) { tx.executeWithoutResult(s -> maintainers.setActive(namespaceId, skillId, "subject", "local", value, "admin-subject", "admin-local")); }
    int events() { return jdbc.queryForObject("SELECT count(*) FROM slark_asset_maintainer_event", Integer.class); }

    @Test void concurrentInitializationGrantsOnceAndCannotRestoreRevokedCreator() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<Void>>();
            for (int i=0; i<16; i++) jobs.add(() -> {initial(); return null;});
            for (var result : executor.invokeAll(jobs)) result.get();
        }
        assertThat(maintainers.isActive(namespaceId, skillId, "subject", "local")).isTrue();
        assertThat(events()).isEqualTo(1);
        active(false); initial(); active(false);
        assertThat(maintainers.isActive(namespaceId, skillId, "subject", "local")).isFalse();
        assertThat(events()).isEqualTo(2);
        active(true);
        assertThat(maintainers.isActive(namespaceId, skillId, "subject", "local")).isTrue();
        assertThat(jdbc.queryForList("SELECT revision FROM slark_asset_maintainer_event ORDER BY id", Long.class)).containsExactly(0L,1L,2L);
        assertThat(jdbc.queryForList("SELECT actor_subject FROM slark_asset_maintainer_event ORDER BY id", String.class)).containsExactly("subject","admin-subject","admin-subject");
    }
    @Test void assetCreatorAndAuditRollbackTogetherAndWritesRequireCallerTransaction() {
        assertThatThrownBy(() -> maintainers.initializeCreator(namespaceId, skillId, "subject", "local")).isInstanceOf(IllegalStateException.class);
        long[] created = {0};
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            created[0] = newSkill();
            maintainers.initializeCreator(namespaceId, created[0], "subject", "local");
            throw new IllegalStateException("fault after creator registration");
        })).hasMessage("fault after creator registration");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?", Integer.class, created[0])).isZero();
        assertThat(events()).isZero();
        assertThat(maintainers.isActive(namespaceId, created[0], "subject", "local")).isFalse();
    }
    @Test void coordinatesAndMappedSubjectsCannotBeSubstitutedOrPhysicallyRevoked() {
        initial();
        long otherNamespace = Long.parseLong(bindings.provision(new SlarkNamespaceKey("registry", "project", "other"), "slark").namespaceId());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> maintainers.setActive(otherNamespace, skillId, "subject", "local", true,"admin-subject","admin-local"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> maintainers.setActive(namespaceId, skillId, "subject", "other-local", true,"admin-subject","admin-local"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> maintainers.setActive(namespaceId, skillId, "subject", "local", false,"admin-subject","other-local"))).isInstanceOf(DataAccessException.class);
        for (String sql : new String[]{"UPDATE slark_asset_maintainer SET subject='other-subject'", "UPDATE slark_asset_maintainer SET namespace_id="+otherNamespace,
                "UPDATE slark_asset_maintainer SET active=false", "DELETE FROM slark_asset_maintainer", "UPDATE slark_asset_maintainer_event SET active=false", "DELETE FROM slark_asset_maintainer_event"}) {
            assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(DataAccessException.class);
        }
        assertThat(events()).isEqualTo(1);
        assertThat(maintainers.isActive(otherNamespace, skillId, "subject", "local")).isFalse();
        assertThat(maintainers.isActive(namespaceId, skillId, "subject", "other-local")).isFalse();
    }
    @Test void existingVersionsOrNativeAssetsCannotAcquireInitialSlarkCreatorRights() {
        jdbc.update("INSERT INTO skill_version(skill_id,version,status) VALUES (?,'1.0.0','UPLOADED')", skillId);
        assertThatThrownBy(this::initial).isInstanceOf(IllegalStateException.class);
        long nativeNamespace = jdbc.queryForObject("INSERT INTO namespace(slug,display_name,type,status) VALUES ('native','Native','TEAM','ACTIVE') RETURNING id", Long.class);
        assertThat(bindings.findByNamespaceId(nativeNamespace)).isEmpty();
        long nativeSkill = jdbc.queryForObject("INSERT INTO skill(namespace_id,slug,owner_id) VALUES (?,'native','local') RETURNING id", Long.class, nativeNamespace);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> maintainers.initializeCreator(nativeNamespace, nativeSkill,"subject","local"))).isInstanceOf(DataAccessException.class);
        assertThat(events()).isZero();
    }
    @Test void changedExternalIdentityStopsOldGrantAndHardDeleteRetainsAudit() {
        initial();
        jdbc.update("UPDATE identity_binding SET user_id='other-local' WHERE provider_code='slark' AND subject='subject'");
        assertThat(maintainers.isActive(namespaceId, skillId, "subject", "local")).isFalse();
        jdbc.update("DELETE FROM skill WHERE id=?", skillId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slark_asset_maintainer", Integer.class)).isZero();
        assertThat(events()).isEqualTo(1);
    }
    @Test void auditFailureCannotLeaveGrantOrRevocationCommitted() {
        jdbc.execute("CREATE FUNCTION reject_m110_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test fault'; END $$");
        jdbc.execute("CREATE TRIGGER reject_m110_audit BEFORE INSERT ON slark_asset_maintainer_event FOR EACH ROW EXECUTE FUNCTION reject_m110_audit()");
        try {
            assertThatThrownBy(this::initial).isInstanceOf(DataAccessException.class);
            assertThat(maintainers.isActive(namespaceId,skillId,"subject","local")).isFalse();
            assertThat(events()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_m110_audit ON slark_asset_maintainer_event");
            jdbc.execute("DROP FUNCTION reject_m110_audit()");
        }
        initial(); assertThat(events()).isEqualTo(1);
        jdbc.execute("CREATE FUNCTION reject_m110_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test fault'; END $$");
        jdbc.execute("CREATE TRIGGER reject_m110_audit BEFORE INSERT ON slark_asset_maintainer_event FOR EACH ROW EXECUTE FUNCTION reject_m110_audit()");
        try {
            assertThatThrownBy(() -> active(false)).isInstanceOf(DataAccessException.class);
            assertThat(maintainers.isActive(namespaceId,skillId,"subject","local")).isTrue();
            assertThat(events()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER reject_m110_audit ON slark_asset_maintainer_event");
            jdbc.execute("DROP FUNCTION reject_m110_audit()");
        }
    }
    @Test void rollbackRefusesBusinessDataAndEmptyRollbackCanBeReapplied() throws Exception {
        String sql = Files.readString(Path.of("../../scripts/rollback-slark-asset-maintainers.sql"));
        initial();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(java.sql.SQLException.class);
            statement.execute("ROLLBACK");
        }
        assertThat(events()).isEqualTo(1);
        jdbc.execute("TRUNCATE slark_asset_maintainer,slark_asset_maintainer_event");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
        assertThat(jdbc.queryForObject("SELECT to_regclass('slark_asset_maintainer')::text", String.class)).isNull();
        try (var stream = getClass().getResourceAsStream("/db/migration/V69__slark_asset_maintainers.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        initial(); assertThat(events()).isEqualTo(1);
    }
}
