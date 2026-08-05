package com.piiscan.pipeline;

import com.piiscan.engine.RegexEngine;
import com.piiscan.io.Jsonl;
import com.piiscan.model.ColumnValue;
import com.piiscan.model.Finding;
import com.piiscan.model.ScanReport;
import com.piiscan.source.DataSource;
import com.piiscan.validate.Validator;
import com.piiscan.validate.ValidatorRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * The producer/consumer scanning pipeline.
 *
 * <p>A single producer reads the data source, collapses identical values into a
 * frequency table, and emits fixed-size batches onto a <em>bounded</em> queue.
 * A pool of consumers running on virtual threads pulls batches, invokes the
 * native regex engine on each, and confirms every candidate finding with its
 * checksum validator. The bounded queue provides backpressure: if the engine
 * cannot keep up, the producer blocks rather than reading the whole source into
 * memory.
 *
 * <p>Results are accumulated into a thread-safe aggregator and returned as an
 * immutable {@link ScanReport}.
 */
public final class ScanPipeline {

    private final RegexEngine engine;
    private final ValidatorRegistry validators;
    private final int workers;
    private final int batchSize;
    private final Path workDir;
    private final ProgressListener progress;

    public ScanPipeline(RegexEngine engine, ValidatorRegistry validators,
                        int workers, int batchSize, Path workDir) {
        this(engine, validators, workers, batchSize, workDir, ProgressListener.NONE);
    }

    public ScanPipeline(RegexEngine engine, ValidatorRegistry validators,
                        int workers, int batchSize, Path workDir, ProgressListener progress) {
        this.engine = engine;
        this.validators = validators;
        this.workers = Math.max(1, workers);
        this.batchSize = Math.max(1, batchSize);
        this.workDir = workDir;
        this.progress = progress == null ? ProgressListener.NONE : progress;
    }

    /**
     * Scan every value from {@code source} and return the aggregated report.
     *
     * @throws InterruptedException if the calling thread is interrupted while
     *                              the pipeline is running
     */
    public ScanReport run(DataSource source) throws InterruptedException {
        // Capacity a small multiple of the worker count keeps every consumer fed
        // while still bounding how far ahead the producer may run.
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(workers * 2);
        Aggregator agg = new Aggregator();

        Thread producer = Thread.ofVirtual().name("producer").start(
                () -> produce(source, queue, agg));

        List<Thread> consumers = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            consumers.add(Thread.ofVirtual().name("consumer-" + i).start(
                    () -> consume(queue, agg)));
        }

        producer.join();
        for (Thread c : consumers) {
            c.join();
        }
        return agg.toReport();
    }

    // ---- producer -------------------------------------------------------

    private void produce(DataSource source, BlockingQueue<Batch> queue, Aggregator agg) {
        Map<String, Long> freq = new LinkedHashMap<>();
        int[] batchId = {0};
        try {
            var it = source.values().iterator();
            while (it.hasNext()) {
                String value = it.next();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                freq.merge(value, 1L, Long::sum);
                if (freq.size() >= batchSize) {
                    flush(source.label(), freq, batchId, queue, agg);
                    freq = new LinkedHashMap<>();
                }
            }
            if (!freq.isEmpty()) {
                flush(source.label(), freq, batchId, queue, agg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // One poison pill per consumer guarantees each loop terminates.
            for (int i = 0; i < workers; i++) {
                putUninterruptibly(queue, Batch.POISON);
            }
        }
    }

    private void flush(String column, Map<String, Long> freq, int[] batchId,
                       BlockingQueue<Batch> queue, Aggregator agg) throws InterruptedException {
        int id = batchId[0]++;
        List<ColumnValue> values = new ArrayList<>(freq.size());
        long rows = 0;
        for (Map.Entry<String, Long> e : freq.entrySet()) {
            values.add(new ColumnValue(e.getKey(), e.getValue()));
            rows += e.getValue();
        }
        agg.recordScanned(values.size(), rows);

        Path input = workDir.resolve("batch-" + id + "-in.jsonl");
        Path output = workDir.resolve("batch-" + id + "-out.jsonl");
        try {
            Jsonl.writeBatch(input, id, column, values);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write batch " + id, e);
        }
        queue.put(new Batch(id, column, input, output)); // blocks when full → backpressure
    }

    // ---- consumers ------------------------------------------------------

    private void consume(BlockingQueue<Batch> queue, Aggregator agg) {
        try {
            while (true) {
                Batch batch = queue.take();
                if (batch.isPoison()) {
                    return;
                }
                process(batch, agg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(Batch batch, Aggregator agg) {
        try {
            List<Finding> findings = engine.scan(batch.input(), batch.output());
            for (Finding f : findings) {
                Validator validator = validators.resolve(f.validator());
                boolean valid = validator.isValid(f.matched());
                agg.record(f.patternId(), valid, f.count());
            }
            agg.batchDone();
        } catch (RegexEngine.EngineException e) {
            agg.batchFailed("batch " + batch.id() + ": " + e.getMessage());
        } finally {
            deleteQuietly(batch.input());
            deleteQuietly(batch.output());
        }
        progress.onBatch(agg.batchesDone(), agg.valuesScannedSoFar(), agg.confirmedRowsSoFar());
    }

    // ---- helpers --------------------------------------------------------

    private static void putUninterruptibly(BlockingQueue<Batch> queue, Batch b) {
        boolean interrupted = false;
        while (true) {
            try {
                queue.put(b);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp files live under a work dir the caller cleans up anyway
        }
    }

    /**
     * Thread-safe accumulator shared by the producer (scanned totals) and all
     * consumers (per-pattern confirmations).
     */
    private static final class Aggregator {
        private final LongAdder valuesScanned = new LongAdder();
        private final LongAdder rowsScanned = new LongAdder();
        private final LongAdder batches = new LongAdder();
        private final LongAdder failedBatches = new LongAdder();
        private final Map<String, Counters> perPattern = new ConcurrentHashMap<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();

        void recordScanned(long values, long rows) {
            valuesScanned.add(values);
            rowsScanned.add(rows);
        }

        void record(String patternId, boolean valid, long rows) {
            Counters c = perPattern.computeIfAbsent(patternId, k -> new Counters());
            if (valid) {
                c.confirmedValues.increment();
                c.confirmedRows.add(rows);
            } else {
                c.rejectedValues.increment();
                c.rejectedRows.add(rows);
            }
        }

        void batchDone() {
            batches.increment();
        }

        int batchesDone() {
            return batches.intValue();
        }

        long valuesScannedSoFar() {
            return valuesScanned.sum();
        }

        long confirmedRowsSoFar() {
            long total = 0;
            for (Counters c : perPattern.values()) {
                total += c.confirmedRows.sum();
            }
            return total;
        }

        void batchFailed(String message) {
            batches.increment();
            failedBatches.increment();
            errors.add(message);
        }

        ScanReport toReport() {
            Map<String, ScanReport.PatternStat> stats = new java.util.TreeMap<>();
            perPattern.forEach((id, c) -> stats.put(id, new ScanReport.PatternStat(
                    c.confirmedValues.sum(), c.confirmedRows.sum(),
                    c.rejectedValues.sum(), c.rejectedRows.sum())));
            return new ScanReport(
                    valuesScanned.sum(), rowsScanned.sum(),
                    batches.intValue(), failedBatches.intValue(),
                    stats, List.copyOf(errors));
        }

        private static final class Counters {
            final LongAdder confirmedValues = new LongAdder();
            final LongAdder confirmedRows = new LongAdder();
            final LongAdder rejectedValues = new LongAdder();
            final LongAdder rejectedRows = new LongAdder();
        }
    }
}
