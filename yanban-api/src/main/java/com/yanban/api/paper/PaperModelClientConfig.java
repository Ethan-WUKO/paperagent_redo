package com.yanban.api.paper;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.paper.service.PaperModelClient;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaperModelClientConfig {

    @Bean
    public PaperModelClient paperModelClient(ChatModelProvider chatModelProvider) {
        return (systemPrompt, userPrompt, temperature, maxTokens) -> {
            ChatResponse response = chatModelProvider.chat(new ChatRequest(
                    null,
                    null,
                    List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)),
                    temperature,
                    maxTokens,
                    null,
                    null
            ));
            return response.assistantText();
        };
    }
}
