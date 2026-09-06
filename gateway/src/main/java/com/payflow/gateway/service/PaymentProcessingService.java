package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentEventType;
import com.payflow.gateway.exception.InvalidPaymentStateException;
import com.payflow.gateway.metrics.PaymentMetrics;
import com.payflow.gateway.outbox.OutboxEventRecorder;
import com.payflow.gateway.repository.PaymentRepository;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Кожен перехід стану - окрема, коротка транзакція, яка щоразу заново читає
 * рядок з бази. Це важливо: якщо тримати один і той самий Payment-об'єкт від
 * початку до кінця довгої обробки (виклик до еквайра може тривати сотні
 * мілісекунд), Hibernate звірятиме версію проти значення, зчитаного ще ДО
 * виклику - і будь-яка паралельна зміна цього рядка (стадія 4 додасть capture/
 * cancel) лишиться непоміченою до самого кінця замість того, щоб провалитись
 * одразу в потрібному місці.
 */
@Service
public class PaymentProcessingService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRecorder outboxEventRecorder;
    private final PaymentMetrics metrics;

    public PaymentProcessingService(PaymentRepository paymentRepository, OutboxEventRecorder outboxEventRecorder,
            PaymentMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRecorder = outboxEventRecorder;
        this.metrics = metrics;
    }

    /**
     * markProcessing навмисно не передає тип події - PROCESSING суто
     * внутрішній, транзитний стан, про який мерчанту сповіщати нічого:
     * платіж або підтвердиться, або ні, і саме про це він дізнається з
     * вебхука.
     */
    @Transactional
    public boolean markProcessing(UUID paymentId) {
        return transition(paymentId, Payment::markProcessing, null);
    }

    /**
     * Як і markProcessing - без outbox-події: NEEDS_RECONCILIATION теж
     * внутрішній, транзитний стан. Мерчанту поки нема про що повідомляти,
     * підсумок ще невідомий; про фінал (AUTHORIZED / DECLINED / FAILED) він
     * дізнається з вебхука, коли примирення завершиться.
     */
    @Transactional
    public boolean markNeedsReconciliation(UUID paymentId) {
        return transition(paymentId, Payment::markNeedsReconciliation, null);
    }

    @Transactional
    public boolean markAuthorized(UUID paymentId) {
        return transition(paymentId, Payment::markAuthorized, PaymentEventType.PAYMENT_AUTHORIZED);
    }

    @Transactional
    public boolean markDeclined(UUID paymentId) {
        return transition(paymentId, Payment::markDeclined, PaymentEventType.PAYMENT_DECLINED);
    }

    @Transactional
    public boolean markFailed(UUID paymentId) {
        return transition(paymentId, Payment::markFailed, PaymentEventType.PAYMENT_FAILED);
    }

    /**
     * Повертає false замість того, щоб кидати виняток, коли платіж не
     * знайдено, вже в неочікуваному стані, або програв гонку за версію -
     * воркер (єдиний виклик на цій стадії) має просто пропустити такий платіж
     * і забрати наступний з черги, а не впасти цілим потоком через один
     * проблемний запис.
     *
     * <p>Запис outbox-події (коли eventType не null) відбувається тут, у тій
     * самій транзакції, що й сам перехід стану - обидва зміни комітяться
     * разом або не комітяться взагалі.
     */
    private boolean transition(UUID paymentId, Consumer<Payment> transition, PaymentEventType eventType) {
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            return false;
        }

        Payment payment = maybePayment.get();
        try {
            transition.accept(payment);
        } catch (InvalidPaymentStateException | ObjectOptimisticLockingFailureException exception) {
            return false;
        }

        if (eventType != null) {
            outboxEventRecorder.record(payment, eventType);
        }
        metrics.transitioned(payment.getStatus());
        return true;
    }
}
