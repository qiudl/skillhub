package com.iflytek.skillhub.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.UserAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** REQ-20261013-0021: durable scan results and retries share the version transaction lock. */
@DataJpaTest(properties = {"skillhub.security.scanner.enabled=true", "skillhub.security.scanner.mode=upload"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SecurityScanService.class, ScanAttemptPgTest.Configuration.class})
class ScanAttemptPgTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @TestConfiguration static class Configuration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @MockBean ScanTaskProducer producer;
    @Autowired SecurityScanService scans;
    @Autowired SecurityAuditRepository audits;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;
    long versionId, auditId;
    static final String TASK = "attempt-first";
    static final ScannerType TYPE = ScannerType.SKILL_SCANNER;

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE user_account,namespace,security_audit,scan_task_outbox CASCADE");
        tx.executeWithoutResult(s -> {
            em.persist(new UserAccount("owner", "Owner", null, null));
            var ns = new Namespace("scan-team", "Scan", "owner"); em.persist(ns); em.flush();
            var skill = new Skill(ns.getId(), "scan-candidate", "owner", SkillVisibility.PUBLIC);
            em.persist(skill); em.flush();
            var version = new SkillVersion(skill.getId(), "1.0.0", "owner");
            version.setStatus(SkillVersionStatus.SCANNING); version.setRequestedVisibility(SkillVisibility.PUBLIC);
            em.persist(version); em.flush(); versionId = version.getId();
            var audit = new SecurityAudit(versionId, TYPE, TASK); em.persist(audit); em.flush(); auditId = audit.getId();
        });
    }
    void result(String task, SecurityVerdict verdict) {
        scans.processScanResult(task, versionId, TYPE,
                new SecurityScanResponse("scan-" + verdict, verdict, 0, null, List.of(), 0.5));
    }
    void failure() { scans.processScanFailure(TASK, versionId, TYPE, "scanner unavailable"); }
    String status() { return jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?", String.class, versionId); }
    String auditRow() { return jdbc.queryForObject("SELECT row_to_json(a)::text FROM security_audit a WHERE id=?", String.class, auditId); }
    long auditCount() { return jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE skill_version_id=?", Long.class, versionId); }
    ScanTask retry() { return tx.execute(s -> scans.retryStoredBundleScan(em.find(SkillVersion.class, versionId), "fixture-bundle", "owner")); }

    @Test void duplicateResultPreservesTheFirstCommittedVerdictAndTimestamp() {
        result(TASK, SecurityVerdict.SAFE); String first = auditRow();
        result(TASK, SecurityVerdict.DANGEROUS);
        assertThat(auditRow()).isEqualTo(first);
        assertThat(status()).isEqualTo("PENDING_REVIEW");
    }
    @Test void lateResultCannotRewriteACommittedFailure() {
        failure(); String first = auditRow(); result(TASK, SecurityVerdict.SAFE);
        assertThat(auditRow()).isEqualTo(first); assertThat(status()).isEqualTo("SCAN_FAILED");
    }
    @Test void lateFailureCannotRewriteACommittedResult() {
        result(TASK, SecurityVerdict.SAFE); String first = auditRow(); failure();
        assertThat(auditRow()).isEqualTo(first); assertThat(status()).isEqualTo("PENDING_REVIEW");
    }
    @Test void oldAttemptMayCompleteItsAuditButCannotCompleteANewerAttempt() {
        tx.executeWithoutResult(s -> em.persist(new SecurityAudit(versionId, TYPE, "attempt-new")));
        result(TASK, SecurityVerdict.SAFE);
        assertThat(status()).isEqualTo("SCANNING");
        assertThat(audits.findByTaskId(TASK).orElseThrow().getScannedAt()).isNotNull();
        assertThat(audits.findByTaskId("attempt-new").orElseThrow().getScannedAt()).isNull();
    }
    @ParameterizedTest @CsvSource({"SCANNING,UPLOADED", "UPLOADED,PUBLISHED"})
    void staleVersionCannotOverwriteACommittedState(String before, String after) throws Exception {
        jdbc.update("UPDATE skill_version SET status=? WHERE id=?", before, versionId);
        try (var worker = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s -> {
                em.find(SkillVersion.class, versionId);
                await(worker.submit(() -> jdbc.update("UPDATE skill_version SET status=? WHERE id=?", after, versionId)));
                result(TASK, SecurityVerdict.SAFE);
            });
        }
        assertThat(status()).isEqualTo(after);
    }
    @Test void staleAuditCannotHideACommittedFailure() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s -> {
                em.find(SecurityAudit.class, auditId); await(worker.submit(this::failure));
                String first = auditRow(); result(TASK, SecurityVerdict.SAFE);
                em.flush(); assertThat(auditRow()).isEqualTo(first);
            });
        }
    }
    @Test void staleRetryCannotStartASecondAttemptAfterAnotherRetryCommitted() throws Exception {
        failure();
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                SkillVersion stale = em.find(SkillVersion.class, versionId);
                await(worker.submit(this::retry));
                scans.retryStoredBundleScan(stale, "fixture-bundle", "owner");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("SCAN_FAILED");
        }
        assertThat(auditCount()).isEqualTo(2);
        assertThat(status()).isEqualTo("SCANNING");
    }
    @Test void matchingTimestampUsesTheNewerAttemptId() {
        tx.executeWithoutResult(s -> em.persist(new SecurityAudit(versionId, TYPE, "attempt-new")));
        jdbc.update("UPDATE security_audit SET created_at='2026-01-01T00:00:00Z' WHERE skill_version_id=?", versionId);
        result(TASK, SecurityVerdict.SAFE);
        assertThat(status()).isEqualTo("SCANNING");
    }
    @Test void deletedAttemptIsNotRewritten() {
        jdbc.update("UPDATE security_audit SET deleted_at=now() WHERE id=?", auditId);
        String first = auditRow(); result(TASK, SecurityVerdict.SAFE); failure();
        assertThat(auditRow()).isEqualTo(first); assertThat(status()).isEqualTo("SCANNING");
    }
    @Test void deletedAttemptRemainsIgnoredAfterItsVersionIsRemoved() {
        jdbc.update("UPDATE security_audit SET deleted_at=now() WHERE id=?", auditId);
        jdbc.update("DELETE FROM skill_version WHERE id=?", versionId);
        String first = auditRow(); result(TASK, SecurityVerdict.SAFE); failure();
        assertThat(auditRow()).isEqualTo(first);
    }
    @Test void missingVersionFailureStillSettlesItsAudit() {
        jdbc.update("DELETE FROM skill_version WHERE id=?", versionId);
        failure();
        SecurityAudit persisted = audits.findByTaskId(TASK).orElseThrow();
        assertThat(persisted.getFailureReason()).isEqualTo("scanner unavailable");
        assertThat(persisted.getScannedAt()).isNotNull();
    }
    @Test void mismatchedCoordinatesDoNotWriteAnything() {
        String first = auditRow();
        assertThatThrownBy(() -> scans.processScanFailure(TASK, versionId, ScannerType.CUSTOM, "wrong scanner"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scans.processScanResult(TASK, versionId + 100, TYPE,
                new SecurityScanResponse("wrong-version", SecurityVerdict.SAFE, 0, null, List.of(), 0.1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auditRow()).isEqualTo(first); assertThat(status()).isEqualTo("SCANNING");
    }
    @Test void privateSuccessRemainsUploaded() {
        jdbc.update("UPDATE skill_version SET requested_visibility='PRIVATE' WHERE id=?", versionId);
        result(TASK, SecurityVerdict.SAFE); assertThat(status()).isEqualTo("UPLOADED");
    }
    @Test void laterTransactionFailureRollsBackAuditAndVersion() {
        String first = auditRow();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            result(TASK, SecurityVerdict.SAFE); throw new IllegalStateException("audit fault");
        })).hasMessage("audit fault");
        assertThat(auditRow()).isEqualTo(first); assertThat(status()).isEqualTo("SCANNING");
    }
    @Test void waitingResultRechecksTheCommittedFailure() throws Exception {
        var written = new CountDownLatch(1); var release = new CountDownLatch(1); var readerPid = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() -> tx.executeWithoutResult(s -> {
                failure(); em.flush(); written.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            var reader = workers.submit(() -> tx.executeWithoutResult(s -> {
                readerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); result(TASK, SecurityVerdict.SAFE);
            }));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')", Boolean.class, readerPid.get()));
                    if (waiting) break; Thread.sleep(20);
                }
                assertThat(waiting).as("result waits for the actual row transaction").isTrue();
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS);
        }
        SecurityAudit persisted = audits.findByTaskId(TASK).orElseThrow();
        assertThat(persisted.getFailureReason()).isEqualTo("scanner unavailable");
        assertThat(persisted.getScanId()).isNull();
        assertThat(persisted.getIsSafe()).isFalse();
        assertThat(status()).isEqualTo("SCAN_FAILED");
    }
    static <T> T await(Future<T> future) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
