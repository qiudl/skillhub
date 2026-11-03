package com.iflytek.skillhub.domain.namespace;

/** A controlled namespace coordinate/state, never a membership or execution grant. */
public record SlarkNamespaceBinding(String registryId, String scopeKind, String ownerId,
                                    String namespaceId, String namespaceSlug, String provisionState) {}
