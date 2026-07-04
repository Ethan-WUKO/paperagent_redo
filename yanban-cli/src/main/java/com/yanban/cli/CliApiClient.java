package com.yanban.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class CliApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CliConfigStore configStore;

    public CliApiClient(CliConfigStore configStore) {
        this.configStore = configStore;
    }

    public JsonNode login(String apiBaseUrl, String username, String password) {
        return sendJson("POST", apiBaseUrl + "/api/v1/auth/login", objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password), null);
    }

    public JsonNode createSession(String title) {
        return sendJson("POST", apiBaseUrl() + "/api/v1/agent/sessions", objectMapper.createObjectNode().put("title", title), accessToken());
    }

    public String chatViaWebSocket(long sessionId, String content) {
        Properties properties = configStore.load();
        String baseUrl = properties.getProperty("apiBaseUrl", "http://localhost:8080");
        String token = properties.getProperty("accessToken");
        String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/api/v1/ws/chat?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                try {
                    String payload = objectMapper.createObjectNode()
                            .put("sessionId", sessionId)
                            .put("content", content)
                            .toString();
                    webSocket.sendText(payload, true);
                    WebSocket.Listener.super.onOpen(webSocket);
                } catch (Exception ex) {
                    latch.countDown();
                }
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                try {
                    JsonNode event = objectMapper.readTree(data.toString());
                    String type = event.path("type").asText();
                    if ("chunk".equals(type)) {
                        String piece = event.path("content").asText("");
                        builder.append(piece);
                        System.out.print(piece);
                    }
                    if ("done".equals(type) || "error".equals(type)) {
                        System.out.println();
                        latch.countDown();
                        webSocket.abort();
                    }
                } catch (Exception ex) {
                    latch.countDown();
                }
                return CompletableFuture.completedFuture(null);
            }
        }).join();
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return builder.toString();
    }

    public JsonNode getSettings() {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/settings"))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    public JsonNode updateSettings(JsonNode payload) {
        return sendJson("PUT", apiBaseUrl() + "/api/v1/settings", payload, accessToken());
    }

    public JsonNode listKbDocuments() {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/kb/documents"))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    public JsonNode simpleUpload(Path file, boolean isPublic) {
        try {
            String boundary = "----yanban" + System.currentTimeMillis();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeFormField(body, boundary, "isPublic", String.valueOf(isPublic));
            writeFileField(body, boundary, "file", file);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/kb/documents/simple-upload"))
                    .header("Authorization", bearer())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            return sendRequest(request);
        } catch (IOException ex) {
            throw new IllegalStateException("构造上传请求失败", ex);
        }
    }

    public JsonNode getPaperTask(long taskId) {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/paper/tasks/" + taskId))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(ByteArrayOutputStream out, String boundary, String name, Path file) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode sendJson(String method, String url, JsonNode payload, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        } catch (Exception ex) {
            throw new IllegalStateException("序列化请求失败", ex);
        }
        return sendRequest(builder.build());
    }

    private JsonNode sendRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("调用后端 API 失败", ex);
        }
    }

    private String apiBaseUrl() {
        return configStore.load().getProperty("apiBaseUrl", "http://localhost:8080");
    }

    private String accessToken() {
        String token = configStore.load().getProperty("accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("请先执行 yanban login");
        }
        return token;
    }

    private String bearer() {
        return "Bearer " + accessToken();
    }
}
