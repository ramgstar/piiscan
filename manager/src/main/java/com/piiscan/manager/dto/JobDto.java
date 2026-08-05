package com.piiscan.manager.dto;

import com.piiscan.manager.model.ScanJob;

/**
 * JSON view of a {@link ScanJob} returned by the REST API.
 *
 * <p>{@code progress} and {@code result} are the raw JSON payloads emitted by
 * the analyzer; the browser parses them directly.
 */
public record JobDto(String id, String status, String source, String progress, String result, String error) {

    public static JobDto of(ScanJob job) {
        return new JobDto(
                job.id(),
                job.status().name(),
                job.source(),
                job.progressJson(),
                job.resultJson(),
                job.error());
    }
}
