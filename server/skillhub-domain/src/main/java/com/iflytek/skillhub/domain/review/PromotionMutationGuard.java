package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.stereotype.Service;

/** Participates in the caller transaction; all locks remain held through commit/rollback. */
@Service
public class PromotionMutationGuard {
    private final EntityManager em;
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final NamespaceRepository namespaces;
    private final ReviewTaskRepository reviews;
    private final PromotionRequestRepository promotions;
    private final SlarkNamespaceBindingRepository bindings;

    public PromotionMutationGuard(EntityManager em, SkillRepository skills, SkillVersionRepository versions,
                                  NamespaceRepository namespaces, ReviewTaskRepository reviews,
                                  PromotionRequestRepository promotions, SlarkNamespaceBindingRepository bindings) {
        this.em=em;this.skills=skills;this.versions=versions;this.namespaces=namespaces;
        this.reviews=reviews;this.promotions=promotions;this.bindings=bindings;
    }

    public void request(Object request) {
        List<?> identity=requestIdentity(request);
        refresh(request);
        if (!identity.equals(requestIdentity(request))) conflict();
        if (request instanceof PromotionRevocationRequest revocation) {
            if (bindings.isBound(revocation.getSourceNamespaceId()) || bindings.isBound(revocation.getTargetNamespaceId())) {
                throw new DomainForbiddenException("error.forbidden");
            }
        } else if (request instanceof PromotionRequest promotion) {
            if (bindings.isBound(skill(promotion.getSourceSkillId()).getNamespaceId()) || bindings.isBound(promotion.getTargetNamespaceId())) {
                throw new DomainForbiddenException("error.forbidden");
            }
        }
    }

    public void submission(Skill source, SkillVersion version, Namespace targetNamespace) {
        Long expectedVersionSkill=version.getSkillId();
        String expectedVersion=version.getVersion();
        Long targetId=promotions.findActiveInitialBySourceSkillId(source.getId())
                .map(PromotionRequest::getTargetSkillId).orElse(null);
        graph(source, targetId==null?null:skill(targetId), targetNamespace.getId());
        if (!Objects.equals(version.getSkillId(),expectedVersionSkill)
                || !Objects.equals(version.getSkillId(),source.getId())
                || !Objects.equals(version.getVersion(),expectedVersion)) conflict();
        Long current=promotions.findActiveInitialBySourceSkillId(source.getId())
                .map(PromotionRequest::getTargetSkillId).orElse(null);
        if (!Objects.equals(targetId,current)) conflict();
    }

    public void approval(PromotionRequest request) {
        request(request);
        if (request.getStatus()!=ReviewTaskStatus.PENDING) {
            throw new DomainBadRequestException("promotion.not_pending",request.getId());
        }
        graph(skill(request.getSourceSkillId()),
                request.getTargetSkillId()==null?null:skill(request.getTargetSkillId()),request.getTargetNamespaceId());
    }

    /** Lock every source promotion before namespace/version/asset locks, including pending updates. */
    public void revocation(Skill source, Skill target) {
        List<PromotionRequest> history=sourcePromotions(source.getId());
        for (var request:history) refresh(request);
        graph(source,target,target.getNamespaceId());
        if (!history.stream().map(PromotionRequest::getId).toList()
                .equals(sourcePromotions(source.getId()).stream().map(PromotionRequest::getId).toList())) conflict();
    }

    private List<PromotionRequest> sourcePromotions(Long sourceId) {
        return em.createQuery("select p from PromotionRequest p where p.sourceSkillId=:id order by p.id",PromotionRequest.class)
                .setParameter("id",sourceId).getResultList();
    }

    private Skill skill(Long id) {
        return skills.findById(id).orElseThrow(()->new DomainNotFoundException("skill.not_found",id));
    }

    private void graph(Skill source, Skill target, Long targetNamespaceId) {
        List<Skill> assets=new ArrayList<>(List.of(source));
        if (target!=null && !Objects.equals(source.getId(),target.getId())) assets.add(target);
        assets.sort(Comparator.comparing(Skill::getId));
        Map<Long,Long> expectedNamespaces=new HashMap<>();
        Map<Long,String> expectedSlugs=new HashMap<>();
        for (var asset:assets) {
            expectedNamespaces.put(asset.getId(),asset.getNamespaceId());
            expectedSlugs.put(asset.getId(),asset.getSlug());
        }
        List<ReviewTask> tasks=assets.stream().flatMap(a->reviews.findBySkillIdOrderByIdAsc(a.getId()).stream())
                .sorted(Comparator.comparing(ReviewTask::getId)).toList();
        for (var task:tasks) refresh(task);
        Set<Long> namespaceIds=new TreeSet<>(expectedNamespaces.values());namespaceIds.add(targetNamespaceId);
        for (var id:namespaceIds) {
            Namespace namespace=namespaces.findById(id)
                    .orElseThrow(()->new DomainNotFoundException("namespace.not_found",id));
            refresh(namespace);
            // Native global promotion must never export/import a Slark scope through native roles.
            if (bindings.isBound(id)) throw new DomainForbiddenException("error.forbidden");
            if (namespace.getStatus()==NamespaceStatus.FROZEN) throw new DomainBadRequestException("error.namespace.frozen",namespace.getSlug());
            if (namespace.getStatus()==NamespaceStatus.ARCHIVED) throw new DomainBadRequestException("error.namespace.archived",namespace.getSlug());
        }
        List<SkillVersion> candidates=assets.stream().flatMap(a->versions.findBySkillId(a.getId()).stream())
                .sorted(Comparator.comparing(SkillVersion::getId)).toList();
        Map<Long,Long> expectedVersionAssets=new HashMap<>();
        Map<Long,String> expectedVersionNames=new HashMap<>();
        for (var version:candidates) {
            expectedVersionAssets.put(version.getId(),version.getSkillId());
            expectedVersionNames.put(version.getId(),version.getVersion());
            refresh(version);
            if (!Objects.equals(expectedVersionAssets.get(version.getId()),version.getSkillId())
                    || !Objects.equals(expectedVersionNames.get(version.getId()),version.getVersion())) conflict();
        }
        for (var asset:assets) {
            refresh(asset);
            if (!Objects.equals(expectedNamespaces.get(asset.getId()),asset.getNamespaceId())
                    || !Objects.equals(expectedSlugs.get(asset.getId()),asset.getSlug())) conflict();
        }
        List<Long> currentTasks=assets.stream().flatMap(a->reviews.findBySkillIdOrderByIdAsc(a.getId()).stream())
                .map(ReviewTask::getId).sorted().toList();
        if (!tasks.stream().map(ReviewTask::getId).toList().equals(currentTasks)) conflict();
        for (var task:tasks) {
            if (task.getSubjectType()!=ReviewSubjectType.SKILL_VERSION
                    || !Objects.equals(task.getSkillId(),task.getSubjectId())
                    || !Objects.equals(expectedNamespaces.get(task.getSkillId()),task.getNamespaceId())
                    || !Objects.equals(task.getSkillVersionId(),task.getSubjectVersionId())
                    || !Objects.equals(task.getSkillVersion(),task.getSubjectVersion())) conflict();
            if (task.getSkillVersionId()!=null) {
                if (!Objects.equals(expectedVersionAssets.get(task.getSkillVersionId()),task.getSkillId())
                        || !Objects.equals(expectedVersionNames.get(task.getSkillVersionId()),task.getSkillVersion())) conflict();
            } else if (task.getStatus()==ReviewTaskStatus.PENDING) conflict();
        }
    }

    private List<?> requestIdentity(Object request) {
        if (request instanceof PromotionRequest p) {
            return Arrays.asList(p.getSourceSkillId(),p.getSourceVersionId(),p.getTargetNamespaceId(),p.getRequestKind(),p.getTargetSkillId());
        }
        if (request instanceof PromotionRevocationRequest r) {
            return Arrays.asList(r.getInitialPromotionRequestId(),r.getSourceSkillId(),r.getTargetSkillId(),r.getSourceNamespaceId(),r.getTargetNamespaceId(),r.getSkillSlug());
        }
        throw new IllegalArgumentException("Unsupported promotion request");
    }

    private void refresh(Object entity) {
        try {em.refresh(entity,LockModeType.PESSIMISTIC_WRITE);}
        catch (EntityNotFoundException missing) {conflict();}
    }
    private void conflict() {throw new DomainBadRequestException("review.candidate.mismatch");}
}
