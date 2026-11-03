package com.iflytek.skillhub.domain.namespace;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Stable external identity; opaque IDs never become paths or display-name lookup keys. */
public record SlarkNamespaceKey(String registryId, String scopeKind, String ownerId) {
    public SlarkNamespaceKey {
        if (!validId(registryId) || !Set.of("public", "organization", "project", "personal").contains(scopeKind == null ? "" : scopeKind)
                || !validId(ownerId) || (scopeKind.equals("public") && !ownerId.equals("public")))
            throw new IllegalArgumentException("Invalid namespace scope");
    }
    public static boolean validId(String value) {
        if (value == null || value.isEmpty() || value.length() > 200 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        // ECMAScript trim whitespace, matching the Slark opaque identifier contract.
        String trim = "[\\s\\x{00a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]";
        return !value.matches("(?s)^" + trim + ".*|.*" + trim + "$");
    }
    public String namespaceSlug() {
        try {
            var bytes = new ByteArrayOutputStream();
            var encoded = new DataOutputStream(bytes);
            for (String part : new String[]{registryId, scopeKind, ownerId}) {
                byte[] utf8 = part.getBytes(StandardCharsets.UTF_8);
                encoded.writeInt(utf8.length); encoded.write(utf8);
            }
            return "slark-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())).substring(0, 58);
        } catch (Exception unavailable) { throw new IllegalStateException("Namespace key unavailable"); }
    }
}
