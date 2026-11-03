package com.iflytek.skillhub.domain.skill.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.event.SkillStatusChangedEvent;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.review.ReviewSubjectType;
import com.iflytek.skillhub.domain.skill.SlarkAssetAuthorization;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Handles governance-oriented mutations on skills and versions, including
 * hiding, archiving, restoring, and destructive cleanup.
 * EntityManager refresh is required for deletion because callers may have loaded stale entities
 * before a competing transaction committed. Locks live through the enclosing audit transaction.
 */
@Service
public class SkillGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(SkillGovernanceService.class);

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillFileRepository skillFileRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ObjectStorageService objectStorageService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityScanService securityScanService;
    private final SkillStorageDeletionCompensationService compensationService;
    private final Clock clock;
    private final NamespaceRepository namespaceRepository;
    private final SlarkAssetAuthorization slarkAssetAuthorization;
    @PersistenceContext private EntityManager entityManager;

    public SkillGovernanceService(SkillRepository skillRepository,
                                  SkillVersionRepository skillVersionRepository,
                                  SkillFileRepository skillFileRepository,
                                  ReviewTaskRepository reviewTaskRepository,
                                  ObjectStorageService objectStorageService,
                                  AuditLogService auditLogService,
                                  ApplicationEventPublisher eventPublisher,
                                  SecurityScanService securityScanService,
                                  SkillStorageDeletionCompensationService compensationService,
                                  Clock clock, NamespaceRepository namespaceRepository,
                                  SlarkAssetAuthorization slarkAssetAuthorization) {
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillFileRepository = skillFileRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.objectStorageService = objectStorageService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.securityScanService = securityScanService;
        this.compensationService = compensationService;
        this.clock = clock;
        this.namespaceRepository = namespaceRepository;
        this.slarkAssetAuthorization = slarkAssetAuthorization;
    }

    @Transactional
    public Skill hideSkill(Long skillId, String actorUserId, String clientIp, String userAgent, String reason) {
        Skill skill = lockAsset(skillId, actorUserId);
        skill.setHidden(true);
        skill.setHiddenAt(currentInstant());
        skill.setHiddenBy(actorUserId);
        skill.setUpdatedBy(actorUserId);
        Skill saved = skillRepository.save(skill);
        auditLogService.record(actorUserId, "HIDE_SKILL", "SKILL", skillId, null, clientIp, userAgent, jsonReason(reason));
        return saved;
    }

    @Transactional
    public Skill archiveSkill(Long skillId,
                              String actorUserId,
                              Map<Long, NamespaceRole> userNamespaceRoles,
                              String clientIp,
                              String userAgent,
                              String reason) {
        Skill skill = lockAsset(skillId, actorUserId);
        assertCanManageLifecycle(skill, actorUserId, userNamespaceRoles);
        return archiveSkillInternal(skill, actorUserId, clientIp, userAgent, reason);
    }

    @Transactional
    public Skill archiveSkillAsAdmin(Long skillId,
                                     String actorUserId,
                                     String clientIp,
                                     String userAgent,
                                     String reason) {
        Skill skill = lockAsset(skillId, actorUserId);
        return archiveSkillInternal(skill, actorUserId, clientIp, userAgent, reason);
    }

    private Skill archiveSkillInternal(Skill skill,
                                       String actorUserId,
                                       String clientIp,
                                       String userAgent,
                                       String reason) {
        SkillStatus previousStatus = skill.getStatus();
        skill.setStatus(SkillStatus.ARCHIVED);
        skill.setUpdatedBy(actorUserId);
        Skill saved = skillRepository.save(skill);
        auditLogService.record(actorUserId, "ARCHIVE_SKILL", "SKILL", skill.getId(), null, clientIp, userAgent, jsonReason(reason));
        eventPublisher.publishEvent(new SkillStatusChangedEvent(skill.getId(), previousStatus, SkillStatus.ARCHIVED));
        return saved;
    }

    @Transactional
    public Skill unhideSkill(Long skillId, String actorUserId, String clientIp, String userAgent) {
        Skill skill = lockAsset(skillId, actorUserId);
        skill.setHidden(false);
        skill.setHiddenAt(null);
        skill.setHiddenBy(null);
        skill.setUpdatedBy(actorUserId);
        Skill saved = skillRepository.save(skill);
        auditLogService.record(actorUserId, "UNHIDE_SKILL", "SKILL", skillId, null, clientIp, userAgent, null);
        return saved;
    }

    @Transactional
    public Skill unarchiveSkill(Long skillId,
                                String actorUserId,
                                Map<Long, NamespaceRole> userNamespaceRoles,
                                String clientIp,
                                String userAgent) {
        Skill skill = lockAsset(skillId, actorUserId);
        assertCanManageLifecycle(skill, actorUserId, userNamespaceRoles);

        SkillStatus previousStatus = skill.getStatus();
        skill.setStatus(SkillStatus.ACTIVE);
        skill.setUpdatedBy(actorUserId);
        Skill saved = skillRepository.save(skill);
        auditLogService.record(actorUserId, "UNARCHIVE_SKILL", "SKILL", skillId, null, clientIp, userAgent, null);
        eventPublisher.publishEvent(new SkillStatusChangedEvent(skillId, previousStatus, SkillStatus.ACTIVE));
        return saved;
    }

    @Transactional
    public void deleteVersion(Skill skill,
                              SkillVersion version,
                              String actorUserId,
                              Map<Long, NamespaceRole> userNamespaceRoles,
                              String clientIp,
                              String userAgent,
                              String namespaceSlug) {
        // Match review decision order: task(s) -> namespace -> version -> skill -> scan audit.
        // Lock only this candidate: holding every version before its task would invert review locks.
        Long expectedSkillId = skill.getId();
        Long expectedNamespaceId = skill.getNamespaceId();
        Long expectedVersionId = version.getId();
        String expectedVersion = version.getVersion();
        var history = reviewTaskRepository.findBySkillVersionIdOrderByIdAsc(expectedVersionId);
        for (var task : history) {
            refreshForDeletion(task, LockModeType.PESSIMISTIC_WRITE, expectedVersionId);
            if (task.getSubjectType() != ReviewSubjectType.SKILL_VERSION
                    || !Objects.equals(task.getNamespaceId(), expectedNamespaceId)
                    || !Objects.equals(task.getSkillId(), expectedSkillId)
                    || !Objects.equals(task.getSubjectId(), expectedSkillId)
                    || !Objects.equals(task.getSkillVersionId(), expectedVersionId)
                    || !Objects.equals(task.getSubjectVersionId(), expectedVersionId)
                    || !Objects.equals(task.getSkillVersion(), expectedVersion)
                    || !Objects.equals(task.getSubjectVersion(), expectedVersion)) {
                throw new DomainBadRequestException("review.candidate.mismatch");
            }
        }
        Namespace namespace = namespaceRepository.findById(expectedNamespaceId)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.notFound", expectedNamespaceId));
        refreshForDeletion(namespace, LockModeType.PESSIMISTIC_READ, expectedVersionId);
        version = skillVersionRepository.findById(expectedVersionId)
                .orElseThrow(() -> new DomainNotFoundException("error.skill.version.notFound", expectedVersionId));
        refreshForDeletion(version, LockModeType.PESSIMISTIC_WRITE, expectedVersionId);
        skill = skillRepository.findById(expectedSkillId)
                .orElseThrow(() -> new DomainNotFoundException("error.skill.notFound", expectedSkillId));
        refreshForDeletion(skill, LockModeType.PESSIMISTIC_WRITE, expectedVersionId);
        if (!Objects.equals(expectedSkillId, version.getSkillId())
                || !Objects.equals(expectedNamespaceId, skill.getNamespaceId())
                || !Objects.equals(expectedVersion, version.getVersion())
                || !Objects.equals(namespace.getSlug(), namespaceSlug.startsWith("@") ? namespaceSlug.substring(1) : namespaceSlug)) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
        var eligibility = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                skill.getNamespaceId(), skill.getId(), actorUserId);
        if (eligibility.isPresent()) {
            if (!eligibility.get()) throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        } else {
            assertCanManageLifecycle(skill, actorUserId, userNamespaceRoles);
        }
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
        // Native governance supports cleanup of archived assets; bound assets remain read-only.
        if (eligibility.isPresent() && skill.getStatus() == SkillStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.skill.publish.archived", skill.getSlug());
        }
        if (history.stream().anyMatch(task -> task.getStatus() == ReviewTaskStatus.PENDING)) {
            throw new DomainBadRequestException("error.skill.version.delete.unsupported", version.getVersion());
        }
        if (version.getStatus() != SkillVersionStatus.DRAFT
                && version.getStatus() != SkillVersionStatus.REJECTED
                && version.getStatus() != SkillVersionStatus.SCAN_FAILED
                && version.getStatus() != SkillVersionStatus.UPLOADED) {
            throw new DomainBadRequestException("error.skill.version.delete.unsupported", version.getVersion());
        }

        long versionCount = skillVersionRepository.findBySkillId(skill.getId()).size();
        if (versionCount <= 1) {
            throw new DomainBadRequestException("error.skill.version.delete.lastVersion", version.getVersion());
        }

        // Rejected versions retain terminal review history whose FK must not outlive the version.
        // Delete exactly the locked history; a newly inserted reference must fail/rollback through
        // its FK, never acquire an unseen task lock after holding the version row.
        history.forEach(reviewTaskRepository::delete);
        List<SkillFile> files = skillFileRepository.findByVersionId(version.getId());
        List<String> storageKeys = new ArrayList<>();
        files.stream()
                .map(SkillFile::getStorageKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(storageKeys::add);
        storageKeys.add(buildBundleStorageKey(skill.getId(), version.getId()));
        deleteStorageAfterCommit(skill, namespaceSlug, storageKeys);
        skillFileRepository.deleteByVersionId(version.getId());
        securityScanService.softDeleteByVersionId(version.getId());
        // FK 约束 fk_skill_latest_version 阻止删除 skill_version 当 skill.latest_version_id 还指向它。
        // 必须先解开引用并 flush，让 PG 在 delete 时看不到引用。
        if (version.getId().equals(skill.getLatestVersionId())) {
            skill.setLatestVersionId(findLatestPublishedVersionId(skill.getId()));
            skill.setUpdatedBy(actorUserId);
            skillRepository.save(skill);
            skillRepository.flush();
        }
        skillVersionRepository.delete(version);
        auditLogService.record(
                actorUserId,
                "DELETE_SKILL_VERSION",
                "SKILL_VERSION",
                version.getId(),
                null,
                clientIp,
                userAgent,
                AuditDetail.of("version", version.getVersion())
        );
    }

    private void refreshForDeletion(Object entity, LockModeType mode, Long versionId) {
        try {
            entityManager.refresh(entity, mode);
        } catch (jakarta.persistence.EntityNotFoundException deleted) {
            throw new DomainNotFoundException("error.skill.version.notFound", versionId);
        }
    }

    private void deleteStorageAfterCommit(Skill skill, String namespaceSlug, List<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStorageWithCompensation(skill, namespaceSlug, storageKeys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStorageWithCompensation(skill, namespaceSlug, storageKeys);
            }
        });
    }

    private void deleteStorageWithCompensation(Skill skill, String namespaceSlug, List<String> storageKeys) {
        try {
            objectStorageService.deleteObjects(storageKeys);
        } catch (RuntimeException ex) {
            compensationService.recordFailure(
                    skill.getId(),
                    namespaceSlug,
                    skill.getSlug(),
                    storageKeys,
                    ex.getMessage()
            );
            log.error("Failed to delete version storage after commit [skillId={}, versionKeys={}]",
                    skill.getId(), storageKeys, ex);
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    @Transactional
    public SkillVersion withdrawPendingVersion(Skill skill,
                                               SkillVersion version,
                                               String actorUserId) {
        if (version.getStatus() != SkillVersionStatus.PENDING_REVIEW) {
            throw new DomainBadRequestException("review.withdraw.not_pending", version.getId());
        }
        version.setStatus(SkillVersionStatus.UPLOADED);
        SkillVersion savedVersion = skillVersionRepository.save(version);
        skill.setUpdatedBy(actorUserId);
        skillRepository.save(skill);
        return savedVersion;
    }

    /**
     * Yanks a published version on behalf of a platform admin. Callers are
     * expected to have enforced the admin role before invoking this method.
     */
    @Transactional
    public SkillVersion yankVersion(Long versionId, String actorUserId, String clientIp, String userAgent, String reason) {
        SkillVersion version = skillVersionRepository.findById(versionId)
            .orElseThrow(() -> new DomainNotFoundException("error.skill.version.notFound", versionId));
        Skill skill = skillRepository.findById(version.getSkillId())
            .orElseThrow(() -> new DomainNotFoundException("error.skill.notFound", version.getSkillId()));
        lockVersion(skill, version, actorUserId);
        return yankVersionInternal(version, actorUserId, clientIp, userAgent, reason);
    }

    /**
     * Yanks a published version on behalf of the skill owner or a namespace
     * ADMIN/OWNER, using the same lifecycle permission rule as archive and
     * delete-version.
     */
    @Transactional
    public SkillVersion yankVersion(Skill skill,
                                    SkillVersion version,
                                    String actorUserId,
                                    Map<Long, NamespaceRole> userNamespaceRoles,
                                    String clientIp,
                                    String userAgent,
                                    String reason) {
        lockVersion(skill, version, actorUserId);
        assertCanManageLifecycle(skill, actorUserId, userNamespaceRoles);
        return yankVersionInternal(version, actorUserId, clientIp, userAgent, reason);
    }

    private SkillVersion yankVersionInternal(SkillVersion version,
                                             String actorUserId,
                                             String clientIp,
                                             String userAgent,
                                             String reason) {
        Long versionId = version.getId();
        if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
            throw new DomainBadRequestException("error.skill.version.notPublished", version.getVersion());
        }
        version.setStatus(SkillVersionStatus.YANKED);
        version.setYankedAt(currentInstant());
        version.setYankedBy(actorUserId);
        version.setYankReason(reason);
        version.setDownloadReady(false);
        SkillVersion saved = skillVersionRepository.save(version);
        skillRepository.findById(version.getSkillId()).ifPresent(skill -> {
            if (versionId.equals(skill.getLatestVersionId())) {
                skill.setLatestVersionId(findLatestPublishedVersionId(skill.getId()));
                skill.setUpdatedBy(actorUserId);
                skillRepository.save(skill);
            }
        });
        auditLogService.record(actorUserId, "YANK_SKILL_VERSION", "SKILL_VERSION", versionId, null, clientIp, userAgent, jsonReason(reason));
        eventPublisher.publishEvent(new com.iflytek.skillhub.domain.event.SkillVersionYankedEvent(
                version.getSkillId(), versionId, actorUserId, true));
        return saved;
    }

    private Long findLatestPublishedVersionId(Long skillId) {
        return skillVersionRepository.findBySkillIdAndStatus(skillId, SkillVersionStatus.PUBLISHED).stream()
                .max(java.util.Comparator
                        .comparing(SkillVersion::getPublishedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(SkillVersion::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(SkillVersion::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(SkillVersion::getId)
                .orElse(null);
    }

    private Skill lockAsset(Long skillId, String actorUserId) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainNotFoundException("error.skill.notFound", skillId));
        Long namespaceId = skill.getNamespaceId();
        String slug = skill.getSlug();
        lockNamespace(namespaceId);
        refreshForDeletion(skill, LockModeType.PESSIMISTIC_WRITE, skillId);
        assertCandidate(skill, namespaceId, slug);
        assertCurrentGovernancePermission(skill, actorUserId);
        return skill;
    }

    private void lockVersion(Skill skill, SkillVersion version, String actorUserId) {
        Long namespaceId = skill.getNamespaceId();
        String slug = skill.getSlug();
        String versionName = version.getVersion();
        if (!Objects.equals(version.getSkillId(), skill.getId())) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
        lockNamespace(namespaceId);
        refreshForDeletion(version, LockModeType.PESSIMISTIC_WRITE, version.getId());
        refreshForDeletion(skill, LockModeType.PESSIMISTIC_WRITE, version.getId());
        assertCandidate(skill, namespaceId, slug);
        if (!Objects.equals(version.getSkillId(), skill.getId())
                || !Objects.equals(versionName, version.getVersion())) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
        assertCurrentGovernancePermission(skill, actorUserId);
    }

    private Namespace lockNamespace(Long namespaceId) {
        Namespace namespace = namespaceRepository.findById(namespaceId)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.notFound", namespaceId));
        refreshForDeletion(namespace, LockModeType.PESSIMISTIC_READ, namespaceId);
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
        return namespace;
    }

    private void assertCandidate(Skill skill, Long namespaceId, String slug) {
        if (!Objects.equals(namespaceId, skill.getNamespaceId()) || !Objects.equals(slug, skill.getSlug())) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
    }

    private void assertCurrentGovernancePermission(Skill skill, String actorUserId) {
        var eligibility = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                skill.getNamespaceId(), skill.getId(), actorUserId);
        if (eligibility.isPresent() && !eligibility.get()) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
    }

    private void assertCanManageLifecycle(Skill skill,
                                          String actorUserId,
                                          Map<Long, NamespaceRole> userNamespaceRoles) {
        var eligibility = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                skill.getNamespaceId(), skill.getId(), actorUserId);
        if (eligibility.isPresent()) {
            if (!eligibility.get()) throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
            return;
        }
        NamespaceRole namespaceRole = userNamespaceRoles.get(skill.getNamespaceId());
        boolean canManage = skill.getOwnerId().equals(actorUserId)
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.OWNER;
        if (!canManage) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
    }

    private String jsonReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return AuditDetail.of("reason", reason);
    }

    private Instant currentInstant() {
        return Instant.now(clock);
    }
}
