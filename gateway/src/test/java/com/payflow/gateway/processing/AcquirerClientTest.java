package com.payflow.gateway.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import com.payflow.gateway.exception.AcquirerUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

/**
 * Юніт-тест без мережі й без mock-acquirer як окремого процесу -
 * MockRestServiceServer підміняє транспорт RestClient-а, тож тут перевіряється
 * лише контракт запиту/відповіді AcquirerClient-а. Реальний наскрізний виклик
 * до mock-acquirer перевіряється вручну через docker-compose (див. README), а
 * взаємодія воркера з AcquirerClient - в PaymentProcessingTest, де сам
 * AcquirerClient підмінений на Mockito-мок.
 */
class AcquirerClientTest {

    @Test
    void authorizeReturnsApprovedOutcome() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://mock-acquirer/charges"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"reference":"%s","outcome":"APPROVED","acquirerReference":"ref-1"}
                        """.formatted(paymentId), MediaType.APPLICATION_JSON));

        AcquirerOutcome outcome = client.authorize(paymentId, 5000, "USD");

        assertThat(outcome).isEqualTo(AcquirerOutcome.APPROVED);
        server.verify();
    }

    @Test
    void authorizeReturnsDeclinedOutcome() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://mock-acquirer/charges"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"reference":"%s","outcome":"DECLINED","acquirerReference":"ref-2"}
                        """.formatted(paymentId), MediaType.APPLICATION_JSON));

        AcquirerOutcome outcome = client.authorize(paymentId, 5200, "USD");

        assertThat(outcome).isEqualTo(AcquirerOutcome.DECLINED);
        server.verify();
    }

    @Test
    void transportFailureBecomesAcquirerUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        server.expect(requestTo("http://mock-acquirer/charges"))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out");
                });

        assertThatThrownBy(() -> client.authorize(UUID.randomUUID(), 5000, "USD"))
                .isInstanceOf(AcquirerUnavailableException.class);
    }

    @Test
    void serverErrorBecomesAcquirerUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        server.expect(requestTo("http://mock-acquirer/charges")).andRespond(withServerError());

        assertThatThrownBy(() -> client.authorize(UUID.randomUUID(), 5000, "USD"))
                .isInstanceOf(AcquirerUnavailableException.class);
    }

    @Test
    void getChargeReturnsEmptyWhenAcquirerNeverSawThePayment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://mock-acquirer/charges/" + paymentId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getCharge(paymentId)).isEmpty();
        server.verify();
    }

    @Test
    void getChargeReturnsRecordedOutcome() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-acquirer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AcquirerClient client = new AcquirerClient(builder.build(), new AcquirerCircuitBreaker(100, 10_000));

        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://mock-acquirer/charges/" + paymentId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"reference":"%s","outcome":"APPROVED","acquirerReference":"ref-9"}
                        """.formatted(paymentId), MediaType.APPLICATION_JSON));

        assertThat(client.getCharge(paymentId)).contains(AcquirerOutcome.APPROVED);
        server.verify();
    }
}
