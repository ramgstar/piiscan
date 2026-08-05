package com.piiscan.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Manager service: a small Spring Boot web app (default port 5050) that queues
 * scan jobs, launches the analyzer as a child process per job, tracks progress
 * from the analyzer's stdout markers, and streams it to a live dashboard.
 */
@SpringBootApplication
public class ManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }
}
