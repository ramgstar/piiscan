package com.piiscan.manager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers a scan.
 *
 * <p>Runs on a fixed delay: the next tick is scheduled only after the previous
 * {@link #tick()} returns, and {@link ScannerLauncher#launchIfIdle()} is itself
 * guarded by {@link RunState}. Together these prevent overlapping scanner
 * processes even if a scan outlasts the delay. Disable the schedule by setting
 * {@code piiscan.schedule.fixed-delay} to a very large value.
 */
@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final ScannerLauncher launcher;

    public ScanScheduler(ScannerLauncher launcher) {
        this.launcher = launcher;
    }

    @Scheduled(fixedDelayString = "${piiscan.schedule.fixed-delay:60000}")
    public void tick() {
        launcher.launchIfIdle().ifPresentOrElse(
                runId -> log.info("scheduled scan started: {}", runId),
                () -> log.debug("scheduled tick skipped: a scan is already running"));
    }
}
