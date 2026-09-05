package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.exception.InvalidPaymentStateException;
import com.payflow.gateway.repository.PaymentRepository;
import java.util.Optional;
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

    public PaymentProcessingService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public boolean markProcessing(UUID paymentId) {
        return transition(paymentId, Payment::markProcessing);
    }

    @Transactional
    public boolean markAuthorized(UUID paymentId) {
        return transition(paymentId, Payment::markAuthorized);
    }

    @Transactional
    public boolean markDeclined(UUID paymentId) {
        return transition(paymentId, Payment::markDeclined);
    }

    @Transactional
    public boolean markFailed(UUID paymentId) {
        return transition(paymentId, Payment::markFailed);
    }

    /**
     * Повертає false замість того, щоб кидати виняток, коли платіж не
     * знайдено, вже в неочікуваному стані, або програв гонку за версію -
     * воркер (єдиний виклик на цій стадії) має просто пропустити такий платіж
     * і забрати наступний з черги, а не впасти цілим потоком через один
     * проблемний запис.
     */
    private boolean transition(UUID paymentId, java.util.function.Consumer<Payment> transition) {
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            return false;
        }

        try {
            transition.accept(maybePayment.get());
            return true;
        } catch (InvalidPaymentStateException | ObjectOptimisticLockingFailureException exception) {
            return false;
        }
    }
}
