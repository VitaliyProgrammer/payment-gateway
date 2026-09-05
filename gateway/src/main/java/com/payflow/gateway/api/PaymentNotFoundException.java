package com.payflow.gateway.api;

import java.util.UUID;

/**
 * Кидається як тоді, коли платежу справді не існує, так і тоді, коли він належить
 * іншому мерчанту. Навмисно однаковий результат в обох випадках: сказати
 * виклику "цей id належить комусь іншому" означало б підтвердити, що id валідний,
 * і розкрити існування чужого платежу, а звичайний 404 цього не робить.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found: " + paymentId);
    }
}
