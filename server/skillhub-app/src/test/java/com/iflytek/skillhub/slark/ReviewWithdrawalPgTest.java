package com.iflytek.skillhub.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.governance.GovernanceNotificationService;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.*;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillGovernanceService;
import com.iflytek.skillhub.domain.user.UserAccount;
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
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({com.iflytek.skillhub.service.ReviewPortalAppService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,ReviewWithdrawalPgTest.Configuration.class})
class ReviewWithdrawalPgTest {
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
    @MockBean com.iflytek.skillhub.domain.security.SecurityScanService scanner;
    @MockBean com.iflytek.skillhub.domain.skill.service.SkillStorageDeletionCompensationService compensation;
    @MockBean GovernanceNotificationService notifications;
    @MockBean com.iflytek.skillhub.repository.GovernanceQueryRepository queries;
    @MockBean com.iflytek.skillhub.repository.ReviewProgressQueryRepository progress;
    @MockBean com.iflytek.skillhub.auth.rbac.RbacService rbac;
    @MockBean com.iflytek.skillhub.observability.RequestIdAccessor requestId;
    @MockBean com.iflytek.skillhub.domain.suite.SkillSuiteLifecycleService suites;
    @Autowired com.iflytek.skillhub.service.ReviewPortalAppService portal;
    @Autowired ReviewService reviews;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;
    long namespaceId,skillId,versionId,taskId;
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE user_account,namespace CASCADE");
        tx.executeWithoutResult(s->{
            em.persist(new UserAccount("owner","Owner",null,null));
            em.persist(new UserAccount("reviewer","Reviewer",null,null));
            var ns=new Namespace("candidate-team","Candidate","owner");em.persist(ns);em.flush();namespaceId=ns.getId();
            var skill=new Skill(namespaceId,"candidate","owner",SkillVisibility.PUBLIC);em.persist(skill);em.flush();skillId=skill.getId();
            var version=new SkillVersion(skillId,"1.0.0","owner");version.setStatus(SkillVersionStatus.PENDING_REVIEW);em.persist(version);em.flush();versionId=version.getId();
            var task=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");em.persist(task);em.flush();taskId=task.getId();
        });
    }
    ReviewTask decide(boolean approve) {
        return approve ? reviews.approveReview(taskId,"reviewer","decision",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of())
                : reviews.rejectReview(taskId,"reviewer","decision",Map.of(namespaceId,NamespaceRole.ADMIN),Set.of());
    }
    String status(String table,long id){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id);}
    void withdraw(){reviews.withdrawReview(versionId,"owner");}
    void unchanged(){
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void obsoleteTaskRequestCannotWithdrawNewReviewRound(){
        jdbc.update("UPDATE review_task SET status='REJECTED' WHERE id=?",taskId);
        long next=tx.execute(s->{var task=new ReviewTask(versionId,skillId,namespaceId,"1.0.0","owner");em.persist(task);em.flush();return task.getId();});
        assertThatThrownBy(()->portal.withdrawReview(taskId,"owner",Map.of(),null)).isInstanceOf(DomainBadRequestException.class);
        assertThat(status("review_task",taskId)).isEqualTo("REJECTED");
        assertThat(status("review_task",next)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void portalWithdrawsExactCurrentTask(){
        portal.withdrawReview(taskId,"owner",Map.of(),null);
        assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isZero();
    }
    @Test void nativeSubmitterWithdrawsToUploaded(){
        withdraw();
        assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isZero();
    }
    @Test void nonSubmitterCannotWithdraw(){
        assertThatThrownBy(()->reviews.withdrawReview(versionId,"reviewer"))
            .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        unchanged();
    }
    @Test void revokedSlarkMaintainerCannotWithdraw(){
        org.mockito.Mockito.when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner"))
            .thenReturn(java.util.Optional.of(false));
        assertThatThrownBy(this::withdraw).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        unchanged();
    }
    @Test void authorityFailureRollsBackWithoutNativeFallback(){
        org.mockito.Mockito.when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner"))
            .thenThrow(new IllegalStateException("authority unavailable"));
        assertThatThrownBy(this::withdraw).hasMessage("authority unavailable");unchanged();
    }
    @Test void currentSlarkMaintainerCanWithdraw(){
        org.mockito.Mockito.when(slark.can(SlarkAssetAuthorization.Operation.SUBMIT,namespaceId,skillId,"owner"))
            .thenReturn(java.util.Optional.of(true));
        withdraw();assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
    }
    @Test void mismatchedTypedCandidateCannotBeDeleted(){
        jdbc.update("UPDATE review_task SET subject_version='2.0.0' WHERE id=?",taskId);
        assertThatThrownBy(this::withdraw).isInstanceOf(DomainBadRequestException.class);unchanged();
    }
    @Test void mismatchedNamespaceCannotBeDeleted(){
        tx.executeWithoutResult(s->em.persist(new Namespace("other","Other","owner")));
        long other=jdbc.queryForObject("SELECT id FROM namespace WHERE slug='other'",Long.class);
        jdbc.update("UPDATE review_task SET namespace_id=? WHERE id=?",other,taskId);
        assertThatThrownBy(this::withdraw).isInstanceOf(DomainBadRequestException.class);unchanged();
    }
    void stale(Class<?> type,long id,String sql) throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(type,id);
                try {worker.submit(()->jdbc.update(sql,id)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                withdraw();
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
    }
    @Test void preloadedNamespaceCannotHideFreeze() throws Exception {
        stale(Namespace.class,namespaceId,"UPDATE namespace SET status='FROZEN' WHERE id=?");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void preloadedAssetCannotHideArchive() throws Exception {
        stale(Skill.class,skillId,"UPDATE skill SET status='ARCHIVED' WHERE id=?");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void preloadedVersionCannotOverwritePublishedState() throws Exception {
        stale(SkillVersion.class,versionId,"UPDATE skill_version SET status='PUBLISHED' WHERE id=?");
        assertThat(status("skill_version",versionId)).isEqualTo("PUBLISHED");
    }
    @Test void scanningVersionKeepsReviewCandidate(){
        jdbc.update("UPDATE skill_version SET status='SCANNING' WHERE id=?",versionId);
        assertThatThrownBy(this::withdraw).isInstanceOf(DomainBadRequestException.class);
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("SCANNING");
    }
    @Test void laterAuditFailureRestoresCandidateAndVersion(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->{withdraw();throw new IllegalStateException("audit fault");})).hasMessage("audit fault");
        unchanged();
    }
    @Test void waitingWithdrawalCannotDeleteCommittedApproval() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var approval=workers.submit(()->tx.executeWithoutResult(s->{
                decide(true);changed.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            var withdrawal=workers.submit(()->{
                try {tx.executeWithoutResult(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));withdraw();});return "withdrawn";}
                catch(DomainBadRequestException expected){return "denied";}
                catch(org.springframework.orm.ObjectOptimisticLockingFailureException conflict){return "late conflict";}
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid.get()));
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("withdrawal waits on actual PostgreSQL lock").isTrue();
            }finally{release.countDown();}
            approval.get(5,TimeUnit.SECONDS);assertThat(withdrawal.get(5,TimeUnit.SECONDS)).isEqualTo("denied");
        }
        assertThat(status("review_task",taskId)).isEqualTo("APPROVED");
        assertThat(status("skill_version",versionId)).isEqualTo("PUBLISHED");
    }
    @Test void waitingApprovalCannotRecreateWithdrawnCandidate() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var approval=workers.submit(()->tx.executeWithoutResult(s->{
                withdraw();em.flush();changed.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            var withdrawal=workers.submit(()->{
                try {tx.executeWithoutResult(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));decide(true);});return "withdrawn";}
                catch(com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException expected){return "denied";}
                catch(org.springframework.orm.ObjectOptimisticLockingFailureException conflict){return "late conflict";}
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid.get()));
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("approval waits on actual PostgreSQL lock").isTrue();
            }finally{release.countDown();}
            approval.get(5,TimeUnit.SECONDS);assertThat(withdrawal.get(5,TimeUnit.SECONDS)).isEqualTo("denied");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_task WHERE id=?",Integer.class,taskId)).isZero();
        assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
    }
}
