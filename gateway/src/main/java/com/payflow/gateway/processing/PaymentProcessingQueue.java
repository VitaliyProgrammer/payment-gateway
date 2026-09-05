package com.payflow.gateway.processing;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * offer(), а не put(). put() заблокував би HTTP-потік, що створює платіж, якщо
 * черга повна - саме та помилка, яку цей проєкт свідомо не повторює (вона була
 * в попередньому навчальному проєкті). offer() повертає false одразу, і виклик
 * перетворює це на чесний 503 Retry-After, а не на зависання запиту.
 */
@Component
public class PaymentProcessingQueue {

    private final BlockingQueue<UUID> queue;

    public PaymentProcessingQueue(@Value("${payflow.processing.queue-capacity:500}") int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public boolean tryEnqueue(UUID paymentId) {
        return queue.offer(paymentId);
    }

    public UUID take() throws InterruptedException {
        return queue.take();
    }
}
