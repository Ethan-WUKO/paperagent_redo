package com.yanban.mcp.transport;

import java.time.Duration;

public interface McpTransport extends AutoCloseable {
    void open();

    void send(String message);

    String read(Duration timeout);

    @Override
    void close();
}
