package com.piiscan.manager.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans server-sent events out to every browser watching the dashboard.
 *
 * <p>There is only ever one scanner run at a time (guarded by {@link RunState}),
 * so this is a single global stream rather than a per-job one. Emitters are held
 * in a list; a dead one (client navigated away) is dropped on the first failed
 * send.
 */
@Component
public class SseBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Registers a new subscriber for the live stream. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Sends a named event carrying a raw JSON payload to all subscribers. */
    public void broadcast(String event, String jsonData) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(jsonData));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Signals the end of a run to subscribers and completes every emitter so the
     * browser's {@code EventSource} stops reconnecting.
     *
     * @param runId the run that just finished, echoed back as an {@code end} event
     */
    public void complete(String runId) {
        broadcast("end", "{\"runId\":\"" + runId + "\"}");
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed/aborted; nothing to do
            }
        }
        emitters.clear();
    }
}
