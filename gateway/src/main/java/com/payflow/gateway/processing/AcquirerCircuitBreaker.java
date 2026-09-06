package com.payflow.gateway.processing;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import com.payflow.gateway.exception.AcquirerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Написаний вручну circuit breaker навколо еквайра - у тому ж стилі, що й решта
 * примітивів стійкості в цьому проєкті (IdempotencyGuard, outbox-оренда й
 * backoff): маленький, повністю прокоментований, без зовнішньої залежності.
 *
 * <p>Навіщо він потрібен: якщо еквайр лежить, кожен платіж усе одно чекає повний
 * таймаут читання (5с), тримаючи воркер зайнятим, і лише потім іде в
 * NEEDS_RECONCILIATION. Під навантаженням це вичерпує пул воркерів даремним
 * очікуванням. Розімкнений ланцюг миттєво відхиляє нові виклики
 * ({@link AcquirerUnavailableException}) - платіж одразу паркується в
 * NEEDS_RECONCILIATION, а sweeper розбереться, коли еквайр повернеться.
 *
 * <p>ReentrantLock, а не synchronized: на Java 21 віртуальний потік, що
 * блокується всередині synchronized, прибивається до носійного потоку (див.
 * README, розділ Concurrency notes) - а весь сенс воркерів тут у тому, що вони
 * віртуальні.
 *
 * <p>Стан-машина: CLOSED -> (failureThreshold поспіль невдач) -> OPEN ->
 * (минув openDuration) -> HALF_OPEN -> (одна пробна спроба) -> CLOSED при
 * успіху або знову OPEN при невдачі. Успіх у стані CLOSED скидає лічильник
 * невдач - рахуються лише невдачі ПОСПІЛЬ, а не за весь час.
 */
@Component
public class AcquirerCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(AcquirerCircuitBreaker.class);

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;

    public AcquirerCircuitBreaker(
            @Value("${payflow.acquirer.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${payflow.acquirer.circuit-breaker.open-duration-ms:10000}") long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofMillis(openDurationMs);
    }

    /**
     * Викликається ПЕРЕД кожним зверненням до еквайра. Якщо ланцюг розімкнено -
     * кидає {@link AcquirerUnavailableException} замість того, щоб пропустити
     * виклик. Виняток: коли після розмикання вже минув {@code openDuration},
     * рівно одна спроба пропускається (перехід у HALF_OPEN), щоб перевірити, чи
     * еквайр ожив.
     */
    void acquirePermission() {
        lock.lock();
        try {
            switch (state) {
                case CLOSED -> {
                    // пропускаємо
                }
                case OPEN -> {
                    if (openedAt != null && !Instant.now().isBefore(openedAt.plus(openDuration))) {
                        state = State.HALF_OPEN;
                        log.info("Acquirer circuit breaker HALF_OPEN - allowing one trial call");
                    } else {
                        throw new AcquirerUnavailableException("Acquirer circuit breaker is OPEN");
                    }
                }
                case HALF_OPEN -> throw new AcquirerUnavailableException(
                        "Acquirer circuit breaker is HALF_OPEN - a trial call is already in flight");
            }
        } finally {
            lock.unlock();
        }
    }

    /** Успішний clean-виклик до еквайра (навіть якщо його підсумок - DECLINED: еквайр живий). */
    void recordSuccess() {
        lock.lock();
        try {
            if (state != State.CLOSED) {
                log.info("Acquirer circuit breaker CLOSED after a successful call");
            }
            state = State.CLOSED;
            consecutiveFailures = 0;
            openedAt = null;
        } finally {
            lock.unlock();
        }
    }

    /** Транспортна невдача або 5xx: еквайр недоступний. */
    void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
            boolean trip = state == State.HALF_OPEN || consecutiveFailures >= failureThreshold;
            if (trip) {
                if (state != State.OPEN) {
                    log.warn("Acquirer circuit breaker OPEN after {} consecutive failure(s)", consecutiveFailures);
                }
                state = State.OPEN;
                openedAt = Instant.now();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Лише для тестів і логів - поточний стан під тим самим локом. */
    State currentState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }
}
