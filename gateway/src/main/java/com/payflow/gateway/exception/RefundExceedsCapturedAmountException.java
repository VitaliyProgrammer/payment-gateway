package com.payflow.gateway.exception;

import java.util.UUID;

/**
 * На відміну від {@link InvalidPaymentStateException} (стан платежу не
 * дозволяє операцію взагалі), тут стан коректний (CAPTURED чи
 * PARTIALLY_REFUNDED), але саме ЦЯ сума повернення не поміщається в те, що
 * лишилось незверненим. Це помилка запиту (400), а не конфлікт (409) - клієнт
 * попросив забагато, а не хтось інший втрутився паралельно.
 */
public class RefundExceedsCapturedAmountException extends RuntimeException {

    public RefundExceedsCapturedAmountException(UUID paymentId, long requestedAmount, long remainingAmount) {
        super("Refund of " + requestedAmount + " for payment " + paymentId
                + " exceeds the remaining refundable amount of " + remainingAmount);
    }
}
