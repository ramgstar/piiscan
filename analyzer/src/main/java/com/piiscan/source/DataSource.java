package com.piiscan.source;

import java.util.stream.Stream;

/**
 * A source of raw column values to scan.
 *
 * <p>Implementations expose a lazy {@link #values()} stream so large inputs are
 * never fully materialized in memory. The {@link #label()} identifies the
 * column being scanned and is recorded in each batch's metadata.
 */
public interface DataSource extends AutoCloseable {

    /** Label for the column/source being scanned, e.g. {@code "CUSTOMERS.MEMO"}. */
    String label();

    /** Lazy stream of raw cell values. */
    Stream<String> values();

    @Override
    default void close() {
    }
}
