package com.payflow.gateway.processing;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * SmartLifecycle, а не @PostConstruct/@PreDestroy - так запуск і зупинка
 * воркерів проходять через той самий керований Spring-ом життєвий цикл, що й
 * решта інфраструктури (включно з graceful shutdown, який уже налаштований у
 * application.yml). Кожен воркер - окремий довгоживучий віртуальний потік:
 * дешевий у створенні, тож "пул" тут - це просто фіксована кількість таких
 * потоків, а не складна структура на кшталт ExecutorService з чергою задач.
 */
@Component
public class PaymentWorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorkerPool.class);

    private final PaymentProcessingWorker worker;
    private final int workerCount;
    private final List<Thread> workerThreads = new ArrayList<>();
    private volatile boolean running = false;

    public PaymentWorkerPool(PaymentProcessingWorker worker,
            @Value("${payflow.processing.worker-count:8}") int workerCount) {
        this.worker = worker;
        this.workerCount = workerCount;
    }

    @Override
    public void start() {
        for (int i = 0; i < workerCount; i++) {
            Thread thread = Thread.ofVirtual().name("payment-worker-", i).start(worker);
            workerThreads.add(thread);
        }
        running = true;
        log.info("Started {} payment processing workers", workerCount);
    }

    @Override
    public void stop() {
        // Перериваємо очікування на queue.take(), щоб воркери перестали
        // забирати НОВУ роботу. Платіж, який саме зараз викликає еквайра, не
        // обривається насильно - він або встигне завершитись, або лишиться в
        // PROCESSING і буде підхоплений майбутнім sweeper-ом зі стадії 6.
        workerThreads.forEach(Thread::interrupt);
        for (Thread thread : workerThreads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        workerThreads.clear();
        running = false;
        log.info("Stopped payment processing workers");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
