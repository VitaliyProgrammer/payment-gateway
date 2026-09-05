package com.payflow.acquirer;

public record ChargeResponse(
        String reference,
        ChargeOutcome outcome,
        String acquirerReference
) {
}
