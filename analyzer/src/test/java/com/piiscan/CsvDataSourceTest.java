package com.piiscan;

import com.piiscan.source.CsvDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvDataSourceTest {

    @Test
    void splitsPlainRow() {
        assertEquals(List.of("a", "b", "c"), CsvDataSource.parseRow("a,b,c"));
    }

    @Test
    void honorsQuotedFieldsWithCommas() {
        assertEquals(List.of("a", "b,c", "d"), CsvDataSource.parseRow("a,\"b,c\",d"));
    }

    @Test
    void unescapesDoubledQuotes() {
        assertEquals(List.of("say \"hi\""), CsvDataSource.parseRow("\"say \"\"hi\"\"\""));
    }

    @Test
    void keepsEmptyTrailingField() {
        assertEquals(List.of("a", "", ""), CsvDataSource.parseRow("a,,"));
    }
}
