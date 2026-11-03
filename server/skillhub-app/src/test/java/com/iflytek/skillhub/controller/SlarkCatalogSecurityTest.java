package com.iflytek.skillhub.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.service.SlarkCatalogService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "skillhub.auth.slark.enabled=true",
            "skillhub.auth.slark.origin=https://slark.example",
            "skillhub.auth.slark.public-origin=https://skills.example",
            "skillhub.auth.slark.client-id=slark",
            "skillhub.auth.slark.service-token=ssssssssssssssssssssssssssssssssssssssss",
            "skillhub.auth.slark.challenge-token=cccccccccccccccccccccccccccccccccccccccc",
            "skillhub.auth.slark.registry-id=registry",
            "skillhub.auth.slark.distribution-enabled=true",
            "skillhub.auth.slark.distribution-token=dddddddddddddddddddddddddddddddddddddddd",
            "skillhub.auth.slark.provision-token=pppppppppppppppppppppppppppppppppppppppp"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SlarkCatalogSecurityTest {
    static final String PATH = "/api/v1/internal/slark/catalog/list",
            TOKEN = "Bearer " + "d".repeat(40);
    static final String BODY =
            "{\"scope\":{\"kind\":\"personal\",\"ownerId\":\"u1\"},\"namespaceId\":\"9007199254740993\",\"subject\":\"u1\",\"afterSkillId\":null,\"q\":\"\",\"limit\":20}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SlarkCatalogService service;

    @Test
    void independentCredentialPrecedesJsonAndBrowserAuthenticationCannotSubstitute()
            throws Exception {
        for (String token :
                new String[] {
                    "",
                    "Bearer user",
                    "Bearer " + "s".repeat(40),
                    "Bearer " + "c".repeat(40),
                    "Bearer " + "p".repeat(40),
                    TOKEN + "x"
                })
            mvc.perform(
                            post(PATH)
                                    .header("Authorization", token)
                                    .contentType("application/json")
                                    .content("{broken"))
                    .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .header("X-Mock-User-Id", "admin")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN, TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void strictInputAndMethodBoundariesRejectBeforeService() throws Exception {
        for (String body :
                java.util.List.of(
                        BODY.replace("\"limit\":20", "\"limit\":101"),
                        BODY.replace("\"limit\":20", "\"limit\":1.5"),
                        BODY.replace("null", "\"01\""),
                        BODY.replace(
                                "\"namespaceId\":\"9007199254740993\"",
                                "\"namespaceId\":9007199254740993"),
                        BODY.replace("\"q\":\"\"", "\"q\":\"\",\"q\":\"different\""),
                        BODY + " {}",
                        BODY.replace("\"q\":\"\"", "\"q\":\"" + "x".repeat(201) + "\"")))
            mvc.perform(
                            post(PATH)
                                    .header("Authorization", TOKEN)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(" ".repeat(4097)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(PATH).header("Authorization", TOKEN))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }

    @Test
    void exactStringCoordinatesReturnNoStoreMetadataWithoutCookies() throws Exception {
        when(service.list(any()))
                .thenReturn(
                        new SlarkCatalogService.Page(
                                "registry",
                                "personal",
                                "u1",
                                "9007199254740993",
                                "ns",
                                java.util.List.of(
                                        new SlarkCatalogService.Item(
                                                "9223372036854775806",
                                                "candidate",
                                                "Name",
                                                "Summary",
                                                "9223372036854775807",
                                                "1.0.0")),
                                null));
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.items[0].skillId").value("9223372036854775806"))
                .andExpect(jsonPath("$.items[0].versionId").value("9223372036854775807"));
        verify(service)
                .list(
                        argThat(
                                r ->
                                        r.namespaceId().toString().equals("9007199254740993")
                                                && r.afterSkillId() == null
                                                && r.limit() == 20));
    }

    @Test
    void preparedOrUnavailableIsAnErrorNotAnEmptySuccess() throws Exception {
        when(service.list(any())).thenThrow(new SlarkCatalogService.Denied());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CATALOG_UNAVAILABLE"));
        doThrow(new IllegalStateException("private SQL credential")).when(service).list(any());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SLARK_UNAVAILABLE"));
    }
}
