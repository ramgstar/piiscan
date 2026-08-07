package com.piiscan.scanner.report;

import java.util.List;

/**
 * One confirmed pattern's contribution to a per-file report.
 *
 * @param patternId      id of the pattern (e.g. {@code "CARD"})
 * @param name           human-readable pattern name
 * @param validator      validator hint that confirmed the matches
 * @param confirmedCount total confirmed occurrences (sum of finding counts)
 * @param maskedSample   one masked representative value
 * @param locations      sample locations (raw parsed location objects: CSV
 *                       {@code {row,col}} or JSON {@code {path}})
 */
public record PatternFinding(
        String patternId,
        String name,
        String validator,
        long confirmedCount,
        String maskedSample,
        List<Object> locations) {
}
