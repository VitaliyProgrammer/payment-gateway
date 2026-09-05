package com.payflow.gateway.outbox;

import com.payflow.gateway.entity.Merchant;
import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.repository.MerchantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Один екземпляр цього Runnable виконують кілька віртуальних потоків одночасно
 * (див. OutboxPollerPool) - той самий патерн, що й PaymentProcessingWorker на
 * стадії 3: жодного мутабельного стану екземпляра, лише ін'єктовані залежності,
 * безпечні для паралельних викликів.
 */
@Component
public class OutboxPoller implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxClaimService claimService;
    private final OutboxDeliveryOutcomeService outcomeService;
    private final WebhookClient webhookClient;
    private final MerchantRepository merchantRepository;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration pollInterval;

    public OutboxPoller(OutboxClaimService claimService, OutboxDeliveryOutcomeService outcomeService,
            WebhookClient webhookClient, MerchantRepository merchantRepository,
            @Value("${payflow.webhooks.batch-size:20}") int batchSize,
            @Value("${payflow.webhooks.claim-lease-ms:30000}") long leaseMs,
            @Value("${payflow.webhooks.poll-interval-ms:200}") long pollIntervalMs) {
        this.claimService = claimService;
        this.outcomeService = outcomeService;
        this.webhookClient = webhookClient;
        this.merchantRepository = merchantRepository;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMillis(leaseMs);
        this.pollInterval = Duration.ofMillis(pollIntervalMs);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            List<OutboxEvent> batch = claimService.claimBatch(batchSize, leaseDuration);

            if (batch.isEmpty()) {
                sleep(pollInterval);
                continue;
            }

            for (OutboxEvent event : batch) {
                deliver(event);
            }
        }
    }

    private void deliver(OutboxEvent event) {
        Optional<Merchant> maybeMerchant = merchantRepository.findById(event.getMerchantId());

        if (maybeMerchant.isEmpty() || maybeMerchant.get().getWebhookUrl() == null) {
            // Мерчант не налаштував вебхук - немає куди доставляти, і це не
            // помилка, яку варто ретраїти.
            outcomeService.markDelivered(event.getId());
            return;
        }

        Merchant merchant = maybeMerchant.get();
        try {
            webhookClient.send(merchant.getWebhookUrl(), merchant.getWebhookSecret(),
                    event.getEventType().wireName(), event.getPayload());
            outcomeService.markDelivered(event.getId());
        } catch (RestClientException exception) {
            log.warn("Webhook delivery failed for event {} (payment {}): {}", event.getId(), event.getPaymentId(),
                    exception.getMessage());
            outcomeService.recordFailure(event.getId(), exception.getMessage());
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
