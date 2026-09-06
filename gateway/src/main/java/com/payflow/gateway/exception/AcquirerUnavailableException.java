package com.payflow.gateway.exception;

import com.payflow.gateway.processing.AcquirerClient;

/**
 * "Запит до еквайра пішов - або міг піти, - але підсумок невідомий": таймаут
 * читання, розрив з'єднання, 5xx, або відкритий circuit breaker. Свідомо
 * ВІДРІЗНЯЄТЬСЯ від будь-якого іншого {@link RuntimeException} з
 * {@link AcquirerClient}: цей означає "спробуй дізнатись підсумок пізніше"
 * (платіж -> NEEDS_RECONCILIATION), тоді як, наприклад, 4xx чи помилка
 * серіалізації означає "це наш баг, ретрай не допоможе" (платіж -> FAILED).
 *
 * <p>Ключова причина, чому це окремий стан, а не просто FAILED: якщо еквайр
 * УСПІШНО авторизував платіж, а ми не дочекались відповіді, позначити платіж
 * FAILED - це втратити реальну авторизацію; а сліпо повторити authorize -
 * ризикнути подвійним списанням. Примирення (стадія 6) розв'язує обидва:
 * ПИТАЄ еквайра про підсумок замість повторного заряду.
 */
public class AcquirerUnavailableException extends RuntimeException {

    public AcquirerUnavailableException(String message) {
        super(message);
    }

    public AcquirerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
