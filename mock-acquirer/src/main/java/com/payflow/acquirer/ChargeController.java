package com.payflow.acquirer;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Заглушка авторизації картки. Відмова прив'язана до конкретної, детермінованої
 * умови (сума кратна 13), а не до випадковості - інакше тести шлюзу, які мають
 * покривати і шлях відмови, і шлях підтвердження, були б недетерміновано
 * нестабільними (flaky).
 */
@RestController
@RequestMapping("/charges")
public class ChargeController {

    @PostMapping
    public ChargeResponse charge(@RequestBody ChargeRequest request) throws InterruptedException {
        simulateNetworkLatency();

        ChargeOutcome outcome = decide(request.amount());
        return new ChargeResponse(request.reference(), outcome, UUID.randomUUID().toString());
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
