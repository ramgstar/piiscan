package com.piiscan;

import com.piiscan.engine.RegexEngine;
import com.piiscan.model.ScanReport;
import com.piiscan.pipeline.ScanPipeline;
import com.piiscan.source.SyntheticDataSource;
import com.piiscan.validate.ValidatorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end pipeline test that drives the real native engine.
 *
 * <p>Skipped automatically when the engine binary has not been built, so a plain
 * {@code mvn test} still passes; run {@code cargo build --release} in {@code
 * ./engine} first to exercise it.
 */
class ScanPipelineTest {

    private static Path enginePath() {
        Path unix = Path.of("..", "engine", "target", "release", "piiscan-engine");
        Path win = Path.of("..", "engine", "target", "release", "piiscan-engine.exe");
        return Files.exists(win) ? win : unix;
    }

    @Test
    void confirmsAndRejectsAcrossTheProcessBoundary(@TempDir Path workDir) throws Exception {
        Path engine = enginePath();
        Path patterns = Path.of("..", "samples", "patterns.json");
        assumeTrue(Files.exists(engine), "engine binary not built — run cargo build --release in ./engine");
        assumeTrue(Files.exists(patterns), "samples/patterns.json missing");

        RegexEngine regexEngine = new RegexEngine(engine, patterns, Duration.ofMinutes(1));
        ScanPipeline pipeline = new ScanPipeline(regexEngine, new ValidatorRegistry(), 4, 500, workDir);

        ScanReport report = pipeline.run(new SyntheticDataSource(5000, 42));

        assertEquals(0, report.failedBatches(), () -> "batch errors: " + report.errors());
        assertTrue(report.valuesScanned() > 0);
        assertTrue(report.totalConfirmedRows() > 0, "expected some confirmed PII");

        // Validation must actually reject look-alikes: at least one pattern with a
        // checksum should show rejected rows.
        long rejected = report.perPattern().values().stream()
                .mapToLong(ScanReport.PatternStat::rejectedRows).sum();
        assertTrue(rejected > 0, "expected the checksum stage to reject some candidates");

        // Email has no checksum, so every email candidate is confirmed.
        ScanReport.PatternStat email = report.perPattern().get("EMAIL");
        if (email != null) {
            assertEquals(0, email.rejectedRows());
        }
    }
}
