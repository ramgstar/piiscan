package com.piiscan;

import com.piiscan.validate.ValidatorRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorRegistryTest {

    private final ValidatorRegistry registry = new ValidatorRegistry();

    @Test
    void resolvesKnownValidators() {
        assertTrue(registry.resolve("luhn").isValid("4111111111111111"));
        assertTrue(registry.resolve("kr_rrn").isValid("900101-1234568"));
        assertTrue(registry.resolve("kr_brn").isValid("220-81-62517"));
        assertFalse(registry.resolve("luhn").isValid("4111111111111112"));
    }

    @Test
    void noneValidatorAcceptsEverything() {
        assertTrue(registry.resolve("none").isValid("anything at all"));
    }

    @Test
    void unknownOrBlankHintAcceptsEverything() {
        assertTrue(registry.resolve("").isValid("x"));
        assertTrue(registry.resolve(null).isValid("x"));
        assertTrue(registry.resolve("mystery").isValid("x"));
    }
}
