package com.piiscan.manager.controller;

import com.piiscan.manager.dto.StatusDto;
import com.piiscan.manager.service.ScannerLauncher;
import com.piiscan.manager.service.SseBroadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

/**
 * REST + SSE API for driving and observing scans from the dashboard.
 *
 * <p>A scan is a singleton at any moment (see {@link ScannerLauncher} /
 * {@code RunState}), so these endpoints talk about "the" current run rather than
 * a collection of jobs.
 */
@RestController
@RequestMapping("/api/v1/scan")
public class ScanController {

    private final ScannerLauncher launcher;
    private final SseBroadcaster broadcaster;

    public ScanController(ScannerLauncher launcher, SseBroadcaster broadcaster) {
        this.launcher = launcher;
        this.broadcaster = broadcaster;
    }

    /**
     * Starts a scan on demand.
     *
     * @return {@code 202 Accepted} with the new run id, or {@code 409 Conflict} if
     *         a scan is already running
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run() {
        Optional<String> runId = launcher.launchIfIdle();
        return runId
                .map(id -> ResponseEntity.accepted().body(Map.of("runId", id)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "a scan is already running")));
    }

    /** @return the current run state plus the latest raw progress payload. */
    @GetMapping("/status")
    public StatusDto status() {
        return new StatusDto(
                launcher.isRunning(),
                launcher.currentRunId(),
                launcher.latestProgressJson());
    }

    /**
     * @return the last run's summary JSON, or {@code 204 No Content} if no run has
     *         completed since startup
     */
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> summary() {
        String json = launcher.lastSummaryJson();
        return json == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(json);
    }

    /**
     * Subscribes to the live event stream. Late subscribers get the latest
     * progress and summary replayed immediately so the dashboard is never blank.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = broadcaster.subscribe();
        try {
            String progress = launcher.latestProgressJson();
            if (progress != null) {
                emitter.send(SseEmitter.event().name("progress").data(progress));
            }
            String summary = launcher.lastSummaryJson();
            if (summary != null) {
                emitter.send(SseEmitter.event().name("summary").data(summary));
            }
        } catch (Exception ignored) {
            // client already gone; broadcaster will drop the emitter on next send
        }
        return emitter;
    }
}
