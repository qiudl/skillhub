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
@Import({com.iflytek.skillhub.search.postgres.PostgresFullTextIndexService.class,com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService.class,com.iflytek.skillhub.service.SkillDeleteAppService.class,com.iflytek.skillhub.domain.skill.service.SkillReviewSubmitService.class,com.iflytek.skillhub.domain.skill.service.SkillPublishService.class,com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser.class,SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,RereleaseConcurrencyPgTest.Configuration.class})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RereleaseConcurrencyPgTest {
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
        when(storage.getObject("source-skill-md")).thenAnswer(i->new java.io.ByteArrayInputStream(sourceBytes()));
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
            em.persist(new SkillFile(retainedId,"SKILL.md",100L,"text/markdown","fixture","source-skill-md"));
        });
    }
    byte[] sourceBytes() {return "---\nname: candidate\ndescription: Current source\nversion: 2.0.0\n---\nBody".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    void rerelease() {publisher.rereleasePublishedVersion(skillId,"2.0.0","3.0.0","owner",Map.of(),true);}
    @ParameterizedTest @ValueSource(strings={"version","archive","freeze"})
    void staleInvalidSourceCannotBeReadOrCopied(String mutation) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(t->{
                switch(mutation) {
                    case "version" -> {em.find(SkillVersion.class,retainedId);await(worker.submit(()->jdbc.update("UPDATE skill_version SET status='YANKED' WHERE id=?",retainedId)));}
                    case "archive" -> {em.find(Skill.class,skillId);await(worker.submit(()->jdbc.update("UPDATE skill SET status='ARCHIVED' WHERE id=?",skillId)));}
                    case "freeze" -> {em.find(Namespace.class,namespaceId);await(worker.submit(()->jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?",namespaceId)));}
                }
                rerelease();
            })).isInstanceOf(DomainBadRequestException.class);
        }
        verify(storage,never()).getObject(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(2);
    }
    @Test void nativeOwnerRereleaseCopiesCurrentSourceAndKeepsPublishedVersion() {
        tx.executeWithoutResult(t->rerelease());
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,retainedId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='3.0.0'",Integer.class,skillId)).isEqualTo(1);
        verify(storage).getObject("source-skill-md");
    }
    @Test void yankFirstMakesWaitingRereleaseFailBeforeObjectRead() throws Exception {
        race(()->governance.yankVersion(retainedId,"owner",null,null,"reason"),()->assertThatThrownBy(this::rerelease).isInstanceOf(DomainBadRequestException.class));
        verify(storage,never()).getObject(anyString());
    }
    @Test void hardDeleteFirstCannotResurrectSource() throws Exception {
        race(()->deletion.hardDeleteSkill(em.find(Skill.class,skillId),"candidate-team","owner",null,null),()->assertThatThrownBy(this::rerelease).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class));
        verify(storage,never()).getObject(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,skillId)).isZero();
    }
    @Test void rereleaseFirstMakesDeleteRejectNewReviewHistory() throws Exception {
        race(this::rerelease,()->assertThatThrownBy(()->deletion.hardDeleteSkill(em.find(Skill.class,skillId),"candidate-team","owner",null,null)).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='3.0.0'",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void sourceRowLockIsHeldWhileObjectReadIsBlocked() throws Exception {
        var reading=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);var pid=new AtomicInteger();
        when(storage.getObject("source-skill-md")).thenAnswer(i->{reading.countDown();if(!releaseRead.await(15,TimeUnit.SECONDS))throw new IllegalStateException("read release timeout");return new java.io.ByteArrayInputStream(sourceBytes());});
        try(var workers=Executors.newFixedThreadPool(2)) {
            var copy=workers.submit(()->tx.executeWithoutResult(t->rerelease()));
            assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue();
            var yank=workers.submit(()->tx.executeWithoutResult(t->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));governance.yankVersion(retainedId,"owner",null,null,"reason");}));
            try {waitForLock(pid);} finally {releaseRead.countDown();}
            await(copy);await(yank);
        } finally {releaseRead.countDown();}
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,retainedId)).isEqualTo("YANKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='3.0.0'",Integer.class,skillId)).isEqualTo(1);
    }
    void waitForLock(AtomicInteger pid) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean blocked=false;
        while(System.nanoTime()<deadline) {
            blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid.get()));
            if(blocked)break;Thread.sleep(20);
        }
        assertThat(blocked).as("competing transaction waits for the protected source").isTrue();
    }
    @Test void objectReadFailureDoesNotChangeSourceOrCreateTargetVersion() {
        when(storage.getObject("source-skill-md")).thenThrow(new IllegalStateException("object unavailable"));
        assertThatThrownBy(()->tx.executeWithoutResult(t->rerelease())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(2);
        verify(storage,never()).deleteObjects(anyList());
    }
    @Test void outerRollbackKeepsOriginalFilesAndVersionGraph() {
        tx.executeWithoutResult(t->{rerelease();t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,skillId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE skill_id=?",Integer.class,skillId)).isZero();
        verify(storage,never()).deleteObjects(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"slug","namespace","owner"})
    void staleSourceIdentityAndOwnerCannotBeReused(String mutation) throws Exception {
        long otherNamespace=tx.execute(t->{var ns=new Namespace("other-team","Other","owner");em.persist(ns);em.flush();return ns.getId();});
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(t->{em.find(Skill.class,skillId);
                String change=switch(mutation){case "slug"->"slug='renamed'";case "namespace"->"namespace_id="+otherNamespace;default->"owner_id='reviewer'";};
                await(worker.submit(()->jdbc.update("UPDATE skill SET "+change+" WHERE id=?",skillId)));rerelease();
            })).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class);
        }
        verify(storage,never()).getObject(anyString());
    }
    @Test void mismatchedEmbeddedNameCannotChooseAnotherAsset() {
        when(storage.getObject("source-skill-md")).thenAnswer(i->new java.io.ByteArrayInputStream("---\nname: another-name\ndescription: Other\nversion: 2.0.0\n---\nBody".getBytes()));
        assertThatThrownBy(()->tx.executeWithoutResult(t->rerelease())).isInstanceOf(DomainBadRequestException.class);
        verify(storage,never()).putObject(anyString(),any(),anyLong(),anyString());
    }
    @Test void boundMaintainerRereleasePinsOriginalAsset() {
        when(slark.can(any(),eq(namespaceId),any(),anyString())).thenReturn(Optional.of(true));
        tx.executeWithoutResult(t->publisher.rereleasePublishedVersion(skillId,"2.0.0","3.0.0","reviewer",Map.of(),true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=?",Integer.class,namespaceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM skill WHERE id=?",String.class,skillId)).isEqualTo("owner");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=? AND version='3.0.0'",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void boundRevocationAfterNamespaceWaitFailsBeforeObjectRead() throws Exception {
        var calls=new AtomicInteger();
        when(slark.can(eq(SlarkAssetAuthorization.Operation.SUBMIT),eq(namespaceId),eq(skillId),eq("owner"))).thenAnswer(i->Optional.of(calls.incrementAndGet()==1));
        race(()->em.find(Namespace.class,namespaceId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE),()->assertThatThrownBy(this::rerelease).isInstanceOf(DomainForbiddenException.class));
        verify(storage,never()).getObject(anyString());
    }
    long targetSkillId,targetVersionId,targetTaskId;
    void otherOwnerTarget(boolean pendingReview) {
        tx.executeWithoutResult(t->{
            var target=new Skill(namespaceId,"candidate","reviewer",SkillVisibility.PUBLIC);em.persist(target);em.flush();targetSkillId=target.getId();
            var v=new SkillVersion(targetSkillId,"4.0.0","reviewer");v.setStatus(pendingReview?SkillVersionStatus.PENDING_REVIEW:SkillVersionStatus.UPLOADED);em.persist(v);em.flush();targetVersionId=v.getId();
            if(pendingReview){var task=new ReviewTask(v.getId(),target.getId(),namespaceId,"4.0.0","reviewer");em.persist(task);em.flush();targetTaskId=task.getId();}
        });
    }
    void asNamespaceAdmin() {publisher.rereleasePublishedVersion(skillId,"2.0.0","3.0.0","reviewer",Map.of(namespaceId,NamespaceRole.ADMIN),true);}
    @Test void nativeOtherOwnerPublishedConflictRemainsDenied() {
        otherOwnerTarget(false);
        assertThatThrownBy(()->tx.executeWithoutResult(t->asNamespaceAdmin())).isInstanceOf(DomainBadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,targetSkillId)).isEqualTo(1);
        verify(storage,never()).putObject(anyString(),any(),anyLong(),anyString());
    }
    @Test void targetReviewLocksAreTakenBeforeSourceNamespaceLock() throws Exception {
        otherOwnerTarget(true);
        race(()->{
            em.find(ReviewTask.class,targetTaskId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){reviews.rejectReview(targetTaskId,"owner","rejected",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of());}
            });
        },()->assertThatThrownBy(this::asNamespaceAdmin).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE id=?",String.class,targetTaskId)).isEqualTo("REJECTED");
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
