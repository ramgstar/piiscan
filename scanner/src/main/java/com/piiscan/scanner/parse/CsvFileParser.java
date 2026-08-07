package com.piiscan.scanner.parse;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CSV parser backed by Apache Commons CSV (1.11.0). The first row is treated as
 * the header; each mapped cell is emitted with a {@link Location#csv} position.
 */
public final class CsvFileParser implements Parser {

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public void parse(Path file, CellConsumer sink) throws IOException {
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .build();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser p = CSVParser.parse(r, fmt)) {
            for (CSVRecord rec : p) {
                for (String h : p.getHeaderNames()) {
                    String v = rec.isMapped(h) ? rec.get(h) : null;
                    if (v != null) {
                        sink.accept(v, Location.csv((int) rec.getRecordNumber(), h));
                    }
                }
            }
        }
    }
}
