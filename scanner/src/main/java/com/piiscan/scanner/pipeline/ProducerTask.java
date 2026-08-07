package com.piiscan.scanner.pipeline;

import com.piiscan.scanner.broker.MessageBroker;
import com.piiscan.scanner.broker.ScanTask;
import com.piiscan.scanner.config.ScannerProperties;
import com.piiscan.scanner.ingest.FileMover;
import com.piiscan.scanner.parse.InputWriter;
import com.piiscan.scanner.parse.Parser;
import com.piiscan.scanner.parse.ParserRegistry;
import com.piiscan.scanner.pipeline.ScanCoordinator.RunAccumulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Parses one claimed file into a unified input JSONL and publishes a
 * {@link ScanTask} claim-check to the broker for a consumer to scan.
 *
 * <p>Runs on its own (virtual) thread. Parsing streams cells through an
 * {@link InputWriter} that dedups values on disk, so only a reference travels
 * over the broker. When parsing is unsupported or fails, the file is quarantined
 * to the failed directory and no task is published.
 */
public final class ProducerTask implements Runnable {

    private final String scanId;
    private final Path source;
    private final ScannerProperties props;
    private final ParserRegistry registry;
    private final MessageBroker<ScanTask> broker;
    private final ProgressReporter reporter;
    private final FileMover fileMover;
    private final RunAccumulator accumulator;
    private final Path workDir;

    public ProducerTask(String scanId,
                        Path source,
                        ScannerProperties props,
                        ParserRegistry registry,
                        MessageBroker<ScanTask> broker,
                        ProgressReporter reporter,
                        FileMover fileMover,
                        RunAccumulator accumulator,
                        Path workDir) {
        this.scanId = scanId;
        this.source = source;
        this.props = props;
        this.registry = registry;
        this.broker = broker;
        this.reporter = reporter;
        this.fileMover = fileMover;
        this.accumulator = accumulator;
        this.workDir = workDir;
    }

    @Override
    public void run() {
        reporter.startFile();
        String name = source.getFileName().toString();
        String ext = extensionOf(name);
        try {
            Parser parser = registry.forExtension(ext);
            InputWriter iw = new InputWriter(props.getSampleLocations());
            parser.parse(source, iw);
            Path inputFile = workDir.resolve(scanId + ".input.jsonl");
            iw.write(inputFile);
            long size = Files.size(source);
            ScanTask task = new ScanTask(scanId, name, size, ext, inputFile, source, Instant.now());
            broker.publish(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(name, "interrupted while publishing");
        } catch (Exception e) {
            fail(name, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Quarantine the file, record the failure, and emit markers without publishing. */
    private void fail(String name, String reason) {
        try {
            fileMover.moveToFailed(source, reason);
        } catch (Exception moveError) {
            System.err.println("failed to quarantine " + source + ": " + moveError.getMessage());
        }
        accumulator.recordFailure(name, reason);
        reporter.file(name, "failed", 0, reason);
        reporter.finishFile(0);
    }

    /** Lowercase extension after the last dot, or {@code ""} when there is none. */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
