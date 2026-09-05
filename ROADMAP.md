# Roadmap

Each stage leaves the application in a runnable state. Checked stages are done.

- [x] **Stage 0 - Skeleton.** Multi-module Maven build, Spring Boot + Postgres + Flyway
      wired together, Testcontainers-backed smoke test, CI green.
- [x] **Stage 1 - Domain and synchronous creation.** `POST /v1/payments`,
      `GET /v1/payments/{id}`, merchant API-key authentication, first Testcontainers
      test against real behaviour.
- [x] **Stage 2 - Idempotency.** `Idempotency-Key` header; a race on the unique
      constraint decides the winner; every caller with the same key gets the same
      response. Honest `503`/`409` (with `Retry-After`) instead of a misleading `401`
      when the connection pool is genuinely saturated - covers both `DataAccessException`
      (query fails) and `TransactionException` (pool exhausted before a transaction can
      even open), which are separate branches of Spring's exception hierarchy.
      **Proves:** 200 concurrent requests with the same key never create more than one
      payment. Under this much contention on a single row, some legitimately get a
      `503`/`409` instead of `201` - Postgres serializes conflicting inserts on one
      unique key regardless of connection pool size, so that is a capacity limit, not a
      bug; a `401` or `500` would have been.
- [x] **Stage 3 - Asynchronous processing.** `mock-acquirer` called for real over HTTP
      (`RestClient`, real network latency, deterministic decline rule for testability);
      bounded in-memory queue drained by a fixed pool of long-lived virtual-thread
      workers (`SmartLifecycle`-managed); `offer()` instead of `put()` so a full queue
      returns `503 Retry-After` instead of blocking the HTTP thread; named state-transition
      methods on `Payment` guard against invalid transitions, with `@Version` catching a
      genuine concurrent conflict.
      **Proves:** a full queue is rejected with `503`, not by hanging the request; an
      acquirer approval/decline/failure each drives the payment to the correct terminal
      status (`AUTHORIZED`/`DECLINED`/`FAILED`) without ever leaving it stuck in
      `PROCESSING`. Found and fixed during this stage: enqueueing before the creating
      transaction committed let a worker (fast enough on a virtual thread) query the row
      before it existed - fixed by committing first, enqueueing second, and marking the
      payment `FAILED` as a compensating step if the queue turns out to be full.
- [x] **Stage 4 - Capture / cancel / refund.** Sum invariants held under concurrency.
      Capture and cancel are mutually-exclusive one-shot actions - the loser of a
      `@Version` race gets an honest `409 Conflict`, not a silent retry that would
      decide the outcome for them. Refund is additive ("as many partial refunds as fit
      under the cap"), so it retries on a lost optimistic-lock race with a fresh re-read
      each attempt, up to a bounded number of attempts - both behaviours reuse the same
      guard/orchestrator split as `IdempotencyGuard` (stage 2), for the same
      self-invocation reason.
      **Proves:** 10 concurrent partial refunds of 20 against a 100 capture succeed
      exactly 5 times and never exceed the captured amount - the other 5 are correctly
      rejected, though as either `400` (some room was left, just not enough) or `409`
      (by the time of its retry the payment had already reached `REFUNDED`, so the
      status check fails before the amount check even runs) depending on exact timing;
      concurrent capture and cancel on the same payment - exactly one wins (`200`), the
      other gets `409`, final status is always one of the two, never anything else.
- [x] **Stage 5 - Outbox and webhooks.** Every merchant-relevant state transition writes
      an `outbox_events` row in the SAME transaction as the transition itself (reusing
      `PaymentProcessingService`/`PaymentTransitionGuard` from stages 3-4). A pool of
      virtual-thread pollers claims work via `FOR UPDATE SKIP LOCKED` with `DISTINCT ON
      (payment_id)` in the subquery - the same query shape both resolves multi-poller/
      multi-instance contention AND guarantees per-payment ordering for free (only the
      oldest pending event per payment is ever eligible). The claim step is a short,
      separate transaction from the actual HTTP delivery - never hold a DB transaction
      open across a slow network call, the same principle as `IdempotencyGuard`. Webhooks
      are HMAC-signed (`X-Payflow-Signature`); failed deliveries get exponential backoff
      and move to `DEAD_LETTER` after a bounded number of attempts instead of retrying
      forever.
      **Proves:** a signed webhook is delivered for each transition; events for the same
      payment are delivered in order even when an earlier one needs a retry (verified by
      forcing the first delivery to fail and confirming later events wait for it);
      exhausted retries land in `DEAD_LETTER`, not stuck `PENDING` forever. Found and
      fixed during this stage: `PaymentWorkerPool`/`OutboxPollerPool` are
      `SmartLifecycle` beans that Spring does not stop between test classes sharing a
      cached context - a lingering pool from an earlier test (different config, e.g.
      default `max-attempts`) could claim a row created by a later test and record an
      extra attempt against a threshold the later test never configured, an
      intermittent, hard-to-reproduce failure. Fixed with `@DirtiesContext` on the shared
      test base class, so every test class's background workers are guaranteed stopped
      before the next class starts - slower suite, but no cross-test interference.
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
