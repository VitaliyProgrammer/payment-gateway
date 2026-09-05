package com.payflow.gateway.exception;

/** Той самий Idempotency-Key повторно використали з іншим тілом запиту. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' вже використовувався із іншим тілом запиту");
    }
}
