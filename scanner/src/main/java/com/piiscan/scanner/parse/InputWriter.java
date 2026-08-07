package com.piiscan.scanner.parse;

import com.piiscan.io.Json;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregating {@link CellConsumer} that dedups values and serializes them as a
 * unified input JSONL file (one JSON object per distinct value).
 *
 * <p>Blank/whitespace-only values are skipped. Each distinct value keeps a total
 * occurrence count and up to {@code sampleLocations} location samples. A single
 * instance is driven by one single-threaded producer, so no synchronization is used.
 */
public final class InputWriter implements CellConsumer {

    private final int sampleLocations;
    private final Map<String, Agg> values = new LinkedHashMap<>();

    /** @param sampleLocations maximum number of location samples kept per distinct value */
    public InputWriter(int sampleLocations) {
        this.sampleLocations = sampleLocations;
    }

    @Override
    public void accept(String value, Location location) {
        if (value == null) {
            return;
        }
        if (value.trim().isEmpty()) {
            return;
        }
        Agg agg = values.computeIfAbsent(value, k -> new Agg());
        agg.count++;
        if (agg.locations.size() < sampleLocations) {
            agg.locations.add(location);
        }
    }

    /**
     * Write the aggregated values to {@code out} as JSONL (one object per line).
     *
     * @return stats describing the number of distinct values and total occurrences
     * @throws IOException on write failure
     */
    public ParseStats write(Path out) throws IOException {
        long total = 0;
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Agg> e : values.entrySet()) {
                Agg agg = e.getValue();
                total += agg.count;
                w.write(line(e.getKey(), agg));
                w.write('\n');
            }
        }
        return new ParseStats(values.size(), total);
    }

    private static String line(String value, Agg agg) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"value\":").append(Json.quote(value));
        sb.append(",\"count\":").append(agg.count);
        sb.append(",\"locations\":[");
        for (int i = 0; i < agg.locations.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendLocation(sb, agg.locations.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Emit only the non-null fields of a location: CSV -> row/col, JSON -> path. */
    private static void appendLocation(StringBuilder sb, Location loc) {
        sb.append('{');
        boolean first = true;
        if (loc.row() != null) {
            sb.append("\"row\":").append(loc.row());
            first = false;
        }
        if (loc.col() != null) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"col\":").append(Json.quote(loc.col()));
            first = false;
        }
        if (loc.path() != null) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"path\":").append(Json.quote(loc.path()));
        }
        sb.append('}');
    }

    /** Mutable per-value accumulator. */
    private static final class Agg {
        private long count;
        private final List<Location> locations = new ArrayList<>();
    }
}
