package com.piiscan.manager.service;

import com.piiscan.manager.dto.ScanRequest;
import com.piiscan.manager.model.ScanJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the lifecycle of scan jobs: starts the analyzer as a child process,
 * reads its stdout markers, updates job state, and broadcasts progress over SSE.
 *
 * <p>The manager and analyzer share no code — they communicate only through the
 * process boundary (command-line arguments in, {@code PROGRESS=}/{@code RESULT=}
 * markers out), which keeps the two independently deployable.
 */
@Service
public class JobManager {

    private static final String PROGRESS = "PROGRESS=";
    private static final String RESULT = "RESULT=";

    private final SseBroadcaster broadcaster;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, ScanJob> jobs = new ConcurrentHashMap<>();

    @Value("${piiscan.java-bin:java}")
    private String javaBin;
    @Value("${piiscan.engine-path:engine/target/release/piiscan-engine}")
    private String enginePath;
    @Value("${piiscan.analyzer-jar:analyzer/target/piiscan-analyzer.jar}")
    private String analyzerJar;
    @Value("${piiscan.patterns-path:samples/patterns.json}")
    private String patternsPath;

    public JobManager(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public ScanJob start(ScanRequest req) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String source = req.isCsv() ? req.inputCsv() : req.syntheticRows() + " synthetic rows";
        ScanJob job = new ScanJob(id, source);
        jobs.put(id, job);
        workers.submit(() -> runJob(job, req));
        return job;
    }

    public ScanJob get(String id) {
        return jobs.get(id);
    }

    public Collection<ScanJob> all() {
        return jobs.values();
    }

    public boolean stop(String id) {
        ScanJob job = jobs.get(id);
        if (job == null || job.process() == null) {
            return false;
        }
        job.status(ScanJob.Status.STOPPED);
        job.process().destroy();
        broadcaster.broadcast(id, "status", "{\"status\":\"STOPPED\"}");
        broadcaster.complete(id);
        return true;
    }

    private void runJob(ScanJob job, ScanRequest req) {
        Deque<String> tail = new ArrayDeque<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command(req));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            job.process(process);
            job.status(ScanJob.Status.RUNNING);
            broadcaster.broadcast(job.id(), "status", "{\"status\":\"RUNNING\"}");

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    handleLine(job, line, tail);
                }
            }

            int exit = process.waitFor();
            finish(job, exit, tail);
        } catch (IOException e) {
            fail(job, "failed to launch analyzer: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "interrupted");
        }
    }

    private void handleLine(ScanJob job, String line, Deque<String> tail) {
        if (line.startsWith(PROGRESS)) {
            String json = line.substring(PROGRESS.length());
            job.progressJson(json);
            broadcaster.broadcast(job.id(), "progress", json);
        } else if (line.startsWith(RESULT)) {
            String json = line.substring(RESULT.length());
            job.resultJson(json);
            broadcaster.broadcast(job.id(), "result", json);
        } else if (!line.isBlank()) {
            // keep a short tail of non-marker output for error diagnostics
            tail.addLast(line);
            if (tail.size() > 10) {
                tail.removeFirst();
            }
        }
    }

    private void finish(ScanJob job, int exit, Deque<String> tail) {
        if (job.status() == ScanJob.Status.STOPPED) {
            return;
        }
        if (exit == 0) {
            job.status(ScanJob.Status.DONE);
            broadcaster.broadcast(job.id(), "done", job.resultJson() == null ? "{}" : job.resultJson());
        } else {
            fail(job, "analyzer exited with code " + exit
                    + (tail.isEmpty() ? "" : ": " + String.join(" | ", tail)));
        }
        broadcaster.complete(job.id());
    }

    private void fail(ScanJob job, String message) {
        job.status(ScanJob.Status.FAILED);
        job.error(message);
        broadcaster.broadcast(job.id(), "error",
                "{\"error\":" + jsonString(message) + "}");
        broadcaster.complete(job.id());
    }

    private List<String> command(ScanRequest req) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-jar");
        cmd.add(abs(analyzerJar));
        cmd.add("--engine");
        cmd.add(abs(enginePath));
        cmd.add("--patterns");
        cmd.add(abs(patternsPath));
        if (req.isCsv()) {
            cmd.add("--input");
            cmd.add(abs(req.inputCsv()));
        } else {
            cmd.add("--synthetic");
            cmd.add(String.valueOf(req.syntheticRows()));
        }
        cmd.add("--workers");
        cmd.add(String.valueOf(req.workers()));
        cmd.add("--batch-size");
        cmd.add(String.valueOf(req.batchSize()));
        return cmd;
    }

    private static String abs(String p) {
        return Path.of(p).toAbsolutePath().normalize().toString();
    }

    /** Minimal JSON string escaping for error messages. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
