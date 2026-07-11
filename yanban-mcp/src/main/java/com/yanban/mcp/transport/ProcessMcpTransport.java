package com.yanban.mcp.transport;

import com.yanban.mcp.McpClientException;
import com.yanban.mcp.McpServerProcessConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ProcessMcpTransport implements McpTransport {

    private final McpServerProcessConfig config;
    private Process process;
    private BufferedInputStream inputStream;
    private OutputStream outputStream;

    public ProcessMcpTransport(McpServerProcessConfig config) {
        this.config = config;
    }

    @Override
    public void open() {
        validateCommand(config.command(), config.allowedCommands());
        try {
            ProcessBuilder builder = new ProcessBuilder(config.command());
            Map<String, String> environment = builder.environment();
            environment.putAll(config.environment());
            process = builder.start();
            inputStream = new BufferedInputStream(process.getInputStream());
            outputStream = process.getOutputStream();
        } catch (IOException ex) {
            throw new McpClientException("启动 MCP 子进程失败", ex);
        }
    }

    @Override
    public void send(String message) {
        ensureOpen();
        try {
            outputStream.write(ContentLengthMcpFraming.encode(message));
            outputStream.flush();
        } catch (IOException ex) {
            throw new McpClientException("发送 MCP 消息失败", ex);
        }
    }

    @Override
    public String read(Duration timeout) {
        ensureOpen();
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> ContentLengthMcpFraming.decode(inputStream));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            destroyQuietly();
            throw new McpClientException("读取 MCP 响应失败", ex);
        }
    }

    @Override
    public void close() {
        destroyQuietly();
    }

    private void ensureOpen() {
        if (process == null || outputStream == null || inputStream == null) {
            throw new McpClientException("MCP 传输尚未打开");
        }
        if (!process.isAlive()) {
            throw new McpClientException("MCP 子进程已退出");
        }
    }

    private void validateCommand(List<String> command, List<String> allowedCommands) {
        if (command == null || command.isEmpty()) {
            throw new McpClientException("MCP 命令未配置");
        }
        String executable = command.get(0);
        if (allowedCommands != null && !allowedCommands.isEmpty() && !allowedCommands.contains(executable)) {
            throw new McpClientException("MCP 命令不在白名单中: " + executable);
        }
    }

    private void destroyQuietly() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
