package com.payflow.gateway.exception;

/**
 * Кидається, коли запитаний перехід стану неможливий із поточного стану
 * платежу (наприклад, capture платежу, який уже CAPTURED чи CANCELED).
 * Використовується і воркером обробки (стадія 3, де просто пропускає такий
 * платіж), і API capture/cancel/refund (стадія 4, де це мапиться на 409 -
 * хтось інший вже змінив стан цього платежу).
 */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
