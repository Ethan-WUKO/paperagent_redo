package com.yanban.core.model;

import java.util.Map;
import reactor.core.publisher.Flux;

public class RoutingChatModelProvider implements ChatModelProvider {

    private final Map<String, ChatModelProvider> providers;
    private final String defaultProvider;
    private final ChatModelProvider fallbackProvider;

    public RoutingChatModelProvider(Map<String, ChatModelProvider> providers, String defaultProvider) {
        this(providers, defaultProvider, null);
    }

    public RoutingChatModelProvider(Map<String, ChatModelProvider> providers,
                                   String defaultProvider,
                                   ChatModelProvider fallbackProvider) {
        this.providers = Map.copyOf(providers);
        this.defaultProvider = defaultProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public String providerName() {
        return "routing";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return resolve(request.provider(), request.apiUrl()).chat(request);
    }

    @Override
    public Flux<ChatChunk> streamChat(ChatRequest request) {
        return resolve(request.provider(), request.apiUrl()).streamChat(request);
    }

    private ChatModelProvider resolve(String provider, String apiUrl) {
        String resolved = (provider == null || provider.isBlank()) ? defaultProvider : provider.trim().toLowerCase();
        ChatModelProvider modelProvider = providers.get(resolved);
        if (modelProvider != null) {
            return modelProvider;
        }
        // Unknown provider (e.g. custom user model) — use the generic OpenAI-compatible fallback
        if (fallbackProvider != null && apiUrl != null && !apiUrl.isBlank()) {
            return fallbackProvider;
        }
        throw new ModelProviderException("Unsupported model provider: " + resolved);
    }
}
