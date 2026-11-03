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
@Import({PromotionMutationGuard.class,PromotionService.class,PromotionRevocationService.class,com.iflytek.skillhub.infra.jpa.PromotionRevocationHistoryJpaRepository.class,com.iflytek.skillhub.search.postgres.PostgresFullTextIndexService.class,com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService.class,com.iflytek.skillhub.service.SkillDeleteAppService.class,com.iflytek.skillhub.domain.skill.service.SkillReviewSubmitService.class,com.iflytek.skillhub.domain.skill.service.SkillPublishService.class,com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser.class,SecurityScanService.class,SkillGovernanceService.class,ReviewService.class,ReviewPermissionChecker.class,PromotionConcurrencyPgTest.Configuration.class})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PromotionConcurrencyPgTest {
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
    @Autowired PromotionService promotions;
    @Autowired PromotionRevocationService revocations;
    @MockBean SlarkNamespaceBindingRepository bindings;
    long globalId,promotionId;
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
            var global=new Namespace("candidate-global","Global","owner");global.setType(NamespaceType.GLOBAL);em.persist(global);em.flush();globalId=global.getId();
        });
    }
    void pending() {promotionId=tx.execute(t->{var p=new PromotionRequest(skillId,retainedId,globalId,"owner");em.persist(p);em.flush();return p.getId();});}
    void approve() {promotions.approvePromotion(promotionId,"reviewer","approved",Set.of("SUPER_ADMIN"),Set.of());}
    @ParameterizedTest @ValueSource(strings={"version","skill","namespace"})
    void staleSourceCannotBePromotedAfterConcurrentInvalidation(String candidate) throws Exception {
        pending();
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(t->{
                switch(candidate) {
                    case "version" -> {em.find(SkillVersion.class,retainedId);await(worker.submit(()->jdbc.update("UPDATE skill_version SET status='YANKED' WHERE id=?",retainedId)));}
                    case "skill" -> {em.find(Skill.class,skillId);await(worker.submit(()->jdbc.update("UPDATE skill SET hidden=true WHERE id=?",skillId)));}
                    case "namespace" -> {em.find(Namespace.class,namespaceId);await(worker.submit(()->jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?",namespaceId)));}
                }
                approve();
            })).isInstanceOf(DomainBadRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=?",Integer.class,globalId)).isZero();
    }
    void submit() {promotions.submitPromotion(skillId,retainedId,globalId,"owner",Map.of(),Set.of());}
    long target() {return jdbc.queryForObject("SELECT target_skill_id FROM promotion_request WHERE id=?",Long.class,promotionId);}
    void initial() {pending();tx.executeWithoutResult(t->approve());}
    long revocationId;
    void submitRevocation() {revocationId=revocations.submit(skillId,"owner",Map.of(),Set.of(),"cleanup").getId();}
    void revoke() {revocations.approve(revocationId,"reviewer",Set.of("SUPER_ADMIN"),"approved",null,null);}
    @ParameterizedTest @ValueSource(strings={"source","target"})
    void boundScopeCannotUseNativePromotion(String side) {
        when(bindings.isBound(side.equals("source")?namespaceId:globalId)).thenReturn(true);
        assertThatThrownBy(()->tx.executeWithoutResult(t->submit())).isInstanceOf(DomainForbiddenException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_request",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"source","target"})
    void boundScopeCannotApprovePreexistingNativePromotion(String side) {
        pending();when(bindings.isBound(side.equals("source")?namespaceId:globalId)).thenReturn(true);
        assertThatThrownBy(()->tx.executeWithoutResult(t->approve())).isInstanceOf(DomainForbiddenException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("PENDING");
    }
    @Test void frozenTargetCannotPublishPromotion() {
        pending();jdbc.update("UPDATE namespace SET status='FROZEN' WHERE id=?",globalId);
        assertThatThrownBy(()->tx.executeWithoutResult(t->approve())).isInstanceOf(DomainBadRequestException.class);
    }
    @Test void twoSubmissionsCreateOnlyOnePendingRequest() throws Exception {
        race(this::submit,()->assertThatThrownBy(this::submit).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_request WHERE status='PENDING'",Integer.class)).isEqualTo(1);
    }
    @Test void twoApprovalsCreateOnlyOneTargetVersion() throws Exception {
        pending();race(this::approve,()->assertThatThrownBy(this::approve).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=?",Integer.class,globalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,target())).isEqualTo(1);
        assertThat(committedEvents.publications).hasSize(1);
    }
    @Test void rejectFirstPreventsWaitingApproval() throws Exception {
        pending();race(()->promotions.rejectPromotion(promotionId,"reviewer","rejected",Set.of("SUPER_ADMIN")),()->assertThatThrownBy(this::approve).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("REJECTED");
    }
    @Test void approvalFirstPreventsWaitingRejection() throws Exception {
        pending();race(this::approve,()->assertThatThrownBy(()->promotions.rejectPromotion(promotionId,"reviewer","rejected",Set.of("SUPER_ADMIN"))).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("APPROVED");
    }
    @Test void yankFirstPreventsWaitingPromotion() throws Exception {
        pending();race(()->governance.yankVersion(retainedId,"reviewer",null,null,"reason"),()->assertThatThrownBy(this::approve).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("PENDING");
    }
    @Test void approvalFirstThenYankPreservesCopy() throws Exception {
        pending();race(this::approve,()->governance.yankVersion(retainedId,"reviewer",null,null,"reason"),false);
        assertThat(jdbc.queryForObject("SELECT status FROM skill_version WHERE skill_id=?",String.class,target())).isEqualTo("PUBLISHED");
    }
    @Test void sourceArchiveFirstPreventsPromotion() throws Exception {
        pending();race(()->governance.archiveSkill(skillId,"owner",Map.of(),null,null,"reason"),()->assertThatThrownBy(this::approve).isInstanceOf(DomainBadRequestException.class));
    }
    com.iflytek.skillhub.domain.skill.service.SkillPublishService.PublishResult publish(String version) {
        byte[] bytes=("---\nname: candidate\ndescription: Candidate\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return publisher.publishFromEntries("candidate-team",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true);
    }
    @Test void sourceUploadWaitsUntilPromotionCopiesStableVersion() throws Exception {
        pending();race(this::approve,()->publish("4.0.0"),false);
        assertThat(jdbc.queryForObject("SELECT version FROM skill_version WHERE skill_id=?",String.class,target())).isEqualTo("2.0.0");
    }
    @Test void rollbackApprovalLeavesPendingAndNoTarget() {
        pending();tx.executeWithoutResult(t->{approve();t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE namespace_id=?",Integer.class,globalId)).isZero();
        assertThat(committedEvents.publications).isEmpty();
    }
    @Test void staleMetadataIsRefreshedBeforeCopy() throws Exception {
        pending();try(var worker=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(t->{em.find(Skill.class,skillId);await(worker.submit(()->jdbc.update("UPDATE skill SET summary='Current' WHERE id=?",skillId)));approve();});
        }
        assertThat(jdbc.queryForObject("SELECT summary FROM skill WHERE id=?",String.class,target())).isEqualTo("Current");
    }
    @Test void doubleRevocationPreservesOnlyOneSuccessfulDecision() throws Exception {
        initial();tx.executeWithoutResult(t->submitRevocation());long targetId=target();
        race(this::revoke,()->assertThatThrownBy(this::revoke).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,targetId)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_skill_id_snapshot FROM promotion_request WHERE id=?",Long.class,promotionId)).isEqualTo(targetId);
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_revocation_request WHERE id=?",String.class,revocationId)).isEqualTo("APPROVED");
        verify(storage,times(1)).deleteObjects(anyList());
    }
    @Test void revocationRollbackKeepsTargetHistoryAndStorage() {
        initial();tx.executeWithoutResult(t->submitRevocation());long targetId=target();
        tx.executeWithoutResult(t->{revoke();verify(storage,never()).deleteObjects(anyList());t.setRollbackOnly();});
        assertThat(target()).isEqualTo(targetId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_revocation_request WHERE id=?",String.class,revocationId)).isEqualTo("PENDING");
        verify(storage,never()).deleteObjects(anyList());
    }
    @Test void boundScopeCannotUseRevocationAsNativeAdmin() {
        initial();tx.executeWithoutResult(t->submitRevocation());when(bindings.isBound(namespaceId)).thenReturn(true);
        assertThatThrownBy(()->tx.executeWithoutResult(t->revoke())).isInstanceOf(DomainForbiddenException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,target())).isEqualTo(1);
    }
    long updateId;
    void updateRequest() {
        tx.executeWithoutResult(t->{var v=new SkillVersion(skillId,"3.0.0","owner");v.setStatus(SkillVersionStatus.PUBLISHED);em.persist(v);em.flush();updateId=promotions.submitPromotion(skillId,v.getId(),globalId,"owner",Map.of(),Set.of()).getId();});
    }
    void approveUpdate(){promotions.approvePromotion(updateId,"reviewer","update",Set.of("SUPER_ADMIN"),Set.of());}
    @Test void updateFirstThenRevocationDeletesAllTargetVersionsAndPreservesHistory() throws Exception {
        initial();updateRequest();tx.executeWithoutResult(t->submitRevocation());long targetId=target();
        race(this::approveUpdate,this::revoke,false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_version WHERE skill_id=?",Integer.class,targetId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_request WHERE source_skill_id=? AND target_skill_id IS NULL AND target_skill_id_snapshot=?",Integer.class,skillId,targetId)).isEqualTo(2);
    }
    @Test void revocationFirstMakesUpdateRejectInactiveTarget() throws Exception {
        initial();updateRequest();tx.executeWithoutResult(t->submitRevocation());
        race(this::revoke,()->assertThatThrownBy(this::approveUpdate).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,updateId)).isEqualTo("REJECTED");
    }
    @Test void sourceFileReferencesRemainAliveWhenDerivedTargetIsRevoked() {
        tx.executeWithoutResult(t->em.persist(new SkillFile(retainedId,"SKILL.md",4L,"text/markdown","hash","shared-source-key")));
        initial();tx.executeWithoutResult(t->submitRevocation());tx.executeWithoutResult(t->revoke());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_file WHERE storage_key='shared-source-key'",Integer.class)).isEqualTo(1);
        verify(storage).deleteObjects(argThat(keys->!keys.contains("shared-source-key")));
    }
    @Test void indexCleanupRunsAfterLocksAndRollsBackWithRevocation() {
        initial();tx.executeWithoutResult(t->submitRevocation());long targetId=target();
        jdbc.update("INSERT INTO skill_search_document(skill_id,namespace_id,namespace_slug,owner_id,visibility,status) VALUES(?,?,'candidate-global','owner','PUBLIC','ACTIVE')",targetId,globalId);
        tx.executeWithoutResult(t->{revocations.approve(revocationId,"reviewer",Set.of("SUPER_ADMIN"),"approved",null,null,search::remove);t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_search_document WHERE skill_id=?",Integer.class,targetId)).isEqualTo(1);
        assertThat(target()).isEqualTo(targetId);verify(storage,never()).deleteObjects(anyList());
    }
    @Test void directRevocationCleansIndexAndPreservesHistory() {
        initial();long targetId=target();
        jdbc.update("INSERT INTO skill_search_document(skill_id,namespace_id,namespace_slug,owner_id,visibility,status) VALUES(?,?,'candidate-global','owner','PUBLIC','ACTIVE')",targetId,globalId);
        tx.executeWithoutResult(t->revocations.revokeDirect(skillId,"reviewer",Set.of("SUPER_ADMIN"),"direct",null,null,search::remove));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_search_document WHERE skill_id=?",Integer.class,targetId)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_skill_id_snapshot FROM promotion_request WHERE id=?",Long.class,promotionId)).isEqualTo(targetId);
    }
    @Test void nativeTargetUploadFirstMakesUpdateRejectOccupiedVersion() throws Exception {
        initial();updateRequest();
        race(()->publishTarget("3.0.0"),()->assertThatThrownBy(this::approveUpdate).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,updateId)).isEqualTo("PENDING");
    }
    @Test void updateFirstMakesNativeUploadRejectPublishedReplacement() throws Exception {
        initial();updateRequest();
        race(this::approveUpdate,()->assertThatThrownBy(()->publishTarget("3.0.0")).isInstanceOf(DomainBadRequestException.class));
    }
    void publishTarget(String version) {
        byte[] bytes=("---\nname: candidate\ndescription: Target upload\nversion: "+version+"\n---\nBody").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        publisher.publishFromEntries("candidate-global",List.of(new com.iflytek.skillhub.domain.skill.validation.PackageEntry("SKILL.md",bytes,bytes.length,"text/markdown")),"owner",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),true);
    }
    @Test void sourceDeleteFirstMakesSubmissionFailWithoutDanglingRequest() throws Exception {
        race(()->deletion.hardDeleteSkill(em.find(Skill.class,skillId),"candidate-team","owner",null,null),()->assertThatThrownBy(this::submit).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_request",Integer.class)).isZero();
    }
    @Test void pendingSubmissionMakesWaitingSourceDeleteFail() throws Exception {
        race(this::submit,()->assertThatThrownBy(()->deletion.hardDeleteSkill(em.find(Skill.class,skillId),"candidate-team","owner",null,null)).isInstanceOf(DomainBadRequestException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill WHERE id=?",Integer.class,skillId)).isEqualTo(1);
    }
    @Test void ownerChangeCannotUsePreloadedSubmitterAuthority() throws Exception {
        pending();try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(t->{em.find(Skill.class,skillId);await(worker.submit(()->jdbc.update("UPDATE skill SET owner_id='reviewer' WHERE id=?",skillId)));approve();})).isInstanceOf(DomainForbiddenException.class);
        }
    }
    @Test void bindingLookupFailureNeverFallsBackToNativeRoles() {
        pending();when(bindings.isBound(namespaceId)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(()->tx.executeWithoutResult(t->approve())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM promotion_request WHERE id=?",String.class,promotionId)).isEqualTo("PENDING");
    }
    @Test void targetRetargetedInPreloadedRequestMustBeRejected() throws Exception {
        pending();long another=tx.execute(t->{var ns=new Namespace("another-global","Other","owner");ns.setType(NamespaceType.GLOBAL);em.persist(ns);em.flush();return ns.getId();});
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(()->tx.executeWithoutResult(t->{em.find(PromotionRequest.class,promotionId);await(worker.submit(()->jdbc.update("UPDATE promotion_request SET target_namespace_id=? WHERE id=?",another,promotionId)));approve();})).isInstanceOf(DomainBadRequestException.class);
        }
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
