package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;

import jakarta.persistence.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Trusted Slark peer must check live subject/scope authority before calling. Reads current exact
 * bytes under the existing writer lock order, never a presigned URL. V68 remains
 * immutable/PREPARED: this does not activate a namespace.
 */
@Service
@ConditionalOnProperty(
        prefix = "skillhub.auth.slark",
        name = {"enabled", "distribution-enabled"},
        havingValue = "true")
public class SlarkDistributionService {
    public static final class Denied extends RuntimeException {
        public Denied() {
            super("DISTRIBUTION_UNAVAILABLE");
        }
    }

    public record Request(
            SlarkNamespaceKey key,
            Long namespaceId,
            Long skillId,
            Long versionId,
            String subject) {}

    public record Snapshot(
            String registryId,
            String scopeKind,
            String scopeOwnerId,
            String namespaceId,
            String namespace,
            String skillId,
            String versionId,
            String version,
            String slug,
            String ownerSubject,
            String visibility,
            String namespaceStatus,
            String assetStatus,
            boolean hidden,
            String versionStatus,
            String scanTaskId,
            String scanVerdict,
            String archiveSha256) {}

    public record Export(byte[] bytes, Snapshot snapshot) {}

    private final NamespaceRepository namespaces;
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillFileRepository files;
    private final SecurityAuditRepository audits;
    private final IdentityBindingRepository identities;
    private final UserAccountRepository users;
    private final SlarkNamespaceBindingRepository bindings;
    private final ObjectStorageService storage;
    private final SlarkDistributionSettings settings;
    private final EntityManager em;

    public SlarkDistributionService(
            NamespaceRepository namespaces,
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillFileRepository files,
            SecurityAuditRepository audits,
            IdentityBindingRepository identities,
            UserAccountRepository users,
            SlarkNamespaceBindingRepository bindings,
            ObjectStorageService storage,
            SlarkDistributionSettings settings,
            EntityManager em) {
        this.namespaces = namespaces;
        this.skills = skills;
        this.versions = versions;
        this.files = files;
        this.audits = audits;
        this.identities = identities;
        this.users = users;
        this.bindings = bindings;
        this.storage = storage;
        this.settings = settings;
        this.em = em;
    }

    @Transactional(timeout = 3)
    public Export read(Request input) {
        if (input == null
                || input.key() == null
                || !settings.registryId().equals(input.key().registryId())
                || input.namespaceId() == null
                || input.namespaceId() <= 0
                || input.skillId() == null
                || input.skillId() <= 0
                || input.versionId() == null
                || input.versionId() <= 0
                || (!input.key().scopeKind().equals("public")
                        && !SlarkNamespaceKey.validId(input.subject()))
                || (input.subject() != null && !SlarkNamespaceKey.validId(input.subject())))
            throw new Denied();
        long deadline = System.nanoTime() + 3_000_000_000L;
        var ns = namespaces.findById(input.namespaceId()).orElseThrow(Denied::new);
        // Check PREPARED before loading any asset identity, then recheck after namespace lock.
        assertBinding(input, ns);
        var version = versions.findById(input.versionId()).orElseThrow(Denied::new);
        var skill = skills.findById(input.skillId()).orElseThrow(Denied::new);
        em.refresh(ns, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(version, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(skill, LockModeType.PESSIMISTIC_WRITE);
        assertBinding(input, ns);
        if (ns.getStatus() != NamespaceStatus.ACTIVE
                || !ns.getId().equals(skill.getNamespaceId())
                || !skill.getId().equals(version.getSkillId())
                || skill.getStatus() != SkillStatus.ACTIVE
                || skill.isHidden()) throw new Denied();
        boolean personal = input.key().scopeKind().equals("personal");
        var expected =
                switch (input.key().scopeKind()) {
                    case "public" -> SkillVisibility.PUBLIC;
                    case "personal" -> SkillVisibility.PRIVATE;
                    default -> SkillVisibility.NAMESPACE_ONLY;
                };
        if (skill.getVisibility() != expected
                || !(version.getStatus() == SkillVersionStatus.PUBLISHED
                        || (personal && version.getStatus() == SkillVersionStatus.UPLOADED)))
            throw new Denied();
        String owner = ownerSubject(skill, input, personal);
        var scan =
                audits.findLatestActiveByVersionIdAndScannerType(
                                version.getId(), ScannerType.SKILL_SCANNER)
                        .orElseThrow(Denied::new);
        if (!version.getId().equals(scan.getSkillVersionId())
                || scan.isDeleted()
                || scan.getScannedAt() == null
                || scan.getFailureReason() != null
                || scan.getVerdict() != SecurityVerdict.SAFE
                || !Boolean.TRUE.equals(scan.getIsSafe())
                || !SlarkNamespaceKey.validId(scan.getTaskId())) throw new Denied();
        var entries =
                files.findByVersionId(version.getId()).stream()
                        .sorted(Comparator.comparing(SkillFile::getFilePath))
                        .toList();
        if (entries.isEmpty() || entries.size() > 500) throw new Denied();
        try {
            var out = new ByteArrayOutputStream();
            long total = 0;
            try (var zip = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
                for (var file : entries) {
                    checkDeadline(deadline);
                    if (file.getFileSize() == null
                            || file.getFileSize() < 0
                            || file.getFileSize() > 10 * 1024 * 1024L
                            || file.getSha256() == null
                            || !file.getSha256().matches("[a-f0-9]{64}")
                            || !version.getId().equals(file.getVersionId())) throw new Denied();
                    total += file.getFileSize();
                    if (total > 100 * 1024 * 1024L) throw new Denied();
                    var entry = new ZipEntry(file.getFilePath());
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    long size = 0;
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var source = storage.getObject(file.getStorageKey())) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = source.read(buffer)) != -1) {
                            checkDeadline(deadline);
                            size += count;
                            if (size > file.getFileSize()) throw new Denied();
                            digest.update(buffer, 0, count);
                            zip.write(buffer, 0, count);
                        }
                    }
                    if (size != file.getFileSize()
                            || !HexFormat.of().formatHex(digest.digest()).equals(file.getSha256()))
                        throw new Denied();
                    zip.closeEntry();
                }
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length > 110 * 1024 * 1024) throw new Denied();
            checkDeadline(deadline);
            String archiveHash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertBinding(input, ns);
            if (!owner.equals(ownerSubject(skill, input, personal))) throw new Denied();
            checkDeadline(deadline);
            var snapshot =
                    new Snapshot(
                            settings.registryId(),
                            input.key().scopeKind(),
                            input.key().ownerId(),
                            ns.getId().toString(),
                            ns.getSlug(),
                            skill.getId().toString(),
                            version.getId().toString(),
                            version.getVersion(),
                            skill.getSlug(),
                            owner,
                            skill.getVisibility().name(),
                            ns.getStatus().name(),
                            skill.getStatus().name(),
                            skill.isHidden(),
                            version.getStatus().name(),
                            scan.getTaskId(),
                            scan.getVerdict().name(),
                            archiveHash);
            return new Export(bytes, snapshot);
        } catch (Denied denied) {
            throw denied;
        } catch (Exception failure) {
            throw new Denied();
        }
    }

    private String ownerSubject(Skill skill, Request input, boolean personal) {
        var owners =
                identities.findByUserId(skill.getOwnerId()).stream()
                        .filter(i -> i.getProviderCode().equals("slark"))
                        .toList();
        if (owners.size() != 1) throw new Denied();
        var identity = owners.getFirst();
        em.refresh(identity, LockModeType.NONE);
        String owner = identity.getSubject();
        if (!identity.getUserId().equals(skill.getOwnerId())
                || !identity.getProviderCode().equals("slark")
                || !SlarkNamespaceKey.validId(owner)) throw new Denied();
        if (personal) {
            var user = users.findById(skill.getOwnerId()).orElseThrow(Denied::new);
            em.refresh(user, LockModeType.NONE);
            if (!owner.equals(input.key().ownerId())
                    || !owner.equals(input.subject())
                    || !user.isActive()
                    || user.isSystemAccount()) throw new Denied();
        }
        return owner;
    }

    private void assertBinding(Request input, Namespace ns) {
        var binding = bindings.findByNamespaceId(input.namespaceId()).orElseThrow(Denied::new);
        if (ns.getType()
                        != (input.key().scopeKind().equals("public")
                                ? NamespaceType.GLOBAL
                                : NamespaceType.TEAM)
                || !binding.provisionState().equals("ACTIVE")
                || !binding.registryId().equals(settings.registryId())
                || !binding.scopeKind().equals(input.key().scopeKind())
                || !binding.ownerId().equals(input.key().ownerId())
                || !binding.namespaceId().equals(input.namespaceId().toString())
                || !binding.namespaceSlug().equals(ns.getSlug())
                || !ns.getSlug().equals(input.key().namespaceSlug())) throw new Denied();
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new Denied();
    }
}
