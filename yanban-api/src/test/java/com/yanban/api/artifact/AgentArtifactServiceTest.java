package com.yanban.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentArtifact;
import com.yanban.core.agent.AgentArtifactRepository;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.KnowledgeIngestionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentArtifactServiceTest {
    private final AgentArtifactRepository artifacts = mock(AgentArtifactRepository.class);
    private final AgentArtifactService service = new AgentArtifactService(artifacts,
            mock(KnowledgeIngestionService.class), mock(KbDocumentRepository.class), new ObjectMapper());

    @BeforeEach
    void returnPersistedArtifact() {
        when(artifacts.saveAndFlush(any(AgentArtifact.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void publicCreateRejectsReservedCandidateSourceTypeBeforePersistence() {
        CreateArtifactRequest forged = new CreateArtifactRequest(3L, "forged.json", AgentArtifact.TYPE_TEXT,
                "{\"schemaVersion\":\"YANBAN_CANDIDATE_ARTIFACT_V1\"}",
                " candidate_changeset ", List.of());

        assertThatThrownBy(() -> service.createArtifact(7L, forged))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(artifacts, never()).saveAndFlush(any(AgentArtifact.class));
    }

    @Test
    void internalCandidateCreateUsesReservedTypeAndTextProjection() {
        service.createCandidateArtifact(7L, 3L, "candidate.json", "validated-envelope");

        ArgumentCaptor<AgentArtifact> saved = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(artifacts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getSessionId()).isEqualTo(3L);
        assertThat(saved.getValue().getArtifactType()).isEqualTo(AgentArtifact.TYPE_TEXT);
        assertThat(saved.getValue().getSourceType())
                .isEqualTo(AgentArtifactService.CANDIDATE_CHANGESET_SOURCE_TYPE);
        assertThat(saved.getValue().getContent()).isEqualTo("validated-envelope");
    }

    @Test
    void publicCreateKeepsOrdinaryArtifactBehavior() {
        ArtifactResponse response = service.createArtifact(7L, new CreateArtifactRequest(
                3L, "notes.txt", AgentArtifact.TYPE_TEXT, "ordinary", " agent_tool ", List.of()));

        assertThat(response.sourceType()).isEqualTo(AgentArtifact.SOURCE_AGENT_TOOL);
        assertThat(response.artifactType()).isEqualTo(AgentArtifact.TYPE_TEXT);
        assertThat(response.content()).isEqualTo("ordinary");
        verify(artifacts).saveAndFlush(any(AgentArtifact.class));
    }
}
