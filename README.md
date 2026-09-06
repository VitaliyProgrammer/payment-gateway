## 💳 Payment Gateway

A payment gateway in the shape of Stripe/Fondy, not a wallet in the shape of PayPal: it
authorizes and captures payments on behalf of merchants and talks to an acquiring bank
on their behalf. It does not hold customer balances or move money between users - that
is a different problem with a different data model.

The point of this project is **correctness under concurrent load**, not the size of the
feature list: idempotent APIs, a payment state machine that cannot be pushed into an
invalid state by two requests racing each other, and webhook delivery that survives a
merchant's server being down.

***

## ⚙ Why this exists

Payment processing is one of the few domains where getting concurrency wrong has an
unambiguous, measurable failure: money appears or disappears. That makes it a good
vehicle for demonstrating things that are easy to *claim* and hard to *fake*:

- Idempotency under a real race, not a single-threaded happy path
- An invariant (captured amount ≥ sum of refunds) that concurrent requests cannot violate
- The difference virtual threads make to a service whose work is mostly waiting on I/O
- Reliable delivery to a consumer that goes away

***

## 🧱 Architecture

- **`gateway`** - the service itself: HTTP API, payment state machine, persistence,
  outbox, webhook delivery
- **`mock-acquirer`** - a separate deployable standing in for a real acquiring bank.
  Deliberately its own service rather than an in-process stub, so a call to it is a real
  network hop that really blocks - the thing the concurrency design has to handle.

***

## 🧰 Technology

- Java 21 (LTS) with virtual threads
- Spring Boot 3, Spring Data JPA / Hibernate
- PostgreSQL + Flyway (schema owned by migrations, not by Hibernate)
- A hand-rolled circuit breaker on the acquirer (same style as the rest of the
  resilience primitives here - small, commented, no extra dependency)
- Micrometer + Actuator + Prometheus + a pre-provisioned Grafana dashboard
- JUnit 5, Testcontainers (real Postgres in tests), Awaitility
- Docker Compose, GitHub Actions

***

## 🧵 Concurrency notes

- **`ReentrantLock`, not `synchronized`**, anywhere a virtual thread can block. On
  Java 21, a virtual thread blocked inside a `synchronized` block pins its carrier
  thread - fixed only in JDK 24 (JEP 491). Since this project is built around
  virtual threads doing exactly that kind of blocking, `synchronized` would quietly
  reintroduce the platform-thread bottleneck it is meant to remove.
- **Money is stored as `bigint` minor units** (cents/kopecks), never `double` and never
  an unscaled `BigDecimal` column - so no rounding question can ever arise.
- **The database is the thing allowed to push back.** Virtual threads make the HTTP
  layer cheap to scale, but the connection pool stays bounded on purpose: under load the
  queue should move to the database's front door, not disappear.

### What the tests prove

Every row is a Testcontainers test against a real Postgres, run in CI.

| Concern | Proven by |
|---|---|
| Idempotency under a real race | 200 concurrent same-key requests create **exactly one** payment |
| Honest overload | a saturated connection pool answers `503`/`409` with `Retry-After`, never a misleading `401`/`500` |
| Async backpressure | a full processing queue answers `503`, it never blocks the request thread |
| Guarded state machine | concurrent `capture` and `cancel` on one payment: exactly one wins, the other gets `409` |
| Sum invariant | 10 concurrent partial refunds of 20 against a 100 capture succeed exactly 5 times, the total never exceeds the capture |
| Ordered delivery | webhooks for one payment arrive in order even when an earlier one is mid-retry |
| Dead-lettering | a webhook that fails every attempt lands in `DEAD_LETTER`, not stuck `PENDING` forever |
| No double charge | a timed-out acquirer call is resolved by *asking* the acquirer, not re-charging - the charge is sent exactly once |

***

## 🔀 Payment state machine

Transitions are named methods on `Payment` that check the source state and throw
otherwise; the `version` column makes two concurrent transitions on one row impossible
to both succeed silently. `PROCESSING` and `NEEDS_RECONCILIATION` are internal transit
states - the merchant only ever hears about the terminal ones, via a signed webhook.

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PROCESSING: worker picks it up
    CREATED --> FAILED: queue full
    PROCESSING --> AUTHORIZED: acquirer approves
    PROCESSING --> DECLINED: acquirer declines
    PROCESSING --> FAILED: permanent 4xx error
    PROCESSING --> NEEDS_RECONCILIATION: timeout, 5xx or breaker open
    NEEDS_RECONCILIATION --> AUTHORIZED: acquirer confirms approved
    NEEDS_RECONCILIATION --> DECLINED: acquirer confirms declined
    NEEDS_RECONCILIATION --> FAILED: never charged or retries exhausted
    AUTHORIZED --> CAPTURED: capture
    AUTHORIZED --> CANCELED: cancel
    CAPTURED --> PARTIALLY_REFUNDED: partial refund
    CAPTURED --> REFUNDED: full refund
    PARTIALLY_REFUNDED --> REFUNDED: last refund reaches the captured amount
    DECLINED --> [*]
    CANCELED --> [*]
    REFUNDED --> [*]
    FAILED --> [*]
```

Refunds are additive: as many partial refunds as fit under the captured amount, so a
payment can sit in `PARTIALLY_REFUNDED` across several of them. A payment abandoned in
`PROCESSING` (the worker or the whole instance died mid-call) is picked up by the same
reconciliation sweeper once it is older than a staleness threshold.

***

## ▶️ Running it

**One command** - builds the images, starts the stack, then walks a payment through its
whole lifecycle (create → authorize → capture → refund) and prints each step:

```bash
./run.sh
```

`./run.sh --monitoring` also starts Prometheus and Grafana; `./run.sh down` stops
everything.

**Without building** - pull the pre-built images (published to GHCR on every push to
`main`), ~30 s to ready instead of a Maven build:

```bash
docker compose -f docker-compose.prod.yml up
```

**Plain compose:**

```bash
docker compose up --build
```

Any of these brings up Postgres, the mock acquirer and the gateway on `localhost:8080`.
The demo merchant (`demo-merchant-api-key`, seeded by the migrations) is ready
immediately:

```bash
curl -X POST localhost:8080/v1/payments \
  -H "Authorization: Bearer demo-merchant-api-key" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 5000, "currency": "UAH"}'
```

`ops/demo.sh` (what `./run.sh` calls) walks the lifecycle: create, poll to a terminal
status, capture, partial refund, and a repeated idempotency key returning the same id.

Host ports: gateway `8080`, Postgres `5432`, mock acquirer `8090` (**not** `9090` - it
clashes too often on shared machines), Prometheus `9091`, Grafana `3000`. Override any of
them, and `IMAGE_BASE` for the pre-built images, with a `.env` file - see `.env.example`.

**Locally, without Docker for the app itself:**

```bash
./mvnw -pl gateway -am spring-boot:run
```

(Postgres and the mock acquirer still need to be reachable - either via
`docker compose up postgres mock-acquirer`, or point `DB_URL` / `ACQUIRER_BASE_URL` at
your own instances. The default `ACQUIRER_BASE_URL` already targets `localhost:8090`.)

***

## 📈 Metrics & dashboard

```bash
docker compose --profile monitoring up --build
```

Adds Prometheus (`localhost:9091`) and Grafana (`localhost:3000`, anonymous viewing
enabled). The **Payflow gateway** dashboard is provisioned automatically - queue depth,
per-status transition rate, acquirer call latency and outcomes, circuit-breaker state,
Hikari pool, outbox backlog and reconciliation lag - alongside the raw metrics at
`localhost:8080/actuator/prometheus`.

***

## 🏁 Benchmark: platform vs virtual threads

```bash
ops/benchmark/run-benchmark.sh
```

The `benchmark` module is a dependency-free load generator (`java.net.http` on virtual
threads, hand-rolled nearest-rank percentiles). It runs a **closed-loop** workload -
each simulated client creates a payment, polls until it reaches a terminal status, then
immediately starts another - against the gateway twice: once with
`spring.threads.virtual.enabled=false` (Tomcat's default 200-thread pool), once with it
`true`. The processing-worker pool is raised for the run so the acquirer drain rate is
not the ceiling; the acquirer adds 50-300&nbsp;ms of simulated latency per authorize.

Numbers are machine-specific and the high-concurrency rows are only meaningful on a
machine that is not itself memory-starved, so the table here is left for you to fill
from your own run - the script writes the combined result to `ops/benchmark/results.md`.
Defaults are `LEVELS=50,200,500,1000`, 20&nbsp;s measured per level.

<!-- BENCHMARK_TABLE_START -->
| profile | conc | completed | thr/s | p50 ms | p90 ms | p99 ms | max ms | http-err | conn-err | timeout |
|--------:|-----:|----------:|------:|-------:|-------:|-------:|-------:|---------:|---------:|--------:|
| _run `ops/benchmark/run-benchmark.sh`_ | | | | | | | | | | |
<!-- BENCHMARK_TABLE_END -->

**What to look for.** The connection pool is the deliberate bottleneck (see *Concurrency
notes*), so peak *throughput* converges between the two thread models - the DB serves the
same number of payments either way. The difference is in the tail under overload: with
platform threads, once all 200 Tomcat threads are parked waiting on the pool, further
connections are refused outright (`conn-err` climbs) and accepted requests hit a latency
cliff. With virtual threads every request is accepted and either completes or gets an
honest `503`, so `p99` grows smoothly and `conn-err` stays near zero.

***

## 🚫 Deliberately out of scope

- Real card numbers - only fake ones understood by `mock-acquirer`. Storing real PANs
  without PCI DSS compliance is not a shortcut worth taking, even in a portfolio project.
- 3D Secure, currency conversion, fraud scoring, payouts, bank reconciliation - each is
  its own project.
- Customer balances, wallets, transfers between users - that is a banking-platform
  problem, not a gateway problem, and mixing the two would blur what this project is
  actually demonstrating.
