package com.piiscan.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Externally bound configuration for the scanner (prefix {@code piiscan}).
 *
 * <p>Kept as a plain mutable class (not a record) so Spring Boot's relaxed
 * binding can populate it from {@code application.yml}, environment variables or
 * command-line arguments. Every field has a sensible default so the scanner runs
 * out of the box against a {@code piiScanner/} working tree.
 */
@ConfigurationProperties(prefix = "piiscan")
public class ScannerProperties {

    /** Directory watched for incoming files to scan. */
    private String scanDir = "piiScanner/scanFiles";
    /** Staging directory where claimed files live while being processed. */
    private String processingDir = "piiScanner/scanFiles/.processing";
    /** Destination for files that completed successfully. */
    private String processedDir = "piiScanner/processed";
    /** Destination for files that failed, alongside a {@code .error.txt}. */
    private String failedDir = "piiScanner/failed";
    /** Directory where per-file and run-summary reports are written. */
    private String outputDir = "piiScanner/results";

    /** Shared patterns JSON consumed by the native engine and the reports. */
    private String patternsPath = "piiScanner/patterns.json";
    /** Path to the compiled native engine binary. */
    private String enginePath = "engine/target/release/piiscan-engine";

    /** Minimum age (seconds) since last modification before a file is claimed. */
    private int quietPeriodSeconds = 5;
    /** Extensions ignored during discovery (lowercase, no dot). */
    private List<String> ignoreExtensions = new ArrayList<>(List.of("tmp", "part"));
    /** Number of consumer workers scanning batches in parallel. */
    private int consumers = Math.max(2, Runtime.getRuntime().availableProcessors());
    /** Broker queue capacity (backpressure bound). */
    private int brokerCapacity = 64;
    /** Maximum number of values per engine batch. */
    private int batchSize = 2000;
    /** Maximum location samples retained per distinct value. */
    private int sampleLocations = 20;
    /** Masking policy for reported values: {@code full}, {@code partial} or {@code hash}. */
    private String masking = "partial";
    /** Retention: keep only the newest N completed run folders under outputDir (0 = unbounded). */
    private int resultsMaxRuns = 20;

    public String getScanDir() {
        return scanDir;
    }

    public void setScanDir(String scanDir) {
        this.scanDir = scanDir;
    }

    public String getProcessingDir() {
        return processingDir;
    }

    public void setProcessingDir(String processingDir) {
        this.processingDir = processingDir;
    }

    public String getProcessedDir() {
        return processedDir;
    }

    public void setProcessedDir(String processedDir) {
        this.processedDir = processedDir;
    }

    public String getFailedDir() {
        return failedDir;
    }

    public void setFailedDir(String failedDir) {
        this.failedDir = failedDir;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public String getPatternsPath() {
        return patternsPath;
    }

    public void setPatternsPath(String patternsPath) {
        this.patternsPath = patternsPath;
    }

    public String getEnginePath() {
        return enginePath;
    }

    public void setEnginePath(String enginePath) {
        this.enginePath = enginePath;
    }

    public int getQuietPeriodSeconds() {
        return quietPeriodSeconds;
    }

    public void setQuietPeriodSeconds(int quietPeriodSeconds) {
        this.quietPeriodSeconds = quietPeriodSeconds;
    }

    public List<String> getIgnoreExtensions() {
        return ignoreExtensions;
    }

    public void setIgnoreExtensions(List<String> ignoreExtensions) {
        this.ignoreExtensions = ignoreExtensions;
    }

    public int getConsumers() {
        return consumers;
    }

    public void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    public int getBrokerCapacity() {
        return brokerCapacity;
    }

    public void setBrokerCapacity(int brokerCapacity) {
        this.brokerCapacity = brokerCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getSampleLocations() {
        return sampleLocations;
    }

    public void setSampleLocations(int sampleLocations) {
        this.sampleLocations = sampleLocations;
    }

    public String getMasking() {
        return masking;
    }

    public void setMasking(String masking) {
        this.masking = masking;
    }

    public int getResultsMaxRuns() {
        return resultsMaxRuns;
    }

    public void setResultsMaxRuns(int resultsMaxRuns) {
        this.resultsMaxRuns = resultsMaxRuns;
    }

    // ---- Path convenience getters --------------------------------------

    /** @return {@link #getScanDir()} as a {@link Path}. */
    public Path scanDirPath() {
        return Path.of(scanDir);
    }

    /** @return {@link #getProcessingDir()} as a {@link Path}. */
    public Path processingDirPath() {
        return Path.of(processingDir);
    }

    /** @return {@link #getProcessedDir()} as a {@link Path}. */
    public Path processedDirPath() {
        return Path.of(processedDir);
    }

    /** @return {@link #getFailedDir()} as a {@link Path}. */
    public Path failedDirPath() {
        return Path.of(failedDir);
    }

    /** @return {@link #getOutputDir()} as a {@link Path}. */
    public Path outputDirPath() {
        return Path.of(outputDir);
    }

    /** @return {@link #getPatternsPath()} as a {@link Path}. */
    public Path patternsPathAsPath() {
        return Path.of(patternsPath);
    }

    /** @return {@link #getEnginePath()} as a {@link Path}. */
    public Path enginePathAsPath() {
        return Path.of(enginePath);
    }
}
