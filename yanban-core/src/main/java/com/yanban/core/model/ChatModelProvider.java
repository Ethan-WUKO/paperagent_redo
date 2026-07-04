package com.yanban.core.model;

import reactor.core.publisher.Flux;

public interface ChatModelProvider {
    String providerName();

    ChatResponse chat(ChatRequest request);

    Flux<ChatChunk> streamChat(ChatRequest request);
}
