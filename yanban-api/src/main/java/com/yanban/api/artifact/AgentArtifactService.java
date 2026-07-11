package com.yanban.api.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentArtifact;
import com.yanban.core.agent.AgentArtifactRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.KnowledgeIngestionService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentArtifactService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_CONTENT_CHARS = 500_000;
    private static final String KNOWLEDGE_SOURCE_TYPE = "AGENT_ARTIFACT";

    private final AgentArtifactRepository artifacts;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final KbDocumentRepository kbDocuments;
    private final ObjectMapper objectMapper;

    public AgentArtifactService(AgentArtifactRepository artifacts,
                                KnowledgeIngestionService knowledgeIngestionService,
                                KbDocumentRepository kbDocuments,
                                ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.kbDocuments = kbDocuments;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ArtifactResponse> listArtifacts(Long userId, Long sessionId, Integer limit) {
        PageRequest page = PageRequest.of(0, safeLimit(limit));
        List<AgentArtifact> rows = sessionId == null
                ? artifacts.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, AgentArtifact.STATUS_ACTIVE, page)
                : artifacts.findByUserIdAndSessionIdAndStatusOrderByUpdatedAtDesc(userId, sessionId, AgentArtifact.STATUS_ACTIVE, page);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ArtifactResponse getArtifact(Long userId, Long artifactId) {
        return toResponse(getOwnedArtifact(userId, artifactId));
    }

    @Transactional
    public ArtifactResponse createArtifact(Long userId, CreateArtifactRequest request) {
        String content = requireContent(request.content());
        AgentArtifact artifact = new AgentArtifact(
                userId,
                request.sessionId(),
                safeTitle(request.title()),
                normalizeType(request.artifactType()),
                content,
                normalizeSourceType(request.sourceType()),
                writeSourceRefs(request.sourceRefs())
        );
        return toResponse(artifacts.saveAndFlush(artifact));
    }

    @Transactional
    public ArtifactResponse createToolArtifact(Long userId, String title, String content) {
        CreateArtifactRequest request = new CreateArtifactRequest(
                null,
                safeTitle(title),
                inferArtifactType(title),
                content,
                AgentArtifact.SOURCE_AGENT_TOOL,
                List.of()
        );
        return createArtifact(userId, request);
    }

    @Transactional(readOnly = true)
    public Resource downloadResource(Long userId, Long artifactId) {
        AgentArtifact artifact = getOwnedArtifact(userId, artifactId);
        return new ByteArrayResource(artifact.getContent().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public String downloadFilename(Long userId, Long artifactId) {
        return filenameFor(getOwnedArtifact(userId, artifactId));
    }

    @Transactional(readOnly = true)
    public String downloadContentType(Long userId, Long artifactId) {
        return contentTypeFor(getOwnedArtifact(userId, artifactId));
    }

    @Transactional
    public SaveArtifactToKnowledgeResponse saveToKnowledge(Long userId, Long artifactId) {
        AgentArtifact artifact = getOwnedArtifact(userId, artifactId);
        KbDocument document = knowledgeIngestionService.ingestText(
                userId,
                filenameFor(artifact),
                artifact.getContent(),
                false,
                KNOWLEDGE_SOURCE_TYPE,
                contentTypeFor(artifact)
        );
        document.setSourceArtifactId(artifact.getId());
        document.setSourceTaskType(KNOWLEDGE_SOURCE_TYPE);
        KbDocument saved = kbDocuments.saveAndFlush(document);
        return new SaveArtifactToKnowledgeResponse(
                artifact.getId(),
                saved.getId(),
                saved.getFilename(),
                saved.getStatus()
        );
    }

    private AgentArtifact getOwnedArtifact(Long userId, Long artifactId) {
        return artifacts.findByIdAndUserId(artifactId, userId)
                .filter(artifact -> AgentArtifact.STATUS_ACTIVE.equals(artifact.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact not found"));
    }

    private ArtifactResponse toResponse(AgentArtifact artifact) {
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getUserId(),
                artifact.getSessionId(),
                artifact.getTitle(),
                artifact.getArtifactType(),
                artifact.getContent(),
                artifact.getSourceType(),
                readSourceRefs(artifact.getSourceRefsJson()),
                artifact.getStatus(),
                "/api/v1/artifacts/" + artifact.getId() + "/download",
                filenameFor(artifact),
                contentTypeFor(artifact),
                artifact.getCreatedAt(),
                artifact.getUpdatedAt()
        );
    }

    private int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "content is too large");
        }
        return content;
    }

    private String safeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "agent-artifact.md";
        }
        String normalized = title.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private String normalizeType(String artifactType) {
        if (!StringUtils.hasText(artifactType)) {
            return AgentArtifact.TYPE_MARKDOWN;
        }
        String normalized = artifactType.trim().toUpperCase();
        return switch (normalized) {
            case AgentArtifact.TYPE_TEXT, AgentArtifact.TYPE_MARKDOWN -> normalized;
            default -> AgentArtifact.TYPE_MARKDOWN;
        };
    }

    private String inferArtifactType(String title) {
        if (StringUtils.hasText(title) && title.trim().toLowerCase().endsWith(".txt")) {
            return AgentArtifact.TYPE_TEXT;
        }
        return AgentArtifact.TYPE_MARKDOWN;
    }

    private String normalizeSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType.trim().toUpperCase() : AgentArtifact.SOURCE_AGENT_TOOL;
    }

    private String filenameFor(AgentArtifact artifact) {
        String title = sanitizeFilename(artifact.getTitle());
        String lower = title.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return title;
        }
        return AgentArtifact.TYPE_TEXT.equals(artifact.getArtifactType()) ? title + ".txt" : title + ".md";
    }

    private String sanitizeFilename(String title) {
        String normalized = StringUtils.hasText(title) ? title.trim() : "agent-artifact";
        normalized = normalized.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replaceAll("[\\r\\n\\t]", " ").replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        return StringUtils.hasText(normalized) ? normalized : "agent-artifact";
    }

    private String contentTypeFor(AgentArtifact artifact) {
        return AgentArtifact.TYPE_TEXT.equals(artifact.getArtifactType()) ? "text/plain;charset=UTF-8" : "text/markdown;charset=UTF-8";
    }

    private String writeSourceRefs(List<ArtifactSourceRef> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new LinkedHashSet<>(sourceRefs));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sourceRefs");
        }
    }

    private List<ArtifactSourceRef> readSourceRefs(String sourceRefsJson) {
        if (!StringUtils.hasText(sourceRefsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourceRefsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
