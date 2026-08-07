package com.piiscan.scanner.report;

import java.time.Instant;
import java.util.List;

/**
 * Structured per-file scan report, serialized to
 * {@code <outputDir>/<sourceName>.report.json} by {@link ReportWriter}.
 *
 * @param scanId            identifier of this file's processing
 * @param scannedAt         when scanning completed (serialized as ISO-8601 UTC)
 * @param sourceName        original file name
 * @param sourceSize        original file size in bytes
 * @param sourceSha256      hex SHA-256 of the original file bytes
 * @param ext               lowercase extension
 * @param patternSetVersion version of the pattern set used
 * @param patternSetSha256  hex SHA-256 of the pattern set file
 * @param engineVersion     native engine version string
 * @param durationMs        wall-clock processing time in milliseconds
 * @param valuesScanned     number of distinct values scanned
 * @param rowsScanned       total value occurrences scanned
 * @param confirmed         total confirmed occurrences across all patterns
 * @param rejected          total occurrences rejected by validators
 * @param batches           number of engine batches run
 * @param failedBatches     number of batches that failed
 * @param findings          per-pattern confirmed findings
 * @param errors            non-fatal errors encountered while scanning this file
 */
public record FileReport(
        String scanId,
        Instant scannedAt,
        String sourceName,
        long sourceSize,
        String sourceSha256,
        String ext,
        String patternSetVersion,
        String patternSetSha256,
        String engineVersion,
        long durationMs,
        long valuesScanned,
        long rowsScanned,
        long confirmed,
        long rejected,
        int batches,
        int failedBatches,
        List<PatternFinding> findings,
        List<String> errors) {
}
