package com.yanban.api.agent;

import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskRepository;
import io.minio.MinioClient;
import java.util.Map;
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
        "spring.datasource.url=jdbc:h2:mem:yanban_task_control_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class TaskControlControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaperTaskRepository paperTaskRepository;

    @Autowired
    LiteratureSearchTaskRepository literatureTaskRepository;

    @Autowired
    SysUserRepository sysUserRepository;

    @MockBean
    MinioClient minioClient;

    @Test
    void cancelPaperTaskThroughUnifiedEndpoint() throws Exception {
        String username = "task_control_owner_paper";
        String token = registerAndGetToken(username);
        Long userId = sysUserRepository.findByUsername(username).orElseThrow().getId();
        PaperTask paperTask = createPaperTask(userId, "RUNNING", "POLISH");
        Long taskId = paperTask.getId();

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskType").value("paper_polish"))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.cancelAccepted").value(true))
                .andExpect(jsonPath("$.beforeStatus").value("RUNNING"))
                .andExpect(jsonPath("$.afterStatus").value(oneOf("CANCEL_REQUESTED", "CANCELLED")));
    }

    @Test
    void cancelLiteratureTaskThroughUnifiedEndpointWithTypeHint() throws Exception {
        String username = "task_control_owner_literature";
        String token = registerAndGetToken(username);
        Long userId = sysUserRepository.findByUsername(username).orElseThrow().getId();
        LiteratureSearchTask literatureTask = createLiteratureTask(userId, "RUNNING", "SEARCHING");
        Long taskId = literatureTask.getId();

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskCancelRequest("literature_search", "stop")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskType").value("literature_search"))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.cancelAccepted").value(true))
                .andExpect(jsonPath("$.beforeStatus").value("RUNNING"))
                .andExpect(jsonPath("$.afterStatus").value("CANCEL_REQUESTED"));
    }

    @Test
    void queryPaperTaskStatusThroughUnifiedEndpointByTypeHint() throws Exception {
        String username = "task_control_owner_status_paper";
        String token = registerAndGetToken(username);
        Long userId = sysUserRepository.findByUsername(username).orElseThrow().getId();
        PaperTask paperTask = createPaperTask(userId, "RUNNING", "POLISH");
        Long taskId = paperTask.getId();

        mockMvc.perform(get("/api/v1/tasks/{taskId}/status", taskId)
                        .header("Authorization", "Bearer " + token)
                        .queryParam("taskType", "paper_polish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("paper_polish"))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.terminal").value(false));
    }

    @Test
    void queryLiteratureTaskStatusAutoDetectThroughUnifiedEndpoint() throws Exception {
        String username = "task_control_owner_status_literature";
        String token = registerAndGetToken(username);
        Long userId = sysUserRepository.findByUsername(username).orElseThrow().getId();
        LiteratureSearchTask literatureTask = createLiteratureTask(userId, "CANCELLED", "CANCELLED");
        Long taskId = literatureTask.getId();

        mockMvc.perform(get("/api/v1/tasks/{taskId}/status", taskId)
                        .header("Authorization", "Bearer " + token)
                        .queryParam("taskType", "literature_search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("literature_search"))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.terminal").value(true));
    }

    private PaperTask createPaperTask(Long userId, String status, String stage) {
        PaperTask task = new PaperTask(
                userId,
                "paper",
                "main.tex",
                "paper/main.tex",
                status,
                "zh",
                stage,
                null
        );
        return paperTaskRepository.save(task);
    }

    private LiteratureSearchTask createLiteratureTask(Long userId, String status, String stage) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                userId,
                null,
                "query",
                "query",
                8,
                null,
                true,
                status,
                stage,
                "req-1",
                "idem-" + userId
        );
        return literatureTaskRepository.save(task);
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
