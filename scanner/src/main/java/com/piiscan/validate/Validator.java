package com.piiscan.validate;

/**
 * A checksum validator that decides whether a regex match is a genuine hit.
 *
 * <p>Implementations receive the exact substring the engine matched and return
 * {@code true} only when the value satisfies the corresponding check digit
 * algorithm. This is what separates a real card number from any 16 digits.
 */
@FunctionalInterface
public interface Validator {

    /**
     * @param matched the substring matched by the regex (may contain separators
     *                such as {@code '-'} or spaces)
     * @return {@code true} if the value passes its checksum
     */
    boolean isValid(String matched);
}
