package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlarkPublicationAuthorizationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private NamespaceRepository namespaceRepository;
    @Mock
    private NamespaceMemberRepository namespaceMemberRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillVersionRepository skillVersionRepository;
    @Mock
    private SkillFileRepository skillFileRepository;
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private SkillPackageValidator skillPackageValidator;
    @Mock
    private SkillMetadataParser skillMetadataParser;
    @Mock
    private PrePublishValidator prePublishValidator;
    @Mock
    private ReviewTaskRepository reviewTaskRepository;
    @Mock
    private SecurityScanService securityScanService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SkillStorageDeletionCompensationService compensationService;

    @Mock
    private SlarkAssetAuthorization slarkAssetAuthorization;

    @Mock private jakarta.persistence.EntityManager entityManager;
    private SkillPublishService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        service = new SkillPublishService(
                namespaceRepository,
                namespaceMemberRepository,
                skillRepository,
                skillVersionRepository,
                skillFileRepository,
                objectStorageService,
                skillPackageValidator,
                skillMetadataParser,
                prePublishValidator,
                objectMapper,
                reviewTaskRepository,
                securityScanService,
                compensationService,
                eventPublisher,
                CLOCK, slarkAssetAuthorization
        );
        var persistenceField = SkillPublishService.class.getDeclaredField("entityManager");
        persistenceField.setAccessible(true);
        persistenceField.set(service, entityManager);
        lenient().when(securityScanService.isEnabled()).thenReturn(true);
        lenient().when(skillVersionRepository.findBySkillIdAndStatus(anyLong(), eq(SkillVersionStatus.PENDING_REVIEW)))
                .thenReturn(List.of());
        lenient().when(reviewTaskRepository.save(any(ReviewTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Skill skill;
    private Namespace namespace;
    private List<PackageEntry> entries;
    private final String owner="owner";
    private final String content="---\nname: test-skill\ndescription: Test\nversion: 2.0.0\n---\nBody";

    void fixture() throws Exception {
        namespace=new Namespace("team","Team",owner);setId(namespace,1L);
        skill=new Skill(1L,"test-skill",owner,SkillVisibility.PUBLIC);setId(skill,11L);
        entries=List.of(new PackageEntry("SKILL.md",content.getBytes(),content.getBytes().length,"text/markdown"));
        lenient().when(namespaceRepository.findBySlug("team")).thenReturn(Optional.of(namespace));
        lenient().when(namespaceRepository.findById(1L)).thenReturn(Optional.of(namespace));
        lenient().when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L,owner)).thenReturn(Optional.of(mock(NamespaceMember.class)));
        lenient().when(skillPackageValidator.validate(anyList())).thenReturn(ValidationResult.pass());
        lenient().when(skillMetadataParser.parse(anyString())).thenReturn(new SkillMetadata("test-skill","Test","2.0.0","Body",Map.of("name","test-skill","version","2.0.0")));
        lenient().when(prePublishValidator.validate(any())).thenReturn(ValidationResult.pass());
        lenient().when(skillRepository.findById(11L)).thenReturn(Optional.of(skill));
        lenient().when(skillRepository.findByNamespaceIdAndSlug(1L,"test-skill")).thenReturn(List.of(skill));
        lenient().when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L,"test-skill",owner)).thenReturn(Optional.of(skill));
        lenient().when(skillRepository.save(any())).thenAnswer(i->{Skill value=i.getArgument(0);if(value.getId()==null)setId(value,99L);return value;});
        lenient().when(skillVersionRepository.save(any())).thenAnswer(i->{SkillVersion value=i.getArgument(0);if(value.getId()==null)setId(value,30L);return value;});
    }
    private boolean boundFixture;
    void bound(String actor,boolean maintain) {
        boundFixture = true;
        lenient().when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,1L,null,actor)).thenReturn(Optional.of(true));
        lenient().when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,1L,11L,actor)).thenReturn(Optional.of(maintain));
    }
    SkillPublishService.PublishResult publish(Set<String> roles,SkillVisibility visibility) {
        return boundFixture ? service.publishFromEntries("team",entries,owner,visibility,roles,false,11L)
            : service.publishFromEntries("team",entries,owner,visibility,roles);
    }
    void noWrites(){
        verifyNoInteractions(objectStorageService);
        verify(skillVersionRepository,never()).save(any());
        verify(reviewTaskRepository,never()).save(any());
        verify(skillFileRepository,never()).saveAll(anyList());
    }
    @Test void revokedScopeCannotUseNativeSuperAdmin() throws Exception {
        fixture();when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,1L,null,owner)).thenReturn(Optional.of(false));
        assertThrows(DomainForbiddenException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC));noWrites();
    }
    @Test void authorityFailureCannotFallBackToNativeMembership() throws Exception {
        fixture();when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,1L,null,owner)).thenThrow(new IllegalStateException("authority unavailable"));
        var error=assertThrows(IllegalStateException.class,()->publish(Set.of(),SkillVisibility.PUBLIC));assertEquals("authority unavailable",error.getMessage());noWrites();
    }
    @Test void revokedMaintainerCannotPublishEvenAsNativeOwner() throws Exception {
        fixture();bound(owner,false);
        assertThrows(DomainForbiddenException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC));noWrites();
    }
    @Test void boundSuperAdminStillCreatesReviewAndTriggersScan() throws Exception {
        fixture();bound(owner,true);
        var result=publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC);
        assertEquals(SkillVersionStatus.PENDING_REVIEW,result.version().getStatus());
        verify(reviewTaskRepository).save(any());verify(securityScanService).triggerScan(eq(30L),anyList(),eq(owner));
    }
    @Test void liveSlarkMembershipDoesNotRequireNativeMemberRow() throws Exception {
        fixture();bound(owner,true);lenient().when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L,owner)).thenReturn(Optional.empty());
        assertEquals(11L,publish(Set.of(),SkillVisibility.PUBLIC).skillId());
        verify(namespaceMemberRepository,never()).findByNamespaceIdAndUserId(any(),any());
    }
    @Test void boundPersonalPublicationRequiresScanner() throws Exception {
        fixture();bound(owner,true);when(securityScanService.isEnabled()).thenReturn(false);
        assertThrows(DomainBadRequestException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PRIVATE));noWrites();
    }
    @Test void boundPrivateAdminDoesNotBecomePublished() throws Exception {
        fixture();bound(owner,true);
        assertEquals(SkillVersionStatus.UPLOADED,publish(Set.of("SUPER_ADMIN"),SkillVisibility.PRIVATE).version().getStatus());
        verify(reviewTaskRepository,never()).save(any());verify(securityScanService).triggerScan(eq(30L),anyList(),eq(owner));
    }
    @Test void nativeSuperAdminRemainsCompatible() throws Exception {
        fixture();assertEquals(SkillVersionStatus.PUBLISHED,publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC).version().getStatus());
    }
    void source() {
        var version=new SkillVersion(11L,"1.0.0",owner);setId(version,21L);version.setStatus(SkillVersionStatus.PUBLISHED);
        lenient().when(skillVersionRepository.findBySkillIdAndVersion(11L,"1.0.0")).thenReturn(Optional.of(version));
        lenient().when(skillVersionRepository.findBySkillIdAndStatus(11L,SkillVersionStatus.PUBLISHED)).thenReturn(List.of(version));
        lenient().when(skillFileRepository.findByVersionId(21L)).thenReturn(List.of(new SkillFile(21L,"SKILL.md",(long)content.length(),"text/markdown","hash","source-md")));
        lenient().when(objectStorageService.getObject("source-md")).thenAnswer(i->new java.io.ByteArrayInputStream(content.getBytes()));
    }
    @Test void revokedRereleaseCannotReadStoredPackage() throws Exception {
        fixture();source();bound(owner,false);
        assertThrows(DomainForbiddenException.class,()->service.rereleasePublishedVersion(11L,"1.0.0","2.0.0",owner,Map.of(1L,com.iflytek.skillhub.domain.namespace.NamespaceRole.OWNER),false));noWrites();
    }
    @Test void delegatedMaintainerRereleasesOriginalAsset() throws Exception {
        fixture();source();bound("maintainer",true);
        var result=service.rereleasePublishedVersion(11L,"1.0.0","2.0.0","maintainer",Map.of(),false);
        assertEquals(11L,result.skillId());assertEquals(11L,result.version().getSkillId());assertEquals(owner,skill.getOwnerId());
        assertEquals("maintainer",result.version().getCreatedBy());
        verify(slarkAssetAuthorization,never()).initializeCreator(any(),any());
        verify(skillRepository,never()).findByNamespaceIdAndSlugAndOwnerId(any(),any(),eq("maintainer"));
    }
    @Test void bindingDisappearingDuringRereleaseCannotFallBackToNative() throws Exception {
        fixture();source();bound(owner,true);
        when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,1L,null,owner)).thenReturn(Optional.empty());
        assertThrows(DomainForbiddenException.class,()->service.rereleasePublishedVersion(11L,"1.0.0","2.0.0",owner,Map.of(),false));
        verify(objectStorageService,never()).putObject(anyString(),any(),anyLong(),anyString());
    }
    @Test void pinnedRereleaseCannotFollowDifferentPackageName() throws Exception {
        fixture();source();bound(owner,true);
        when(skillMetadataParser.parse(anyString())).thenReturn(new SkillMetadata("other","Test","2.0.0","Body",Map.of("name","other")));
        assertThrows(DomainBadRequestException.class,()->service.rereleasePublishedVersion(11L,"1.0.0","2.0.0",owner,Map.of(),false));
        verify(objectStorageService,never()).putObject(anyString(),any(),anyLong(),anyString());
    }
    @Test void missingMaintainerDecisionDoesNotRestoreNativeOwner() throws Exception {
        fixture();bound(owner,true);
        when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,1L,11L,owner)).thenReturn(Optional.empty());
        assertThrows(DomainForbiddenException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC));noWrites();
    }
    @Test void maintainerAuthorityFailureHasNoStorageWrites() throws Exception {
        fixture();bound(owner,true);
        when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,1L,11L,owner)).thenThrow(new IllegalStateException("maintainer unavailable"));
        assertThrows(IllegalStateException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC));noWrites();
    }
    @Test void newAssetInitializesCreatorBeforeCheckingMaintenance() throws Exception {
        fixture();bound(owner,true);
        when(skillRepository.findByNamespaceIdAndSlug(1L,"test-skill")).thenReturn(List.of());
        when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,1L,99L,owner)).thenReturn(Optional.of(true));
        var result=service.publishFromEntries("team",entries,owner,SkillVisibility.PUBLIC,Set.of());assertEquals(99L,result.skillId());
        var order=inOrder(slarkAssetAuthorization);
        order.verify(slarkAssetAuthorization).initializeCreator(any(Skill.class),eq(owner));
        order.verify(slarkAssetAuthorization).can(SlarkAssetAuthorization.Operation.SUBMIT,1L,99L,owner);
    }
    @Test void frozenRereleaseDoesNotReadSourceFiles() throws Exception {
        fixture();source();bound(owner,true);namespace.setStatus(com.iflytek.skillhub.domain.namespace.NamespaceStatus.FROZEN);
        assertThrows(DomainBadRequestException.class,()->service.rereleasePublishedVersion(11L,"1.0.0","2.0.0",owner,Map.of(),false));noWrites();
    }
    @Test void scannerUnavailableRereleaseDoesNotReadSourceFiles() throws Exception {
        fixture();source();bound(owner,true);when(securityScanService.isEnabled()).thenReturn(false);
        assertThrows(DomainBadRequestException.class,()->service.rereleasePublishedVersion(11L,"1.0.0","2.0.0",owner,Map.of(),false));noWrites();
    }
    @Test void bundleMemberCannotUseNativeSuperAdminToBypassMaintenance() throws Exception {
        fixture();bound(owner,false);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        assertThrows(DomainForbiddenException.class,()->service.publishBundleMemberFromEntries("team",11L,"test-skill","2.0.0",entries,Map.of("SKILL.md",hash),owner,SkillVisibility.PUBLIC,Map.of(),Set.of("SUPER_ADMIN"),false));noWrites();
    }
    @Test void boundBundleMemberStillRequiresReview() throws Exception {
        fixture();bound(owner,true);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        var result=service.publishBundleMemberFromEntries("team",11L,"test-skill","2.0.0",entries,Map.of("SKILL.md",hash),owner,SkillVisibility.PUBLIC,Map.of(),Set.of("SUPER_ADMIN"),false);
        assertEquals(SkillVersionStatus.PENDING_REVIEW,result.version().getStatus());verify(reviewTaskRepository).save(any());
    }
    private static void setId(Object value,Long id){
        try{var field=value.getClass().getDeclaredField("id");field.setAccessible(true);field.set(value,id);}catch(Exception error){throw new AssertionError(error);}
    }
    @Test void rejectedVisibilityStopsUploadBeforeAnyVersionOrObjectMutation() throws Exception {
        fixture();bound(owner,true);
        doThrow(new DomainForbiddenException("error.skill.lifecycle.noPermission"))
            .when(slarkAssetAuthorization).assertVisibility(1L,SkillVisibility.PUBLIC);
        assertThrows(DomainForbiddenException.class,()->publish(Set.of("SUPER_ADMIN"),SkillVisibility.PUBLIC));
        noWrites();verify(skillRepository,never()).save(any());verify(reviewTaskRepository,never()).delete(any());
    }
    @Test void rejectedVisibilityAlsoStopsDryRun() throws Exception {
        fixture();
        doThrow(new DomainForbiddenException("error.skill.lifecycle.noPermission"))
            .when(slarkAssetAuthorization).assertVisibility(1L,SkillVisibility.PUBLIC);
        assertThrows(DomainForbiddenException.class,()->service.validateOnly("team",entries,owner,SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN")));
        noWrites();
    }

    @Test void boundExistingCoordinateRequiresExplicitAssetId() throws Exception {
        fixture();bound(owner,true);
        assertThrows(DomainBadRequestException.class,()->service.publishFromEntries("team",entries,owner,SkillVisibility.PUBLIC,Set.of()));
        noWrites();verify(skillRepository,never()).save(any());
    }

    SkillPublishService.PublishResult uploadTo(Long id,String actor) {
        return service.publishFromEntries("team",entries,actor,SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"),false,id);
    }
    @Test void delegatedUploadPinsOriginalAssetAndOwner() throws Exception {
        fixture();source();bound("maintainer",true);
        var result=uploadTo(11L,"maintainer");
        assertEquals(11L,result.skillId());assertEquals(11L,result.version().getSkillId());
        assertEquals(owner,skill.getOwnerId());assertEquals("maintainer",result.version().getCreatedBy());
        assertEquals(SkillVersionStatus.PENDING_REVIEW,result.version().getStatus());
        verify(slarkAssetAuthorization,never()).initializeCreator(any(),any());
        verify(skillRepository,never()).findByNamespaceIdAndSlugAndOwnerId(any(),any(),any());
    }
    @Test void sameNameDoesNotSelectAnotherMaintainedAsset() throws Exception {
        fixture();bound("maintainer",true);
        var sibling=new Skill(1L,"test-skill","different-owner",SkillVisibility.PUBLIC);setId(sibling,12L);
        when(skillRepository.findByNamespaceIdAndSlug(1L,"test-skill")).thenReturn(List.of(sibling,skill));
        assertEquals(11L,uploadTo(11L,"maintainer").skillId());
        verify(slarkAssetAuthorization,never()).can(SlarkAssetAuthorization.Operation.SUBMIT,1L,12L,"maintainer");
        verify(slarkAssetAuthorization,never()).initializeCreator(any(),any());
    }
    @Test void delegatedUntargetedUploadCannotCreateSameNameCopy() throws Exception {
        fixture();bound("maintainer",true);
        assertThrows(DomainBadRequestException.class,()->service.publishFromEntries("team",entries,"maintainer",SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN")));
        noWrites();verify(skillRepository,never()).save(any());verify(slarkAssetAuthorization,never()).initializeCreator(any(),any());
    }
    @Test void missingTargetNeverCreatesReplacementAsset() throws Exception {
        fixture();bound(owner,true);
        assertThrows(DomainBadRequestException.class,()->uploadTo(999L,owner));
        noWrites();verify(skillRepository,never()).save(any());verify(slarkAssetAuthorization,never()).initializeCreator(any(),any());
    }
    @Test void foreignNamespaceTargetCannotUseLocalScopeGrant() throws Exception {
        fixture();bound(owner,true);
        var foreign=new Skill(2L,"test-skill",owner,SkillVisibility.PUBLIC);setId(foreign,11L);
        when(skillRepository.findById(11L)).thenReturn(Optional.of(foreign));
        assertThrows(DomainBadRequestException.class,()->uploadTo(11L,owner));noWrites();
    }
    @Test void packageRenameCannotRetargetAsset() throws Exception {
        fixture();bound(owner,true);
        when(skillMetadataParser.parse(anyString())).thenReturn(new SkillMetadata("other","Test","2.0.0","Body",Map.of("name","other")));
        assertThrows(DomainBadRequestException.class,()->uploadTo(11L,owner));noWrites();
        verify(skillRepository,never()).save(any());
    }
    @Test void explicitTargetRejectsRevokedMaintainerAndArchivedAsset() throws Exception {
        fixture();bound(owner,false);
        assertThrows(DomainForbiddenException.class,()->uploadTo(11L,owner));noWrites();
        bound(owner,true);skill.setStatus(SkillStatus.ARCHIVED);
        assertThrows(DomainBadRequestException.class,()->uploadTo(11L,owner));noWrites();
    }
    @Test void explicitNativeTargetDoesNotActivateAnAlternatePublishingPath() throws Exception {
        fixture();
        assertThrows(DomainBadRequestException.class,()->uploadTo(11L,owner));noWrites();
    }
    @Test void nonPositiveTargetCannotBeTreatedAsCreate() throws Exception {
        fixture();bound(owner,true);
        assertThrows(DomainBadRequestException.class,()->uploadTo(0L,owner));
        assertThrows(DomainBadRequestException.class,()->uploadTo(-1L,owner));noWrites();
    }
    @Test void boundDryRunDoesNotPromiseUntargetedUpdate() throws Exception {
        fixture();bound(owner,true);
        var result=service.validateOnly("team",entries,owner,SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN"));
        assertFalse(result.valid());assertTrue(result.errors().contains("error.skill.publish.target.required"));noWrites();
    }
    @Test void boundDryRunRetainsLiveScopeAndScannerChecksWithoutNativeMemberRow() throws Exception {
        fixture();bound(owner,true);
        when(skillRepository.findByNamespaceIdAndSlug(1L,"test-skill")).thenReturn(List.of());
        assertTrue(service.validateOnly("team",entries,owner,SkillVisibility.PRIVATE,Set.of()).valid());
        verify(namespaceMemberRepository,never()).findByNamespaceIdAndUserId(any(),any());
        when(securityScanService.isEnabled()).thenReturn(false);
        assertFalse(service.validateOnly("team",entries,owner,SkillVisibility.PRIVATE,Set.of("SUPER_ADMIN")).valid());
        when(securityScanService.isEnabled()).thenReturn(true);
        when(slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,1L,null,owner)).thenReturn(Optional.of(false));
        assertFalse(service.validateOnly("team",entries,owner,SkillVisibility.PUBLIC,Set.of("SUPER_ADMIN")).valid());noWrites();
    }

}
