package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentExperimentRecord;
import com.yanban.core.agent.AgentExperimentRecordRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentExperimentRecordServiceTest {

    @Test
    void persistsEvalRecordWhenEnabled() throws Exception {
        AgentExperimentRecordRepository repository = mock(AgentExperimentRecordRepository.class);
        AgentExperimentRecord saved = new AgentExperimentRecord(
                2L, 7L, "req-1",
                "LANGCHAIN4J", "LANGCHAIN4J_AUGMENTOR", "CONTEXT_PACKER", "LANGCHAIN4J_TOOL_BINDING",
                true, 321L, 2, 3, 1,
                "[]", "[]", "[]", "[]", "[]",
                null, null, "answer"
        );
        Field idField = AgentExperimentRecord.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(saved, 99L);
        when(repository.saveAndFlush(any(AgentExperimentRecord.class))).thenReturn(saved);
        AgentExperimentRecordService service = new AgentExperimentRecordService(repository, new ObjectMapper());

        Long recordId = service.persistIfEnabled(
                7L,
                2L,
                "req-1",
                experimentContext(true),
                debugPayload(),
                "See cite-1 and cite-2 for details.",
                true,
                null,
                321L
        );

        assertThat(recordId).isEqualTo(99L);
        verify(repository).saveAndFlush(any(AgentExperimentRecord.class));
    }

    @Test
    void skipsPersistenceWhenDisabled() {
        AgentExperimentRecordRepository repository = mock(AgentExperimentRecordRepository.class);
        AgentExperimentRecordService service = new AgentExperimentRecordService(repository, new ObjectMapper());

        Long recordId = service.persistIfEnabled(
                7L,
                2L,
                "req-1",
                experimentContext(false),
                debugPayload(),
                "answer",
                true,
                null,
                12L
        );

        assertThat(recordId).isNull();
        verify(repository, never()).saveAndFlush(any());
    }

    private AgentExperimentContext experimentContext(boolean persist) {
        return new AgentExperimentContext(new AgentExperimentRequest(
                true,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                AgentMemoryMode.CONTEXT_PACKER,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of(AgentDebugFlag.SHOW_TOOL_TRACE),
                persist
        ), new AgentSelectedModesDebug(
                AgentRuntimeMode.LANGCHAIN4J,
                AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                AgentMemoryMode.CONTEXT_PACKER,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING
        ), null);
    }

    private AgentDebugPayload debugPayload() {
        return new AgentDebugPayload(
                new AgentSelectedModesDebug(
                        AgentRuntimeMode.LANGCHAIN4J,
                        AgentRagMode.LANGCHAIN4J_AUGMENTOR,
                        AgentMemoryMode.CONTEXT_PACKER,
                        AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING
                ),
                List.of(
                        new AgentRetrievedChunkDebug("kb", 1L, "note-a.md", 0, "cite-1", 1.2, "content-a"),
                        new AgentRetrievedChunkDebug("kb", 2L, "note-b.md", 1, "cite-2", 1.1, "content-b")
                ),
                "context",
                "raw prompt",
                List.of("SHOW_TOOL_TRACE"),
                List.of("step=1 tool=search_web"),
                List.of("cite-1", "cite-2"),
                new AgentExperimentMetricsDebug("req-1", 2L, 321L, 2, 3, 11, 22, 33, 1, 2, null),
                new AgentMemoryWindowDebug(AgentMemoryMode.CONTEXT_PACKER, List.of("1. user: hi")),
                List.of(),
                new ModelSourceDebug("deepseek", "deepseek-chat", "builtin", "DeepSeek")
        );
    }
}
