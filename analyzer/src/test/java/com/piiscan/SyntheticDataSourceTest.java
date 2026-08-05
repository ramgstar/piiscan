package com.piiscan;

import com.piiscan.source.SyntheticDataSource;
import com.piiscan.validate.Checksums;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDataSourceTest {

    private static final Pattern CARD = Pattern.compile("\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}");

    @Test
    void isDeterministicForAGivenSeed() {
        List<String> a = new SyntheticDataSource(500, 42).values().collect(Collectors.toList());
        List<String> b = new SyntheticDataSource(500, 42).values().collect(Collectors.toList());
        assertEquals(a, b);
        assertEquals(500, a.size());
    }

    @Test
    void producesBothValidAndInvalidLookalikes() {
        List<String> values = new SyntheticDataSource(4000, 7).values().collect(Collectors.toList());

        long validCards = values.stream()
                .filter(v -> CARD.matcher(v).matches())
                .filter(Checksums::luhn)
                .count();
        long invalidCards = values.stream()
                .filter(v -> CARD.matcher(v).matches())
                .filter(v -> !Checksums.luhn(v))
                .count();

        // The whole point of the demo data: some genuine values and some
        // look-alikes that only the validation stage can tell apart.
        assertTrue(validCards > 0, "expected some Luhn-valid cards");
        assertTrue(invalidCards > 0, "expected some Luhn-invalid look-alikes");
    }
}
