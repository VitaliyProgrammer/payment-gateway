package com.payflow.gateway.service;

import com.payflow.gateway.entity.Payment;
import java.time.Duration;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Capture і cancel - взаємовиключні одноразові дії: якщо вони зіштовхнулись на
 * одному платежі, програш гонки за версію - це чесна відмова ("хтось інший
 * щойно змінив цей платіж"), а не привід ретраїти. Ретраїти тут було б
 * неправильно по суті - переможець уже вирішив долю платежу, і повторна спроба
 * програвшого не повинна магічно "перетворитись" на іншу дію.
 *
 * <p>Refund - інша природа: це адитивна операція "поки не вичерпано ліміт".
 * Кілька конкурентних часткових повернень МОЖУТЬ усі бути законними, якщо їх
 * сума вкладається в captured_amount - тому тут потрібен ретрай із свіжим
 * читанням стану, а не одноразова спроба.
 */
@Service
public class PaymentLifecycleService {

    private static final int MAX_REFUND_ATTEMPTS = 25;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(5);

    private final PaymentTransitionGuard guard;

    public PaymentLifecycleService(PaymentTransitionGuard guard) {
        this.guard = guard;
    }

    public Payment capture(UUID paymentId, UUID merchantId) {
        return guard.capture(paymentId, merchantId);
    }

    public Payment cancel(UUID paymentId, UUID merchantId) {
        return guard.cancel(paymentId, merchantId);
    }

    public Payment refund(UUID paymentId, UUID merchantId, long amount) {
        for (int attempt = 1; attempt <= MAX_REFUND_ATTEMPTS; attempt++) {
            try {
                return guard.refund(paymentId, merchantId, amount);
            } catch (ObjectOptimisticLockingFailureException exception) {
                if (attempt == MAX_REFUND_ATTEMPTS) {
                    throw exception;
                }
                sleep(RETRY_BACKOFF);
            }
        }
        // Недосяжно: цикл або повертає результат, або кидає виняток на останній ітерації.
        throw new IllegalStateException("unreachable");
    }

    /**
     * Звичайний sleep на віртуальному потоці - дешевий навіть під десятками
     * одночасних ретраїв, той самий підхід, що й у PaymentIdempotencyService.
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a refund", exception);
        }
    }
}
