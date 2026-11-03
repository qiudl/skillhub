package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;

/** Dedicated operated-instance read credential; never a browser or publication grant. */
public record SlarkDistributionSettings(String registryId, String token) {
    public SlarkDistributionSettings {
        if (!SlarkNamespaceKey.validId(registryId)
                || token == null
                || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("Invalid distribution configuration");
    }

    public static SlarkDistributionSettings from(
            SlarkBridgeSettings bridge, String registryId, String token, String provisionToken) {
        if (token == null
                || token.equals(bridge.serviceToken())
                || token.equals(bridge.challengeToken())
                || token.equals(provisionToken))
            throw new IllegalArgumentException("Independent distribution credential required");
        return new SlarkDistributionSettings(registryId, token);
    }

    @Override
    public String toString() {
        return "SlarkDistributionSettings[redacted]";
    }
}
