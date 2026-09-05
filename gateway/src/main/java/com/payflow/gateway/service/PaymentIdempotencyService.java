package com.payflow.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.gateway.api.CreatePaymentRequest;
import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.status.IdempotencyStatus;
import com.payflow.gateway.exception.IdempotencyInProgressException;
import com.payflow.gateway.exception.IdempotencyKeyConflictException;
import com.payflow.gateway.idempotency.*;
import com.payflow.gateway.repository.IdempotencyRecordRepository;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.util.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Оркеструє створення платежу так, щоб той самий Idempotency-Key ніколи не
 * створив два платежі, навіть якщо сотні однакових запитів прийдуть буквально
 * одночасно.
 *
 * <p>Механіка: спершу застосунок намагається "застовпити" ключ у таблиці
 * idempotency_records - унікальний індекс у базі даних вирішує, хто перший
 * (див. {@link IdempotencyGuard}). Переможець гонки виконує справжнє створення
 * платежу і зберігає готову відповідь. Усі інші учасники просто короткими
 * ітераціями чекають, поки переможець завершить роботу, а потім читають ту саму
 * збережену відповідь - тож усі виклики з одним ключем завжди отримують
 * побайтово однаковий результат, а не просто "якийсь платіж з тим самим id".
 */
@Service
public class PaymentIdempotencyService {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    private final IdempotencyGuard guard;
    private final IdempotencyRecordRepository repository;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentIdempotencyService(IdempotencyGuard guard, IdempotencyRecordRepository repository,
            PaymentService paymentService, ObjectMapper objectMapper) {
        this.guard = guard;
        this.repository = repository;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    public PaymentResponse createIdempotently(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        String fingerprint = Sha256.hex(request.amount() + "|" + request.currency());
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);

        while (true) {
            Optional<IdempotencyRecord> claimed = tryClaim(merchantId, idempotencyKey, fingerprint);

            if (claimed.isPresent()) {
                return executeAndComplete(claimed.get(), merchantId, request);
            }

            Optional<IdempotencyRecord> existing =
                    repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);

            if (existing.isEmpty()) {
                // Той, хто щойно володів ключем, звільнив його рівно між нашою
                // невдалою спробою захопити і цим читанням (типово - через
                // провал операції). Ключ знову вільний - пробуємо захопити
                // його самі, замість того щоб чекати того, що вже не станеться.
                continue;
            }

            IdempotencyRecord record = existing.get();

            if (!record.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }

            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return deserialize(record.getResponseBody());
            }

            if (Instant.now().isAfter(deadline)) {
                throw new IdempotencyInProgressException(idempotencyKey);
            }

            sleep(POLL_INTERVAL);
        }
    }

    /**
     * Обгортка навколо {@link IdempotencyGuard#claim}: ловить
     * {@link DataIntegrityViolationException} тут, зовні транзакції методу
     * claim() (див. Javadoc там - чому саме зовні, а не всередині). Порожній
     * результат означає лише "ключ уже зайнятий", а не помилку.
     */
    private Optional<IdempotencyRecord> tryClaim(UUID merchantId, String idempotencyKey, String fingerprint) {
        try {
            return Optional.of(guard.claim(merchantId, idempotencyKey, fingerprint));
        } catch (DataIntegrityViolationException exception) {
            return Optional.empty();
        }
    }

    private PaymentResponse executeAndComplete(IdempotencyRecord record, UUID merchantId,
            CreatePaymentRequest request) {
        try {
            Payment payment = paymentService.create(merchantId, request);
            PaymentResponse response = PaymentResponse.from(payment);
            guard.complete(record, HttpStatus.CREATED.value(), serialize(response));
            return response;
        } catch (RuntimeException exception) {
            guard.release(record);
            throw exception;
        }
    }

    private String serialize(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не вдалось серіалізувати відповідь для збереження ідемпотентності",
                    exception);
        }
    }

    private PaymentResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не вдалось десеріалізувати збережену ідемпотентну відповідь",
                    exception);
        }
    }

    /**
     * Звичайний sleep, а не щось складніше - ми на віртуальних потоках, тож
     * навіть сотні одночасно "заснулих" запитів не займають жодного платформного
     * потоку і практично нічого не коштують.
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Перервано під час очікування завершення ідемпотентного запиту",
                    exception);
        }
    }
}
