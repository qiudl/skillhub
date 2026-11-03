package com.iflytek.skillhub.auth.slark;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@ConditionalOnProperty(
        prefix = "skillhub.auth.slark",
        name = {"enabled", "distribution-enabled"},
        havingValue = "true")
public class SlarkDistributionConfiguration {
    @Bean
    SlarkDistributionSettings slarkDistributionSettings(
            SlarkBridgeSettings bridge,
            @Value("${skillhub.auth.slark.registry-id}") String registry,
            @Value("${skillhub.auth.slark.distribution-token}") String token,
            @Value("${skillhub.auth.slark.provision-token:}") String provision) {
        return SlarkDistributionSettings.from(bridge, registry, token, provision);
    }

    @Bean
    @Order(-3)
    SecurityFilterChain slarkDistributionChain(
            HttpSecurity http, SlarkDistributionSettings settings) throws Exception {
        byte[] expected = ("Bearer " + settings.token()).getBytes(StandardCharsets.UTF_8);
        http.securityMatcher(
                        new org.springframework.security.web.util.matcher.OrRequestMatcher(
                                new AntPathRequestMatcher(
                                        "/api/v1/internal/slark/distribution/read", "POST"),
                                new AntPathRequestMatcher(
                                        "/api/v1/internal/slark/catalog/list", "POST")))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
                                    throws IOException, ServletException {
                                response.setHeader("Cache-Control", "no-store");
                                response.setHeader("Pragma", "no-cache");
                                response.setHeader("Referrer-Policy", "no-referrer");
                                response.setHeader("X-Content-Type-Options", "nosniff");
                                var headers =
                                        java.util.Collections.list(
                                                request.getHeaders("Authorization"));
                                String supplied = request.getHeader("Authorization");
                                if (headers.size() != 1
                                        || supplied == null
                                        || supplied.length() > 263
                                        || request.getHeader("X-Mock-User-Id") != null
                                        || !MessageDigest.isEqual(
                                                expected,
                                                supplied.getBytes(StandardCharsets.UTF_8))) {
                                    SlarkBridgeGuardFilter.reject(response, 401, "AUTH_REQUIRED");
                                    return;
                                }
                                if (request.getContentLengthLong() < 0
                                        || request.getContentLengthLong() > 4096) {
                                    SlarkBridgeGuardFilter.reject(response, 413, "INVALID_REQUEST");
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        CsrfFilter.class);
        return http.build();
    }
}
