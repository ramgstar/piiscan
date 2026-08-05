package com.piiscan.source;

import com.piiscan.validate.Checksums;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Generates a deterministic mix of synthetic values for demos and tests.
 *
 * <p>The output deliberately contains both genuine values (that pass their
 * checksum) and look-alikes (that match the regex but fail the checksum), plus
 * unrelated noise. That mix is what makes the two-stage design visible: the
 * regex stage flags all candidates, and the validation stage rejects the
 * look-alikes.
 *
 * <p>All values are fabricated from a fixed seed; none correspond to real
 * people, cards, or businesses.
 */
public final class SyntheticDataSource implements DataSource {

    private final int rows;
    private final long seed;

    public SyntheticDataSource(int rows, long seed) {
        this.rows = rows;
        this.seed = seed;
    }

    @Override
    public String label() {
        return "synthetic.SAMPLE";
    }

    @Override
    public Stream<String> values() {
        Random rnd = new Random(seed);
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            pool.add(next(rnd));
        }
        return pool.stream();
    }

    private String next(Random rnd) {
        return switch (rnd.nextInt(8)) {
            case 0 -> validRrn(rnd);
            case 1 -> invalidRrn(rnd);        // matches regex, fails checksum
            case 2 -> validCard(rnd);
            case 3 -> invalidCard(rnd);       // 16 digits, fails Luhn
            case 4 -> validBrn(rnd);
            case 5 -> "user" + rnd.nextInt(100000) + "@example.com";
            default -> noise(rnd);            // no pattern at all
        };
    }

    private String validRrn(Random rnd) {
        int[] d = new int[13];
        for (int i = 0; i < 6; i++) {
            d[i] = rnd.nextInt(10);
        }
        d[6] = 1 + rnd.nextInt(4); // gender/century digit 1-4
        for (int i = 7; i < 12; i++) {
            d[i] = rnd.nextInt(10);
        }
        int[] w = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += d[i] * w[i];
        }
        d[12] = (11 - (sum % 11)) % 10;
        return format(d, new int[]{6});
    }

    private String invalidRrn(Random rnd) {
        String valid = validRrn(rnd).replace("-", "");
        int last = valid.charAt(12) - '0';
        char bad = (char) ('0' + ((last + 1) % 10));
        String broken = valid.substring(0, 12) + bad;
        return broken.substring(0, 6) + "-" + broken.substring(6);
    }

    private String validCard(Random rnd) {
        int[] d = new int[16];
        for (int i = 0; i < 15; i++) {
            d[i] = rnd.nextInt(10);
        }
        // choose the last digit so the whole number satisfies Luhn
        for (int last = 0; last < 10; last++) {
            d[15] = last;
            if (Checksums.luhn(join(d))) {
                break;
            }
        }
        return format(d, new int[]{4, 8, 12});
    }

    private String invalidCard(Random rnd) {
        String valid = validCard(rnd).replace("-", "");
        int last = valid.charAt(15) - '0';
        char bad = (char) ('0' + ((last + 5) % 10));
        String broken = valid.substring(0, 15) + bad;
        return broken.replaceAll("(.{4})(?!$)", "$1-");
    }

    private String validBrn(Random rnd) {
        int[] d = new int[10];
        for (int i = 0; i < 9; i++) {
            d[i] = rnd.nextInt(10);
        }
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += d[i] * w[i];
        }
        sum += (d[8] * 5) / 10;
        d[9] = (10 - (sum % 10)) % 10;
        return format(d, new int[]{3, 5});
    }

    private String noise(Random rnd) {
        String[] words = {"pending", "n/a", "see notes", "approved", "N/A", "-", "confidential"};
        return words[rnd.nextInt(words.length)];
    }

    private static String join(int[] d) {
        StringBuilder sb = new StringBuilder(d.length);
        for (int x : d) {
            sb.append(x);
        }
        return sb.toString();
    }

    /** Render digits with hyphens after the given (0-based) positions. */
    private static String format(int[] d, int[] hyphenAfter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            sb.append(d[i]);
            for (int h : hyphenAfter) {
                if (i == h - 1) {
                    sb.append('-');
                }
            }
        }
        return sb.toString();
    }
}
