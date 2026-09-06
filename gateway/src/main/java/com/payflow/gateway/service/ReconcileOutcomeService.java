package com.payflow.gateway.service;

import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.reconciliation.PaymentReconciler;
import com.payflow.gateway.repository.PaymentRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Застосовує підсумок примирення до платежу. Окремий бін від
 * {@link PaymentReconciler} з тієї самої причини, що й {@code PaymentTransitionGuard}
 * окремий від свого викликача на стадії 4: інакше Spring-проксі не перехопив би
 * {@code @Transactional} при виклику з того самого класу (self-invocation).
 *
 * <p>Переходи AUTHORIZED / DECLINED / FAILED делеговані
 * {@link PaymentProcessingService} - там уже є і власна транзакція, і запис
 * outbox-події (мерчант нарешті дізнається фінал), і безпечне повернення
 * {@code false}, якщо платіж хтось устиг довести до кінця раніше.
 */
@Component
public class ReconcileOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileOutcomeService.class);

    private final PaymentProcessingService processingService;
    private final PaymentRepository paymentRepository;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public ReconcileOutcomeService(PaymentProcessingService processingService, PaymentRepository paymentRepository,
            @Value("${payflow.reconciliation.max-attempts:10}") int maxAttempts,
            @Value("${payflow.reconciliation.base-backoff-ms:1000}") long baseBackoffMs,
            @Value("${payflow.reconciliation.max-backoff-ms:30000}") long maxBackoffMs) {
        this.processingService = processingService;
        this.paymentRepository = paymentRepository;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
    }

    /** Еквайр підтвердив: платіж авторизовано. */
    public void applyAuthorized(UUID paymentId) {
        processingService.markAuthorized(paymentId);
    }

    /** Еквайр підтвердив: платіж відхилено. */
    public void applyDeclined(UUID paymentId) {
        processingService.markDeclined(paymentId);
    }

    /**
     * Еквайр цього платежу не бачив (404) - жодного списання не відбулось,
     * тож FAILED тут чесний і остаточний.
     */
    public void applyNoChargeAtAcquirer(UUID paymentId) {
        processingService.markFailed(paymentId);
    }

    /**
     * Еквайр і сам зараз недоступний. Відкладаємо наступну спробу (експоненційний
     * backoff, обмежений зверху - той самий розрахунок, що й у
     * {@code OutboxDeliveryOutcomeService}), а коли спроби вичерпано - чесно
     * позначаємо FAILED з гучним логом, а не крутимось вічно.
     */
    @Transactional
    public void scheduleRetryOrFail(UUID paymentId, String error) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.NEEDS_RECONCILIATION) {
                // Хтось уже довів платіж до кінця між нашим захопленням і зараз.
                return;
            }
            if (payment.getReconcileAttempts() >= maxAttempts) {
                log.error("Reconciliation of payment {} exhausted after {} attempts, marking FAILED (last error: {})",
                        paymentId, payment.getReconcileAttempts(), error);
                payment.markFailed();
            } else {
                payment.scheduleReconcile(Instant.now().plus(backoffFor(payment.getReconcileAttempts())), error);
            }
        });
    }

    private Duration backoffFor(int attempts) {
        long millis = baseBackoff.toMillis() * (1L << Math.min(Math.max(attempts - 1, 0), 20));
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }
}
