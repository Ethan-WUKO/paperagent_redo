package com.yanban.api.agent;

public interface WebSearchClient {

    String provider();

    boolean available();

    String unavailableReason();

    WebSearchResponse search(String query, int limit);
}
