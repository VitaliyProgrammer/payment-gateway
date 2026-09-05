package com.payflow.gateway.idempotency;

import java.util.UUID;

import com.payflow.gateway.repository.IdempotencyRecordRepository;
import com.payflow.gateway.service.PaymentIdempotencyService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Кожен метод тут комітиться в ОКРЕМІЙ транзакції ({@code REQUIRES_NEW}),
 * незалежно від того, що відбувається навколо. Це принципово: захоплення ключа
 * має стати видимим іншим конкурентним запитам одразу, ще до того, як почне
 * виконуватись власне створення платежу - інакше конкурентні запити просто не
 * побачать один одного в базі, і гонку неможливо буде виявити.
 *
 * <p>Клас навмисно окремий від {@link PaymentIdempotencyService}: якби ці методи
 * викликались як {@code this.tryClaim(...)} з того самого класу, Spring не зміг
 * би перехопити виклик своїм проксі, і {@code @Transactional} тихо ігнорувалася
 * б (класична пастка self-invocation). Виклик з іншого біна змушує Spring пройти
 * через проксі й справді відкрити нову транзакцію для кожного методу.
 */
@Component
public class IdempotencyGuard {

    private final IdempotencyRecordRepository repository;

    public IdempotencyGuard(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Намагається "застовпити" ключ. flush() тут не косметика - без нього INSERT
     * лишився б відкладеним до кінця транзакції, і ми дізнались би про конфлікт
     * унікального індексу вже після того, як помилково повідомили б виклику, що
     * ключ захоплено.
     *
     * <p>Кидає {@link org.springframework.dao.DataIntegrityViolationException},
     * якщо ключ уже зайнятий, і навмисно НЕ ловить цей виняток тут, усередині
     * власної транзакції. На Postgres будь-яка помилка команди (включно з
     * порушенням унікального індексу) переводить усю поточну транзакцію в стан
     * aborted - і якщо зловити виняток прямо тут, Spring на виході з методу все
     * одно спробує її закомітити, отримає від бази відмову і кине
     * {@code UnexpectedRollbackException} замість очікуваного "ключ зайнятий".
     * Тому виняток навмисно летить назовні: тоді проксі Spring сам коректно
     * відкочує саме цю транзакцію, а виклик ловить виняток уже після цього, поза
     * межами транзакції.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(UUID merchantId, String idempotencyKey, String requestFingerprint) {
        IdempotencyRecord record =
                new IdempotencyRecord(UUID.randomUUID(), merchantId, idempotencyKey, requestFingerprint);
        return repository.saveAndFlush(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(IdempotencyRecord record, int responseStatus, String responseBody) {
        record.complete(responseStatus, responseBody);
        repository.save(record);
    }

    /**
     * Якщо сама операція провалилась, звільняємо ключ, щоб клієнт міг повторити
     * запит із тим самим Idempotency-Key і не застряг назавжди на статусі
     * IN_PROGRESS, чекаючи завершення, якого ніколи не станеться.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyRecord record) {
        repository.deleteById(record.getId());
    }
}
