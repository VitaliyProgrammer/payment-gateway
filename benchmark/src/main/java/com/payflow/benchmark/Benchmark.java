package com.payflow.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Закритоциклова навантажувалка: {@code N} "користувачів" (кожен - віртуальний
 * потік) у циклі створюють платіж і чекають, поки він дійде термінального
 * статусу, потім одразу починають наступний. Міряємо наскрізну затримку
 * "створення -> термінальний статус" і пропускну здатність.
 *
 * <p>Сенс - порівняти дві конфігурації ШЛЮЗУ (platform vs virtual threads на
 * Tomcat), ганяючи проти обох ту саму навантажувалку. Сам генератор завжди на
 * віртуальних потоках - він тут не вузьке місце.
 *
 * <p>Конфіг - через {@code -Dbench.*} системні властивості (див. {@link #cfg}).
 * Не є частиною CI; запуск - через {@code ops/benchmark/run-benchmark.sh}.
 */
public final class Benchmark {

    private static final Set<String> TERMINAL = Set.of("AUTHORIZED", "DECLINED", "FAILED");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private enum Kind { COMPLETED, HTTP_ERROR, CONN_ERROR, TIMEOUT, ABORTED }

    private record Outcome(Kind kind, long latencyNanos) {
        static final Outcome HTTP_ERROR = new Outcome(Kind.HTTP_ERROR, 0);
        static final Outcome CONN_ERROR = new Outcome(Kind.CONN_ERROR, 0);
        static final Outcome TIMEOUT = new Outcome(Kind.TIMEOUT, 0);
        static final Outcome ABORTED = new Outcome(Kind.ABORTED, 0);
    }

    private record LevelResult(int concurrency, long completed, double elapsedSeconds, Percentiles latencyMillis,
            long httpErrors, long connErrors, long timeouts) {

        double throughputPerSecond() {
            return elapsedSeconds <= 0 ? 0 : completed / elapsedSeconds;
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final long amount;
    private final String currency;
    private final Duration requestTimeout;
    private final Duration terminalTimeout;
    private final long pollMillis;
    private final HttpClient http;

    private Benchmark() {
        this.baseUrl = cfg("bench.baseUrl", "http://localhost:8080").replaceAll("/+$", "");
        this.apiKey = cfg("bench.apiKey", "demo-merchant-api-key");
        this.amount = Long.parseLong(cfg("bench.amount", "5000"));
        this.currency = cfg("bench.currency", "UAH");
        this.requestTimeout = Duration.ofSeconds(Long.parseLong(cfg("bench.requestTimeoutSeconds", "15")));
        this.terminalTimeout = Duration.ofSeconds(Long.parseLong(cfg("bench.terminalTimeoutSeconds", "20")));
        this.pollMillis = Long.parseLong(cfg("bench.pollMillis", "50"));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Benchmark benchmark = new Benchmark();

        String label = cfg("bench.label", "gateway");
        int warmupSeconds = Integer.parseInt(cfg("bench.warmupSeconds", "5"));
        int durationSeconds = Integer.parseInt(cfg("bench.durationSeconds", "20"));
        List<Integer> levels = new ArrayList<>();
        for (String part : cfg("bench.concurrency", "50,200,500,1000").split(",")) {
            levels.add(Integer.parseInt(part.trim()));
        }

        System.out.printf("# Benchmark: label=%s base=%s warmup=%ds measure=%ds levels=%s%n",
                label, benchmark.baseUrl, warmupSeconds, durationSeconds, levels);
        System.out.println("# load generator: java.net.http on virtual threads, closed-loop (request-after-request)");
        System.out.println();

        List<LevelResult> results = new ArrayList<>();
        for (int concurrency : levels) {
            System.out.printf("## level %d: warmup %ds ...%n", concurrency, warmupSeconds);
            benchmark.runPhase(concurrency, Duration.ofSeconds(warmupSeconds));

            System.out.printf("## level %d: measuring %ds ...%n", concurrency, durationSeconds);
            LevelResult result = benchmark.runPhase(concurrency, Duration.ofSeconds(durationSeconds));
            results.add(result);
            System.out.println(row(label, result));
            System.out.println();
        }

        System.out.println();
        System.out.println(header());
        for (LevelResult result : results) {
            System.out.println(row(label, result));
        }
    }

    private LevelResult runPhase(int concurrency, Duration duration) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        List<Future<List<Outcome>>> futures = new ArrayList<>(concurrency);

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> worker(running)));
            }
            Thread.sleep(duration.toMillis());
            running.set(false);
            pool.shutdown();
            if (!pool.awaitTermination(terminalTimeout.toSeconds() + 10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        List<Long> latencyMillis = new ArrayList<>();
        long completed = 0;
        long httpErrors = 0;
        long connErrors = 0;
        long timeouts = 0;
        for (Future<List<Outcome>> future : futures) {
            for (Outcome outcome : join(future)) {
                switch (outcome.kind()) {
                    case COMPLETED -> {
                        completed++;
                        latencyMillis.add(outcome.latencyNanos() / 1_000_000);
                    }
                    case HTTP_ERROR -> httpErrors++;
                    case CONN_ERROR -> connErrors++;
                    case TIMEOUT -> timeouts++;
                    case ABORTED -> { /* був у польоті на момент зупинки - не рахуємо */ }
                }
            }
        }

        long[] values = latencyMillis.stream().mapToLong(Long::longValue).toArray();
        return new LevelResult(concurrency, completed, elapsedSeconds, new Percentiles(values),
                httpErrors, connErrors, timeouts);
    }

    private List<Outcome> worker(AtomicBoolean running) {
        List<Outcome> outcomes = new ArrayList<>();
        while (running.get()) {
            outcomes.add(onePayment(running));
        }
        return outcomes;
    }

    private Outcome onePayment(AtomicBoolean running) {
        long start = System.nanoTime();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> created;
        try {
            created = http.send(createRequest(key), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            return Outcome.CONN_ERROR;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.ABORTED;
        }
        if (created.statusCode() != 201) {
            return Outcome.HTTP_ERROR;
        }

        String id = match(ID, created.body());
        if (id == null) {
            return Outcome.HTTP_ERROR;
        }
        String status = match(STATUS, created.body());

        long deadline = start + terminalTimeout.toNanos();
        while (status == null || !TERMINAL.contains(status)) {
            if (!running.get()) {
                return Outcome.ABORTED;
            }
            if (System.nanoTime() > deadline) {
                return Outcome.TIMEOUT;
            }
            sleepMillis(pollMillis);

            HttpResponse<String> polled;
            try {
                polled = http.send(getRequest(id), HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                return Outcome.CONN_ERROR;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Outcome.ABORTED;
            }
            if (polled.statusCode() != 200) {
                return Outcome.HTTP_ERROR;
            }
            status = match(STATUS, polled.body());
        }
        return new Outcome(Kind.COMPLETED, System.nanoTime() - start);
    }

    private HttpRequest createRequest(String idempotencyKey) {
        String body = "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
        return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest getRequest(String paymentId) {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments/" + paymentId))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
    }

    // --- дрібні помічники ---------------------------------------------------

    private static String cfg(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String match(Pattern pattern, String body) {
        if (body == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException("worker future failed", exception);
        }
    }

    private static String header() {
        return """
                | profile | conc | completed | thr/s | p50 ms | p90 ms | p99 ms | max ms | http-err | conn-err | timeout |
                |--------:|-----:|----------:|------:|-------:|-------:|-------:|-------:|---------:|---------:|--------:|""";
    }

    private static String row(String label, LevelResult r) {
        Percentiles p = r.latencyMillis();
        return String.format(Locale.ROOT, "| %s | %d | %d | %.1f | %d | %d | %d | %d | %d | %d | %d |",
                label, r.concurrency(), r.completed(), r.throughputPerSecond(),
                p.p(50), p.p(90), p.p(99), p.max(),
                r.httpErrors(), r.connErrors(), r.timeouts());
    }
}
