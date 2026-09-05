package com.payflow.gateway.api;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.util.HashSet;
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

class PaymentIdempotencyTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
    }

    @Test
    @DisplayName("creating a payment without an Idempotency-Key header is rejected with 400")
    void missingIdempotencyKeyIsRejected() {
        ResponseEntity<String> response = client.create(DEMO_API_KEY, null, 5000, "UAH", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("repeating the same key and payload returns the exact same payment, not a new one")
    void sameKeyAndPayloadReplaysTheOriginalResponse() {
        String idempotencyKey = UUID.randomUUID().toString();

        PaymentResponse first = client.create(DEMO_API_KEY, idempotencyKey, 5000, "UAH").getBody();
        PaymentResponse second = client.create(DEMO_API_KEY, idempotencyKey, 5000, "UAH").getBody();

        assertThat(first).isNotNull();
        // Рівність тут - структурна рівність record-а по всіх полях, включно з
        // createdAt. Якби другий виклик справді створив ще один платіж, у нього
        // майже напевно був би інший id і інший createdAt - тож сама ця рівність
        // є доказом, що другого платежу не було створено.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("reusing a key with a different payload is rejected as a conflict")
    void sameKeyWithDifferentPayloadIsRejected() {
        String idempotencyKey = UUID.randomUUID().toString();

        client.create(DEMO_API_KEY, idempotencyKey, 5000, "UAH");
        ResponseEntity<String> conflicting = client.create(DEMO_API_KEY, idempotencyKey, 7000, "UAH", String.class);

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("200 concurrent requests with the same idempotency key never create more than one payment")
    void concurrentSameKeyCreatesAtMostOnePayment() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();
        int concurrentRequests = 200;

        CountDownLatch ready = new CountDownLatch(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);
        List<ResponseEntity<PaymentResponse>> responses = new CopyOnWriteArrayList<>();

        // Кожен запит - окремий віртуальний потік. Усі спершу "сигналізують
        // готовність" і чекають на спільний старт, щоб справді зіштовхнутись
        // одночасно, а не просто виконатись послідовно один за одним.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrentRequests; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    awaitUninterruptibly(start);
                    responses.add(client.create(DEMO_API_KEY, idempotencyKey, 12345, "USD"));
                });
            }
            ready.await();
            start.countDown();
        }

        assertThat(responses).hasSize(concurrentRequests);

        // Це навмисно екстремальний сценарій: 200 запитів одночасно б'ються за
        // захоплення ОДНОГО й того самого рядка, і Postgres фізично серіалізує
        // конкуруючі вставки на один унікальний ключ через блокування рядка -
        // жоден розмір пулу з'єднань цього не прискорить. Тому частина відповідей
        // чесно провалюється: 503 (вичерпаний пул) або 409 (не дочекались
        // завершення переможця за відведений час). Це прийнятно - система чесно
        // каже "спробуй пізніше", а не бреше. Неприйнятно - 401 (маскування збою
        // під "неавторизовано") чи 500 (необроблений виняток), і неприйнятно
        // створення другого платежу.
        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode())
                .isIn(HttpStatus.CREATED, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE));

        List<PaymentResponse> created = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .map(ResponseEntity::getBody)
                .toList();

        assertThat(created).isNotEmpty();

        // Усі успішні відповіді структурно однакові (той самий id, той самий
        // createdAt) - тобто реально відбулось рівно одне створення платежу,
        // скільки б викликів не отримали 201.
        assertThat(new HashSet<>(created)).hasSize(1);
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
