package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SlarkScopeAuthorityClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> media = new AtomicReference<>("application/json");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private SlarkScopeAuthorityClient client;
    private static final SlarkBridgeClient.Grant GRANT = new SlarkBridgeClient.Grant("subject", 7,
            "12345678-1234-4234-8234-123456789abc", "Person", Long.MAX_VALUE, "publish", "/");

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/sink", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/api/internal/skillhub/scopes/check", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", media.get());
            exchange.getResponseHeaders().add("Location", "/sink");
            if (response.get().equals("stall")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                return;
            }
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        client = new SlarkScopeAuthorityClient(new SlarkBridgeSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                "https://skills.example", "slark", "s".repeat(40), "c".repeat(40)), "registry");
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    private SlarkNamespaceBinding binding(String kind) {
        var key = new SlarkNamespaceKey("registry", kind, kind.equals("public") ? "public" : "subject");
        return new SlarkNamespaceBinding("registry", kind, key.ownerId(), "9007199254740993", key.namespaceSlug(), "PREPARED");
    }
    private ObjectNode success(String kind) {
        var bound = binding(kind);
        var value = json.createObjectNode().put("ok", true);
        var scope = value.putObject("scope").put("kind", kind);
        if (!kind.equals("public")) scope.put("ownerId", "subject");
        value.putObject("actor").put("userId", "subject").put("active", true).put("platformAdmin", false)
                .put("synthetic", false).put("accountType", "human");
        if (kind.equals("project") || kind.equals("organization")) {
            var member = value.putObject("membership").put("userId", "subject").put("role", "member").put("active", true);
            member.set("scope", scope.deepCopy());
        } else value.putNull("membership");
        value.putObject("binding").put("registryId", "registry").put("scopeKind", kind).put("ownerId", bound.ownerId())
                .put("namespaceId", bound.namespaceId()).put("namespaceSlug", bound.namespaceSlug())
                .put("membershipRevision", "9223372036854775807");
        return value;
    }
    private void unavailable() {
        assertThatThrownBy(() -> client.check(GRANT, binding("project")))
                .isInstanceOfSatisfying(SlarkBridgeFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(503);
                    assertThat(failure).hasMessage("SLARK_SCOPE_UNAVAILABLE").hasNoCause();
                });
    }
    @Test void fourScopesUseOnlyPinnedServiceEndpointAndPreserveBigintsWithoutCaching() throws Exception {
        for (String kind : new String[]{"public", "organization", "project", "personal"}) {
            response.set(success(kind).toString());
            var facts = client.check(GRANT, binding(kind));
            assertThat(facts.namespaceId()).isEqualTo("9007199254740993");
            assertThat(facts.membershipRevision()).isEqualTo("9223372036854775807");
            assertThat(facts.subject()).isEqualTo("subject");
            assertThat(facts.scope().scopeKind()).isEqualTo(kind);
            assertThat(facts.membershipRole()).isEqualTo(kind.equals("organization") || kind.equals("project") ? "member" : null);
            assertThat(facts.platformAdmin()).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer " + "s".repeat(40));
            var sent = json.readTree(request.get());
            assertThat(sent.size()).isEqualTo(3);
            assertThat(sent.get("scope")).isEqualTo(success(kind).get("scope"));
            assertThat(sent.get("namespaceId").textValue()).isEqualTo("9007199254740993");
            assertThat(sent.get("session").get("subject").textValue()).isEqualTo("subject");
            assertThat(sent.get("session").get("authVersion").intValue()).isEqualTo(7);
        }
        response.set(success("project").toString());
        client.check(GRANT, binding("project"));
        status.set(403);
        assertThatThrownBy(() -> client.check(GRANT, binding("project"))).hasMessage("SLARK_SCOPE_DENIED");
        assertThat(calls).hasValue(6);
    }
    @Test void rejectsEveryMissingUnknownOrWrongTypedField() {
        var valid = success("project");
        for (String path : new String[]{"", "/scope", "/actor", "/membership", "/membership/scope", "/binding"}) {
            var fields = new java.util.ArrayList<String>();
            valid.at(path).fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                var missing = valid.deepCopy(); ((ObjectNode) missing.at(path)).remove(field);
                response.set(missing.toString()); unavailable();
                var wrong = valid.deepCopy(); ((ObjectNode) wrong.at(path)).putArray(field).add("forged");
                response.set(wrong.toString()); unavailable();
            }
            var extra = valid.deepCopy(); ((ObjectNode) extra.at(path)).put("extra", "forged");
            response.set(extra.toString()); unavailable();
        }
    }
    @Test void rejectsMismatchedIdentityMembershipCoordinatesAndIneligibleActors() {
        for (String[] change : new String[][]{
                {"actor", "userId", "other"}, {"actor", "accountType", "agent"},
                {"scope", "ownerId", "other"}, {"scope", "kind", "organization"},
                {"membership", "userId", "other"}, {"membership", "role", "SUPER_ADMIN"},
                {"binding", "registryId", "other"}, {"binding", "scopeKind", "organization"},
                {"binding", "ownerId", "other"}, {"binding", "namespaceId", "9007199254740992"},
                {"binding", "namespaceSlug", "forged"}, {"binding", "membershipRevision", "00"},
                {"binding", "membershipRevision", "9223372036854775808"}, {"binding", "membershipRevision", "-1"}}) {
            var value = success("project"); ((ObjectNode) value.get(change[0])).put(change[1], change[2]);
            response.set(value.toString()); unavailable();
        }
        for (String[] change : new String[][]{{"actor", "active"}, {"membership", "active"}, {"actor", "synthetic"}}) {
            var value = success("project"); ((ObjectNode) value.get(change[0])).put(change[1], change[1].equals("synthetic"));
            response.set(value.toString()); unavailable();
        }
        var value = success("project"); ((ObjectNode) value.at("/membership/scope")).put("ownerId", "other");
        response.set(value.toString()); unavailable();
    }
    @Test void publicAndPersonalRequireNullMembershipAndPersonalCannotCrossOwner() {
        for (String kind : new String[]{"public", "personal"}) {
            var value = success(kind); value.set("membership", success("project").get("membership"));
            response.set(value.toString());
            assertThatThrownBy(() -> client.check(GRANT, binding(kind))).hasMessage("SLARK_SCOPE_UNAVAILABLE");
        }
        var value = success("personal");
        ((ObjectNode) value.get("actor")).put("userId", "other").put("platformAdmin", true);
        response.set(value.toString());
        var other = new SlarkBridgeClient.Grant("other", 7, GRANT.bridgeSessionId(), "Other", Long.MAX_VALUE, "publish", "/");
        assertThatThrownBy(() -> client.check(other, binding("personal"))).hasMessage("SLARK_SCOPE_UNAVAILABLE");
    }
    @Test void malformedDuplicateTrailingOversizeAndWrongMediaFailClosed() {
        String valid = success("project").toString();
        for (String body : new String[]{"null", "[]", "{", valid + " {}", valid.replace("\"ok\":true", "\"ok\":false,\"ok\":true"), "x".repeat(5000)}) {
            response.set(body); unavailable();
        }
        response.set(valid); media.set("application/json-forged"); unavailable();
    }
    @Test void statusCodesNeverFallbackOrFollowRedirects() {
        response.set("sensitive-response-body");
        for (int code : new int[]{301, 302, 307, 400, 404, 500, 503}) { status.set(code); unavailable(); }
        status.set(401);
        assertThatThrownBy(() -> client.check(GRANT, binding("project"))).hasMessage("SLARK_SESSION_INVALID");
        status.set(403);
        assertThatThrownBy(() -> client.check(GRANT, binding("project"))).hasMessage("SLARK_SCOPE_DENIED");
        assertThat(redirected).hasValue(0);
    }
    @Test void bodyReadHasFiniteDeadlineAndInvalidLocalInputNeverSends() {
        response.set("stall");
        long start = System.nanoTime(); unavailable();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
        int count = calls.get();
        var b = binding("project");
        for (var invalid : new SlarkNamespaceBinding[]{null,
                new SlarkNamespaceBinding("other", b.scopeKind(), b.ownerId(), b.namespaceId(), b.namespaceSlug(), "PREPARED"),
                new SlarkNamespaceBinding("registry", b.scopeKind(), b.ownerId(), "01", b.namespaceSlug(), "PREPARED"),
                new SlarkNamespaceBinding("registry", b.scopeKind(), b.ownerId(), b.namespaceId(), "forged", "PREPARED")}) {
            assertThatThrownBy(() -> client.check(GRANT, invalid)).hasMessage("SLARK_SCOPE_UNAVAILABLE");
        }
        var expired = new SlarkBridgeClient.Grant("subject", 7, GRANT.bridgeSessionId(), "Person", 0, "publish", "/");
        assertThatThrownBy(() -> client.check(expired, b)).hasMessage("SLARK_SCOPE_UNAVAILABLE");
        assertThat(calls).hasValue(count);
    }
}
