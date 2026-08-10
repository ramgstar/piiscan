package com.piiscan.scanner.pipeline;

import com.piiscan.engine.RegexEngine;
import com.piiscan.io.Json;
import com.piiscan.model.PatternDef;
import com.piiscan.scanner.broker.ArrayBlockingQueueBroker;
import com.piiscan.scanner.broker.MessageBroker;
import com.piiscan.scanner.broker.ScanTask;
import com.piiscan.scanner.config.ScannerProperties;
import com.piiscan.scanner.ingest.FileMover;
import com.piiscan.scanner.ingest.FileScanner;
import com.piiscan.scanner.parse.ParserRegistry;
import com.piiscan.scanner.report.Masker;
import com.piiscan.scanner.report.PatternSetInfo;
import com.piiscan.scanner.report.ReportWriter;
import com.piiscan.scanner.report.RunSummary;
import com.piiscan.validate.ValidatorRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates a single scan run: claim files, fan them out to producers and
 * consumers over the broker, aggregate results, and emit the run summary.
 *
 * <p>Threading model:
 * <ul>
 *   <li>Producers: one virtual thread per claimed file, parsing and publishing
 *       into a bounded broker (publish blocks under backpressure).</li>
 *   <li>A closer thread awaits producer completion, then {@code close()}s the
 *       broker so consumers know no more work is coming.</li>
 *   <li>Consumers: a fixed pool of {@code properties.consumers} workers that
 *       loop until the broker is drained.</li>
 * </ul>
 * Producers and consumers must run concurrently: producers block on a full queue,
 * so consumers have to drain it in parallel to avoid deadlock.
 */
public final class ScanCoordinator {

    private final ScannerProperties props;
    private final RegexEngine engine;
    private final ParserRegistry parsers;
    private final ValidatorRegistry validators;
    private final PatternSetInfo patternSet;
    private final String engineVersion;
    private final String runId;

    public ScanCoordinator(ScannerProperties props,
                           RegexEngine engine,
                           ParserRegistry parsers,
                           ValidatorRegistry validators,
                           PatternSetInfo patternSet,
                           String engineVersion,
                           String runId) {
        this.props = props;
        this.engine = engine;
        this.parsers = parsers;
        this.validators = validators;
        this.patternSet = patternSet;
        this.engineVersion = engineVersion;
        this.runId = runId;
    }

    /**
     * Run the scan.
     *
     * @return process exit code (0 on completion, even when some files failed)
     * @throws IOException if claiming files or writing the summary fails
     */
    public int run() throws IOException {
        Instant startedAt = Instant.now();
        ProgressReporter reporter = new ProgressReporter();
        FileMover fileMover = new FileMover(props);
        RunAccumulator accumulator = new RunAccumulator();

        List<Path> claimed = new FileScanner(props).claimEligible();
        int total = claimed.size();
        reporter.setTotal(total);

        if (claimed.isEmpty()) {
            // 파일이 없으면 이력 폴더를 만들지 않고(스케줄 빈 실행이 이력을 오염시키지 않도록)
            // 대시보드용 SUMMARY 마커만 내보내고 종료한다.
            RunSummary summary = new RunSummary(runId, startedAt, Instant.now(), 0,
                    0, 0, 0, 0, Map.of(), List.of());
            reporter.summary(summaryJson(summary));
            return 0;
        }

        // 파일이 있는 경우에만 run 폴더(results/<runId>/)를 만든다.
        ReportWriter reportWriter = new ReportWriter(props.outputDirPath(), runId);
        Masker masker = new Masker(props.getMasking());
        MessageBroker<ScanTask> broker = new ArrayBlockingQueueBroker<>(props.getBrokerCapacity());
        Path workDir = Files.createTempDirectory("piiscan-" + runId + "-");

        // Consumers first, so producers never deadlock on a full queue.
        int consumerCount = Math.max(1, props.getConsumers());
        ExecutorService consumerPool = Executors.newFixedThreadPool(consumerCount);
        for (int i = 0; i < consumerCount; i++) {
            consumerPool.submit(new ConsumerWorker(
                    broker, engine, validators, masker, reportWriter, fileMover,
                    reporter, patternSet, engineVersion, accumulator, workDir,
                    props.getSampleLocations()));
        }

        // Producers: one virtual thread per file.
        ExecutorService producerPool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < total; i++) {
            Path source = claimed.get(i);
            String scanId = runId + "-" + i;
            producerPool.submit(new ProducerTask(
                    scanId, source, props, parsers, broker, reporter, fileMover,
                    accumulator, workDir));
        }
        producerPool.shutdown();

        // Closer: await all producers, then signal end-of-stream to consumers.
        Thread closer = new Thread(() -> {
            try {
                producerPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            broker.close();
        }, "broker-closer");
        closer.start();

        // Await consumers draining everything.
        consumerPool.shutdown();
        try {
            consumerPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            closer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Instant finishedAt = Instant.now();
        RunSummary summary = new RunSummary(
                runId,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                total,
                accumulator.processed(),
                accumulator.failed(),
                accumulator.confirmedTotal(),
                accumulator.byPattern(),
                accumulator.failures());
        reportWriter.writeRunSummary(summary);
        reportWriter.pruneOldRuns(props.getResultsMaxRuns());
        reporter.summary(summaryJson(summary));

        deleteWorkDir(workDir);
        return 0;
    }

    /**
     * Compact SUMMARY= marker body (a subset of the full run summary), including a
     * {@code patterns} array (id/name/confirmed) so the dashboard can draw the
     * per-pattern chart directly.
     */
    private String summaryJson(RunSummary s) {
        Map<String, String> names = new HashMap<>();
        for (PatternDef p : patternSet.patterns()) {
            names.put(p.id(), p.name());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"runId\":").append(Json.quote(s.runId()));
        sb.append(",\"files\":{\"total\":").append(s.filesTotal());
        sb.append(",\"processed\":").append(s.filesProcessed());
        sb.append(",\"failed\":").append(s.filesFailed()).append('}');
        sb.append(",\"confirmedTotal\":").append(s.confirmedTotal());
        sb.append(",\"durationMs\":").append(s.durationMs());
        sb.append(",\"patterns\":[");
        boolean first = true;
        for (Map.Entry<String, Long> e : s.byPattern().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(Json.quote(e.getKey()))
                    .append(",\"name\":").append(Json.quote(names.getOrDefault(e.getKey(), "")))
                    .append(",\"confirmed\":").append(e.getValue()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void deleteWorkDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            System.err.println("failed to clean work dir " + dir + ": " + e.getMessage());
        }
    }

    /**
     * Thread-safe accumulator of run-level results, shared across all consumers
     * (and producers, for produce-time failures).
     */
    public static final class RunAccumulator {

        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicLong confirmedTotal = new AtomicLong();
        private final Map<String, Long> byPattern = new ConcurrentHashMap<>();
        private final List<RunSummary.Failure> failures = new ArrayList<>();

        /** Record a successfully processed file and merge its per-pattern counts. */
        public void recordProcessed(Map<String, Long> perPattern, long confirmed) {
            processed.incrementAndGet();
            confirmedTotal.addAndGet(confirmed);
            perPattern.forEach((k, v) -> byPattern.merge(k, v, Long::sum));
        }

        /** Record a file-level failure. */
        public void recordFailure(String file, String reason) {
            failed.incrementAndGet();
            synchronized (failures) {
                failures.add(new RunSummary.Failure(file, reason));
            }
        }

        public int processed() {
            return processed.get();
        }

        public int failed() {
            return failed.get();
        }

        public long confirmedTotal() {
            return confirmedTotal.get();
        }

        /** Snapshot of confirmed occurrences keyed by pattern id. */
        public Map<String, Long> byPattern() {
            return new java.util.LinkedHashMap<>(byPattern);
        }

        /** Snapshot of recorded failures. */
        public List<RunSummary.Failure> failures() {
            synchronized (failures) {
                return new ArrayList<>(failures);
            }
        }
    }
}
