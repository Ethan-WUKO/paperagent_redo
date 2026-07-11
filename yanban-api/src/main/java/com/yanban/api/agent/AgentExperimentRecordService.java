package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentExperimentRecord;
import com.yanban.core.agent.AgentExperimentRecordRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentExperimentRecordService {

    private static final Logger log = LoggerFactory.getLogger(AgentExperimentRecordService.class);

    private final AgentExperimentRecordRepository records;
    private final ObjectMapper objectMapper;

    public AgentExperimentRecordService(AgentExperimentRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    public Long persistIfEnabled(Long userId,
                                 Long sessionId,
                                 String clientRequestId,
                                 AgentExperimentContext experimentContext,
                                 AgentDebugPayload debugPayload,
                                 String assistantContent,
                                 boolean success,
                                 String errorMessage,
                                 Long latencyMs) {
        if (experimentContext == null || !experimentContext.enabled() || !experimentContext.persistEvalRecord()) {
            return null;
        }
        try {
            List<String> finalCitations = extractFinalCitations(assistantContent, debugPayload == null ? List.of() : debugPayload.retrievedChunks());
            AgentExperimentRecord saved = records.saveAndFlush(new AgentExperimentRecord(
                    sessionId,
                    userId,
                    clientRequestId,
                    experimentContext.selectedModes().runtimeMode().name(),
                    experimentContext.selectedModes().ragMode().name(),
                    experimentContext.selectedModes().memoryMode().name(),
                    experimentContext.selectedModes().toolCallingMode().name(),
                    success,
                    latencyMs,
                    debugPayload == null || debugPayload.retrievedChunks() == null ? 0 : debugPayload.retrievedChunks().size(),
                    debugPayload == null || debugPayload.memoryWindow() == null ? 0 : debugPayload.memoryWindow().entries().size(),
                    1,
                    write(debugPayload == null ? List.of() : debugPayload.debugFlags()),
                    write(debugPayload == null ? List.of() : debugPayload.toolTrace()),
                    write(debugPayload == null || debugPayload.memoryWindow() == null ? List.of() : debugPayload.memoryWindow().entries()),
                    write(debugPayload == null ? List.of() : debugPayload.retrievedChunks()),
                    write(debugPayload == null ? finalCitations : debugPayload.finalCitations().isEmpty() ? finalCitations : debugPayload.finalCitations()),
                    errorMessage,
                    firstFallback(debugPayload),
                    assistantContent
            ));
            return saved.getId();
        } catch (Exception ex) {
            log.warn("Failed to persist agent experiment record sessionId={} userId={} requestId={}",
                    sessionId, userId, clientRequestId, ex);
            return null;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize experiment record payload", ex);
        }
    }

    private String firstFallback(AgentDebugPayload debugPayload) {
        if (debugPayload == null || debugPayload.fallbacks() == null || debugPayload.fallbacks().isEmpty()) {
            return null;
        }
        return debugPayload.fallbacks().get(0);
    }

    private List<String> extractFinalCitations(String assistantContent, List<AgentRetrievedChunkDebug> chunks) {
        if (assistantContent == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> citations = new ArrayList<>();
        for (AgentRetrievedChunkDebug chunk : chunks) {
            if (chunk == null || chunk.citationId() == null || chunk.citationId().isBlank()) {
                continue;
            }
            if (assistantContent.contains(chunk.citationId())) {
                citations.add(chunk.citationId());
            }
        }
        return citations;
    }
}
