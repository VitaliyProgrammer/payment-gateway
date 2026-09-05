package com.payflow.gateway.outbox;

import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxClaimService {

    private final OutboxEventRepository repository;

    public OutboxClaimService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Коротка транзакція: захоплює пакет подій і одразу відсуває їм
     * next_attempt_at у майбутнє (оренда), після чого повертає керування.
     * Сам HTTP-виклик доставки відбувається ПОЗА цією транзакцією -
     * тримати з'єднання з базою відкритим на час мережевого виклику до
     * чужого сервера було б і марнотратно, і небезпечно під навантаженням
     * (та сама причина, з якої IdempotencyGuard і PaymentTransitionGuard на
     * попередніх стадіях тримають свої транзакції короткими).
     */
    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize, Duration leaseDuration) {
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);
        Instant leaseUntil = Instant.now().plus(leaseDuration);
        batch.forEach(event -> event.claim(leaseUntil));
        return batch;
    }
}
