package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SlarkAssetAuthorization;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Centralizes review and promotion permission checks derived from namespace and platform roles.
 */
@Component
public class ReviewPermissionChecker {
    private final SlarkAssetAuthorization slark;
    public ReviewPermissionChecker(SlarkAssetAuthorization slark) { this.slark = slark; }

    public void assertCandidateVisibility(Skill skill, SkillVersion version) {
        slark.assertVisibility(skill.getNamespaceId(), version.getRequestedVisibility() == null
                ? skill.getVisibility() : version.getRequestedVisibility());
    }

    /** Withdrawal retains the native submitter restriction and requires current
     * asset maintenance eligibility for a Slark-bound namespace. */
    public boolean canWithdrawReview(ReviewTask task, String userId) {
        if (!task.getSubmittedBy().equals(userId)) return false;
        return slark.can(SlarkAssetAuthorization.Operation.SUBMIT,
                task.getNamespaceId(), task.getSkillId(), userId).orElse(true);
    }

    public boolean canSubmitReview(Skill skill, String userId, Map<Long, NamespaceRole> roles) {
        var decision = slark.can(SlarkAssetAuthorization.Operation.SUBMIT, skill.getNamespaceId(), skill.getId(), userId);
        return decision.orElseGet(() -> hasNamespaceMembership(skill.getNamespaceId(), roles));
    }

    public boolean canSubmitReview(Long namespaceId,
                                   Map<Long, NamespaceRole> userNamespaceRoles) {
        // Namespace-only legacy calls cannot establish an asset maintainer grant.
        var decision = slark.can(SlarkAssetAuthorization.Operation.SUBMIT, namespaceId, null, null);
        return decision.orElseGet(() -> hasNamespaceMembership(namespaceId, userNamespaceRoles));
    }

    private boolean hasNamespaceMembership(Long namespaceId, Map<Long, NamespaceRole> userNamespaceRoles) {
        NamespaceRole role = userNamespaceRoles.get(namespaceId);
        return role == NamespaceRole.OWNER
                || role == NamespaceRole.ADMIN
                || role == NamespaceRole.MEMBER;
    }

    public boolean canReview(ReviewTask task,
                             String userId,
                             NamespaceType namespaceType,
                             Map<Long, NamespaceRole> userNamespaceRoles,
                             Set<String> platformRoles) {
        var decision = slark.can(SlarkAssetAuthorization.Operation.REVIEW, task.getNamespaceId(), task.getSkillId(), userId);
        if (decision.isPresent()) return decision.get();
        if (task.getSubmittedBy().equals(userId)) {
            return platformRoles.contains("SUPER_ADMIN")
                    || canSelfReviewNamespace(task.getNamespaceId(), namespaceType, userNamespaceRoles);
        }
        return canReviewNamespace(task.getNamespaceId(), namespaceType, userNamespaceRoles, platformRoles);
    }

    public boolean canSubmitForReview(Skill skill,
                                      String userId,
                                      Map<Long, NamespaceRole> userNamespaceRoles,
                                      Set<String> platformRoles) {
        var decision = slark.can(SlarkAssetAuthorization.Operation.SUBMIT, skill.getNamespaceId(), skill.getId(), userId);
        if (decision.isPresent()) return decision.get();
        if (skill.getOwnerId().equals(userId)) {
            return true;
        }
        if (hasPlatformReviewRole(platformRoles)) {
            return true;
        }

        NamespaceRole role = userNamespaceRoles.get(skill.getNamespaceId());
        return role == NamespaceRole.ADMIN || role == NamespaceRole.OWNER;
    }

    public boolean canViewReview(ReviewTask task,
                                 String userId,
                                 NamespaceType namespaceType,
                                 Map<Long, NamespaceRole> userNamespaceRoles,
                                 Set<String> platformRoles) {
        var decision = slark.can(SlarkAssetAuthorization.Operation.READ_REVIEW, task.getNamespaceId(), task.getSkillId(), userId);
        if (decision.isPresent()) return decision.get();
        if (task.getSubmittedBy().equals(userId)) {
            return true;
        }
        return canReview(task, userId, namespaceType, userNamespaceRoles, platformRoles);
    }

    public boolean canReviewNamespace(Long namespaceId,
                                      NamespaceType namespaceType,
                                      Map<Long, NamespaceRole> userNamespaceRoles,
                                      Set<String> platformRoles) {
        var decision = slark.can(SlarkAssetAuthorization.Operation.READ_REVIEW, namespaceId, null, null);
        if (decision.isPresent()) return decision.get();
        if (hasPlatformReviewRole(platformRoles)) {
            return true;
        }
        if (namespaceType == NamespaceType.GLOBAL) {
            return false;
        }

        NamespaceRole role = userNamespaceRoles.get(namespaceId);
        return role == NamespaceRole.OWNER || role == NamespaceRole.ADMIN;
    }

    public boolean canManageNamespaceReviews(Long namespaceId,
                                             NamespaceType namespaceType,
                                             Map<Long, NamespaceRole> userNamespaceRoles,
                                             Set<String> platformRoles) {
        return canReviewNamespace(namespaceId, namespaceType, userNamespaceRoles, platformRoles);
    }

    public boolean canReadReview(ReviewTask task,
                                 String userId,
                                 NamespaceType namespaceType,
                                 Map<Long, NamespaceRole> userNamespaceRoles,
                                 Set<String> platformRoles) {
        return canViewReview(task, userId, namespaceType, userNamespaceRoles, platformRoles);
    }

    public boolean canSubmitPromotion(Skill sourceSkill,
                                      String userId,
                                      Map<Long, NamespaceRole> userNamespaceRoles,
                                      Set<String> platformRoles) {
        return canSubmitForReview(sourceSkill, userId, userNamespaceRoles, platformRoles);
    }

    public boolean canSubmitPromotion(Skill sourceSkill,
                                      String userId,
                                      Map<Long, NamespaceRole> userNamespaceRoles) {
        return canSubmitPromotion(sourceSkill, userId, userNamespaceRoles, Set.of());
    }

    public boolean canReviewPromotion(
            PromotionRequest request,
            String userId,
            Set<String> platformRoles) {
        if (request.getSubmittedBy().equals(userId)) {
            return platformRoles.contains("SUPER_ADMIN");
        }
        return hasPlatformReviewRole(platformRoles);
    }

    public boolean canViewPromotion(PromotionRequest request,
                                    String userId,
                                    Set<String> platformRoles) {
        if (request.getSubmittedBy().equals(userId)) {
            return true;
        }
        return canReviewPromotion(request, userId, platformRoles);
    }

    public boolean canListPendingPromotions(Set<String> platformRoles) {
        return hasPlatformReviewRole(platformRoles);
    }

    public boolean canReadPromotion(PromotionRequest request,
                                    String userId,
                                    Set<String> platformRoles) {
        return canViewPromotion(request, userId, platformRoles);
    }

    private boolean hasPlatformReviewRole(Set<String> platformRoles) {
        return platformRoles.contains("SKILL_ADMIN")
                || platformRoles.contains("SUPER_ADMIN");
    }

    private boolean canSelfReviewNamespace(Long namespaceId,
                                           NamespaceType namespaceType,
                                           Map<Long, NamespaceRole> userNamespaceRoles) {
        if (namespaceType == NamespaceType.GLOBAL) {
            return false;
        }

        NamespaceRole role = userNamespaceRoles.get(namespaceId);
        return role == NamespaceRole.OWNER || role == NamespaceRole.ADMIN;
    }
}
