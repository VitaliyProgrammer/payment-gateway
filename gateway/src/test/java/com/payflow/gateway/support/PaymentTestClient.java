package com.payflow.gateway.support;

import com.payflow.gateway.api.CreatePaymentRequest;
import com.payflow.gateway.api.CreateRefundRequest;
import com.payflow.gateway.api.PaymentResponse;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Тонка обгортка над {@link TestRestTemplate} для викликів /v1/payments у тестах. */
public class PaymentTestClient {

    private final TestRestTemplate restTemplate;

    public PaymentTestClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<PaymentResponse> create(String apiKey, String idempotencyKey, long amount,
            String currency) {
        return create(apiKey, idempotencyKey, amount, currency, PaymentResponse.class);
    }

    public <T> ResponseEntity<T> create(String apiKey, String idempotencyKey, long amount, String currency,
            Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<CreatePaymentRequest> request = new HttpEntity<>(new CreatePaymentRequest(amount, currency),
                headers);
        return restTemplate.exchange("/v1/payments", HttpMethod.POST, request, responseType);
    }

    public <T> ResponseEntity<T> get(String apiKey, UUID id, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return restTemplate.exchange("/v1/payments/" + id, HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    public <T> ResponseEntity<T> capture(String apiKey, UUID id, Class<T> responseType) {
        return post(apiKey, "/v1/payments/" + id + "/capture", null, responseType);
    }

    public <T> ResponseEntity<T> cancel(String apiKey, UUID id, Class<T> responseType) {
        return post(apiKey, "/v1/payments/" + id + "/cancel", null, responseType);
    }

    public <T> ResponseEntity<T> refund(String apiKey, UUID id, long amount, Class<T> responseType) {
        return post(apiKey, "/v1/payments/" + id + "/refunds", new CreateRefundRequest(amount), responseType);
    }

    private <T> ResponseEntity<T> post(String apiKey, String uri, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }
}
