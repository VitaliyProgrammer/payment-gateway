package com.payflow.acquirer;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Заглушка авторизації картки. Відмова прив'язана до конкретної, детермінованої
 * умови (сума кратна 13), а не до випадковості - інакше тести шлюзу, які мають
 * покривати і шлях відмови, і шлях підтвердження, були б недетерміновано
 * нестабільними (flaky).
 *
 * <p>Кожен charge запам'ятовується за reference (стадія 6, {@link ChargeStore}):
 * повторний {@code POST} з тим самим reference повертає той самий результат, а
 * {@code GET /charges/{reference}} дозволяє шлюзу перепитати підсумок після
 * таймауту замість того, щоб заряджати повторно.
 */
@RestController
@RequestMapping("/charges")
public class ChargeController {

    private final ChargeStore store;

    ChargeController(ChargeStore store) {
        this.store = store;
    }

    @PostMapping
    public ChargeResponse charge(@RequestBody ChargeRequest request,
            @RequestHeader(name = "X-Acquirer-Sleep-Ms", required = false) Long sleepMs) throws InterruptedException {
        simulateNetworkLatency();

        // Рішення й acquirerReference карбуються рівно один раз на reference:
        // ретрай шлюзу після таймауту отримає ТОЙ САМИЙ запис, а не нове списання.
        ChargeResponse response = store.recordOrGet(request.reference(), () -> decide(request.amount()));

        // Необов'язкова затримка вже ПІСЛЯ запису charge-у: відтворює найгірший
        // для примирення випадок - еквайр списав, але шлюз не дочекався відповіді
        // й дістав таймаут читання. У звичайній роботі шлюз цей заголовок не шле;
        // ним користуються лише тести стадії 6.
        if (sleepMs != null && sleepMs > 0) {
            Thread.sleep(sleepMs);
        }
        return response;
    }

    @GetMapping("/{reference}")
    public ChargeResponse status(@PathVariable String reference) {
        return store.find(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No charge recorded for reference " + reference));
    }

    /**
     * Реальний виклик до банку ніколи не відповідає миттєво. Ця затримка -
     * причина, чому воркер шлюзу мусить бути на віртуальному потоці: заблокувати
     * платформний потік на 50-300 мс сотні разів одночасно коштувало б дорого,
     * а віртуальний потік такого блокування майже не відчуває.
     */
    private void simulateNetworkLatency() throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextLong(50, 300));
    }

    private ChargeOutcome decide(long amount) {
        return amount % 13 == 0 ? ChargeOutcome.DECLINED : ChargeOutcome.APPROVED;
    }
}
