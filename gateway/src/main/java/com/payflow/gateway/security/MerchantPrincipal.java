package com.payflow.gateway.security;

import java.util.UUID;

/** Ідентифікує мерчанта, що робить запит, протягом одного автентифікованого запиту. */
public record MerchantPrincipal(UUID merchantId) {
}
