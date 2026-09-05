package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.exception.PaymentNotFoundException;
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
 */
@Component
public class PaymentTransitionGuard {

    private final PaymentRepository paymentRepository;

    public PaymentTransitionGuard(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment capture(UUID paymentId, UUID merchantId) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.capture();
        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment cancel(UUID paymentId, UUID merchantId) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.cancel();
        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment refund(UUID paymentId, UUID merchantId, long amount) {
        Payment payment = findOwnedPayment(paymentId, merchantId);
        payment.refund(amount);
        return paymentRepository.saveAndFlush(payment);
    }

    private Payment findOwnedPayment(UUID paymentId, UUID merchantId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
