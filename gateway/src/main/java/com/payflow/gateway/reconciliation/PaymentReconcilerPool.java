package com.payflow.gateway.reconciliation;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Той самий SmartLifecycle-патерн, що й PaymentWorkerPool (стадія 3) і OutboxPollerPool (стадія 5). */
@Component
public class PaymentReconcilerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcilerPool.class);

    private final PaymentReconciler reconciler;
    private final int reconcilerCount;
    private final List<Thread> reconcilerThreads = new ArrayList<>();
    private volatile boolean running = false;

    public PaymentReconcilerPool(PaymentReconciler reconciler,
            @Value("${payflow.reconciliation.reconciler-count:1}") int reconcilerCount) {
        this.reconciler = reconciler;
        this.reconcilerCount = reconcilerCount;
    }

    @Override
    public void start() {
        for (int i = 0; i < reconcilerCount; i++) {
            reconcilerThreads.add(Thread.ofVirtual().name("payment-reconciler-", i).start(reconciler));
        }
        running = true;
        log.info("Started {} payment reconciler(s)", reconcilerCount);
    }

    @Override
    public void stop() {
        reconcilerThreads.forEach(Thread::interrupt);
        for (Thread thread : reconcilerThreads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        reconcilerThreads.clear();
        running = false;
        log.info("Stopped payment reconcilers");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
