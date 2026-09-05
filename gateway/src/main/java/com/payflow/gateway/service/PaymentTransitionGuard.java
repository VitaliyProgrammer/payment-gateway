package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentEventType;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.exception.PaymentNotFoundException;
import com.payflow.gateway.outbox.OutboxEventRecorder;
import com.payflow.gateway.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Кожен метод тут - одна ізольована спроба переходу стану, з власним читанням
 * рядка з бази. saveAndFlush() у кінці кожного методу навмисний: він
 * форсує перевірку @Version ЗАРАЗ, у межах цієї транзакції, а не десь потім
 * при коміті - так виклик (PaymentLifecycleService) отримує
 * ObjectOptimisticLockingFailureException одразу з цього виклику й може
 * вирішити, ретраїти чи здатись, замість здогадуватись за результатами пізніше.
 *
 * <p>Клас окремий від {@link PaymentLifecycleService} з тієї самої причини, що
 * й {@code IdempotencyGuard} окремий від {@code PaymentIdempotencyService} на
 * стадії 2: якби ретрай-цикл викликав ці методи як this.capture(...) з того
 * самого класу, Spring не зміг би перехопити виклик своїм проксі, і
 * @Transactional тихо ігнорувалась би (self-invocation).
 *
 * <p>Запис outbox-події тут відбувається в тій самій транзакції, що й сама
 * зміна стану - той самий принцип, що й у PaymentProcessingService.
 */
@Component
public class PaymentTransitionGuard {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRecorder outboxEventRecorder;

    public PaymentTransitionGuard(PaymentRepository paymentRepository, OutboxEventRecorder outboxEventRecorder) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRecorder = outboxEventRecorder;
    }

    @Transactional
    public Payment capture(UUID paymentId, UUID merchantId) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.capture();
        outboxEventRecorder.record(payment, PaymentEventType.PAYMENT_CAPTURED);
        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment cancel(UUID paymentId, UUID merchantId) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.cancel();
        outboxEventRecorder.record(payment, PaymentEventType.PAYMENT_CANCELED);
        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment refund(UUID paymentId, UUID merchantId, long amount) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.refund(amount);

        PaymentEventType eventType = payment.getStatus() == PaymentStatus.REFUNDED
                ? PaymentEventType.PAYMENT_REFUNDED
                : PaymentEventType.PAYMENT_PARTIALLY_REFUNDED;
        outboxEventRecorder.record(payment, eventType);

        return paymentRepository.saveAndFlush(payment);
    }

    private Payment findOwnedPayment(UUID paymentId, UUID merchantId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
