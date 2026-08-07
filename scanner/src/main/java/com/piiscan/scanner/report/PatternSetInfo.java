package com.piiscan.scanner.report;

import com.piiscan.io.Json;
import com.piiscan.model.PatternDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loaded view of the shared {@code patterns.json}: its version, a content hash
 * for provenance, and the parsed pattern definitions.
 *
 * <p>The current file is a bare JSON array of {@code {id,name,regex,validator}};
 * a wrapping object with a {@code "version"} field is also tolerated. When no
 * version is present it is reported as {@code "unversioned"}. The SHA-256 lets a
 * report record exactly which pattern set produced its findings.
 */
public final class PatternSetInfo {

    private final String version;
    private final String sha256;
    private final List<PatternDef> patterns;

    private PatternSetInfo(String version, String sha256, List<PatternDef> patterns) {
        this.version = version;
        this.sha256 = sha256;
        this.patterns = List.copyOf(patterns);
    }

    /**
     * Load and parse the pattern set from disk.
     *
     * @param patternsPath path to {@code patterns.json}
     * @throws IOException if the file cannot be read
     */
    public static PatternSetInfo load(Path patternsPath) throws IOException {
        byte[] bytes = Files.readAllBytes(patternsPath);
        String sha256 = sha256Hex(bytes);
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Object root = Json.parse(text);

        String version = "unversioned";
        List<?> rawPatterns;
        if (root instanceof Map<?, ?> obj) {
            Object v = obj.get("version");
            if (v != null) {
                version = v.toString();
            }
            Object arr = obj.get("patterns");
            rawPatterns = arr instanceof List<?> list ? list : List.of();
        } else if (root instanceof List<?> list) {
            rawPatterns = list;
        } else {
            rawPatterns = List.of();
        }

        List<PatternDef> patterns = new ArrayList<>();
        for (Object o : rawPatterns) {
            if (o instanceof Map<?, ?> m) {
                patterns.add(new PatternDef(
                        str(m.get("id")),
                        str(m.get("name")),
                        str(m.get("regex")),
                        str(m.get("validator"))));
            }
        }
        return new PatternSetInfo(version, sha256, patterns);
    }

    /** @return declared pattern-set version, or {@code "unversioned"}. */
    public String version() {
        return version;
    }

    /** @return hex SHA-256 of the patterns file bytes. */
    public String sha256() {
        return sha256;
    }

    /** @return parsed pattern definitions. */
    public List<PatternDef> patterns() {
        return patterns;
    }

    /** @return the ids of all patterns, in file order. */
    public List<String> patternIds() {
        List<String> ids = new ArrayList<>(patterns.size());
        for (PatternDef p : patterns) {
            ids.add(p.id());
        }
        return ids;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
