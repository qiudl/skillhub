package com.iflytek.skillhub.domain.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.governance.GovernanceNotificationService;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillGovernanceService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** REQ-20261013-0021: a review decision must affect exactly its still-pending candidate. */
class ReviewCandidateTest {
    ReviewTaskRepository tasks = mock(ReviewTaskRepository.class);
    SkillVersionRepository versions = mock(SkillVersionRepository.class);
    SkillRepository skills = mock(SkillRepository.class);
    NamespaceRepository namespaces = mock(NamespaceRepository.class);
    ReviewPermissionChecker permissions = mock(ReviewPermissionChecker.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    GovernanceNotificationService notifications = mock(GovernanceNotificationService.class);
    EntityManager em = mock(EntityManager.class);
    ReviewService service = new ReviewService(tasks, versions, skills, namespaces, permissions, events,
            new ObjectMapper(), mock(SkillGovernanceService.class), notifications, em, Clock.systemUTC());
    ReviewTask task;
    SkillVersion version;
    Skill skill;
    Namespace namespace;
    @BeforeEach void seed() throws Exception {
        task = new ReviewTask(30L,20L,10L,"1.0.0","submitter"); field(task,"id",40L);
        version = new SkillVersion(20L,"1.0.0","submitter"); field(version,"id",30L);
        version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        skill = new Skill(10L,"candidate","submitter",SkillVisibility.PUBLIC); field(skill,"id",20L);
        namespace = new Namespace("team","Team","submitter"); field(namespace,"id",10L);
        when(tasks.findById(40L)).thenReturn(Optional.of(task));
        when(versions.findById(30L)).thenReturn(Optional.of(version));
        when(skills.findById(20L)).thenReturn(Optional.of(skill));
        when(namespaces.findById(10L)).thenReturn(Optional.of(namespace));
        when(permissions.canReview(any(),any(),any(),anyMap(),anySet())).thenReturn(true);
        when(tasks.updateStatusWithVersion(any(),any(),any(),any(),any())).thenReturn(1);
    }
    static void field(Object target, String name, Object value) throws Exception {
        var field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    void decide(boolean approve) {
        if (approve) service.approveReview(40L,"reviewer","decision",Map.of(),Set.of("SUPER_ADMIN"));
        else service.rejectReview(40L,"reviewer","decision",Map.of(),Set.of("SUPER_ADMIN"));
    }
    void noWrites() {
        verify(tasks,never()).updateStatusWithVersion(any(),any(),any(),any(),any());
        verify(versions,never()).save(any()); verify(skills,never()).save(any());
        verifyNoInteractions(events,notifications);
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
    }
    static Stream<Arguments> states() {
        return Stream.of(SkillVersionStatus.values()).filter(s -> s!=SkillVersionStatus.PENDING_REVIEW)
                .flatMap(s -> Stream.of(Arguments.of(s,true),Arguments.of(s,false)));
    }
    @ParameterizedTest @MethodSource("states")
    void cannotDecideNonPendingCandidate(SkillVersionStatus state, boolean approve) {
        version.setStatus(state);
        assertThatThrownBy(() -> decide(approve)).isInstanceOf(DomainBadRequestException.class);
        noWrites(); assertThat(version.getStatus()).isEqualTo(state);
    }
    static Stream<Arguments> mismatches() {
        return Stream.of("subjectType","subjectId","subjectVersionId","subjectVersion","skillId","skillVersionId",
                "skillVersion","assetNamespace","versionAsset","versionName","archived","missingTypedSubject")
                .flatMap(s -> Stream.of(Arguments.of(s,true),Arguments.of(s,false)));
    }
    @ParameterizedTest @MethodSource("mismatches")
    void cannotApplyDecisionToDifferentCandidate(String mismatch, boolean approve) throws Exception {
        switch(mismatch) {
            case "subjectType" -> field(task,"subjectType",ReviewSubjectType.SUITE_VERSION);
            case "subjectId" -> field(task,"subjectId",21L);
            case "subjectVersionId" -> field(task,"subjectVersionId",31L);
            case "subjectVersion" -> field(task,"subjectVersion","2.0.0");
            case "skillId" -> field(task,"skillId",21L);
            case "skillVersionId" -> field(task,"skillVersionId",31L);
            case "skillVersion" -> field(task,"skillVersion","2.0.0");
            case "assetNamespace" -> field(skill,"namespaceId",11L);
            case "versionAsset" -> {field(version,"skillId",21L); when(skills.findById(21L)).thenReturn(Optional.of(skill));}
            case "versionName" -> field(version,"version","2.0.0");
            case "archived" -> skill.setStatus(SkillStatus.ARCHIVED);
            case "missingTypedSubject" -> field(task,"subjectType",null);
        }
        assertThatThrownBy(() -> decide(approve)).isInstanceOf(DomainBadRequestException.class);
        noWrites(); assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PENDING_REVIEW);
    }
}
