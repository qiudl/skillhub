package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.*;
import com.iflytek.skillhub.storage.ObjectStorageService;

import jakarta.persistence.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

/** ACTIVE binding is an isolated future-state fixture, never an activation operation. */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SlarkDistributionServiceTest {
    @Mock NamespaceRepository namespaces;
    @Mock SkillRepository skills;
    @Mock SkillVersionRepository versions;
    @Mock SkillFileRepository files;
    @Mock SecurityAuditRepository audits;
    @Mock IdentityBindingRepository identities;
    @Mock UserAccountRepository users;
    @Mock SlarkNamespaceBindingRepository bindings;
    @Mock ObjectStorageService storage;
    @Mock EntityManager em;
    SlarkDistributionService service;
    Namespace ns;
    Skill skill;
    SkillVersion version;
    SecurityAudit scan;
    SlarkNamespaceKey key;
    byte[] bytes;
    SlarkDistributionService.Request request;

    @BeforeEach
    void setup() throws Exception {
        key = new SlarkNamespaceKey("registry", "personal", "u1");
        ns = new Namespace(key.namespaceSlug(), "Personal", "owner");
        ReflectionTestUtils.setField(ns, "id", 7L);
        skill = new Skill(7L, "candidate", "owner", SkillVisibility.PRIVATE);
        ReflectionTestUtils.setField(skill, "id", 8L);
        version = new SkillVersion(8L, "1.0.0", "owner");
        ReflectionTestUtils.setField(version, "id", 9L);
        version.setStatus(SkillVersionStatus.UPLOADED);
        scan = new SecurityAudit(9L, ScannerType.SKILL_SCANNER, "current");
        scan.setVerdict(SecurityVerdict.SAFE);
        scan.setIsSafe(true);
        scan.setScannedAt(Instant.now());
        bytes =
                "---\nname: candidate\ndescription: Test\nversion: 1.0.0\n---\nBody"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(namespaces.findById(7L)).thenReturn(Optional.of(ns));
        when(skills.findById(8L)).thenReturn(Optional.of(skill));
        when(versions.findById(9L)).thenReturn(Optional.of(version));
        when(bindings.findByNamespaceId(7L)).thenReturn(Optional.of(binding("ACTIVE")));
        when(audits.findLatestActiveByVersionIdAndScannerType(9L, ScannerType.SKILL_SCANNER))
                .thenReturn(Optional.of(scan));
        when(identities.findByUserId("owner"))
                .thenReturn(List.of(new IdentityBinding("owner", "slark", "u1", null)));
        when(users.findById("owner"))
                .thenReturn(Optional.of(new UserAccount("owner", "Owner", null, null)));
        when(files.findByVersionId(9L))
                .thenReturn(
                        List.of(
                                new SkillFile(
                                        9L,
                                        "SKILL.md",
                                        (long) bytes.length,
                                        "text/markdown",
                                        hex(bytes),
                                        "object")));
        when(storage.getObject("object")).thenAnswer(i -> new ByteArrayInputStream(bytes));
        service =
                new SlarkDistributionService(
                        namespaces,
                        skills,
                        versions,
                        files,
                        audits,
                        identities,
                        users,
                        bindings,
                        storage,
                        new SlarkDistributionSettings("registry", "d".repeat(40)),
                        em);
        request = new SlarkDistributionService.Request(key, 7L, 8L, 9L, "u1");
    }

    SlarkNamespaceBinding binding(String state) {
        return new SlarkNamespaceBinding(
                "registry", "personal", "u1", "7", key.namespaceSlug(), state);
    }

    static String hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    @Test
    void archiveAndCurrentSnapshotAreBuiltUnderOrderedLocks() throws Exception {
        var result = service.read(request);
        assertThat(result.snapshot().namespaceId()).isEqualTo("7");
        assertThat(result.snapshot().versionId()).isEqualTo("9");
        assertThat(result.snapshot().ownerSubject()).isEqualTo("u1");
        assertThat(result.snapshot().scanTaskId()).isEqualTo("current");
        assertThat(result.snapshot().archiveSha256()).isEqualTo(hex(result.bytes()));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(result.bytes()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("SKILL.md");
            assertThat(zip.readAllBytes()).isEqualTo(bytes);
            assertThat(zip.getNextEntry()).isNull();
        }
        var order = inOrder(em, storage);
        order.verify(em).refresh(ns, LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).refresh(version, LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).refresh(skill, LockModeType.PESSIMISTIC_WRITE);
        order.verify(storage).getObject("object");
    }

    @Test
    void preparedNativeOrWrongBindingNeverReadsBytes() {
        for (var value :
                List.of(
                        Optional.<SlarkNamespaceBinding>empty(),
                        Optional.of(binding("PREPARED")),
                        Optional.of(
                                new SlarkNamespaceBinding(
                                        "other",
                                        "personal",
                                        "u1",
                                        "7",
                                        key.namespaceSlug(),
                                        "ACTIVE")))) {
            when(bindings.findByNamespaceId(7L)).thenReturn(value);
            assertThatThrownBy(() -> service.read(request))
                    .isInstanceOf(SlarkDistributionService.Denied.class);
        }
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void staleNamespaceIsRefreshedBeforeObjectRead() {
        doAnswer(
                        i -> {
                            ns.setStatus(NamespaceStatus.FROZEN);
                            return null;
                        })
                .when(em)
                .refresh(ns, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void invalidVersionAndScannerCannotUseHistoricalSafeScan() {
        for (var state :
                List.of(
                        SkillVersionStatus.SCANNING,
                        SkillVersionStatus.SCAN_FAILED,
                        SkillVersionStatus.PENDING_REVIEW,
                        SkillVersionStatus.YANKED)) {
            version.setStatus(state);
            assertThatThrownBy(() -> service.read(request))
                    .isInstanceOf(SlarkDistributionService.Denied.class);
        }
        version.setStatus(SkillVersionStatus.UPLOADED);
        scan.setScannedAt(null);
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        scan.setScannedAt(Instant.now());
        scan.setVerdict(SecurityVerdict.BLOCKED);
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void personalOwnerAndVersionCannotBeSubstituted() {
        assertThatThrownBy(
                        () ->
                                service.read(
                                        new SlarkDistributionService.Request(
                                                key, 7L, 8L, 9L, "u2")))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        ReflectionTestUtils.setField(version, "skillId", 99L);
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void objectMismatchFailsWithoutEvidence() {
        byte[] original = bytes;
        bytes = new byte[] {1, 2};
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        bytes = original.clone();
        bytes[0] ^= 1;
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).deleteObjects(anyList());
    }

    @Test
    void nonpersonalScopesRequirePublishedAndUseTheirFixedVisibility() {
        for (String kind : List.of("public", "organization", "project")) {
            key =
                    new SlarkNamespaceKey(
                            "registry", kind, kind.equals("public") ? "public" : "scope");
            ReflectionTestUtils.setField(ns, "slug", key.namespaceSlug());
            ns.setType(kind.equals("public") ? NamespaceType.GLOBAL : NamespaceType.TEAM);
            skill.setVisibility(
                    kind.equals("public")
                            ? SkillVisibility.PUBLIC
                            : SkillVisibility.NAMESPACE_ONLY);
            when(bindings.findByNamespaceId(7L))
                    .thenReturn(
                            Optional.of(
                                    new SlarkNamespaceBinding(
                                            "registry",
                                            kind,
                                            key.ownerId(),
                                            "7",
                                            key.namespaceSlug(),
                                            "ACTIVE")));
            request =
                    new SlarkDistributionService.Request(
                            key, 7L, 8L, 9L, kind.equals("public") ? null : "u1");
            version.setStatus(SkillVersionStatus.UPLOADED);
            assertThatThrownBy(() -> service.read(request))
                    .isInstanceOf(SlarkDistributionService.Denied.class);
            version.setStatus(SkillVersionStatus.PUBLISHED);
            assertThat(service.read(request).snapshot().scopeKind()).isEqualTo(kind);
        }
    }

    @Test
    void missingOrAmbiguousIdentityFailsClosed() {
        when(identities.findByUserId("owner")).thenReturn(List.of());
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        when(identities.findByUserId("owner"))
                .thenReturn(
                        List.of(
                                new IdentityBinding("owner", "slark", "u1", null),
                                new IdentityBinding("owner", "slark", "u2", null)));
        assertThatThrownBy(() -> service.read(request))
                .isInstanceOf(SlarkDistributionService.Denied.class);
        verify(storage, never()).getObject(anyString());
    }
}
