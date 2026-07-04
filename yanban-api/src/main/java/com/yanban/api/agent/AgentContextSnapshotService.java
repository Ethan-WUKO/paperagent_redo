package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentContextSnapshot;
import com.yanban.core.agent.AgentContextSnapshotRepository;
import com.yanban.core.agent.AgentSessionRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentContextSnapshotService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AgentContextSnapshotRepository snapshots;
    private final AgentSessionRepository sessions;
    private final ObjectMapper objectMapper;

    public AgentContextSnapshotService(AgentContextSnapshotRepository snapshots,
                                       AgentSessionRepository sessions,
                                       ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentContextSnapshot saveSnapshot(Long turnId,
                                             Long sessionId,
                                             Long userId,
                                             String traceId,
                                             AgentContextPackage contextPackage) {
        AgentContextPackage safePackage = contextPackage == null
                ? new AgentContextPackage(List.of(), List.of(), List.of(), 0, 0, 0)
                : contextPackage;
        AgentContextSnapshot snapshot = new AgentContextSnapshot(
                turnId,
                sessionId,
                userId,
                traceId,
                writeJson(safePackage.sections()),
                writeJson(safePackage.droppedItems()),
                safePackage.rawMessageCount(),
                safePackage.normalizedMessageCount(),
                safePackage.messages().size(),
                safePackage.estimatedCharacters()
        );
        return snapshots.saveAndFlush(snapshot);
    }

    @Transactional(readOnly = true)
    public AgentContextSnapshotResponse getTurnSnapshot(Long userId, Long sessionId, Long turnId) {
        assertOwnedSession(userId, sessionId);
        return snapshots.findByTurnIdAndSessionIdAndUserId(turnId, sessionId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "context snapshot not found"));
    }

    @Transactional(readOnly = true)
    public List<AgentContextSnapshotResponse> listSessionSnapshots(Long userId, Long sessionId, Integer limit) {
        assertOwnedSession(userId, sessionId);
        return snapshots.findBySessionIdAndUserIdOrderByCreatedAtDesc(
                        sessionId,
                        userId,
                        PageRequest.of(0, safeLimit(limit))
                ).stream()
                .map(this::toResponse)
                .toList();
    }

    private void assertOwnedSession(Long userId, Long sessionId) {
        if (sessions.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
    }

    private int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private AgentContextSnapshotResponse toResponse(AgentContextSnapshot snapshot) {
        return new AgentContextSnapshotResponse(
                snapshot.getId(),
                snapshot.getTurnId(),
                snapshot.getSessionId(),
                snapshot.getUserId(),
                snapshot.getTraceId(),
                readSections(snapshot.getSectionsJson()),
                readDroppedItems(snapshot.getDroppedItemsJson()),
                snapshot.getRawMessageCount(),
                snapshot.getNormalizedMessageCount(),
                snapshot.getContextMessageCount(),
                snapshot.getEstimatedCharacters(),
                snapshot.getCreatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize agent context snapshot.", ex);
        }
    }

    private List<AgentContextSection> readSections(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse agent context snapshot sections.", ex);
        }
    }

    private List<AgentContextDroppedItem> readDroppedItems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse agent context snapshot dropped items.", ex);
        }
    }
}
