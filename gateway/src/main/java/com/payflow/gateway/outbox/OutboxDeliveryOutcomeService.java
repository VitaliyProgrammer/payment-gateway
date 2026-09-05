package com.payflow.gateway.outbox;

import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxDeliveryOutcomeService {

    private final OutboxEventRepository repository;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public OutboxDeliveryOutcomeService(OutboxEventRepository repository,
            @Value("${payflow.webhooks.max-attempts:5}") int maxAttempts,
            @Value("${payflow.webhooks.base-backoff-ms:200}") long baseBackoffMs,
            @Value("${payflow.webhooks.max-backoff-ms:5000}") long maxBackoffMs) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
    }

    @Transactional
    public void markDelivered(UUID eventId) {
        repository.findById(eventId).ifPresent(OutboxEvent::markDelivered);
    }

    @Transactional
    public void recordFailure(UUID eventId, String error) {
        repository.findById(eventId).ifPresent(event -> {
            if (event.getAttempts() >= maxAttempts) {
                event.markDeadLetter(error);
            } else {
                event.scheduleRetry(Instant.now().plus(backoffFor(event.getAttempts())), error);
            }
        });
    }

    /**
     * Експоненційний backoff, обмежений зверху: перша повторна спроба скоро,
     * кожна наступна вдвічі пізніше за попередню, але не пізніше maxBackoff -
     * щоб черга подій, що ось-ось підуть у dead-letter, не чекала годинами
     * між останніми спробами.
     */
    private Duration backoffFor(int attempts) {
        long millis = baseBackoff.toMillis() * (1L << Math.min(Math.max(attempts - 1, 0), 20));
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }
}
