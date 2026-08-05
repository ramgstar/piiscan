package com.piiscan.pipeline;

import java.nio.file.Path;

/**
 * A unit of work handed from the producer to a consumer: one JSONL input file
 * to scan and the output path the engine should write findings to.
 *
 * @param id     monotonic batch number
 * @param column source column label
 * @param input  JSONL batch file the producer wrote
 * @param output path for the engine's findings
 */
record Batch(int id, String column, Path input, Path output) {

    /** Sentinel enqueued once per consumer to signal shutdown. */
    static final Batch POISON = new Batch(-1, "", null, null);

    boolean isPoison() {
        return this == POISON;
    }
}
