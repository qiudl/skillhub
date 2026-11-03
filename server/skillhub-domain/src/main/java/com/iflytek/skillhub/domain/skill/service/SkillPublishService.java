package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import jakarta.persistence.EntityNotFoundException;
import com.iflytek.skillhub.domain.review.ReviewSubjectType;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.metadata.ComplianceMetadataService;
import com.iflytek.skillhub.domain.skill.metadata.ComplianceSnapshot;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publishes packaged skill artifacts into persisted skill and version records.
 *
 * <p>The service validates archive contents, parses metadata, stores files,
 * creates review tasks when needed, and updates the skill's lifecycle pointer.
 * EntityManager refresh is necessary to replace stale managed state after row-lock waits.
 * Publication locks review history, namespace, versions, then asset, matching review/deletion.
 * The namespace write lock also serializes creation before a skill coordinate exists.
 */
@Service
public class SkillPublishService {
    private final SlarkAssetAuthorization slarkAssetAuthorization;
    @PersistenceContext
    private EntityManager entityManager;

    private static final DateTimeFormatter AUTO_VERSION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneId.systemDefault());
    private static final Set<SkillVersionStatus> REPLACEABLE_VERSION_STATUSES = Set.of(
            SkillVersionStatus.DRAFT,
            SkillVersionStatus.SCAN_FAILED,
            SkillVersionStatus.UPLOADED,
            SkillVersionStatus.REJECTED
    );
    private static final Logger log = LoggerFactory.getLogger(SkillPublishService.class);

    public record PublishResult(
            Long skillId,
            String slug,
            SkillVersion version
    ) {}

    private record StagedPackageFile(
            Path path,
            String filePath,
            String storageKey,
            long size,
            String contentType,
            String sha256
    ) {}

    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillFileRepository skillFileRepository;
    private final ObjectStorageService objectStorageService;
    private final SkillPackageValidator skillPackageValidator;
    private final SkillMetadataParser skillMetadataParser;
    private final ComplianceMetadataService complianceMetadataService = new ComplianceMetadataService();
    private final PrePublishValidator prePublishValidator;
    private final ObjectMapper objectMapper;
    private final ReviewTaskRepository reviewTaskRepository;
    private final SecurityScanService securityScanService;
    private final SkillStorageDeletionCompensationService compensationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SkillPublishService(
            NamespaceRepository namespaceRepository,
            NamespaceMemberRepository namespaceMemberRepository,
            SkillRepository skillRepository,
            SkillVersionRepository skillVersionRepository,
            SkillFileRepository skillFileRepository,
            ObjectStorageService objectStorageService,
            SkillPackageValidator skillPackageValidator,
            SkillMetadataParser skillMetadataParser,
            PrePublishValidator prePublishValidator,
            ObjectMapper objectMapper,
            ReviewTaskRepository reviewTaskRepository,
            SecurityScanService securityScanService,
            SkillStorageDeletionCompensationService compensationService,
            ApplicationEventPublisher eventPublisher,
            Clock clock, SlarkAssetAuthorization slarkAssetAuthorization) {
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillFileRepository = skillFileRepository;
        this.objectStorageService = objectStorageService;
        this.skillPackageValidator = skillPackageValidator;
        this.skillMetadataParser = skillMetadataParser;
        this.prePublishValidator = prePublishValidator;
        this.objectMapper = objectMapper;
        this.reviewTaskRepository = reviewTaskRepository;
        this.securityScanService = securityScanService;
        this.compensationService = compensationService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.slarkAssetAuthorization = slarkAssetAuthorization;
    }

    public record DryRunResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            String resolvedSlug,
            String resolvedVersion
    ) {}

    /**
     * Validates a package without persisting anything. Used by the --dry-run CLI flow.
     *
     * <p>Warnings make the result invalid because the CLI publish flow uses
     * {@code confirmWarnings=false}, which causes real publish to reject any
     * package with warnings. Treating warnings as making the dry-run invalid
     * keeps the two flows in lockstep.
     */
    @Transactional(readOnly = true)
    public DryRunResult validateOnly(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles) {

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String resolvedSlug = null;
        String resolvedVersion = null;

        // 1. Find namespace
        var namespaceOpt = namespaceRepository.findBySlug(namespaceSlug);
        if (namespaceOpt.isEmpty()) {
            errors.add("Namespace not found: " + namespaceSlug);
            return new DryRunResult(false, errors, warnings, null, null);
        }
        Namespace namespace = namespaceOpt.get();
        slarkAssetAuthorization.assertVisibility(namespace.getId(), visibility);
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            errors.add("Namespace is frozen: " + namespaceSlug);
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            errors.add("Namespace is archived: " + namespaceSlug);
        }

        // 2. Check current eligibility; local roles cannot substitute for a bound scope.
        var eligibility = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.CREATE,
                namespace.getId(), null, publisherId);
        boolean slarkNamespace = eligibility.isPresent();
        if (eligibility.filter(allowed -> !allowed).isPresent()) errors.add("error.skill.lifecycle.noPermission");
        boolean isSuperAdmin = platformRoles.contains("SUPER_ADMIN") && !slarkNamespace;
        if (!slarkNamespace && !isSuperAdmin) {
            var member = namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId);
            if (member.isEmpty()) {
                errors.add("Publisher is not a member of namespace: " + namespaceSlug);
            }
        }
        if ((slarkNamespace || requiresSecurityScanner(visibility)) && !securityScanService.isEnabled()) {
            errors.add("error.security.scanner.required");
        }

        // 3. Package validation
        ValidationResult packageValidation = skillPackageValidator.validate(entries);
        errors.addAll(packageValidation.errors());
        warnings.addAll(packageValidation.warnings());

        if (!packageValidation.passed()) {
            return new DryRunResult(false, errors, warnings, null, null);
        }

        // 4. Parse SKILL.md
        PackageEntry skillMd = entries.stream()
                .filter(e -> e.path().equals("SKILL.md"))
                .findFirst()
                .orElse(null);
        if (skillMd == null) {
            errors.add("Missing required file: SKILL.md at root");
            return new DryRunResult(false, errors, warnings, null, null);
        }

        SkillMetadata metadata;
        try {
            metadata = skillMetadataParser.parse(new String(skillMd.content(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            errors.add("Invalid SKILL.md: " + e.getMessage());
            return new DryRunResult(false, errors, warnings, null, null);
        }

        if (metadata.version() == null || metadata.version().isBlank()) {
            resolvedVersion = AUTO_VERSION_FORMATTER.format(currentTime());
        } else {
            resolvedVersion = metadata.version();
        }

        try {
            resolvedSlug = SlugValidator.slugify(metadata.name());
        } catch (Exception e) {
            errors.add("Invalid skill name for slug generation: " + e.getMessage());
            return new DryRunResult(false, errors, warnings, resolvedSlug, resolvedVersion);
        }

        // 5. Pre-publish validation (credential scan)
        PrePublishValidator.SkillPackageContext context = new PrePublishValidator.SkillPackageContext(
                entries, metadata, publisherId, namespace.getId());
        ValidationResult prePublishValidation = prePublishValidator.validate(context);
        errors.addAll(prePublishValidation.errors());
        warnings.addAll(prePublishValidation.warnings());

        // 6. Slug conflict, archived skill, and version-exists checks
        if (resolvedSlug != null && errors.isEmpty()) {
            List<Skill> existingSkills = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), resolvedSlug);
            if (slarkNamespace && !existingSkills.isEmpty()) {
                errors.add("error.skill.publish.target.required");
                return new DryRunResult(false, errors, warnings, resolvedSlug, resolvedVersion);
            }
            for (Skill existing : existingSkills) {
                if (existing.getOwnerId().equals(publisherId)) {
                    if (existing.getStatus() == SkillStatus.ARCHIVED) {
                        errors.add("Cannot publish to archived skill: " + resolvedSlug);
                    }
                    if (resolvedVersion != null) {
                        var existingVersion = skillVersionRepository.findBySkillIdAndVersion(existing.getId(), resolvedVersion);
                        if (existingVersion.isPresent() && existingVersion.get().getStatus() == SkillVersionStatus.PUBLISHED) {
                            errors.add("Version already published: " + resolvedVersion);
                        }
                    }
                } else {
                    boolean hasPublished = !skillVersionRepository
                            .findBySkillIdAndStatus(existing.getId(), SkillVersionStatus.PUBLISHED)
                            .isEmpty();
                    if (hasPublished) {
                        errors.add("Name conflict: slug \"" + resolvedSlug + "\" is already published by another user");
                        break;
                    }
                }
            }
        }

        // Warnings make valid=false: real publish rejects them when confirmWarnings=false,
        // which is the only mode the CLI uses today.
        boolean valid = errors.isEmpty() && warnings.isEmpty();
        return new DryRunResult(valid, errors, warnings, resolvedSlug, resolvedVersion);
    }

    /**
     * Publishes an extracted package into the target namespace.
     *
     * <p>Super administrators may auto-publish, while regular publishers
     * usually create a pending-review version.
     */
    @Transactional
    public PublishResult publishFromEntries(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            java.util.Set<String> platformRoles) {
        return publishFromEntries(namespaceSlug, entries, publisherId, visibility, platformRoles, false);
    }

    @Transactional
    public PublishResult publishFromEntries(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            java.util.Set<String> platformRoles,
            boolean confirmWarnings) {
        return publishFromEntriesInternal(namespaceSlug, entries, publisherId, visibility, platformRoles, confirmWarnings, false, false);
    }

    /** Upload into one explicitly selected Slark asset; the ID never grants maintenance rights. */
    @Transactional
    public PublishResult publishFromEntries(
            String namespaceSlug, List<PackageEntry> entries, String publisherId,
            SkillVisibility visibility, Set<String> platformRoles, boolean confirmWarnings, Long skillId) {
        return publishFromEntriesInternal(namespaceSlug, entries, publisherId, visibility, platformRoles,
                confirmWarnings, false, false, null, null, skillId);
    }

    /**
     * Publishes one package that was explicitly bound by a confirmed Suite Bundle plan.
     *
     * <p>Unlike the interactive single-Skill path, this entry point never withdraws a pending
     * review and never replaces an existing unpublished version. The expected identity is checked
     * again in the write transaction so a stale Bundle plan cannot target a different Skill or
     * version after confirmation.
     */
    @Transactional
    public PublishResult publishBundleMemberFromEntries(
            String namespaceSlug,
            Long expectedSkillId,
            String expectedSkillSlug,
            String expectedVersion,
            List<PackageEntry> entries,
            Map<String, String> stagedSha256,
            String publisherId,
            SkillVisibility visibility,
            Map<Long, NamespaceRole> userNamespaceRoles,
            Set<String> platformRoles,
            boolean confirmWarnings
    ) {
        BundlePublicationTarget target = new BundlePublicationTarget(
                expectedSkillId, expectedSkillSlug, expectedVersion,
                userNamespaceRoles == null ? Map.of() : Map.copyOf(userNamespaceRoles),
                stagedSha256 == null ? Map.of() : Map.copyOf(stagedSha256));
        return publishFromEntriesInternal(
                namespaceSlug, entries, publisherId, visibility,
                platformRoles == null ? Set.of() : platformRoles,
                confirmWarnings, false, false, target);
    }

    /**
     * Rebuilds a new version from an already published version by copying its
     * stored files and rewriting the embedded metadata version field.
     */
    @Transactional
    public PublishResult rereleasePublishedVersion(
            Long skillId,
            String sourceVersion,
            String targetVersion,
            String publisherId,
            Map<Long, NamespaceRole> userNamespaceRoles,
            boolean confirmWarnings) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.notFound", skillId));
        boolean slarkRerelease = assertCanManageLifecycle(skill, publisherId, userNamespaceRoles);

        Long expectedNamespaceId = skill.getNamespaceId();
        String expectedSlug = skill.getSlug();
        Namespace namespace = namespaceRepository.findById(expectedNamespaceId)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.notFound", expectedNamespaceId));
        Skill selectedTarget = slarkRerelease ? skill : nativeRereleaseTarget(skill, publisherId);
        List<Skill> candidates = new ArrayList<>(List.of(skill));
        if (selectedTarget != null && !Objects.equals(selectedTarget.getId(), skill.getId())) candidates.add(selectedTarget);
        // Read no object bytes until both source and existing target are locked. Taking only
        // the source first would acquire target review locks after namespace/version locks.
        lockPublications(namespace, candidates);
        if (!Objects.equals(expectedNamespaceId, skill.getNamespaceId())
                || !Objects.equals(expectedSlug, skill.getSlug())) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }
        assertNamespaceWritable(namespace);
        if (assertCanManageLifecycle(skill, publisherId, userNamespaceRoles) != slarkRerelease) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
        if (skill.getStatus() == SkillStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.skill.publish.archived", skill.getSlug());
        }
        Skill currentTarget = slarkRerelease ? skill : nativeRereleaseTarget(skill, publisherId);
        if (!Objects.equals(selectedTarget == null ? null : selectedTarget.getId(),
                currentTarget == null ? null : currentTarget.getId())) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict", expectedSlug);
        }
        SkillVersion publishedVersion = skillVersionRepository.findBySkillIdAndVersion(skillId, sourceVersion)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.version.notFound", sourceVersion));
        if (publishedVersion.getStatus() != SkillVersionStatus.PUBLISHED) {
            throw new DomainBadRequestException("error.skill.version.notPublished", sourceVersion);
        }
        if (skillVersionRepository.findBySkillIdAndVersion(skillId, targetVersion).isPresent()) {
            throw new DomainBadRequestException("error.skill.version.exists", targetVersion);
        }

        if (slarkRerelease) {
            if (!securityScanService.isEnabled()) {
                throw new DomainBadRequestException("error.security.scanner.required");
            }
        }
        List<PackageEntry> entries = rebuildEntriesForRerelease(skillId, publishedVersion.getId(), targetVersion);
        PackageEntry skillMd = entries.stream().filter(entry -> entry.path().equals("SKILL.md")).findFirst()
                .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.skillMd.notFound"));
        if (!Objects.equals(expectedSlug, SlugValidator.slugify(skillMetadataParser.parse(new String(skillMd.content())).name()))) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }

        // Rerelease follows the same visibility-based workflow as normal publish:
        // - PRIVATE skills go to UPLOADED status
        // - PUBLIC/NAMESPACE_ONLY skills go to PENDING_REVIEW (or UPLOADED after scan)
        return publishFromEntriesInternal(
                resolveNamespaceSlug(skill.getNamespaceId()),
                entries,
                publisherId,
                skill.getVisibility(),
                Set.of(),
                confirmWarnings,  // confirmWarnings: honour caller's choice for rerelease
                false,  // forceAutoPublish=false: respect visibility rules
                true,
                null,
                slarkRerelease ? skill : null
        );
    }

    private Skill nativeRereleaseTarget(Skill source, String publisherId) {
        if (Objects.equals(source.getOwnerId(), publisherId)) return source;
        return skillRepository.findByNamespaceIdAndSlugAndOwnerId(source.getNamespaceId(), source.getSlug(), publisherId).orElse(null);
    }

    private PublishResult publishFromEntriesInternal(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles,
            boolean confirmWarnings,
            boolean forceAutoPublish,
            boolean bypassMembershipCheck) {
        return publishFromEntriesInternal(
                namespaceSlug, entries, publisherId, visibility, platformRoles,
                confirmWarnings, forceAutoPublish, bypassMembershipCheck, null);
    }

    private PublishResult publishFromEntriesInternal(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles,
            boolean confirmWarnings,
            boolean forceAutoPublish,
            boolean bypassMembershipCheck,
            BundlePublicationTarget bundleTarget) {
        return publishFromEntriesInternal(namespaceSlug, entries, publisherId, visibility, platformRoles,
                confirmWarnings, forceAutoPublish, bypassMembershipCheck, bundleTarget, null);
    }

    private PublishResult publishFromEntriesInternal(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles,
            boolean confirmWarnings,
            boolean forceAutoPublish,
            boolean bypassMembershipCheck,
            BundlePublicationTarget bundleTarget,
            Skill pinnedTarget) {
        return publishFromEntriesInternal(namespaceSlug, entries, publisherId, visibility, platformRoles,
                confirmWarnings, forceAutoPublish, bypassMembershipCheck, bundleTarget, pinnedTarget, null);
    }

    private PublishResult publishFromEntriesInternal(
            String namespaceSlug, List<PackageEntry> entries, String publisherId,
            SkillVisibility visibility, Set<String> platformRoles, boolean confirmWarnings,
            boolean forceAutoPublish, boolean bypassMembershipCheck,
            BundlePublicationTarget bundleTarget, Skill pinnedTarget, Long explicitSkillId) {

        // 1. Find namespace by slug
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        assertNamespaceWritable(namespace);

        var eligibility = slarkAssetAuthorization.can(
                SlarkAssetAuthorization.Operation.CREATE, namespace.getId(), null, publisherId);
        boolean slarkNamespace = eligibility.isPresent();
        if (eligibility.filter(allowed -> !allowed).isPresent()
                || (pinnedTarget != null && !slarkNamespace)) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
        slarkAssetAuthorization.assertVisibility(namespace.getId(), visibility);
        if (explicitSkillId != null) {
            if (!slarkNamespace) throw new DomainBadRequestException("error.skill.publish.target.unsupported");
            if (explicitSkillId <= 0) throw new DomainBadRequestException("error.skill.publish.target.mismatch");
            pinnedTarget = skillRepository.findById(explicitSkillId)
                    .filter(target -> Objects.equals(target.getNamespaceId(), namespace.getId()))
                    .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.target.mismatch"));
        }
        boolean isSuperAdmin = platformRoles.contains("SUPER_ADMIN") && !slarkNamespace;

        // Native membership is not the authority for a Slark-bound scope.
        if (!slarkNamespace && !isSuperAdmin && !bypassMembershipCheck) {
            namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId)
                    .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.publisher.notMember", namespaceSlug));
        }

        // 3. Validate package
        ValidationResult packageValidation = skillPackageValidator.validate(entries);
        if (!packageValidation.passed()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.package.invalid",
                    String.join(", ", packageValidation.errors()));
        }

        // 4. Parse SKILL.md
        PackageEntry skillMd = entries.stream()
                .filter(e -> e.path().equals("SKILL.md"))
                .findFirst()
                .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.skillMd.notFound"));

        String skillMdContent = new String(skillMd.content());
        SkillMetadata metadata = skillMetadataParser.parse(skillMdContent);
        if (metadata.version() == null || metadata.version().isBlank()) {
            String autoVersion = AUTO_VERSION_FORMATTER.format(currentTime());
            metadata = new SkillMetadata(metadata.name(), metadata.description(), autoVersion, metadata.body(), metadata.frontmatter());
        }
        String skillSlug = SlugValidator.slugify(metadata.name());
        if (pinnedTarget != null && (!Objects.equals(pinnedTarget.getNamespaceId(), namespace.getId())
                || !Objects.equals(pinnedTarget.getSlug(), skillSlug))) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }
        if (bundleTarget != null
                && (!bundleTarget.expectedSkillSlug().equals(skillSlug)
                || !bundleTarget.expectedVersion().equals(metadata.version()))) {
            throw bundleStateChanged();
        }
        if (bundleTarget != null && !bundleTarget.stagedSha256().keySet().equals(
                entries.stream().map(PackageEntry::path).collect(Collectors.toSet()))) {
            throw bundleStateChanged();
        }

        // 5. Run PrePublishValidator
        PrePublishValidator.SkillPackageContext context = new PrePublishValidator.SkillPackageContext(
                entries, metadata, publisherId, namespace.getId());
        ValidationResult prePublishValidation = prePublishValidator.validate(context);
        if (!prePublishValidation.passed()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.precheck.failed",
                    String.join(", ", prePublishValidation.errors()));
        }
        List<String> publishWarnings = new ArrayList<>(packageValidation.warnings());
        publishWarnings.addAll(prePublishValidation.warnings());
        if (!confirmWarnings && !publishWarnings.isEmpty()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.precheck.confirmRequired",
                    formatValidationMessages(publishWarnings));
        }
        if ((slarkNamespace || requiresSecurityScanner(visibility)) && !securityScanService.isEnabled()) {
            throw new DomainBadRequestException("error.security.scanner.required");
        }

        // Select before taking the namespace lock: never discover and lock review tasks
        // after it (review decisions acquire those tasks before the namespace).
        Skill selected = pinnedTarget;
        if (selected == null && bundleTarget != null && bundleTarget.expectedSkillId() != null) {
            selected = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), skillSlug).stream()
                    .filter(candidate -> Objects.equals(candidate.getId(), bundleTarget.expectedSkillId()))
                    .findFirst().orElseThrow(this::bundleStateChanged);
        } else if (selected == null && bundleTarget == null && !slarkNamespace) {
            selected = skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespace.getId(), skillSlug, publisherId).orElse(null);
        }
        if (selected != null && (!Objects.equals(selected.getNamespaceId(), namespace.getId())
                || !Objects.equals(selected.getSlug(), skillSlug))) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }
        List<ReviewTask> lockedHistory = lockPublication(namespace, selected);
        if (!Objects.equals(namespace.getSlug(), namespaceSlug)) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }
        assertNamespaceWritable(namespace);
        var currentEligibility = slarkAssetAuthorization.can(
                SlarkAssetAuthorization.Operation.CREATE, namespace.getId(), null, publisherId);
        if (currentEligibility.isPresent() != slarkNamespace || currentEligibility.filter(allowed -> !allowed).isPresent()) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
        slarkAssetAuthorization.assertVisibility(namespace.getId(), visibility);
        if (!slarkNamespace && !isSuperAdmin && !bypassMembershipCheck) {
            namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId)
                    .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.publisher.notMember", namespaceSlug));
        }
        if (selected != null && (!Objects.equals(selected.getNamespaceId(), namespace.getId())
                || !Objects.equals(selected.getSlug(), skillSlug))) {
            throw new DomainBadRequestException("error.skill.publish.target.mismatch");
        }
        if (pinnedTarget == null && bundleTarget == null && !slarkNamespace) {
            Skill current = skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespace.getId(), skillSlug, publisherId).orElse(null);
            if (!Objects.equals(selected == null ? null : selected.getId(), current == null ? null : current.getId())) {
                throw new DomainBadRequestException("error.skill.publish.concurrentConflict", skillSlug);
            }
        }

        // 6. Find or create Skill record (with owner isolation)
        List<Skill> existingSkills = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), skillSlug);
        if (slarkNamespace && pinnedTarget == null && bundleTarget == null && !existingSkills.isEmpty()) {
            throw new DomainBadRequestException("error.skill.publish.target.required");
        }

        // Check if any other owner's skill has published versions
        // Only PUBLISHED status blocks same-name publishing (UPLOADED/PENDING_REVIEW allowed)
        for (Skill existing : existingSkills) {
            boolean isBoundTarget = (bundleTarget != null
                    && Objects.equals(existing.getId(), bundleTarget.expectedSkillId()))
                    || (pinnedTarget != null && Objects.equals(existing.getId(), pinnedTarget.getId()));
            if (!existing.getOwnerId().equals(publisherId) && !isBoundTarget) {
                boolean hasPublished = !skillVersionRepository
                        .findBySkillIdAndStatus(existing.getId(), SkillVersionStatus.PUBLISHED)
                        .isEmpty();
                if (hasPublished) {
                    // Distinguish between PRIVATE and PUBLIC/NAMESPACE_ONLY conflicts
                    if (existing.getVisibility() == SkillVisibility.PRIVATE) {
                        throw new DomainBadRequestException("error.skill.publish.nameConflict.private", skillSlug);
                    } else {
                        throw new DomainBadRequestException("error.skill.publish.nameConflict", skillSlug);
                    }
                }
            }
        }

        Skill skill = pinnedTarget != null ? pinnedTarget : (bundleTarget == null
                ? (slarkNamespace ? createSkill(namespace, skillSlug, publisherId, visibility)
                    : findOrCreateOwnedSkill(namespace, skillSlug, publisherId, visibility))
                : resolveBundleTargetSkill(namespace, existingSkills, publisherId, visibility,
                        platformRoles, bundleTarget));
        if (slarkNamespace && !slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                namespace.getId(), skill.getId(), publisherId).orElse(false)) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }

        if (skill.getStatus() == SkillStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.skill.publish.archived", skillSlug);
        }

        // 6c. Auto-withdraw pending review versions
        // When publishing a new version, existing PENDING_REVIEW versions are withdrawn to UPLOADED status
        List<SkillVersion> pendingVersions = skillVersionRepository
                .findBySkillIdAndStatus(skill.getId(), SkillVersionStatus.PENDING_REVIEW);
        if (bundleTarget != null && !pendingVersions.isEmpty()) {
            throw bundleStateChanged();
        }
        if (bundleTarget == null) {
            for (SkillVersion pending : pendingVersions) {
                deleteLockedPendingTasks(lockedHistory, pending);
                pending.setStatus(SkillVersionStatus.UPLOADED);
                skillVersionRepository.save(pending);
            }
        }

        // 7. Check version doesn't already exist
        java.util.Optional<SkillVersion> existingVersion = skillVersionRepository.findBySkillIdAndVersion(skill.getId(), metadata.version());
        if (existingVersion.isPresent()) {
            if (bundleTarget != null) {
                throw bundleStateChanged();
            }
            SkillVersion matchedVersion = existingVersion.get();
            if (matchedVersion.getStatus() == SkillVersionStatus.PUBLISHED) {
                throw new DomainBadRequestException("error.skill.version.exists", metadata.version());
            }
            deleteReplaceableVersionArtifacts(skill, matchedVersion, namespaceSlug, lockedHistory);
        }

        // 8. Create SkillVersion
        SkillVersion version = new SkillVersion(skill.getId(), metadata.version(), publisherId);
        version.setRequestedVisibility(visibility);
        boolean autoPublish = !slarkNamespace && (forceAutoPublish || isSuperAdmin);
        if (autoPublish) {
            version.setStatus(SkillVersionStatus.PUBLISHED);
            version.setPublishedAt(currentTime());
        } else if (visibility == SkillVisibility.PRIVATE) {
            // PRIVATE skill goes to UPLOADED status, no review task created
            version.setStatus(SkillVersionStatus.UPLOADED);
            version.setPublishedAt(currentTime());
        } else {
            version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        }

        ComplianceSnapshot complianceSnapshot = complianceMetadataService.buildSnapshot(metadata.frontmatter(), entries);

        // Store metadata as JSON
        try {
            String metadataJson = objectMapper.writeValueAsString(buildParsedMetadata(metadata, complianceSnapshot));
            version.setParsedMetadataJson(metadataJson);
            version.setManifestJson(objectMapper.writeValueAsString(buildManifest(entries)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }

        try {
            version = skillVersionRepository.save(version);
            // Detect the version-coordinate race before object storage writes begin.
            skillVersionRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // A concurrent publish inserted the same (skillId, version) coordinate first and won the
            // unique-constraint race. Surface a deterministic business conflict rather than an HTTP 500.
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict", skillSlug);
        }

        // 9. Upload each file to storage and compute SHA-256
        List<SkillFile> skillFiles = new ArrayList<>();
        long totalSize = 0;

        Path bundleZip = null;
        List<StagedPackageFile> stagedFiles = new ArrayList<>();
        List<Path> temporaryEntryFiles = new ArrayList<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            HexFormat hexFormat = HexFormat.of();
            bundleZip = Files.createTempFile("skillhub-package-", ".zip");
            try (OutputStream bundleOutput = Files.newOutputStream(bundleZip);
                 ZipOutputStream zipOutput = new ZipOutputStream(bundleOutput)) {
                for (PackageEntry entry : entries) {
                    String storageKey = String.format(
                            "skills/%d/%d/%s", skill.getId(), version.getId(), entry.path());
                    Path stagedFile = Files.createTempFile("skillhub-package-entry-", ".tmp");
                    temporaryEntryFiles.add(stagedFile);
                    ZipEntry zipEntry = new ZipEntry(entry.path());
                    zipOutput.putNextEntry(zipEntry);
                    long actualSize = 0;
                    digest.reset();
                    try (InputStream input = entry.openStream();
                         OutputStream stagedOutput = Files.newOutputStream(stagedFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            stagedOutput.write(buffer, 0, read);
                            zipOutput.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            actualSize += read;
                        }
                    }
                    zipOutput.closeEntry();
                    if (actualSize != entry.size()) {
                        throw new DomainBadRequestException(
                                "error.suite.bundle.member.stateChanged");
                    }
                    String sha256 = hexFormat.formatHex(digest.digest());
                    String expectedSha256 = bundleTarget == null
                            ? null
                            : bundleTarget.stagedSha256().get(entry.path());
                    if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256)) {
                        throw new DomainBadRequestException(
                                "error.suite.bundle.member.stateChanged");
                    }
                    stagedFiles.add(new StagedPackageFile(
                            stagedFile, entry.path(), storageKey, actualSize, entry.contentType(), sha256));
                }
                zipOutput.finish();
            }
            for (StagedPackageFile staged : stagedFiles) {
                try (InputStream stagedInput = Files.newInputStream(staged.path())) {
                    objectStorageService.putObject(
                            staged.storageKey(), stagedInput, staged.size(), staged.contentType());
                }
                skillFiles.add(new SkillFile(
                        version.getId(), staged.filePath(), staged.size(),
                        staged.contentType(), staged.sha256(), staged.storageKey()));
                totalSize += staged.size();
            }
            String bundleKey = String.format("packages/%d/%d/bundle.zip", skill.getId(), version.getId());
            long bundleSize = Files.size(bundleZip);
            try (InputStream bundleInput = Files.newInputStream(bundleZip)) {
                objectStorageService.putObject(
                        bundleKey, bundleInput, bundleSize, "application/zip");
            }
        } catch (LocalizedDomainException exception) {
            throw exception;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process files", e);
        } finally {
            temporaryEntryFiles.forEach(this::deleteTemporaryFile);
            deleteTemporaryFile(bundleZip);
        }

        // 10. Save SkillFile records
        skillFileRepository.saveAll(skillFiles);

        // 11. Update version stats
        version.setFileCount(skillFiles.size());
        version.setTotalSize(totalSize);
        version.setBundleReady(true);
        version.setDownloadReady(!skillFiles.isEmpty());
        skillVersionRepository.save(version);

        // Create review task for PUBLIC/NAMESPACE_ONLY (not PRIVATE)
        if (!autoPublish && visibility != SkillVisibility.PRIVATE) {
            ReviewTask reviewTask = new ReviewTask(
                    version.getId(), skill.getId(), namespace.getId(), version.getVersion(), publisherId);
            ReviewTask savedReviewTask = reviewTaskRepository.save(reviewTask);
            eventPublisher.publishEvent(new ReviewSubmittedEvent(
                    savedReviewTask.getId(),
                    skill.getId(),
                    version.getId(),
                    savedReviewTask.getSubmittedBy(),
                    savedReviewTask.getNamespaceId()
            ));
        }

        // Trigger security scan for all versions (including auto-publish)
        if (securityScanService.isEnabled()) {
            securityScanService.triggerScan(version.getId(), entries, publisherId);
        }

        // 12. Update skill metadata and move the published pointer for auto-publish flows
        skill.setDisplayName(metadata.name());
        skill.setSummary(metadata.description());
        if (autoPublish || visibility == SkillVisibility.PRIVATE) {
            // Update latestVersionId for autoPublish or PRIVATE skill (UPLOADED status)
            skill.setLatestVersionId(version.getId());
            skill.setVisibility(visibility);
        }
        skill.setUpdatedBy(publisherId);
        skillRepository.save(skill);
        // A Bundle may confirm the new private version in this same transaction.
        // Persist our own changes while publication locks are held, before that
        // service refreshes managed entities to observe concurrent decisions.
        skillRepository.flush();

        if (autoPublish) {
            eventPublisher.publishEvent(new SkillPublishedEvent(skill.getId(), version.getId(), publisherId));
        }

        // 13. Return identifiers for the created version
        return new PublishResult(skill.getId(), skill.getSlug(), version);
    }

    private List<ReviewTask> lockPublication(Namespace namespace, Skill skill) {
        return lockPublications(namespace, skill == null ? List.of() : List.of(skill));
    }

    private List<ReviewTask> lockPublications(Namespace namespace, List<Skill> candidates) {
        List<Skill> assets = candidates.stream().sorted(Comparator.comparing(Skill::getId)).toList();
        List<ReviewTask> history = assets.stream()
                .flatMap(asset -> reviewTaskRepository.findBySkillIdOrderByIdAsc(asset.getId()).stream())
                .sorted(Comparator.comparing(ReviewTask::getId)).toList();
        for (ReviewTask task : history) refreshForPublication(task);
        refreshForPublication(namespace);
        List<SkillVersion> versions = assets.stream().flatMap(asset -> skillVersionRepository.findBySkillId(asset.getId()).stream())
                .sorted(Comparator.comparing(SkillVersion::getId)).toList();
        for (SkillVersion version : versions) refreshForPublication(version);
        for (Skill asset : assets) refreshForPublication(asset);
        List<Long> current = assets.stream().flatMap(asset -> reviewTaskRepository.findBySkillIdOrderByIdAsc(asset.getId()).stream())
                .map(ReviewTask::getId).sorted().toList();
        if (!history.stream().map(ReviewTask::getId).toList().equals(current)) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict");
        }
        for (ReviewTask task : history) {
            if (task.getSubjectType() != ReviewSubjectType.SKILL_VERSION
                    || !Objects.equals(task.getNamespaceId(), namespace.getId())
                    || !Objects.equals(task.getSubjectId(), task.getSkillId())
                    || !Objects.equals(task.getSubjectVersionId(), task.getSkillVersionId())
                    || !Objects.equals(task.getSubjectVersion(), task.getSkillVersion())) {
                throw new DomainBadRequestException("review.candidate.mismatch");
            }
            if (task.getSkillVersionId() != null) {
                SkillVersion reviewed = versions.stream()
                        .filter(version -> Objects.equals(version.getId(), task.getSkillVersionId()))
                        .findFirst().orElseThrow(() -> new DomainBadRequestException("review.candidate.mismatch"));
                if (!Objects.equals(reviewed.getSkillId(), task.getSkillId())
                        || !Objects.equals(task.getSkillVersion(), reviewed.getVersion())) {
                    throw new DomainBadRequestException("review.candidate.mismatch");
                }
            } else if (task.getStatus() == ReviewTaskStatus.PENDING) {
                throw new DomainBadRequestException("review.candidate.mismatch");
            }
        }
        return history;
    }

    private void refreshForPublication(Object entity) {
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException ex) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict");
        }
    }

    private void deleteLockedPendingTasks(List<ReviewTask> history, SkillVersion version) {
        for (ReviewTask task : history) {
            if (Objects.equals(task.getSkillVersionId(), version.getId()) && task.getStatus() == ReviewTaskStatus.PENDING) {
                if (!Objects.equals(task.getSkillVersion(), version.getVersion())) {
                    throw new DomainBadRequestException("review.candidate.mismatch");
                }
                // A pending version may also be the replacement target; do not delete twice.
                if (entityManager.contains(task)) reviewTaskRepository.delete(task);
            }
        }
    }

    private Skill findOrCreateOwnedSkill(
            Namespace namespace,
            String skillSlug,
            String publisherId,
            SkillVisibility visibility
    ) {
        return skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespace.getId(), skillSlug, publisherId)
                .orElseGet(() -> createSkill(namespace, skillSlug, publisherId, visibility));
    }

    private Skill resolveBundleTargetSkill(
            Namespace namespace,
            List<Skill> coordinateSkills,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles,
            BundlePublicationTarget target
    ) {
        if (target.expectedSkillId() == null) {
            if (!coordinateSkills.isEmpty()) {
                throw bundleStateChanged();
            }
            return createSkill(namespace, target.expectedSkillSlug(), publisherId, visibility);
        }
        Skill skill = coordinateSkills.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), target.expectedSkillId()))
                .findFirst()
                .orElseThrow(this::bundleStateChanged);
        if (!Objects.equals(skill.getNamespaceId(), namespace.getId())
                || !skill.getSlug().equals(target.expectedSkillSlug())
                || skill.getVisibility() != visibility) {
            throw bundleStateChanged();
        }
        assertCanManageLifecycle(skill, publisherId, target.userNamespaceRoles(), platformRoles);
        return skill;
    }

    private Skill createSkill(
            Namespace namespace,
            String skillSlug,
            String publisherId,
            SkillVisibility visibility
    ) {
        Skill newSkill = new Skill(namespace.getId(), skillSlug, publisherId, visibility);
        newSkill.setCreatedBy(publisherId);
        try {
            Skill savedSkill = skillRepository.save(newSkill);
            // save() may defer the unique-constraint check until transaction commit.
            skillRepository.flush();
            slarkAssetAuthorization.initializeCreator(savedSkill, publisherId);
            return savedSkill;
        } catch (DataIntegrityViolationException ex) {
            throw new DomainBadRequestException("error.skill.publish.concurrentConflict", skillSlug);
        }
    }

    private DomainBadRequestException bundleStateChanged() {
        return new DomainBadRequestException("error.suite.bundle.member.stateChanged");
    }

    private void deleteReplaceableVersionArtifacts(Skill skill, SkillVersion version, String namespaceSlug, List<ReviewTask> lockedHistory) {
        if (!REPLACEABLE_VERSION_STATUSES.contains(version.getStatus())) {
            throw new DomainBadRequestException("error.skill.version.exists", version.getVersion());
        }

        // PostgreSQL prevents deleting a skill_version while skill.latest_version_id still references it.
        if (version.getId().equals(skill.getLatestVersionId())) {
            skill.setLatestVersionId(null);
            skillRepository.save(skill);
            skillRepository.flush();
        }

        // A replaceable version may still have one obsolete pending task, but settled attempts are
        // durable governance history. The database detaches those settled attempts from the
        // replaced version while retaining their skill/version snapshot.
        deleteLockedPendingTasks(lockedHistory, version);

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
        skillVersionRepository.delete(version);
        skillVersionRepository.flush();
    }

    private boolean requiresSecurityScanner(SkillVisibility visibility) {
        return visibility == SkillVisibility.PUBLIC || visibility == SkillVisibility.NAMESPACE_ONLY;
    }

    private String resolveNamespaceSlug(Long namespaceId) {
        return namespaceRepository.findById(namespaceId)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.notFound", namespaceId))
                .getSlug();
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
            log.error("Failed to delete replaced version storage after commit [skillId={}, versionKeys={}]",
                    skill.getId(), storageKeys, ex);
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String formatValidationMessages(List<String> warnings) {
        return warnings.stream()
                .map(warning -> "- " + warning)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }

    private void assertNamespaceWritable(Namespace namespace) {
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
    }

    private boolean assertCanManageLifecycle(Skill skill,
                                          String actorUserId,
                                          Map<Long, NamespaceRole> userNamespaceRoles) {
        return assertCanManageLifecycle(skill, actorUserId, userNamespaceRoles, Set.of());
    }

    private boolean assertCanManageLifecycle(Skill skill,
                                          String actorUserId,
                                          Map<Long, NamespaceRole> userNamespaceRoles,
                                          Set<String> platformRoles) {
        var decision = slarkAssetAuthorization.can(SlarkAssetAuthorization.Operation.SUBMIT,
                skill.getNamespaceId(), skill.getId(), actorUserId);
        if (decision.isPresent()) {
            if (!decision.get()) throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
            return true;
        }
        NamespaceRole namespaceRole = userNamespaceRoles.get(skill.getNamespaceId());
        boolean canManage = skill.getOwnerId().equals(actorUserId)
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.OWNER
                || platformRoles.contains("SUPER_ADMIN");
        if (!canManage) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
        return false;
    }

    private record BundlePublicationTarget(
            Long expectedSkillId,
            String expectedSkillSlug,
            String expectedVersion,
            Map<Long, NamespaceRole> userNamespaceRoles,
            Map<String, String> stagedSha256
    ) {
        private BundlePublicationTarget {
            if (expectedSkillSlug == null || expectedSkillSlug.isBlank()
                    || expectedVersion == null || expectedVersion.isBlank()) {
                throw new IllegalArgumentException("Bundle publication target must include slug and version");
            }
            if (stagedSha256.values().stream().anyMatch(
                    hash -> hash == null || !hash.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Bundle staged hashes must be lowercase SHA-256 values");
            }
        }
    }

    private Instant currentTime() {
        return Instant.now(clock);
    }

    private List<PackageEntry> rebuildEntriesForRerelease(Long skillId, Long versionId, String targetVersion) {
        List<SkillFile> files = skillFileRepository.findByVersionId(versionId).stream()
                .sorted(Comparator.comparing(SkillFile::getFilePath))
                .toList();
        List<PackageEntry> entries = new ArrayList<>(files.size());
        for (SkillFile file : files) {
            byte[] content = readAllBytes(objectStorageService.getObject(file.getStorageKey()));
            if ("SKILL.md".equals(file.getFilePath())) {
                content = rewriteSkillMdVersion(content, targetVersion);
            }
            entries.add(new PackageEntry(
                    file.getFilePath(),
                    content,
                    content.length,
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream"
            ));
        }
        return entries;
    }

    private byte[] readAllBytes(InputStream inputStream) {
        try (InputStream in = inputStream) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read stored skill file", e);
        }
    }

    private byte[] rewriteSkillMdVersion(byte[] content, String targetVersion) {
        String skillMdContent = new String(content);
        SkillMetadata metadata = skillMetadataParser.parse(skillMdContent);
        Map<String, Object> frontmatter = new LinkedHashMap<>(metadata.frontmatter());
        frontmatter.put("version", targetVersion);
        String rewritten = "---\n"
                + new Yaml().dump(frontmatter).trim()
                + "\n---\n"
                + metadata.body();
        return rewritten.getBytes();
    }

    private List<Map<String, Object>> buildManifest(List<PackageEntry> entries) {
        return entries.stream()
                .map(entry -> Map.<String, Object>of(
                        "path", entry.path(),
                        "size", entry.size(),
                        "contentType", entry.contentType()))
                .toList();
    }

    private Map<String, Object> buildParsedMetadata(SkillMetadata metadata, ComplianceSnapshot complianceSnapshot) {
        Map<String, Object> parsedMetadata = new LinkedHashMap<>();
        parsedMetadata.put("name", metadata.name());
        parsedMetadata.put("description", metadata.description());
        parsedMetadata.put("version", metadata.version());
        parsedMetadata.put("body", metadata.body());
        parsedMetadata.put("frontmatter", metadata.frontmatter());
        parsedMetadata.put(ComplianceMetadataService.SNAPSHOT_FIELD_NAME, complianceSnapshot);
        return parsedMetadata;
    }

    private void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to delete temporary package file {}", path, exception);
        }
    }
}
