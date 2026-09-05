package com.payflow.gateway.entity.status;

public enum OutboxEventStatus {
    PENDING,
    DELIVERED,
    DEAD_LETTER
}
