package com.yanban.mcp.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentLengthMcpFramingTest {

    @Test
    void encodeAndDecodeRoundTrip() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        byte[] framed = ContentLengthMcpFraming.encode(json);
        String decoded = ContentLengthMcpFraming.decode(new ByteArrayInputStream(framed));
        assertThat(decoded).isEqualTo(json);
        assertThat(new String(framed, StandardCharsets.UTF_8)).endsWith("\n");
    }

    @Test
    void decodeContentLengthFrame() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] framed = ("Content-Length: " + body.length + "\r\n\r\n" + json).getBytes(StandardCharsets.UTF_8);

        String decoded = ContentLengthMcpFraming.decode(new ByteArrayInputStream(framed));

        assertThat(decoded).isEqualTo(json);
    }
}
