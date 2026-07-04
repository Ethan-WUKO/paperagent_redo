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

    @MockBean(name = "chatModelProvider")
    ChatModelProvider chatModelProvider;

    @MockBean
    AdHocLiteratureSearchService adHocLiteratureSearchService;

    @Test
    void userCanCreateListCorrectAndSoftDeleteOwnMemory() throws Exception {
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
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andReturn();
        long firstId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

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
                .andExpect(jsonPath("$.sourceType").value("USER_CORRECTED"))
                .andExpect(jsonPath("$.supersedesMemoryId").value(firstId))
                .andReturn();
        long correctedId = objectMapper.readTree(correctionResult.getResponse().getContentAsString()).get("id").asLong();

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

        mockMvc.perform(delete("/api/v1/settings/memory/{id}", memoryId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
