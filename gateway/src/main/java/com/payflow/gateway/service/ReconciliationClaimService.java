package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Коротка транзакція: під {@code FOR UPDATE SKIP LOCKED} захоплює пакет
 * платежів, що потребують примирення, відсуває кожному {@code reconcile_next_at}
 * у майбутнє (оренда) і одразу повертає керування. Самі HTTP-виклики до еквайра
 * відбуваються ПОЗА цією транзакцією - той самий принцип, що й у
 * {@code OutboxClaimService} на стадії 5: ніколи не тримати транзакцію
 * відкритою на час мережевого виклику до чужого сервера.
 *
 * <p>Платежі, що застрягли в {@code PROCESSING} (воркер помер посеред виклику),
 * тут же переводяться в {@code NEEDS_RECONCILIATION} - далі по конвеєру всі
 * захоплені платежі однакові.
 */
@Component
public class ReconciliationClaimService {

    private final PaymentRepository paymentRepository;
    private final Duration staleProcessingAfter;

    public ReconciliationClaimService(PaymentRepository paymentRepository,
            @Value("${payflow.reconciliation.stale-processing-after-ms:60000}") long staleProcessingAfterMs) {
        this.paymentRepository = paymentRepository;
        this.staleProcessingAfter = Duration.ofMillis(staleProcessingAfterMs);
    }

    @Transactional
    public List<UUID> claimBatch(int batchSize, Duration leaseDuration) {
        Instant staleBefore = Instant.now().minus(staleProcessingAfter);
        List<Payment> batch = paymentRepository.lockReconcilableBatch(staleBefore, batchSize);
        Instant leaseUntil = Instant.now().plus(leaseDuration);

        List<UUID> claimed = new ArrayList<>(batch.size());
        for (Payment payment : batch) {
            if (payment.getStatus() == PaymentStatus.PROCESSING) {
                payment.markNeedsReconciliation();
            }
            payment.claimForReconciliation(leaseUntil);
            claimed.add(payment.getId());
        }
        return claimed;
    }
}
