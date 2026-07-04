package com.yanban.api.ws;

public record WsChatEvent(
        String type,
        String content,
        Long sessionId,
        String error,
        String finishReason,
        String navigationUrl
) {
    public static WsChatEvent chunk(Long sessionId, String content) {
        return new WsChatEvent("chunk", content, sessionId, null, null, null);
    }

    public static WsChatEvent done(Long sessionId, String finishReason) {
        return new WsChatEvent("done", null, sessionId, null, finishReason, null);
    }

    public static WsChatEvent doneWithNavigation(Long sessionId, String finishReason, String navigationUrl) {
        return new WsChatEvent("done", null, sessionId, null, finishReason, navigationUrl);
    }

    public static WsChatEvent error(Long sessionId, String error) {
        return new WsChatEvent("error", null, sessionId, error, null, null);
    }
}
