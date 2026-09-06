package com.payflow.gateway.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.gateway.entity.status.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PaymentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentMetrics metrics = new PaymentMetrics(registry);

    @Test
    void paymentAcceptedCountsByUppercasedCurrency() {
        metrics.paymentAccepted("uah");
        metrics.paymentAccepted("UAH");

        assertThat(registry.get("payflow.payments.accepted").tag("currency", "UAH").counter().count()).isEqualTo(2.0);
    }

    @Test
    void transitionCountsByNewStatus() {
        metrics.transitioned(PaymentStatus.AUTHORIZED);
        metrics.transitioned(PaymentStatus.AUTHORIZED);
        metrics.transitioned(PaymentStatus.DECLINED);

        assertThat(registry.get("payflow.payments.transitions").tag("status", "AUTHORIZED").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("payflow.payments.transitions").tag("status", "DECLINED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void acquirerCallRecordsATimerTaggedByOperationAndOutcome() {
        metrics.acquirerCall("authorize", "approved", TimeUnit.MILLISECONDS.toNanos(120));

        var timer = registry.get("payflow.acquirer.calls")
                .tag("operation", "authorize")
                .tag("outcome", "approved")
                .timer();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isBetween(100.0, 200.0);
    }

    @Test
    void queueEnqueueAndReconciliationOutcomeAreTagged() {
        metrics.queueEnqueue(true);
        metrics.queueEnqueue(false);
        metrics.reconciliationOutcome("authorized");

        assertThat(registry.get("payflow.processing.queue.enqueue").tag("outcome", "accepted").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("payflow.processing.queue.enqueue").tag("outcome", "rejected").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("payflow.reconciliation.outcome").tag("outcome", "authorized").counter().count())
                .isEqualTo(1.0);
    }
}
