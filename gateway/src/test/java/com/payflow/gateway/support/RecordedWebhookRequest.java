package com.payflow.gateway.support;

public record RecordedWebhookRequest(String body, String signature, String eventType) {
}
