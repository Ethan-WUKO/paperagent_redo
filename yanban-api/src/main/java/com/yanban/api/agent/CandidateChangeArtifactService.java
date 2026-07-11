package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.api.artifact.CreateArtifactRequest;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentArtifact;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persists proposal-only candidates in the existing artifact table and revalidates on read. */
@Service
public class CandidateChangeArtifactService {
    public static final String SOURCE_TYPE = "CANDIDATE_CHANGESET";
    private final AgentArtifactService artifacts;
    private final ProjectService projects;
    private final ObjectMapper objectMapper;

    public CandidateChangeArtifactService(AgentArtifactService artifacts, ProjectService projects, ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.projects = projects;
        this.objectMapper = objectMapper;
    }

    public CandidateChangeSet store(Long userId, Long sessionId, CandidateChangeSet candidate) {
        try {
            ArtifactResponse artifact = artifacts.createArtifact(userId, new CreateArtifactRequest(sessionId,
                    "candidate-" + candidate.relativePath().replace('/', '_') + ".md", AgentArtifact.TYPE_MARKDOWN,
                    objectMapper.writeValueAsString(candidate), SOURCE_TYPE,
                    List.of()));
            return candidate.withArtifactId(artifact.id());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist candidate change artifact", ex);
        }
    }

    public CandidateChangeSet getCurrent(Long userId, Long artifactId) {
        ArtifactResponse artifact = artifacts.getArtifact(userId, artifactId);
        if (!SOURCE_TYPE.equals(artifact.sourceType())) throw new IllegalArgumentException("artifact is not a candidate change set");
        CandidateChangeSet candidate;
        try {
            candidate = objectMapper.readValue(artifact.content(), CandidateChangeSet.class).withArtifactId(artifact.id());
        } catch (Exception ex) {
            throw new IllegalArgumentException("candidate artifact content is invalid", ex);
        }
        try {
            ProjectManifestResponse manifest = projects.manifest(userId, candidate.projectId());
            boolean current = manifest.files().stream().anyMatch(file -> file.path().equals(candidate.relativePath())
                    && file.sha256().equals(candidate.baseVersion()));
            return withStatus(candidate, current ? CandidateChangeStatus.CANDIDATE : CandidateChangeStatus.STALE);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return withStatus(candidate, CandidateChangeStatus.INVALIDATED);
            }
            throw new IllegalArgumentException("candidate artifact content is invalid", ex);
        } catch (Exception ex) {
            throw new IllegalArgumentException("candidate artifact content is invalid", ex);
        }
    }

    private CandidateChangeSet withStatus(CandidateChangeSet candidate, CandidateChangeStatus status) {
        return new CandidateChangeSet(candidate.projectId(), candidate.relativePath(), candidate.baseVersion(),
                candidate.summary(), candidate.patchOrSuggestion(), candidate.evidenceRefs(), status,
                CandidateChangeSet.NOT_APPLIED, candidate.artifactId());
    }
}
