package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.identity.IdentityBindingService;
import com.iflytek.skillhub.auth.session.PlatformSessionService;
import com.iflytek.skillhub.auth.token.ApiTokenAuthenticationFilter;
import com.iflytek.skillhub.auth.token.ApiTokenScopeFilter;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated bridge endpoints; the ordinary chains retain their existing CSRF rules. */
@Configuration
@ConditionalOnProperty(name = "skillhub.auth.slark.enabled", havingValue = "true")
public class SlarkBridgeConfiguration {
    @Bean SlarkBridgeSettings slarkBridgeSettings(
            @Value("${skillhub.auth.slark.origin}") String origin,
            @Value("${skillhub.auth.slark.public-origin}") String publicOrigin,
            @Value("${skillhub.auth.slark.client-id}") String clientId,
            @Value("${skillhub.auth.slark.service-token}") String serviceToken,
            @Value("${skillhub.auth.slark.challenge-token}") String challengeToken) {
        return new SlarkBridgeSettings(origin, publicOrigin, clientId, serviceToken, challengeToken);
    }
    @Bean SlarkChallengeStore slarkChallengeStore(StringRedisTemplate redis, ObjectMapper json) { return new SlarkChallengeStore(redis, json); }
    @Bean SlarkBridgeClient slarkBridgeClient(SlarkBridgeSettings settings, ObjectMapper json) { return new SlarkBridgeClient(settings, json); }
    @Bean
    @ConditionalOnProperty(name = "skillhub.auth.slark.registry-id")
    SlarkScopeAuthorityClient slarkScopeAuthorityClient(SlarkBridgeSettings settings,
            @Value("${skillhub.auth.slark.registry-id}") String registryId) {
        return new SlarkScopeAuthorityClient(settings, registryId);
    }
    @Bean SlarkBridgeService slarkBridgeService(SlarkBridgeSettings settings, SlarkChallengeStore store,
            SlarkBridgeClient client, IdentityBindingService identities, PlatformSessionService sessions) {
        return new SlarkBridgeService(settings, store, client, identities, sessions, Clock.systemUTC());
    }
    @Bean SlarkBridgeGuardFilter slarkBridgeGuardFilter(SlarkBridgeClient client, UserAccountRepository users) {
        return new SlarkBridgeGuardFilter(client, users, Clock.systemUTC());
    }
    @Bean FilterRegistrationBean<SlarkBridgeGuardFilter> slarkGuardServletRegistration(SlarkBridgeGuardFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    // These native filters still run inside the ordinary chains. Disable their additional servlet
    // registrations so they cannot reinterpret the bridge's service Bearer outside its own chain.
    @Bean FilterRegistrationBean<ApiTokenAuthenticationFilter> slarkTokenServletRegistration(ApiTokenAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean FilterRegistrationBean<ApiTokenScopeFilter> slarkScopeServletRegistration(ApiTokenScopeFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }

    @Bean @Order(-1)
    SecurityFilterChain slarkBridgeChain(HttpSecurity http) throws Exception {
        http.securityMatcher(new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/auth/slark/start", "GET"),
                new AntPathRequestMatcher("/api/v1/auth/slark/callback", "POST"),
                new AntPathRequestMatcher("/api/v1/internal/auth/slark/challenges/resolve", "POST")))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Callback uses exact Origin + single-use state + HttpOnly challenge Cookie;
                // resolve owns a separate constant-time service credential check.
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(headers -> headers.referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        response.setHeader("Cache-Control", "no-store");
                        response.setHeader("Pragma", "no-cache");
                        if ("POST".equals(request.getMethod()) && (request.getContentLengthLong() < 0 || request.getContentLengthLong() > 4096)) {
                            SlarkBridgeGuardFilter.reject(response, 413, "INVALID_REQUEST"); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, CsrfFilter.class);
        return http.build();
    }
}
