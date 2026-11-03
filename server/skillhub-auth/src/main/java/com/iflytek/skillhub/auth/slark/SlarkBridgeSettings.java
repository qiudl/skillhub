package com.iflytek.skillhub.auth.slark;

import java.net.URI;
import java.util.Set;

public record SlarkBridgeSettings(String slarkOrigin, String publicOrigin, String clientId,
                                  String serviceToken, String challengeToken) {
    public SlarkBridgeSettings {
        slarkOrigin = origin(slarkOrigin);
        publicOrigin = origin(publicOrigin);
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,100}") ||
                serviceToken == null || !serviceToken.matches("[A-Za-z0-9_-]{32,256}") ||
                challengeToken == null || !challengeToken.matches("[A-Za-z0-9_-]{32,256}") ||
                serviceToken.equals(challengeToken)) throw new IllegalArgumentException("Invalid Slark bridge configuration");
    }
    private static String origin(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) ||
                    !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) throw new IllegalArgumentException();
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid Slark bridge origin"); }
    }
    public String callbackUri() { return publicOrigin + "/api/v1/auth/slark/callback"; }
    public boolean secureCookie() { return publicOrigin.startsWith("https:"); }
    public String cookieName() { return secureCookie() ? "__Host-slark-challenge" : "slark-challenge-local"; }
    @Override public String toString() { return "SlarkBridgeSettings[redacted]"; }
}
