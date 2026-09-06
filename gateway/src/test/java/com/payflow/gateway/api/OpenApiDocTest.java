package com.payflow.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.gateway.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Опис API (OpenAPI + Swagger UI) віддається без ключа й містить платіжні
 * ендпоінти та схему автентифікації.
 */
class OpenApiDocTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("/v3/api-docs is public and describes the payments API")
    void apiDocsAreExposed() {
        ResponseEntity<String> docs = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody())
                .contains("Payflow payment gateway API")
                .contains("/v1/payments")
                .contains("/v1/payments/{id}/capture")
                .contains("/v1/payments/{id}/refunds")
                .contains("merchantApiKey");
    }

    @Test
    @DisplayName("Swagger UI is reachable without a key")
    void swaggerUiIsReachable() {
        ResponseEntity<String> ui = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).containsIgnoringCase("swagger");
    }
}
