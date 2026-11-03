package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SlarkBridgeClientTest {
    @Test void authenticatesPinnedEndpointAndRejectsRedirectsOversizeAndStalledBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        var redirected = new AtomicInteger();
        server.createContext("/sink", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/api/internal/skillhub/auth/check", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + "s".repeat(40));
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).contains("\"clientId\":\"slark\"");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (mode.get() == 1) {
                exchange.getResponseHeaders().add("Location", "/sink"); exchange.sendResponseHeaders(302, -1); exchange.close();
            } else if (mode.get() == 3) {
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            } else {
                byte[] body = (mode.get() == 2 ? "x".repeat(10000) : "{\"active\":true}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }
        });
        server.start();
        try {
            var client = new SlarkBridgeClient(new SlarkBridgeSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                    "https://skills.example", "slark", "s".repeat(40), "c".repeat(40)), new ObjectMapper());
            var grant = new SlarkBridgeClient.Grant("user", 1, SlarkBridgeServiceTest.INTENT, "A", Long.MAX_VALUE, "publish", "/");
            assertThat(client.active(grant)).isTrue();
            for (int value = 1; value <= 3; value++) {
                mode.set(value);
                assertThatThrownBy(() -> client.active(grant)).isInstanceOf(SlarkBridgeFailure.class)
                        .hasMessage("SLARK_UNAVAILABLE");
            }
            assertThat(redirected).hasValue(0);
        } finally { server.stop(0); }
    }
}
