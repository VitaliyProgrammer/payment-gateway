package com.payflow.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code version} - це поле для оптимістичного блокування Hibernate (звичайна
 * колонка {@code @Version}). Через нього проходить кожен перехід стану - авторизація,
 * захоплення, скасування, повернення - тож два переходи, що змагаються за один і той
 * самий рядок, ніколи не зможуть обидва мовчки успішно завершитись: другий записувач
 * отримає {@link jakarta.persistence.OptimisticLockException} замість того, щоб
 * перезаписати зміну першого. Реальна перевірка цього під конкурентним навантаженням
 * буде на стадії 4; але саме поле закладене з самого початку, бо додавати
 * оптимістичне блокування до сутності, яка вже використовується "в бою", - значно
 * ризикованіше, ніж одразу почати з ним.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "captured_amount", nullable = false)
    private long capturedAmount;

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID merchantId, long amount, String currency) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.capturedAmount = 0;
        this.refundedAmount = 0;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public long getCapturedAmount() {
        return capturedAmount;
    }

    public long getRefundedAmount() {
        return refundedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
