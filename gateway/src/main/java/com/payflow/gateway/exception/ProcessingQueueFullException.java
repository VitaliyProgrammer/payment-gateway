package com.payflow.gateway.exception;

/**
 * Черга обробки платежів заповнена. Кидається ДО будь-якого запису в базу -
 * коли це стається, жоден платіж ще не створений і ключ ідемпотентності
 * просто звільняється, тож клієнт може безпечно повторити той самий запит
 * (навіть з тим самим Idempotency-Key) пізніше.
 */
public class ProcessingQueueFullException extends RuntimeException {

    public ProcessingQueueFullException() {
        super("Черга обробки платежів наразі заповнена, спробуйте пізніше");
    }
}
