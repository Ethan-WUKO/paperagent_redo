package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageCacheService {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageCacheService.class);
    private static final int MAX_RECENT_MESSAGES = 50;
    private static final Duration RECENT_TTL = Duration.ofHours(6);
    private static final TypeReference<List<AgentMessageResponse>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public AgentMessageCacheService(ObjectMapper objectMapper,
                                    ObjectProvider<StringRedisTemplate> redisProvider) {
        this.objectMapper = objectMapper;
        this.redis = redisProvider.getIfAvailable();
    }

    public Optional<List<AgentMessageResponse>> getRecentVisibleMessages(Long userId, Long sessionId, int limit) {
        if (redis == null || userId == null || sessionId == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(recentKey(userId, sessionId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            List<AgentMessageResponse> values = objectMapper.readValue(json, MESSAGE_LIST_TYPE);
            values.sort(Comparator.comparing(AgentMessageResponse::id));
            return Optional.of(tail(values, limit));
        } catch (Exception ex) {
            log.debug("Ignoring Redis recent message cache read failure userId={} sessionId={} error={}", userId, sessionId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void putRecentVisibleMessages(Long userId, Long sessionId, List<AgentMessageResponse> messages) {
        if (redis == null || userId == null || sessionId == null) {
            return;
        }
        try {
            List<AgentMessageResponse> values = tail(visible(messages), MAX_RECENT_MESSAGES);
            redis.opsForValue().set(recentKey(userId, sessionId), objectMapper.writeValueAsString(values), RECENT_TTL);
        } catch (Exception ex) {
            evictSession(userId, sessionId);
            log.debug("Ignoring Redis recent message cache write failure userId={} sessionId={} error={}", userId, sessionId, ex.getMessage());
        }
    }

    public void appendVisibleMessage(Long userId, Long sessionId, AgentMessageResponse message) {
        if (message == null || !isVisible(message)) {
            return;
        }
        if (redis == null || userId == null || sessionId == null) {
            return;
        }
        try {
            List<AgentMessageResponse> values = getRecentVisibleMessages(userId, sessionId, MAX_RECENT_MESSAGES)
                    .map(ArrayList::new)
                    .orElseGet(ArrayList::new);
            values.removeIf(existing -> existing.id().equals(message.id()));
            values.add(message);
            values.sort(Comparator.comparing(AgentMessageResponse::id));
            putRecentVisibleMessages(userId, sessionId, values);
        } catch (Exception ex) {
            evictSession(userId, sessionId);
            log.debug("Ignoring Redis recent message cache append failure userId={} sessionId={} error={}", userId, sessionId, ex.getMessage());
        }
    }

    public void putTurnStatus(Long turnId, String status, String errorMessage) {
        if (redis == null || turnId == null) {
            return;
        }
        try {
            String value = objectMapper.writeValueAsString(new TurnStatusCacheValue(status, errorMessage));
            redis.opsForValue().set(turnStatusKey(turnId), value, RECENT_TTL);
        } catch (Exception ex) {
            log.debug("Ignoring Redis turn status write failure turnId={} error={}", turnId, ex.getMessage());
        }
    }

    public void evictSession(Long userId, Long sessionId) {
        if (redis == null || sessionId == null) {
            return;
        }
        try {
            if (userId != null) {
                redis.delete(recentKey(userId, sessionId));
            }
            redis.delete(legacyRecentKey(sessionId));
        } catch (Exception ex) {
            log.debug("Ignoring Redis session cache eviction failure userId={} sessionId={} error={}", userId, sessionId, ex.getMessage());
        }
    }

    private List<AgentMessageResponse> visible(List<AgentMessageResponse> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(this::isVisible)
                .sorted(Comparator.comparing(AgentMessageResponse::id))
                .toList();
    }

    private boolean isVisible(AgentMessageResponse message) {
        if (message == null || message.role() == null) {
            return false;
        }
        String role = message.role().trim().toLowerCase();
        return "user".equals(role) || "assistant".equals(role);
    }

    private List<AgentMessageResponse> tail(List<AgentMessageResponse> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(MAX_RECENT_MESSAGES, limit));
        int fromIndex = Math.max(0, values.size() - safeLimit);
        return new ArrayList<>(values.subList(fromIndex, values.size()));
    }

    private String recentKey(Long userId, Long sessionId) {
        return "chat:user:" + userId + ":session:" + sessionId + ":recent";
    }

    private String legacyRecentKey(Long sessionId) {
        return "chat:session:" + sessionId + ":recent";
    }

    private String turnStatusKey(Long turnId) {
        return "chat:turn:" + turnId + ":status";
    }

    private record TurnStatusCacheValue(String status, String errorMessage) {
    }
}
