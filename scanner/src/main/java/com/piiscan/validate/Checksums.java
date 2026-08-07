package com.piiscan.validate;

/**
 * Pure checksum algorithms used to confirm candidate matches.
 *
 * <p>Every method strips non-digit separators first, so callers may pass values
 * with or without hyphens/spaces. Each returns {@code false} rather than
 * throwing on malformed input.
 */
public final class Checksums {

    private Checksums() {
    }

    /** Keep only ASCII digits. */
    static int[] digits(String s) {
        int[] tmp = new int[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                tmp[n++] = c - '0';
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * Luhn (mod-10) check, used by most credit/debit card numbers.
     *
     * @return {@code true} when the 13–19 digit value satisfies Luhn
     */
    public static boolean luhn(String value) {
        int[] d = digits(value);
        if (d.length < 13 || d.length > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = d.length - 1; i >= 0; i--) {
            int x = d[i];
            if (doubleIt) {
                x *= 2;
                if (x > 9) {
                    x -= 9;
                }
            }
            sum += x;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /**
     * Korean resident registration number (주민등록번호) check digit.
     *
     * <p>13 digits; the last is a check digit over the first 12 with weights
     * {@code 2,3,4,5,6,7,8,9,2,3,4,5}.
     */
    public static boolean koreanRrn(String value) {
        int[] d = digits(value);
        if (d.length != 13) {
            return false;
        }
        int[] w = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += d[i] * w[i];
        }
        int check = (11 - (sum % 11)) % 10;
        return check == d[12];
    }

    /**
     * Korean business registration number (사업자등록번호) check digit.
     *
     * <p>10 digits; weights {@code 1,3,7,1,3,7,1,3,5} over the first nine, with a
     * carry term applied to the ninth digit before the mod-10 check.
     */
    public static boolean koreanBrn(String value) {
        int[] d = digits(value);
        if (d.length != 10) {
            return false;
        }
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += d[i] * w[i];
        }
        sum += (d[8] * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        return check == d[9];
    }
}
