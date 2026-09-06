package com.payflow.acquirer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Пам'ять еквайра про вже оброблені charge-и, за {@code reference} (id платежу
 * в шлюзі). Робить {@code POST /charges} ідемпотентним: перший виклик з даним
 * reference вирішує підсумок і карбує {@code acquirerReference}, а кожен повтор
 * повертає ТОЙ САМИЙ результат, нічого не "списуючи" вдруге.
 *
 * <p>Саме на це спирається примирення на боці шлюзу (стадія 6): після таймауту
 * читання шлюз не знає, чи авторизація відбулась, - і мусить мати змогу
 * безпечно ПЕРЕПИТАТИ підсумок ({@code GET /charges/{reference}}) чи повторити
 * той самий POST, не ризикуючи подвійним списанням.
 *
 * <p>{@code computeIfAbsent} на {@link ConcurrentHashMap} - щоб два одночасні
 * однакові запити згорнулись в один запис, а не в два з різними
 * acquirerReference.
 */
@Component
public class ChargeStore {

    private final ConcurrentHashMap<String, ChargeResponse> byReference = new ConcurrentHashMap<>();

    ChargeResponse recordOrGet(String reference, Supplier<ChargeOutcome> outcomeIfNew) {
        return byReference.computeIfAbsent(reference,
                ref -> new ChargeResponse(ref, outcomeIfNew.get(), UUID.randomUUID().toString()));
    }

    Optional<ChargeResponse> find(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }
}
