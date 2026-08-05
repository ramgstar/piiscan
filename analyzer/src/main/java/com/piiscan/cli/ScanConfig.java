package com.piiscan.cli;

import java.nio.file.Path;

/**
 * Everything a single scan run needs. Built from CLI arguments by both the
 * plain {@code Main} entry point and the Spring Boot analyzer application.
 *
 * @param enginePath   path to the native engine binary
 * @param patternsPath shared patterns JSON
 * @param inputCsv     CSV file to scan, or {@code null} to use synthetic data
 * @param syntheticRows number of synthetic rows when {@code inputCsv} is null
 * @param workers      consumer virtual threads
 * @param batchSize    distinct values per batch
 * @param seed         synthetic RNG seed
 */
public record ScanConfig(
        Path enginePath,
        Path patternsPath,
        Path inputCsv,
        int syntheticRows,
        int workers,
        int batchSize,
        long seed) {
}
