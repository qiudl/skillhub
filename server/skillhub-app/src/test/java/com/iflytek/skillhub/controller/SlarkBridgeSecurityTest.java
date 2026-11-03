package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.slark.*;
import com.iflytek.skillhub.domain.namespace.GlobalNamespaceMembershipService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "skillhub.auth.slark.enabled=true", "skillhub.auth.slark.origin=https://slark.example",
        "skillhub.auth.slark.public-origin=https://skills.example", "skillhub.auth.slark.client-id=slark",
        "skillhub.auth.slark.service-token=ssssssssssssssssssssssssssssssssssssssss",
        "skillhub.auth.slark.challenge-token=cccccccccccccccccccccccccccccccccccccccc" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SlarkBridgeSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired IdentityBindingRepository identities;
    @MockBean SlarkChallengeStore store;
    @MockBean SlarkBridgeClient client;
    @MockBean DeviceAuthService deviceAuthService;
    @MockBean NamespaceMemberRepository namespaceMemberRepository;
    @MockBean GlobalNamespaceMembershipService globalMembership;
    final Map<String, SlarkChallengeStore.Challenge> entries = new ConcurrentHashMap<>();
    static final String INTENT = "11111111-1111-4111-8111-111111111111";

    @BeforeEach void setup() {
        doAnswer(call -> { entries.put(call.getArgument(0), call.getArgument(1)); return null; }).when(store).save(anyString(), any());
        when(store.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        when(store.consume(anyString(), any())).thenAnswer(call -> entries.remove(call.getArgument(0), call.getArgument(1)));
        when(client.exchange(anyString(), anyString())).thenReturn(new SlarkBridgeClient.Grant("stable-user", 3, INTENT, "First name", System.currentTimeMillis() + 600_000, "publish", "/"));
        when(client.active(any())).thenReturn(true);
    }
    record Launch(Map<String, String> query, Cookie cookie) {}
    Launch launch() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/slark/start").queryParam("intentId", INTENT))
                .andExpect(status().isSeeOther()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        String cookieHeader = response.getHeader("Set-Cookie");
        assertThat(cookieHeader).contains("HttpOnly", "Secure", "SameSite=None", "Path=/", "Max-Age=60");
        String cookieValue = cookieHeader.split(";", 2)[0].split("=", 2)[1];
        var query = Arrays.stream(URI.create(response.getHeader("Location")).getRawQuery().split("&"))
                .map(part -> part.split("=", 2)).collect(Collectors.toMap(part -> part[0], part -> part[1]));
        return new Launch(query, new Cookie("__Host-slark-challenge", cookieValue));
    }
    MockHttpSession login(Launch launch) throws Exception {
        String body = "challengeId=" + launch.query().get("challengeId") + "&state=" + launch.query().get("state") + "&code=" + "A".repeat(43);
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/slark/callback").header("Origin", "https://slark.example")
                        .cookie(launch.cookie()).contentType("application/x-www-form-urlencoded").content(body))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/dashboard/publish")).andReturn().getRequest().getSession(false);
    }

    @Test void realSecurityChainsAcceptBridgeAndEnforceRevocationAndStableIdentity() throws Exception {
        var launch = launch();
        String body = "{\"intentId\":\"" + INTENT + "\",\"challengeId\":\"" + launch.query().get("challengeId") +
                "\",\"clientId\":\"slark\",\"redirectUri\":\"https://skills.example/api/v1/auth/slark/callback\"}";
        mvc.perform(post("/api/v1/internal/auth/slark/challenges/resolve").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/auth/slark/challenges/resolve").header("Authorization", "Bearer " + "c".repeat(40))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intentId").value(INTENT));
        var session = login(launch);
        String localId = identities.findByProviderCodeAndSubject("slark", "stable-user").orElseThrow().getUserId();
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
        when(client.exchange(anyString(), anyString())).thenReturn(new SlarkBridgeClient.Grant("stable-user", 3, INTENT, "Renamed", System.currentTimeMillis() + 600_000, "publish", "/"));
        var compatibilitySession = login(launch());
        assertThat(identities.findByProviderCodeAndSubject("slark", "stable-user").orElseThrow().getUserId()).isEqualTo(localId);
        when(client.active(any())).thenReturn(false);
        mvc.perform(get("/api/v1/skills/example").session(compatibilitySession)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void wrongCookieAndAdjacentRoutesNeverGainTheCallbackExemption() throws Exception {
        var launch = launch();
        String form = "challengeId=" + launch.query().get("challengeId") + "&state=" + launch.query().get("state") + "&code=" + "A".repeat(43);
        mvc.perform(post("/api/v1/auth/slark/callback").header("Origin", "https://slark.example")
                        .cookie(new Cookie("__Host-slark-challenge", "B".repeat(43))).contentType("application/x-www-form-urlencoded").content(form))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/slark/callback/extra").contentType("application/x-www-form-urlencoded").content(form))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void nativeBearerIsDeniedInEveryCompatibilityAndOrdinaryChain() throws Exception {
        for (String path : new String[]{"/api/v1/labels", "/api/v1/download", "/api/v1/skills/example", "/api/web/skills"}) {
            mvc.perform(get(path).header("Authorization", "Bearer native-token")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/oauth2/authorization/github")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/local/login").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void validatedCallbackFailureClearsAnOldCookieEvenWhenSameSiteOmittedIt() throws Exception {
        var launch = launch();
        when(client.exchange(anyString(), anyString())).thenThrow(new SlarkBridgeFailure(503, "SLARK_UNAVAILABLE"));
        String form = "challengeId=" + launch.query().get("challengeId") + "&state=" + launch.query().get("state") + "&code=" + "A".repeat(43);
        var response = mvc.perform(post("/api/v1/auth/slark/callback").header("Origin", "https://slark.example")
                        .cookie(launch.cookie()).contentType("application/x-www-form-urlencoded").content(form))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value -> assertThat(value).startsWith("SESSION=").contains("Max-Age=0"));
        assertThat(entries).doesNotContainKey(launch.query().get("challengeId"));
    }
}
