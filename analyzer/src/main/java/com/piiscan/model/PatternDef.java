package com.piiscan.model;

/**
 * A pattern definition as it appears in the shared {@code patterns.json}.
 *
 * <p>The Java stage only needs the {@code validator} hint; the {@code regex} is
 * consumed by the native engine. The record is still loaded in full so the
 * pipeline can report pattern names in its summary.
 *
 * @param id        stable identifier, e.g. {@code "KR_RRN"}
 * @param name      human-readable name
 * @param regex     regular expression used by the native engine
 * @param validator checksum hint: {@code kr_rrn}, {@code luhn}, {@code kr_brn}
 *                  or {@code none}
 */
public record PatternDef(String id, String name, String regex, String validator) {
}
