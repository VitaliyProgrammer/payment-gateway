package com.payflow.gateway.metrics;

import com.payflow.gateway.entity.status.OutboxEventStatus;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.processing.AcquirerCircuitBreaker;
import com.payflow.gateway.processing.PaymentProcessingQueue;
import com.payflow.gateway.repository.OutboxEventRepository;
import com.payflow.gateway.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Реєструє gauge-метрики, значення яких - це миттєвий знімок живого стану, а не
 * подія (стадія 7). Micrometer опитує наведені тут функції в момент scrape-у
 * {@code /actuator/prometheus}, тож для outbox/reconciliation це count-запит на
 * кожен scrape - при типовому інтервалі 5-15с це дешево.
 *
 * <p>Окремо від {@link PaymentMetrics}, щоб уникнути циклу залежностей:
 * {@link PaymentProcessingQueue} залежить від {@link PaymentMetrics}
 * (лічильник enqueue), а цей клас залежить від самої черги.
 */
@Configuration(proxyBeanMethods = false)
public class GaugesMetrics {

    public GaugesMetrics(MeterRegistry registry, PaymentProcessingQueue queue, AcquirerCircuitBreaker circuitBreaker,
                         PaymentRepository paymentRepository, OutboxEventRepository outboxEventRepository) {

        Gauge.builder("payflow.processing.queue.depth", queue, PaymentProcessingQueue::size)
                .description("Payments waiting in the in-memory processing queue")
                .register(registry);

        Gauge.builder("payflow.acquirer.circuit_breaker.state", circuitBreaker, AcquirerCircuitBreaker::stateCode)
                .description("Acquirer circuit breaker: 0=closed, 1=half-open, 2=open")
                .register(registry);

        Gauge.builder("payflow.outbox.pending", outboxEventRepository,
                        repo -> repo.countByStatus(OutboxEventStatus.PENDING))
                .description("Outbox events awaiting delivery")
                .register(registry);

        Gauge.builder("payflow.outbox.dead_letter", outboxEventRepository,
                        repo -> repo.countByStatus(OutboxEventStatus.DEAD_LETTER))
                .description("Outbox events that exhausted all delivery attempts")
                .register(registry);

        Gauge.builder("payflow.reconciliation.pending", paymentRepository,
                        repo -> repo.countByStatus(PaymentStatus.NEEDS_RECONCILIATION))
                .description("Payments awaiting reconciliation with the acquirer")
                .register(registry);
    }
}
