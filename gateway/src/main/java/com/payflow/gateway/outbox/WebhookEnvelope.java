package com.payflow.gateway.outbox;

import com.payflow.gateway.api.PaymentResponse;
import java.time.Instant;
import java.util.UUID;

/** Форма тіла вебхука, яке реально бачить мерчант - окремо від внутрішнього OutboxEvent. */
public record WebhookEnvelope(UUID id, String type, Instant createdAt, PaymentResponse data) {
}
