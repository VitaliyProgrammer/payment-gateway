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

/**
 * Головний тест усього проєкту: доводить, що сума часткових повернень ніколи
 * не перевищує захоплену суму, навіть коли клієнти буквально одночасно
 * намагаються повернути більше, ніж лишилось.
 */
class PaymentRefundConcurrencyTest extends PostgresIntegrationTest {

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
    @DisplayName("10 concurrent refunds of 20 against a 100 capture succeed exactly 5 times")
    void tenConcurrentPartialRefundsNeverExceedTheCapturedAmount() throws InterruptedException {
        UUID paymentId = createCapturedPayment(100);

        int refundRequests = 10;
        long refundAmount = 20;

        CountDownLatch ready = new CountDownLatch(refundRequests);
        CountDownLatch start = new CountDownLatch(1);
        List<ResponseEntity<String>> responses = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < refundRequests; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    awaitUninterruptibly(start);
                    responses.add(client.refund(DEMO_API_KEY, paymentId, refundAmount, String.class));
                });
            }
            ready.await();
            start.countDown();
        }

        assertThat(responses).hasSize(refundRequests);

        long successCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        // Відхилена спроба повернення бачить одне з двох, залежно від того, В
        // ЯКИЙ МОМЕНТ саме її (ретрайнута) спроба прочитала свіжий стан:
        // - 400, якщо лишався ще ЯКИЙСЬ залишок, але менший за запитану суму;
        // - 409, якщо на момент читання платіж уже повністю REFUNDED - тоді
        //   requireStatus() відхиляє ще до перевірки суми, бо статусу
        //   CAPTURED/PARTIALLY_REFUNDED вже немає.
        // 100 ділиться на 20 без остачі, тож для цього конкретного сценарію
        // реалізується саме другий випадок - але обидва коди однаково коректні
        // відповіді на "місця більше немає", просто в різний момент гонки.
        long rejectedCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST || r.getStatusCode() == HttpStatus.CONFLICT)
                .count();

        // 100 / 20 = рівно 5 повернень мають законно поміститись, решта 5 -
        // чесно відхилені. Жодних 500 чи "якось само вийшло" - рахунок
        // б'ється точно.
        assertThat(successCount).isEqualTo(5);
        assertThat(rejectedCount).isEqualTo(5);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getCapturedAmount()).isEqualTo(100);
        assertThat(payment.getRefundedAmount()).isEqualTo(100);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    private UUID createCapturedPayment(long amount) {
        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), amount, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });

        ResponseEntity<PaymentResponse> captureResponse =
                client.capture(DEMO_API_KEY, created.id(), PaymentResponse.class);
        assertThat(captureResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        return created.id();
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
