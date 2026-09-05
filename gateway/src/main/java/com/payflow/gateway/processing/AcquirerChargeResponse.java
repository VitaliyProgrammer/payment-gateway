package com.payflow.gateway.processing;

public record AcquirerChargeResponse(String reference, AcquirerOutcome outcome, String acquirerReference) {
}
