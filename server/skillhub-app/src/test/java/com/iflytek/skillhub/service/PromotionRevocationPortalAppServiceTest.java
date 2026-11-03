package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.PromotionRevocationRequest;
import com.iflytek.skillhub.domain.review.PromotionRevocationRequestRepository;
import com.iflytek.skillhub.domain.review.PromotionRevocationService;
import com.iflytek.skillhub.domain.review.PromotionRequest;
import com.iflytek.skillhub.domain.review.PromotionRequestRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.search.SearchIndexService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionRevocationPortalAppServiceTest {
    @Mock PromotionRevocationService domain;
    @Mock PromotionRevocationRequestRepository repository;
    @Mock PromotionRequestRepository promotionRepository;
    @Mock SkillRepository skillRepository;
    @Mock RbacService rbacService;
    @Mock SearchIndexService searchIndexService;
    @Mock AuditLogService auditLogService;
    private PromotionRevocationPortalAppService service;

    @BeforeEach
    void setUp() {
        service = new PromotionRevocationPortalAppService(domain, repository, promotionRepository, skillRepository,
                rbacService, searchIndexService, auditLogService, new RequestIdAccessor());
    }

    @Test
    void approvalRemovesOnlyApprovedTargetSearchDocument() {
        PromotionRevocationRequest request = new PromotionRevocationRequest(
                4L, 10L, 30L, 2L, 1L, "foo", "owner-1", "withdraw");
        when(rbacService.getUserRoleCodes("admin-1")).thenReturn(Set.of("SKILL_ADMIN"));
        when(domain.approve(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq("admin-1"), org.mockito.ArgumentMatchers.eq(Set.of("SKILL_ADMIN")), org.mockito.ArgumentMatchers.eq("ok"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i->{java.util.function.Consumer<Long> callback=i.getArgument(6);callback.accept(30L);return request;});
        service.approve(5L,"admin-1","ok",null);
        verify(searchIndexService).remove(30L);
    }

    @Test
    void directRevocationUsesDomainChosenTargetForIndexCleanup() {
        PromotionRevocationRequest request = new PromotionRevocationRequest(
                4L, 10L, 30L, 2L, 1L, "foo", "admin-1", "withdraw");
        when(rbacService.getUserRoleCodes("admin-1")).thenReturn(Set.of("SKILL_ADMIN"));
        when(domain.revokeDirect(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("admin-1"), org.mockito.ArgumentMatchers.eq(Set.of("SKILL_ADMIN")), org.mockito.ArgumentMatchers.eq("withdraw"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i->{java.util.function.Consumer<Long> callback=i.getArgument(6);callback.accept(30L);return request;});
        service.revokeDirect(10L,"admin-1","withdraw",null);
        verify(searchIndexService).remove(30L);
    }

    @Test
    void sourceHistoryRejectsUnrelatedReader() {
        Skill source = new Skill(2L, "foo", "owner-1", SkillVisibility.PUBLIC);
        when(rbacService.getUserRoleCodes("other")).thenReturn(Set.of());
        when(skillRepository.findById(10L)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.history(10L, "other", Map.of()))
                .isInstanceOf(DomainForbiddenException.class);
    }

    @Test
    void teamAdministratorCanReadSourceHistory() {
        Skill source = new Skill(2L, "foo", "owner-1", SkillVisibility.PUBLIC);
        when(rbacService.getUserRoleCodes("team-admin")).thenReturn(Set.of());
        when(skillRepository.findById(10L)).thenReturn(Optional.of(source));
        when(repository.findBySourceSkillIdOrderBySubmittedAtDesc(10L)).thenReturn(List.of());

        assertThat(service.history(10L, "team-admin", Map.of(2L, NamespaceRole.ADMIN))).isEmpty();
        verify(repository).findBySourceSkillIdOrderBySubmittedAtDesc(10L);
    }

    @Test
    void reviewedHistoryIsPagedAndPlatformAdminOnly() {
        PromotionRevocationRequest reviewed = new PromotionRevocationRequest(
                4L, 10L, 30L, 2L, 1L, "foo", "owner-1", "withdraw");
        reviewed.review(com.iflytek.skillhub.domain.review.ReviewTaskStatus.APPROVED,
                "admin-1", "ok", java.time.Instant.parse("2026-10-09T00:00:00Z"));
        when(rbacService.getUserRoleCodes("admin-1")).thenReturn(Set.of("SKILL_ADMIN"));
        var pageable = PageRequest.of(0, 20,
                Sort.by(Sort.Order.desc("reviewedAt"), Sort.Order.desc("id")));
        when(repository.findByStatusIn(List.of(
                com.iflytek.skillhub.domain.review.ReviewTaskStatus.APPROVED,
                com.iflytek.skillhub.domain.review.ReviewTaskStatus.REJECTED), pageable))
                .thenReturn(new PageImpl<>(List.of(reviewed), pageable, 1));

        var result = service.reviewedHistory("admin-1", 0, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("APPROVED");
    }
}
