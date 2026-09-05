package com.payflow.gateway.api;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AcquirerClient тут підмінений Mockito-моком: реальний mock-acquirer як
 * окремий процес для цих тестів не піднімається, бо мета цих тестів - черга,
 * воркери й переходи стану всередині шлюзу, а не сам HTTP-контракт з еквайром
 * (той перевіряється окремо в AcquirerClientTest).
 */
class PaymentProcessingTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
    }

    @Test
    @DisplayName("a payment approved by the acquirer transitions to AUTHORIZED")
    void approvedPaymentBecomesAuthorized() {
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });
    }

    @Test
    @DisplayName("a payment declined by the acquirer transitions to DECLINED")
    void declinedPaymentBecomesDeclined() {
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.DECLINED);

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        });
    }

    @Test
    @DisplayName("an acquirer failure marks the payment FAILED instead of leaving it stuck in PROCESSING")
    void acquirerFailureMarksPaymentFailed() {
        when(acquirerClient.authorize(any(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("acquirer is down"));

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        });
    }
}
