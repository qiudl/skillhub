package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBindingRepository;
import com.iflytek.skillhub.domain.skill.SlarkAssetAuthorization;
import com.iflytek.skillhub.domain.skill.SlarkAssetMaintainerRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** REQ-20261013-0021 M1-10. Native roles never supply Slark scope or maintainer rights.
 * Namespace writability and pinned version/scanner checks remain mandatory in operation services. */
@Service
public class SlarkAssetAuthorizationService implements SlarkAssetAuthorization {
    private final SlarkNamespaceBindingRepository bindings;
    private final SlarkAssetMaintainerRepository maintainers;
    private final SkillRepository skills;
    private final UserAccountRepository users;
    private final IdentityBindingRepository identities;
    private final ObjectProvider<SlarkScopeAuthorityClient> clients;

    public SlarkAssetAuthorizationService(SlarkNamespaceBindingRepository bindings,
            SlarkAssetMaintainerRepository maintainers, SkillRepository skills, UserAccountRepository users,
            IdentityBindingRepository identities, ObjectProvider<SlarkScopeAuthorityClient> clients) {
        this.bindings = bindings; this.maintainers = maintainers; this.skills = skills;
        this.users = users; this.identities = identities; this.clients = clients;
    }

    @Override public Optional<Boolean> can(Operation operation, Long namespaceId, Long skillId, String expectedLocalUserId) {
        try {
            var binding = bindings.findByNamespaceId(namespaceId);
            if (binding.isEmpty()) return Optional.empty();
            if (operation == null) return Optional.of(false);
            // Eligibility must not expose staged review content before activation.
            if (operation == Operation.READ_REVIEW && "PREPARED".equals(binding.get().provisionState())) return Optional.of(false);
            if (operation == Operation.SUBMIT && (skillId == null || expectedLocalUserId == null)) return Optional.of(false);
            if (skillId != null && skills.findById(skillId).filter(s -> namespaceId.equals(s.getNamespaceId())).isEmpty())
                return Optional.of(false);
            var actor = resolve(binding.get(), expectedLocalUserId);
            var facts = actor.facts();
            if (facts.scope().scopeKind().equals("personal"))
                return Optional.of(facts.subject().equals(facts.scope().ownerId()));
            boolean reviewer = facts.scope().scopeKind().equals("public") ? facts.platformAdmin()
                    : "owner".equals(facts.membershipRole()) || "admin".equals(facts.membershipRole());
            return Optional.of(switch (operation) {
                case CREATE -> true;
                case REVIEW -> reviewer;
                case SUBMIT -> maintainers.isActive(namespaceId, skillId, facts.subject(), actor.localUserId());
                case READ_REVIEW -> reviewer || (skillId != null && maintainers.isActive(namespaceId, skillId, facts.subject(), actor.localUserId()));
            });
        } catch (SlarkAssetAuthorizationFailure failure) { throw failure; }
        catch (Exception unavailable) { throw SlarkAssetAuthorizationFailure.unavailable(); }
    }

    @Override public void assertVisibility(Long namespaceId, SkillVisibility visibility) {
        try {
            var binding = bindings.findByNamespaceId(namespaceId);
            if (binding.isEmpty()) return;
            SkillVisibility required = switch (binding.get().scopeKind()) {
                case "public" -> SkillVisibility.PUBLIC;
                case "organization", "project" -> SkillVisibility.NAMESPACE_ONLY;
                case "personal" -> SkillVisibility.PRIVATE;
                default -> throw SlarkAssetAuthorizationFailure.unavailable();
            };
            if (visibility != required) throw SlarkAssetAuthorizationFailure.denied();
        } catch (SlarkAssetAuthorizationFailure failure) { throw failure; }
        catch (Exception unavailable) { throw SlarkAssetAuthorizationFailure.unavailable(); }
    }

    @Override public void initializeCreator(Skill skill, String localUserId) {
        try {
            var binding = bindings.findByNamespaceId(skill.getNamespaceId());
            if (binding.isEmpty()) return;
            if (!localUserId.equals(skill.getOwnerId())) throw SlarkAssetAuthorizationFailure.denied();
            var actor = resolve(binding.get(), localUserId);
            maintainers.initializeCreator(skill.getNamespaceId(), skill.getId(), actor.facts().subject(), actor.localUserId());
        } catch (SlarkAssetAuthorizationFailure failure) { throw failure; }
        catch (Exception unavailable) { throw SlarkAssetAuthorizationFailure.unavailable(); }
    }

    private record Actor(String localUserId, SlarkScopeAuthorityClient.Facts facts) {}
    private Actor resolve(SlarkNamespaceBinding binding, String expectedLocalUserId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw SlarkAssetAuthorizationFailure.invalid();
        var request = attributes.getRequest();
        if (request.getHeader("Authorization") != null || request.getHeader("X-Mock-User-Id") != null)
            throw SlarkAssetAuthorizationFailure.invalid();
        var session = request.getSession(false);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principalValue = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)
                ? authentication.getPrincipal() : session == null ? null : session.getAttribute("platformPrincipal");
        Object stored = session == null ? null : session.getAttribute(SlarkBridgeService.SESSION_BINDING);
        if (!(principalValue instanceof PlatformPrincipal principal) || !"slark".equals(principal.oauthProvider())
                || !(stored instanceof SlarkBridgeService.SessionBinding bridge)
                || !principal.userId().equals(bridge.localUserId())
                || (expectedLocalUserId != null && !expectedLocalUserId.equals(principal.userId()))
                || bridge.grant().expiresAt() <= System.currentTimeMillis()) throw SlarkAssetAuthorizationFailure.invalid();
        var user = users.findById(principal.userId());
        if (user.isEmpty() || !user.get().isActive() || user.get().isSystemAccount()
                || identities.findByProviderCodeAndSubject("slark", bridge.grant().subject())
                    .filter(identity -> principal.userId().equals(identity.getUserId())).isEmpty())
            throw SlarkAssetAuthorizationFailure.invalid();
        var client = clients.getIfAvailable();
        if (client == null) throw SlarkAssetAuthorizationFailure.unavailable();
        try {
            var facts = client.check(bridge.grant(), binding);
            if (bridge.grant().expiresAt() <= System.currentTimeMillis()) throw SlarkAssetAuthorizationFailure.invalid();
            return new Actor(principal.userId(), facts);
        } catch (SlarkBridgeFailure failure) {
            if (failure.status() == 401) throw SlarkAssetAuthorizationFailure.invalid();
            if (failure.status() == 403) throw SlarkAssetAuthorizationFailure.denied();
            throw SlarkAssetAuthorizationFailure.unavailable();
        }
    }
}
