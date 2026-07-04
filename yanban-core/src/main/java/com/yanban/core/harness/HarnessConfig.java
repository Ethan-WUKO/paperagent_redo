package com.yanban.core.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.rag.KnowledgeContextProvider;
import com.yanban.core.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HarnessConfig {

    @Bean
    public HarnessEngine harnessEngine(ChatModelProvider chatModelProvider,
                                       ToolRegistry toolRegistry,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<KnowledgeContextProvider> knowledgeContextProvider,
                                       ObjectProvider<java.util.List<ToolResultPostProcessor>> toolResultPostProcessors,
                                       @Value("${yanban.harness.rag-top-k:5}") int ragTopK) {
        java.util.List<ToolResultPostProcessor> processors = toolResultPostProcessors.getIfAvailable(java.util.List::of);
        return new HarnessEngine(chatModelProvider, toolRegistry, objectMapper, knowledgeContextProvider.getIfAvailable(), processors, ragTopK);
    }
}
