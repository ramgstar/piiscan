package com.piiscan.validate;

import java.util.Map;

/**
 * Maps the {@code validator} hint carried by each finding to a concrete
 * {@link Validator}.
 *
 * <p>An unknown or empty hint (including {@code "none"}) resolves to a validator
 * that accepts everything, so patterns without a checksum — such as email — are
 * confirmed on the regex match alone.
 */
public final class ValidatorRegistry {

    private static final Validator ACCEPT_ALL = matched -> true;

    private final Map<String, Validator> byName = Map.of(
            "luhn", Checksums::luhn,
            "kr_rrn", Checksums::koreanRrn,
            "kr_brn", Checksums::koreanBrn,
            "none", ACCEPT_ALL
    );

    /**
     * Resolve a validator by hint. Never returns {@code null}.
     *
     * @param hint validator name from the pattern definition (may be {@code null})
     * @return the matching validator, or an accept-all validator when unknown
     */
    public Validator resolve(String hint) {
        if (hint == null || hint.isBlank()) {
            return ACCEPT_ALL;
        }
        return byName.getOrDefault(hint, ACCEPT_ALL);
    }
}
