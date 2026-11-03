package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.identity.IdentityBindingService;
import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.session.PlatformSessionService;
import com.iflytek.skillhub.domain.user.UserStatus;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlarkBridgeServiceTest {
    static final long NOW = 1_900_000_000_000L;
    static final String INTENT = "11111111-1111-4111-8111-111111111111";
    static final SlarkBridgeSettings SETTINGS = new SlarkBridgeSettings(
            "https://slark.example", "https://skills.example", "slark", "s".repeat(40), "c".repeat(40));
    final SlarkChallengeStore store = mock(SlarkChallengeStore.class);
    final SlarkBridgeClient client = mock(SlarkBridgeClient.class);
    final IdentityBindingService identities = mock(IdentityBindingService.class);
    final Map<String, SlarkChallengeStore.Challenge> entries = new ConcurrentHashMap<>();
    SlarkBridgeService service;

    @BeforeEach void setup() {
        doAnswer(invocation -> { entries.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(store).save(anyString(), any());
        when(store.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
        when(store.consume(anyString(), any())).thenAnswer(invocation -> entries.remove(invocation.getArgument(0), invocation.getArgument(1)));
        when(client.exchange(anyString(), anyString())).thenReturn(new SlarkBridgeClient.Grant("slark-user", 2,
                INTENT, "Display", NOW + 600_000, "publish", "/"));
        when(identities.bindOrCreate(any(), eq(UserStatus.ACTIVE))).thenReturn(new PlatformPrincipal(
                "local-user", "Display", null, null, "slark", Set.of("USER")));
        service = new SlarkBridgeService(SETTINGS, store, client, identities,
                new PlatformSessionService(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    MockHttpServletRequest callback(SlarkBridgeService.Launch launch) {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", SETTINGS.slarkOrigin());
        request.setCookies(new Cookie(SETTINGS.cookieName(), launch.cookie()));
        return request;
    }

    @Test void createsBoundChallengeAndOnlyServiceCanResolveIt() {
        var launch = service.start(INTENT);
        assertThat(launch.authorizeUri()).startsWith("https://slark.example/skillhub/authorize?");
        assertThat(launch.authorizeUri()).doesNotContain(launch.cookie());
        var input = new SlarkBridgeService.ResolveInput(INTENT, launch.challengeId(), "slark", SETTINGS.callbackUri());
        assertThatThrownBy(() -> service.resolve("Bearer wrong", input)).isInstanceOf(SlarkBridgeFailure.class);
        var resolved = service.resolve("Bearer " + SETTINGS.challengeToken(), input);
        assertThat(resolved.challengeHash()).isEqualTo(SlarkBridgeService.hash(launch.cookie()));
        assertThat(resolved.expiresAt()).isEqualTo(NOW + 60_000);
        assertThatThrownBy(() -> service.resolve("Bearer " + SETTINGS.challengeToken(),
                new SlarkBridgeService.ResolveInput(INTENT, launch.challengeId(), "other", SETTINGS.callbackUri())))
                .isInstanceOf(SlarkBridgeFailure.class);
    }

    @Test void callbackBindsStableSubjectWithoutEmailAndRotatesSession() {
        var launch = service.start(INTENT);
        var request = callback(launch);
        String oldId = request.getSession().getId();
        service.complete(launch.challengeId(), launch.state(), "A".repeat(43), request);
        assertThat(request.getSession().getId()).isNotEqualTo(oldId);
        assertThat(request.getSession().getAttribute(SlarkBridgeService.SESSION_BINDING)).isNotNull();
        var claims = ArgumentCaptor.forClass(OAuthClaims.class);
        verify(identities).bindOrCreate(claims.capture(), eq(UserStatus.ACTIVE));
        assertThat(claims.getValue().provider()).isEqualTo("slark");
        assertThat(claims.getValue().subject()).isEqualTo("slark-user");
        assertThat(claims.getValue().email()).isNull();
        assertThat(claims.getValue().emailVerified()).isFalse();
        assertThatThrownBy(() -> service.complete(launch.challengeId(), launch.state(), "A".repeat(43), request))
                .isInstanceOf(SlarkBridgeFailure.class);
        verify(client, times(1)).exchange(anyString(), anyString());
    }

    @Test void swappedStateCookieOriginAndDuplicateCookieCannotConsumeChallenge() {
        var a = service.start(INTENT);
        var b = service.start(INTENT);
        assertThatThrownBy(() -> service.complete(a.challengeId(), b.state(), "A".repeat(43), callback(a)))
                .isInstanceOf(SlarkBridgeFailure.class);
        assertThatThrownBy(() -> service.complete(a.challengeId(), a.state(), "A".repeat(43), callback(b)))
                .isInstanceOf(SlarkBridgeFailure.class);
        var foreign = callback(a);
        foreign.removeHeader("Origin"); foreign.addHeader("Origin", "https://evil.example");
        assertThatThrownBy(() -> service.complete(a.challengeId(), a.state(), "A".repeat(43), foreign))
                .isInstanceOf(SlarkBridgeFailure.class);
        var duplicate = callback(a);
        duplicate.setCookies(new Cookie(SETTINGS.cookieName(), a.cookie()), new Cookie(SETTINGS.cookieName(), a.cookie()));
        assertThatThrownBy(() -> service.complete(a.challengeId(), a.state(), "A".repeat(43), duplicate))
                .isInstanceOf(SlarkBridgeFailure.class);
        verifyNoInteractions(client, identities);
        service.complete(a.challengeId(), a.state(), "A".repeat(43), callback(a));
    }

    @Test void expiredChallengeFailsBeforeExchange() {
        var launch = service.start(INTENT);
        var expired = new SlarkBridgeService(SETTINGS, store, client, identities,
                new PlatformSessionService(), Clock.fixed(Instant.ofEpochMilli(NOW + 60_000), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.complete(launch.challengeId(), launch.state(), "A".repeat(43), callback(launch)))
                .isInstanceOf(SlarkBridgeFailure.class);
        verifyNoInteractions(client, identities);
    }

    @Test void concurrentCallbacksExchangeExactlyOnce() throws Exception {
        var launch = service.start(INTENT);
        var wins = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) executor.submit(() -> {
                try { service.complete(launch.challengeId(), launch.state(), "A".repeat(43), callback(launch)); wins.incrementAndGet(); }
                catch (SlarkBridgeFailure expected) { /* lost the atomic claim */ }
                finally { SecurityContextHolder.clearContext(); }
            });
        }
        assertThat(wins.get()).isEqualTo(1);
        verify(client, times(1)).exchange(anyString(), anyString());
    }

    @Test void exchangeFailureCannotReuseOldSessionOrReplayChallenge() {
        var launch = service.start(INTENT);
        var request = callback(launch);
        var old = request.getSession();
        old.setAttribute("platformPrincipal", "different-user");
        when(client.exchange(anyString(), anyString())).thenThrow(new SlarkBridgeFailure(503, "SLARK_UNAVAILABLE"));
        assertThatThrownBy(() -> service.complete(launch.challengeId(), launch.state(), "A".repeat(43), request))
                .isInstanceOf(SlarkBridgeFailure.class);
        assertThat(request.getSession(false)).isNull();
        assertThat(entries).doesNotContainKey(launch.challengeId());
        verifyNoInteractions(identities);
    }

    @Test void settingsRejectNonHttpsAndCredentialReuse() {
        assertThatThrownBy(() -> new SlarkBridgeSettings("http://evil.example", SETTINGS.publicOrigin(), "slark", "s".repeat(40), "c".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlarkBridgeSettings(SETTINGS.slarkOrigin(), SETTINGS.publicOrigin(), "slark", "s".repeat(40), "s".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
