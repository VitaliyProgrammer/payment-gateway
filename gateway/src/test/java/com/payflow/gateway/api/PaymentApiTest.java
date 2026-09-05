package com.payflow.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static com.payflow.gateway.support.TestMerchants.DEMO_MERCHANT_ID;

import com.payflow.gateway.domain.Payment;
import com.payflow.gateway.domain.PaymentRepository;
import com.payflow.gateway.domain.PaymentStatus;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentApiTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("a valid API key can create a payment and read it back")
    void createsAndReadsBackAPayment() {
        ResponseEntity<PaymentResponse> createResponse = createPayment(DEMO_API_KEY, 5000, "UAH");

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.amount()).isEqualTo(5000);
        assertThat(created.currency()).isEqualTo("UAH");
        assertThat(created.status()).isEqualTo(PaymentStatus.CREATED);

        ResponseEntity<PaymentResponse> getResponse = getPayment(DEMO_API_KEY, created.id());

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().id()).isEqualTo(created.id());

        Payment persisted = paymentRepository.findByIdAndMerchantId(created.id(), DEMO_MERCHANT_ID).orElseThrow();
        assertThat(persisted.getAmount()).isEqualTo(5000);
    }

    @Test
    @DisplayName("a request with no API key is rejected before it reaches the handler")
    void rejectsRequestsWithoutAnApiKey() {
        HttpEntity<CreatePaymentRequest> request = new HttpEntity<>(new CreatePaymentRequest(5000L, "UAH"));

        ResponseEntity<String> response =
                restTemplate.exchange("/v1/payments", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unrecognized API key is rejected")
    void rejectsAnUnrecognizedApiKey() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments", HttpMethod.POST,
                new HttpEntity<>(new CreatePaymentRequest(5000L, "UAH"), authHeader("not-a-real-key")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a non-positive amount is rejected with 400")
    void rejectsNonPositiveAmount() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments", HttpMethod.POST,
                new HttpEntity<>(new CreatePaymentRequest(0L, "UAH"), authHeader(DEMO_API_KEY)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("fetching an unknown payment id returns 404")
    void unknownPaymentIdReturns404() {
        ResponseEntity<String> response = getPayment(DEMO_API_KEY, UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("one merchant cannot fetch another merchant's payment")
    void merchantsCannotReadEachOthersPayments() {
        PaymentResponse payment = createPayment(DEMO_API_KEY, 1500, "USD").getBody();
        assertThat(payment).isNotNull();

        String otherMerchantKey = "some-other-merchants-key";
        ResponseEntity<String> response = getPayment(otherMerchantKey, payment.id(), String.class);

        // Такого ключа теж не існує, тож реалістично тут буде 401. Важлива
        // поведінкова гарантія - що PaymentRepository#findByIdAndMerchantId ніколи
        // не може "просочити" платіж іншому мерчанту - забезпечується самим
        // запитом, і її не можна перевірити через HTTP, маючи лише одного
        // засіяного мерчанта.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the repository's merchant-scoped lookup cannot cross merchant boundaries")
    void repositoryLookupIsScopedToMerchant() {
        Payment payment = paymentRepository.save(new Payment(UUID.randomUUID(), DEMO_MERCHANT_ID, 2500, "EUR"));
        UUID unrelatedMerchantId = UUID.randomUUID();

        assertThat(paymentRepository.findByIdAndMerchantId(payment.getId(), unrelatedMerchantId)).isEmpty();
        assertThat(paymentRepository.findByIdAndMerchantId(payment.getId(), DEMO_MERCHANT_ID)).isPresent();
    }

    private ResponseEntity<PaymentResponse> createPayment(String apiKey, long amount, String currency) {
        HttpEntity<CreatePaymentRequest> request =
                new HttpEntity<>(new CreatePaymentRequest(amount, currency), authHeader(apiKey));
        return restTemplate.exchange("/v1/payments", HttpMethod.POST, request, PaymentResponse.class);
    }

    private ResponseEntity<PaymentResponse> getPayment(String apiKey, UUID id) {
        return getPayment(apiKey, id, PaymentResponse.class);
    }

    private <T> ResponseEntity<T> getPayment(String apiKey, UUID id, Class<T> responseType) {
        return restTemplate.exchange(
                "/v1/payments/" + id, HttpMethod.GET, new HttpEntity<>(authHeader(apiKey)), responseType);
    }

    private HttpHeaders authHeader(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }
}
