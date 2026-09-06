package com.payflow.gateway.processing;

import com.payflow.gateway.metrics.PaymentMetrics;
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
    private final PaymentMetrics metrics;

    public PaymentProcessingQueue(@Value("${payflow.processing.queue-capacity:500}") int capacity,
            PaymentMetrics metrics) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.metrics = metrics;
    }

    public boolean tryEnqueue(UUID paymentId) {
        boolean accepted = queue.offer(paymentId);
        metrics.queueEnqueue(accepted);
        return accepted;
    }

    public UUID take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Поточна глибина черги - джерело для gauge-метрики
     * {@code payflow.processing.queue.depth} (стадія 7). Дешева O(1)-операція на
     * ArrayBlockingQueue.
     */
    public int size() {
        return queue.size();
    }
}
