package com.payflow.gateway.outbox;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Той самий SmartLifecycle-патерн, що й PaymentWorkerPool на стадії 3. */
@Component
public class OutboxPollerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollerPool.class);

    private final OutboxPoller poller;
    private final int pollerCount;
    private final List<Thread> pollerThreads = new ArrayList<>();
    private volatile boolean running = false;

    public OutboxPollerPool(OutboxPoller poller, @Value("${payflow.webhooks.poller-count:2}") int pollerCount) {
        this.poller = poller;
        this.pollerCount = pollerCount;
    }

    @Override
    public void start() {
        for (int i = 0; i < pollerCount; i++) {
            pollerThreads.add(Thread.ofVirtual().name("outbox-poller-", i).start(poller));
        }
        running = true;
        log.info("Started {} outbox pollers", pollerCount);
    }

    @Override
    public void stop() {
        pollerThreads.forEach(Thread::interrupt);
        for (Thread thread : pollerThreads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        pollerThreads.clear();
        running = false;
        log.info("Stopped outbox pollers");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
