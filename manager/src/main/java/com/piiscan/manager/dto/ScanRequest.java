package com.piiscan.manager.dto;

/**
 * Parameters for starting a scan, posted by the dashboard.
 *
 * @param mode          {@code "synthetic"} or {@code "csv"}
 * @param syntheticRows rows to generate in synthetic mode
 * @param inputCsv      CSV path in csv mode (relative to where the manager runs)
 * @param workers       consumer virtual threads
 * @param batchSize     distinct values per batch
 */
public record ScanRequest(String mode, int syntheticRows, String inputCsv, int workers, int batchSize) {

    public ScanRequest {
        if (mode == null || mode.isBlank()) {
            mode = "synthetic";
        }
        if (syntheticRows <= 0) {
            syntheticRows = 20000;
        }
        if (workers <= 0) {
            workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        }
        if (batchSize <= 0) {
            batchSize = 2000;
        }
    }

    public boolean isCsv() {
        return "csv".equalsIgnoreCase(mode) && inputCsv != null && !inputCsv.isBlank();
    }
}
