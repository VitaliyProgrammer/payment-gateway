package com.payflow.gateway.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentEventType;
import com.payflow.gateway.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Викликається зсередини ЧУЖОЇ вже активної транзакції (переходу стану
 * платежу в PaymentProcessingService чи PaymentTransitionGuard) і навмисно НЕ
 * має власної @Transactional анотації. Подія має закомітитись РАЗОМ із самою
 * зміною стану, в тій самій транзакції - інакше весь сенс transactional
 * outbox зникає: саме атомарність "або обидва записи, або жоден" і є тим, що
 * робить доставку вебхуків надійною.
 */
@Component
public class OutboxEventRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(Payment payment, PaymentEventType eventType) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        WebhookEnvelope envelope = new WebhookEnvelope(eventId, eventType.wireName(), now, PaymentResponse.from(payment));
        String payload = serialize(envelope);

        repository.save(new OutboxEvent(eventId, payment.getId(), payment.getMerchantId(), eventType, payload, now));
    }

    private String serialize(WebhookEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не вдалось серіалізувати тіло вебхука", exception);
        }
    }
}
