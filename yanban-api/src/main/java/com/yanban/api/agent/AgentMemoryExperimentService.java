package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentMemoryExperimentService {

    private final AgentContextBuilder agentContextBuilder;

    public AgentMemoryExperimentService(AgentContextBuilder agentContextBuilder) {
        this.agentContextBuilder = agentContextBuilder;
    }

    public AgentMemoryExperimentResult buildContext(AgentExperimentContext experimentContext,
                                                    AgentContextBuildRequest request) {
        AgentContextPackage contextPackage = agentContextBuilder.build(request);
        AgentMemoryWindowDebug memoryWindow = shouldExposeMemoryWindow(experimentContext)
                ? new AgentMemoryWindowDebug(AgentMemoryMode.CONTEXT_PACKER, summarizeCoreMessages(contextPackage.messages()))
                : null;
        return new AgentMemoryExperimentResult(
                contextPackage,
                memoryWindow
        );
    }

    private boolean shouldExposeMemoryWindow(AgentExperimentContext experimentContext) {
        return experimentContext != null
                && experimentContext.enabled()
                && experimentContext.hasFlag(AgentDebugFlag.SHOW_MEMORY_WINDOW);
    }

    private List<String> summarizeCoreMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        int index = 1;
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String content = defaultContent(message.content()).replace('\n', ' ').trim();
            if (content.length() > 120) {
                content = content.substring(0, 120).trim() + "...";
            }
            entries.add(index + ". " + message.role() + ": " + content);
            index++;
        }
        return entries;
    }

    private String defaultContent(String value) {
        return StringUtils.hasText(value) ? value.trim() : "(empty)";
    }
}
