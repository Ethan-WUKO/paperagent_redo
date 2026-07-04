package com.yanban.paper.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PaperEventStreamService {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, List<PaperSseEvent>> history = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(ex -> removeEmitter(taskId, emitter));
        history.getOrDefault(taskId, List.of()).forEach(event -> sendEvent(taskId, emitter, event));
        return emitter;
    }

    public void publish(PaperSseEvent event) {
        history.computeIfAbsent(event.taskId(), key -> new CopyOnWriteArrayList<>()).add(event);
        for (SseEmitter emitter : emitters.getOrDefault(event.taskId(), List.of())) {
            sendEvent(event.taskId(), emitter, event);
        }
    }

    private void sendEvent(Long taskId, SseEmitter emitter, PaperSseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event));
        } catch (IOException | IllegalStateException ex) {
            removeEmitter(taskId, emitter);
            safeComplete(emitter);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // The client may have already closed the SSE response.
        }
    }

    private void removeEmitter(Long taskId, SseEmitter emitter) {
        emitters.computeIfPresent(taskId, (key, values) -> {
            values.remove(emitter);
            return values.isEmpty() ? null : values;
        });
    }
}
