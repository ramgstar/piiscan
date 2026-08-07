package com.piiscan;

import com.piiscan.io.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesFlatObject() {
        Map<String, Object> o = Json.parseObject(
                "{\"patternId\":\"CARD\",\"count\":7,\"ok\":true}");
        assertEquals("CARD", o.get("patternId"));
        assertEquals(7L, o.get("count"));
        assertEquals(Boolean.TRUE, o.get("ok"));
    }

    @Test
    void parsesArraysAndNesting() {
        Object v = Json.parse("[{\"a\":1},{\"a\":2.5}]");
        assertInstanceOf(List.class, v);
        List<?> list = (List<?>) v;
        assertEquals(2, list.size());
        assertEquals(2.5, ((Map<?, ?>) list.get(1)).get("a"));
    }

    @Test
    void handlesEscapesBothWays() {
        String raw = "he said \"hi\"\tand \\ slid\ndown";
        String quoted = Json.quote(raw);
        // round-trips through a parse of a one-field object
        Map<String, Object> o = Json.parseObject("{\"v\":" + quoted + "}");
        assertEquals(raw, o.get("v"));
    }

    @Test
    void quoteEscapesControlCharacters() {
        assertTrue(Json.quote("a\nb").contains("\\n"));
        assertTrue(Json.quote("a\"b").contains("\\\""));
        assertFalse(Json.quote("plain").contains("\\"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} extra"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,2"));
    }
}
