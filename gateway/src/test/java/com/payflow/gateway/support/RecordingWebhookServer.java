package com.payflow.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Стенд замість справжнього сервера мерчанта - JDK-вий HttpServer, без нових
 * залежностей у тестах. Достатньо, щоб перевірити реальний наскрізний HTTP-
 * виклик (заголовки, підпис, тіло), не піднімаючи окремий процес чи бібліотеку
 * на кшталт WireMock.
 */
public class RecordingWebhookServer implements AutoCloseable {

    private final HttpServer server;
    private final BlockingQueue<RecordedWebhookRequest> received = new LinkedBlockingQueue<>();
    private final AtomicInteger failuresToInject = new AtomicInteger(0);
    // Коли задано, ін'єктовані 500 застосовуються лише до вебхуків цього платежу -
    // щоб чужий (протеклий з іншого тестового класу) вебхук не "з'їв" відмову.
    private final AtomicReference<UUID> failOnlyFor = new AtomicReference<>();

    public RecordingWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = exchange.getRequestHeaders().getFirst("X-Payflow-Signature");
        String eventType = exchange.getRequestHeaders().getFirst("X-Payflow-Event-Type");
        RecordedWebhookRequest request = new RecordedWebhookRequest(body, signature, eventType);
        received.add(request);

        UUID onlyFor = failOnlyFor.get();
        boolean eligible = onlyFor == null || request.isFor(onlyFor);
        boolean shouldFail = eligible && failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
        int status = shouldFail ? 500 : 200;
        exchange.sendResponseHeaders(status, -1);
        try (OutputStream ignored = exchange.getResponseBody()) {
            // немає тіла відповіді - клієнту цього достатньо
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/webhook";
    }

    /** Наступні {@code count} вхідних викликів отримають 500 замість 200. */
    public void failNextAttempts(int count) {
        failOnlyFor.set(null);
        failuresToInject.set(count);
    }

    /** Те саме, але 500 отримають лише вебхуки саме про {@code paymentId}. */
    public void failNextAttemptsFor(UUID paymentId, int count) {
        failOnlyFor.set(paymentId);
        failuresToInject.set(count);
    }

    /**
     * Наступний вебхук саме про цей платіж, пропускаючи (і відкидаючи) чужі, що
     * могли протекти з іншого тестового класу через спільну базу демо-мерчанта.
     */
    public RecordedWebhookRequest awaitNextFor(UUID paymentId, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                long ms = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                RecordedWebhookRequest request = received.poll(ms, TimeUnit.MILLISECONDS);
                if (request == null) {
                    break;
                }
                if (request.isFor(paymentId)) {
                    return request;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        throw new AssertionError("No webhook for payment " + paymentId + " within " + timeout);
    }

    public List<RecordedWebhookRequest> awaitCountFor(UUID paymentId, int count, Duration timeout) {
        List<RecordedWebhookRequest> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (collected.size() < count) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative()) {
                throw new AssertionError("Expected " + count + " webhooks for payment " + paymentId
                        + " but got " + collected.size() + " within " + timeout);
            }
            collected.add(awaitNextFor(paymentId, remaining));
        }
        return collected;
    }

    public RecordedWebhookRequest awaitNext(Duration timeout) {
        try {
            RecordedWebhookRequest request = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (request == null) {
                throw new AssertionError("No webhook request received within " + timeout);
            }
            return request;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    public List<RecordedWebhookRequest> awaitCount(int count, Duration timeout) {
        List<RecordedWebhookRequest> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);

        while (collected.size() < count) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative()) {
                throw new AssertionError("Expected " + count + " webhook requests but got " + collected.size()
                        + " within " + timeout);
            }
            collected.add(awaitNext(remaining));
        }
        return collected;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
