package com.yanban.api.paper;

import com.yanban.api.agent.AgentRuntimeMode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentToolCallingMode;
import com.yanban.api.agent.LangChain4jChatModelAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.paper.service.PaperModelExecutionContext;
import com.yanban.paper.service.PaperModelClient;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(PaperModelProperties.class)
public class PaperModelClientConfig {

    @Bean
    public PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel,
                                             PaperModelProperties properties,
                                             ObjectProvider<UserSettingsService> userSettingsServiceProvider) {
        return paperModelClient(chatModel, properties, userSettingsServiceProvider.getIfAvailable());
    }

    PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel,
                                      PaperModelProperties properties,
                                      UserSettingsService userSettingsService) {
        Logger log = LoggerFactory.getLogger(PaperModelClientConfig.class);
        return (systemPrompt, userPrompt, temperature, maxTokens) -> {
            UserSettingsService.ModelEndpoint endpoint = resolveUserEndpoint(properties, userSettingsService);
            String modelName = endpoint == null ? defaultString(properties.getModel()) : endpoint.modelName();
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(defaultString(systemPrompt)),
                            UserMessage.from(defaultString(userPrompt))
                    ))
                    .parameters(ChatRequestParameters.builder()
                            .temperature(temperature)
                            .maxOutputTokens(maxTokens)
                            .build())
                    .modelName(modelName);
            ChatRequest request = builder.build();
            ChatResponse response;
            if (endpoint != null) {
                log.info("Paper model call provider={} model={} sourceType={} sourceLabel={}",
                        endpoint.providerKey(), endpoint.modelName(), endpoint.sourceType(), endpoint.sourceLabel());
                response = chatModel.chat(request, runtimeRequest(endpoint, temperature, maxTokens));
            } else if (StringUtils.hasText(properties.getProvider())) {
                log.info("Paper model call provider={} model={} sourceType=paper-properties sourceLabel=yanban.paper.model",
                        properties.getProvider(), properties.getModel());
                response = chatModel.chat(request, runtimeRequest(properties, temperature, maxTokens));
            } else {
                log.info("Paper model call provider=default model={} sourceType=chat-model-bean sourceLabel=default", modelName);
                response = chatModel.chat(request);
            }
            return response == null || response.aiMessage() == null ? "" : defaultString(response.aiMessage().text());
        };
    }

    public PaperModelClient paperModelClient(LangChain4jChatModelAdapter chatModel, PaperModelProperties properties) {
        return paperModelClient(chatModel, properties, (UserSettingsService) null);
    }

    private UserSettingsService.ModelEndpoint resolveUserEndpoint(PaperModelProperties properties,
                                                                  UserSettingsService userSettingsService) {
        if (StringUtils.hasText(properties.getProvider()) || userSettingsService == null) {
            return null;
        }
        Long userId = PaperModelExecutionContext.currentUserId();
        if (userId == null) {
            return null;
        }
        return userSettingsService.resolveModelEndpoint(userId, null, null);
    }

    private AgentRuntimeRequest runtimeRequest(PaperModelProperties properties, Double temperature, Integer maxTokens) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                null,
                List.of(),
                null,
                "paper-model-call",
                properties.getProvider(),
                properties.getModel(),
                temperature,
                maxTokens,
                1,
                true,
                null,
                properties.getApiKey(),
                properties.getApiUrl(),
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AgentRuntimeRequest runtimeRequest(UserSettingsService.ModelEndpoint endpoint, Double temperature, Integer maxTokens) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                null,
                List.of(),
                null,
                "paper-model-call",
                endpoint.providerKey(),
                endpoint.modelName(),
                temperature,
                maxTokens,
                1,
                true,
                null,
                endpoint.apiKey(),
                endpoint.apiUrl(),
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static String defaultString(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
