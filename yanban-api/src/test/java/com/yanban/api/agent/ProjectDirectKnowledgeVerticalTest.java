package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.knowledge.service.KnowledgeSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project_direct_knowledge_vertical;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class ProjectDirectKnowledgeVerticalTest {
    private static final long DIRECT_PROJECT_ID = 39L;
    private static final long REACT_PROJECT_ID = 40L;
    private static final long FALLBACK_DIRECT_PROJECT_ID = 41L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired AgentMessageRepository messages;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "chatModelProvider") ChatModelProvider model;
    @MockBean ProjectService projects;
    @MockBean AdHocLiteratureSearchService literature;
    @MockBean KnowledgeSearchService knowledge;

    @BeforeEach
    void setUp() {
        when(projects.manifest(anyLong(), anyLong()))
                .thenAnswer(invocation -> new ProjectManifestResponse(
                        invocation.getArgument(1), "a".repeat(64), List.of()));
        when(model.providerName()).thenReturn("mock");
        when(model.chat(any())).thenAnswer(invocation -> answer(invocation.getArgument(0)));
    }

    @Test
    void routerDirectKnowledgeAnswerPersistsOneUserAndOneCanonicalAssistant() throws Exception {
        String token = register("project_direct_knowledge_user");
        insertProject(currentUserId(token), DIRECT_PROJECT_ID);
        long sessionId = createProjectSession(token, DIRECT_PROJECT_ID, "Direct knowledge");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages",
                        DIRECT_PROJECT_ID, sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"1+1等于多少？不要读取项目文件。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("2"))
                .andExpect(jsonPath("$.completionStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.messages.length()").value(2));

        List<AgentMessage> persisted = formalMessages(sessionId);
        assertThat(persisted).extracting(AgentMessage::getRole).containsExactly("user", "assistant");
        assertThat(persisted).extracting(AgentMessage::getContent)
                .containsExactly("1+1等于多少？不要读取项目文件。", "2");
    }

    @Test
    void malformedRouterFallsBackToDirectAndPersistsOneCanonicalAnswer() throws Exception {
        String token = register("project_fallback_direct_user");
        insertProject(currentUserId(token), FALLBACK_DIRECT_PROJECT_ID);
        long sessionId = createProjectSession(token, FALLBACK_DIRECT_PROJECT_ID, "Fallback direct");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages",
                        FALLBACK_DIRECT_PROJECT_ID, sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"What is 2+2?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("4"))
                .andExpect(jsonPath("$.completionStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.messages.length()").value(2));

        List<AgentMessage> persisted = formalMessages(sessionId);
        assertThat(persisted).extracting(AgentMessage::getRole).containsExactly("user", "assistant");
        assertThat(persisted).extracting(AgentMessage::getContent)
                .containsExactly("What is 2+2?", "4");
    }

    @Test
    void projectOperationConsultationDoesNotRequireProjectFileEvidence() throws Exception {
        String token = register("project_operation_consultation_user");
        insertProject(currentUserId(token), 42L);
        long sessionId = createProjectSession(token, 42L, "Operation consultation");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages",
                        42L, sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"如何在页面里刷新 Candidate 列表？不要读取项目文件。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.completionStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.projectEvidence.length()").value(0))
                .andExpect(jsonPath("$.messages.length()").value(2));

        assertThat(formalMessages(sessionId)).extracting(AgentMessage::getRole)
                .containsExactly("user", "assistant");
    }

    @Test
    void projectPlanClaimWithoutEvidenceStillPersistsOnlyTheCanonicalRejection() throws Exception {
        String token = register("project_react_evidence_user");
        insertProject(currentUserId(token), REACT_PROJECT_ID);
        long sessionId = createProjectSession(token, REACT_PROJECT_ID, "React evidence");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages",
                        REACT_PROJECT_ID, sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Read README and state what it says about this Project.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString(
                        "no current authorized Project file evidence")))
                .andExpect(jsonPath("$.messages.length()").value(2));

        List<AgentMessage> persisted = formalMessages(sessionId);
        assertThat(persisted).extracting(AgentMessage::getRole).containsExactly("user", "assistant");
        assertThat(persisted.get(1).getContent()).contains("no current authorized Project file evidence");
        assertThat(persisted).noneMatch(message -> "assistant".equals(message.getRole())
                && message.getContent() != null && message.getContent().contains("README says"));
    }

    private ChatResponse answer(ChatRequest request) {
        String joined = request.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
        boolean router = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .anyMatch(content -> content != null && content.contains("You route one user request"));
        boolean projectClaim = joined.contains("README");
        boolean planner = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .anyMatch(content -> content != null && content.contains("You are the planner for Yanban Agent"));
        if (router) {
            if (joined.contains("What is 2+2?")) {
                return new ChatResponse(ChatMessage.assistant("{malformed"), "stop", null);
            }
            String route = projectClaim
                    ? "{\"strategy\":\"PLAN_EXECUTE\",\"taskStructure\":\"MULTI_STEP_DEPENDENT\",\"requiresProjectTools\":true,\"requiresMultipleSteps\":true}"
                    : "{\"strategy\":\"DIRECT\",\"taskStructure\":\"KNOWLEDGE_ANSWER\",\"requiresProjectTools\":false,\"requiresMultipleSteps\":false}";
            return new ChatResponse(ChatMessage.assistant(route), "stop", null);
        }
        if (planner) {
            return new ChatResponse(ChatMessage.assistant("""
                    {"summary":"Read README","steps":[{"id":"read","title":"Read README",
                    "description":"Read README","type":"FILE_READ","dependencies":[],
                    "allowedTools":["project_read_file"],"successCriteria":"README observed"}]}
                    """), "stop", null);
        }
        String answer = projectClaim ? "README says this Project uses Java."
                : joined.contains("What is 2+2?") ? "4" : "2";
        return new ChatResponse(ChatMessage.assistant(answer), "stop", null);
    }

    private String register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private long createProjectSession(String token, long projectId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/agent/sessions", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"maxSteps\":6,\"ragDisabled\":true}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.path("id").asLong();
    }

    private long currentUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void insertProject(long userId, long projectId) {
        jdbc.update("INSERT INTO projects (id,user_id,name,root_type,root_path,canonical_root_path,access_mode,"
                        + "include_rules,ignore_rules,index_version) VALUES (?,?,'vertical-test','LOCAL',"
                        + "'vertical-test','vertical-test','READ_ONLY','[]','[]',?)",
                projectId, userId, "a".repeat(64));
    }

    private List<AgentMessage> formalMessages(long sessionId) {
        return messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(message -> "user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                .toList();
    }
}
