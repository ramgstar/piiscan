package com.piiscan.engine;

import com.piiscan.io.Jsonl;
import com.piiscan.model.Finding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the native {@code piiscan-engine} binary.
 *
 * <p>Each call launches the engine as a separate OS process (mirroring how a
 * manager service would fan work out to a native worker), hands it an input
 * batch and a shared patterns file, and reads the findings it writes back. The
 * engine does the regex matching; this class never inspects values itself.
 */
public final class RegexEngine {

    private final Path enginePath;
    private final Path patternsPath;
    private final Duration timeout;

    /**
     * @param enginePath   path to the compiled {@code piiscan-engine} binary
     * @param patternsPath shared patterns JSON file
     * @param timeout      maximum time to wait for a single batch
     */
    public RegexEngine(Path enginePath, Path patternsPath, Duration timeout) {
        this.enginePath = resolveBinary(enginePath);
        this.patternsPath = patternsPath;
        this.timeout = timeout;
    }

    /**
     * Tolerate a platform-agnostic engine path. On Windows the binary is
     * {@code piiscan-engine.exe}, but callers (and the manager's defaults) often
     * pass the extension-less name; if the given path is missing and a sibling
     * with {@code .exe} exists, use that instead. A no-op on other platforms and
     * when the path already resolves.
     */
    private static Path resolveBinary(Path path) {
        if (!Files.isRegularFile(path)) {
            Path withExe = path.resolveSibling(path.getFileName() + ".exe");
            if (Files.isRegularFile(withExe)) {
                return withExe;
            }
        }
        return path;
    }

    /** Confirm the engine binary exists and is executable before starting work. */
    public void verifyAvailable() {
        if (!Files.isRegularFile(enginePath)) {
            throw new IllegalStateException(
                    "engine binary not found at " + enginePath.toAbsolutePath()
                            + " — build it with `cargo build --release` in ./engine");
        }
        if (!Files.isExecutable(enginePath)) {
            throw new IllegalStateException("engine binary is not executable: " + enginePath.toAbsolutePath());
        }
    }

    /**
     * Run the engine over one batch file and return its findings.
     *
     * @param inputJsonl  batch produced by {@link Jsonl#writeBatch}
     * @param outputJsonl path the engine should write findings to
     * @return parsed findings
     * @throws EngineException if the process fails, times out, or exits non-zero
     */
    public List<Finding> scan(Path inputJsonl, Path outputJsonl) throws EngineException {
        ProcessBuilder pb = new ProcessBuilder(
                enginePath.toString(),
                "--patterns", patternsPath.toString(),
                "--input", inputJsonl.toString(),
                "--output", outputJsonl.toString()
        );
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();
            String stderr = readAll(process.getErrorStream());

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new EngineException("engine timed out after " + timeout.toSeconds() + "s");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new EngineException("engine exited with code " + exit + ": " + stderr.strip());
            }
            return Jsonl.readFindings(outputJsonl);
        } catch (IOException e) {
            throw new EngineException("failed to run engine: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new EngineException("interrupted while waiting for engine", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Raised when a native engine invocation fails. */
    public static final class EngineException extends Exception {
        public EngineException(String message) {
            super(message);
        }

        public EngineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
