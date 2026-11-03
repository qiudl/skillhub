package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlarkBridgeGuardFilterTest {
    final SlarkBridgeClient client = mock(SlarkBridgeClient.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final SlarkBridgeGuardFilter filter = new SlarkBridgeGuardFilter(client, users,
            Clock.fixed(Instant.ofEpochMilli(SlarkBridgeServiceTest.NOW), ZoneOffset.UTC));
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    MockHttpServletRequest request(String path, boolean login) {
        var request = new MockHttpServletRequest("GET", path);
        if (login) {
            var principal = new PlatformPrincipal("local-user", "A", null, null, "slark", Set.of("USER"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, Set.of()));
            request.getSession().setAttribute(SlarkBridgeService.SESSION_BINDING,
                    new SlarkBridgeService.SessionBinding("local-user", new SlarkBridgeClient.Grant(
                            "slark-user", 2, SlarkBridgeServiceTest.INTENT, "A", SlarkBridgeServiceTest.NOW + 1000, "publish", "/")));
            var user = mock(UserAccount.class);
            when(user.isActive()).thenReturn(true);
            when(users.findById("local-user")).thenReturn(Optional.of(user));
        }
        return request;
    }

    @Test void checksEachAuthenticatedRequestWithoutCaching() throws Exception {
        when(client.active(any())).thenReturn(true, false);
        var allowed = new AtomicBoolean();
        filter.doFilter(request("/api/web/skills", true), new MockHttpServletResponse(), (q, s) -> allowed.set(true));
        assertThat(allowed).isTrue();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/download/private", true), response, (q, s) -> fail("revoked session reached download"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(client, times(2)).active(any());
    }

    @Test void dependencyFailureIs503AndDoesNotAllowProtectedAction() throws Exception {
        when(client.active(any())).thenThrow(new SlarkBridgeFailure(503, "SLARK_UNAVAILABLE"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/web/skills/publish", true), response, (q, s) -> fail("unavailable authority allowed publish"));
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test void anonymousPublicRequestsDoNotDependOnSlarkAvailability() throws Exception {
        var allowed = new AtomicBoolean();
        filter.doFilter(request("/api/v1/search", false), new MockHttpServletResponse(), (q, s) -> allowed.set(true));
        assertThat(allowed).isTrue(); verifyNoInteractions(client);
    }

    @Test void nativeTokensAndAlternativeLoginCannotBypassSlark() throws Exception {
        for (String path : new String[]{"/api/v1/auth/local/login", "/api/v1/auth/device/token", "/oauth2/authorization/github", "/api/v1/tokens"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(path, false), response, (q, s) -> fail("native auth bypass"));
            assertThat(response.getStatus()).isEqualTo(403);
        }
        var bearer = request("/api/v1/download", false);
        bearer.addHeader("Authorization", "Bearer native-api-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(bearer, response, (q, s) -> fail("token bypass"));
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(client);
        var mockLogin = request("/api/web/skills", false);
        mockLogin.addHeader("X-Mock-User-Id", "local-admin");
        filter.doFilter(mockLogin, new MockHttpServletResponse(), (q, s) -> fail("mock identity bypass"));
    }

    @Test void missingBindingAndExpiredBindingCannotReachAuthority() throws Exception {
        var request = request("/api/web/skills", true);
        request.getSession().removeAttribute(SlarkBridgeService.SESSION_BINDING);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (q, s) -> fail("unbound session"));
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(client);
    }
}
