package com.iflytek.skillhub.slark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.service.SlarkDistributionService;
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
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * Real Flyway/JPA row locks and bytes with actual controlled namespace activation.
 * Object storage remains a byte fixture; PREPARED and stopped spaces cannot distribute.
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
@Import({SlarkDistributionService.class, DistributionReadPgTest.Configuration.class,
        com.iflytek.skillhub.infra.jpa.SlarkNamespaceBindingJdbcRepository.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DistributionReadPgTest {
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

    @Autowired SlarkNamespaceBindingRepository bindings;
    @MockBean ObjectStorageService storage;
    @Autowired SlarkDistributionService service;
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
                    var prepared = bindings.provision(key, "slark");
                    nsId = Long.parseLong(prepared.namespaceId());
                    transition(0, "ACTIVE");
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
        when(storage.getObject("object")).thenAnswer(i -> new ByteArrayInputStream(bytes));
    }

    SlarkDistributionService.Request request() {
        return new SlarkDistributionService.Request(key, nsId, skillId, versionId, "u1");
    }

    private void transition(long expectedRevision, String state) {
        jdbc.queryForObject("SELECT public.slark_transition_namespace(?,?,?,?,?,?,?,?,?,?,?)", Long.class,
                key.registryId(), key.scopeKind(), key.ownerId(), nsId, key.namespaceSlug(), expectedRevision,
                state, UUID.randomUUID().toString(), "a".repeat(40), "sha256:" + "b".repeat(64),
                "REQ-20261013-0021 distribution acceptance");
    }

    @Test
    void controlledStopImmediatelyDeniesDistributionBeforeObjectStorage() {
        assertThat(service.read(request()).bytes()).isNotEmpty();
        clearInvocations(storage);
        transition(1, "PREPARED");
        assertThatThrownBy(() -> service.read(request())).isInstanceOf(SlarkDistributionService.Denied.class);
        verifyNoInteractions(storage);
    }

    @Test
    void currentArchiveDoesNotChangeVersionOrScan() {
        var result = service.read(request());
        assertThat(result.bytes()).isNotEmpty();
        assertThat(result.snapshot().scanTaskId()).isEqualTo("current");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM skill_version WHERE id=?",
                                String.class,
                                versionId))
                .isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sourceVersionLockIsHeldDuringObjectRead() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new AtomicInteger();
        when(storage.getObject("object"))
                .thenAnswer(
                        i -> {
                            reading.countDown();
                            if (!release.await(2, TimeUnit.SECONDS))
                                throw new IllegalStateException();
                            return new ByteArrayInputStream(bytes);
                        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var read = workers.submit(() -> service.read(request()));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            var update =
                    workers.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            t -> {
                                                pid.set(
                                                        jdbc.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Integer.class));
                                                jdbc.update(
                                                        "UPDATE skill_version SET status='YANKED'"
                                                            + " WHERE id=?",
                                                        versionId);
                                            }));
            boolean waiting = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime() < deadline) {
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                                + " pid=? AND wait_event_type='Lock')",
                                            Boolean.class,
                                            pid.get()));
                    if (waiting) break;
                    Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(read.get(5, TimeUnit.SECONDS).snapshot().versionStatus())
                    .isEqualTo("UPLOADED");
            update.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM skill_version WHERE id=?",
                                String.class,
                                versionId))
                .isEqualTo("YANKED");
    }

    @Test
    void staleYankedSourceIsRefreshedBeforeIO() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            t -> {
                                                em.find(SkillVersion.class, versionId);
                                                try {
                                                    worker.submit(
                                                                    () ->
                                                                            jdbc.update(
                                                                                    "UPDATE"
                                                                                        + " skill_version"
                                                                                        + " SET status='YANKED'"
                                                                                        + " WHERE"
                                                                                        + " id=?",
                                                                                    versionId))
                                                            .get();
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                                service.read(request());
                                            }))
                    .isInstanceOf(SlarkDistributionService.Denied.class);
        }
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void currentPreparedBindingAndDatabaseActivationGuardsStillReject() {
        var actual =
                new com.iflytek.skillhub.infra.jpa.SlarkNamespaceBindingJdbcRepository(
                        jdbc, new DataSourceTransactionManager(ds));
        var preparedKey = new SlarkNamespaceKey("registry", "personal", "prepared-owner");
        var prepared = actual.provision(preparedKey, "slark");
        long id = Long.parseLong(prepared.namespaceId());
        assertThatThrownBy(
                        () ->
                                service.read(
                                        new SlarkDistributionService.Request(
                                                preparedKey,
                                                id,
                                                skillId,
                                                versionId,
                                                "prepared-owner")))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).getObject(anyString());
        assertThatThrownBy(() -> jdbc.update("UPDATE namespace SET status='ACTIVE' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE slark_namespace_binding SET"
                                            + " provision_state='ACTIVE' WHERE namespace_id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"account", "identity"})
    void ownerRevocationDuringIOCannotReturnEvidence(String mutation) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            when(storage.getObject("object"))
                    .thenAnswer(
                            i -> {
                                worker.submit(
                                                () ->
                                                        jdbc.update(
                                                                mutation.equals("account")
                                                                        ? "UPDATE user_account SET"
                                                                              + " status='DISABLED'"
                                                                              + " WHERE id='owner'"
                                                                        : "UPDATE identity_binding"
                                                                              + " SET subject='u2'"
                                                                              + " WHERE"
                                                                              + " user_id='owner'"))
                                        .get(2, TimeUnit.SECONDS);
                                return new ByteArrayInputStream(bytes);
                            });
            assertThatThrownBy(() -> service.read(request()))
                    .isInstanceOf(SlarkDistributionService.Denied.class);
        }
    }

    @Test
    void objectFailureRollsBackWithoutChangingTheSource() {
        when(storage.getObject("object")).thenThrow(new IllegalStateException("private failure"));
        assertThatThrownBy(() -> service.read(request()))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version", Integer.class))
                .isEqualTo(1);
        verify(storage, never()).deleteObjects(anyList());
    }
}
