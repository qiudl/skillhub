package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.ReviewPermissionChecker;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.*;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlarkAssetAuthorizationServiceTest {
    final SlarkNamespaceBindingRepository bindings = mock(SlarkNamespaceBindingRepository.class);
    final SlarkAssetMaintainerRepository maintainers = mock(SlarkAssetMaintainerRepository.class);
    final SkillRepository skills = mock(SkillRepository.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final IdentityBindingRepository identities = mock(IdentityBindingRepository.class);
    SlarkScopeAuthorityClient client;
    com.sun.net.httpserver.HttpServer server;
    final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger responseStatus = new java.util.concurrent.atomic.AtomicInteger(200);
    final java.util.concurrent.atomic.AtomicReference<String> response = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
    @SuppressWarnings("unchecked") final ObjectProvider<SlarkScopeAuthorityClient> clients = mock(ObjectProvider.class);
    SlarkAssetAuthorizationService service;
    ReviewPermissionChecker checker;
    MockHttpServletRequest request;
    Skill skill;
    SlarkNamespaceBinding binding;
    final SlarkBridgeClient.Grant grant = new SlarkBridgeClient.Grant("subject", 7,
            "12345678-1234-4234-8234-123456789abc", "Human", Long.MAX_VALUE, "reviews", "/");

    @BeforeEach void setup() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/skillhub/scopes/check", exchange -> {
            calls.incrementAndGet(); sent.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + "s".repeat(40));
            byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        client = new SlarkScopeAuthorityClient(new SlarkBridgeSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                "https://skills.example", "slark", "s".repeat(40), "c".repeat(40)), "registry");
        service = new SlarkAssetAuthorizationService(bindings, maintainers, skills, users, identities, clients);
        checker = new ReviewPermissionChecker(service);
        request = new MockHttpServletRequest();
        request.getSession().setAttribute("platformPrincipal", new PlatformPrincipal("local", "Human", null, null, "slark", Set.of()));
        request.getSession().setAttribute(SlarkBridgeService.SESSION_BINDING, new SlarkBridgeService.SessionBinding("local", grant));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var user = new UserAccount("local", "Human", null, null);
        when(users.findById("local")).thenReturn(Optional.of(user));
        var identity = mock(IdentityBinding.class); when(identity.getUserId()).thenReturn("local");
        when(identities.findByProviderCodeAndSubject("slark", "subject")).thenReturn(Optional.of(identity));
        when(clients.getIfAvailable()).thenReturn(client);
        skill = new Skill(10L, "skill", "local", SkillVisibility.PUBLIC);
        org.springframework.test.util.ReflectionTestUtils.setField(skill, "id", 20L);
        when(skills.findById(20L)).thenReturn(Optional.of(skill));
        scope("project", "project", "member", false);
    }
    void scope(String kind, String owner, String role, boolean admin) {
        var key = new SlarkNamespaceKey("registry", kind, owner);
        binding = new SlarkNamespaceBinding("registry", kind, owner, "10", key.namespaceSlug(), "PREPARED");
        when(bindings.findByNamespaceId(10L)).thenReturn(Optional.of(binding));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var value = json.createObjectNode().put("ok", true);
        var scope = value.putObject("scope").put("kind", kind);
        if (!kind.equals("public")) scope.put("ownerId", owner);
        value.putObject("actor").put("userId", "subject").put("active", true).put("platformAdmin", admin).put("synthetic", false).put("accountType", "human");
        if (role == null) value.putNull("membership");
        else {var member = value.putObject("membership").put("userId", "subject").put("role", role).put("active", true); member.set("scope", scope.deepCopy());}
        value.putObject("binding").put("registryId", "registry").put("scopeKind", kind).put("ownerId", owner)
                .put("namespaceId", "10").put("namespaceSlug", key.namespaceSlug()).put("membershipRevision", "0");
        response.set(value.toString());
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); SecurityContextHolder.clearContext(); server.stop(0); }
    @Test void nativeOwnerAndRolesCannotReplaceExplicitMaintainerAndRevocationIsImmediate() {
        assertThat(checker.canSubmitForReview(skill, "local", Map.of(10L, NamespaceRole.OWNER), Set.of("SUPER_ADMIN"))).isFalse();
        when(maintainers.isActive(10L, 20L, "subject", "local")).thenReturn(true);
        assertThat(checker.canSubmitForReview(skill, "local", Map.of(), Set.of())).isTrue();
        when(maintainers.isActive(10L, 20L, "subject", "local")).thenReturn(false);
        assertThat(checker.canSubmitForReview(skill, "local", Map.of(), Set.of())).isFalse();
        assertThat(calls).hasValue(3);
        assertThat(sent.get()).contains("\"subject\":\"subject\"").doesNotContain("SUPER_ADMIN", "local");
    }
    @Test void liveScopeRevocationAndWrongActorNeverUseNativeRoleFallback() {
        when(maintainers.isActive(10L, 20L, "subject", "local")).thenReturn(true);
        responseStatus.set(403);
        assertThatThrownBy(() -> checker.canSubmitForReview(skill, "local", Map.of(10L, NamespaceRole.OWNER), Set.of("SUPER_ADMIN")))
                .isInstanceOf(SlarkAssetAuthorizationFailure.class);
        assertThatThrownBy(() -> checker.canSubmitForReview(skill, "other-local", Map.of(), Set.of("SUPER_ADMIN")))
                .isInstanceOf(SlarkAssetAuthorizationFailure.class);
    }
    @Test void currentScopeAdminsCanSelfReviewButPlatformAdminCannotCrossPrivateScope() {
        var review = new ReviewTask(30L, 20L, 10L, "1.0.0", "local");
        scope("project", "project", "member", true);
        assertThat(checker.canReview(review, "local", NamespaceType.TEAM, Map.of(10L, NamespaceRole.OWNER), Set.of("SUPER_ADMIN"))).isFalse();
        scope("project", "project", "admin", false);
        assertThat(checker.canReview(review, "local", NamespaceType.TEAM, Map.of(), Set.of())).isTrue();
        scope("public", "public", null, true);
        assertThat(checker.canReview(review, "local", NamespaceType.GLOBAL, Map.of(), Set.of())).isTrue();
        scope("public", "public", null, false);
        assertThat(checker.canReview(review, "local", NamespaceType.GLOBAL, Map.of(), Set.of("SUPER_ADMIN"))).isFalse();
    }
    @Test void preparedReviewBodyStaysClosedEvenForSubmitterAndReviewer() {
        scope("public", "public", null, true);
        var review = new ReviewTask(30L, 20L, 10L, "1.0.0", "local");
        assertThat(checker.canViewReview(review, "local", NamespaceType.GLOBAL, Map.of(), Set.of("SUPER_ADMIN"))).isFalse();
    }
    @Test void legacySubmissionStillNeedsAssetMaintainerAndPersonalCannotCrossOwner() {
        assertThat(checker.canSubmitReview(skill, "local", Map.of(10L, NamespaceRole.MEMBER))).isFalse();
        when(maintainers.isActive(10L, 20L, "subject", "local")).thenReturn(true);
        assertThat(checker.canSubmitReview(skill, "local", Map.of())).isTrue();
        scope("personal", "subject", null, false);
        assertThat(checker.canSubmitForReview(skill, "local", Map.of(), Set.of())).isTrue();
        scope("personal", "other-subject", null, true);
        assertThatThrownBy(() -> checker.canSubmitForReview(skill, "local", Map.of(), Set.of("SUPER_ADMIN"))).isInstanceOf(SlarkAssetAuthorizationFailure.class);
    }
    @Test void nativeNamespaceRetainsExistingChecksWithoutSlarkNetwork() {
        when(bindings.findByNamespaceId(10L)).thenReturn(Optional.empty());
        assertThat(checker.canSubmitForReview(skill, "local", Map.of(), Set.of())).isTrue();
        assertThat(checker.canSubmitReview(skill, "other", Map.of(10L, NamespaceRole.MEMBER))).isTrue();
        verifyNoInteractions(maintainers);
        assertThat(calls).hasValue(0);
    }
    void deniedLocally(int status) {
        assertThatThrownBy(() -> service.can(SlarkAssetAuthorization.Operation.SUBMIT, 10L, 20L, "local"))
                .isInstanceOfSatisfying(SlarkAssetAuthorizationFailure.class, e -> assertThat(e.statusCode()).isEqualTo(status));
    }
    @Test void missingSessionAndNativeOrMismatchedPrincipalCannotBorrowSlarkGrant() {
        request.getSession().removeAttribute(SlarkBridgeService.SESSION_BINDING);
        deniedLocally(401);
        request.getSession().setAttribute(SlarkBridgeService.SESSION_BINDING, new SlarkBridgeService.SessionBinding("local", grant));
        request.getSession().setAttribute("platformPrincipal", new PlatformPrincipal("local", "Human", null, null, "github", Set.of("SUPER_ADMIN")));
        deniedLocally(401);
        var mismatched = new PlatformPrincipal("other-local", "Other", null, null, "slark", Set.of("SUPER_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(mismatched, null, java.util.List.of()));
        deniedLocally(401);
        assertThat(calls).hasValue(0);
        verifyNoInteractions(maintainers);
    }
    @Test void expiredGrantAndApiOrMockHeadersCannotUseSessionAuthority() {
        request.addHeader("Authorization", "Bearer native-token"); deniedLocally(401);
        request.removeHeader("Authorization"); request.addHeader("X-Mock-User-Id", "local"); deniedLocally(401);
        request.removeHeader("X-Mock-User-Id");
        var expired = new SlarkBridgeClient.Grant("subject", 7, grant.bridgeSessionId(), "Human", 1L, "reviews", "/");
        request.getSession().setAttribute(SlarkBridgeService.SESSION_BINDING, new SlarkBridgeService.SessionBinding("local", expired));
        deniedLocally(401);
        assertThat(calls).hasValue(0);
    }
    @Test void localDeactivationSystemAccountOrIdentityRemapInvalidatesGrantBeforeHttp() {
        var disabled = new UserAccount("local", "Human", null, null);
        disabled.setStatus(UserStatus.DISABLED);
        when(users.findById("local")).thenReturn(Optional.of(disabled)); deniedLocally(401);
        when(users.findById("local")).thenReturn(Optional.of(UserAccount.systemAccount("local", "System", null, null))); deniedLocally(401);
        when(users.findById("local")).thenReturn(Optional.of(new UserAccount("local", "Human", null, null)));
        var remapped = mock(IdentityBinding.class); when(remapped.getUserId()).thenReturn("other-local");
        when(identities.findByProviderCodeAndSubject("slark", "subject")).thenReturn(Optional.of(remapped)); deniedLocally(401);
        assertThat(calls).hasValue(0);
    }
    @Test void authorityFailuresAreClosedAndDoNotDiscloseRemoteBody() {
        when(clients.getIfAvailable()).thenReturn(null); deniedLocally(503);
        when(clients.getIfAvailable()).thenReturn(client);
        responseStatus.set(503); response.set("remote-private-detail"); deniedLocally(503);
        responseStatus.set(200); response.set("{malformed}"); deniedLocally(503);
        responseStatus.set(401); deniedLocally(401);
        assertThat(calls).hasValue(3);
        verifyNoInteractions(maintainers);
    }
    @Test void assetNamespaceMismatchAndLegacyAssetlessSubmissionNeverReachAuthority() {
        when(skills.findById(20L)).thenReturn(Optional.of(new Skill(11L,"other","local",SkillVisibility.PUBLIC)));
        assertThat(service.can(SlarkAssetAuthorization.Operation.SUBMIT,10L,20L,"local")).contains(false);
        assertThat(checker.canSubmitReview(10L,Map.of(10L,NamespaceRole.OWNER))).isFalse();
        assertThat(calls).hasValue(0);
    }
    @Test void creatorInitializationUsesTrustedExternalSubjectAndRejectsOwnerSubstitution() {
        service.initializeCreator(skill,"local");
        verify(maintainers).initializeCreator(10L,20L,"subject","local");
        assertThatThrownBy(() -> service.initializeCreator(skill,"other-local")).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        verifyNoMoreInteractions(maintainers);
    }
    @Test void actualReviewServiceBothSubmissionOverloadsRequireLiveMaintainerAndPreserveFrozenGuard() {
        var versions = mock(SkillVersionRepository.class);
        var tasks = mock(com.iflytek.skillhub.domain.review.ReviewTaskRepository.class);
        var namespaces = mock(NamespaceRepository.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var version = new SkillVersion(20L,"1.0.0","local");
        org.springframework.test.util.ReflectionTestUtils.setField(version,"id",30L);
        when(versions.findById(30L)).thenReturn(Optional.of(version));
        var namespace = new Namespace(binding.namespaceSlug(),"Project","local");
        when(namespaces.findById(10L)).thenReturn(Optional.of(namespace));
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        var reviews = new com.iflytek.skillhub.domain.review.ReviewService(tasks,versions,skills,namespaces,checker,events,
            new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.iflytek.skillhub.domain.skill.service.SkillGovernanceService.class),
            mock(com.iflytek.skillhub.domain.governance.GovernanceNotificationService.class),mock(jakarta.persistence.EntityManager.class),java.time.Clock.systemUTC());
        skill.setVisibility(SkillVisibility.NAMESPACE_ONLY);
        // ACTIVE is a policy-isolation fixture only. Production Slark namespaces remain FROZEN.
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of(10L,NamespaceRole.OWNER))).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of(10L,NamespaceRole.OWNER),Set.of("SUPER_ADMIN"))).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        verify(versions,never()).save(any()); verify(tasks,never()).save(any()); verifyNoInteractions(events);
        when(maintainers.isActive(10L,20L,"subject","local")).thenReturn(true);
        assertThat(reviews.submitReview(30L,"local",Map.of()).getSubmittedBy()).isEqualTo("local");
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PENDING_REVIEW);
        version.setStatus(SkillVersionStatus.DRAFT);
        responseStatus.set(403);
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of(),Set.of())).isInstanceOf(SlarkAssetAuthorizationFailure.class);
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.DRAFT);
        responseStatus.set(200); namespace.setStatus(NamespaceStatus.FROZEN);
        int before = calls.get();
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of())).isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException.class);
        assertThat(calls).hasValue(before);
        verify(versions,times(1)).save(any()); verify(tasks,times(1)).save(any());
    }

    @Test void actualApprovalRecordsSelfReviewerButCannotBypassScopeScanningOrFrozenNamespace() {
        var versions = mock(SkillVersionRepository.class);
        var tasks = mock(com.iflytek.skillhub.domain.review.ReviewTaskRepository.class);
        var namespaces = mock(NamespaceRepository.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var version = new SkillVersion(20L,"1.0.0","local");
        org.springframework.test.util.ReflectionTestUtils.setField(version,"id",30L);
        version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        when(versions.findById(30L)).thenReturn(Optional.of(version));
        var task = new ReviewTask(30L,20L,10L,"1.0.0","local");
        org.springframework.test.util.ReflectionTestUtils.setField(task,"id",40L);
        when(tasks.findById(40L)).thenReturn(Optional.of(task));
        when(tasks.updateStatusWithVersion(eq(40L),any(),eq("local"),eq("approved"),anyInt())).thenReturn(1);
        var namespace = new Namespace(binding.namespaceSlug(),"Project","local");
        when(namespaces.findById(10L)).thenReturn(Optional.of(namespace));
        var instant = java.time.Instant.parse("2026-10-15T04:00:00Z");
        var reviews = new com.iflytek.skillhub.domain.review.ReviewService(tasks,versions,skills,namespaces,checker,events,
            new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.iflytek.skillhub.domain.skill.service.SkillGovernanceService.class),
            mock(com.iflytek.skillhub.domain.governance.GovernanceNotificationService.class),mock(jakarta.persistence.EntityManager.class),java.time.Clock.fixed(instant,java.time.ZoneOffset.UTC));
        scope("project","project","member",true);
        assertThatThrownBy(() -> reviews.approveReview(40L,"local","approved",Map.of(10L,NamespaceRole.OWNER),Set.of("SUPER_ADMIN")))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException.class);
        scope("project","project","admin",false);
        version.setStatus(SkillVersionStatus.SCANNING);
        assertThatThrownBy(() -> reviews.approveReview(40L,"local","approved",Map.of(),Set.of()))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException.class);
        version.setStatus(SkillVersionStatus.PENDING_REVIEW); namespace.setStatus(NamespaceStatus.FROZEN);
        assertThatThrownBy(() -> reviews.approveReview(40L,"local","approved",Map.of(),Set.of()))
                .isInstanceOf(com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException.class);
        verify(tasks,never()).updateStatusWithVersion(anyLong(),any(),anyString(),anyString(),anyInt());
        verify(versions,never()).save(any()); verifyNoInteractions(events);
        skill.setVisibility(SkillVisibility.NAMESPACE_ONLY);
        // Isolated ACTIVE fixture exercises the reviewer policy; it does not activate a binding.
        namespace.setStatus(NamespaceStatus.ACTIVE);
        var approved = reviews.approveReview(40L,"local","approved",Map.of(),Set.of());
        assertThat(approved.getReviewedBy()).isEqualTo("local");
        assertThat(approved.getReviewedAt()).isEqualTo(instant);
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
        verify(tasks).updateStatusWithVersion(40L,com.iflytek.skillhub.domain.review.ReviewTaskStatus.APPROVED,"local","approved",task.getVersion());
    }

    @Test void unreadableBindingOrMaintainerDatabaseNeverFallsBackToNativeOwner() {
        when(bindings.findByNamespaceId(10L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        deniedLocally(503);
        assertThat(calls).hasValue(0);
        doReturn(Optional.of(binding)).when(bindings).findByNamespaceId(10L);
        when(maintainers.isActive(10L,20L,"subject","local")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        deniedLocally(503);
        assertThat(calls).hasValue(1);
    }

    @Test void projectPublicCandidateCannotBeSubmittedThroughEitherReviewOverload() {
        when(maintainers.isActive(10L,20L,"subject","local")).thenReturn(true);
        var versions = mock(SkillVersionRepository.class);
        var tasks = mock(com.iflytek.skillhub.domain.review.ReviewTaskRepository.class);
        var namespaces = mock(NamespaceRepository.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var version = new SkillVersion(20L,"1.0.0","local");
        when(versions.findById(30L)).thenReturn(Optional.of(version));
        when(namespaces.findById(10L)).thenReturn(Optional.of(new Namespace("project","Project","local")));
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        var reviews = new com.iflytek.skillhub.domain.review.ReviewService(tasks,versions,skills,namespaces,checker,events,
            new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.iflytek.skillhub.domain.skill.service.SkillGovernanceService.class),
            mock(com.iflytek.skillhub.domain.governance.GovernanceNotificationService.class),mock(jakarta.persistence.EntityManager.class),java.time.Clock.systemUTC());
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of()))
            .isInstanceOf(SlarkAssetAuthorizationFailure.class);
        assertThatThrownBy(() -> reviews.submitReview(30L,"local",Map.of(),Set.of("SUPER_ADMIN")))
            .isInstanceOf(SlarkAssetAuthorizationFailure.class);
        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.DRAFT);
        verify(versions,never()).save(any()); verify(tasks,never()).save(any()); verifyNoInteractions(events);
    }

}
