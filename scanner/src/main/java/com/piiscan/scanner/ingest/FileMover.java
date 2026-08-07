package com.piiscan.scanner.ingest;

import com.piiscan.scanner.config.ScannerProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Moves a claimed file out of the processing directory once a scan finishes.
 *
 * <p>Success moves it to the processed directory; failure moves it to the failed
 * directory and drops a sibling {@code <name>.error.txt} describing the reason,
 * so an operator can triage without re-running the scan.
 */
public final class FileMover {

    private final Path processedDir;
    private final Path failedDir;

    /** @param props configuration supplying the processed/failed directories */
    public FileMover(ScannerProperties props) {
        this.processedDir = props.processedDirPath();
        this.failedDir = props.failedDirPath();
        createDirs();
    }

    private void createDirs() {
        try {
            Files.createDirectories(processedDir);
            Files.createDirectories(failedDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create output directories", e);
        }
    }

    /**
     * Move a successfully scanned file to the processed directory.
     *
     * @return the new path in the processed directory
     */
    public Path moveToProcessed(Path processingFile) throws IOException {
        Path dest = processedDir.resolve(processingFile.getFileName());
        Files.move(processingFile, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /**
     * Move a failed file to the failed directory and record the reason alongside it.
     *
     * @param processingFile the claimed file to quarantine
     * @param reason         human-readable failure reason
     * @return the new path in the failed directory
     */
    public Path moveToFailed(Path processingFile, String reason) throws IOException {
        Path fileName = processingFile.getFileName();
        Path dest = failedDir.resolve(fileName);
        Files.move(processingFile, dest, StandardCopyOption.REPLACE_EXISTING);
        Path errorFile = failedDir.resolve(fileName + ".error.txt");
        Files.writeString(errorFile, reason == null ? "" : reason, StandardCharsets.UTF_8);
        return dest;
    }
}
