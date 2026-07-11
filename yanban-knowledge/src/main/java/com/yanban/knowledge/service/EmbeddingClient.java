package com.yanban.knowledge.service;

import java.util.List;

public interface EmbeddingClient {
    List<Double> embed(String text);
}
