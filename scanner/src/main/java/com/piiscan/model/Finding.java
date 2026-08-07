package com.piiscan.model;

/**
 * A candidate finding emitted by the native engine.
 *
 * <p>This is only a candidate — the regex matched, but the checksum implied by
 * {@code validator} has not yet been applied. Confirmation happens in the Java
 * stage, producing a {@link ConfirmedFinding}.
 *
 * @param patternId id of the pattern that matched
 * @param validator checksum hint carried from the pattern definition
 * @param matched   the exact substring that matched the regex
 * @param value     the full original column value
 * @param count     frequency carried from the input record
 */
public record Finding(String patternId, String validator, String matched, String value, long count) {
}
