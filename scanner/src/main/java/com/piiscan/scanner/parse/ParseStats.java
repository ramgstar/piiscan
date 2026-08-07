package com.piiscan.scanner.parse;

/**
 * Summary of one parse run.
 *
 * @param distinctValues number of distinct (deduped) values written
 * @param totalCells     total number of value occurrences seen (sum of counts)
 */
public record ParseStats(long distinctValues, long totalCells) {
}
