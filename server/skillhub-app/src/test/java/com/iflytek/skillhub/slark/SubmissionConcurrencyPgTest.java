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
@Import({com.iflytek.skillhub.domain.skill.service.SkillReviewSubmitService.class,com.iflytek.skillhub.domain.skill.service.SkillPublishService.class,com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser.class,SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,SubmissionConcurrencyPgTest.Configuration.class})
class SubmissionConcurrencyPgTest {
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
        @Bean CommittedEvents committedEvents(){return new CommittedEvents();}
    }
    static class CommittedEvents {
        final java.util.List<com.iflytek.skillhub.domain.event.SkillPublishedEvent> publications=new java.util.concurrent.CopyOnWriteArrayList<>();
        @org.springframework.transaction.event.TransactionalEventListener
        public void published(com.iflytek.skillhub.domain.event.SkillPublishedEvent event){publications.add(event);}
    }
    @Autowired CommittedEvents committedEvents;
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
    @Autowired com.iflytek.skillhub.domain.skill.service.SkillReviewSubmitService submissions;
    long namespaceId,skillId,versionId,taskId,retainedId;
    @BeforeEach void seed() {
        committedEvents.publications.clear();
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
    void action(String action) {
        switch(action) {
            case "modern" -> reviews.submitReview(versionId,"owner",Map.of(),Set.of());
            case "legacy" -> reviews.submitReview(versionId,"owner",Map.of(namespaceId,NamespaceRole.MEMBER));
            case "lifecycle" -> submissions.submitForReview(skillId,versionId,SkillVisibility.PUBLIC,"owner",Map.of());
            case "confirm" -> submissions.confirmPublish(skillId,versionId,"owner",Map.of());
            default -> throw new IllegalArgumentException(action);
        }
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void preloadedVersionCannotOverwriteCommittedPublication(String action) throws Exception {
        jdbc.update("UPDATE skill SET visibility='PRIVATE' WHERE id=?",skillId);
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,versionId);
                try {worker.submit(()->jdbc.update("UPDATE skill_version SET status='PUBLISHED' WHERE id=?",versionId)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                action(action);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isZero();
    }
    void privateAsset() {jdbc.update("UPDATE skill SET visibility='PRIVATE' WHERE id=?",skillId);}
    String state() {return jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId);}
    int pending() {return jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_version_id=? AND status='PENDING'",Integer.class,versionId);}
    void history() {taskId=tx.execute(s->{var t=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");em.persist(t);em.flush();return t.getId();});}
    void audit() {tx.executeWithoutResult(s->em.persist(new SecurityAudit(versionId,ScannerType.SKILL_SCANNER,"submit-attempt")));}
    void delete() {tx.executeWithoutResult(s->governance.deleteVersion(em.find(Skill.class,skillId),em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"candidate-team"));}
    void retry() {tx.executeWithoutResult(s->scanner.retryStoredBundleScan(em.find(SkillVersion.class,versionId),"fixture-bundle","owner"));}
    void result() {scanner.processScanResult("submit-attempt",versionId,ScannerType.SKILL_SCANNER,new SecurityScanResponse("submit-scan",SecurityVerdict.SAFE,0,null,List.of(),0.5));}
    com.iflytek.skillhub.domain.skill.service.SkillPublishService.PublishResult publish(String version) {
        byte[] bytes=("---\nname: candidate\ndescription: Candidate\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"modern,SCANNING","legacy,YANKED","lifecycle,REJECTED","confirm,SCAN_FAILED","confirm,PENDING_REVIEW"})
    void staleVersionCannotHideProtectedStatus(String action,String status) throws Exception {
        privateAsset();stale(action,SkillVersion.class,versionId,"UPDATE skill_version SET status='"+status+"' WHERE id=?");
        assertThat(state()).isEqualTo(status);assertThat(pending()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void staleFrozenNamespaceCannotPermitWrite(String action) throws Exception {
        privateAsset();stale(action,Namespace.class,namespaceId,"UPDATE namespace SET status='FROZEN' WHERE id=?");
        assertThat(state()).isEqualTo("UPLOADED");assertThat(pending()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void staleArchivedAssetCannotPermitWrite(String action) throws Exception {
        privateAsset();stale(action,Skill.class,skillId,"UPDATE skill SET status='ARCHIVED' WHERE id=?");
        assertThat(state()).isEqualTo("UPLOADED");assertThat(pending()).isZero();
    }
    @Test void staleArchivedNamespaceCannotPermitConfirmation() throws Exception {
        privateAsset();stale("confirm",Namespace.class,namespaceId,"UPDATE namespace SET status='ARCHIVED' WHERE id=?");
        assertThat(state()).isEqualTo("UPLOADED");
    }
    @Test void stalePrivateVisibilityCannotPermitConfirmation() throws Exception {
        privateAsset();stale("confirm",Skill.class,skillId,"UPDATE skill SET visibility='PUBLIC' WHERE id=?");
        assertThat(state()).isEqualTo("UPLOADED");
    }
    @Test void renamedVersionCannotBeSubmittedFromStaleCandidate() throws Exception {
        stale("lifecycle",SkillVersion.class,versionId,"UPDATE skill_version SET version='renamed' WHERE id=?");assertThat(pending()).isZero();
    }
    @Test void renamedAssetCannotBeSubmittedFromStaleCandidate() throws Exception {
        stale("modern",Skill.class,skillId,"UPDATE skill SET slug='renamed' WHERE id=?");assertThat(pending()).isZero();
    }
    void stale(String action,Class<?> type,long id,String sql) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{em.find(type,id);await(worker.submit(()->jdbc.update(sql,id)));action(action);}))
                    .isInstanceOf(DomainBadRequestException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void pendingRoundCannotBeBypassedWhenVersionStateIsInconsistent(String action) {
        privateAsset();history();assertThatThrownBy(()->action(action)).isInstanceOf(DomainBadRequestException.class);
        assertThat(state()).isEqualTo("UPLOADED");assertThat(pending()).isEqualTo(1);
    }
    @Test void typedPendingRoundCannotBeBypassedWhenLegacyReferenceIsDetached() {
        privateAsset();history();jdbc.update("UPDATE review_task SET skill_version_id=NULL WHERE id=?",taskId);
        assertThatThrownBy(()->action("confirm")).isInstanceOf(DomainBadRequestException.class);assertThat(state()).isEqualTo("UPLOADED");
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"modern,legacy","legacy,lifecycle","lifecycle,modern"})
    void competingSubmissionEntriesCreateExactlyOneRound(String first,String second) throws Exception {
        race(()->action(first),()->assertThatThrownBy(()->action(second)).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo("PENDING_REVIEW");assertThat(pending()).isEqualTo(1);
    }
    @Test void concurrentConfirmationsPublishExactlyOnce() throws Exception {
        privateAsset();race(()->action("confirm"),()->assertThatThrownBy(()->action("confirm")).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo("PUBLISHED");assertThat(pending()).isZero();
        assertThat(committedEvents.publications).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isEqualTo(versionId);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"confirm,lifecycle","lifecycle,confirm"})
    void confirmationAndSubmissionCannotBothSucceed(String first,String second) throws Exception {
        privateAsset();race(()->action(first),()->assertThatThrownBy(()->action(second)).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo(first.equals("confirm")?"PUBLISHED":"PENDING_REVIEW");
    }
    @ParameterizedTest @ValueSource(strings={"modern","confirm"})
    void retryFirstPreventsStaleSubmissionOrConfirmation(String action) throws Exception {
        privateAsset();jdbc.update("UPDATE skill_version SET status='SCAN_FAILED' WHERE id=?",versionId);audit();
        race(this::retry,()->assertThatThrownBy(()->action(action)).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo("SCANNING");assertThat(pending()).isZero();
    }
    @Test void completedPrivateScanAllowsWaitingConfirmation() throws Exception {
        privateAsset();jdbc.update("UPDATE skill_version SET status='SCANNING',requested_visibility='PRIVATE' WHERE id=?",versionId);audit();
        race(this::result,()->action("confirm"),false);assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT scan_id FROM security_audit WHERE task_id='submit-attempt'",String.class)).isEqualTo("submit-scan");
    }
    @Test void lateScanCallbackCannotOverwriteConfirmedPublication() throws Exception {
        privateAsset();audit();race(()->action("confirm"),this::result,false);assertThat(state()).isEqualTo("PUBLISHED");
    }
    @ParameterizedTest @ValueSource(strings={"modern","confirm"})
    void deletionFirstMakesWaitingSubmissionRejectMissingVersion(String action) throws Exception {
        privateAsset();race(this::delete,()->assertThatThrownBy(()->action(action))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"lifecycle","confirm"})
    void submissionFirstMakesWaitingDeletionRejectProtectedVersion(String action) throws Exception {
        privateAsset();race(()->action(action),()->assertThatThrownBy(this::delete).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo(action.equals("confirm")?"PUBLISHED":"PENDING_REVIEW");
    }
    @ParameterizedTest @ValueSource(strings={"modern","confirm"})
    void replacementFirstMakesWaitingSubmissionRejectRemovedCandidate(String action) throws Exception {
        privateAsset();race(()->publish("1.0.0"),()->assertThatThrownBy(()->action(action))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException.class));
    }
    @Test void submissionFirstMakesWaitingReplacementRejectNewReviewHistory() throws Exception {
        race(()->action("modern"),()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class));
        assertThat(state()).isEqualTo("PENDING_REVIEW");assertThat(pending()).isEqualTo(1);
    }
    @Test void confirmationFirstMakesWaitingReplacementPreservePublishedVersion() throws Exception {
        privateAsset();race(()->action("confirm"),()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(DomainBadRequestException.class));assertThat(state()).isEqualTo("PUBLISHED");
    }
    @Test void approvalFirstPreventsSecondSubmission() throws Exception {
        jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);history();
        race(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of()),
                ()->assertThatThrownBy(()->action("lifecycle")).isInstanceOf(DomainBadRequestException.class));assertThat(state()).isEqualTo("PUBLISHED");
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void currentSlarkRevocationCannotFallBackToNativeOwner(String action) throws Exception {
        privateAsset();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenAnswer(i->Optional.of(allowed.get()));
        race(()->{em.find(SkillVersion.class,versionId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);allowed.set(false);},
                ()->assertThatThrownBy(()->action(action)).isInstanceOf(DomainForbiddenException.class));assertThat(state()).isEqualTo("UPLOADED");
    }
    @Test void authorityFailureCannotUseNativeOwnerFallback() {
        privateAsset();when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(()->action("confirm")).hasMessage("offline");assertThat(state()).isEqualTo("UPLOADED");
    }
    @Test void crossAssetVersionPairIsRejected() {
        long other=tx.execute(s->{var skill=new Skill(namespaceId,"other","owner",SkillVisibility.PRIVATE);em.persist(skill);em.flush();return skill.getId();});
        assertThatThrownBy(()->submissions.confirmPublish(other,versionId,"owner",Map.of())).isInstanceOf(DomainBadRequestException.class);assertThat(state()).isEqualTo("UPLOADED");
    }
    @ParameterizedTest @ValueSource(strings={"modern","legacy","lifecycle","confirm"})
    void outerFailureRollsBackVersionPointerAndNewReview(String action) {
        privateAsset();assertThatThrownBy(()->tx.executeWithoutResult(s->{action(action);em.flush();throw new IllegalStateException("outer audit failed");})).hasMessage("outer audit failed");
        assertThat(state()).isEqualTo("UPLOADED");assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isNull();
        assertThat(committedEvents.publications).isEmpty();
    }
    @Test void sameTransactionUploadAndConfirmRetainsPublicationArtifacts() {
        // Bundle performs these two operations in one transaction, with scanning disabled for this native fixture.
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"enabled",false);
        try {
            long created=tx.execute(s->{
                em.persist(new NamespaceMember(namespaceId,"owner",NamespaceRole.OWNER));em.flush();
                byte[] bytes="---\nname: candidate\ndescription: Private candidate\nversion: 3.0.0\n---\nBody".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var result=publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PRIVATE,Set.of(),true);
                submissions.confirmPublish(skillId,result.version().getId(),"owner",Map.of());return result.version().getId();
            });
            assertThat(jdbc.queryForObject("SELECT file_count FROM skill_version WHERE id=?",Integer.class,created)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT bundle_ready AND download_ready FROM skill_version WHERE id=?",Boolean.class,created)).isTrue();
            assertThat(jdbc.queryForObject("SELECT summary FROM skill WHERE id=?",String.class,skillId)).isEqualTo("Private candidate");
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(scanner,"enabled",true);}
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
