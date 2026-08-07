package com.piiscan.scanner.ingest;

import com.piiscan.scanner.config.ScannerProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers eligible files in the scan directory and atomically claims them by
 * moving them into the processing directory.
 *
 * <p>The atomic move is the claim: only one runner can win the {@link Files#move}
 * with {@link StandardCopyOption#ATOMIC_MOVE}, so concurrent runners never scan
 * the same file. Files already in the processing directory are re-claimed as-is,
 * which recovers work interrupted by a crash.
 */
public final class FileScanner {

    private final Path scanDir;
    private final Path processingDir;
    private final Set<String> ignoreExtensions;
    private final Duration quietPeriod;

    /**
     * @param props configuration supplying the scan/processing dirs, ignored
     *              extensions and quiet period
     */
    public FileScanner(ScannerProperties props) {
        this.scanDir = props.scanDirPath();
        this.processingDir = props.processingDirPath();
        this.ignoreExtensions = props.getIgnoreExtensions().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        this.quietPeriod = Duration.ofSeconds(props.getQuietPeriodSeconds());
        createDirs();
    }

    private void createDirs() {
        try {
            Files.createDirectories(scanDir);
            Files.createDirectories(processingDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create scan directories", e);
        }
    }

    /**
     * Discover and atomically claim eligible files.
     *
     * @return files now living in the processing directory (recovered + newly claimed)
     * @throws IOException if the scan directory cannot be listed
     */
    public List<Path> claimEligible() throws IOException {
        List<Path> claimed = new ArrayList<>();

        // 1. Recover a crashed run: anything already in processing is ours.
        claimed.addAll(listRegularFiles(processingDir));

        Instant now = Instant.now();
        // 2. List regular files directly in scanDir (non-recursive).
        for (Path src : listRegularFiles(scanDir)) {
            // 3a. Skip ignored extensions.
            if (ignoreExtensions.contains(extensionOf(src))) {
                continue;
            }
            // 3b. Skip files still being written (mtime within the quiet period).
            if (withinQuietPeriod(src, now)) {
                continue;
            }
            // 4. Atomically claim by moving into the processing directory.
            Path dest = processingDir.resolve(src.getFileName());
            try {
                Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
                claimed.add(dest);
            } catch (IOException e) {
                // Locked / being written / lost the race — skip and continue.
                System.err.println("skip (could not claim) " + src + ": " + e.getMessage());
            }
        }
        return claimed;
    }

    private List<Path> listRegularFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    private boolean withinQuietPeriod(Path file, Instant now) {
        try {
            FileTime mtime = Files.getLastModifiedTime(file);
            return mtime.toInstant().isAfter(now.minus(quietPeriod));
        } catch (IOException e) {
            // If we cannot stat it, treat it as not-yet-stable and skip this round.
            return true;
        }
    }

    /** Lowercase extension after the last dot, or {@code ""} when there is none. */
    static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
