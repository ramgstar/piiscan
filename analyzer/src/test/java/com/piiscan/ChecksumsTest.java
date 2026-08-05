package com.piiscan;

import com.piiscan.validate.Checksums;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksumsTest {

    @Test
    void luhnAcceptsKnownValidCards() {
        assertTrue(Checksums.luhn("4111111111111111"));      // classic test Visa
        assertTrue(Checksums.luhn("4242-4242-4242-4242"));   // separators ignored
        assertTrue(Checksums.luhn("5555 5555 5555 4444"));   // spaces ignored
    }

    @Test
    void luhnRejectsBadCards() {
        assertFalse(Checksums.luhn("4111111111111112"));     // last digit off by one
        assertFalse(Checksums.luhn("1234567890123456"));
    }

    @Test
    void luhnRejectsWrongLength() {
        assertFalse(Checksums.luhn("41111111"));             // too short
        assertFalse(Checksums.luhn("41111111111111111111")); // too long
    }

    @Test
    void koreanRrnAcceptsValidCheckDigit() {
        assertTrue(Checksums.koreanRrn("900101-1234568"));
        assertTrue(Checksums.koreanRrn("9001011234568"));    // without hyphen
    }

    @Test
    void koreanRrnRejectsWrongCheckDigit() {
        assertFalse(Checksums.koreanRrn("900101-1234560"));
        assertFalse(Checksums.koreanRrn("900101-1234567"));
    }

    @Test
    void koreanRrnRejectsWrongLength() {
        assertFalse(Checksums.koreanRrn("90010112345"));     // 11 digits
    }

    @Test
    void koreanBrnAcceptsValidCheckDigit() {
        assertTrue(Checksums.koreanBrn("220-81-62517"));
        assertTrue(Checksums.koreanBrn("2208162517"));
    }

    @Test
    void koreanBrnRejectsWrongCheckDigit() {
        assertFalse(Checksums.koreanBrn("220-81-62518"));
    }

    @Test
    void koreanBrnRejectsWrongLength() {
        assertFalse(Checksums.koreanBrn("220816251"));       // 9 digits
    }
}
