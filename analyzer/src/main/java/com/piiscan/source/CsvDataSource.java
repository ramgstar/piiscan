package com.piiscan.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads cell values from a simple CSV file.
 *
 * <p>The first line is treated as a header. Every cell in every data row is
 * streamed as a value, so any column can carry a hit. This is a deliberately
 * minimal CSV reader (comma-separated, optional double-quoted fields with
 * doubled quotes for escaping) — enough for demo inputs without pulling in a
 * CSV library.
 */
public final class CsvDataSource implements DataSource {

    private final Path file;
    private final String label;

    public CsvDataSource(Path file) {
        this.file = file;
        this.label = file.getFileName().toString();
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Stream<String> values() {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> cells = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) { // skip header
                if (lines.get(i).isBlank()) {
                    continue;
                }
                cells.addAll(parseRow(lines.get(i)));
            }
            return cells.stream();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read CSV " + file, e);
        }
    }

    /** Split one CSV row into fields, honoring double-quoted values. */
    public static List<String> parseRow(String row) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
