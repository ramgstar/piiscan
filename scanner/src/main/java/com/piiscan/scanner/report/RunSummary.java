package com.piiscan.scanner.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregated summary of a whole run, serialized to
 * {@code <outputDir>/run-<runId>.summary.json} by {@link ReportWriter}.
 *
 * @param runId          run identifier
 * @param startedAt      run start (serialized as ISO-8601 UTC)
 * @param finishedAt     run finish (serialized as ISO-8601 UTC)
 * @param durationMs     wall-clock run duration in milliseconds
 * @param filesTotal     files claimed for this run
 * @param filesProcessed files that completed successfully
 * @param filesFailed    files that failed
 * @param confirmedTotal total confirmed occurrences across all files
 * @param byPattern      confirmed occurrences keyed by pattern id
 * @param failures       per-file failure reasons
 */
public record RunSummary(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        int filesTotal,
        int filesProcessed,
        int filesFailed,
        long confirmedTotal,
        Map<String, Long> byPattern,
        List<Failure> failures) {

    /**
     * A single file-level failure.
     *
     * @param file   the file name that failed
     * @param reason the failure reason
     */
    public record Failure(String file, String reason) {
    }
}
