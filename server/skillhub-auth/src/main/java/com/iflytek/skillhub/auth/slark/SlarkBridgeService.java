package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.identity.IdentityBindingService;
import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import com.iflytek.skillhub.auth.session.PlatformSessionService;
import com.iflytek.skillhub.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;

public class SlarkBridgeService {
    public static final String SESSION_BINDING = "slarkBridgeBindingV1";
    public static final String CALLBACK_CLAIMED = "slarkBridgeCallbackClaimed";
    private final SlarkBridgeSettings settings;
    private final SlarkChallengeStore store;
    private final SlarkBridgeClient client;
    private final IdentityBindingService identities;
    private final PlatformSessionService sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public SlarkBridgeService(SlarkBridgeSettings settings, SlarkChallengeStore store, SlarkBridgeClient client,
                              IdentityBindingService identities, PlatformSessionService sessions, Clock clock) {
        this.settings = settings; this.store = store; this.client = client;
        this.identities = identities; this.sessions = sessions; this.clock = clock;
    }
    public record Launch(String challengeId, String state, String cookie, String authorizeUri) {
        @Override public String toString() { return "SlarkLaunch[redacted]"; }
    }
    public record ResolveInput(String intentId, String challengeId, String clientId, String redirectUri) {}
    public record Resolved(String intentId, String challengeId, String clientId, String redirectUri, String challengeHash, long expiresAt) {}
    public record SessionBinding(String localUserId, SlarkBridgeClient.Grant grant) implements Serializable {}

    public Launch start(String intentId) {
        if (!uuid(intentId)) throw new SlarkBridgeFailure(400, "INVALID_REQUEST");
        String id = nonce(), state = nonce(), cookie = nonce();
        store.save(id, new SlarkChallengeStore.Challenge(intentId, settings.clientId(), settings.callbackUri(), hash(state), hash(cookie), clock.millis() + 60_000));
        return new Launch(id, state, cookie, settings.slarkOrigin() + "/skillhub/authorize?intentId=" + intentId + "&challengeId=" + id + "&state=" + state);
    }
    public Resolved resolve(String authorization, ResolveInput input) {
        if (authorization == null || !equal(authorization, "Bearer " + settings.challengeToken()))
            throw new SlarkBridgeFailure(401, "AUTH_REQUIRED");
        if (input == null || !settings.clientId().equals(input.clientId()) || !settings.callbackUri().equals(input.redirectUri()))
            throw SlarkBridgeFailure.invalid();
        var challenge = challenge(input.challengeId());
        if (!challenge.intentId().equals(input.intentId())) throw SlarkBridgeFailure.invalid();
        return new Resolved(input.intentId(), input.challengeId(), input.clientId(), input.redirectUri(), challenge.cookieHash(), challenge.expiresAt());
    }
    public String complete(String id, String state, String code, HttpServletRequest request) {
        if (!settings.slarkOrigin().equals(request.getHeader("Origin")) || !token(state) || !token(code)) throw SlarkBridgeFailure.invalid();
        var challenge = challenge(id);
        String cookie = null;
        if (request.getCookies() != null) for (var candidate : request.getCookies()) {
            if (candidate.getName().equals(settings.cookieName())) {
                if (cookie != null) throw SlarkBridgeFailure.invalid();
                cookie = candidate.getValue();
            }
        }
        if (!token(cookie) || !equal(hash(state), challenge.stateHash()) || !equal(hash(cookie), challenge.cookieHash())) throw SlarkBridgeFailure.invalid();
        if (!store.consume(id, challenge)) throw SlarkBridgeFailure.invalid();
        request.setAttribute(CALLBACK_CLAIMED, true);
        // A valid callback replaces the old identity; failed exchange never restores it.
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        var grant = client.exchange(code, challenge.cookieHash());
        if (grant.expiresAt() <= clock.millis()) throw SlarkBridgeFailure.invalid();
        var principal = identities.bindOrCreate(new OAuthClaims("slark", grant.subject(), null, false, grant.displayName(), Map.of()), UserStatus.ACTIVE);
        sessions.establishSession(principal, request);
        request.getSession().setAttribute(SESSION_BINDING, new SessionBinding(principal.userId(), grant));
        return switch (grant.destination()) {
            case "publish" -> "/dashboard/publish";
            case "reviews" -> "/dashboard/reviews";
            case "dashboard" -> "/dashboard";
            default -> throw SlarkBridgeFailure.invalid();
        };
    }
    private SlarkChallengeStore.Challenge challenge(String id) {
        if (!token(id)) throw SlarkBridgeFailure.invalid();
        var value = store.get(id);
        if (value == null || value.expiresAt() <= clock.millis() || !settings.clientId().equals(value.clientId()) ||
                !settings.callbackUri().equals(value.redirectUri())) throw SlarkBridgeFailure.invalid();
        return value;
    }
    private String nonce() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static boolean uuid(String value) { return value != null && value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[1-8][a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}"); }
    static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}") && Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)).equals(value);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(String a, String b) {
        return a.length() <= 300 && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
