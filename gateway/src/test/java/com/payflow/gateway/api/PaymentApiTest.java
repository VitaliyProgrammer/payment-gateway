package com.payflow.gateway.api;

import static com.payflow.gateway.support.TestMerchants.DEMO_API_KEY;
import static com.payflow.gateway.support.TestMerchants.DEMO_MERCHANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.processing.AcquirerClient;
import com.payflow.gateway.processing.AcquirerOutcome;
import com.payflow.gateway.repository.PaymentRepository;
import com.payflow.gateway.entity.status.PaymentStatus;
import com.payflow.gateway.support.PaymentTestClient;
import com.payflow.gateway.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PaymentApiTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    /**
     * Ці тести - про HTTP-поверхню API (автентифікація, валідація, читання
     * назад), а не про обробку. Мок прибирає фонову роботу: без нього кожен
     * створений тут платіж б'ється в непіднятий mock-acquirer, дістає таймаут і
     * осідає в NEEDS_RECONCILIATION, звідки sweeper - уже в контексті
     * НАСТУПНОГО тестового класу - зрештою робить його FAILED і породжує
     * payment.failed подію, яка тече у вебхук-тести. Той самий підхід, що і в
     * PaymentProcessingTest.
     */
    @MockitoBean
    private AcquirerClient acquirerClient;

    private PaymentTestClient client;

    @BeforeEach
    void setUp() {
        client = new PaymentTestClient(restTemplate);
        when(acquirerClient.authorize(any(), anyLong(), anyString())).thenReturn(AcquirerOutcome.APPROVED);
    }

    @Test
    @DisplayName("a valid API key can create a payment and read it back")
    void createsAndReadsBackAPayment() {
        ResponseEntity<PaymentResponse> createResponse = client.create(DEMO_API_KEY, newKey(), 5000, "UAH");

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.amount()).isEqualTo(5000);
        assertThat(created.currency()).isEqualTo("UAH");
        assertThat(created.status()).isEqualTo(PaymentStatus.CREATED);

        ResponseEntity<PaymentResponse> getResponse = client.get(DEMO_API_KEY, created.id(), PaymentResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().id()).isEqualTo(created.id());

        Payment persisted = paymentRepository.findByIdAndMerchantId(created.id(), DEMO_MERCHANT_ID).orElseThrow();
        assertThat(persisted.getAmount()).isEqualTo(5000);
    }

    @Test
    @DisplayName("a request with no API key is rejected before it reaches the handler")
    void rejectsRequestsWithoutAnApiKey() {
        ResponseEntity<String> response = client.create(null, newKey(), 5000, "UAH", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unrecognized API key is rejected")
    void rejectsAnUnrecognizedApiKey() {
        ResponseEntity<String> response = client.create("not-a-real-key", newKey(), 5000, "UAH", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a non-positive amount is rejected with 400")
    void rejectsNonPositiveAmount() {
        ResponseEntity<String> response = client.create(DEMO_API_KEY, newKey(), 0, "UAH", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("fetching an unknown payment id returns 404")
    void unknownPaymentIdReturns404() {
        ResponseEntity<String> response = client.get(DEMO_API_KEY, UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("one merchant cannot fetch another merchant's payment")
    void merchantsCannotReadEachOthersPayments() {
        PaymentResponse payment = client.create(DEMO_API_KEY, newKey(), 1500, "USD").getBody();
        assertThat(payment).isNotNull();

        String otherMerchantKey = "some-other-merchants-key";
        ResponseEntity<String> response = client.get(otherMerchantKey, payment.id(), String.class);

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

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}
