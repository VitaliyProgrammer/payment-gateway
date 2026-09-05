package com.payflow.gateway.api;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Окремий Spring-контекст із крихітною чергою й нульовою кількістю воркерів -
 * так переповнення черги стає детермінованим (ніхто ніколи її не розбирає),
 * а не залежним від того, наскільки швидко воркер встигне забрати перший
 * елемент у звичайному контексті.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "payflow.processing.queue-capacity=1",
                "payflow.processing.worker-count=0"
        })
class PaymentProcessingQueueFullTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
    }

    @Test
    @DisplayName("a full processing queue is rejected with 503 instead of blocking the request")
    void fullQueueReturns503() {
        ResponseEntity<PaymentResponse> first =
                client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second =
                client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH", String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(second.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
}
