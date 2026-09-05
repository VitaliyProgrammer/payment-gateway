package com.payflow.gateway.entity;

import com.payflow.gateway.entity.status.OutboxEventStatus;
import com.payflow.gateway.entity.status.PaymentEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Persistable з тієї самої причини, що й IdempotencyRecord на стадії 2: id -
 * це UUID, згенерований застосунком, а не базою, тож без цього Spring Data
 * робив би зайвий SELECT-перед-INSERT замість чистого INSERT.
 *
 * <p>Немає @Version - і це свідомо, на відміну від Payment. Конкурентний
 * доступ до рядка тут виключає сам SELECT ... FOR UPDATE SKIP LOCKED у
 * репозиторії: поки один поллер тримає рядок захопленим, жоден інший його
 * навіть не побачить у своєму запиті. Оптимістичне блокування розв'язувало б
 * проблему, якої тут просто не існує.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private PaymentEventType eventType;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxEventStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Transient
    private boolean isNew = true;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, UUID paymentId, UUID merchantId, PaymentEventType eventType, String payload,
            Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
    }

    /** Захоплення "в оренду" - див. коментар до next_attempt_at у міграції V6. */
    public void claim(Instant leaseUntil) {
        this.attempts++;
        this.nextAttemptAt = leaseUntil;
    }

    public void markDelivered() {
        this.status = OutboxEventStatus.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    public void scheduleRetry(Instant nextAttempt, String error) {
        this.nextAttemptAt = nextAttempt;
        this.lastError = truncate(error);
    }

    public void markDeadLetter(String error) {
        this.status = OutboxEventStatus.DEAD_LETTER;
        this.lastError = truncate(error);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public PaymentEventType getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
