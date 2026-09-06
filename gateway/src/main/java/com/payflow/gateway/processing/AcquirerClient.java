package com.payflow.gateway.processing;

import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.exception.AcquirerUnavailableException;
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
 * невдачі його "заряджають", clean-відповіді - скидають.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private final RestClient restClient;
    private final AcquirerCircuitBreaker circuitBreaker;

    public AcquirerClient(RestClient acquirerRestClient, AcquirerCircuitBreaker circuitBreaker) {
        this.restClient = acquirerRestClient;
        this.circuitBreaker = circuitBreaker;
    }

    public AcquirerOutcome authorize(UUID paymentId, long amount, String currency) {
        circuitBreaker.acquirePermission();
        try {
            AcquirerChargeResponse response = restClient.post()
                    .uri("/charges")
                    .body(new AcquirerChargeRequest(paymentId.toString(), amount, currency))
                    .retrieve()
                    .body(AcquirerChargeResponse.class);
            circuitBreaker.recordSuccess();
            return response.outcome();
        } catch (RestClientResponseException exception) {
            throw classifyResponseError("authorize", paymentId, exception);
        } catch (ResourceAccessException exception) {
            circuitBreaker.recordFailure();
            throw new AcquirerUnavailableException(
                    "Acquirer transport failure on authorize for payment " + paymentId, exception);
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
        circuitBreaker.acquirePermission();
        try {
            AcquirerChargeResponse response = restClient.get()
                    .uri("/charges/{reference}", paymentId.toString())
                    .retrieve()
                    .body(AcquirerChargeResponse.class);
            circuitBreaker.recordSuccess();
            return Optional.of(response.outcome());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                circuitBreaker.recordSuccess();
                return Optional.empty();
            }
            throw classifyResponseError("getCharge", paymentId, exception);
        } catch (ResourceAccessException exception) {
            circuitBreaker.recordFailure();
            throw new AcquirerUnavailableException(
                    "Acquirer transport failure on getCharge for payment " + paymentId, exception);
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
