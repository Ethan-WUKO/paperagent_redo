package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentToolPolicyEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void stableCommonKnowledgeUsesNoTools() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u897f\u74dc\u7684\u529f\u6548", false, null);

        assertThat(decision.allowedTools()).isEmpty();
        assertThat(decision.reason()).isEqualTo("direct_answer_no_tools");
    }

    @Test
    void explicitSearchEnablesOneWebSearch() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u5e2e\u6211\u641c\u7d22\u4e00\u4e0b DeepSeek \u6700\u65b0\u6a21\u578b", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_web");
        assertThat(decision.maxToolCalls()).isEqualTo(1);
    }

    @Test
    void healthQuestionsUseOneWebSearchForAuthoritativeEvidence() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u767d\u8840\u75c5\u662f\u5982\u4f55\u5f62\u6210\u7684\uff1f", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_web");
        assertThat(decision.maxToolCalls()).isEqualTo(1);
        assertThat(decision.reason()).isEqualTo("health_or_medical");
    }

    @Test
    void literatureRequestUsesLiteratureSearch() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u627e 5 \u7bc7 RAG \u76f8\u5173\u8bba\u6587\uff0c\u5e26 DOI", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_literature");
    }

    @Test
    void knowledgeRequestUsesKnowledgeSearchWhenRagEnabled() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u6839\u636e\u6211\u4e0a\u4f20\u7684\u6587\u6863\u603b\u7ed3\u8981\u70b9", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_knowledge");
    }

    @Test
    void lookupTokenUsesKnowledgeSearchWhenRagEnabled() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("mentor_lookup_deepseek-20260702233836", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_knowledge");
        assertThat(decision.reason()).isEqualTo("knowledge_intent");
    }

    @Test
    void selectedSkillAllowlistWins() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("\u897f\u74dc\u7684\u529f\u6548", false, Set.of("search_web"));

        assertThat(decision.allowedTools()).containsExactly("search_web");
        assertThat(decision.reason()).isEqualTo("skill_allowlist");
    }

    private ToolRegistry registry() {
        return new ToolRegistry()
                .register(new StubTool("search_web"))
                .register(new StubTool("search_literature"))
                .register(new StubTool("search_knowledge"));
    }

    private class StubTool implements ToolExecutor {
        private final ToolDefinition definition;

        private StubTool(String name) {
            this.definition = new ToolDefinition(
                    name,
                    "stub " + name,
                    objectMapper.createObjectNode().put("type", "object")
            );
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
        }
    }
}
