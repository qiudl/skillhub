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
@Import({ReviewService.class,ReviewPermissionChecker.class,ReviewCandidatePgTest.Configuration.class})
class ReviewCandidatePgTest {
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
    @MockBean SkillGovernanceService governance;
    @MockBean GovernanceNotificationService notifications;
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
    @Test void validCandidatePublishesAndRecordsReviewer() {
        decide(true);
        assertThat(status("review_task",taskId)).isEqualTo("APPROVED");
        assertThat(status("skill_version",versionId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isEqualTo(versionId);
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM review_task WHERE id=?",String.class,taskId)).isEqualTo("reviewer");
    }
    @Test void mismatchCannotChangeEitherAsset() {
        jdbc.update("UPDATE review_task SET subject_version='2.0.0' WHERE id=?",taskId);
        assertThatThrownBy(()->decide(true)).isInstanceOf(DomainBadRequestException.class);
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void preloadedVersionCannotOverwriteACompletedWithdrawal() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(SkillVersion.class,versionId);
                try {worker.submit(()->jdbc.update("UPDATE skill_version SET status='UPLOADED' WHERE id=?",versionId)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                decide(true);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
    }
    @Test void preloadedNamespaceCannotHideACompletedFreeze() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->{
                em.find(Namespace.class,namespaceId);
                try {worker.submit(()->jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?",namespaceId)).get(5,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                decide(true);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void preloadedReviewCannotOverwriteACompletedDecision() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                em.find(ReviewTask.class, taskId);
                try { worker.submit(() -> decide(false)).get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
                decide(true);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(status("review_task", taskId)).isEqualTo("REJECTED");
        assertThat(status("skill_version", versionId)).isEqualTo("REJECTED");
    }
    @Test void preloadedAssetCannotHideACompletedArchive() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                em.find(Skill.class, skillId);
                try { worker.submit(() -> jdbc.update("UPDATE skill SET status='ARCHIVED' WHERE id=?", skillId)).get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
                decide(true);
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(status("skill", skillId)).isEqualTo("ARCHIVED");
        assertThat(status("review_task", taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version", versionId)).isEqualTo("PENDING_REVIEW");
    }
    @Test void competingApproveAndRejectHaveOnlyOneCommittedWinner() throws Exception {
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var approve=workers.submit(()->attempt(start,true));var reject=workers.submit(()->attempt(start,false));start.countDown();
            assertThat((approve.get(10,TimeUnit.SECONDS)?1:0)+(reject.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        String taskStatus=status("review_task",taskId);
        assertThat(status("skill_version",versionId)).isEqualTo(taskStatus.equals("APPROVED")?"PUBLISHED":"REJECTED");
    }
    boolean attempt(CountDownLatch start,boolean approve) throws Exception {
        start.await(5,TimeUnit.SECONDS);
        try {decide(approve);return true;} catch(DomainBadRequestException|java.util.ConcurrentModificationException expected){return false;}
    }
    @Test void waitingReviewerRechecksCandidateAfterTheOtherTransactionCommits() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var reviewerPid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var writer=workers.submit(()->tx.executeWithoutResult(s->{
                jdbc.update("UPDATE skill_version SET status='UPLOADED' WHERE id=?",versionId);changed.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            var reviewer=workers.submit(()-> {
                try {tx.executeWithoutResult(s->{reviewerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));decide(true);});return "approved";}
                catch(DomainBadRequestException expected){return "denied";}
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,reviewerPid.get()));
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("reviewer must wait on the actual PostgreSQL row lock").isTrue();
            } finally {release.countDown();}
            writer.get(5,TimeUnit.SECONDS);assertThat(reviewer.get(5,TimeUnit.SECONDS)).isEqualTo("denied");
        }
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("UPLOADED");
    }
    @Test void laterAuditFailureRollsBackReviewAndPublicationTogether() {
        assertThatThrownBy(()->tx.executeWithoutResult(s->{decide(true);throw new IllegalStateException("audit fault");})).hasMessage("audit fault");
        assertThat(status("review_task",taskId)).isEqualTo("PENDING");
        assertThat(status("skill_version",versionId)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT latest_version_id FROM skill WHERE id=?",Long.class,skillId)).isNull();
    }
}
