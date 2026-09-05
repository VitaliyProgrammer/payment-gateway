package com.payflow.gateway.processing;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AcquirerClient {

    private final RestClient restClient;

    public AcquirerClient(RestClient acquirerRestClient) {
        this.restClient = acquirerRestClient;
    }

    public AcquirerOutcome authorize(UUID paymentId, long amount, String currency) {
        AcquirerChargeResponse response = restClient.post()
                .uri("/charges")
                .body(new AcquirerChargeRequest(paymentId.toString(), amount, currency))
                .retrieve()
                .body(AcquirerChargeResponse.class);

        return response.outcome();
    }
}
