package com.piiscan.model;

/**
 * A distinct column value together with how many rows held it.
 *
 * <p>The producer collapses identical values into one {@code ColumnValue} with
 * an aggregated {@code count}, so the engine matches each distinct value once
 * regardless of how often it occurs in the source.
 *
 * @param value the raw column value
 * @param count number of source rows that held this value (&ge; 1)
 */
public record ColumnValue(String value, long count) {
}
