package com.piiscan.manager.dto;

/**
 * JSON view of the manager's current run state returned by {@code GET /status}.
 *
 * <p>{@code progress} is the scanner's latest {@code PROGRESS=} payload as a JSON
 * string; the browser parses it. It is kept as a {@code String} (rather than an
 * inlined object via {@code @JsonRawValue}) to avoid coupling to a specific
 * Jackson version's raw-value handling under Spring Boot 4.
 *
 * @param running  whether a scanner process is in flight
 * @param runId    the current or most recent run id, or {@code null} if none yet
 * @param progress the latest {@code PROGRESS=} payload as a JSON string, or {@code null}
 */
public record StatusDto(boolean running, String runId, String progress) {
}
