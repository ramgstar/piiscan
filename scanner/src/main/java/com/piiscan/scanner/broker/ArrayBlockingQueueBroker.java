package com.piiscan.scanner.broker;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-JVM {@link MessageBroker} backed by a bounded {@link ArrayBlockingQueue}.
 *
 * <p>The bounded queue gives natural backpressure: {@link #publish(Object)} blocks
 * when the queue is full, throttling producers to the pace consumers can keep. A
 * later {@code KafkaMessageBroker} can replace this behind the same interface.
 *
 * @param <T> message payload type
 */
public final class ArrayBlockingQueueBroker<T> implements MessageBroker<T> {

    private final BlockingQueue<T> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** @param capacity maximum number of buffered messages before publishers block */
    public ArrayBlockingQueueBroker(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void publish(T message) throws InterruptedException {
        queue.put(message);
    }

    @Override
    public Optional<T> poll(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @Override
    public boolean isDrained() {
        return closed.get() && queue.isEmpty();
    }
}
