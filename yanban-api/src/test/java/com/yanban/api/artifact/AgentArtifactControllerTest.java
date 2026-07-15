package com.yanban.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.security.JwtUser;
import org.junit.jupiter.api.Test;

class AgentArtifactControllerTest {

    @Test
    void candidateEndpointReturnsAuthoritativeMultiFileProjection() {
        AgentArtifactService artifacts = mock(AgentArtifactService.class);
        CandidateChangeArtifactService candidates = mock(CandidateChangeArtifactService.class);
        CandidateArtifactResponse response = mock(CandidateArtifactResponse.class);
        when(candidates.getCurrent(7L, 9L)).thenReturn(response);
        AgentArtifactController controller = new AgentArtifactController(artifacts, candidates);

        assertThat(controller.getCandidate(new JwtUser(7L, "user"), 9L)).isSameAs(response);
        verify(candidates).getCurrent(7L, 9L);
    }
}
