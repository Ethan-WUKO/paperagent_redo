package com.yanban.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GlmModelProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chatParsesAssistantTextAndSendsGlmStructuredOptions() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            traceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"choices":[{"message":{"role":"assistant","content":"GLM reply"},"finish_reason":"stop"}],"usage":{"total_tokens":9}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        GlmModelProvider provider = new GlmModelProvider(
                properties(),
                org.springframework.web.reactive.function.client.WebClient.builder(),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        ChatResponse response = provider.chat(new ChatRequest(
                "glm",
                null,
                List.of(ChatMessage.user("hello")),
                null,
                256,
                null,
                "glm-key",
                ChatRequest.ResponseFormat.jsonObject(),
                ChatRequest.Thinking.disabled(),
                "trace-glm-1"
        ));

        assertThat(response.assistantText()).isEqualTo("GLM reply");
        assertThat(authorization.get()).isEqualTo("Bearer glm-key");
        assertThat(traceId.get()).isEqualTo("trace-glm-1");
        assertThat(requestBody.get()).contains("\"max_tokens\":256");
        assertThat(requestBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(requestBody.get()).contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    @Test
    void chatRetriesWhenConnectionClosesBeforeResponse() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
            byte[] bytes = """
                    {"choices":[{"message":{"role":"assistant","content":"recovered"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        GlmModelProvider provider = new GlmModelProvider(
                properties(),
                org.springframework.web.reactive.function.client.WebClient.builder(),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        ChatResponse response = provider.chat(new ChatRequest(
                "glm",
                null,
                List.of(ChatMessage.user("hello")),
                null,
                null,
                null,
                "glm-key"
        ));

        assertThat(response.assistantText()).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(2);
    }

    private GlmProperties properties() {
        GlmProperties properties = new GlmProperties();
        properties.setApiUrl("http://localhost:" + server.getAddress().getPort() + "/v4/chat/completions");
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v4/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
