package com.payflow.gateway.outbox;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static com.payflow.gateway.support.TestMerchants.DEMO_MERCHANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.Merchant;
import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.entity.status.OutboxEventStatus;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.repository.MerchantRepository;
import com.payflow.gateway.repository.OutboxEventRepository;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import com.payflow.gateway.support.RecordingWebhookServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Окремий Spring-контекст із крихітними лімітами (2 спроби, майже без
 * затримки між ними) - так вичерпання ретраїв і перехід у DEAD_LETTER стає
 * швидким і детермінованим тестом, а не залежить від дефолтних 5 спроб з
 * секундами очікування.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "payflow.webhooks.max-attempts=2",
                "payflow.webhooks.base-backoff-ms=20",
                "payflow.webhooks.max-backoff-ms=50",
                "payflow.webhooks.poll-interval-ms=20"
        })
class OutboxDeadLetterTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;
    private RecordingWebhookServer webhookServer;

    @BeforeEach
    void setUp() throws IOException {
        client = new PaymentTestClient(restTemplate);
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);

        webhookServer = new RecordingWebhookServer();
        // Більше провалів, ніж max-attempts - жодна спроба ніколи не вдасться.
        webhookServer.failNextAttempts(10);
        setDemoMerchantWebhookUrl(webhookServer.url());
    }

    @AfterEach
    void tearDown() {
        webhookServer.close();
        setDemoMerchantWebhookUrl(null);
    }

    @Test
    @DisplayName("an event that fails every delivery attempt ends up as DEAD_LETTER, not stuck PENDING forever")
    void exhaustedRetriesMovesEventToDeadLetter() {
        PaymentResponse created = client.create(DEMO_API_KEY, UUID.randomUUID().toString(), 5000, "UAH").getBody();
        assertThat(created).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                    .filter(event -> event.getPaymentId().equals(created.id()))
                    .toList();

            assertThat(events).hasSize(1);
            OutboxEvent event = events.get(0);
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
            assertThat(event.getAttempts()).isEqualTo(2);
            assertThat(event.getLastError()).isNotNull();
        });
    }

    private void setDemoMerchantWebhookUrl(String url) {
        Merchant merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
        merchant.updateWebhookUrl(url);
        merchantRepository.save(merchant);
    }
}
