package com.piiscan.io;

import com.piiscan.model.ColumnValue;
import com.piiscan.model.Finding;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the JSONL wire format exchanged with the native engine.
 *
 * <p>Input batches start with a single {@code _meta} line (ignored by the
 * engine) followed by one record per distinct value. Output files contain one
 * {@link Finding} per line.
 */
public final class Jsonl {

    private Jsonl() {
    }

    /**
     * Write a batch of values to {@code file} as JSONL.
     *
     * @param file    destination path
     * @param batchId identifier recorded in the meta line
     * @param column  source column label recorded in the meta line
     * @param values  distinct values with frequencies
     */
    public static void writeBatch(Path file, int batchId, String column, List<ColumnValue> values)
            throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("{\"_meta\":{\"batch\":" + batchId + ",\"column\":" + Json.quote(column) + "}}");
            w.newLine();
            for (ColumnValue v : values) {
                w.write("{\"value\":" + Json.quote(v.value()) + ",\"count\":" + v.count() + "}");
                w.newLine();
            }
        }
    }

    /**
     * Read all findings from an engine output file.
     *
     * @param file engine output path
     * @return findings in file order (empty if the file is empty)
     */
    public static List<Finding> readFindings(Path file) throws IOException {
        List<Finding> findings = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> o = Json.parseObject(line);
                findings.add(new Finding(
                        str(o.get("patternId")),
                        str(o.getOrDefault("validator", "")),
                        str(o.get("matched")),
                        str(o.get("value")),
                        asLong(o.getOrDefault("count", 1L))
                ));
            }
        }
        return findings;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 1L;
    }
}
