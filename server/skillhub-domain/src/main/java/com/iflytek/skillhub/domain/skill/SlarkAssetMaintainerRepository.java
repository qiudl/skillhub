package com.iflytek.skillhub.domain.skill;

/** SkillHub is the sole maintainer authority. Subject and local identity must match the bridge. */
public interface SlarkAssetMaintainerRepository {
    boolean isActive(Long namespaceId, Long skillId, String subject, String localUserId);
    void initializeCreator(Long namespaceId, Long skillId, String subject, String localUserId);
    /** Trusted administration only; callers must first check live scope-admin eligibility.
     * No public endpoint is exposed by this repository. */
    void setActive(Long namespaceId, Long skillId, String subject, String localUserId,
                   boolean active, String actorSubject, String actorLocalUserId);
}
