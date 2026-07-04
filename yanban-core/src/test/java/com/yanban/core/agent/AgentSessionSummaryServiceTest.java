package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSessionSummaryServiceTest {

    @Mock
    private AgentSessionSummaryRepository summaries;

    private AgentSessionSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AgentSessionSummaryService(summaries);
    }

    @Test
    void findReturnsEmptyWhenSessionOrUserMissing() {
        assertThat(service.findBySessionAndUser(null, 1001L)).isEmpty();
        assertThat(service.findBySessionAndUser(1L, null)).isEmpty();
    }

    @Test
    void upsertCreatesSummaryWhenMissing() {
        AgentSessionSummaryUpdate update = new AgentSessionSummaryUpdate(
                10L,
                1001L,
                "User studies RAG.",
                20L,
                3,
                "deepseek",
                "deepseek-chat"
        );
        when(summaries.findBySessionIdAndUserId(10L, 1001L)).thenReturn(Optional.empty());
        when(summaries.saveAndFlush(org.mockito.ArgumentMatchers.any(AgentSessionSummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AgentSessionSummary saved = service.upsert(update);

        assertThat(saved.getSessionId()).isEqualTo(10L);
        assertThat(saved.getUserId()).isEqualTo(1001L);
        assertThat(saved.getSummaryText()).isEqualTo("User studies RAG.");
        assertThat(saved.getCoveredMessageId()).isEqualTo(20L);
    }

    @Test
    void upsertUpdatesExistingSummary() {
        AgentSessionSummary existing = new AgentSessionSummary(
                10L,
                1001L,
                "old",
                20L,
                1,
                "deepseek",
                "deepseek-chat"
        );
        when(summaries.findBySessionIdAndUserId(10L, 1001L)).thenReturn(Optional.of(existing));
        when(summaries.saveAndFlush(existing)).thenReturn(existing);

        AgentSessionSummary saved = service.upsert(new AgentSessionSummaryUpdate(
                10L,
                1001L,
                "new summary",
                30L,
                4,
                "glm",
                "glm-4"
        ));

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getSummaryText()).isEqualTo("new summary");
        assertThat(saved.getCoveredMessageId()).isEqualTo(30L);
        assertThat(saved.getMessageCount()).isEqualTo(4);
        assertThat(saved.getModelProviderSnapshot()).isEqualTo("glm");
    }

    @Test
    void deleteBySessionIgnoresNullAndDeletesKnownSession() {
        service.deleteBySession(null);
        service.deleteBySession(10L);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(summaries).deleteBySessionId(captor.capture());
        assertThat(captor.getValue()).isEqualTo(10L);
    }
}
