package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** REQ-20261013-0021 M1-09. Uncached scope facts from a fixed Slark authority.
 * This client grants no asset permission and is not an activation switch. The caller must
 * obtain the grant from its trusted session binding and the namespace from its repository. */
public final class SlarkScopeAuthorityClient {
    private final SlarkBridgeSettings settings;
    private final String registryId;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SlarkScopeAuthorityClient(SlarkBridgeSettings settings, String registryId) {
        if (settings == null || !SlarkNamespaceKey.validId(registryId))
            throw new IllegalArgumentException("Invalid scope authority configuration");
        this.settings = settings;
        this.registryId = registryId;
    }

    /** Immutable, single-call facts; membershipRole is null for public/personal scopes.
     * membershipRevision is an opaque decimal string, not an asset-maintainer grant. */
    public record Facts(String subject, SlarkNamespaceKey scope, String namespaceId,
                        boolean platformAdmin, String membershipRole, String membershipRevision) {}

    public Facts check(SlarkBridgeClient.Grant grant, SlarkNamespaceBinding binding) {
        long started = System.nanoTime();
        try {
            if (grant == null || !SlarkNamespaceKey.validId(grant.subject()) || grant.authVersion() < 0
                    || !SlarkBridgeService.uuid(grant.bridgeSessionId()) || grant.expiresAt() <= System.currentTimeMillis()
                    || binding == null || !registryId.equals(binding.registryId()) || !decimal(binding.namespaceId(), false))
                throw unavailable();
            var key = new SlarkNamespaceKey(registryId, binding.scopeKind(), binding.ownerId());
            if (!key.namespaceSlug().equals(binding.namespaceSlug())) throw unavailable();
            Map<String, String> scope = key.scopeKind().equals("public") ? Map.of("kind", "public")
                    : Map.of("kind", key.scopeKind(), "ownerId", key.ownerId());
            var body = Map.of("session", Map.of("bridgeSessionId", grant.bridgeSessionId(),
                    "subject", grant.subject(), "authVersion", grant.authVersion()),
                    "scope", scope, "namespaceId", binding.namespaceId());
            var request = HttpRequest.newBuilder(URI.create(settings.slarkOrigin() + "/api/internal/skillhub/scopes/check"))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + settings.serviceToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var future = http.sendAsync(request, response -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response = future.get(Math.max(1, TimeUnit.SECONDS.toNanos(3) - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            } finally { if (!future.isDone()) future.cancel(true); }
            if (response.statusCode() == 401) throw new SlarkBridgeFailure(401, "SLARK_SESSION_INVALID");
            if (response.statusCode() == 403) throw new SlarkBridgeFailure(403, "SLARK_SCOPE_DENIED");
            String media = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (response.statusCode() != 200 || !media.equalsIgnoreCase("application/json")) throw unavailable();
            JsonNode value = json.readTree(response.body());
            requireFields(value, "ok", "scope", "actor", "membership", "binding");
            if (!value.path("ok").isBoolean() || !value.get("ok").booleanValue()
                    || !json.valueToTree(scope).equals(value.get("scope"))) throw unavailable();
            JsonNode actor = value.get("actor");
            requireFields(actor, "userId", "active", "platformAdmin", "synthetic", "accountType");
            if (!equalsText(actor, "userId", grant.subject()) || !equalsText(actor, "accountType", "human")
                    || !actor.path("active").isBoolean() || !actor.get("active").booleanValue()
                    || !actor.path("synthetic").isBoolean() || actor.get("synthetic").booleanValue()
                    || !actor.path("platformAdmin").isBoolean()) throw unavailable();
            JsonNode member = value.get("membership");
            String role = null;
            if (Set.of("organization", "project").contains(key.scopeKind())) {
                requireFields(member, "userId", "scope", "role", "active");
                if (!equalsText(member, "userId", grant.subject()) || !json.valueToTree(scope).equals(member.get("scope"))
                        || !member.path("active").isBoolean() || !member.get("active").booleanValue()
                        || !member.path("role").isTextual()
                        || !Set.of("owner", "admin", "member").contains(member.get("role").textValue())) throw unavailable();
                role = member.get("role").textValue();
            } else if (!member.isNull() || (key.scopeKind().equals("personal") && !key.ownerId().equals(grant.subject()))) {
                throw unavailable();
            }
            JsonNode coordinate = value.get("binding");
            requireFields(coordinate, "registryId", "scopeKind", "ownerId", "namespaceId", "namespaceSlug", "membershipRevision");
            if (!equalsText(coordinate, "registryId", registryId) || !equalsText(coordinate, "scopeKind", key.scopeKind())
                    || !equalsText(coordinate, "ownerId", key.ownerId()) || !equalsText(coordinate, "namespaceId", binding.namespaceId())
                    || !equalsText(coordinate, "namespaceSlug", key.namespaceSlug())
                    || !coordinate.path("membershipRevision").isTextual()
                    || !decimal(coordinate.get("membershipRevision").textValue(), true)) throw unavailable();
            if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(3) || grant.expiresAt() <= System.currentTimeMillis())
                throw unavailable();
            return new Facts(grant.subject(), key, binding.namespaceId(), actor.get("platformAdmin").booleanValue(),
                    role, coordinate.get("membershipRevision").textValue());
        } catch (SlarkBridgeFailure failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception failure) { throw unavailable(); }
    }

    private static boolean decimal(String value, boolean zero) {
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(value); return true; } catch (NumberFormatException invalid) { return false; }
    }
    private static void requireFields(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) throw unavailable();
        for (String field : fields) if (!value.has(field)) throw unavailable();
    }
    private static boolean equalsText(JsonNode value, String field, String expected) {
        return value.path(field).isTextual() && expected.equals(value.get(field).textValue());
    }
    private static SlarkBridgeFailure unavailable() { return new SlarkBridgeFailure(503, "SLARK_SCOPE_UNAVAILABLE"); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 4096 - size) { subscription.cancel(); delegate.onError(unavailable()); return; }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
