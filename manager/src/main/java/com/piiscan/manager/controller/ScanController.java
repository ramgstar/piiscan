package com.piiscan.manager.controller;

import com.piiscan.manager.dto.JobDto;
import com.piiscan.manager.dto.ScanRequest;
import com.piiscan.manager.model.ScanJob;
import com.piiscan.manager.service.JobManager;
import com.piiscan.manager.service.SseBroadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * REST + SSE API for driving scans from the dashboard.
 */
@RestController
@RequestMapping("/api/v1/scan")
public class ScanController {

    private final JobManager jobs;
    private final SseBroadcaster broadcaster;

    public ScanController(JobManager jobs, SseBroadcaster broadcaster) {
        this.jobs = jobs;
        this.broadcaster = broadcaster;
    }

    @PostMapping("/start")
    public JobDto start(@RequestBody(required = false) ScanRequest request) {
        ScanRequest req = request != null ? request
                : new ScanRequest("synthetic", 20000, null, 0, 0);
        return JobDto.of(jobs.start(req));
    }

    @GetMapping
    public List<JobDto> list() {
        return jobs.all().stream().map(JobDto::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDto> get(@PathVariable String id) {
        ScanJob job = jobs.get(id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(JobDto.of(job));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> stop(@PathVariable String id) {
        return jobs.stop(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        SseEmitter emitter = broadcaster.subscribe(id);
        // Replay current state so a late subscriber isn't left blank.
        ScanJob job = jobs.get(id);
        if (job != null) {
            try {
                if (job.progressJson() != null) {
                    emitter.send(SseEmitter.event().name("progress").data(job.progressJson()));
                }
                if (job.resultJson() != null) {
                    emitter.send(SseEmitter.event().name("result").data(job.resultJson()));
                }
            } catch (Exception ignored) {
                // client already gone; broadcaster will drop it
            }
        }
        return emitter;
    }
}
