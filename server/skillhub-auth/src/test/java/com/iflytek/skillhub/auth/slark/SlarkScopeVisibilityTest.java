package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.*;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.governance.GovernanceNotificationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual scope policy and mutation services; ACTIVE fixtures do not activate production bindings. */
class SlarkScopeVisibilityTest {
    final SlarkNamespaceBindingRepository bindings = mock(SlarkNamespaceBindingRepository.class);
    final SkillRepository skills = mock(SkillRepository.class);
    final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    final ReviewTaskRepository tasks = mock(ReviewTaskRepository.class);
    final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    @SuppressWarnings("unchecked") final ObjectProvider<SlarkScopeAuthorityClient> clients = mock(ObjectProvider.class);
    final SlarkAssetAuthorizationService auth = spy(new SlarkAssetAuthorizationService(bindings,
        mock(SlarkAssetMaintainerRepository.class), skills, mock(UserAccountRepository.class),
        mock(IdentityBindingRepository.class), clients));
    final Skill skill = new Skill(10L,"skill","owner",SkillVisibility.NAMESPACE_ONLY);
    final SkillVersion version = new SkillVersion(20L,"1.0.0","owner");
    final ReviewTask task = new ReviewTask(30L,20L,10L,"1.0.0","owner");
    void fixture(String scope) {
        when(bindings.findByNamespaceId(10L)).thenReturn(Optional.of(new SlarkNamespaceBinding(
            "registry",scope,"owner","10","slark-fixture","PREPARED")));
        ReflectionTestUtils.setField(skill,"id",20L);
        ReflectionTestUtils.setField(version,"id",30L);
        ReflectionTestUtils.setField(task,"id",40L);
        when(skills.findById(20L)).thenReturn(Optional.of(skill));
        when(versions.findById(30L)).thenReturn(Optional.of(version));
        when(tasks.findById(40L)).thenReturn(Optional.of(task));
        when(namespaces.findById(10L)).thenReturn(Optional.of(new Namespace("slark-fixture","Fixture","owner")));
        when(tasks.save(any())).thenAnswer(i->i.getArgument(0));
        when(tasks.updateStatusWithVersion(anyLong(),any(),any(),any(),anyInt())).thenReturn(1);
        doReturn(Optional.of(true)).when(auth).can(any(),eq(10L),any(),any());
    }
    ReviewService reviews() {
        return new ReviewService(tasks,versions,skills,namespaces,new ReviewPermissionChecker(auth),events,
            new ObjectMapper(),mock(SkillGovernanceService.class),mock(GovernanceNotificationService.class),
            mock(EntityManager.class),Clock.systemUTC());
    }
    SkillReviewSubmitService submissions() {
        return new SkillReviewSubmitService(skills,versions,tasks,namespaces,events,Clock.systemUTC(),auth,mock(EntityManager.class));
    }
    void unchanged(SkillVersionStatus status) {
        assertThat(version.getStatus()).isEqualTo(status);
        assertThat(skill.getLatestVersionId()).isNull();
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(versions,never()).save(any()); verify(tasks,never()).save(any());
        verify(tasks,never()).updateStatusWithVersion(anyLong(),any(),any(),any(),anyInt());
        verify(skills,never()).save(any()); verifyNoInteractions(events);
    }
    @ParameterizedTest @CsvSource({"public,PUBLIC","organization,NAMESPACE_ONLY","project,NAMESPACE_ONLY","personal,PRIVATE"})
    void scopeHasExactlyOneVisibility(String scope, SkillVisibility required) {
        fixture(scope);
        for (SkillVisibility visibility : SkillVisibility.values()) {
            if (visibility == required) assertThatCode(()->auth.assertVisibility(10L,visibility)).doesNotThrowAnyException();
            else assertThatThrownBy(()->auth.assertVisibility(10L,visibility))
                .isInstanceOfSatisfying(SlarkAssetAuthorizationFailure.class,e->assertThat(e.statusCode()).isEqualTo(403));
        }
        assertThatThrownBy(()->auth.assertVisibility(10L,null)).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        verifyNoInteractions(clients);
    }
    @Test void nativeAllowsAllVisibilitiesWithoutNetworkButUnreadableOrUnknownBindingFailsClosed() {
        when(bindings.findByNamespaceId(10L)).thenReturn(Optional.empty());
        for (SkillVisibility visibility : SkillVisibility.values()) auth.assertVisibility(10L,visibility);
        auth.assertVisibility(10L,null);
        for (String kind : Arrays.asList("unknown",null)) {
            when(bindings.findByNamespaceId(10L)).thenReturn(Optional.of(new SlarkNamespaceBinding("registry",kind,"owner","10","fixture","PREPARED")));
            assertThatThrownBy(()->auth.assertVisibility(10L,SkillVisibility.PUBLIC))
                .isInstanceOfSatisfying(SlarkAssetAuthorizationFailure.class,e->assertThat(e.statusCode()).isEqualTo(503));
        }
        when(bindings.findByNamespaceId(10L)).thenThrow(new IllegalStateException("database-secret"));
        assertThatThrownBy(()->auth.assertVisibility(10L,SkillVisibility.PUBLIC))
            .isInstanceOfSatisfying(SlarkAssetAuthorizationFailure.class,e->{assertThat(e.statusCode()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("database-secret");});
        verifyNoInteractions(clients);
    }
    @ParameterizedTest @CsvSource({"public,PRIVATE","public,NAMESPACE_ONLY","organization,PUBLIC","organization,PRIVATE","project,PUBLIC","project,PRIVATE","personal,PUBLIC","personal,NAMESPACE_ONLY"})
    void wrongCandidateCannotBeApprovedEvenByNativeSuperAdmin(String scope,SkillVisibility candidate) {
        fixture(scope);version.setRequestedVisibility(candidate);version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        assertThatThrownBy(()->reviews().approveReview(40L,"admin","approve",Map.of(10L,NamespaceRole.OWNER),Set.of("SUPER_ADMIN")))
            .isInstanceOf(SlarkAssetAuthorizationFailure.class);
        unchanged(SkillVersionStatus.PENDING_REVIEW);
    }
    @ParameterizedTest @CsvSource({"public,PUBLIC","organization,NAMESPACE_ONLY","project,NAMESPACE_ONLY","personal,PRIVATE"})
    void approvalChecksCandidateRatherThanOldAssetVisibility(String scope,SkillVisibility candidate) {
        fixture(scope);skill.setVisibility(candidate==SkillVisibility.PUBLIC?SkillVisibility.PRIVATE:SkillVisibility.PUBLIC);
        version.setRequestedVisibility(candidate);version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        reviews().approveReview(40L,"admin","approve",Map.of(),Set.of());
        assertThat(skill.getVisibility()).isEqualTo(candidate);
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
        assertThat(skill.getLatestVersionId()).isEqualTo(30L);
    }
    @Test void legacyNullCandidateCannotInheritInvalidPublicAssetVisibility() {
        fixture("project");skill.setVisibility(SkillVisibility.PUBLIC);version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        assertThatThrownBy(()->reviews().approveReview(40L,"admin","approve",Map.of(),Set.of())).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        unchanged(SkillVersionStatus.PENDING_REVIEW);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bothReviewOverloadsRejectWrongCandidateWithoutMutation(boolean legacy) {
        fixture("project");version.setRequestedVisibility(SkillVisibility.PRIVATE);
        assertThatThrownBy(()-> { if(legacy) reviews().submitReview(30L,"owner",Map.of());
            else reviews().submitReview(30L,"owner",Map.of(),Set.of("SUPER_ADMIN")); }).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        unchanged(SkillVersionStatus.DRAFT);
    }
    @ParameterizedTest @CsvSource({"public,PRIVATE","organization,PUBLIC","project,PUBLIC","personal,NAMESPACE_ONLY"})
    void visibilityChangingSubmissionCannotEscapeScope(String scope,SkillVisibility candidate) {
        fixture(scope);
        assertThatThrownBy(()->submissions().submitForReview(20L,30L,candidate,"owner",Map.of()))
            .isInstanceOf(SlarkAssetAuthorizationFailure.class);
        assertThat(version.getRequestedVisibility()).isNull();unchanged(SkillVersionStatus.DRAFT);
    }
    @Test void allowedSubmissionSupportsDelegatedMaintainerAndRevokedOwnerIsDenied() {
        fixture("project");
        doReturn(Optional.of(false)).when(auth).can(SlarkAssetAuthorization.Operation.SUBMIT,10L,20L,"owner");
        assertThatThrownBy(()->submissions().submitForReview(20L,30L,SkillVisibility.NAMESPACE_ONLY,"owner",Map.of(10L,NamespaceRole.OWNER)))
            .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        unchanged(SkillVersionStatus.DRAFT);
        submissions().submitForReview(20L,30L,SkillVisibility.NAMESPACE_ONLY,"maintainer",Map.of());
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PENDING_REVIEW);
        assertThat(version.getRequestedVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
    }
    @Test void confirmPrivatePublishCannotUseInvalidProjectAssetOrRevokedPersonalOwner() {
        fixture("project");skill.setVisibility(SkillVisibility.PRIVATE);
        assertThatThrownBy(()->submissions().confirmPublish(20L,30L,"owner",Map.of(),Set.of("SUPER_ADMIN"))).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        fixture("personal");doReturn(Optional.of(false)).when(auth).can(SlarkAssetAuthorization.Operation.SUBMIT,10L,20L,"owner");
        assertThatThrownBy(()->submissions().confirmPublish(20L,30L,"owner",Map.of(),Set.of("SUPER_ADMIN")))
            .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        unchanged(SkillVersionStatus.DRAFT);
    }
    @ParameterizedTest @CsvSource({"FROZEN,false","FROZEN,true","ARCHIVED,false","ARCHIVED,true"})
    void boundSubmissionAndConfirmationRequireWritableNamespace(NamespaceStatus status,boolean confirm) {
        fixture("personal");skill.setVisibility(SkillVisibility.PRIVATE);
        var namespace = new Namespace("slark-fixture","Fixture","owner");namespace.setStatus(status);
        when(namespaces.findById(10L)).thenReturn(Optional.of(namespace));
        assertThatThrownBy(()-> { if(confirm) submissions().confirmPublish(20L,30L,"owner",Map.of(),Set.of("SUPER_ADMIN"));
            else submissions().submitForReview(20L,30L,SkillVisibility.PRIVATE,"owner",Map.of()); })
            .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException.class);
        unchanged(SkillVersionStatus.DRAFT);
    }

}
