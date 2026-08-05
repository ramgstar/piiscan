package com.piiscan.pipeline;

/**
 * Notified once per completed batch so a caller can surface live progress —
 * for example by printing a marker line the manager parses and forwards to a
 * dashboard over SSE.
 *
 * <p>Invoked from consumer threads, so implementations must be thread-safe.
 */
@FunctionalInterface
public interface ProgressListener {

    /** A no-op listener. */
    ProgressListener NONE = (batchesDone, valuesDone, confirmedRows) -> {
    };

    /**
     * @param batchesDone   batches finished so far
     * @param valuesDone    distinct values processed so far
     * @param confirmedRows confirmed PII rows so far
     */
    void onBatch(int batchesDone, long valuesDone, long confirmedRows);
}
