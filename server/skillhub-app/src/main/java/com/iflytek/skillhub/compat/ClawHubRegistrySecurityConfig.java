package com.iflytek.skillhub.compat;

import com.iflytek.skillhub.auth.token.ApiTokenAuthenticationFilter;
import com.iflytek.skillhub.auth.slark.SlarkBridgeGuardFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * Declares a dedicated stateless security chain for public compatibility endpoints used by
 * registry-style clients.
 */
@Configuration
public class ClawHubRegistrySecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain publicLabelFilterChain(HttpSecurity http, ObjectProvider<SlarkBridgeGuardFilter> slarkGuard) throws Exception {
        http
                .securityMatcher(
                        new OrRequestMatcher(
                                new AntPathRequestMatcher("/api/v1/labels", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/api/web/labels", HttpMethod.GET.name())
                        )
                )
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        slarkGuard.ifAvailable(filter -> http.addFilterBefore(filter, LogoutFilter.class));
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain clawHubRegistryFilterChain(
            HttpSecurity http,
            ApiTokenAuthenticationFilter apiTokenAuthenticationFilter,
            ObjectProvider<SlarkBridgeGuardFilter> slarkGuard) throws Exception {
        http
                .securityMatcher(
                        "/api/v1/search",
                        "/api/v1/download",
                        "/api/v1/skills/*"
                )
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        slarkGuard.ifAvailable(filter -> http.addFilterBefore(filter, LogoutFilter.class));
        return http.build();
    }
}
