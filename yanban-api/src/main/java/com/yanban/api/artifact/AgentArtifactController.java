package com.yanban.api.artifact;

import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.CandidateChangeSet;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artifacts")
public class AgentArtifactController {

    private final AgentArtifactService artifactService;
    private final CandidateChangeArtifactService candidateArtifacts;

    public AgentArtifactController(AgentArtifactService artifactService, CandidateChangeArtifactService candidateArtifacts) {
        this.artifactService = artifactService;
        this.candidateArtifacts = candidateArtifacts;
    }

    @GetMapping
    public List<ArtifactResponse> list(@AuthenticationPrincipal JwtUser currentUser,
                                       @RequestParam(required = false) Long sessionId,
                                       @RequestParam(defaultValue = "50") Integer limit) {
        return artifactService.listArtifacts(currentUser.id(), sessionId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactResponse create(@AuthenticationPrincipal JwtUser currentUser,
                                   @Valid @RequestBody CreateArtifactRequest request) {
        return artifactService.createArtifact(currentUser.id(), request);
    }

    @GetMapping("/{artifactId}")
    public ArtifactResponse get(@AuthenticationPrincipal JwtUser currentUser,
                                @PathVariable Long artifactId) {
        return artifactService.getArtifact(currentUser.id(), artifactId);
    }

    /** Re-displays a proposal after checking its Project base version; no apply operation exists. */
    @GetMapping("/{artifactId}/candidate")
    public CandidateChangeSet getCandidate(@AuthenticationPrincipal JwtUser currentUser,
                                           @PathVariable Long artifactId) {
        return candidateArtifacts.getCurrent(currentUser.id(), artifactId);
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal JwtUser currentUser,
                                             @PathVariable Long artifactId) {
        Resource resource = artifactService.downloadResource(currentUser.id(), artifactId);
        String filename = artifactService.downloadFilename(currentUser.id(), artifactId);
        String contentType = artifactService.downloadContentType(currentUser.id(), artifactId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(resource);
    }

    @PostMapping("/{artifactId}/save-to-knowledge")
    public SaveArtifactToKnowledgeResponse saveToKnowledge(@AuthenticationPrincipal JwtUser currentUser,
                                                           @PathVariable Long artifactId) {
        return artifactService.saveToKnowledge(currentUser.id(), artifactId);
    }
}
