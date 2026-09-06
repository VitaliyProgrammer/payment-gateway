package com.payflow.gateway.metrics;

import com.payflow.gateway.entity.status.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Тонкий фасад над {@link MeterRegistry} для доменних метрик шлюзу (стадія 7).
 * Лічильники й таймери, які викликаються з кількох місць; gauge-и, прив'язані до
 * живого стану (глибина черги, стан circuit breaker-а, кількість PENDING-подій),
 * реєструються окремо в {@link GaugesMetrics}.
 *
 * <p>Тут навмисно немає жодної логіки, крім самого запису метрики - жоден бізнес-
 * клас не має чекати чи падати через метрики, тож усі виклики сюди робляться
 * після того, як основна дія вже відбулась.
 *
 * <p>{@code registry.counter(...)} / {@code Timer.builder(...)} самі кешують
 * метрику за іменем і тегами, тож повторні виклики з тими самими тегами дешеві -
 * окремо кешувати Meter-и тут не потрібно.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Прийнято новий платіж. Тег - валюта (низька кардинальність). Назва
     * навмисно {@code accepted}, а не {@code created}: суфікс {@code _created} у
     * Prometheus/OpenMetrics зарезервований під позначку часу створення серії, і
     * лічильник {@code payflow.payments.created} перетворився б на
     * {@code payflow_payments_total}.
     */
    public void paymentAccepted(String currency) {
        Counter.builder("payflow.payments.accepted")
                .tag("currency", currency == null ? "unknown" : currency.toUpperCase(Locale.ROOT))
                .register(registry)
                .increment();
    }

    /**
     * Відбувся перехід стану платежу. Тег - НОВИЙ статус; сюди потрапляють і
     * транзитні переходи (PROCESSING, NEEDS_RECONCILIATION), і термінальні
     * (AUTHORIZED, DECLINED, FAILED, CAPTURED, ...).
     */
    public void transitioned(PaymentStatus to) {
        Counter.builder("payflow.payments.transitions")
                .tag("status", to.name())
                .register(registry)
                .increment();
    }

    /**
     * Виклик до еквайра завершився. {@code operation} - authorize | get_charge,
     * {@code outcome} - approved | declined | not_found | unavailable | rejected
     * | circuit_open | error.
     */
    public void acquirerCall(String operation, String outcome, long durationNanos) {
        Timer.builder("payflow.acquirer.calls")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** Спроба покласти платіж у чергу обробки. Тег - accepted | rejected. */
    public void queueEnqueue(boolean accepted) {
        Counter.builder("payflow.processing.queue.enqueue")
                .tag("outcome", accepted ? "accepted" : "rejected")
                .register(registry)
                .increment();
    }

    /**
     * Sweeper примирення дійшов якогось підсумку по платежу. {@code outcome} -
     * authorized | declined | no_charge | retry_scheduled | exhausted_failed.
     */
    public void reconciliationOutcome(String outcome) {
        Counter.builder("payflow.reconciliation.outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
