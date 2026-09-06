package com.payflow.gateway.reconciliation;

import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.exception.AcquirerUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.service.ReconcileOutcomeService;
import com.payflow.gateway.service.ReconciliationClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sweeper примирення. Один екземпляр цього Runnable виконують кілька віртуальних
 * потоків одночасно (див. {@link PaymentReconcilerPool}) - той самий патерн, що
 * й {@code OutboxPoller} на стадії 5: жодного мутабельного стану екземпляра,
 * лише ін'єктовані залежності, безпечні для паралельних викликів.
 *
 * <p>Ключова відмінність від воркера обробки: тут НІКОЛИ не викликається
 * {@code authorize} повторно. Платіж, що дійшов сюди, вже міг бути списаний у
 * еквайра - тож єдине безпечне питання - "то чим усе скінчилось?"
 * ({@link AcquirerClient#getCharge}), а не "спробуймо ще раз".
 */
@Component
public class PaymentReconciler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final ReconciliationClaimService claimService;
    private final ReconcileOutcomeService outcomeService;
    private final AcquirerClient acquirerClient;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration pollInterval;

    public PaymentReconciler(ReconciliationClaimService claimService, ReconcileOutcomeService outcomeService,
            AcquirerClient acquirerClient,
            @Value("${payflow.reconciliation.batch-size:20}") int batchSize,
            @Value("${payflow.reconciliation.claim-lease-ms:30000}") long leaseMs,
            @Value("${payflow.reconciliation.poll-interval-ms:1000}") long pollIntervalMs) {
        this.claimService = claimService;
        this.outcomeService = outcomeService;
        this.acquirerClient = acquirerClient;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMillis(leaseMs);
        this.pollInterval = Duration.ofMillis(pollIntervalMs);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            List<UUID> claimed = claimService.claimBatch(batchSize, leaseDuration);

            if (claimed.isEmpty()) {
                sleep(pollInterval);
                continue;
            }

            for (UUID paymentId : claimed) {
                reconcile(paymentId);
            }
        }
    }

    private void reconcile(UUID paymentId) {
        try {
            Optional<AcquirerOutcome> outcome = acquirerClient.getCharge(paymentId);

            if (outcome.isEmpty()) {
                log.info("Acquirer has no record of payment {}, marking FAILED", paymentId);
                outcomeService.applyNoChargeAtAcquirer(paymentId);
            } else if (outcome.get() == AcquirerOutcome.APPROVED) {
                log.info("Reconciled payment {} as AUTHORIZED", paymentId);
                outcomeService.applyAuthorized(paymentId);
            } else {
                log.info("Reconciled payment {} as DECLINED", paymentId);
                outcomeService.applyDeclined(paymentId);
            }
        } catch (AcquirerUnavailableException exception) {
            log.warn("Reconciliation of payment {} still cannot reach the acquirer: {}",
                    paymentId, exception.getMessage());
            outcomeService.scheduleRetryOrFail(paymentId, exception.getMessage());
        } catch (RuntimeException exception) {
            // Не даємо одному проблемному платежу вбити потік sweeper-а.
            log.error("Unexpected error reconciling payment {}", paymentId, exception);
            outcomeService.scheduleRetryOrFail(paymentId, exception.getMessage());
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
