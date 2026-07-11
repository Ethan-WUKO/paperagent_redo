package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentMemoryExperimentServiceTest {

    @Test
    void contextPackerDelegatesToDefaultContextBuilder() {
        AgentContextBuilder contextBuilder = mock(AgentContextBuilder.class);
        AgentContextPackage contextPackage = new AgentContextPackage(
                List.of(ChatMessage.system("summary"), ChatMessage.user("hello")),
                List.of(),
                List.of(),
                2,
                2,
                40
        );
        when(contextBuilder.build(any(AgentContextBuildRequest.class))).thenReturn(contextPackage);
        AgentMemoryExperimentService service = new AgentMemoryExperimentService(contextBuilder);

        AgentMemoryExperimentResult result = service.buildContext(disabledExperiment(), request());

        assertThat(result.contextPackage()).isSameAs(contextPackage);
        assertThat(result.memoryWindow()).isNull();
        verify(contextBuilder).build(any(AgentContextBuildRequest.class));
    }

    @Test
    void contextPackerExposesFinalContextWhenDebugFlagIsEnabled() {
        AgentContextBuilder contextBuilder = mock(AgentContextBuilder.class);
        AgentContextPackage contextPackage = new AgentContextPackage(
                List.of(ChatMessage.system("Session summary:\nproject=orion"), ChatMessage.user("continue")),
                List.of(),
                List.of(),
                2,
                2,
                40
        );
        when(contextBuilder.build(any(AgentContextBuildRequest.class))).thenReturn(contextPackage);
        AgentMemoryExperimentService service = new AgentMemoryExperimentService(contextBuilder);

        AgentMemoryExperimentResult result = service.buildContext(enabledExperiment(), request());

        assertThat(result.contextPackage()).isSameAs(contextPackage);
        assertThat(result.memoryWindow()).isNotNull();
        assertThat(result.memoryWindow().mode()).isEqualTo(AgentMemoryMode.CONTEXT_PACKER);
        assertThat(result.memoryWindow().entries()).anyMatch(entry -> entry.contains("project=orion"));
        verify(contextBuilder).build(any(AgentContextBuildRequest.class));
    }

    private AgentExperimentContext disabledExperiment() {
        return new AgentExperimentContext(null, new AgentSelectedModesDebug(
                AgentRuntimeMode.LANGCHAIN4J,
                AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                AgentMemoryMode.CONTEXT_PACKER,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING
        ), null);
    }

    private AgentExperimentContext enabledExperiment() {
        return new AgentExperimentContext(new AgentExperimentRequest(
                true,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                AgentMemoryMode.CONTEXT_PACKER,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of(AgentDebugFlag.SHOW_MEMORY_WINDOW),
                false
        ), new AgentSelectedModesDebug(
                AgentRuntimeMode.LANGCHAIN4J,
                AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                AgentMemoryMode.CONTEXT_PACKER,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING
        ), null);
    }

    private AgentContextBuildRequest request() {
        return new AgentContextBuildRequest(
                12L,
                3L,
                "deepseek",
                "deepseek-v4-flash",
                "session summary",
                AgentLongTermMemoryContext.empty(),
                "rag context",
                null,
                12,
                2000
        );
    }
}
