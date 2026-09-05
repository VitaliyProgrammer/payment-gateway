package com.payflow.gateway.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateRefundRequest(

        @NotNull
        @Positive(message = "amount must be a positive number of minor currency units (e.g. cents)")
        Long amount
) {
}
