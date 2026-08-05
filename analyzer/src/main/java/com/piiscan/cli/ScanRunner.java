package com.piiscan.cli;

import com.piiscan.engine.RegexEngine;
import com.piiscan.io.Json;
import com.piiscan.model.PatternDef;
import com.piiscan.model.ScanReport;
import com.piiscan.pipeline.ProgressListener;
import com.piiscan.pipeline.ScanPipeline;
import com.piiscan.source.CsvDataSource;
import com.piiscan.source.DataSource;
import com.piiscan.source.SyntheticDataSource;
import com.piiscan.validate.ValidatorRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs one scan from a {@link ScanConfig}.
 *
 * <p>This is the shared, framework-free core used by both the plain CLI and the
 * Spring Boot analyzer, so the actual scanning logic has no dependency on Spring
 * and stays trivially testable.
 */
public final class ScanRunner {

    private ScanRunner() {
    }

    /**
     * Run a scan, forwarding per-batch progress to {@code progress}.
     *
     * @return the aggregated report
     */
    public static ScanReport run(ScanConfig cfg, ProgressListener progress) throws IOException, InterruptedException {
        RegexEngine engine = new RegexEngine(cfg.enginePath(), cfg.patternsPath(), Duration.ofMinutes(5));
        engine.verifyAvailable();

        DataSource source = cfg.inputCsv() != null
                ? new CsvDataSource(cfg.inputCsv())
                : new SyntheticDataSource(cfg.syntheticRows(), cfg.seed());

        Path workDir = Files.createTempDirectory("piiscan-");
        try (source) {
            return new ScanPipeline(engine, new ValidatorRegistry(),
                    cfg.workers(), cfg.batchSize(), workDir, progress).run(source);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** Load pattern id → display name for reporting. */
    public static Map<String, String> loadPatternNames(Path patternsPath) throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        Object parsed = Json.parse(Files.readString(patternsPath));
        if (parsed instanceof List<?> array) {
            for (Object item : array) {
                if (item instanceof Map<?, ?> o) {
                    PatternDef def = new PatternDef(
                            str(o.get("id")), str(o.get("name")), str(o.get("regex")), str(o.get("validator")));
                    names.put(def.id(), def.name());
                }
            }
        }
        return names;
    }

    /** The label the run will use for its source (mirrors {@link #run}). */
    public static String sourceLabel(ScanConfig cfg) {
        return cfg.inputCsv() != null ? cfg.inputCsv().getFileName().toString() : "synthetic.SAMPLE";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                List<Path> paths;
                try (var walk = Files.walk(dir)) {
                    paths = new ArrayList<>(walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList());
                }
                for (Path p : paths) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
