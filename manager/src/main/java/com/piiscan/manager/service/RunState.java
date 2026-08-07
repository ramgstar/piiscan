package com.piiscan.manager.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-slot run guard shared by the scheduler and the manual "run now" action.
 *
 * <p>Only one scanner process may run at a time. Callers must {@link #tryAcquire()}
 * before launching and {@link #release()} exactly once when the process ends. The
 * guard is a plain {@link AtomicBoolean} — state is in memory only, which is fine
 * because a single manager instance owns the one scanner child process.
 */
@Component
public class RunState {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Attempts to move from idle to running.
     *
     * @return {@code true} if the caller acquired the slot (was idle), {@code false}
     *         if a run is already in progress
     */
    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    /** Releases the slot so the next scheduled or manual run can start. */
    public void release() {
        running.set(false);
    }

    /** @return {@code true} while a scanner process is running. */
    public boolean isRunning() {
        return running.get();
    }
}
