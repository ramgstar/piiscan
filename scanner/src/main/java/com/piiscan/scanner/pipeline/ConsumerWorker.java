package com.piiscan.scanner.pipeline;

import com.piiscan.engine.RegexEngine;
import com.piiscan.io.Json;
import com.piiscan.model.Finding;
import com.piiscan.model.PatternDef;
import com.piiscan.scanner.broker.MessageBroker;
import com.piiscan.scanner.broker.ScanTask;
import com.piiscan.scanner.ingest.FileMover;
import com.piiscan.scanner.pipeline.ScanCoordinator.RunAccumulator;
import com.piiscan.scanner.report.FileReport;
import com.piiscan.scanner.report.Masker;
import com.piiscan.scanner.report.PatternFinding;
import com.piiscan.scanner.report.PatternSetInfo;
import com.piiscan.scanner.report.ReportWriter;
import com.piiscan.validate.ValidatorRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consumes {@link ScanTask}s from the broker, runs the native engine over each
 * batch, confirms candidates with checksum validators, and writes a per-file
 * report.
 *
 * <p>Several workers run in parallel; each loops until the broker is drained.
 * Every task is wrapped in try/catch: on any error the source is quarantined to
 * the failed directory, and temp files are always deleted.
 */
public final class ConsumerWorker implements Runnable {

    private final MessageBroker<ScanTask> broker;
    private final RegexEngine engine;
    private final ValidatorRegistry validators;
    private final Masker masker;
    private final ReportWriter reportWriter;
    private final FileMover fileMover;
    private final ProgressReporter reporter;
    private final Map<String, PatternDef> patternsById;
    private final String patternSetVersion;
    private final String patternSetSha256;
    private final String engineVersion;
    private final RunAccumulator accumulator;
    private final Path workDir;
    private final int maxLocationsPerPattern;

    public ConsumerWorker(MessageBroker<ScanTask> broker,
                          RegexEngine engine,
                          ValidatorRegistry validators,
                          Masker masker,
                          ReportWriter reportWriter,
                          FileMover fileMover,
                          ProgressReporter reporter,
                          PatternSetInfo patternSet,
                          String engineVersion,
                          RunAccumulator accumulator,
                          Path workDir,
                          int maxLocationsPerPattern) {
        this.broker = broker;
        this.engine = engine;
        this.validators = validators;
        this.masker = masker;
        this.reportWriter = reportWriter;
        this.fileMover = fileMover;
        this.reporter = reporter;
        this.patternsById = new HashMap<>();
        for (PatternDef p : patternSet.patterns()) {
            this.patternsById.put(p.id(), p);
        }
        this.patternSetVersion = patternSet.version();
        this.patternSetSha256 = patternSet.sha256();
        this.engineVersion = engineVersion;
        this.accumulator = accumulator;
        this.workDir = workDir;
        this.maxLocationsPerPattern = maxLocationsPerPattern;
    }

    @Override
    public void run() {
        while (!broker.isDrained()) {
            try {
                Optional<ScanTask> task = broker.poll(Duration.ofMillis(200));
                task.ifPresent(this::process);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(ScanTask task) {
        Instant start = Instant.now();
        Path output = workDir.resolve(task.scanId() + ".findings.jsonl");
        try {
            List<Finding> candidates = engine.scan(task.inputFile(), output);
            InputData input = loadInput(task.inputFile());

            Map<String, PatAgg> byPattern = new LinkedHashMap<>();
            long confirmed = 0;
            long rejected = 0;
            for (Finding f : candidates) {
                boolean valid = validators.resolve(f.validator()).isValid(f.matched());
                if (valid) {
                    confirmed += f.count();
                    PatAgg pa = byPattern.computeIfAbsent(f.patternId(), k -> new PatAgg());
                    pa.confirmedCount += f.count();
                    if (pa.maskedSample == null) {
                        pa.maskedSample = masker.mask(f.value());
                    }
                    List<Object> locs = input.locations().get(f.value());
                    if (locs != null) {
                        for (Object loc : locs) {
                            if (pa.locations.size() >= maxLocationsPerPattern) {
                                break;
                            }
                            pa.locations.add(loc);
                        }
                    }
                } else {
                    rejected += f.count();
                }
            }

            List<PatternFinding> findings = new ArrayList<>(byPattern.size());
            Map<String, Long> confirmedByPattern = new LinkedHashMap<>();
            for (Map.Entry<String, PatAgg> e : byPattern.entrySet()) {
                PatAgg pa = e.getValue();
                PatternDef def = patternsById.get(e.getKey());
                findings.add(new PatternFinding(
                        e.getKey(),
                        def != null ? def.name() : "",
                        def != null ? def.validator() : "",
                        pa.confirmedCount,
                        pa.maskedSample == null ? "" : pa.maskedSample,
                        pa.locations));
                confirmedByPattern.put(e.getKey(), pa.confirmedCount);
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            FileReport report = new FileReport(
                    task.scanId(),
                    Instant.now(),
                    task.fileName(),
                    task.fileSize(),
                    sha256File(task.sourceOriginal()),
                    task.ext(),
                    patternSetVersion,
                    patternSetSha256,
                    engineVersion,
                    durationMs,
                    input.distinctValues(),
                    input.totalCells(),
                    confirmed,
                    rejected,
                    1,
                    0,
                    findings,
                    List.of());
            reportWriter.writePerFile(report);

            fileMover.moveToProcessed(task.sourceOriginal());
            accumulator.recordProcessed(confirmedByPattern, confirmed);
            reporter.file(task.fileName(), "processed", confirmed, null);
            reporter.finishFile(confirmed);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                fileMover.moveToFailed(task.sourceOriginal(), reason);
            } catch (Exception moveError) {
                System.err.println("failed to quarantine " + task.sourceOriginal()
                        + ": " + moveError.getMessage());
            }
            accumulator.recordFailure(task.fileName(), reason);
            reporter.file(task.fileName(), "failed", 0, reason);
            reporter.finishFile(0);
        } finally {
            deleteQuietly(task.inputFile());
            deleteQuietly(output);
        }
    }

    /** Read the input JSONL: build value→locations and count distinct values / total cells. */
    private static InputData loadInput(Path inputFile) throws IOException {
        Map<String, List<Object>> locations = new HashMap<>();
        long distinct = 0;
        long total = 0;
        for (String line : Files.readAllLines(inputFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> o = Json.parseObject(line);
            String value = o.get("value") == null ? "" : o.get("value").toString();
            Object countObj = o.get("count");
            long count = countObj instanceof Number n ? n.longValue() : 0;
            Object locs = o.get("locations");
            @SuppressWarnings("unchecked")
            List<Object> locList = locs instanceof List ? (List<Object>) locs : List.of();
            locations.put(value, locList);
            distinct++;
            total += count;
        }
        return new InputData(locations, distinct, total);
    }

    private static String sha256File(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            System.err.println("failed to delete temp file " + p + ": " + e.getMessage());
        }
    }

    /** Parsed input file: value→locations plus scan-size counters. */
    private record InputData(Map<String, List<Object>> locations, long distinctValues, long totalCells) {
    }

    /** Mutable per-pattern accumulator during confirmation. */
    private static final class PatAgg {
        private long confirmedCount;
        private String maskedSample;
        private final List<Object> locations = new ArrayList<>();
    }
}
