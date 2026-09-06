package com.payflow.gateway.support;

import java.util.UUID;

public record RecordedWebhookRequest(String body, String signature, String eventType) {

    /**
     * Чи це вебхук саме про цей платіж. Тіло - JSON-конверт із вкладеним
     * {@code data.id = <paymentId>}; звіряємо точним рядком, тож збіг з id самої
     * події (теж UUID) виключений.
     */
    public boolean isFor(UUID paymentId) {
        return body != null && body.contains("\"id\":\"" + paymentId + "\"");
    }
}
