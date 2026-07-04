package com.yanban.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_settings_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class UserSettingsControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SysUserSettingsRepository settingsRepository;

    @Autowired
    UserSettingsService userSettingsService;

    @Test
    void getSettingsDoesNotReturnPlaintextApiKey() throws Exception {
        String token = registerAndGetToken("settings_user_a");

        mockMvc.perform(put("/api/v1/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deepseekApiKey\":\"sk-test-secret\",\"maxSteps\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deepseekApiKeyConfigured").value(true));

        MvcResult result = mockMvc.perform(get("/api/v1/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deepseekApiKeyConfigured").value(true))
                .andExpect(jsonPath("$.maxSteps").value(5))
                .andExpect(jsonPath("$", not(hasKey("deepseekApiKey"))))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.toString()).doesNotContain("sk-test-secret");

        SysUserSettings settings = settingsRepository.findAll().get(0);
        assertThat(settings.getDeepseekApiKeyEncrypted()).isNotEqualTo("sk-test-secret");
        assertThat(userSettingsService.decryptDeepseekApiKey(settings)).isEqualTo("sk-test-secret");
    }

    @Test
    void createSessionUsesUserSettingsDefaults() throws Exception {
        String token = registerAndGetToken("settings_user_b");

        mockMvc.perform(put("/api/v1/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultProvider\":\"glm\",\"glmModel\":\"glm-4-flash\",\"glmApiKey\":\"glm-secret\",\"maxSteps\":5,\"ragDefaultEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.glmApiKeyConfigured").value(true));

        mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"来自设置默认值\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelProvider").value("glm"))
                .andExpect(jsonPath("$.model").value("glm-4-flash"))
                .andExpect(jsonPath("$.maxSteps").value(5))
                .andExpect(jsonPath("$.ragDisabled").value(true));
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
