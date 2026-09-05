package com.payflow.gateway.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(

        @NotNull
        @Positive(message = "amount must be a positive number of minor currency units (e.g. cents)")
        Long amount,

        @NotNull
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code, e.g. USD")
        String currency
) {
}
