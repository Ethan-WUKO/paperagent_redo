package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryRetrievalServiceTest {

    private static final long USER_ID = 42L;

    @Mock
    private AgentLongTermMemoryRepository memories;

    private LongTermMemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryRetrievalService(memories, new ObjectMapper());
    }

    @Test
    void retrievesRelevantMemoryByKeywordAndTag() {
        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(eq(USER_ID), eq(AgentLongTermMemory.STATUS_ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "User prefers GraphRAG answers with explicit ablation caveats.", "[\"GraphRAG\",\"style\"]", "0.86"),
                        memory("PREFERENCE", "User studies compiler optimization.", "[\"systems\"]", "0.90")
                ));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "Please help with GraphRAG evaluation.");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("GraphRAG", "ablation caveats");
        assertThat(context.content()).doesNotContain("compiler optimization");
        assertThat(context.note()).contains("hits=1", "candidates=2", "GraphRAG");
    }

    @Test
    void returnsEmptyWhenNoMemoryMatchesQuery() {
        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(eq(USER_ID), eq(AgentLongTermMemory.STATUS_ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(memory("FACT", "User studies reinforcement learning.", "[\"RL\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "Discuss literature review structure.");

        assertThat(context.hasContent()).isFalse();
        assertThat(context.hitCount()).isZero();
        assertThat(context.note()).contains("No relevant long-term memory");
    }

    @Test
    void filtersDeletedSupersededAndLowConfidenceMemories() {
        AgentLongTermMemory deleted = memory("FACT", "Deleted GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.90");
        deleted.markDeleted();
        AgentLongTermMemory superseded = memory("FACT", "Superseded GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.90");
        superseded.markSuperseded(99L);
        AgentLongTermMemory lowConfidence = memory("FACT", "Low confidence GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.10");
        AgentLongTermMemory active = memory("FACT", "Active GraphRAG memory should appear.", "[\"GraphRAG\"]", "0.70");

        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(eq(USER_ID), eq(AgentLongTermMemory.STATUS_ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(deleted, superseded, lowConfidence, active));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Active GraphRAG memory");
        assertThat(context.content()).doesNotContain("Deleted", "Superseded", "Low confidence");
    }

    @Test
    void reportsBudgetOmissions() {
        List<AgentLongTermMemory> rows = List.of(
                memory("FACT", repeated("GraphRAG memory A "), "[\"GraphRAG\"]", "0.90"),
                memory("FACT", repeated("GraphRAG memory B "), "[\"GraphRAG\"]", "0.89"),
                memory("FACT", repeated("GraphRAG memory C "), "[\"GraphRAG\"]", "0.88"),
                memory("FACT", repeated("GraphRAG memory D "), "[\"GraphRAG\"]", "0.87"),
                memory("FACT", repeated("GraphRAG memory E "), "[\"GraphRAG\"]", "0.86")
        );
        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(eq(USER_ID), eq(AgentLongTermMemory.STATUS_ACTIVE), any(Pageable.class)))
                .thenReturn(rows);

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isLessThan(5);
        assertThat(context.omittedCount()).isGreaterThan(0);
        assertThat(context.note()).contains("omitted=");
    }

    private AgentLongTermMemory memory(String type, String content, String tagsJson, String confidence) {
        return new AgentLongTermMemory(
                USER_ID,
                null,
                AgentLongTermMemory.SCOPE_USER,
                type,
                content,
                tagsJson,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null,
                new BigDecimal(confidence),
                null
        );
    }

    private String repeated(String value) {
        return value.repeat(40);
    }
}
