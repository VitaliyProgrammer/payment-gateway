package com.payflow.gateway.outbox;

import com.payflow.gateway.util.HmacSigner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookClient {

    private static final String SIGNATURE_HEADER = "X-Payflow-Signature";
    private static final String EVENT_TYPE_HEADER = "X-Payflow-Event-Type";

    private final RestClient restClient;

    public WebhookClient(RestClient webhookRestClient) {
        this.restClient = webhookRestClient;
    }

    /**
     * retrieve() без .onStatus(...) навмисно - дефолтна поведінка RestClient
     * вже кидає виняток на будь-яку не-2xx відповідь, а саме це й потрібно:
     * будь-що, крім успіху, має вважатись невдалою доставкою й піти на
     * ретрай/backoff у виклика (OutboxPoller).
     */
    public void send(String url, String webhookSecret, String eventTypeWireName, String payload) {
        String signature = HmacSigner.sign(webhookSecret, payload);

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, signature)
                .header(EVENT_TYPE_HEADER, eventTypeWireName)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
