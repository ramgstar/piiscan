package com.piiscan.scanner.pipeline;

import com.piiscan.io.Json;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe emitter of the {@code PROGRESS=} / {@code FILE=} / {@code SUMMARY=}
 * markers that the manager parses from the scanner's stdout.
 *
 * <p>Counters are atomic and each print is synchronized on {@code this}, so lines
 * from concurrent producer/consumer threads never interleave. Only marker lines
 * go to stdout; everything else is kept off it.
 */
public final class ProgressReporter {

    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong confirmed = new AtomicLong();

    /** Set the total number of files for this run. */
    public void setTotal(int n) {
        total.set(n);
    }

    /** Mark a file as started (increments in-flight); emits a PROGRESS line. */
    public void startFile() {
        inFlight.incrementAndGet();
        progress("started");
    }

    /**
     * Mark a file as finished.
     *
     * @param confirmedForFile confirmed occurrences contributed by this file
     */
    public void finishFile(long confirmedForFile) {
        inFlight.decrementAndGet();
        completed.incrementAndGet();
        confirmed.addAndGet(confirmedForFile);
        progress("finished");
    }

    /** Emit a {@code PROGRESS=} marker with the current counters and a stage label. */
    public synchronized void progress(String stage) {
        String json = "{\"total\":" + total.get()
                + ",\"completed\":" + completed.get()
                + ",\"inFlight\":" + inFlight.get()
                + ",\"stage\":" + Json.quote(stage == null ? "" : stage)
                + ",\"confirmed\":" + confirmed.get()
                + "}";
        emit(Markers.PROGRESS + json);
    }

    /** Emit a {@code FILE=} marker for a completed file. */
    public synchronized void file(String name, String status, long confirmedForFile, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(Json.quote(name == null ? "" : name));
        sb.append(",\"status\":").append(Json.quote(status == null ? "" : status));
        sb.append(",\"confirmed\":").append(confirmedForFile);
        if (reason != null && !reason.isEmpty()) {
            sb.append(",\"reason\":").append(Json.quote(reason));
        }
        sb.append('}');
        emit(Markers.FILE + sb);
    }

    /** Emit a {@code SUMMARY=} marker with a pre-built JSON body. */
    public synchronized void summary(String json) {
        emit(Markers.SUMMARY + json);
    }

    private void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
