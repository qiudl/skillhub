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
@Import({com.iflytek.skillhub.domain.skill.service.SkillPublishService.class,com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser.class,SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,PublicationConcurrencyPgTest.Configuration.class})
class PublicationConcurrencyPgTest {
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
    @Autowired com.iflytek.skillhub.domain.skill.service.SkillPublishService publisher;
    @MockBean com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator validator;
    @MockBean com.iflytek.skillhub.domain.skill.validation.PrePublishValidator precheck;
    long namespaceId,skillId,versionId,taskId,retainedId;
    @BeforeEach void seed() {
        when(validator.validate(anyList())).thenReturn(com.iflytek.skillhub.domain.skill.validation.ValidationResult.pass());
        when(precheck.validate(any())).thenReturn(com.iflytek.skillhub.domain.skill.validation.ValidationResult.pass());
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
    com.iflytek.skillhub.domain.skill.service.SkillPublishService.PublishResult publish(String version) {
        byte[] bytes=("---\nname: candidate\ndescription: Candidate\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true);
    }
    @Test void preloadedVersionCannotReplaceCommittedPublication() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,versionId);
                try {worker.submit(()->jdbc.update("UPDATE skill_version SET status='PUBLISHED' WHERE id=?",versionId)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                publish("1.0.0");
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PUBLISHED");
        verifyNoInteractions(storage);
    }

    void bound() {
        when(slark.can(eq(SlarkAssetAuthorization.Operation.CREATE),eq(namespaceId),isNull(),anyString())).thenReturn(Optional.of(true));
        when(slark.can(eq(SlarkAssetAuthorization.Operation.SUBMIT),eq(namespaceId),anyLong(),anyString())).thenReturn(Optional.of(true));
    }
    com.iflytek.skillhub.domain.skill.service.SkillPublishService.PublishResult boundPublish(String name,String version,Long id) {
        byte[] bytes=("---\nname: "+name+"\ndescription: Candidate\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true,id);
    }
    void history(ReviewTaskStatus status) {
        taskId=tx.execute(s->{var task=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");
            task.setStatus(status);em.persist(task);em.flush();return task.getId();});
    }
    void audit() {tx.executeWithoutResult(s->em.persist(new SecurityAudit(versionId,ScannerType.SKILL_SCANNER,"publish-attempt")));}
    void retry() {tx.executeWithoutResult(s->scanner.retryStoredBundleScan(em.find(SkillVersion.class,versionId),"fixture-bundle","owner"));}
    void result() {scanner.processScanResult("publish-attempt",versionId,ScannerType.SKILL_SCANNER,
            new SecurityScanResponse("publish-scan",SecurityVerdict.SAFE,0,null,List.of(),0.5));}
    void delete() {tx.executeWithoutResult(s->governance.deleteVersion(em.find(Skill.class,skillId),
            em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"candidate-team"));}
    void approve() {reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of());}
    void retained() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isEqualTo(1);
        verifyNoInteractions(storage);
    }
    @ParameterizedTest @ValueSource(strings={"SCANNING","PUBLISHED","YANKED"})
    void preloadedVersionCannotHideProtectedState(String state) throws Exception {
        stale(SkillVersion.class,versionId,"UPDATE skill_version SET status='"+state+"' WHERE id=?");retained();
    }
    @ParameterizedTest @ValueSource(strings={"FROZEN","ARCHIVED"})
    void preloadedNamespaceCannotHideReadOnlyState(String state) throws Exception {
        stale(Namespace.class,namespaceId,"UPDATE namespace SET status='"+state+"' WHERE id=?");retained();
    }
    @Test void preloadedAssetCannotHideArchive() throws Exception {
        stale(Skill.class,skillId,"UPDATE skill SET status='ARCHIVED' WHERE id=?");retained();
    }
    void stale(Class<?> type,long id,String sql) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{em.find(type,id);await(worker.submit(()->jdbc.update(sql,id)));publish("1.0.0");}))
                    .isInstanceOf(DomainBadRequestException.class);
        }
    }
    @Test void approvalFirstPreventsReplacementOfNewlyPublishedVersion() throws Exception {
        jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);history(ReviewTaskStatus.PENDING);
        race(this::approve,()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PUBLISHED");retained();
    }
    @Test void publicationFirstWithdrawsReviewAndWaitingApprovalCannotPublishOldRound() throws Exception {
        jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);history(ReviewTaskStatus.PENDING);
        race(()->publish("3.0.0"),()->assertThatThrownBy(this::approve)
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isZero();
    }
    @Test void retryFirstPreventsReplacementOfScanningVersion() throws Exception {
        jdbc.update("UPDATE skill_version SET status='SCAN_FAILED' WHERE id=?",versionId);audit();
        race(this::retry,()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class));retained();
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("SCANNING");
    }
    @Test void replacementFirstMakesWaitingRetryRejectRemovedVersion() throws Exception {
        jdbc.update("UPDATE skill_version SET status='SCAN_FAILED' WHERE id=?",versionId);audit();
        race(()->publish("1.0.0"),()->assertThatThrownBy(this::retry).isInstanceOf(IllegalStateException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isZero();
    }
    @Test void replacementFirstMakesLateScanResultIgnoreDeletedAudit() throws Exception {
        audit();race(()->publish("1.0.0"),this::result,false);
        assertThat(jdbc.queryForObject("SELECT (deleted_at IS NOT NULL) FROM security_audit WHERE task_id='publish-attempt'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='publish-attempt'",String.class)).isNull();
    }
    @Test void resultFirstRetainsVerdictWhenAuditIsDetachedByReplacement() throws Exception {
        audit();race(this::result,()->publish("1.0.0"),false);
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='publish-attempt'",String.class)).isEqualTo("publish-scan");
        assertThat(jdbc.queryForObject("SELECT (deleted_at IS NOT NULL) FROM security_audit WHERE task_id='publish-attempt'",Boolean.class)).isTrue();
    }
    @Test void deletionFirstAllowsReplacementCoordinateWithoutTouchingDeletedObjectsTwice() throws Exception {
        race(this::delete,()->publish("1.0.0"),false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='1.0.0'",Integer.class,skillId)).isEqualTo(1);
        verify(storage,times(1)).deleteObjects(List.of("packages/"+skillId+"/"+versionId+"/bundle.zip"));
    }
    @Test void publicationFirstMakesDeletionRejectRemovedVersion() throws Exception {
        race(()->publish("1.0.0"),()->assertThatThrownBy(this::delete)
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='1.0.0'",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void concurrentPublicationsPreserveBothNewVersionsAndLatestPointer() throws Exception {
        race(()->publish("3.0.0"),()->publish("4.0.0"),false);
        assertThat(jdbc.queryForList("SELECT version FROM skill_version WHERE skill_id=? ORDER BY version",String.class,skillId))
                .containsExactly("1.0.0","2.0.0","3.0.0","4.0.0");
        assertThat(jdbc.queryForObject("SELECT v.version FROM skill s JOIN skill_version v ON s.latest_version_id=v.id WHERE s.id=?",String.class,skillId)).isEqualTo("4.0.0");
    }
    @Test void boundConcurrentCreationRequiresExplicitTargetAfterFirstCommit() throws Exception {
        bound();race(()->boundPublish("new-candidate","1.0.0",null),()->assertThatThrownBy(()->boundPublish("new-candidate","2.0.0",null))
                .isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=? AND slug='new-candidate'",Integer.class,namespaceId)).isEqualTo(1);
    }
    @Test void revocationDuringNamespaceWaitCannotUseAdminFallback() throws Exception {
        bound();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenAnswer(i->Optional.of(allowed.get()));
        race(()->{em.find(Namespace.class,namespaceId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);allowed.set(false);},
                ()->assertThatThrownBy(()->boundPublish("candidate","1.0.0",skillId)).isInstanceOf(DomainForbiddenException.class));retained();
    }
    @Test void corruptHistoryFailsClosedBeforeStorageWrites() {
        history(ReviewTaskStatus.REJECTED);jdbc.update("UPDATE review_task SET subject_id=999999 WHERE id=?",taskId);
        assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class);retained();
    }
    @Test void settledHistoryRemainsDetachedAfterSuccessfulReplacement() {
        history(ReviewTaskStatus.REJECTED);audit();
        tx.executeWithoutResult(s->{publish("1.0.0");verify(storage,never()).deleteObjects(anyList());});
        assertThat(jdbc.queryForObject("SELECT skill_version_id IS NULL FROM review_task WHERE id=?",Boolean.class,taskId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT subject_version FROM review_task WHERE id=?",String.class,taskId)).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE id=?",String.class,taskId)).isEqualTo("REJECTED");
        verify(storage).deleteObjects(List.of("packages/"+skillId+"/"+versionId+"/bundle.zip"));
    }
    @Test void rollbackRetainsOriginalFilesVersionHistoryAndAudit() {
        history(ReviewTaskStatus.REJECTED);audit();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{publish("1.0.0");em.flush();throw new IllegalStateException("outer failed");})).hasMessage("outer failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE id=?",Integer.class,versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT skill_version_id FROM review_task WHERE id=?",Long.class,taskId)).isEqualTo(versionId);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM security_audit WHERE task_id='publish-attempt'",Boolean.class)).isTrue();
        verify(storage,never()).deleteObjects(anyList());
    }
    @Test void boundRereleaseCannotReadFilesFromStalePublishedSource() throws Exception {
        bound();
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,retainedId);
                await(worker.submit(()->jdbc.update("UPDATE skill_version SET status='YANKED' WHERE id=?",retainedId)));
                publisher.rereleasePublishedVersion(skillId,"2.0.0","3.0.0","owner",Map.of(),true);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        verifyNoInteractions(storage);
    }
    @Test void linkedHistoryMustMatchActualVersionName() {
        history(ReviewTaskStatus.REJECTED);
        jdbc.update("UPDATE review_task SET skill_version='wrong',subject_version='wrong' WHERE id=?",taskId);
        assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class);retained();
    }
    @Test void nativeConcurrentCreationCannotSwitchToNewlyDiscoveredTarget() throws Exception {
        race(()->nativeNewPublish("1.0.0"),()->assertThatThrownBy(()->nativeNewPublish("2.0.0")).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=? AND slug='native-new'",Integer.class,namespaceId)).isEqualTo(1);
    }
    void nativeNewPublish(String version) {boundPublish("native-new",version,null);}

    @Test void createRevocationDuringNamespaceWaitFailsClosed() throws Exception {
        bound();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(slark.can(SlarkAssetAuthorization.Operation.CREATE,namespaceId,null,"owner")).thenAnswer(i->Optional.of(allowed.get()));
        var checked=new CountDownLatch(1);
        when(slark.can(SlarkAssetAuthorization.Operation.CREATE,namespaceId,null,"owner")).thenAnswer(i->{
            boolean decision=allowed.get();checked.countDown();return Optional.of(decision);
        });
        var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var locked=new CountDownLatch(1);
            var holder=workers.submit(()->tx.executeWithoutResult(s->{
                em.find(Namespace.class,namespaceId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var request=workers.submit(()->assertThatThrownBy(()->boundPublish("candidate","1.0.0",skillId)).isInstanceOf(DomainForbiddenException.class));
            try {assertThat(checked.await(5,TimeUnit.SECONDS)).isTrue();allowed.set(false);} finally {release.countDown();}
            await(holder);await(request);
        }
        retained();
    }
    @Test void newlyCommittedReviewHistoryCannotBeLockedAfterNamespace() throws Exception {
        race(()->{
            em.find(Namespace.class,namespaceId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            var task=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");em.persist(task);
        },()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class));
        retained();assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isEqualTo(1);
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
