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
- Micrometer + Actuator + Prometheus
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

See `ROADMAP.md` for the staged build-out and the specific concurrency scenarios each
stage proves with a test.

***

## ▶️ Running it

```bash
docker compose up --build
```

```bash
curl -X POST localhost:8080/v1/payments \
  -H "Authorization: Bearer <merchant-api-key>" \
  -H "Idempotency-Key: <uuid>" \
  -d '{"amount": 5000, "currency": "UAH"}'
```

**Locally, without Docker for the app itself:**

```bash
./mvnw -pl gateway -am spring-boot:run
```

(Postgres and the mock acquirer still need to be reachable - either via
`docker compose up postgres mock-acquirer`, or point `DB_URL` / `ACQUIRER_BASE_URL` at
your own instances.)

***

## 🚫 Deliberately out of scope

- Real card numbers - only fake ones understood by `mock-acquirer`. Storing real PANs
  without PCI DSS compliance is not a shortcut worth taking, even in a portfolio project.
- 3D Secure, currency conversion, fraud scoring, payouts, bank reconciliation - each is
  its own project.
- Customer balances, wallets, transfers between users - that is a banking-platform
  problem, not a gateway problem, and mixing the two would blur what this project is
  actually demonstrating.
