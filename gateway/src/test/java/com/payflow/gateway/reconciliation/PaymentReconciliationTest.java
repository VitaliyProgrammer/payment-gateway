package com.payflow.gateway.reconciliation;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static com.payflow.gateway.support.TestMerchants.DEMO_MERCHANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.exception.AcquirerUnavailableException;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.service.PaymentProcessingService;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Стадія 6. Коли виклик до еквайра завершується таймаутом, шлюз не знає
 * підсумку - платіж іде в NEEDS_RECONCILIATION, а sweeper з'ясовує, чим усе
 * скінчилось, ПИТАЮЧИ еквайра ({@code getCharge}), а не заряджаючи повторно.
 *
 * <p>AcquirerClient підмінений Mockito-моком (як у PaymentProcessingTest): тут
 * важлива логіка конвеєра шлюзу, а не HTTP-контракт з еквайром.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "payflow.reconciliation.poll-interval-ms=50",
                "payflow.reconciliation.stale-processing-after-ms=200",
                "payflow.reconciliation.base-backoff-ms=20",
                "payflow.reconciliation.max-backoff-ms=50"
        })
class PaymentReconciliationTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
        // authorize завжди "таймаутить": воркер ніколи не дізнається підсумку сам.
        when(acquirerClient.authorize(any(), anyLong(), anyString()))
                .thenThrow(new AcquirerUnavailableException("Read timed out"));
    }

    @Test
    @DisplayName("a timed-out payment is reconciled to AUTHORIZED by asking the acquirer, never by re-charging")
    void timedOutPaymentIsReconciledWithoutRecharging() {
        when(acquirerClient.getCharge(any())).thenReturn(Optional.of(AcquirerOutcome.APPROVED));

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });

        // Суть інваріанту стадії 6: charge пішов рівно один раз. Примирення
        // йшло через getCharge, а не через повторний authorize.
        verify(acquirerClient, times(1)).authorize(eq(created.id()), anyLong(), anyString());
    }

    @Test
    @DisplayName("reconciliation honours a DECLINED outcome from the acquirer")
    void timedOutPaymentIsReconciledToDeclined() {
        when(acquirerClient.getCharge(any())).thenReturn(Optional.of(AcquirerOutcome.DECLINED));

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        });
    }

    @Test
    @DisplayName("if the acquirer never saw the payment, reconciliation ends it as FAILED")
    void paymentUnknownToAcquirerEndsFailed() {
        when(acquirerClient.getCharge(any())).thenReturn(Optional.empty());

        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        });
    }

    @Test
    @DisplayName("a payment abandoned in PROCESSING (worker died mid-call) is swept and finished")
    void staleProcessingPaymentIsSwept() {
        when(acquirerClient.getCharge(any())).thenReturn(Optional.of(AcquirerOutcome.APPROVED));

        // Симулюємо смерть воркера посеред виклику: платіж лишився в PROCESSING,
        // у черзі його немає, ніхто його не доведе - крім sweeper-а.
        UUID paymentId = UUID.randomUUID();
        paymentRepository.save(new Payment(paymentId, DEMO_MERCHANT_ID, 7000, "UAH"));
        assertThat(paymentProcessingService.markProcessing(paymentId)).isTrue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });
    }
}
