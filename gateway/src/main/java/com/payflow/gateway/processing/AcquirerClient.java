package com.payflow.gateway.processing;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.exception.AcquirerUnavailableException;
import com.payflow.gateway.metrics.PaymentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Єдина точка, де шлюз говорить з еквайром по HTTP. Тут же - класифікація
 * помилок, від якої залежить, куди піде платіж:
 *
 * <ul>
 *   <li>{@link ResourceAccessException} (таймаут, розрив з'єднання - HTTP-
 *       відповіді взагалі не було) та 5xx -> {@link AcquirerUnavailableException}:
 *       підсумок НЕВІДОМИЙ, платіж -> NEEDS_RECONCILIATION;</li>
 *   <li>4xx та будь-що інше -> проброс як є: це помилка запиту або наш баг,
 *       ретрай її не полагодить, платіж -> FAILED.</li>
 * </ul>
 *
 * Кожен виклик проходить через {@link AcquirerCircuitBreaker}: транспортні
 * невдачі його "заряджають", clean-відповіді - скидають. Кожен виклик також
 * пишеться в таймер {@code payflow.acquirer.calls} з тегом підсумку (стадія 7) -
 * запис у {@code finally}, тож він не змінює жодної гілки поведінки.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private final RestClient restClient;
    private final AcquirerCircuitBreaker circuitBreaker;
    private final PaymentMetrics metrics;

    public AcquirerClient(RestClient acquirerRestClient, AcquirerCircuitBreaker circuitBreaker,
            PaymentMetrics metrics) {
        this.restClient = acquirerRestClient;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    public AcquirerOutcome authorize(UUID paymentId, long amount, String currency) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            circuitBreaker.acquirePermission();
            AcquirerChargeResponse response = restClient.post()
                    .uri("/charges")
                    .body(new AcquirerChargeRequest(paymentId.toString(), amount, currency))
                    .retrieve()
                    .body(AcquirerChargeResponse.class);
            circuitBreaker.recordSuccess();
            outcome = response.outcome().name().toLowerCase(Locale.ROOT);
            return response.outcome();
        } catch (AcquirerUnavailableException circuitOpen) {
            // acquirePermission() відхилив виклик - HTTP-запиту не було взагалі.
            outcome = "circuit_open";
            throw circuitOpen;
        } catch (RestClientResponseException exception) {
            RuntimeException translated = classifyResponseError("authorize", paymentId, exception);
            outcome = translated instanceof AcquirerUnavailableException ? "unavailable" : "rejected";
            throw translated;
        } catch (ResourceAccessException exception) {
            circuitBreaker.recordFailure();
            outcome = "unavailable";
            throw new AcquirerUnavailableException(
                    "Acquirer transport failure on authorize for payment " + paymentId, exception);
        } finally {
            metrics.acquirerCall("authorize", outcome, System.nanoTime() - start);
        }
    }

    /**
     * Запитує в еквайра підсумок уже надісланого charge-у - шлях примирення
     * (стадія 6). Спирається на ідемпотентність mock-acquirer: {@code GET
     * /charges/{id}} повертає раніше записане рішення, нічого не заряджаючи
     * повторно.
     *
     * <p>404 -> {@link Optional#empty()}: еквайр цього платежу НЕ бачив, жодного
     * списання не сталось, викликач може безпечно позначити платіж FAILED.
     * Транспортна помилка чи 5xx -> {@link AcquirerUnavailableException}:
     * підсумок досі невідомий, спробуємо пізніше.
     */
    public Optional<AcquirerOutcome> getCharge(UUID paymentId) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            circuitBreaker.acquirePermission();
            AcquirerChargeResponse response = restClient.get()
                    .uri("/charges/{reference}", paymentId.toString())
                    .retrieve()
                    .body(AcquirerChargeResponse.class);
            circuitBreaker.recordSuccess();
            outcome = response.outcome().name().toLowerCase(Locale.ROOT);
            return Optional.of(response.outcome());
        } catch (AcquirerUnavailableException circuitOpen) {
            outcome = "circuit_open";
            throw circuitOpen;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                circuitBreaker.recordSuccess();
                outcome = "not_found";
                return Optional.empty();
            }
            RuntimeException translated = classifyResponseError("getCharge", paymentId, exception);
            outcome = translated instanceof AcquirerUnavailableException ? "unavailable" : "rejected";
            throw translated;
        } catch (ResourceAccessException exception) {
            circuitBreaker.recordFailure();
            outcome = "unavailable";
            throw new AcquirerUnavailableException(
                    "Acquirer transport failure on getCharge for payment " + paymentId, exception);
        } finally {
            metrics.acquirerCall("get_charge", outcome, System.nanoTime() - start);
        }
    }

    private RuntimeException classifyResponseError(String operation, UUID paymentId,
            RestClientResponseException exception) {
        if (exception.getStatusCode().is5xxServerError()) {
            circuitBreaker.recordFailure();
            return new AcquirerUnavailableException(
                    "Acquirer returned " + exception.getStatusCode() + " on " + operation
                            + " for payment " + paymentId, exception);
        }
        // 4xx: еквайр досяжний і відповів - ланцюг лишається замкненим, - але
        // ретраєм цього не полагодиш, тож пробрасуємо як є (-> FAILED).
        circuitBreaker.recordSuccess();
        log.error("Acquirer rejected {} for payment {} with {}", operation, paymentId, exception.getStatusCode());
        return exception;
    }
}
