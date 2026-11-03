package com.iflytek.skillhub.domain.skill.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.review.ReviewSubjectType;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.util.Objects;

/** Shared submission/confirmation locking inside the caller's transaction.
 * Refresh replaces stale managed state after waits. These paths never mutate existing
 * review tasks, so they must not acquire task locks after namespace/version/asset locks.
 */
public final class SkillSubmissionGuard {
    private final NamespaceRepository namespaces;
    private final ReviewTaskRepository tasks;
    private final EntityManager entityManager;

    public SkillSubmissionGuard(NamespaceRepository namespaces, ReviewTaskRepository tasks, EntityManager entityManager) {
        this.namespaces = namespaces;
        this.tasks = tasks;
        this.entityManager = entityManager;
    }

    public void lock(Skill skill, SkillVersion version) {
        Long skillId = skill.getId();
        Long namespaceId = skill.getNamespaceId();
        Long versionId = version.getId();
        String slug = skill.getSlug();
        String versionName = version.getVersion();
        if (!Objects.equals(version.getSkillId(), skillId)) {
            throw new DomainBadRequestException("error.skill.version.mismatch");
        }
        var namespace = namespaces.findById(namespaceId)
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", namespaceId));
        try {
            entityManager.refresh(namespace, LockModeType.PESSIMISTIC_READ);
            entityManager.refresh(version, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(skill, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException ex) {
            throw new DomainNotFoundException("skill_version.not_found", versionId);
        }
        if (!Objects.equals(skill.getNamespaceId(), namespaceId)
                || !Objects.equals(skill.getSlug(), slug)
                || !Objects.equals(version.getSkillId(), skillId)
                || !Objects.equals(version.getVersion(), versionName)) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
        if (namespace.getStatus() != NamespaceStatus.ACTIVE) {
            throw new DomainBadRequestException(namespace.getStatus() == NamespaceStatus.FROZEN
                    ? "error.namespace.frozen" : "error.namespace.archived", namespace.getSlug());
        }
        if (skill.getStatus() == SkillStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.skill.publish.archived", slug);
        }
        // Presence queries do not lock or modify history. A legacy/typed mismatch must
        // not allow an existing pending round to be bypassed by direct confirmation.
        if (tasks.findBySkillVersionIdAndStatus(versionId, ReviewTaskStatus.PENDING).isPresent()
                || tasks.findBySubjectTypeAndSubjectVersionIdAndStatus(
                        ReviewSubjectType.SKILL_VERSION, versionId, ReviewTaskStatus.PENDING).isPresent()) {
            throw new DomainBadRequestException("review.submit.duplicate", versionId);
        }
    }
}
