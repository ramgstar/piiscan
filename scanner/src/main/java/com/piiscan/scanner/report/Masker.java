package com.piiscan.scanner.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Masks discovered values before they are written to a report, so raw PII never
 * leaves the scanner.
 *
 * <ul>
 *   <li>{@link Policy#FULL} — every character replaced by {@code '*'} (length kept).</li>
 *   <li>{@link Policy#PARTIAL} — keep the leading {@code ceil(len * 0.4)} characters,
 *       mask the rest with {@code '*'}.</li>
 *   <li>{@link Policy#HASH} — {@code "sha256:"} followed by the hex SHA-256 of the value.</li>
 * </ul>
 */
public final class Masker {

    /** Masking strategy. */
    public enum Policy {
        FULL, PARTIAL, HASH
    }

    private final Policy policy;

    /**
     * @param policy policy name ({@code full}, {@code partial}, {@code hash});
     *               unknown or {@code null} falls back to {@link Policy#PARTIAL}
     */
    public Masker(String policy) {
        this.policy = parse(policy);
    }

    private static Policy parse(String policy) {
        if (policy == null || policy.isBlank()) {
            return Policy.PARTIAL;
        }
        try {
            return Policy.valueOf(policy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Policy.PARTIAL;
        }
    }

    /** @return the active policy. */
    public Policy policy() {
        return policy;
    }

    /**
     * Mask a value according to the configured policy.
     *
     * @param value raw value (may be {@code null})
     * @return the masked representation ({@code null} maps to {@code ""})
     */
    public String mask(String value) {
        if (value == null) {
            return "";
        }
        return switch (policy) {
            case FULL -> "*".repeat(value.length());
            case PARTIAL -> partial(value);
            case HASH -> "sha256:" + sha256Hex(value);
        };
    }

    private static String partial(String value) {
        int len = value.length();
        if (len == 0) {
            return "";
        }
        int keep = (int) Math.ceil(len * 0.4);
        if (keep >= len) {
            return value;
        }
        return value.substring(0, keep) + "*".repeat(len - keep);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
