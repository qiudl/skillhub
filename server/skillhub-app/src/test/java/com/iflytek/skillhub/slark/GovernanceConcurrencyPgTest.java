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
@Import({com.iflytek.skillhub.search.postgres.PostgresFullTextIndexService.class,com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService.class,com.iflytek.skillhub.service.SkillDeleteAppService.class,com.iflytek.skillhub.domain.skill.service.SkillReviewSubmitService.class,com.iflytek.skillhub.domain.skill.service.SkillPublishService.class,com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser.class,SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,GovernanceConcurrencyPgTest.Configuration.class})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GovernanceConcurrencyPgTest {
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
    @Autowired com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService deletion;
    @Autowired com.iflytek.skillhub.service.SkillDeleteAppService deleteApp;
    @Autowired com.iflytek.skillhub.search.SearchIndexService search;
    @MockBean com.iflytek.skillhub.search.SearchEmbeddingService embedding;
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
    void govern(String action) {
        switch(action) {
            case "hide" -> governance.hideSkill(skillId,"owner",null,null,"reason");
            case "unhide" -> governance.unhideSkill(skillId,"owner",null,null);
            case "archive" -> governance.archiveSkill(skillId,"owner",Map.of(),null,null,"reason");
            case "unarchive" -> governance.unarchiveSkill(skillId,"owner",Map.of(),null,null);
            case "adminArchive" -> governance.archiveSkillAsAdmin(skillId,"reviewer",null,null,"reason");
            case "yank" -> governance.yankVersion(em.find(Skill.class,skillId),em.find(SkillVersion.class,retainedId),"owner",Map.of(),null,null,"reason");
            case "adminYank" -> governance.yankVersion(retainedId,"reviewer",null,null,"reason");
            case "delete" -> deletion.hardDeleteSkill(em.find(Skill.class,skillId),"candidate-team","owner",null,null);
            default -> throw new IllegalArgumentException(action);
        }
    }
    @ParameterizedTest @ValueSource(strings={"hide","unhide","archive","unarchive","adminArchive"})
    void staleAssetCannotOverwriteCommittedMetadata(String action) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s->{
                em.find(Skill.class,skillId);
                await(worker.submit(()->jdbc.update("UPDATE skill SET summary='committed',latest_version_id=? WHERE id=?",retainedId,skillId)));
                govern(action);em.flush();
            });
        }
        assertThat(jdbc.queryForObject("SELECT summary FROM skill WHERE id=?",String.class,skillId)).isEqualTo("committed");
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isEqualTo(retainedId);
    }
    @ParameterizedTest @ValueSource(strings={"yank","adminYank"})
    void staleVersionCannotOverwriteCommittedFileStatistics(String action) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,retainedId);
                await(worker.submit(()->jdbc.update("UPDATE skill_version SET file_count=42 WHERE id=?",retainedId)));
                govern(action);em.flush();
            });
        }
        assertThat(jdbc.queryForObject("SELECT file_count FROM skill_version WHERE id=?",Integer.class,retainedId)).isEqualTo(42);
    }
    void history() {taskId=tx.execute(s->{var t=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");em.persist(t);em.flush();return t.getId();});}
    void audit() {tx.executeWithoutResult(s->em.persist(new SecurityAudit(versionId,ScannerType.SKILL_SCANNER,"submit-attempt")));}
    void delete() {tx.executeWithoutResult(s->governance.deleteVersion(em.find(Skill.class,skillId),em.find(SkillVersion.class,versionId),"owner",Map.of(),null,null,"candidate-team"));}
    void retry() {tx.executeWithoutResult(s->scanner.retryStoredBundleScan(em.find(SkillVersion.class,versionId),"fixture-bundle","owner"));}
    void result() {scanner.processScanResult("submit-attempt",versionId,ScannerType.SKILL_SCANNER,new SecurityScanResponse("submit-scan",SecurityVerdict.SAFE,0,null,List.of(),0.5));}
    com.iflytek.skillhub.domain.skill.service.SkillPublishService.PublishResult publish(String version) {
        byte[] bytes=("---\nname: candidate\ndescription: Candidate\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true);
    }
    @ParameterizedTest @ValueSource(strings={"hide","unhide","archive","unarchive","adminArchive","yank","adminYank","delete"})
    void frozenSpaceBlocksEveryMutation(String action) {
        jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?",namespaceId);
        assertThatThrownBy(()->tx.executeWithoutResult(s->govern(action))).isInstanceOf(DomainBadRequestException.class);
        assertAssetPresent();assertThat(jdbc.queryForObject("SELECT status FROM skill WHERE id=?",String.class,skillId)).isEqualTo("ACTIVE");
    }
    @ParameterizedTest @ValueSource(strings={"hide","unhide","archive","unarchive","adminArchive","yank","adminYank","delete"})
    void liveRevocationBlocksEveryMutation(String action) {
        when(slark.can(any(),eq(namespaceId),eq(skillId),anyString())).thenReturn(Optional.of(false));
        assertThatThrownBy(()->tx.executeWithoutResult(s->govern(action))).isInstanceOf(DomainForbiddenException.class);
        assertAssetPresent();verify(storage,never()).deleteObjects(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"archive","unarchive","delete"})
    void boundMaintainerNeedNotBeNativeOwner(String action) {
        when(slark.can(any(),eq(namespaceId),eq(skillId),anyString())).thenReturn(Optional.of(true));
        jdbc.update("UPDATE skill SET owner_id='reviewer' WHERE id=?",skillId);
        tx.executeWithoutResult(s->{
            if(action.equals("delete")) deleteApp.deleteSkillByIdFromPortal(skillId,principal("owner"),null);
            else govern(action);
        });
        if(action.equals("delete")) assertAssetDeleted();else assertAssetPresent();
    }
    com.iflytek.skillhub.auth.rbac.PlatformPrincipal principal(String user) {
        return new com.iflytek.skillhub.auth.rbac.PlatformPrincipal(user,user,null,"","session",Set.of("USER"));
    }
    @Test void portalChecksOwnerAfterRefresh() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(Skill.class,skillId);
                await(worker.submit(()->jdbc.update("UPDATE skill SET owner_id='reviewer' WHERE id=?",skillId)));
                deleteApp.deleteSkillByIdFromPortal(skillId,principal("owner"),null);
            })).isInstanceOf(DomainForbiddenException.class);
        }
        assertAssetPresent();verify(storage,never()).deleteObjects(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"hide","archive","yank","delete"})
    void renamedPreloadedAssetCannotBeMutated(String action) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(Skill.class,skillId);await(worker.submit(()->jdbc.update("UPDATE skill SET slug='renamed' WHERE id=?",skillId)));
                govern(action);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertAssetPresent();
    }
    @ParameterizedTest @ValueSource(strings={"yank","adminYank"})
    void stalePublishedVersionCannotBeYankedTwice(String action) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{em.find(SkillVersion.class,retainedId);
                await(worker.submit(()->jdbc.update("UPDATE skill_version SET status='YANKED' WHERE id=?",retainedId)));govern(action);
            })).isInstanceOf(DomainBadRequestException.class);
        }
    }
    @Test void archiveFirstMakesWaitingSubmissionFail() throws Exception {
        race(()->govern("archive"),()->assertThatThrownBy(()->reviews.submitReview(versionId,"owner",Map.of(),Set.of())).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isZero();
    }
    @Test void submissionFirstThenArchivePreservesPendingRound() throws Exception {
        race(()->reviews.submitReview(versionId,"owner",Map.of(),Set.of()),()->govern("archive"),false);
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,versionId)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void publicationFirstThenHidePreservesNewMetadata() throws Exception {
        race(()->publish("3.0.0"),()->govern("hide"),false);
        assertThat(jdbc.queryForObject("SELECT summary FROM skill WHERE id=?",String.class,skillId)).isEqualTo("Candidate");
        assertThat(jdbc.queryForObject("SELECT hidden FROM skill WHERE id=?",Boolean.class,skillId)).isTrue();
    }
    @Test void archiveFirstRejectsWaitingPublication() throws Exception {
        race(()->govern("archive"),()->assertThatThrownBy(()->publish("3.0.0")).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(2);
    }
    @Test void hardDeleteFirstMakesWaitingSubmissionFailWithoutOrphans() throws Exception {
        race(()->govern("delete"),()->assertThatThrownBy(()->reviews.submitReview(versionId,"owner",Map.of(),Set.of())).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class));
        assertAssetDeleted();
    }
    @Test void submissionFirstMakesDeleteRejectNewUnseenHistory() throws Exception {
        race(()->reviews.submitReview(versionId,"owner",Map.of(),Set.of()),()->assertThatThrownBy(()->govern("delete")).isInstanceOf(DomainBadRequestException.class));
        assertAssetPresent();assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void hardDeleteWaitsForVersionDeletion() throws Exception {
        race(this::delete,()->govern("delete"),false);assertAssetDeleted();
    }
    @Test void hardDeleteWaitsForReplacementAndDeletesCurrentVersionSet() throws Exception {
        race(()->publish("1.0.0"),()->govern("delete"),false);assertAssetDeleted();
    }
    @Test void hardDeleteFirstRejectsWaitingReplacement() throws Exception {
        race(()->govern("delete"),()->assertThatThrownBy(()->publish("1.0.0")).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class));
        assertAssetDeleted();
    }
    @Test void hardDeleteWaitsForReviewDecision() throws Exception {
        history();jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);
        race(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of()),()->govern("delete"),false);
        assertAssetDeleted();
    }
    @Test void scanFirstThenHardDeleteRemovesAudit() throws Exception {
        audit();race(this::result,()->govern("delete"),false);assertAssetDeleted();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE skill_version_id=?",Integer.class,versionId)).isZero();
    }
    @Test void hardDeleteFirstAllowsLateScanCallbackToExit() throws Exception {
        audit();race(()->govern("delete"),this::result,false);assertAssetDeleted();
    }
    @Test void hardDeleteFirstAllowsLateFailureCallbackToExit() throws Exception {
        audit();race(()->govern("delete"),()->scanner.processScanFailure("submit-attempt",versionId,ScannerType.SKILL_SCANNER,"late"),false);assertAssetDeleted();
    }
    @Test void twoHardDeletesCannotRunStorageCleanupTwice() throws Exception {
        race(()->govern("delete"),()->assertThatThrownBy(()->govern("delete")).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class));
        assertAssetDeleted();verify(storage,times(1)).deleteObjects(anyList());
    }
    @Test void deleteRollbackPreservesDataStorageAndSearch() {
        history();audit();searchDocument();
        tx.executeWithoutResult(s->{deleteApp.deleteSkillByIdFromPortal(skillId,principal("owner"),null);verify(storage,never()).deleteObjects(anyList());assertThat(searchCount()).isZero();s.setRollbackOnly();});
        assertAssetPresent();assertThat(searchCount()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isEqualTo(1);
        verify(storage,never()).deleteObjects(anyList());
    }
    @Test void deleteCommitRemovesTransactionalSearchAndStorageAfterCommit() {
        searchDocument();
        tx.executeWithoutResult(s->{deleteApp.deleteSkillByIdFromPortal(skillId,principal("owner"),null);verify(storage,never()).deleteObjects(anyList());});
        assertAssetDeleted();verify(storage,times(1)).deleteObjects(anyList());assertThat(searchCount()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"archive","yank","delete"})
    void permissionIsReadAfterWaitingForAssetLock(String action) throws Exception {
        when(slark.can(any(),eq(namespaceId),eq(skillId),anyString())).thenReturn(Optional.of(true));
        race(()->{
            em.find(Skill.class,skillId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            when(slark.can(any(),eq(namespaceId),eq(skillId),anyString())).thenReturn(Optional.of(false));
        },()->assertThatThrownBy(()->govern(action)).isInstanceOf(DomainForbiddenException.class));
        assertAssetPresent();
    }
    @Test void archiveFirstMakesWaitingApprovalFailPreservingPending() throws Exception {
        history();jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);
        race(()->govern("archive"),()->assertThatThrownBy(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of())).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE id=?",String.class,taskId)).isEqualTo("PENDING");
    }
    @Test void approvalFirstThenArchivePreservesPublishedPointer() throws Exception {
        history();jdbc.update("UPDATE skill_version SET status='PENDING_REVIEW' WHERE id=?",versionId);
        race(()->reviews.approveReview(taskId,"reviewer","approve",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of()),()->govern("archive"),false);
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isEqualTo(versionId);
    }
    @Test void doubleYankCannotEmitSecondSuccessfulMutation() throws Exception {
        race(()->govern("yank"),()->assertThatThrownBy(()->govern("adminYank")).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,retainedId)).isEqualTo("YANKED");
    }
    @Test void hardDeleteRejectsMismatchedHistoryBeforeAnyCleanup() {
        history();jdbc.update("UPDATE review_task SET subject_version='foreign' WHERE id=?",taskId);
        assertThatThrownBy(()->tx.executeWithoutResult(s->govern("delete"))).isInstanceOf(DomainBadRequestException.class);
        assertAssetPresent();verify(storage,never()).deleteObjects(anyList());
    }
    @Test void unknownAttemptStillFailsWhenVersionExists() {
        assertThatThrownBy(()->scanner.processScanResult("unknown",versionId,ScannerType.SKILL_SCANNER,new SecurityScanResponse("unknown",SecurityVerdict.SAFE,0,null,List.of(),0.1))).isInstanceOf(IllegalStateException.class);
    }
    @Test void pendingPromotionRequiresDedicatedRevocationPath() {
        tx.executeWithoutResult(t->em.persist(new PromotionRequest(skillId,retainedId,namespaceId,"owner")));
        assertThatThrownBy(()->tx.executeWithoutResult(t->govern("delete"))).isInstanceOf(DomainBadRequestException.class);
        assertAssetPresent();verify(storage,never()).deleteObjects(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"hide","archive","yank","delete"})
    void outerRollbackUndoesGovernanceMutation(String action) {
        tx.executeWithoutResult(t->{govern(action);t.setRollbackOnly();});
        assertAssetPresent();assertThat(jdbc.queryForObject("SELECT status FROM skill WHERE id=?",String.class,skillId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT hidden FROM skill WHERE id=?",Boolean.class,skillId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,retainedId)).isEqualTo("PUBLISHED");
        verify(storage,never()).deleteObjects(anyList());
    }
    void searchDocument(){jdbc.update("INSERT INTO skill_search_document(skill_id,namespace_id,namespace_slug,owner_id,visibility,status) VALUES(?,?,'candidate-team','owner','PUBLIC','ACTIVE')",skillId,namespaceId);}
    int searchCount(){return jdbc.queryForObject("SELECT count(*) FROM skill_search_document WHERE skill_id=?",Integer.class,skillId);}
    void assertAssetPresent(){assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,skillId)).isEqualTo(1);}
    void assertAssetDeleted(){assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,skillId)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isZero();}
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
