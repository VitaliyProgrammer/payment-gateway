package com.payflow.gateway.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.gateway.exception.AcquirerUnavailableException;
import org.junit.jupiter.api.Test;

/**
 * Стан-машина circuit breaker-а окремо від мережі: швидко й детерміновано,
 * без Spring-контексту.
 */
class AcquirerCircuitBreakerTest {

    @Test
    void staysClosedWhileSuccessesInterruptFailures() {
        AcquirerCircuitBreaker breaker = new AcquirerCircuitBreaker(3, 10_000);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess(); // скидає лічильник - рахуються лише невдачі поспіль
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.currentState()).isEqualTo(AcquirerCircuitBreaker.State.CLOSED);
        assertThatCode(breaker::acquirePermission).doesNotThrowAnyException();
    }

    @Test
    void opensAfterThresholdConsecutiveFailuresAndRejectsCalls() {
        AcquirerCircuitBreaker breaker = new AcquirerCircuitBreaker(3, 10_000);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.currentState()).isEqualTo(AcquirerCircuitBreaker.State.OPEN);
        assertThatThrownBy(breaker::acquirePermission).isInstanceOf(AcquirerUnavailableException.class);
    }

    @Test
    void afterOpenWindowAllowsOneTrialThenClosesOnSuccess() throws InterruptedException {
        AcquirerCircuitBreaker breaker = new AcquirerCircuitBreaker(1, 50);
        breaker.recordFailure(); // -> OPEN

        assertThatThrownBy(breaker::acquirePermission).isInstanceOf(AcquirerUnavailableException.class);
        Thread.sleep(80);

        // Перша спроба після вікна проходить (HALF_OPEN)...
        assertThatCode(breaker::acquirePermission).doesNotThrowAnyException();
        assertThat(breaker.currentState()).isEqualTo(AcquirerCircuitBreaker.State.HALF_OPEN);
        // ...а паралельна друга - ні, поки пробна в польоті.
        assertThatThrownBy(breaker::acquirePermission).isInstanceOf(AcquirerUnavailableException.class);

        breaker.recordSuccess();
        assertThat(breaker.currentState()).isEqualTo(AcquirerCircuitBreaker.State.CLOSED);
    }

    @Test
    void trialFailureReopensImmediately() throws InterruptedException {
        AcquirerCircuitBreaker breaker = new AcquirerCircuitBreaker(1, 50);
        breaker.recordFailure();
        Thread.sleep(80);

        breaker.acquirePermission(); // -> HALF_OPEN
        breaker.recordFailure();     // пробна провалилась

        assertThat(breaker.currentState()).isEqualTo(AcquirerCircuitBreaker.State.OPEN);
    }
}
