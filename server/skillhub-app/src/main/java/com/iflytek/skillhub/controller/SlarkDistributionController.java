package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.iflytek.skillhub.auth.slark.SlarkDistributionSettings;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import com.iflytek.skillhub.service.SlarkDistributionService;

import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Hidden
@RestController
@ConditionalOnProperty(
        prefix = "skillhub.auth.slark",
        name = {"enabled", "distribution-enabled"},
        havingValue = "true")
public class SlarkDistributionController {
    private final SlarkDistributionService service;
    private final SlarkDistributionSettings settings;
    private final ObjectMapper json;

    public SlarkDistributionController(
            SlarkDistributionService service,
            SlarkDistributionSettings settings,
            ObjectMapper json) {
        this.service = service;
        this.settings = settings;
        this.json =
                json.copy()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping(value = "/api/v1/internal/slark/distribution/read", consumes = "application/json")
    public ResponseEntity<byte[]> read(@RequestBody byte[] body) throws Exception {
        var input = json.readTree(body);
        fields(input, Set.of("scope", "namespaceId", "skillId", "versionId", "subject"));
        var scope = input.get("scope");
        if (scope == null || !scope.path("kind").isTextual()) throw new IllegalArgumentException();
        String kind = scope.get("kind").asText();
        fields(scope, kind.equals("public") ? Set.of("kind") : Set.of("kind", "ownerId"));
        if (!kind.equals("public") && !scope.path("ownerId").isTextual())
            throw new IllegalArgumentException();
        var key =
                new SlarkNamespaceKey(
                        settings.registryId(),
                        kind,
                        kind.equals("public") ? "public" : scope.get("ownerId").asText());
        JsonNode subject = input.get("subject");
        if (subject == null || !(subject.isNull() || subject.isTextual()))
            throw new IllegalArgumentException();
        String actor = subject.isNull() ? null : subject.asText();
        if (actor != null && !SlarkNamespaceKey.validId(actor))
            throw new IllegalArgumentException();
        var result =
                service.read(
                        new SlarkDistributionService.Request(
                                key,
                                id(input, "namespaceId"),
                                id(input, "skillId"),
                                id(input, "versionId"),
                                actor));
        String snapshot =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.writeValueAsBytes(result.snapshot()));
        if (snapshot.length() > 8192) throw new IllegalStateException();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(result.bytes().length)
                .header("X-Slark-Distribution-Snapshot", snapshot)
                .body(result.bytes());
    }

    private static Long id(JsonNode input, String field) {
        var value = input.get(field);
        if (value == null || !value.isTextual() || !value.asText().matches("[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException();
        try {
            return Long.valueOf(value.asText());
        } catch (Exception bad) {
            throw new IllegalArgumentException();
        }
    }

    private static void fields(JsonNode input, Set<String> required) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException();
        var actual = new HashSet<String>();
        input.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw new IllegalArgumentException();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failure(Exception error) {
        int status =
                error instanceof IllegalArgumentException
                                || error instanceof JsonProcessingException
                                || error instanceof HttpMessageNotReadableException
                        ? 400
                        : error instanceof SlarkDistributionService.Denied ? 403 : 503;
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(
                        Map.of(
                                "error",
                                status == 400
                                        ? "INVALID_REQUEST"
                                        : status == 403
                                                ? "DISTRIBUTION_UNAVAILABLE"
                                                : "SLARK_UNAVAILABLE"));
    }
}
