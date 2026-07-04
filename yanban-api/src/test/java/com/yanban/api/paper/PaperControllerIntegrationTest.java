package com.yanban.api.paper;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskRoundRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_paper_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class PaperControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaperTaskRepository paperTaskRepository;

    @Autowired
    PaperTaskRoundRepository paperTaskRoundRepository;

    @MockBean
    MinioClient minioClient;

    @BeforeEach
    void setUp() throws Exception {
        when(minioClient.putObject(any())).thenReturn(null);
    }

    @Test
    void uploadLatexCreatesPendingTask() throws Exception {
        String token = registerAndGetToken("paper_user_a");

        MvcResult result = mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "main.tex",
                                "application/x-tex",
                                "\\documentclass{article}\\begin{document}Hi\\end{document}".getBytes()))
                        .file(new MockMultipartFile("bibFile", "refs.bib",
                                "text/x-bibtex",
                                "@article{a,title={A}}".getBytes()))
                        .param("scoreThreshold", "75")
                        .param("maxRounds", "3")
                        .param("innerMaxAttempts", "2")
                        .param("literatureCount", "5")
                        .param("targetLanguage", "zh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceFilename").value("main.tex"))
                .andExpect(jsonPath("$.objectKey", not(blankOrNullString())))
                .andReturn();

        Long taskId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        waitForRounds(taskId);
        var task = paperTaskRepository.findById(taskId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(task.getStatus()).isIn("PENDING", "RUNNING", "COMPLETED");
        org.assertj.core.api.Assertions.assertThat(task.getObjectKey()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(task.getTargetLanguage()).isEqualTo("zh");
        org.assertj.core.api.Assertions.assertThat(task.getInputFormat()).isEqualTo("LATEX");
        org.assertj.core.api.Assertions.assertThat(task.getMode()).isEqualTo("LATEX_BIB");
        org.assertj.core.api.Assertions.assertThat(paperTaskRoundRepository.findByTaskIdOrderByCreatedAtAsc(taskId)).isNotEmpty();

        mockMvc.perform(get("/api/v1/paper/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId));

        if (task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank()) {
            mockMvc.perform(get("/api/v1/paper/tasks/{taskId}/download", taskId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        }
    }

    @Test
    void rejectInvalidExtension() throws Exception {
        String token = registerAndGetToken("paper_user_b");

        mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "sample.pdf", "application/pdf", "pdf-content".getBytes()))
                        .param("scoreThreshold", "75")
                        .param("maxRounds", "3")
                        .param("innerMaxAttempts", "2")
                        .param("literatureCount", "5")
                        .param("targetLanguage", "zh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private void waitForRounds(Long taskId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (!paperTaskRoundRepository.findByTaskIdOrderByCreatedAtAsc(taskId).isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
