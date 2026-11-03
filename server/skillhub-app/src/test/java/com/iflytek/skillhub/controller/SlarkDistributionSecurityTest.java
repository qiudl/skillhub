package com.iflytek.skillhub.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.service.SlarkDistributionService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

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
class SlarkDistributionSecurityTest {
    static final String PATH = "/api/v1/internal/slark/distribution/read",
            TOKEN = "Bearer " + "d".repeat(40);
    static final String BODY =
            "{\"scope\":{\"kind\":\"personal\",\"ownerId\":\"u1\"},\"namespaceId\":\"9007199254740993\",\"skillId\":\"9223372036854775806\",\"versionId\":\"9223372036854775807\",\"subject\":\"u1\"}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SlarkDistributionService service;

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
    void exactStringIdsAndNoStoreSnapshotAreReturned() throws Exception {
        var snapshot =
                new SlarkDistributionService.Snapshot(
                        "registry",
                        "personal",
                        "u1",
                        "9007199254740993",
                        "ns",
                        "9223372036854775806",
                        "9223372036854775807",
                        "1.0.0",
                        "my-skill",
                        "u1",
                        "PRIVATE",
                        "ACTIVE",
                        "ACTIVE",
                        false,
                        "UPLOADED",
                        "current",
                        "SAFE",
                        "a".repeat(64));
        when(service.read(any()))
                .thenReturn(new SlarkDistributionService.Export(new byte[] {1, 2}, snapshot));
        var response =
                mvc.perform(
                                post(PATH)
                                        .header("Authorization", TOKEN)
                                        .contentType("application/json")
                                        .content(BODY))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().contentType("application/zip"))
                        .andReturn()
                        .getResponse();
        var value =
                json.readTree(
                        Base64.getUrlDecoder()
                                .decode(response.getHeader("X-Slark-Distribution-Snapshot")));
        assertThat(value.get("versionId").isTextual()).isTrue();
        assertThat(value.get("versionId").asText()).isEqualTo("9223372036854775807");
        assertThat(response.getContentAsByteArray()).containsExactly(1, 2);
    }

    @Test
    void duplicateUnknownAndLossyIdsNeverReachTheService() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    BODY + " {}",
                    BODY.replace("{\"scope\"", "{\"subject\":\"u2\",\"scope\""),
                    BODY.replace("\"9223372036854775807\"", "9223372036854775807"),
                    BODY.replace("\"9007199254740993\"", "\"01\""),
                    BODY.replace("\"u1\"}", "\"u1\",\"admin\":true}"),
                    BODY.replace("\"personal\"", "\"unknown\"")
                })
            mvc.perform(
                            post(PATH)
                                    .header("Authorization", TOKEN)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void deniedAndDependencyErrorsDoNotExposeInternals() throws Exception {
        doThrow(new SlarkDistributionService.Denied()).when(service).read(any());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"DISTRIBUTION_UNAVAILABLE\"}"));
        doThrow(new IllegalStateException("private SQL/key")).when(service).read(any());
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"SLARK_UNAVAILABLE\"}"));
    }

    @Test
    void oversizeWrongMethodsAndAdjacentPathsHaveNoPrivilegedChain() throws Exception {
        mvc.perform(
                        post(PATH)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content("x".repeat(5000)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(PATH).header("Authorization", TOKEN))
                .andExpect(status().is4xxClientError());
        mvc.perform(
                        post(PATH + "/other")
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
}
