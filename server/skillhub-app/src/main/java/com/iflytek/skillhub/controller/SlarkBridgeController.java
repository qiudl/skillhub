package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.slark.SlarkBridgeFailure;
import com.iflytek.skillhub.auth.slark.SlarkBridgeService;
import com.iflytek.skillhub.auth.slark.SlarkBridgeSettings;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Private operated-instance browser/backchannel protocol, deliberately absent from public SDKs. */
@Hidden
@RestController
@ConditionalOnProperty(name = "skillhub.auth.slark.enabled", havingValue = "true")
public class SlarkBridgeController {
    private final SlarkBridgeService service;
    private final SlarkBridgeSettings settings;
    private final String sessionCookieName;
    public SlarkBridgeController(SlarkBridgeService service, SlarkBridgeSettings settings,
            @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.service = service; this.settings = settings; this.sessionCookieName = sessionCookieName;
    }

    @GetMapping("/api/v1/auth/slark/start")
    public void start(HttpServletRequest request, HttpServletResponse response) {
        parameters(request, Set.of("intentId"));
        var launch = service.start(request.getParameter("intentId"));
        response.addHeader("Set-Cookie", cookie(launch.cookie(), 60));
        response.setStatus(303); response.setHeader("Location", launch.authorizeUri());
    }
    @PostMapping(value = "/api/v1/auth/slark/callback", consumes = "application/x-www-form-urlencoded")
    public void callback(HttpServletRequest request, HttpServletResponse response) {
        parameters(request, Set.of("challengeId", "state", "code"));
        String destination = service.complete(request.getParameter("challengeId"), request.getParameter("state"), request.getParameter("code"), request);
        response.addHeader("Set-Cookie", cookie("", 0));
        response.setStatus(303); response.setHeader("Location", destination);
    }
    @PostMapping(value = "/api/v1/internal/auth/slark/challenges/resolve", consumes = "application/json")
    public SlarkBridgeService.Resolved resolve(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!body.keySet().equals(Set.of("intentId", "challengeId", "clientId", "redirectUri"))) throw new SlarkBridgeFailure(400, "INVALID_REQUEST");
        return service.resolve(request.getHeader("Authorization"), new SlarkBridgeService.ResolveInput(
                body.get("intentId"), body.get("challengeId"), body.get("clientId"), body.get("redirectUri")));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failure(Exception failure, HttpServletRequest request, HttpServletResponse response) {
        if (Boolean.TRUE.equals(request.getAttribute(SlarkBridgeService.CALLBACK_CLAIMED))) {
            // A cross-site form POST may omit the old SameSite=Lax session Cookie. Expire the
            // browser's old cookie after a validated callback fails, instead of reusing its identity.
            response.addHeader("Set-Cookie", ResponseCookie.from(sessionCookieName, "").path("/")
                    .httpOnly(true).secure(settings.secureCookie()).maxAge(0).build().toString());
            response.addHeader("Set-Cookie", cookie("", 0));
        }
        int status = failure instanceof SlarkBridgeFailure bridge ? bridge.status() : 503;
        String code = failure instanceof SlarkBridgeFailure ? failure.getMessage() : "SLARK_UNAVAILABLE";
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", code));
    }
    private String cookie(String value, long maxAge) {
        return ResponseCookie.from(settings.cookieName(), value).httpOnly(true).secure(settings.secureCookie())
                .sameSite(settings.secureCookie() ? "None" : "Lax").path("/").maxAge(maxAge).build().toString();
    }
    private static void parameters(HttpServletRequest request, Set<String> fields) {
        if (!request.getParameterMap().keySet().equals(fields) ||
                fields.stream().anyMatch(key -> request.getParameterValues(key).length != 1)) throw new SlarkBridgeFailure(400, "INVALID_REQUEST");
    }
}
