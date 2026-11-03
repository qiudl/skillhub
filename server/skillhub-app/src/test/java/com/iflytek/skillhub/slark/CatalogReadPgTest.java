package com.iflytek.skillhub.slark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.service.SlarkCatalogService;
import com.iflytek.skillhub.storage.ObjectStorageService;

import jakarta.persistence.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

/**
 * Real Flyway/JPA row locks and bytes. ACTIVE binding is a mocked future-state fact; the real
 * PREPARED SQL trigger path is independently asserted and never relaxed.
 */
@DataJpaTest(
        properties = {
            "skillhub.auth.slark.enabled=true",
            "skillhub.auth.slark.distribution-enabled=true"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SlarkCatalogService.class, CatalogReadPgTest.Configuration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogReadPgTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        SlarkDistributionSettings settings() {
            return new SlarkDistributionSettings("registry", "d".repeat(40));
        }
    }

    @MockBean SlarkNamespaceBindingRepository bindings;
    @MockBean ObjectStorageService storage;
    @Autowired SlarkCatalogService service;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @PersistenceContext EntityManager em;
    SlarkNamespaceKey key;
    long nsId, skillId, versionId;
    byte[] bytes;

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE user_account,namespace,security_audit CASCADE");
        key = new SlarkNamespaceKey("registry", "personal", "u1");
        bytes =
                "---\nname: candidate\ndescription: Test\nversion: 1.0.0\n---\nBody"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        tx.executeWithoutResult(
                t -> {
                    em.persist(new UserAccount("owner", "Owner", null, null));
                    em.persist(new IdentityBinding("owner", "slark", "u1", null));
                    var ns = new Namespace(key.namespaceSlug(), "Personal", "owner");
                    em.persist(ns);
                    em.flush();
                    nsId = ns.getId();
                    var skill = new Skill(nsId, "candidate", "owner", SkillVisibility.PRIVATE);
                    em.persist(skill);
                    em.flush();
                    skillId = skill.getId();
                    var version = new SkillVersion(skillId, "1.0.0", "owner");
                    version.setStatus(SkillVersionStatus.UPLOADED);
                    em.persist(version);
                    em.flush();
                    versionId = version.getId();
                    em.persist(
                            new SkillFile(
                                    versionId,
                                    "SKILL.md",
                                    (long) bytes.length,
                                    "text/markdown",
                                    digest,
                                    "object"));
                    var scan = new SecurityAudit(versionId, ScannerType.SKILL_SCANNER, "current");
                    scan.setVerdict(SecurityVerdict.SAFE);
                    scan.setIsSafe(true);
                    scan.setScannedAt(Instant.now());
                    em.persist(scan);
                });
        when(bindings.findByNamespaceId(nsId))
                .thenReturn(
                        Optional.of(
                                new SlarkNamespaceBinding(
                                        "registry",
                                        "personal",
                                        "u1",
                                        Long.toString(nsId),
                                        key.namespaceSlug(),
                                        "ACTIVE")));
        when(storage.getObject("object")).thenAnswer(i -> new ByteArrayInputStream(bytes));
    }

    SlarkCatalogService.Request request(Long after, String q, int limit) {
        return new SlarkCatalogService.Request(key, nsId, "u1", after, q, limit);
    }

    @Test
    void personalMetadataHasExactIdsAndNeverTouchesStorageOrChangesRows() {
        var page = service.list(request(null, "", 20));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().skillId()).isEqualTo(Long.toString(skillId));
        assertThat(page.items().getFirst().versionId()).isEqualTo(Long.toString(versionId));
        assertThat(page.nextAfterSkillId()).isNull();
        assertThat(service.list(request(skillId, "", 20)).items()).isEmpty();
        assertThat(service.list(request(null, "candidate", 20)).items()).hasSize(1);
        assertThat(service.list(request(null, "%", 20)).items()).isEmpty();
        assertThat(service.list(request(null, "other", 20)).items()).isEmpty();
        verifyNoInteractions(storage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void paginationIsKeysetBoundedAndMetadataTextHasABudget() {
        for (int i = 0; i < 3; i++) {
            final int n = i;
            tx.executeWithoutResult(
                    t -> {
                        var skill =
                                new Skill(nsId, "candidate-" + n, "owner", SkillVisibility.PRIVATE);
                        skill.setSummary("界".repeat(3000));
                        em.persist(skill);
                        em.flush();
                        var version = new SkillVersion(skill.getId(), "1.0." + n, "owner");
                        version.setStatus(SkillVersionStatus.UPLOADED);
                        em.persist(version);
                    });
        }
        var first = service.list(request(null, "", 2));
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextAfterSkillId()).isEqualTo(first.items().getLast().skillId());
        var second = service.list(request(Long.valueOf(first.nextAfterSkillId()), "", 2));
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextAfterSkillId()).isNull();
        assertThat(second.items().getFirst().summary()).hasSize(1024);
    }

    @Test
    void unauthorizedOwnerVisibilityAndLifecycleNeverLeakMetadata() throws Exception {
        assertThatThrownBy(
                        () ->
                                service.list(
                                        new SlarkCatalogService.Request(
                                                key, nsId, "u2", null, "", 20)))
                .isInstanceOf(SlarkCatalogService.Denied.class);
        for (String mutation :
                List.of(
                        "UPDATE skill SET hidden=true",
                        "UPDATE skill SET status='ARCHIVED'",
                        "UPDATE skill_version SET status='YANKED'",
                        "UPDATE user_account SET status='DISABLED'",
                        "UPDATE identity_binding SET subject='u2'",
                        "UPDATE skill SET visibility='PUBLIC'")) {
            jdbc.update(mutation);
            assertThat(service.list(request(null, "", 20)).items()).isEmpty();
            seed();
        }
        jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?", nsId);
        assertThatThrownBy(() -> service.list(request(null, "", 20)))
                .isInstanceOf(SlarkCatalogService.Denied.class);
    }

    @Test
    void preparedBindingStillFailsAndActualActivationGuardsRemain() {
        var actual =
                new com.iflytek.skillhub.infra.jpa.SlarkNamespaceBindingJdbcRepository(
                        jdbc, new DataSourceTransactionManager(ds));
        var k = new SlarkNamespaceKey("registry", "personal", "prepared-owner");
        var prepared = actual.provision(k, "slark");
        long id = Long.parseLong(prepared.namespaceId());
        when(bindings.findByNamespaceId(id)).thenReturn(Optional.of(prepared));
        assertThatThrownBy(
                        () ->
                                service.list(
                                        new SlarkCatalogService.Request(
                                                k, id, "prepared-owner", null, "", 20)))
                .isInstanceOf(SlarkCatalogService.Denied.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE namespace SET status='ACTIVE' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void nonPersonalScopesRequireTheirPublishedVersionAndExactVisibility() {
        for (String kind : List.of("public", "organization", "project")) {
            String owner = kind.equals("public") ? "public" : "scope-owner";
            var k = new SlarkNamespaceKey("registry", kind, owner);
            long id =
                    tx.execute(
                            t -> {
                                var ns = new Namespace(k.namespaceSlug(), "Scope", "owner");
                                ns.setType(
                                        kind.equals("public")
                                                ? NamespaceType.GLOBAL
                                                : NamespaceType.TEAM);
                                em.persist(ns);
                                em.flush();
                                return ns.getId();
                            });
            when(bindings.findByNamespaceId(id))
                    .thenReturn(
                            Optional.of(
                                    new SlarkNamespaceBinding(
                                            "registry",
                                            kind,
                                            owner,
                                            Long.toString(id),
                                            k.namespaceSlug(),
                                            "ACTIVE")));
            long[] ids =
                    tx.execute(
                            t -> {
                                var skill =
                                        new Skill(
                                                id,
                                                "shared",
                                                "owner",
                                                kind.equals("public")
                                                        ? SkillVisibility.PUBLIC
                                                        : SkillVisibility.NAMESPACE_ONLY);
                                em.persist(skill);
                                em.flush();
                                var version = new SkillVersion(skill.getId(), "2.0.0", "owner");
                                version.setStatus(SkillVersionStatus.PUBLISHED);
                                em.persist(version);
                                em.flush();
                                skill.setLatestVersionId(version.getId());
                                return new long[] {skill.getId(), version.getId()};
                            });
            var req =
                    new SlarkCatalogService.Request(
                            k, id, kind.equals("public") ? null : "u1", null, "", 20);
            assertThat(service.list(req).items()).hasSize(1);
            jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?", ids[1]);
            assertThat(service.list(req).items()).isEmpty();
            jdbc.update("UPDATE skill_version SET status='PUBLISHED' WHERE id=?", ids[1]);
            jdbc.update("UPDATE skill SET visibility='PRIVATE' WHERE id=?", ids[0]);
            assertThat(service.list(req).items()).isEmpty();
        }
    }
}
