package com.payflow.acquirer;

public record ChargeRequest(String reference, long amount, String currency) {
}
