package com.payflow.gateway.entity.status;

/**
 * wireName() - це те, що бачить мерчант у заголовку й тілі вебхука
 * ("payment.authorized"), а ім'я константи Java - те, що зберігається в
 * колонці event_type через звичайний @Enumerated(EnumType.STRING). Два різні
 * представлення навмисно: у БД зручніший стандартний enum-мапінг, назовні -
 * крапковий стиль, звичний для вебхуків (Stripe і подібні).
 */
public enum PaymentEventType {
    PAYMENT_AUTHORIZED("payment.authorized"),
    PAYMENT_DECLINED("payment.declined"),
    PAYMENT_FAILED("payment.failed"),
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_CANCELED("payment.canceled"),
    PAYMENT_PARTIALLY_REFUNDED("payment.partially_refunded"),
    PAYMENT_REFUNDED("payment.refunded");

    private final String wireName;

    PaymentEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
