package com.yanban.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({DeepSeekProperties.class, GlmProperties.class})
public class ModelProviderConfig {

    @Bean
    public DeepSeekModelProvider deepSeekModelProvider(DeepSeekProperties properties) {
        return new DeepSeekModelProvider(properties);
    }

    @Bean
    public GlmModelProvider glmModelProvider(GlmProperties properties) {
        return new GlmModelProvider(properties);
    }

    @Bean
    public OpenAiCompatibleModelProvider openAiCompatibleModelProvider(ObjectMapper objectMapper) {
        return new OpenAiCompatibleModelProvider(objectMapper);
    }

    @Bean
    public ChatModelProvider chatModelProvider(DeepSeekModelProvider deepSeekModelProvider,
                                               GlmModelProvider glmModelProvider,
                                               OpenAiCompatibleModelProvider openAiCompatibleModelProvider) {
        return new RoutingChatModelProvider(
                java.util.Map.of(
                        deepSeekModelProvider.providerName(), deepSeekModelProvider,
                        glmModelProvider.providerName(), glmModelProvider
                ),
                deepSeekModelProvider.providerName(),
                openAiCompatibleModelProvider
        );
    }
}
