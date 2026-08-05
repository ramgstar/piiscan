package com.piiscan.manager.model;

import java.time.Instant;

/**
 * In-memory state for one scan job.
 *
 * <p>Mutable fields are {@code volatile} because they are written by the thread
 * reading the analyzer's stdout and read by request threads serving the
 * dashboard. Job state is intentionally kept in memory only — this is a demo
 * manager, not a durable scheduler.
 */
public class ScanJob {

    public enum Status {PENDING, RUNNING, DONE, FAILED, STOPPED}

    private final String id;
    private final String source;
    private final Instant startedAt = Instant.now();

    private volatile Status status = Status.PENDING;
    private volatile String progressJson; // latest PROGRESS= payload (raw JSON)
    private volatile String resultJson;   // final RESULT= payload (raw JSON)
    private volatile String error;
    private volatile transient Process process;

    public ScanJob(String id, String source) {
        this.id = id;
        this.source = source;
    }

    public String id() {
        return id;
    }

    public String source() {
        return source;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Status status() {
        return status;
    }

    public void status(Status s) {
        this.status = s;
    }

    public String progressJson() {
        return progressJson;
    }

    public void progressJson(String json) {
        this.progressJson = json;
    }

    public String resultJson() {
        return resultJson;
    }

    public void resultJson(String json) {
        this.resultJson = json;
    }

    public String error() {
        return error;
    }

    public void error(String error) {
        this.error = error;
    }

    public Process process() {
        return process;
    }

    public void process(Process process) {
        this.process = process;
    }
}
