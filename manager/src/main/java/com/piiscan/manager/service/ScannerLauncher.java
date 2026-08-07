package com.piiscan.manager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launches the scanner as a child process and turns its stdout markers into
 * live SSE events plus in-memory status.
 *
 * <p>The manager and the scanner share no code — they communicate only across the
 * process boundary: a command line in ({@code java -jar <scanner-jar> --run-id
 * <id>}) and single-line {@code PROGRESS=}/{@code FILE=}/{@code SUMMARY=} markers
 * out. The marker prefixes below are declared locally on purpose; importing them
 * from the scanner module would couple the two artifacts.
 *
 * <p>Only one run may be in flight at a time; {@link RunState} enforces that so a
 * fast-firing scheduler cannot stack overlapping scanner processes.
 */
@Service
public class ScannerLauncher {

    private static final Logger log = LoggerFactory.getLogger(ScannerLauncher.class);

    // --- stdout marker protocol (kept in sync with the scanner by contract only) ---
    /** Progress heartbeat: {@code {"total":N,"completed":M,"inFlight":k,"stage":"…","confirmed":c}}. */
    static final String PROGRESS = "PROGRESS=";
    /** Per-file result: {@code {"name":"…","status":"processed|failed","confirmed":c,"reason":"…"}}. */
    static final String FILE = "FILE=";
    /** Final run summary JSON emitted once at the end. */
    static final String SUMMARY = "SUMMARY=";

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RunState runState;
    private final SseBroadcaster broadcaster;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${piiscan.scanner-jar:scanner/target/piiscan-scanner.jar}")
    private String scannerJar;
    @Value("${piiscan.java-bin:java}")
    private String javaBin;

    // Latest state, read by the /status and /summary endpoints and replayed to
    // late SSE subscribers. Volatile: written by the reader virtual thread, read
    // by request threads.
    private volatile String currentRunId;
    private volatile String latestProgressJson;
    private volatile String lastSummaryJson;

    public ScannerLauncher(RunState runState, SseBroadcaster broadcaster) {
        this.runState = runState;
        this.broadcaster = broadcaster;
    }

    /**
     * Launches a scanner run if none is currently in progress.
     *
     * @return the new run id, or empty if a run was already in flight
     */
    public Optional<String> launchIfIdle() {
        if (!runState.tryAcquire()) {
            return Optional.empty();
        }
        String runId = LocalDateTime.now().format(RUN_ID_FMT);
        currentRunId = runId;
        latestProgressJson = null; // clear stale progress from the previous run
        readers.submit(() -> runScanner(runId));
        log.info("scanner run {} launched", runId);
        return Optional.of(runId);
    }

    /** @return {@code true} while a scanner process is running. */
    public boolean isRunning() {
        return runState.isRunning();
    }

    /** @return the id of the current or most recent run, or {@code null} if none yet. */
    public String currentRunId() {
        return currentRunId;
    }

    /** @return the latest {@code PROGRESS=} payload (raw JSON), or {@code null}. */
    public String latestProgressJson() {
        return latestProgressJson;
    }

    /** @return the last {@code SUMMARY=} payload (raw JSON), or {@code null}. */
    public String lastSummaryJson() {
        return lastSummaryJson;
    }

    private void runScanner(String runId) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaBin, "-jar", scannerJarAbsPath(), "--run-id", runId);
            // Working dir is the manager's working dir (repo root by convention) so
            // the scanner finds scanFiles/engine/patterns via its own config.
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                }
            }

            int exit = process.waitFor();
            log.info("scanner run {} exited with code {}", runId, exit);
        } catch (IOException e) {
            log.error("scanner run {} failed to launch/read: {}", runId, e.getMessage());
            broadcaster.broadcast("error",
                    "{\"runId\":\"" + runId + "\",\"error\":\"failed to launch scanner\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("scanner run {} interrupted", runId);
        } finally {
            runState.release();
            broadcaster.complete(runId);
        }
    }

    private void handleLine(String line) {
        if (line.startsWith(PROGRESS)) {
            String json = line.substring(PROGRESS.length());
            latestProgressJson = json;
            broadcaster.broadcast("progress", json);
        } else if (line.startsWith(FILE)) {
            broadcaster.broadcast("file", line.substring(FILE.length()));
        } else if (line.startsWith(SUMMARY)) {
            String json = line.substring(SUMMARY.length());
            lastSummaryJson = json;
            broadcaster.broadcast("summary", json);
        }
        // Non-marker lines (plain scanner logs) are ignored on purpose.
    }

    private String scannerJarAbsPath() {
        return Path.of(scannerJar).toAbsolutePath().normalize().toString();
    }
}
