package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import com.iflytek.skillhub.domain.skill.SlarkAssetMaintainerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Direct SQL preserves atomic first-create/ON CONFLICT semantics and audit triggers within
 * the caller's asset transaction. Revocation never deletes identity or initial-create history. */
@Repository
public class SlarkAssetMaintainerJdbcRepository implements SlarkAssetMaintainerRepository {
    private final JdbcTemplate jdbc;
    public SlarkAssetMaintainerJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean isActive(Long namespaceId, Long skillId, String subject, String localUserId) {
        validate(namespaceId, skillId, subject, localUserId);
        return java.util.Objects.requireNonNull(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM slark_asset_maintainer m
                JOIN skill s ON s.id=m.skill_id AND s.namespace_id=m.namespace_id
                JOIN identity_binding i ON i.provider_code='slark' AND i.subject=m.subject AND i.user_id=m.local_user_id
                WHERE m.namespace_id=? AND m.skill_id=? AND m.subject=? AND m.local_user_id=? AND m.active)
                """, Boolean.class, namespaceId, skillId, subject, localUserId));
    }

    @Override public void initializeCreator(Long namespaceId, Long skillId, String subject, String localUserId) {
        validate(namespaceId, skillId, subject, localUserId);
        requireTransaction();
        Boolean isNewOwnedAsset = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM skill s WHERE s.id=? AND s.namespace_id=? AND s.owner_id=?
                AND NOT EXISTS(SELECT 1 FROM skill_version v WHERE v.skill_id=s.id))
                """, Boolean.class, skillId, namespaceId, localUserId);
        if (!Boolean.TRUE.equals(isNewOwnedAsset)) throw new IllegalStateException("Initial maintainer requires a new owned asset");
        jdbc.update("""
                INSERT INTO slark_asset_maintainer(skill_id,namespace_id,subject,local_user_id,active,actor_subject,actor_local_user_id)
                VALUES(?,?,?,?,true,?,?) ON CONFLICT(skill_id,subject) DO NOTHING
                """, skillId, namespaceId, subject, localUserId, subject, localUserId);
    }

    @Override public void setActive(Long namespaceId, Long skillId, String subject, String localUserId,
            boolean active, String actorSubject, String actorLocalUserId) {
        validate(namespaceId, skillId, subject, localUserId);
        validate(namespaceId, skillId, actorSubject, actorLocalUserId);
        requireTransaction();
        jdbc.update("""
                INSERT INTO slark_asset_maintainer(skill_id,namespace_id,subject,local_user_id,active,actor_subject,actor_local_user_id)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(skill_id,subject) DO UPDATE SET
                active=EXCLUDED.active,revision=slark_asset_maintainer.revision+1,
                actor_subject=EXCLUDED.actor_subject,actor_local_user_id=EXCLUDED.actor_local_user_id
                WHERE slark_asset_maintainer.namespace_id=EXCLUDED.namespace_id
                AND slark_asset_maintainer.local_user_id=EXCLUDED.local_user_id
                AND slark_asset_maintainer.active IS DISTINCT FROM EXCLUDED.active
                """, skillId, namespaceId, subject, localUserId, active, actorSubject, actorLocalUserId);
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Maintainer write requires the caller's transaction");
    }
    private static void validate(Long namespaceId, Long skillId, String subject, String localUserId) {
        if (namespaceId == null || namespaceId <= 0 || skillId == null || skillId <= 0
                || !SlarkNamespaceKey.validId(subject) || !SlarkNamespaceKey.validId(localUserId) || localUserId.length() > 128)
            throw new IllegalArgumentException("Invalid maintainer identity");
    }
}
