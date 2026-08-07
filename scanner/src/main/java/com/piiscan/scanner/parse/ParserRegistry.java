package com.piiscan.scanner.parse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of built-in {@link Parser} strategies, keyed by lowercase extension.
 */
public final class ParserRegistry {

    private final Map<String, Parser> parsers = new LinkedHashMap<>();

    /** Register the built-in parsers (CSV, JSON). */
    public ParserRegistry() {
        register(new CsvFileParser());
        register(new JsonFileParser());
    }

    private void register(Parser parser) {
        parsers.put(parser.extension().toLowerCase(), parser);
    }

    /**
     * Look up a parser by extension (case-insensitive).
     *
     * @throws IllegalArgumentException if no parser handles {@code ext}
     */
    public Parser forExtension(String ext) {
        Parser parser = ext == null ? null : parsers.get(ext.toLowerCase());
        if (parser == null) {
            throw new IllegalArgumentException("unsupported extension: " + ext);
        }
        return parser;
    }

    /** Extensions handled by the registered parsers. */
    public Set<String> supportedExtensions() {
        return Collections.unmodifiableSet(parsers.keySet());
    }
}
