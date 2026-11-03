package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBindingRepository;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "skillhub.auth.slark.enabled=true", "skillhub.auth.slark.origin=https://slark.example",
        "skillhub.auth.slark.public-origin=https://skills.example", "skillhub.auth.slark.client-id=slark",
        "skillhub.auth.slark.service-token=ssssssssssssssssssssssssssssssssssssssss",
        "skillhub.auth.slark.challenge-token=cccccccccccccccccccccccccccccccccccccccc",
        "skillhub.auth.slark.provisioning-enabled=true", "skillhub.auth.slark.registry-id=registry",
        "skillhub.auth.slark.provision-token=pppppppppppppppppppppppppppppppppppppppp" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SlarkNamespaceProvisioningSecurityTest {
    static final String PATH = "/api/v1/internal/slark/namespaces/provision";
    static final String TOKEN = "Bearer " + "p".repeat(40);
    @Autowired MockMvc mvc;
    @MockBean SlarkNamespaceBindingRepository repository;
    @BeforeEach void setup() {
        when(repository.provision(any(), anyString())).thenAnswer(call -> {
            SlarkNamespaceKey key = call.getArgument(0);
            return new SlarkNamespaceBinding(key.registryId(), key.scopeKind(), key.ownerId(),
                    "9007199254740993", key.namespaceSlug(), "PREPARED");
        });
    }
    @Test void fixedRegistryClientAndStringNamespaceAreUsedForAllFourScopes() throws Exception {
        for (String kind : new String[]{"public", "organization", "project", "personal"}) {
            String scope = kind.equals("public") ? "{\"kind\":\"public\"}"
                    : "{\"kind\":\"" + kind + "\",\"ownerId\":\"owner\"}";
            mvc.perform(post(PATH).header("Authorization", TOKEN).contentType("application/json").content("{\"scope\":" + scope + "}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.registryId").value("registry"))
                    .andExpect(jsonPath("$.namespaceId").value("9007199254740993"))
                    .andExpect(jsonPath("$.provisionState").value("PREPARED"));
            verify(repository).provision(new SlarkNamespaceKey("registry", kind, kind.equals("public") ? "public" : "owner"), "slark");
        }
    }
    @Test void credentialsAreIndependentAndCheckedBeforeMalformedJson() throws Exception {
        for (String token : new String[]{"", "Bearer user-token", "Bearer " + "s".repeat(40), "Bearer " + "c".repeat(40), TOKEN + "x"})
            mvc.perform(post(PATH).header("Authorization", token).contentType("application/json").content("{secret"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
        verifyNoInteractions(repository);
    }
    @Test void strictFieldsDuplicateKeysAndTrailingJsonCannotSelectAuthority() throws Exception {
        for (String body : new String[]{"", "{}", "null", "[]", "{\"scope\":{\"kind\":\"public\",\"ownerId\":\"x\"}}",
                "{\"scope\":{\"kind\":\"project\"}}", "{\"scope\":{\"kind\":\"project\",\"ownerId\":\" bad\"}}",
                "{\"scope\":{\"kind\":\"public\"},\"registryId\":\"other\"}",
                "{\"scope\":{\"kind\":\"public\"},\"namespaceId\":\"1\"}",
                "{\"scope\":{\"kind\":\"public\",\"kind\":\"project\"}}",
                "{\"scope\":{\"kind\":\"public\"},\"scope\":{\"kind\":\"public\"}}",
                "{\"scope\":{\"kind\":\"public\"}} {}"})
            mvc.perform(post(PATH).header("Authorization", TOKEN).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        verifyNoInteractions(repository);
    }
    @Test void oversizeAdjacentPathsAndWrongMethodsCannotReachProvisioning() throws Exception {
        mvc.perform(post(PATH).header("Authorization", TOKEN).contentType("application/json").content("x".repeat(5000)))
                .andExpect(status().isPayloadTooLarge());
        for (String path : new String[]{PATH + "/extra", "/api/v1/internal/slark/namespaces"})
            mvc.perform(post(path).header("Authorization", TOKEN).contentType("application/json").content("{}"))
                    .andExpect(status().is4xxClientError());
        mvc.perform(get(PATH).header("Authorization", TOKEN)).andExpect(status().is4xxClientError());
        verifyNoInteractions(repository);
    }
    @Test void conflictAndDependencyFailuresDoNotReflectDatabaseDetails() throws Exception {
        doThrow(new DataIntegrityViolationException("secret SQL")).when(repository).provision(any(), anyString());
        mvc.perform(post(PATH).header("Authorization", TOKEN).contentType("application/json").content("{\"scope\":{\"kind\":\"public\"}}"))
                .andExpect(status().isConflict()).andExpect(content().json("{\"error\":\"NAMESPACE_CONFLICT\"}"));
        doThrow(new IllegalStateException("secret token")).when(repository).provision(any(), anyString());
        mvc.perform(post(PATH).header("Authorization", TOKEN).contentType("application/json").content("{\"scope\":{\"kind\":\"public\"}}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"SLARK_UNAVAILABLE\"}"));
    }
}
