package com.iflytek.skillhub.auth.slark;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Exact service-only POST chain. Neither native user auth nor browser CSRF exemptions are widened. */
@Configuration
@ConditionalOnProperty(prefix = "skillhub.auth.slark", name = {"enabled", "provisioning-enabled"}, havingValue = "true")
public class SlarkNamespaceProvisionConfiguration {
    @Bean SlarkNamespaceProvisionSettings slarkNamespaceProvisionSettings(SlarkBridgeSettings bridge,
            @Value("${skillhub.auth.slark.registry-id}") String registryId,
            @Value("${skillhub.auth.slark.provision-token}") String token) {
        return SlarkNamespaceProvisionSettings.from(bridge, registryId, token);
    }
    @Bean @Order(-2)
    SecurityFilterChain slarkNamespaceProvisionChain(HttpSecurity http, SlarkNamespaceProvisionSettings settings) throws Exception {
        byte[] expected = ("Bearer " + settings.token()).getBytes(StandardCharsets.UTF_8);
        http.securityMatcher(new AntPathRequestMatcher("/api/v1/internal/slark/namespaces/provision", "POST"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws IOException, ServletException {
                        response.setHeader("Cache-Control", "no-store");
                        response.setHeader("Pragma", "no-cache");
                        response.setHeader("Referrer-Policy", "no-referrer");
                        String supplied = request.getHeader("Authorization");
                        if (supplied == null || supplied.length()>263 || request.getHeader("X-Mock-User-Id") != null ||
                                !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                            SlarkBridgeGuardFilter.reject(response, 401, "AUTH_REQUIRED"); return;
                        }
                        if (request.getContentLengthLong()<0 || request.getContentLengthLong()>4096) {
                            SlarkBridgeGuardFilter.reject(response, 413, "INVALID_REQUEST"); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, CsrfFilter.class);
        return http.build();
    }
}
