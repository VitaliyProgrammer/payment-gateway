package com.payflow.gateway.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
        AcquirerClient client = new AcquirerClient(builder.build());

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
        AcquirerClient client = new AcquirerClient(builder.build());

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
}
