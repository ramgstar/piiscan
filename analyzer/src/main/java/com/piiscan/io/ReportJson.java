package com.piiscan.io;

import com.piiscan.model.ScanReport;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Serializes a {@link ScanReport} to a compact JSON string.
 *
 * <p>Used both for the {@code RESULT=} marker the analyzer prints on stdout and
 * for the JSON the manager serves to its dashboard, so the two always agree on
 * shape.
 */
public final class ReportJson {

    private ReportJson() {
    }

    public static String toJson(ScanReport r, Map<String, String> patternNames, String column) {
        StringJoiner patterns = new StringJoiner(",", "[", "]");
        r.perPattern().forEach((id, s) -> patterns.add(
                "{\"id\":" + Json.quote(id)
                        + ",\"name\":" + Json.quote(patternNames.getOrDefault(id, ""))
                        + ",\"confirmedValues\":" + s.confirmedValues()
                        + ",\"confirmedRows\":" + s.confirmedRows()
                        + ",\"rejectedValues\":" + s.rejectedValues()
                        + ",\"rejectedRows\":" + s.rejectedRows()
                        + "}"));

        StringJoiner errors = new StringJoiner(",", "[", "]");
        r.errors().forEach(e -> errors.add(Json.quote(e)));

        return "{"
                + "\"column\":" + Json.quote(column)
                + ",\"valuesScanned\":" + r.valuesScanned()
                + ",\"rowsScanned\":" + r.rowsScanned()
                + ",\"batches\":" + r.batches()
                + ",\"failedBatches\":" + r.failedBatches()
                + ",\"confirmedRows\":" + r.totalConfirmedRows()
                + ",\"patterns\":" + patterns
                + ",\"errors\":" + errors
                + "}";
    }
}
