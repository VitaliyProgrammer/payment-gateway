package com.payflow.gateway.exception;

/**
 * Інший запит із тим самим ключем усе ще виконується довше, ніж ми готові
 * чекати. У нормальних умовах це не мало б статись - операція створення
 * платежу швидка - тож це, найімовірніше, ознака того, що переможець гонки
 * завис (наприклад, застряг на виклику до еквайра, ще не реалізованому на цій
 * стадії).
 */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String idempotencyKey) {
        super("Запит із Idempotency-Key '" + idempotencyKey + "' все ще обробляється; спробуйте пізніше");
    }
}
