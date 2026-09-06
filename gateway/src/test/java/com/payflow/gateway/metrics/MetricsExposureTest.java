package com.payflow.gateway.metrics;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Стадія 7: доменні метрики реально реєструються й доходять до Actuator після
 * обробки платежу. AcquirerClient підмінений моком (як у PaymentProcessingTest) -
 * таймер {@code payflow.acquirer.calls} з реальним клієнтом перевіряється в
 * AcquirerClientTest, а мапінг подія -> метрика - в PaymentMetricsTest.
 */
class MetricsExposureTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);
    }

    @Test
    @DisplayName("processing a payment registers the domain counters and gauges")
    void domainMetersAreRegistered() {
        double createdBefore = counter("payflow.payments.accepted");
        double authorizedBefore = counter("payflow.payments.transitions", "status", "AUTHORIZED");

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });

        assertThat(counter("payflow.payments.accepted")).isGreaterThan(createdBefore);
        assertThat(counter("payflow.payments.transitions", "status", "PROCESSING")).isGreaterThan(0.0);
        assertThat(counter("payflow.payments.transitions", "status", "AUTHORIZED")).isGreaterThan(authorizedBefore);
        assertThat(counter("payflow.processing.queue.enqueue", "outcome", "accepted")).isGreaterThan(0.0);

        assertThat(meterRegistry.get("payflow.processing.queue.depth").gauge().value()).isGreaterThanOrEqualTo(0.0);
        assertThat(meterRegistry.get("payflow.acquirer.circuit_breaker.state").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("payflow.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(0.0);
        assertThat(meterRegistry.get("payflow.reconciliation.pending").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("the domain meters are exposed over /actuator/metrics")
    void domainMetersAreExposedOverActuator() {
        client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH");

        ResponseEntity<String> names = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(names.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(names.getBody())
                .contains("payflow.payments.accepted")
                .contains("payflow.payments.transitions")
                .contains("payflow.processing.queue.depth")
                .contains("payflow.acquirer.circuit_breaker.state")
                .contains("payflow.outbox.pending")
                .contains("payflow.reconciliation.pending");

        ResponseEntity<String> one = restTemplate.getForEntity(
                "/actuator/metrics/payflow.acquirer.circuit_breaker.state", String.class);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody()).contains("VALUE");
    }

    @Test
    @DisplayName("the Prometheus scrape endpoint serves the domain metrics")
    void prometheusScrapeEndpointWorks() {
        client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH");

        ResponseEntity<String> scrape = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("payflow_payments_accepted_total")
                .contains("payflow_processing_queue_depth")
                .contains("payflow_acquirer_circuit_breaker_state");
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
