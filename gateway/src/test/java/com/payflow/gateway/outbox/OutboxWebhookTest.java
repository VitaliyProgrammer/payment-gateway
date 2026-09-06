package com.payflow.gateway.outbox;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static com.payflow.gateway.support.TestMerchants.DEMO_MERCHANT_ID;
import static com.payflow.gateway.support.TestMerchants.DEMO_WEBHOOK_SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.Merchant;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.repository.MerchantRepository;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import com.payflow.gateway.support.RecordedWebhookRequest;
import com.payflow.gateway.support.RecordingWebhookServer;
import com.payflow.gateway.util.HmacSigner;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class OutboxWebhookTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;
    private RecordingWebhookServer webhookServer;

    @BeforeEach
    void setUp() throws IOException {
        client = new PaymentTestClient(restTemplate);
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);

        webhookServer = new RecordingWebhookServer();
        setDemoMerchantWebhookUrl(webhookServer.url());
    }

    @AfterEach
    void tearDown() {
        webhookServer.close();
        // Інші тестові класи ділять того самого демо-мерчанта (та сама
        // засіяна міграцією база), тож URL треба прибрати - інакше вони
        // намагатимуться бити по вже закритому тестовому серверу.
        setDemoMerchantWebhookUrl(null);
    }

    @Test
    @DisplayName("an authorized payment triggers a correctly signed webhook")
    void authorizedPaymentTriggersSignedWebhook() {
        UUID paymentId = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody().id();

        RecordedWebhookRequest request = webhookServer.awaitNextFor(paymentId, Duration.ofSeconds(10));

        assertThat(request.eventType()).isEqualTo("payment.authorized");
        assertThat(request.signature()).isEqualTo(HmacSigner.sign(DEMO_WEBHOOK_SECRET, request.body()));
    }

    @Test
    @DisplayName("events for the same payment are delivered in order even when an earlier one needs a retry")
    void eventsForSamePaymentAreDeliveredInOrder() {
        // Перша доставка саме для цього платежу (буде payment.authorized -
        // завжди найперша подія) провалиться один раз і піде на ретрай. Якщо
        // порядок НЕ гарантується, capture/refund могли б проскочити повз, поки
        // authorized ще чекає повторної спроби. Прапорець ставиться одразу після
        // create(), ще ДО того, як поллер міг доставити першу подію.
        UUID paymentId = createAuthorizedPayment(10000, 1);

        // awaitCountFor() блокує, вичерпує чергу й пропускає чужі вебхуки, що
        // могли протекти з іншого тестового класу через спільну базу.
        List<RecordedWebhookRequest> firstTwo = webhookServer.awaitCountFor(paymentId, 2, Duration.ofSeconds(10));
        assertThat(firstTwo).hasSize(2);

        client.capture(DEMO_API_KEY, paymentId, PaymentResponse.class);
        client.refund(DEMO_API_KEY, paymentId, 4000, PaymentResponse.class);

        List<RecordedWebhookRequest> lastTwo = webhookServer.awaitCountFor(paymentId, 2, Duration.ofSeconds(10));

        List<String> eventTypes = Stream.concat(firstTwo.stream(), lastTwo.stream())
                .map(RecordedWebhookRequest::eventType)
                .toList();

        assertThat(eventTypes).containsExactly(
                "payment.authorized", "payment.authorized", "payment.captured", "payment.partially_refunded");
    }

    private UUID createAuthorizedPayment(long amount, int failFirstDeliveries) {
        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), amount, "UAH").getBody();
        assertThat(created).isNotNull();

        if (failFirstDeliveries > 0) {
            webhookServer.failNextAttemptsFor(created.id(), failFirstDeliveries);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(created.id()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        });

        return created.id();
    }

    private void setDemoMerchantWebhookUrl(String url) {
        Merchant merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
        merchant.updateWebhookUrl(url);
        merchantRepository.save(merchant);
    }
}
