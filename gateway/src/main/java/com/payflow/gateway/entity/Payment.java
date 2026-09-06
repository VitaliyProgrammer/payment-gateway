package com.payflow.gateway.entity;

import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.exception.InvalidPaymentStateException;
import com.payflow.gateway.exception.RefundExceedsCapturedAmountException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
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

    /**
     * Стан примирення (стадія 6). Заповнені лише для платежів, які пройшли через
     * NEEDS_RECONCILIATION: {@code reconcileAttempts} рахує спроби sweeper-а
     * дізнатись підсумок у еквайра, {@code reconcileNextAt} - і backoff між
     * ними, і оренда на час активної спроби (див. міграцію V7).
     */
    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(name = "reconcile_next_at")
    private Instant reconcileNextAt;

    @Column(name = "last_reconcile_error")
    private String lastReconcileError;

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

    /**
     * Переходи стану навмисно виражені як іменовані методи (markProcessing,
     * markAuthorized...), а не як голий publicний setStatus(). Так неможливо
     * випадково виставити довільний статус з довільного місця коду - кожен
     * перехід перевіряє, з якого стану він дозволений, і кидає виняток інакше.
     * Це не заміна @Version (той захищає від гонки на рівні бази), а захист від
     * помилки логіки в межах одного потоку виконання.
     */
    public void markProcessing() {
        requireStatus(PaymentStatus.CREATED);
        this.status = PaymentStatus.PROCESSING;
        touch();
    }

    /**
     * NEEDS_RECONCILIATION (стадія 6): виклик до еквайра завершився таймаутом
     * читання або 5xx - запит пішов, але підсумок невідомий. Не термінальний
     * стан: фоновий sweeper пізніше запитає в еквайра, чим усе скінчилось, і
     * доведе платіж до AUTHORIZED / DECLINED / FAILED. Дозволено лише з
     * PROCESSING - у цей момент воркер уже зробив markProcessing і саме
     * викликав еквайра.
     */
    public void markNeedsReconciliation() {
        requireStatus(PaymentStatus.PROCESSING);
        this.status = PaymentStatus.NEEDS_RECONCILIATION;
        this.reconcileNextAt = Instant.now();
        touch();
    }

    /**
     * Захоплення платежу sweeper-ом "в оренду": відсуває reconcileNextAt у
     * майбутнє (щоб інший sweeper чи інстанс його не підхопив) і рахує спробу.
     * Той самий патерн, що й {@code OutboxEvent.claim} на стадії 5.
     */
    public void claimForReconciliation(Instant leaseUntil) {
        requireStatus(PaymentStatus.NEEDS_RECONCILIATION);
        this.reconcileAttempts++;
        this.reconcileNextAt = leaseUntil;
        touch();
    }

    /**
     * Еквайр і сам зараз недоступний - відкласти наступну спробу примирення
     * (backoff) і запам'ятати помилку. Лічильник спроб уже збільшив
     * {@link #claimForReconciliation}, тут його чіпати не треба - так само, як
     * {@code OutboxEvent.scheduleRetry} не чіпає attempts.
     */
    public void scheduleReconcile(Instant nextAttemptAt, String error) {
        requireStatus(PaymentStatus.NEEDS_RECONCILIATION);
        this.reconcileNextAt = nextAttemptAt;
        this.lastReconcileError = truncate(error);
        touch();
    }

    public void markAuthorized() {
        requireStatus(PaymentStatus.PROCESSING, PaymentStatus.NEEDS_RECONCILIATION);
        this.status = PaymentStatus.AUTHORIZED;
        touch();
    }

    public void markDeclined() {
        requireStatus(PaymentStatus.PROCESSING, PaymentStatus.NEEDS_RECONCILIATION);
        this.status = PaymentStatus.DECLINED;
        touch();
    }

    /**
     * FAILED тут означає "збій на нашому боці чи в комунікації з еквайром"
     * (наприклад, еквайр не відповів), на відміну від DECLINED - "еквайр
     * відповів і відмовив". Дозволено з CREATED, PROCESSING і
     * NEEDS_RECONCILIATION: перше - коли навіть спроба зв'язатись з еквайром не
     * відбулась; друге - коли вона провалилась під час виконання; третє - коли
     * примирення вичерпало спроби або еквайр підтвердив, що запиту не бачив.
     */
    public void markFailed() {
        requireStatus(PaymentStatus.CREATED, PaymentStatus.PROCESSING, PaymentStatus.NEEDS_RECONCILIATION);
        this.status = PaymentStatus.FAILED;
        touch();
    }

    /**
     * Повне захоплення раніше заблокованих коштів. Часткове захоплення (взяти
     * менше, ніж було авторизовано) - реальна фіча платіжних систем, але для
     * цілей цього проєкту вона нічого не додає до історії про конкурентність,
     * тож свідомо не реалізована.
     */
    public void capture() {
        requireStatus(PaymentStatus.AUTHORIZED);
        this.capturedAmount = this.amount;
        this.status = PaymentStatus.CAPTURED;
        touch();
    }

    public void cancel() {
        requireStatus(PaymentStatus.AUTHORIZED);
        this.status = PaymentStatus.CANCELED;
        touch();
    }

    /**
     * На відміну від capture/cancel (взаємовиключні одноразові дії, де програш
     * гонки за версію - це чесна відмова), повернення - адитивна операція:
     * кілька часткових повернень можуть послідовно успішно відбутись, поки їх
     * сума не вичерпає captured_amount. Тому виклик цього методу (через
     * PaymentTransitionGuard) очікує на РЕТРАЙ при програші оптимістичного
     * блокування, а не на одноразову відмову - див. PaymentLifecycleService.
     */
    public void refund(long refundAmount) {
        requireStatus(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);

        long remaining = this.capturedAmount - this.refundedAmount;
        if (refundAmount > remaining) {
            throw new RefundExceedsCapturedAmountException(this.id, refundAmount, remaining);
        }

        this.refundedAmount += refundAmount;
        this.status = (this.refundedAmount == this.capturedAmount)
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        touch();
    }

    private void requireStatus(PaymentStatus... allowed) {
        for (PaymentStatus candidate : allowed) {
            if (this.status == candidate) {
                return;
            }
        }
        throw new InvalidPaymentStateException(
                "Payment " + id + " is in status " + status + ", expected one of " + Arrays.toString(allowed));
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
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

    public int getReconcileAttempts() {
        return reconcileAttempts;
    }

    public Instant getReconcileNextAt() {
        return reconcileNextAt;
    }

    public String getLastReconcileError() {
        return lastReconcileError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
