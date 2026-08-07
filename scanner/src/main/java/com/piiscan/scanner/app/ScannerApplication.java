package com.piiscan.scanner.app;

import com.piiscan.engine.RegexEngine;
import com.piiscan.scanner.config.ScannerProperties;
import com.piiscan.scanner.parse.ParserRegistry;
import com.piiscan.scanner.pipeline.ScanCoordinator;
import com.piiscan.scanner.report.PatternSetInfo;
import com.piiscan.validate.ValidatorRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * Spring Boot entry point for the scanner CLI.
 *
 * <p>Runs as a short-lived (non-web) command: it builds the native engine
 * wrapper, loads the pattern set, and hands off to {@link ScanCoordinator} for a
 * single run. The coordinator's exit code becomes the process exit code so a
 * manager or shell can detect failures.
 */
@SpringBootApplication
@EnableConfigurationProperties(ScannerProperties.class)
public class ScannerApplication implements CommandLineRunner, ExitCodeGenerator {

    /** Reported engine version; the pipeline records it in every report. */
    private static final String ENGINE_VERSION = "0.1.0";

    private final ScannerProperties properties;
    private int exitCode = 0;

    public ScannerApplication(ScannerProperties properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ScannerApplication.class, args)));
    }

    @Override
    public void run(String... args) throws Exception {
        String runId = argValue(args, "--run-id");
        if (runId == null || runId.isBlank()) {
            runId = UUID.randomUUID().toString();
        }

        RegexEngine engine = new RegexEngine(
                properties.enginePathAsPath(),
                properties.patternsPathAsPath(),
                Duration.ofMinutes(5));
        engine.verifyAvailable();

        PatternSetInfo patternSet = PatternSetInfo.load(properties.patternsPathAsPath());
        ParserRegistry parsers = new ParserRegistry();
        ValidatorRegistry validators = new ValidatorRegistry();

        ScanCoordinator coordinator = new ScanCoordinator(
                properties, engine, parsers, validators, patternSet, ENGINE_VERSION, runId);
        this.exitCode = coordinator.run();
    }

    /**
     * Exit code contributed to {@link SpringApplication#exit}.
     *
     * @return the coordinator's exit code
     */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** Read {@code --key value} or {@code --key=value} from the raw args. */
    private static String argValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals(key)) {
                return i + 1 < args.length ? args[i + 1] : null;
            }
            String prefix = key + "=";
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }
}
