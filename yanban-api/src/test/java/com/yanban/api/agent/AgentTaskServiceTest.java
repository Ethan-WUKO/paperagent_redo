package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTask;
import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRepository;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskRepository;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AgentTaskServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 42L;

    private AgentTaskRepository agentTasks;
    private AgentTaskEventRepository taskEvents;
    private PaperTaskRepository paperTasks;
    private PaperTaskArtifactRepository paperArtifacts;
    private LiteratureSearchTaskRepository literatureTasks;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        agentTasks = mock(AgentTaskRepository.class);
        taskEvents = mock(AgentTaskEventRepository.class);
        paperTasks = mock(PaperTaskRepository.class);
        paperArtifacts = mock(PaperTaskArtifactRepository.class);
        literatureTasks = mock(LiteratureSearchTaskRepository.class);
        service = new AgentTaskService(agentTasks, taskEvents, paperTasks, paperArtifacts, literatureTasks);
    }

    @Test
    void getStatusUsesMirrorFirstAndEnrichesPaperArtifacts() {
        AgentTask mirror = new AgentTask(
                USER_ID,
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                AgentTaskRegistry.SOURCE_PAPER_TASK,
                TASK_ID,
                "CANCELLED"
        );
        mirror.setCurrentStage("CANCELLED");
        mirror.setStartedAt(Instant.parse("2026-07-05T00:00:00Z"));
        mirror.setFinishedAt(Instant.parse("2026-07-05T00:05:00Z"));
        when(agentTasks.findByTaskTypeAndSourceAndSourceIdAndUserId(
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                AgentTaskRegistry.SOURCE_PAPER_TASK,
                TASK_ID,
                USER_ID
        )).thenReturn(Optional.of(mirror));
        when(paperTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(paperTask("CANCELLED", "CANCELLED")));
        when(paperArtifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of(
                artifact(PaperTaskArtifact.STATUS_PARTIAL),
                artifact(PaperTaskArtifact.STATUS_COMPLETED)
        ));
        AgentTaskEvent latest = new AgentTaskEvent(
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                TASK_ID,
                USER_ID,
                "TASK_CANCELLED",
                "CANCELLED",
                "CANCELLED",
                "paper task cancelled",
                null
        );
        ReflectionTestUtils.setField(latest, "id", 900L);
        ReflectionTestUtils.setField(latest, "createdAt", Instant.parse("2026-07-05T00:05:01Z"));
        when(taskEvents.findTopByTaskTypeAndTaskIdAndUserIdOrderByIdDesc(
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                TASK_ID,
                USER_ID
        )).thenReturn(Optional.of(latest));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "paper_polish");

        assertThat(response.taskType()).isEqualTo("paper_polish");
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.startedAt()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
        assertThat(response.finishedAt()).isEqualTo(Instant.parse("2026-07-05T00:05:00Z"));
        assertThat(response.partialResultAvailable()).isTrue();
        assertThat(response.completedArtifactCount()).isEqualTo(1);
        assertThat(response.partialArtifactCount()).isEqualTo(1);
        assertThat(response.lastEventId()).isEqualTo(900L);
        assertThat(response.lastEventType()).isEqualTo("TASK_CANCELLED");
        assertThat(response.lastEventMessage()).isEqualTo("paper task cancelled");
        assertThat(response.lastEventAt()).isEqualTo(Instant.parse("2026-07-05T00:05:01Z"));
    }

    @Test
    void getStatusFallsBackToMirrorWhenLegacyRowMissing() {
        AgentTask mirror = new AgentTask(
                USER_ID,
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                TASK_ID,
                "CANCELLING"
        );
        mirror.setCurrentStage("SEARCHING");
        mirror.setCancellationReason("manual stop");
        when(agentTasks.findByTaskTypeAndSourceAndSourceIdAndUserId(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                TASK_ID,
                USER_ID
        )).thenReturn(Optional.of(mirror));
        when(literatureTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "literature_search");

        assertThat(response.taskType()).isEqualTo("literature_search");
        assertThat(response.status()).isEqualTo("CANCELLING");
        assertThat(response.currentStage()).isEqualTo("SEARCHING");
        assertThat(response.cancellationReason()).isEqualTo("manual stop");
        assertThat(response.cancellable()).isFalse();
        assertThat(response.terminal()).isFalse();
    }

    @Test
    void getStatusAutoDetectRejectsAmbiguousMirrorMatch() {
        AgentTask paperMirror = new AgentTask(
                USER_ID,
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                AgentTaskRegistry.SOURCE_PAPER_TASK,
                TASK_ID,
                "RUNNING"
        );
        AgentTask literatureMirror = new AgentTask(
                USER_ID,
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                TASK_ID,
                "RUNNING"
        );
        when(agentTasks.findBySourceIdAndUserIdAndTaskTypeIn(
                TASK_ID,
                USER_ID,
                List.of(
                        AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                        AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH
                )
        )).thenReturn(List.of(paperMirror, literatureMirror));

        assertThatThrownBy(() -> service.getStatus(USER_ID, TASK_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("please specify taskType");
    }

    @Test
    void getStatusFallsBackToLegacyWhenMirrorMissing() {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                9L,
                "query",
                "query",
                5,
                null,
                true,
                "COMPLETED",
                "COMPLETE",
                "req-1",
                "idem-1"
        );
        task.setStartedAt(Instant.parse("2026-07-05T01:00:00Z"));
        task.setFinishedAt(Instant.parse("2026-07-05T01:02:00Z"));
        task.setCancelReason("none");
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        when(agentTasks.findByTaskTypeAndSourceAndSourceIdAndUserId(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                TASK_ID,
                USER_ID
        )).thenReturn(Optional.empty());
        when(literatureTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "literature_search");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.progressPercent()).isEqualTo(100);
        assertThat(response.startedAt()).isEqualTo(Instant.parse("2026-07-05T01:00:00Z"));
        assertThat(response.finishedAt()).isEqualTo(Instant.parse("2026-07-05T01:02:00Z"));
    }

    @Test
    void getStatusMarksLiteratureResultPartialWhenSourceFailuresExist() {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                9L,
                "query",
                "query",
                5,
                null,
                true,
                "COMPLETED",
                "COMPLETE",
                "req-1",
                "idem-1"
        );
        task.setResultJson("{\"items\":[{\"title\":\"A\"}]}");
        task.setSourceFailuresJson("[\"openalex: timeout\"]");
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        when(agentTasks.findByTaskTypeAndSourceAndSourceIdAndUserId(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                TASK_ID,
                USER_ID
        )).thenReturn(Optional.empty());
        when(literatureTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "literature_search");

        assertThat(response.partialResultAvailable()).isTrue();
    }

    private PaperTask paperTask(String status, String stage) {
        PaperTask task = new PaperTask(USER_ID, "paper", "paper.tex", "paper/object.tex", status, "zh", stage, null);
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }

    private PaperTaskArtifact artifact(String status) {
        PaperTaskArtifact artifact = new PaperTaskArtifact(TASK_ID, "polished_tex", "paper/polished.tex", 1);
        artifact.setArtifactStatus(status);
        return artifact;
    }
}
