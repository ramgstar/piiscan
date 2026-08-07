package com.piiscan.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Manager service: a small Spring Boot web app (default port 5050) that schedules
 * scans, launches the scanner as a child process per run, parses the scanner's
 * stdout markers, and streams progress to a live dashboard over SSE.
 *
 * <p>Run it from the repository root so the launched scanner resolves its own
 * {@code scanFiles}/engine/patterns paths relative to that working directory.
 */
@SpringBootApplication
@EnableScheduling
public class ManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }
}
