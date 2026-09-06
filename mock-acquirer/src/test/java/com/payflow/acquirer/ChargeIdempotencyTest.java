package com.payflow.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Ідемпотентність еквайра - фундамент, на якому стоїть примирення шлюзу на
 * стадії 6: якби повторний POST з тим самим reference списував удруге, безпечно
 * "перепитати" підсумок після таймауту було б неможливо.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargeIdempotencyTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void repeatedChargeWithSameReferenceReturnsTheIdenticalResponse() {
        String reference = UUID.randomUUID().toString();
        ChargeRequest request = new ChargeRequest(reference, 5000, "UAH");

        ChargeResponse first = restTemplate.postForObject("/charges", request, ChargeResponse.class);
        ChargeResponse second = restTemplate.postForObject("/charges", request, ChargeResponse.class);

        assertThat(first.outcome()).isEqualTo(ChargeOutcome.APPROVED);
        assertThat(second).isEqualTo(first);
        assertThat(second.acquirerReference()).isEqualTo(first.acquirerReference());
    }

    @Test
    void concurrentDuplicatesCollapseToOneRecordedCharge() {
        String reference = UUID.randomUUID().toString();
        ChargeRequest request = new ChargeRequest(reference, 5000, "UAH");

        ConcurrentHashMap<String, Boolean> acquirerReferences = new ConcurrentHashMap<>();
        IntStream.range(0, 32).parallel().forEach(i -> {
            ChargeResponse response = restTemplate.postForObject("/charges", request, ChargeResponse.class);
            acquirerReferences.put(response.acquirerReference(), Boolean.TRUE);
        });

        assertThat(acquirerReferences.keySet()).hasSize(1);
    }

    @Test
    void statusLookupReturnsTheRecordedChargeAndIsAmountRuleDeterministic() {
        String reference = UUID.randomUUID().toString();
        // 5200 = 400 * 13 -> детермінована відмова.
        restTemplate.postForObject("/charges", new ChargeRequest(reference, 5200, "UAH"), ChargeResponse.class);

        ChargeResponse looked = restTemplate.getForObject("/charges/" + reference, ChargeResponse.class);

        assertThat(looked.reference()).isEqualTo(reference);
        assertThat(looked.outcome()).isEqualTo(ChargeOutcome.DECLINED);
    }

    @Test
    void statusLookupForUnknownReferenceIs404() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/charges/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
