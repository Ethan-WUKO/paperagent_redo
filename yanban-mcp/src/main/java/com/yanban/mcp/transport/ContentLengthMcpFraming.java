package com.yanban.mcp.transport;

import com.yanban.mcp.McpClientException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ContentLengthMcpFraming {

    private static final byte[] HEADER_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private ContentLengthMcpFraming() {
    }

    public static byte[] encode(String json) {
        // Current npm MCP servers used by this project speak newline-delimited JSON over stdio.
        // Content-Length framing is still supported on decode for compatibility with older servers/tests.
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static String decode(InputStream inputStream) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                int b = inputStream.read();
                if (b < 0) {
                    throw new McpClientException("MCP stream closed while reading message");
                }
                buffer.write(b);

                byte[] bytes = buffer.toByteArray();
                String current = new String(bytes, StandardCharsets.UTF_8);
                if (current.startsWith("Content-Length:")) {
                    if (endsWithHeaderSeparator(bytes)) {
                        String headers = current;
                        int contentLength = parseContentLength(headers);
                        byte[] body = inputStream.readNBytes(contentLength);
                        if (body.length != contentLength) {
                            throw new McpClientException("MCP stream closed while reading body");
                        }
                        return new String(body, StandardCharsets.UTF_8);
                    }
                } else if (b == '\n') {
                    String line = current.trim();
                    if (!line.isEmpty()) {
                        return line;
                    }
                    buffer.reset();
                }
            }
        } catch (IOException ex) {
            throw new McpClientException("读取 MCP 消息失败", ex);
        }
    }

    private static boolean endsWithHeaderSeparator(byte[] bytes) {
        if (bytes.length < HEADER_SEPARATOR.length) {
            return false;
        }
        for (int i = 0; i < HEADER_SEPARATOR.length; i++) {
            if (bytes[bytes.length - HEADER_SEPARATOR.length + i] != HEADER_SEPARATOR[i]) {
                return false;
            }
        }
        return true;
    }

    private static int parseContentLength(String headers) {
        for (String line : headers.split("\\r\\n")) {
            String normalized = line.trim().toLowerCase();
            if (normalized.startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        throw new McpClientException("MCP headers missing Content-Length");
    }
}
