package com.piiscan.scanner.broker;

import java.time.Duration;
import java.util.Optional;

/**
 * producer와 consumer를 잇는 유계 메시지 채널. Kafka 전환을 대비한 추상화.
 *
 * <p>기본 구현은 {@code ArrayBlockingQueueBroker}(인-JVM). 후속으로 {@code KafkaMessageBroker}를
 * 같은 인터페이스로 추가하면 드라이버 교체만으로 전환된다.
 */
public interface MessageBroker<T> {

    /** 메시지 발행. 큐가 가득 차면 블록한다(백프레셔). */
    void publish(T message) throws InterruptedException;

    /** 타임아웃 내 메시지를 꺼낸다. 없으면 {@link Optional#empty()}. */
    Optional<T> poll(Duration timeout) throws InterruptedException;

    /** 더 이상 발행이 없음을 표시한다(모든 producer 종료 후 1회 호출). */
    void close();

    /** {@code close()} 되었고 큐가 비었으면 true → consumer 루프 종료 신호. */
    boolean isDrained();
}
