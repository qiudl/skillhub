package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;

/** Independent write credential and fixed instance identity; never accept these from request JSON. */
public record SlarkNamespaceProvisionSettings(String registryId, String clientId, String token) {
    public SlarkNamespaceProvisionSettings {
        if (!SlarkNamespaceKey.validId(registryId) || clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,100}")
                || token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("Invalid Slark namespace provisioning configuration");
    }
    public static SlarkNamespaceProvisionSettings from(SlarkBridgeSettings bridge, String registryId, String token) {
        if (token == null || token.equals(bridge.serviceToken()) || token.equals(bridge.challengeToken()))
            throw new IllegalArgumentException("Independent provisioning credential required");
        return new SlarkNamespaceProvisionSettings(registryId, bridge.clientId(), token);
    }
    @Override public String toString() { return "SlarkNamespaceProvisionSettings[redacted]"; }
}
