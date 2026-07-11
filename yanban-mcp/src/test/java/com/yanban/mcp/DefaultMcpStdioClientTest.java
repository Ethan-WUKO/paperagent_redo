package com.yanban.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yanban.mcp.transport.McpTransport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class DefaultMcpStdioClientTest {

    @Test
    void initializeListToolsAndCallTool() {
        FakeTransport transport = new FakeTransport(List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{\"name\":\"fake\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"search_repositories\",\"description\":\"search github\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}"
        ));
        DefaultMcpStdioClient client = new DefaultMcpStdioClient(
                new McpServerProcessConfig(List.of("echo"), List.of("echo"), Map.of(), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                transport,
                new ObjectMapper()
        );

        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        var result = client.callTool("search_repositories", JsonNodeFactory.instance.objectNode().put("query", "rag"));

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search_repositories");
        assertThat(result.toString()).contains("ok");
        assertThat(transport.sent).hasSize(4);
        assertThat(transport.sent.get(1)).contains("notifications/initialized");
    }

    private static final class FakeTransport implements McpTransport {
        private final Queue<String> responses;
        private final List<String> sent = new ArrayList<>();

        private FakeTransport(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override public void open() {}
        @Override public void close() {}
        @Override public void send(String message) { sent.add(message); }
        @Override public String read(Duration timeout) { return responses.remove(); }
    }
}
