package com.yanban.api.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LongTermMemoryRetrievalService {

    private static final int MAX_CANDIDATES = 100;
    private static final int MAX_HITS = 5;
    private static final int MAX_CONTEXT_CHARACTERS = 1_600;
    private static final int MAX_ITEM_CHARACTERS = 280;
    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.valueOf(0.30);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private final AgentLongTermMemoryRepository memories;
    private final ObjectMapper objectMapper;

    public LongTermMemoryRetrievalService(AgentLongTermMemoryRepository memories, ObjectMapper objectMapper) {
        this.memories = memories;
        this.objectMapper = objectMapper;
    }

    public AgentLongTermMemoryContext retrieve(Long userId, String query) {
        if (userId == null || !StringUtils.hasText(query)) {
            return AgentLongTermMemoryContext.empty();
        }
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return AgentLongTermMemoryContext.empty();
        }

        List<AgentLongTermMemory> rows = memories.findByUserIdAndStatusOrderByUpdatedAtDesc(
                userId,
                AgentLongTermMemory.STATUS_ACTIVE,
                PageRequest.of(0, MAX_CANDIDATES)
        );
        List<ScoredMemory> scored = new ArrayList<>();
        int candidates = 0;
        for (AgentLongTermMemory memory : rows) {
            if (!eligible(memory)) {
                continue;
            }
            candidates++;
            List<String> tags = readTags(memory.getTagsJson());
            double score = score(memory, tags, queryTokens);
            if (score > 0) {
                scored.add(new ScoredMemory(memory, tags, score));
            }
        }
        if (scored.isEmpty()) {
            return new AgentLongTermMemoryContext(null, 0, candidates, 0,
                    "No relevant long-term memory matched the current user message.");
        }

        scored.sort(Comparator
                .comparingDouble(ScoredMemory::score).reversed()
                .thenComparing(item -> safeUpdatedAt(item.memory()), Comparator.reverseOrder()));

        List<ScoredMemory> selected = scored.stream().limit(MAX_HITS).toList();
        StringBuilder content = new StringBuilder();
        List<String> debugItems = new ArrayList<>();
        int omittedByBudget = 0;
        for (ScoredMemory item : selected) {
            String line = formatMemoryLine(item);
            if (content.length() + line.length() + 1 > MAX_CONTEXT_CHARACTERS) {
                omittedByBudget++;
                continue;
            }
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(line);
            debugItems.add(formatDebugItem(item));
        }
        int omitted = Math.max(0, scored.size() - selected.size()) + omittedByBudget;
        if (content.isEmpty()) {
            return new AgentLongTermMemoryContext(null, 0, candidates, scored.size(),
                    "Long-term memory matches were dropped by the context budget.");
        }
        String note = "Injected long-term memories: hits=%d, candidates=%d, omitted=%d, minConfidence=%s, items=%s"
                .formatted(debugItems.size(), candidates, omitted, MIN_CONFIDENCE, String.join(" | ", debugItems));
        return new AgentLongTermMemoryContext(content.toString(), debugItems.size(), candidates, omitted, note);
    }

    private boolean eligible(AgentLongTermMemory memory) {
        return memory != null
                && AgentLongTermMemory.STATUS_ACTIVE.equals(memory.getStatus())
                && memory.getConfidence() != null
                && memory.getConfidence().compareTo(MIN_CONFIDENCE) >= 0
                && StringUtils.hasText(memory.getContent());
    }

    private double score(AgentLongTermMemory memory, List<String> tags, Set<String> queryTokens) {
        String content = memory.getContent().toLowerCase(Locale.ROOT);
        Set<String> memoryTokens = tokenize(content);
        double matchScore = 0.0;
        for (String token : queryTokens) {
            if (memoryTokens.contains(token) || content.contains(token)) {
                matchScore += 2.0;
            }
            for (String tag : tags) {
                String normalizedTag = tag.toLowerCase(Locale.ROOT);
                if (normalizedTag.equals(token) || normalizedTag.contains(token) || token.contains(normalizedTag)) {
                    matchScore += 3.0;
                }
            }
        }
        if (matchScore <= 0) {
            return 0.0;
        }
        return matchScore + memory.getConfidence().doubleValue() + recencyScore(memory.getUpdatedAt());
    }

    private double recencyScore(Instant updatedAt) {
        if (updatedAt == null) {
            return 0.0;
        }
        long days = Math.max(0, Duration.between(updatedAt, Instant.now()).toDays());
        if (days <= 7) {
            return 0.4;
        }
        if (days <= 30) {
            return 0.2;
        }
        if (days <= 180) {
            return 0.1;
        }
        return 0.0;
    }

    private String formatMemoryLine(ScoredMemory item) {
        AgentLongTermMemory memory = item.memory();
        String tags = item.tags().isEmpty() ? "" : ", tags=" + String.join("/", item.tags());
        return "- memory#" + idText(memory)
                + " [" + memory.getMemoryType() + ", confidence=" + memory.getConfidence() + tags + "] "
                + abbreviate(memory.getContent(), MAX_ITEM_CHARACTERS);
    }

    private String formatDebugItem(ScoredMemory item) {
        AgentLongTermMemory memory = item.memory();
        return "#" + idText(memory) + ":" + memory.getMemoryType() + ":"
                + abbreviate(memory.getContent(), 72).replace('\n', ' ');
    }

    private String idText(AgentLongTermMemory memory) {
        return memory.getId() == null ? "new" : String.valueOf(memory.getId());
    }

    private Set<String> tokenize(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private List<String> readTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private Instant safeUpdatedAt(AgentLongTermMemory memory) {
        return memory.getUpdatedAt() == null ? Instant.EPOCH : memory.getUpdatedAt();
    }

    private record ScoredMemory(AgentLongTermMemory memory, List<String> tags, double score) {
    }
}
