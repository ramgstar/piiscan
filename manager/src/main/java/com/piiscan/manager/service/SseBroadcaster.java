package com.piiscan.manager.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans server-sent events out to every browser subscribed to a given job.
 *
 * <p>Emitters are held per job id; dead ones (client navigated away) are dropped
 * on the first failed send.
 */
@Component
public class SseBroadcaster {

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** Register a new subscriber for {@code jobId}. */
    public SseEmitter subscribe(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        List<SseEmitter> list = emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        return emitter;
    }

    /** Send a named event carrying a raw JSON payload to all subscribers. */
    public void broadcast(String jobId, String event, String jsonData) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(jsonData));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    /** Complete all emitters for a finished job. */
    public void complete(String jobId) {
        List<SseEmitter> list = emitters.remove(jobId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }
}
