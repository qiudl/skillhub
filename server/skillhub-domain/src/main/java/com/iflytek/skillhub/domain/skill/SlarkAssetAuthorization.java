package com.iflytek.skillhub.domain.skill;

import java.util.Optional;

/** Live role eligibility for bound assets, independent of frozen state, scans and version state.
 * Empty means a native namespace; a failure must never be converted to native-role fallback. */
public interface SlarkAssetAuthorization {
    enum Operation { CREATE, SUBMIT, REVIEW, READ_REVIEW }
    Optional<Boolean> can(Operation operation, Long namespaceId, Long skillId, String expectedLocalUserId);
    /** Enforce the immutable scope visibility mapping; native namespaces retain their own policy.
     * This is a content boundary, not a grant. Callers must also check live actor eligibility. */
    void assertVisibility(Long namespaceId, SkillVisibility visibility);
    /** Only the new-asset creation path may call this, inside its creation transaction. */
    void initializeCreator(Skill skill, String localUserId);
}
