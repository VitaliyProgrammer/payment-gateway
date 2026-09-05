package com.payflow.gateway.entity.status;

/**
 * {@code AUTHORIZED} і {@code CAPTURED} навмисно залишені окремими станами, а не
 * об'єднані в один - мерчант може заблокувати кошти (авторизація), так і не
 * забравши гроші, і скасувати блокування замість захоплення. Об'єднавши ці два
 * стани, ми втратили б можливість це представити - а це саме той тип спрощення,
 * через яке доменна модель платежів перестає виглядати переконливо.
 */
public enum PaymentStatus {
    CREATED,
    PROCESSING,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    DECLINED,
    CANCELED,
    FAILED,
    NEEDS_RECONCILIATION
}
