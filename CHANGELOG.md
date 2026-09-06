# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-09-06

First complete cut of the gateway: every capability below is exercised by a
Testcontainers test against a real Postgres in CI.

### Added

- **Payments API** - `POST /v1/payments`, `GET /v1/payments/{id}`, and
  `/capture`, `/cancel`, `/refunds` on a payment. Merchant authentication with a
  hashed API key. OpenAPI 3 spec and Swagger UI at `/swagger-ui.html`.
- **Idempotency** - `Idempotency-Key` header; a unique-constraint race decides
  the single winner, and every caller with the same key gets byte-identical
  response. Pool exhaustion answers `503`/`409` with `Retry-After`, never a
  misleading `401`.
- **Payment state machine** - transitions are guarded named methods on the
  entity; an optimistic-lock `version` column makes two concurrent transitions
  on one row impossible to both succeed silently.
- **Asynchronous processing** - a bounded in-memory queue drained by a fixed
  pool of virtual-thread workers that call the acquirer over real HTTP. A full
  queue answers `503` instead of blocking the request thread.
- **Capture / cancel / refund** - capture and cancel are mutually exclusive
  one-shot actions (loser of the race gets `409`); refunds are additive up to
  the captured amount, with bounded optimistic-lock retries.
- **Transactional outbox + webhooks** - every merchant-relevant transition
  writes an outbox row in the same transaction. A pool of virtual-thread pollers
  delivers HMAC-signed webhooks with `FOR UPDATE SKIP LOCKED` + `DISTINCT ON`
  for per-payment ordering, exponential backoff, and `DEAD_LETTER` after a
  bounded number of attempts.
- **Reconciliation + resilience** - a read timeout or `5xx` from the acquirer
  moves the payment to `NEEDS_RECONCILIATION`; a sweeper resolves it by *asking*
  the acquirer (which is idempotent) rather than re-charging. A hand-rolled
  circuit breaker short-circuits the acquirer while it is down.
- **Observability** - Micrometer domain metrics, `/actuator/prometheus`, and a
  pre-provisioned Grafana dashboard (`docker compose --profile monitoring up`).
- **Benchmark** - a dependency-free load generator comparing platform-thread and
  virtual-thread throughput under the same closed-loop workload
  (`ops/benchmark/run-benchmark.sh`).
- **Deployment** - multi-stage non-root Docker images published to GHCR on every
  push to `main`; `./run.sh` for a one-command build-and-demo, and
  `docker-compose.prod.yml` to run from the pre-built images.

[1.0.0]: https://github.com/VitaliyProgrammer/payment-gateway/releases/tag/v1.0.0
