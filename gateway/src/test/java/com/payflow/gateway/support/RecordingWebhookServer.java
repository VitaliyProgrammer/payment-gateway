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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
        received.add(new RecordedWebhookRequest(body, signature, eventType));

        boolean shouldFail = failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
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
        failuresToInject.set(count);
    }

    public RecordedWebhookRequest awaitNext(Duration timeout) {
        try {
            RecordedWebhookRequest request = received.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
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
