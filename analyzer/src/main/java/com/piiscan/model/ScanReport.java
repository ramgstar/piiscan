package com.piiscan.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable summary of a completed scan.
 *
 * @param valuesScanned distinct values sent to the engine
 * @param rowsScanned   total source rows those values represent
 * @param batches       number of batches processed
 * @param failedBatches batches that errored (and were skipped)
 * @param perPattern    per-pattern statistics keyed by pattern id
 * @param errors        human-readable messages for failed batches
 */
public record ScanReport(
        long valuesScanned,
        long rowsScanned,
        int batches,
        int failedBatches,
        Map<String, PatternStat> perPattern,
        List<String> errors) {

    /**
     * Counts for one pattern.
     *
     * @param confirmedValues distinct values that matched and passed the checksum
     * @param confirmedRows   source rows those confirmed values represent
     * @param rejectedValues  distinct values that matched but failed the checksum
     * @param rejectedRows    source rows those rejected values represent
     */
    public record PatternStat(
            long confirmedValues,
            long confirmedRows,
            long rejectedValues,
            long rejectedRows) {
    }

    /** Total confirmed rows across all patterns. */
    public long totalConfirmedRows() {
        return perPattern.values().stream().mapToLong(PatternStat::confirmedRows).sum();
    }
}
