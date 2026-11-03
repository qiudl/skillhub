package com.iflytek.skillhub.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.governance.GovernanceNotificationService;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.*;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillGovernanceService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Real Flyway/JPA/PostgreSQL decision transactions, including preloaded persistence contexts. */
@DataJpaTest(properties={"skillhub.security.scanner.enabled=true", "skillhub.security.scanner.mode=upload"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,VersionDeletionPgTest.Configuration.class})
class VersionDeletionPgTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword); r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled",()->true); r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @TestConfiguration static class Configuration {
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
    }
    // This persistence slice exercises native authorization; M110 separately covers real Slark HTTP facts.
    @MockBean SlarkAssetAuthorization slark;
    @MockBean com.iflytek.skillhub.storage.ObjectStorageService storage;
    @MockBean com.iflytek.skillhub.domain.audit.AuditLogService audits;
    @Autowired SecurityScanService scanner;
    @MockBean ScanTaskProducer producer;
    @MockBean com.iflytek.skillhub.domain.skill.service.SkillStorageDeletionCompensationService compensation;
    @MockBean GovernanceNotificationService notifications;
    @MockBean com.iflytek.skillhub.repository.GovernanceQueryRepository queries;
    @MockBean com.iflytek.skillhub.repository.ReviewProgressQueryRepository progress;
    @MockBean com.iflytek.skillhub.auth.rbac.RbacService rbac;
    @MockBean com.iflytek.skillhub.observability.RequestIdAccessor requestId;
    @MockBean com.iflytek.skillhub.domain.suite.SkillSuiteLifecycleService suites;
    @Autowired ReviewService reviews;
    @Autowired SkillGovernanceService governance;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;
    long namespaceId,skillId,versionId,taskId,retainedId;
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE user_account,namespace,security_audit,scan_task_outbox CASCADE");
        tx.executeWithoutResult(s->{
            em.persist(new UserAccount("owner","Owner",null,null));
            em.persist(new UserAccount("reviewer","Reviewer",null,null));
            var ns=new Namespace("candidate-team","Candidate","owner");em.persist(ns);em.flush();namespaceId=ns.getId();
            var skill=new Skill(namespaceId,"candidate","owner",SkillVisibility.PUBLIC);em.persist(skill);em.flush();skillId=skill.getId();
            var version=new SkillVersion(skillId,"1.0.0","owner");version.setStatus(SkillVersionStatus.UPLOADED);em.persist(version);em.flush();versionId=version.getId();
            var retained=new SkillVersion(skillId,"2.0.0","owner");retained.setStatus(SkillVersionStatus.PUBLISHED);em.persist(retained);em.flush();retainedId=retained.getId();
        });
    }
    void delete() { tx.executeWithoutResult(s -> governance.deleteVersion(em.find(Skill.class,skillId),
            em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"candidate-team")); }
    @Test void preloadedVersionCannotHideCommittedPublication() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,versionId);
                try {worker.submit(()->jdbc.update("UPDATE skill_version SET status='PUBLISHED' WHERE id=?",versionId)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                delete();
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PUBLISHED");
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    void delete(long id, String actor, Map<Long,NamespaceRole> roles) {
        tx.executeWithoutResult(s -> governance.deleteVersion(em.find(Skill.class,skillId),
                em.find(SkillVersion.class,id),actor,roles,null,null,"candidate-team"));
    }
    void retained() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isEqualTo(1);
        verifyNoInteractions(storage);
    }
    void history(ReviewTaskStatus status) {
        taskId=tx.execute(s->{var task=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");
            task.setStatus(status);em.persist(task);em.flush();return task.getId();});
    }
    void audit() {
        tx.executeWithoutResult(s->em.persist(new SecurityAudit(versionId,ScannerType.SKILL_SCANNER,"delete-attempt")));
    }
    void result() { scanner.processScanResult("delete-attempt",versionId,ScannerType.SKILL_SCANNER,
            new SecurityScanResponse("delete-scan",SecurityVerdict.SAFE,0,null,List.of(),0.5)); }
    void retry() { tx.executeWithoutResult(s->scanner.retryStoredBundleScan(em.find(SkillVersion.class,versionId),"fixture-bundle","owner")); }
    @Test void nativeOwnerDeletesAndCleansOnlyAfterCommit() {
        tx.executeWithoutResult(s->{delete();verifyNoInteractions(storage);});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isZero();
        verify(storage).deleteObjects(List.of("packages/"+skillId+"/"+versionId+"/bundle.zip"));
    }
    @Test void rejectedHistoryAndFilesAreDeletedButScanHistoryIsRetained() {
        jdbc.update("UPDATE skill_version SET status='REJECTED' WHERE id=?",versionId);
        history(ReviewTaskStatus.REJECTED);audit();
        tx.executeWithoutResult(s->em.persist(new SkillFile(versionId,"SKILL.md",1L,"text/markdown","sha","candidate-key")));
        delete();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_file WHERE version_id=?",Integer.class,versionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT (deleted_at IS NOT NULL) FROM security_audit WHERE task_id='delete-attempt'",Boolean.class)).isTrue();
        result();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='delete-attempt'",String.class)).isNull();
    }
    @Test void corruptReviewHistoryCannotBeDeleted() {
        history(ReviewTaskStatus.REJECTED);
        jdbc.update("UPDATE review_task SET subject_version='other' WHERE id=?",taskId);
        assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class);retained();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isEqualTo(1);
    }
    @Test void pendingHistoryCannotBeDeletedEvenWhenVersionStatusIsInconsistent() {
        history(ReviewTaskStatus.PENDING);
        assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class);retained();
    }
    @Test void revokedOwnerCannotUseLocalNamespaceAdminFallback() {
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenReturn(Optional.of(false));
        assertThatThrownBy(()->delete(versionId,"owner",Map.of(namespaceId,NamespaceRole.ADMIN)))
                .isInstanceOf(DomainForbiddenException.class);retained();
    }
    @Test void delegatedMaintainerCanDeleteWithoutLocalOwnership() {
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"reviewer")).thenReturn(Optional.of(true));
        delete(versionId,"reviewer",Map.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isZero();
    }
    @Test void authorityFailureCannotUseNativeFallback() {
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(this::delete).hasMessage("offline");retained();
    }
    @Test void nativeNonOwnerCannotDelete() {
        assertThatThrownBy(()->delete(versionId,"reviewer",Map.of())).isInstanceOf(DomainForbiddenException.class);retained();
    }
    @ParameterizedTest @ValueSource(strings={"FROZEN","ARCHIVED"})
    void preloadedNamespaceCannotHideStatusChange(String status) throws Exception {
        stale(Namespace.class,namespaceId,"UPDATE namespace SET status='"+status+"' WHERE id=?");retained();
    }
    @Test void preloadedAssetCannotHideArchive() throws Exception {
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenReturn(Optional.of(true));
        stale(Skill.class,skillId,"UPDATE skill SET status='ARCHIVED' WHERE id=?");retained();
    }
    @ParameterizedTest @ValueSource(strings={"SCANNING","PENDING_REVIEW","PUBLISHED","YANKED"})
    void preloadedVersionCannotHideNonDeletableStatus(String status) throws Exception {
        stale(SkillVersion.class,versionId,"UPDATE skill_version SET status='"+status+"' WHERE id=?");retained();
    }
    void stale(Class<?> type,long id,String sql) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(type,id);await(worker.submit(()->jdbc.update(sql,id)));delete();
            })).isInstanceOf(DomainBadRequestException.class);
        }
    }
    @Test void foreignAssetVersionPairIsRejected() {
        long foreign=tx.execute(s->{var skill=new Skill(namespaceId,"foreign","owner",SkillVisibility.PUBLIC);em.persist(skill);em.flush();return skill.getId();});
        assertThatThrownBy(()->tx.executeWithoutResult(s->governance.deleteVersion(em.find(Skill.class,foreign),
                em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"candidate-team")))
                .isInstanceOf(DomainBadRequestException.class);retained();
    }
    @Test void incorrectNamespaceCoordinateIsRejected() {
        assertThatThrownBy(()->tx.executeWithoutResult(s->governance.deleteVersion(em.find(Skill.class,skillId),
                em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"other-team")))
                .isInstanceOf(DomainBadRequestException.class);retained();
    }
    @Test void outerAuditFailureRestoresDatabaseAndLeavesObjects() {
        history(ReviewTaskStatus.REJECTED);audit();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{delete();em.flush();throw new IllegalStateException("outer audit failed");}))
                .hasMessage("outer audit failed");retained();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT (deleted_at IS NOT NULL) FROM security_audit WHERE task_id='delete-attempt'",Boolean.class)).isFalse();
    }
    @Test void twoConcurrentDeletionsKeepLastVersion() throws Exception {
        jdbc.update("UPDATE skill_version SET status='UPLOADED' WHERE skill_id=?",skillId);
        race(this::delete,()->{
            assertThatThrownBy(()->delete(retainedId,"owner",Map.of())).isInstanceOf(DomainBadRequestException.class);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void retryFirstMakesWaitingDeletionFailWithoutRemovingAttempt() throws Exception {
        jdbc.update("UPDATE skill_version SET status='SCAN_FAILED' WHERE id=?",versionId);audit();
        race(this::retry,()->assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("SCANNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE skill_version_id=? AND deleted_at IS NULL",Integer.class,versionId)).isEqualTo(2);
        verifyNoInteractions(storage);
    }
    @Test void deletionFirstMakesWaitingRetryFailWithoutNewAttempt() throws Exception {
        jdbc.update("UPDATE skill_version SET status='SCAN_FAILED' WHERE id=?",versionId);audit();
        race(this::delete,()->assertThatThrownBy(this::retry).isInstanceOf(IllegalStateException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE skill_version_id=?",Integer.class,versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scan_task_outbox WHERE version_id=?",Integer.class,versionId)).isZero();
    }
    @Test void resultFirstMakesWaitingDeletionObservePendingReview() throws Exception {
        jdbc.update("UPDATE skill_version SET status='SCANNING' WHERE id=?",versionId);audit();
        race(this::result,()->assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class));retained();
    }
    @Test void deletionFirstMakesWaitingResultIgnoreDeletedAudit() throws Exception {
        audit();race(this::delete,this::result,false);
        assertThat(jdbc.queryForObject("SELECT (deleted_at IS NOT NULL) FROM security_audit WHERE task_id='delete-attempt'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='delete-attempt'",String.class)).isNull();
    }
    @Test void preloadedScanAuditCannotLoseCommittedVerdictDuringDeletion() throws Exception {
        audit();
        try(var worker=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s->{
                em.createQuery("from SecurityAudit",SecurityAudit.class).getResultList();
                await(worker.submit(this::result));delete();
            });
        }
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='delete-attempt'",String.class)).isEqualTo("delete-scan");
    }
    @Test void approvalFirstMakesWaitingDeletionPreservePublishedCandidate() throws Exception {
        jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);history(ReviewTaskStatus.PENDING);
        race(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of()),
                ()->assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PUBLISHED");retained();
    }
    @Test void deletionFirstMakesWaitingReviewRejectMissingHistory() throws Exception {
        history(ReviewTaskStatus.REJECTED);
        race(this::delete,()->assertThatThrownBy(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of()))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException.class));
    }
    static <T> T await(Future<T> result) {
        try{return result.get(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
    }
    void race(Runnable first,Runnable second) throws Exception {
        race(first,second,true);
    }
    void race(Runnable first,Runnable second,boolean deniedSecondTransaction) throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var winner=workers.submit(()->tx.executeWithoutResult(s->{
                first.run();em.flush();changed.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(changed.await(10,TimeUnit.SECONDS)).isTrue();
            var loser=workers.submit(()->tx.executeWithoutResult(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));second.run();}));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
                while(System.nanoTime()<deadline){
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid.get()));
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("second transaction waits on PostgreSQL row lock").isTrue();
            } finally {release.countDown();}
            await(winner);
            try {await(loser);}catch(RuntimeException e){
                // A denied nested REQUIRED service marks its enclosing transaction rollback-only.
                if (!deniedSecondTransaction || !(e.getCause() instanceof ExecutionException ex && ex.getCause() instanceof org.springframework.transaction.UnexpectedRollbackException)) throw e;
            }
        }
    }
}
