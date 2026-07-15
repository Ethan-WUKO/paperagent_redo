package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:long_term_memory_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class LongTermMemoryControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LongTermMemoryRetrievalService retrievalService;

    @MockBean(name = "chatModelProvider")
    ChatModelProvider chatModelProvider;

    @MockBean
    AdHocLiteratureSearchService adHocLiteratureSearchService;

    @Test
    void userCanCreateConfirmExpireCorrectAndSoftDeleteOwnMemory() throws Exception {
        String token = registerAndGetToken("memory_user_crud");

        MvcResult createResult = mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scope":"USER",
                                  "memoryType":"PREFERENCE",
                                  "content":"User prefers concise academic prose.",
                                  "tags":["style","style","writing"],
                                  "confidence":0.85
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmationStatus").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").doesNotExist())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andReturn();
        long firstId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();
        long userId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("userId").asLong();

        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", firstId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedSource").value("USER_ACTION"))
                .andExpect(jsonPath("$.provenanceType").value("USER_SETTINGS_ACTION"))
                .andExpect(jsonPath("$.provenanceRef").value("memory-settings:" + firstId + ":confirm"));

        AgentLongTermMemoryContext memoryContext = retrievalService.retrieve(userId, "academic prose");
        assertThat(memoryContext.hasContent()).isTrue();
        assertThat(memoryContext.content()).contains("User prefers concise academic prose.");

        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", firstId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceRef").value("memory-settings:" + firstId + ":confirm"));

        mockMvc.perform(put("/api/v1/settings/memory/{id}/expiry", firstId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2099-01-01T00:00:00Z"));

        mockMvc.perform(put("/api/v1/settings/memory/{id}/expiry", firstId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        mockMvc.perform(get("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("User prefers concise academic prose."));

        MvcResult correctionResult = mockMvc.perform(put("/api/v1/settings/memory/{id}", firstId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scope":"USER",
                                  "memoryType":"PREFERENCE",
                                  "content":"User prefers detailed academic prose with explicit caveats.",
                                  "tags":["style","writing"],
                                  "confidence":0.9
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.sourceType").value("USER_CORRECTED"))
                .andExpect(jsonPath("$.provenanceType").value("USER_SETTINGS_ACTION"))
                .andExpect(jsonPath("$.supersedesMemoryId").value(firstId))
                .andReturn();
        long correctedId = objectMapper.readTree(correctionResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/v1/settings/memory/{id}", firstId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        MvcResult allResult = mockMvc.perform(get("/api/v1/settings/memory?status=ALL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        JsonNode all = objectMapper.readTree(allResult.getResponse().getContentAsString());
        assertThat(all.toString()).contains("SUPERSEDED", "ACTIVE", "detailed academic prose");

        mockMvc.perform(put("/api/v1/settings/memory/{id}", firstId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Old version should stay historical.\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/settings/memory/{id}", correctedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", correctedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", correctedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/settings/memory/{id}/expiry", correctedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":null}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/settings/memory/{id}", correctedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/settings/memory/{id}", correctedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());
    }

    @Test
    void rejectIsIdempotentAndCannotOverrideConfirmedMemory() throws Exception {
        String token = registerAndGetToken("memory_rejection_state");
        long rejectedId = createMemory(token, "Unconfirmed memory.");

        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", rejectedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("REJECTED"));
        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", rejectedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("REJECTED"));
        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", rejectedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        MvcResult correction = mockMvc.perform(put("/api/v1/settings/memory/{id}", rejectedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Corrected after explicit rejection.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.supersedesMemoryId").value(rejectedId))
                .andReturn();
        long correctedId = objectMapper.readTree(correction.getResponse().getContentAsString()).get("id").asLong();
        assertThat(correctedId).isNotEqualTo(rejectedId);
        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", rejectedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        long confirmedId = createMemory(token, "Confirmed memory.");
        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", confirmedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", confirmedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidScopeExpiryAndClientGovernanceOverridesFailClosed() throws Exception {
        String token = registerAndGetToken("memory_fail_closed");

        mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"USER\",\"projectId\":7,\"content\":\"Invalid identity.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PROJECT\",\"content\":\"Missing project.\"}"))
                .andExpect(status().isBadRequest());

        MvcResult result = mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"USER","content":"Safe user preference.",
                                 "sourceType":"MODEL_INFERRED","sourceRefId":"client-forged"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("USER_CONFIRMED"))
                .andExpect(jsonPath("$.sourceRefId").doesNotExist())
                .andReturn();
        long memoryId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/api/v1/settings/memory/{id}/expiry", memoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"password=do-not-store\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userCannotReadCorrectOrDeleteAnotherUsersMemory() throws Exception {
        String tokenA = registerAndGetToken("memory_user_owner");
        String tokenB = registerAndGetToken("memory_user_other");

        MvcResult createResult = mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Owner studies GraphRAG.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long memoryId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/v1/settings/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/settings/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Other user tries to edit.\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/settings/memory/{id}/confirm", memoryId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/settings/memory/{id}/reject", memoryId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/settings/memory/{id}/expiry", memoryId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":null}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/settings/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private long createMemory(String token, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
