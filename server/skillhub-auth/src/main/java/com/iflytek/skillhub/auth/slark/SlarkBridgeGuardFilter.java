package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed inside every security chain when opted in; never registered as a servlet filter. */
public class SlarkBridgeGuardFilter extends OncePerRequestFilter {
    private final SlarkBridgeClient client;
    private final UserAccountRepository users;
    private final Clock clock;
    public SlarkBridgeGuardFilter(SlarkBridgeClient client, UserAccountRepository users, Clock clock) {
        this.client = client; this.users = users; this.clock = clock;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RouteSecurityPolicyRegistry.requestPath(request);
        if ((path.startsWith("/api/v1/auth/") && !path.equals("/api/v1/auth/me") && !path.equals("/api/v1/auth/logout")) ||
                path.equals("/api/v1/tokens") || path.startsWith("/api/v1/tokens/") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/")) {
            reject(response, 403, "SLARK_LOGIN_REQUIRED"); return;
        }
        if (request.getHeader("Authorization") != null || request.getHeader("X-Mock-User-Id") != null) {
            reject(response, 401, "SLARK_LOGIN_REQUIRED"); return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(SlarkBridgeService.SESSION_BINDING);
        Object principalValue = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)
                ? authentication.getPrincipal() : session == null ? null : session.getAttribute("platformPrincipal");
        // Compat chains are stateless, but AuthContextFilter also reads platformPrincipal from
        // the servlet session. Validate that identity even without a Spring Authentication.
        if (principalValue != null || stored != null) {
            response.setHeader("Cache-Control", "no-store");
            if (!(principalValue instanceof PlatformPrincipal principal) || !"slark".equals(principal.oauthProvider()) ||
                    !(stored instanceof SlarkBridgeService.SessionBinding binding) || !principal.userId().equals(binding.localUserId()) ||
                    binding.grant().expiresAt() <= clock.millis()) {
                invalidate(request); reject(response, 401, "INVALID_SESSION"); return;
            }
            try {
                var user = users.findById(principal.userId());
                if (user.isEmpty() || !user.get().isActive() || user.get().isSystemAccount() || !client.active(binding.grant())) {
                    invalidate(request); reject(response, 401, "INVALID_SESSION"); return;
                }
            } catch (Exception unavailable) {
                // Retain binding for recovery, but never execute the protected operation.
                reject(response, 503, "SLARK_UNAVAILABLE"); return;
            }
        }
        chain.doFilter(request, response);
    }
    private static void invalidate(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
    }
    static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
