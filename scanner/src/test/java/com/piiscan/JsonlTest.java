package com.piiscan;

import com.piiscan.io.Jsonl;
import com.piiscan.model.ColumnValue;
import com.piiscan.model.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTest {

    @Test
    void writesMetaLineThenRecords(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("batch.jsonl");
        Jsonl.writeBatch(file, 7, "CUSTOMERS.MEMO", List.of(
                new ColumnValue("901231-1234567", 3),
                new ColumnValue("has \"quotes\"", 1)));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"_meta\""));
        assertTrue(lines.get(0).contains("\"batch\":7"));
        assertTrue(lines.get(1).contains("\"value\":\"901231-1234567\""));
        assertTrue(lines.get(1).contains("\"count\":3"));
        // special characters must be JSON-escaped
        assertTrue(lines.get(2).contains("\\\"quotes\\\""));
    }

    @Test
    void readsFindingsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.jsonl");
        Files.writeString(file, """
                {"patternId":"CARD","validator":"luhn","matched":"4111111111111111","value":"card 4111111111111111","count":2}
                {"patternId":"EMAIL","validator":"none","matched":"a@b.co","value":"a@b.co","count":5}
                """);

        List<Finding> findings = Jsonl.readFindings(file);
        assertEquals(2, findings.size());
        Finding card = findings.get(0);
        assertEquals("CARD", card.patternId());
        assertEquals("luhn", card.validator());
        assertEquals("4111111111111111", card.matched());
        assertEquals(2, card.count());
        assertEquals("EMAIL", findings.get(1).patternId());
    }
}
