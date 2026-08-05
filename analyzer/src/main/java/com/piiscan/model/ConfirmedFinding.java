package com.piiscan.model;

/**
 * A {@link Finding} after checksum validation.
 *
 * @param finding the underlying candidate
 * @param valid   whether the checksum (or {@code none}) accepted the match
 */
public record ConfirmedFinding(Finding finding, boolean valid) {

    public String patternId() {
        return finding.patternId();
    }

    /** Row frequency this finding accounts for. */
    public long count() {
        return finding.count();
    }
}
