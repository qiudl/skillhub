package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBindingRepository;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Direct SQL is required to serialize a previously absent business key and atomically insert
 * both namespace and binding. A slug collision is a conflict, never adoption of existing data. */
@Repository
public class SlarkNamespaceBindingJdbcRepository implements SlarkNamespaceBindingRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public SlarkNamespaceBindingJdbcRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(3);
    }
    @Override public boolean isBound(Long namespaceId) {
        if (namespaceId == null || namespaceId <= 0) throw new IllegalArgumentException("Invalid namespace ID");
        // Use the immutable namespace identity, not a slug prefix or an auth feature flag.
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM slark_namespace_binding WHERE namespace_id=?)",
                Boolean.class, namespaceId));
    }
    @Override public java.util.Optional<SlarkNamespaceBinding> findByNamespaceId(Long namespaceId) {
        if (namespaceId == null || namespaceId <= 0) throw new IllegalArgumentException("Invalid namespace ID");
        return jdbc.query("""
                SELECT b.registry_id,b.scope_kind,b.owner_id,b.namespace_id::text,n.slug,b.provision_state
                FROM slark_namespace_binding b JOIN namespace n ON n.id=b.namespace_id WHERE b.namespace_id=?
                """, (row, index) -> new SlarkNamespaceBinding(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6)), namespaceId).stream().findFirst();
    }
    @Override public SlarkNamespaceBinding provision(SlarkNamespaceKey key, String clientId) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout='2000ms'");
            jdbc.execute("SET LOCAL statement_timeout='3000ms'");
            String slug = key.namespaceSlug();
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", result -> {}, slug);
            var existing = jdbc.query("""
                    SELECT binding.namespace_id::text, namespace.slug, binding.provision_state
                    FROM slark_namespace_binding binding JOIN namespace ON namespace.id=binding.namespace_id
                    WHERE registry_id=? AND scope_kind=? AND owner_id=?
                    """, (row, index) -> new SlarkNamespaceBinding(key.registryId(), key.scopeKind(), key.ownerId(),
                    row.getString(1), row.getString(2), row.getString(3)), key.registryId(), key.scopeKind(), key.ownerId());
            if (!existing.isEmpty()) return existing.getFirst();
            String namespaceId = jdbc.queryForObject("""
                    INSERT INTO namespace(slug,display_name,type,status)
                    VALUES (?,'Skills',?,'FROZEN') RETURNING id::text
                    """, String.class, slug, key.scopeKind().equals("public") ? "GLOBAL" : "TEAM");
            jdbc.update("""
                    INSERT INTO slark_namespace_binding(registry_id,scope_kind,owner_id,namespace_id,provisioned_by_client)
                    VALUES (?,?,?,?,?)
                    """, key.registryId(), key.scopeKind(), key.ownerId(), Long.valueOf(namespaceId), clientId);
            return new SlarkNamespaceBinding(key.registryId(), key.scopeKind(), key.ownerId(), namespaceId, slug, "PREPARED");
        });
    }
}
