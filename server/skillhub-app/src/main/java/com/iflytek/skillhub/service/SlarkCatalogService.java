package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.*;

import jakarta.persistence.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Bounded metadata observations. The trusted Slark peer checks current scope authority before and
 * after this call. No files, archive proof or install grant.
 */
@Service
@ConditionalOnProperty(
        prefix = "skillhub.auth.slark",
        name = {"enabled", "distribution-enabled"},
        havingValue = "true")
public class SlarkCatalogService {
    public static final class Denied extends RuntimeException {
        public Denied() {
            super("CATALOG_UNAVAILABLE");
        }
    }

    public record Request(
            SlarkNamespaceKey key,
            Long namespaceId,
            String subject,
            Long afterSkillId,
            String q,
            int limit) {}

    public record Item(
            String skillId,
            String slug,
            String displayName,
            String summary,
            String versionId,
            String version) {}

    public record Page(
            String registryId,
            String scopeKind,
            String scopeOwnerId,
            String namespaceId,
            String namespace,
            List<Item> items,
            String nextAfterSkillId) {}

    private final NamespaceRepository namespaces;
    private final SlarkNamespaceBindingRepository bindings;
    private final SlarkDistributionSettings settings;
    private final EntityManager em;

    public SlarkCatalogService(
            NamespaceRepository namespaces,
            SlarkNamespaceBindingRepository bindings,
            SlarkDistributionSettings settings,
            EntityManager em) {
        this.namespaces = namespaces;
        this.bindings = bindings;
        this.settings = settings;
        this.em = em;
    }

    @Transactional(timeout = 3)
    public Page list(Request input) {
        if (input == null
                || input.key() == null
                || !settings.registryId().equals(input.key().registryId())
                || input.namespaceId() == null
                || input.namespaceId() <= 0
                || (input.afterSkillId() != null && input.afterSkillId() <= 0)
                || input.limit() < 1
                || input.limit() > 100
                || input.q() == null
                || input.q().length() > 200
                || input.q().indexOf('\0') >= 0
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(input.q())
                || (input.subject() != null && !SlarkNamespaceKey.validId(input.subject()))
                || (!input.key().scopeKind().equals("public") && input.subject() == null)
                || (input.key().scopeKind().equals("personal")
                        && !input.key().ownerId().equals(input.subject()))) throw new Denied();
        var ns = namespaces.findById(input.namespaceId()).orElseThrow(Denied::new);
        assertBinding(input, ns);
        // Native lifecycle writers take WRITE on namespace before versions/assets.
        // SHARE serializes their scope freeze/visibility/publication changes while
        // the single SQL statement observes the metadata graph.
        em.refresh(ns, LockModeType.PESSIMISTIC_READ);
        assertBinding(input, ns);
        String kind = input.key().scopeKind();
        String visibility =
                kind.equals("public")
                        ? "PUBLIC"
                        : kind.equals("personal") ? "PRIVATE" : "NAMESPACE_ONLY";
        String term =
                "%" + input.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                em.createNativeQuery(
                                """
SELECT s.id,s.slug,substring(s.display_name from 1 for 200),substring(s.summary from 1 for 1024),v.id,v.version
  FROM skill s
  JOIN LATERAL (
    SELECT candidate.id,candidate.version FROM skill_version candidate
     WHERE candidate.skill_id=s.id
       AND ((:kind='personal' AND candidate.status IN ('UPLOADED','PUBLISHED'))
         OR (:kind<>'personal' AND candidate.id=s.latest_version_id AND candidate.status='PUBLISHED'))
     ORDER BY candidate.id DESC LIMIT 1
  ) v ON true
 WHERE s.namespace_id=:namespace AND s.id>:after AND s.status='ACTIVE' AND s.hidden=false
   AND s.visibility=:visibility
   AND (SELECT count(*) FROM identity_binding i WHERE i.user_id=s.owner_id AND i.provider_code='slark')=1
   AND (:kind<>'personal' OR EXISTS (
     SELECT 1 FROM identity_binding i JOIN user_account u ON u.id=i.user_id
      WHERE i.user_id=s.owner_id AND i.provider_code='slark' AND i.subject=:subject
        AND u.status='ACTIVE' AND u.system_account=false
   ))
   AND (s.slug ILIKE :term ESCAPE '\\' OR s.display_name ILIKE :term ESCAPE '\\' OR s.summary ILIKE :term ESCAPE '\\')
 ORDER BY s.id ASC
""")
                        .setParameter("kind", kind)
                        .setParameter("namespace", input.namespaceId())
                        .setParameter(
                                "after", input.afterSkillId() == null ? 0L : input.afterSkillId())
                        .setParameter("visibility", visibility)
                        .setParameter("subject", input.subject() == null ? "" : input.subject())
                        .setParameter("term", term)
                        .setMaxResults(input.limit() + 1)
                        .getResultList();
        var items = new ArrayList<Item>();
        for (var r : rows.subList(0, Math.min(rows.size(), input.limit())))
            items.add(
                    new Item(
                            r[0].toString(),
                            (String) r[1],
                            bounded((String) r[2], 200),
                            bounded((String) r[3], 1024),
                            r[4].toString(),
                            (String) r[5]));
        assertBinding(input, ns);
        return new Page(
                settings.registryId(),
                kind,
                input.key().ownerId(),
                ns.getId().toString(),
                ns.getSlug(),
                List.copyOf(items),
                rows.size() > input.limit() ? items.getLast().skillId() : null);
    }

    private void assertBinding(Request input, Namespace ns) {
        var b = bindings.findByNamespaceId(input.namespaceId()).orElseThrow(Denied::new);
        if (ns.getStatus() != NamespaceStatus.ACTIVE
                || ns.getType()
                        != (input.key().scopeKind().equals("public")
                                ? NamespaceType.GLOBAL
                                : NamespaceType.TEAM)
                || !b.provisionState().equals("ACTIVE")
                || !b.registryId().equals(settings.registryId())
                || !b.scopeKind().equals(input.key().scopeKind())
                || !b.ownerId().equals(input.key().ownerId())
                || !b.namespaceId().equals(input.namespaceId().toString())
                || !b.namespaceSlug().equals(ns.getSlug())
                || !ns.getSlug().equals(input.key().namespaceSlug())) throw new Denied();
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        int end = Math.min(value.length(), max);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1)))
            end--;
        return value.substring(0, end);
    }
}
