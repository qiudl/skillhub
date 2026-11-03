package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.report.SkillReportRepository;
import com.iflytek.skillhub.domain.review.PromotionRequestRepository;
import com.iflytek.skillhub.domain.review.ReviewSubjectType;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillTagRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatsRepository;
import com.iflytek.skillhub.domain.skill.SlarkAssetAuthorization;
import com.iflytek.skillhub.domain.social.SkillRatingRepository;
import com.iflytek.skillhub.domain.social.SkillStarRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Permanently deletes a skill and all of its persisted artifacts so the slug
 * may be uploaded again without residual conflicts.
 */
@Service
public class SkillHardDeleteService {

    private static final Logger log = LoggerFactory.getLogger(SkillHardDeleteService.class);

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillFileRepository skillFileRepository;
    private final SkillTagRepository skillTagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final PromotionRequestRepository promotionRequestRepository;
    private final SkillStarRepository skillStarRepository;
    private final SkillRatingRepository skillRatingRepository;
    private final SkillReportRepository skillReportRepository;
    private final SkillVersionStatsRepository skillVersionStatsRepository;
    private final ObjectStorageService objectStorageService;
    private final SkillStorageDeletionCompensationService compensationService;
    private final SecurityScanService securityScanService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final NamespaceRepository namespaceRepository;
    private final SlarkAssetAuthorization slarkAssetAuthorization;
    @PersistenceContext private EntityManager entityManager;

    public SkillHardDeleteService(SkillRepository skillRepository,
                                  SkillVersionRepository skillVersionRepository,
                                  SkillFileRepository skillFileRepository,
                                  SkillTagRepository skillTagRepository,
                                  ReviewTaskRepository reviewTaskRepository,
                                  PromotionRequestRepository promotionRequestRepository,
                                  SkillStarRepository skillStarRepository,
                                  SkillRatingRepository skillRatingRepository,
                                  SkillReportRepository skillReportRepository,
                                  SkillVersionStatsRepository skillVersionStatsRepository,
                                  ObjectStorageService objectStorageService,
                                  SkillStorageDeletionCompensationService compensationService,
                                  SecurityScanService securityScanService,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper,
                                  NamespaceRepository namespaceRepository,
                                  SlarkAssetAuthorization slarkAssetAuthorization) {
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillFileRepository = skillFileRepository;
        this.skillTagRepository = skillTagRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.promotionRequestRepository = promotionRequestRepository;
        this.skillStarRepository = skillStarRepository;
        this.skillRatingRepository = skillRatingRepository;
        this.skillReportRepository = skillReportRepository;
        this.skillVersionStatsRepository = skillVersionStatsRepository;
        this.objectStorageService = objectStorageService;
        this.compensationService = compensationService;
        this.securityScanService = securityScanService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.namespaceRepository = namespaceRepository;
        this.slarkAssetAuthorization = slarkAssetAuthorization;
    }

    @Transactional
    public void hardDeleteSkill(Skill skill, String namespaceSlug, String actorUserId, String clientIp, String userAgent) {
        prepareForDeletion(skill, namespaceSlug, actorUserId);
        deleteSkill(skill, namespaceSlug, actorUserId, clientIp, userAgent, false);
    }

    /** Locks history → namespace → versions → asset, then checks current eligibility.
     * Returns whether Slark maintenance replaces the portal's native owner/admin policy.
     * Callers keep this transaction open through index and artifact deletion.
     */
    @Transactional
    public boolean prepareForDeletion(Skill skill, String namespaceSlug, String actorUserId) {
        assertNoPromotion(skill);
        Long namespaceId = skill.getNamespaceId();
        String slug = skill.getSlug();
        List<ReviewTask> history = reviewTaskRepository.findBySkillIdOrderByIdAsc(skill.getId());
        for (var task : history) refresh(task);
        var namespace = namespaceRepository.findById(namespaceId)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.notFound", namespaceId));
        refresh(namespace);
        List<SkillVersion> versions = skillVersionRepository.findBySkillId(skill.getId()).stream()
                .sorted(Comparator.comparing(SkillVersion::getId)).toList();
        for (var version : versions) refresh(version);
        refresh(skill);
        if (!Objects.equals(namespaceId, skill.getNamespaceId())
                || !Objects.equals(slug, skill.getSlug())
                || !Objects.equals(namespace.getSlug(), namespaceSlug.startsWith("@") ? namespaceSlug.substring(1) : namespaceSlug)) {
            throw new DomainBadRequestException("review.candidate.mismatch");
        }
        var current = reviewTaskRepository.findBySkillIdOrderByIdAsc(skill.getId());
        if (!history.stream().map(ReviewTask::getId).toList()
                .equals(current.stream().map(ReviewTask::getId).toList())) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict", skill.getSlug());
        }
        for (var task : history) {
            if (task.getSubjectType() != ReviewSubjectType.SKILL_VERSION
                    || !Objects.equals(task.getNamespaceId(), namespaceId)
                    || !Objects.equals(task.getSkillId(), skill.getId())
                    || !Objects.equals(task.getSubjectId(), skill.getId())
                    || !Objects.equals(task.getSkillVersionId(), task.getSubjectVersionId())
                    || !Objects.equals(task.getSkillVersion(), task.getSubjectVersion())) {
                throw new DomainBadRequestException("review.candidate.mismatch");
            }
            if (task.getSkillVersionId() != null) {
                SkillVersion reviewed = versions.stream().filter(v -> Objects.equals(v.getId(), task.getSkillVersionId()))
                        .findFirst().orElseThrow(() -> new DomainBadRequestException("review.candidate.mismatch"));
                if (!Objects.equals(reviewed.getVersion(), task.getSkillVersion())) {
                    throw new DomainBadRequestException("review.candidate.mismatch");
                }
            } else if (task.getStatus() == ReviewTaskStatus.PENDING) {
                throw new DomainBadRequestException("review.candidate.mismatch");
            }
        }
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
        assertNoPromotion(skill);
        var eligibility = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                namespaceId, skill.getId(), actorUserId);
        if (eligibility.isPresent()) {
            if (!eligibility.get()) throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
            if (skill.getStatus() == SkillStatus.ARCHIVED) {
                throw new DomainBadRequestException("error.skill.publish.archived", skill.getSlug());
            }
        }
        return eligibility.isPresent();
    }

    private void assertNoPromotion(Skill skill) {
        if (skill.getSourceSkillId() != null
                || promotionRequestRepository.findActiveInitialBySourceSkillId(skill.getId()).isPresent()
                || promotionRequestRepository.findBySourceSkillIdAndStatus(skill.getId(), ReviewTaskStatus.PENDING).isPresent()) {
            throw new DomainBadRequestException("promotion.revocation.required");
        }
    }

    private void refresh(Object entity) {
        try {entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);}
        catch (EntityNotFoundException missing) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict");
        }
    }

    /** Deletes a revoked global derivative while retaining its promotion and revocation history. */
    @Transactional
    public void hardDeleteRevokedPromotionTarget(Skill skill, String namespaceSlug,
                                                 String actorUserId, String clientIp, String userAgent) {
        deleteSkill(skill, namespaceSlug, actorUserId, clientIp, userAgent, true);
    }

    private void deleteSkill(Skill skill, String namespaceSlug, String actorUserId,
                             String clientIp, String userAgent, boolean preservePromotionHistory) {
        List<SkillVersion> versions = skillVersionRepository.findBySkillId(skill.getId());
        List<Long> versionIds = versions.stream().map(SkillVersion::getId).toList();

        List<String> storageKeys = new ArrayList<>();
        for (SkillVersion version : versions) {
            List<SkillFile> files = skillFileRepository.findByVersionId(version.getId());
            files.stream().map(SkillFile::getStorageKey)
                    .filter(key -> key != null && !key.isBlank())
                    .filter(key -> !skillFileRepository.existsByStorageKeyAndVersionIdNotIn(key, versionIds))
                    .forEach(storageKeys::add);
            storageKeys.add(buildBundleStorageKey(skill.getId(), version.getId()));
        }
        deleteStorageAfterCommit(skill, namespaceSlug, storageKeys);

        skill.setLatestVersionId(null);
        skill.setUpdatedBy(actorUserId);
        skillRepository.save(skill);
        skillRepository.flush();

        // Also removes detached historical attempts whose replaced skill version no longer exists.
        reviewTaskRepository.deleteBySkillId(skill.getId());
        if (!preservePromotionHistory) {
            promotionRequestRepository.deleteBySourceSkillIdOrTargetSkillId(skill.getId(), skill.getId());
        }
        skillTagRepository.deleteBySkillId(skill.getId());
        skillStarRepository.deleteBySkillId(skill.getId());
        skillRatingRepository.deleteBySkillId(skill.getId());
        skillReportRepository.deleteBySkillId(skill.getId());
        skillVersionStatsRepository.deleteBySkillId(skill.getId());

        for (Long versionId : versionIds) {
            securityScanService.hardDeleteByVersionId(versionId);
            skillFileRepository.deleteByVersionId(versionId);
        }
        skillVersionRepository.deleteBySkillId(skill.getId());
        skillRepository.delete(skill);

        auditLogService.record(
                actorUserId,
                preservePromotionHistory ? "REVOKE_PROMOTION_TARGET" : "DELETE_SKILL_HARD",
                "SKILL",
                skill.getId(),
                null,
                clientIp,
                userAgent,
                toAuditPayload(skill)
        );
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
            log.error("Failed to delete storage objects after hard delete commit [keys={}]", storageKeys, ex);
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String toAuditPayload(Skill skill) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("namespaceId", skill.getNamespaceId());
        payload.put("slug", skill.getSlug());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize hard-delete audit payload", e);
        }
    }
}
