package com.piiscan.scanner.report;

import com.piiscan.io.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link FileReport} and {@link RunSummary} to JSON files in the
 * output directory.
 *
 * <p>JSON is built directly with {@link Json#quote} rather than a mapping
 * library, matching the dependency-free style used elsewhere in the pipeline.
 * Location objects are written by a small generic serializer so both CSV
 * ({@code row}/{@code col}) and JSON ({@code path}) shapes round-trip.
 */
public final class ReportWriter {

    private final Path outputDir;

    /** @param outputDir directory where report files are written (created if missing) */
    public ReportWriter(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create output directory", e);
        }
    }

    /**
     * Write a per-file report to {@code <outputDir>/<sourceName>.report.json}.
     *
     * @return the path written
     */
    public Path writePerFile(FileReport r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        field(sb, "scanId", r.scanId());
        sb.append(',');
        field(sb, "scannedAt", iso(r.scannedAt()));
        sb.append(",\"source\":{");
        field(sb, "name", r.sourceName());
        sb.append(',');
        raw(sb, "size", Long.toString(r.sourceSize()));
        sb.append(',');
        field(sb, "sha256", r.sourceSha256());
        sb.append(',');
        field(sb, "ext", r.ext());
        sb.append("},\"patternSet\":{");
        field(sb, "version", r.patternSetVersion());
        sb.append(',');
        field(sb, "sha256", r.patternSetSha256());
        sb.append("},");
        field(sb, "engineVersion", r.engineVersion());
        sb.append(',');
        raw(sb, "durationMs", Long.toString(r.durationMs()));
        sb.append(",\"summary\":{");
        raw(sb, "valuesScanned", Long.toString(r.valuesScanned()));
        sb.append(',');
        raw(sb, "rowsScanned", Long.toString(r.rowsScanned()));
        sb.append(',');
        raw(sb, "confirmed", Long.toString(r.confirmed()));
        sb.append(',');
        raw(sb, "rejected", Long.toString(r.rejected()));
        sb.append(',');
        raw(sb, "batches", Integer.toString(r.batches()));
        sb.append(',');
        raw(sb, "failedBatches", Integer.toString(r.failedBatches()));
        sb.append("},\"findings\":[");
        List<PatternFinding> findings = r.findings();
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendFinding(sb, findings.get(i));
        }
        sb.append("],\"errors\":[");
        List<String> errors = r.errors();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.quote(errors.get(i)));
        }
        sb.append("]}");

        Path out = outputDir.resolve(r.sourceName() + ".report.json");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    /**
     * Write a run summary to {@code <outputDir>/run-<runId>.summary.json}.
     *
     * @return the path written
     */
    public Path writeRunSummary(RunSummary s) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        field(sb, "runId", s.runId());
        sb.append(',');
        field(sb, "startedAt", iso(s.startedAt()));
        sb.append(',');
        field(sb, "finishedAt", iso(s.finishedAt()));
        sb.append(',');
        raw(sb, "durationMs", Long.toString(s.durationMs()));
        sb.append(",\"files\":{");
        raw(sb, "total", Integer.toString(s.filesTotal()));
        sb.append(',');
        raw(sb, "processed", Integer.toString(s.filesProcessed()));
        sb.append(',');
        raw(sb, "failed", Integer.toString(s.filesFailed()));
        sb.append("},");
        raw(sb, "confirmedTotal", Long.toString(s.confirmedTotal()));
        sb.append(",\"byPattern\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : s.byPattern().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            raw(sb, e.getKey(), Long.toString(e.getValue()));
        }
        sb.append("},\"failures\":[");
        List<RunSummary.Failure> failures = s.failures();
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            RunSummary.Failure f = failures.get(i);
            sb.append('{');
            field(sb, "file", f.file());
            sb.append(',');
            field(sb, "reason", f.reason() == null ? "" : f.reason());
            sb.append('}');
        }
        sb.append("]}");

        Path out = outputDir.resolve("run-" + s.runId() + ".summary.json");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    // ---- helpers --------------------------------------------------------

    private static void appendFinding(StringBuilder sb, PatternFinding f) {
        sb.append('{');
        field(sb, "patternId", f.patternId());
        sb.append(',');
        field(sb, "name", f.name());
        sb.append(',');
        field(sb, "validator", f.validator());
        sb.append(',');
        raw(sb, "confirmedCount", Long.toString(f.confirmedCount()));
        sb.append(',');
        field(sb, "maskedSample", f.maskedSample() == null ? "" : f.maskedSample());
        sb.append(",\"locations\":[");
        List<Object> locations = f.locations();
        for (int i = 0; i < locations.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, locations.get(i));
        }
        sb.append("]}");
    }

    /** Append {@code "key":"quoted-string-value"}. */
    private static void field(StringBuilder sb, String key, String value) {
        sb.append(Json.quote(key)).append(':').append(Json.quote(value == null ? "" : value));
    }

    /** Append {@code "key":rawValue} (numbers/booleans already serialized). */
    private static void raw(StringBuilder sb, String key, String rawValue) {
        sb.append(Json.quote(key)).append(':').append(rawValue);
    }

    private static String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    /** Generic serializer for parsed JSON values (Map/List/String/Number/Boolean/null). */
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append(Json.quote(s));
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(Json.quote(String.valueOf(e.getKey()))).append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, list.get(i));
            }
            sb.append(']');
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v.toString());
        } else {
            sb.append(Json.quote(v.toString()));
        }
    }
}
