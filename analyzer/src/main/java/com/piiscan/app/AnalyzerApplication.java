package com.piiscan.app;

import com.piiscan.Main;
import com.piiscan.cli.ScanConfig;
import com.piiscan.cli.ScanRunner;
import com.piiscan.io.ReportJson;
import com.piiscan.model.ScanReport;
import com.piiscan.pipeline.ProgressListener;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring Boot entry point for the analyzer, launched by the manager as a
 * separate process (one process per scan job).
 *
 * <p>It runs the shared {@link ScanRunner} core and communicates back to the
 * manager purely through stdout markers — mirroring how a manager service fans
 * work out to workers and reads their progress without a shared runtime:
 *
 * <ul>
 *   <li>{@code PROGRESS={json}} — emitted once per completed batch</li>
 *   <li>{@code RESULT={json}}   — the final aggregated report</li>
 * </ul>
 *
 * The exit code is 0 on success and 1 on failure, so the manager can tell a
 * clean finish from a crash.
 */
@SpringBootApplication
public class AnalyzerApplication implements CommandLineRunner {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(AnalyzerApplication.class, args)));
    }

    @Override
    public void run(String... args) throws Exception {
        Map<String, String> opt = Main.parseArgs(args);
        ScanConfig cfg = Main.configFrom(opt);
        Map<String, String> names = ScanRunner.loadPatternNames(cfg.patternsPath());
        String column = ScanRunner.sourceLabel(cfg);

        AtomicInteger emitted = new AtomicInteger();
        ProgressListener listener = (batchesDone, valuesDone, confirmedRows) -> {
            // Marker line the manager parses; keep it single-line JSON.
            System.out.println("PROGRESS={\"batches\":" + batchesDone
                    + ",\"values\":" + valuesDone
                    + ",\"confirmed\":" + confirmedRows + "}");
            System.out.flush();
            emitted.incrementAndGet();
        };

        ScanReport report = ScanRunner.run(cfg, listener);
        System.out.println("RESULT=" + ReportJson.toJson(report, names, column));
        System.out.flush();
    }
}
