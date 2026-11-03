package com.iflytek.skillhub.domain.namespace;

/** Atomically reserves a new frozen namespace or returns the exact existing binding. */
public interface SlarkNamespaceBindingRepository {
    SlarkNamespaceBinding provision(SlarkNamespaceKey key, String clientId);

    /** Bound namespaces use Slark authorization even after controlled activation. */
    boolean isBound(Long namespaceId);
    java.util.Optional<SlarkNamespaceBinding> findByNamespaceId(Long namespaceId);
}
