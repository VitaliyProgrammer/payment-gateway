# Roadmap

Each stage leaves the application in a runnable state. Checked stages are done.

- [x] **Stage 0 - Skeleton.** Multi-module Maven build, Spring Boot + Postgres + Flyway
      wired together, Testcontainers-backed smoke test, CI green.
- [x] **Stage 1 - Domain and synchronous creation.** `POST /v1/payments`,
      `GET /v1/payments/{id}`, merchant API-key authentication, first Testcontainers
      test against real behaviour.
- [ ] **Stage 2 - Idempotency.** `Idempotency-Key` header; a race on the unique
      constraint decides the winner; every caller with the same key gets the same
      response.
      **Proves:** 200 concurrent requests with the same key create exactly one payment.
- [ ] **Stage 3 - Asynchronous processing.** `mock-acquirer` called for real; bounded
      queue + worker pool on virtual threads; `503 Retry-After` instead of blocking the
      HTTP thread when the queue is full; `@Version` guarding state transitions.
- [ ] **Stage 4 - Capture / cancel / refund.** Sum invariants held under concurrency.
      **Proves:** 10 concurrent partial refunds of 20 against a 100 capture succeed
      exactly 5 times and never exceed the captured amount; concurrent capture and
      cancel on the same payment - exactly one wins, no invalid state.
- [ ] **Stage 5 - Outbox and webhooks.** Transactional outbox, `FOR UPDATE SKIP LOCKED`
      poller (safe with multiple instances), signed webhooks, exponential backoff,
      dead-letter, in-order delivery per payment.
- [ ] **Stage 6 - Reconciliation and resilience.** Sweeper for payments stuck in
      `PROCESSING`; circuit breaker on the acquirer.
      **Proves:** a request that times out against the acquirer is never charged twice
      on retry.
- [ ] **Stage 7 - Observability and benchmark.** Micrometer metrics, Grafana dashboard
      in Compose, a load test comparing platform-thread and virtual-thread throughput
      under the same concurrent load.
- [ ] **Stage 8 - README and demo.** Concurrency section with the benchmark table,
      state-machine diagram, one-command `docker compose up` demo.

**Deliberately not in the MVP:** merchant dashboard UI, hosted payment page, demo shop -
these come after stage 8, once the API is stable, so the frontend is built once instead
of chasing a moving contract.
