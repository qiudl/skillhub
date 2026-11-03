package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Fixed authenticated backchannel, bounded response and deadline including response-body reads. */
public class SlarkBridgeClient {
    private final SlarkBridgeSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public SlarkBridgeClient(SlarkBridgeSettings settings, ObjectMapper json) { this.settings = settings; this.json = json; }
    public record Grant(String subject, int authVersion, String bridgeSessionId, String displayName,
                        long expiresAt, String destination, String returnPath) implements Serializable {}

    public Grant exchange(String code, String challengeHash) {
        JsonNode value = post("exchange", Map.of("code", code, "challengeHash", challengeHash,
                "clientId", settings.clientId(), "redirectUri", settings.callbackUri()));
        if (!value.isObject() || value.size() != 7 || !text(value, "subject", 200) ||
                !text(value, "displayName", 128) || !text(value, "returnPath", 2048) ||
                !java.util.Set.of("publish", "dashboard", "reviews").contains(value.path("destination").asText()) ||
                !value.path("returnPath").asText().startsWith("/") || value.path("returnPath").asText().startsWith("//") || !text(value, "bridgeSessionId", 36) ||
                !SlarkBridgeService.uuid(value.path("bridgeSessionId").asText()) ||
                !value.path("authVersion").isIntegralNumber() || !value.path("authVersion").canConvertToInt() || value.path("authVersion").asInt() < 0 ||
                !value.path("expiresAt").isIntegralNumber() || !value.path("expiresAt").canConvertToLong())
            throw SlarkBridgeFailure.unavailable();
        return new Grant(value.get("subject").asText(), value.get("authVersion").asInt(),
                value.get("bridgeSessionId").asText(), value.get("displayName").asText(), value.get("expiresAt").asLong(),
                value.get("destination").asText(), value.get("returnPath").asText());
    }
    public boolean active(Grant grant) {
        JsonNode value = post("check", Map.of("subject", grant.subject(), "authVersion", grant.authVersion(),
                "bridgeSessionId", grant.bridgeSessionId(), "clientId", settings.clientId()));
        if (!value.isObject() || value.size() != 1 || !value.path("active").isBoolean()) throw SlarkBridgeFailure.unavailable();
        return value.get("active").asBoolean();
    }
    private static boolean text(JsonNode value, String field, int max) {
        return value.path(field).isTextual() && !value.get(field).asText().isBlank() && value.get(field).asText().length() <= max;
    }
    private JsonNode post(String action, Map<String, ?> body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(settings.slarkOrigin() + "/api/internal/skillhub/auth/" + action))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + settings.serviceToken())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var future = http.sendAsync(request, response -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = future.get(3, TimeUnit.SECONDS); }
            finally { if (!future.isDone()) future.cancel(true); }
            if (response.statusCode() == 401) throw SlarkBridgeFailure.invalid();
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
                throw SlarkBridgeFailure.unavailable();
            return json.readTree(response.body());
        } catch (SlarkBridgeFailure failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw SlarkBridgeFailure.unavailable(); }
        catch (Exception failure) { throw SlarkBridgeFailure.unavailable(); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 4096 - size) { subscription.cancel(); delegate.onError(SlarkBridgeFailure.unavailable()); return; }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
