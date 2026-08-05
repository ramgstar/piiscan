package com.piiscan;

import com.piiscan.cli.ScanConfig;
import com.piiscan.cli.ScanRunner;
import com.piiscan.model.ScanReport;
import com.piiscan.pipeline.ProgressListener;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain command-line entry point (no Spring) for running a scan directly.
 *
 * <p>The Spring Boot analyzer application shares the same {@link ScanRunner}
 * core; this class exists so the scanner can be run and tested without the
 * framework.
 *
 * <pre>{@code
 * java -cp ... com.piiscan.Main \
 *   --engine ../engine/target/release/piiscan-engine \
 *   --patterns ../samples/patterns.json \
 *   --synthetic 20000 --workers 8 --batch-size 2000
 * }</pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if (opt.containsKey("help")) {
            printUsage();
            return;
        }

        ScanConfig cfg = configFrom(opt);
        Map<String, String> names = ScanRunner.loadPatternNames(cfg.patternsPath());

        long start = System.nanoTime();
        ScanReport report = ScanRunner.run(cfg, ProgressListener.NONE);
        long ms = (System.nanoTime() - start) / 1_000_000;

        printReport(report, names, ScanRunner.sourceLabel(cfg), cfg.workers(), ms);
    }

    public static ScanConfig configFrom(Map<String, String> opt) {
        Path enginePath = Path.of(opt.getOrDefault("engine", defaultEnginePath()));
        Path patternsPath = Path.of(opt.getOrDefault("patterns", "samples/patterns.json"));
        Path inputCsv = opt.containsKey("input") ? Path.of(opt.get("input")) : null;
        int synthetic = Integer.parseInt(opt.getOrDefault("synthetic", "5000"));
        int workers = Integer.parseInt(opt.getOrDefault("workers",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        int batchSize = Integer.parseInt(opt.getOrDefault("batch-size", "1000"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "42"));
        return new ScanConfig(enginePath, patternsPath, inputCsv, synthetic, workers, batchSize, seed);
    }

    private static void printReport(ScanReport r, Map<String, String> names,
                                    String column, int workers, long ms) {
        System.out.println();
        System.out.println("piiscan report");
        System.out.println("----------------------------------------------");
        System.out.printf("source        : %s%n", column);
        System.out.printf("workers       : %d (virtual threads)%n", workers);
        System.out.printf("values scanned: %,d distinct (%,d rows)%n", r.valuesScanned(), r.rowsScanned());
        System.out.printf("batches       : %d (%d failed)%n", r.batches(), r.failedBatches());
        System.out.printf("elapsed       : %,d ms%n", ms);
        System.out.println("----------------------------------------------");
        System.out.printf("%-8s %-34s %10s %10s%n", "PATTERN", "NAME", "CONFIRMED", "REJECTED");
        if (r.perPattern().isEmpty()) {
            System.out.println("(no matches)");
        }
        r.perPattern().forEach((id, s) -> System.out.printf(
                "%-8s %-34s %10d %10d%n",
                id, names.getOrDefault(id, ""), s.confirmedRows(), s.rejectedRows()));
        System.out.println("----------------------------------------------");
        System.out.printf("confirmed PII rows: %,d%n", r.totalConfirmedRows());
        if (!r.errors().isEmpty()) {
            System.out.println("\nerrors:");
            r.errors().forEach(e -> System.out.println("  - " + e));
        }
    }

    public static String defaultEnginePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String bin = os.contains("win") ? "piiscan-engine.exe" : "piiscan-engine";
        return Path.of("engine", "target", "release", bin).toString();
    }

    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help")) {
                opt.put("help", "true");
            } else if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opt.put(key, args[++i]);
                } else {
                    opt.put(key, "true");
                }
            }
        }
        return opt;
    }

    private static void printUsage() {
        System.out.println("""
                piiscan analyzer — two-stage PII scanner (Rust regex engine + Java validation)

                Usage:
                  java -jar piiscan-analyzer.jar [options]

                Options:
                  --engine <path>      path to the piiscan-engine binary
                  --patterns <path>    patterns JSON (default: samples/patterns.json)
                  --input <csv>        scan a CSV file (all columns)
                  --synthetic <N>      generate N synthetic rows instead (default: 5000)
                  --workers <M>        consumer virtual threads (default: CPU count)
                  --batch-size <N>     distinct values per batch (default: 1000)
                  --seed <n>           synthetic RNG seed (default: 42)
                  -h, --help           show this help
                """);
    }

    private Main() {
    }
}
