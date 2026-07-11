package com.yanban.api.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LongTermMemoryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final String STATUS_ALL = "ALL";

    private final AgentLongTermMemoryRepository memories;
    private final ObjectMapper objectMapper;

    public LongTermMemoryService(AgentLongTermMemoryRepository memories, ObjectMapper objectMapper) {
        this.memories = memories;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LongTermMemoryResponse> listMemories(Long userId, String status, Integer limit) {
        String normalizedStatus = normalizeStatus(status);
        PageRequest page = PageRequest.of(0, safeLimit(limit));
        List<AgentLongTermMemory> rows = STATUS_ALL.equals(normalizedStatus)
                ? memories.findByUserIdOrderByUpdatedAtDesc(userId, page)
                : memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, normalizedStatus, page);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LongTermMemoryResponse getMemory(Long userId, Long memoryId) {
        return toResponse(getOwnedMemory(userId, memoryId));
    }

    @Transactional
    public LongTermMemoryResponse createMemory(Long userId, CreateLongTermMemoryRequest request) {
        AgentLongTermMemory memory = new AgentLongTermMemory(
                userId,
                request.projectId(),
                request.scope(),
                request.memoryType(),
                request.content(),
                writeTags(request.tags()),
                request.sourceType(),
                request.sourceRefId(),
                request.confidence(),
                null
        );
        return toResponse(memories.saveAndFlush(memory));
    }

    @Transactional
    public LongTermMemoryResponse correctMemory(Long userId, Long memoryId, UpdateLongTermMemoryRequest request) {
        AgentLongTermMemory current = getOwnedMemory(userId, memoryId);
        if (!AgentLongTermMemory.STATUS_ACTIVE.equals(current.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only active memory can be corrected");
        }
        AgentLongTermMemory replacement = new AgentLongTermMemory(
                userId,
                request.projectId() == null ? current.getProjectId() : request.projectId(),
                fallback(request.scope(), current.getScope()),
                fallback(request.memoryType(), current.getMemoryType()),
                request.content(),
                request.tags() == null ? current.getTagsJson() : writeTags(request.tags()),
                AgentLongTermMemory.SOURCE_USER_CORRECTED,
                String.valueOf(current.getId()),
                request.confidence() == null ? current.getConfidence() : request.confidence(),
                current.getId()
        );
        AgentLongTermMemory saved = memories.saveAndFlush(replacement);
        current.markSuperseded(saved.getId());
        memories.saveAndFlush(current);
        return toResponse(saved);
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        AgentLongTermMemory memory = getOwnedMemory(userId, memoryId);
        if (!AgentLongTermMemory.STATUS_DELETED.equals(memory.getStatus())) {
            memory.markDeleted();
            memories.saveAndFlush(memory);
        }
    }

    private AgentLongTermMemory getOwnedMemory(Long userId, Long memoryId) {
        return memories.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
    }

    private LongTermMemoryResponse toResponse(AgentLongTermMemory memory) {
        return new LongTermMemoryResponse(
                memory.getId(),
                memory.getUserId(),
                memory.getProjectId(),
                memory.getScope(),
                memory.getMemoryType(),
                memory.getContent(),
                readTags(memory.getTagsJson()),
                memory.getSourceType(),
                memory.getSourceRefId(),
                memory.getConfidence(),
                memory.getStatus(),
                memory.getSupersedesMemoryId(),
                memory.getSupersededByMemoryId(),
                memory.getCreatedAt(),
                memory.getUpdatedAt(),
                memory.getDeletedAt()
        );
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : AgentLongTermMemory.STATUS_ACTIVE;
    }

    private int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String fallback(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate.trim() : fallback;
    }

    private String writeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (StringUtils.hasText(tag)) {
                normalized.add(tag.trim());
            }
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize memory tags.", ex);
        }
    }

    private List<String> readTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse memory tags.", ex);
        }
    }
}
