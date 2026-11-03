package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.service.SlarkNamespaceProvisionService;
import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal operated-instance protocol, not a public user/SDK namespace creation API. */
@Hidden
@RestController
@ConditionalOnProperty(prefix = "skillhub.auth.slark", name = {"enabled", "provisioning-enabled"}, havingValue = "true")
public class SlarkNamespaceProvisionController {
    private final SlarkNamespaceProvisionService service;
    private final ObjectMapper json;
    public SlarkNamespaceProvisionController(SlarkNamespaceProvisionService service, ObjectMapper json) {
        this.service = service;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value = "/api/v1/internal/slark/namespaces/provision", consumes = "application/json")
    public SlarkNamespaceBinding provision(@RequestBody byte[] body) throws IOException {
        JsonNode input = json.readTree(body);
        requireFields(input, Set.of("scope"));
        JsonNode scope = input.get("scope");
        if (scope == null || !scope.path("kind").isTextual()) throw new IllegalArgumentException();
        String kind = scope.get("kind").asText();
        requireFields(scope, kind.equals("public") ? Set.of("kind") : Set.of("kind", "ownerId"));
        if (!kind.equals("public") && !scope.path("ownerId").isTextual()) throw new IllegalArgumentException();
        return service.provision(kind, kind.equals("public") ? "public" : scope.get("ownerId").asText());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failure(Exception failure) {
        int status = failure instanceof IllegalArgumentException || failure instanceof JsonProcessingException || failure instanceof HttpMessageNotReadableException ? 400
                : failure instanceof DataIntegrityViolationException ? 409 : 503;
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error",
                status == 400 ? "INVALID_REQUEST" : status == 409 ? "NAMESPACE_CONFLICT" : "SLARK_UNAVAILABLE"));
    }
    private static void requireFields(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject()) throw new IllegalArgumentException();
        var fields = new HashSet<String>(); object.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(expected)) throw new IllegalArgumentException();
    }
}
