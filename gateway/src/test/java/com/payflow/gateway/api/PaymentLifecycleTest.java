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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PaymentLifecycleTest extends PostgresIntegrationTest {

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
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);
    }

    @Test
    @DisplayName("capturing an authorized payment moves it to CAPTURED for the full amount")
    void captureMovesAuthorizedPaymentToCaptured() {
        UUID paymentId = createAuthorizedPayment(5000);

        ResponseEntity<PaymentResponse> response = client.capture(DEMO_API_KEY, paymentId, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(response.getBody().capturedAmount()).isEqualTo(5000);
    }

    @Test
    @DisplayName("canceling an authorized payment moves it to CANCELED")
    void cancelMovesAuthorizedPaymentToCanceled() {
        UUID paymentId = createAuthorizedPayment(5000);

        ResponseEntity<PaymentResponse> response = client.cancel(DEMO_API_KEY, paymentId, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("capturing an already-captured payment is rejected as a conflict")
    void capturingTwiceIsRejected() {
        UUID paymentId = createAuthorizedPayment(5000);
        client.capture(DEMO_API_KEY, paymentId, PaymentResponse.class);

        ResponseEntity<String> second = client.capture(DEMO_API_KEY, paymentId, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a refund larger than the remaining captured amount is rejected")
    void refundExceedingRemainingAmountIsRejected() {
        UUID paymentId = createCapturedPayment(5000);

        ResponseEntity<String> response = client.refund(DEMO_API_KEY, paymentId, 5001, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("sequential partial refunds that sum to the captured amount fully refund the payment")
    void sequentialPartialRefundsFullyRefundThePayment() {
        UUID paymentId = createCapturedPayment(10000);

        ResponseEntity<PaymentResponse> first = client.refund(DEMO_API_KEY, paymentId, 3000, PaymentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(first.getBody().refundedAmount()).isEqualTo(3000);

        ResponseEntity<PaymentResponse> second = client.refund(DEMO_API_KEY, paymentId, 7000, PaymentResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(second.getBody().refundedAmount()).isEqualTo(10000);
    }

    @Test
    @DisplayName("concurrent capture and cancel on the same payment - exactly one wins")
    void concurrentCaptureAndCancelExactlyOneWins() throws InterruptedException {
        UUID paymentId = createAuthorizedPayment(5000);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ResponseEntity<String>> responses = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                ready.countDown();
                awaitUninterruptibly(start);
                responses.add(client.capture(DEMO_API_KEY, paymentId, String.class));
            });
            executor.submit(() -> {
                ready.countDown();
                awaitUninterruptibly(start);
                responses.add(client.cancel(DEMO_API_KEY, paymentId, String.class));
            });
            ready.await();
            start.countDown();
        }

        assertThat(responses).hasSize(2);

        long successCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long conflictCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

        // Рівно один переміг (200), рівно один програв гонку за версію (409) -
        // ніколи не обидва одночасно і ніколи жодного.
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isIn(PaymentStatus.CAPTURED, PaymentStatus.CANCELED);
    }

    private UUID createAuthorizedPayment(long amount) {
        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), amount, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });

        return created.id();
    }

    private UUID createCapturedPayment(long amount) {
        UUID paymentId = createAuthorizedPayment(amount);
        ResponseEntity<PaymentResponse> response = client.capture(DEMO_API_KEY, paymentId, PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return paymentId;
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
