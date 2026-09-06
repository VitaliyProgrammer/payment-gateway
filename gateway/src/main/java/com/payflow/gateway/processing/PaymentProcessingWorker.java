package com.payflow.gateway.processing;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.exception.AcquirerUnavailableException;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.service.PaymentProcessingService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Один екземпляр цього Runnable виконують кілька віртуальних потоків одночасно
 * (див. {@link PaymentWorkerPool}) - тут немає жодного мутабельного поля-стану
 * екземпляра, лише ін'єктовані залежності, які й самі безпечні для паралельних
 * викликів (репозиторій, HTTP-клієнт), тож поділ одного Runnable між кількома
 * потоками тут не проблема.
 */
@Component
public class PaymentProcessingWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingWorker.class);

    private final PaymentProcessingQueue queue;
    private final PaymentRepository paymentRepository;
    private final PaymentProcessingService processingService;
    private final AcquirerClient acquirerClient;

    public PaymentProcessingWorker(PaymentProcessingQueue queue, PaymentRepository paymentRepository,
            PaymentProcessingService processingService, AcquirerClient acquirerClient) {
        this.queue = queue;
        this.paymentRepository = paymentRepository;
        this.processingService = processingService;
        this.acquirerClient = acquirerClient;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            UUID paymentId;
            try {
                paymentId = queue.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }

            process(paymentId);
        }
    }

    private void process(UUID paymentId) {
        // Рядка може не бути, якщо слот у черзі зарезервували ще до запису в
        // базу (див. PaymentIdempotencyService), а сам запис пізніше провалився.
        // Це самозагоюваний рідкісний випадок, а не помилка воркера.
        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            log.warn("Payment {} was queued but no longer exists, skipping", paymentId);
            return;
        }

        if (!processingService.markProcessing(paymentId)) {
            log.warn("Payment {} could not be moved to PROCESSING, skipping", paymentId);
            return;
        }

        Payment payment = maybePayment.get();

        try {
            AcquirerOutcome outcome = acquirerClient.authorize(paymentId, payment.getAmount(), payment.getCurrency());
            if (outcome == AcquirerOutcome.APPROVED) {
                processingService.markAuthorized(paymentId);
            } else {
                processingService.markDeclined(paymentId);
            }
        } catch (AcquirerUnavailableException exception) {
            // Таймаут читання / 5xx / розімкнений circuit breaker: запит до
            // еквайра пішов (або міг піти), але підсумок НЕВІДОМИЙ. Позначити
            // FAILED тут - це або втратити реальну авторизацію, або спонукати
            // мерчанта до повторного платежу за вже заблоковані кошти. Замість
            // цього - NEEDS_RECONCILIATION: sweeper (див. пакет reconciliation)
            // пізніше ЗАПИТАЄ еквайра, чим усе скінчилось, а не зарядить повторно.
            log.warn("Acquirer outcome unknown for payment {} ({}), moving to NEEDS_RECONCILIATION",
                    paymentId, exception.getMessage());
            processingService.markNeedsReconciliation(paymentId);
        } catch (RuntimeException exception) {
            // 4xx чи будь-яка інша несподівана помилка: ретрай не допоможе.
            // Головне - не дати платежу застрягти в PROCESSING мовчки і не
            // впустити виняток, який вбив би цей потік воркера назавжди.
            log.warn("Acquirer call failed for payment {}: {}", paymentId, exception.getMessage());
            processingService.markFailed(paymentId);
        }
    }
}
