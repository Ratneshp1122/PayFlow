# PayFlow Engineering Guide

Software requirements specification, architecture, implementation roadmap, and performance engineering

Version 1.0 | 7 November 2026 | Prepared for Ratnesh P

## 1 Purpose and product boundaries

PayFlow is a payment-orchestration simulator that accepts payment and refund commands, coordinates an external provider simulator, records an append-only double-entry ledger, and delivers merchant notifications. It is designed to remain correct when clients retry, callbacks arrive twice or out of order, and workers fail at awkward moments.

This document specifies the broader target design. The repository README and CLAIMS.md describe the implemented subset, and VALIDATION.md links executed tests and benchmark evidence. The 1,200 RPS, p99 below 35 ms, and recovery below three seconds are benchmark hypotheses with explicitly different measurement boundaries. Proposed multi-provider and distributed deployment extensions below are roadmap items, not assertions of current implementation.

### Scope

Initial scope: authenticated merchant APIs, idempotent commands, asynchronous provider processing, transactionally coupled database changes and outbox records, duplicate-safe consumers, refund reservations, immutable journal entries, reconciliation, signed webhooks, rate limiting, observability, and failure tests.

The initial provider is a deterministic simulator. It does not move real funds. Use synthetic payment-method tokens; do not collect card numbers, CVVs, bank credentials, or personal customer data. Live payment acceptance, settlement, disputes, chargebacks, foreign exchange, tax, regulatory compliance, and custody are separate projects. This design is not a claim of payment-network certification or production readiness.

### Actors and primary use cases

- A merchant creates a payment with an amount, currency, order reference, and idempotency key.
- A provider worker submits the durable command and handles success, definitive failure, or an unknown outcome.
- A merchant reads payment status and requests partial or full refunds.
- A provider sends signed callbacks that may be duplicated or reordered.
- A notification worker delivers signed events to merchant endpoints and retries failures.
- An operator reconciles uncertain outcomes, inspects ledger invariants, and replays safe work without inventing new financial operations.

The minimum useful demonstration is one successful simulated capture, a duplicate create request, two racing refund requests, a worker crash after provider success, and final proof that the ledger and provider state agree.

## 2 Domain model and invariants

A payment is one merchant intent to capture a positive amount in a single currency. A provider operation is a durable command with a stable provider idempotency key. A refund is a separate business object associated with a succeeded payment. An event announces a committed state change; it is not permission to repeat the original financial operation blindly.

Use integer minor units in a signed 64-bit column with checked arithmetic and explicit maximum amounts. Currency definitions determine the minor-unit exponent; do not assume every currency has two decimal places. Never sum amounts across currencies. Validate amounts before casts, arithmetic, and JSON serialization.

### Core invariants

- PF-I01: One merchant-scoped idempotency key and operation scope maps to one canonical command payload and one resource result.
- PF-I02: Repeating a provider operation uses the same provider key until its outcome is reconciled; a timeout never automatically creates a new charge.
- PF-I03: Each confirmed capture or refund posts at most one ledger transaction for its business reference.
- PF-I04: Every posted ledger transaction has at least two entries and equal total debits and credits in one currency.
- PF-I05: Succeeded refunds plus pending or unknown refund reservations never exceed the captured payment amount.
- PF-I06: Payment state, ledger posting, and corresponding outbox events commit together when finalizing a successful operation.
- PF-I07: Only the owning merchant can read or mutate a resource; operator capabilities are explicit and audited.
- PF-I08: A merchant notification failure cannot roll back or repeat a payment capture.
- PF-I09: Unknown provider outcomes retain their reservations and enter reconciliation rather than being treated as definitive failures.
- PF-I10: Posted journals are append-only to application roles; corrections use linked reversal journals rather than edits.

Idempotency is scoped, not unlimited. For this project keep payment/refund command keys for the lifetime of their business records. If a later retention policy removes keys, document that old retries may no longer be recognized. Rate-limit caches do not define financial correctness.

## 3 Software requirements specification

| ID | Priority | Requirement and acceptance criterion | Test |
|---|---|---|---|
| PF-F01 | P0 | Authenticate merchant requests and enforce resource ownership on every API and query. | PF-T01 |
| PF-F02 | P0 | Create one payment resource for concurrent identical idempotent requests; reject changed payloads under the same key. | PF-T02 |
| PF-F03 | P0 | Return 202 only after the command, payment, stored response, and outbox record commit. | PF-T03 |
| PF-F04 | P0 | Persist stable provider-operation keys and recover ambiguous outcomes without a second capture. | PF-T04 |
| PF-F05 | P0 | Finalize state, ledger, and event atomically with duplicate posting prevention. | PF-T05 |
| PF-F06 | P0 | Serialize refund reservations against the payment and reject amounts exceeding available refundable funds. | PF-T06 |
| PF-F07 | P0 | Authenticate callbacks, durably record them, and suppress repeated effects. | PF-T07 |
| PF-F08 | P0 | Relay outbox records and consume events with crash-safe deduplication. | PF-T08 |
| PF-F09 | P1 | Retry merchant notifications with bounded backoff and durable delivery state. | PF-T09 |
| PF-F10 | P1 | Reconcile UNKNOWN operations and provider/database mismatches through an auditable workflow. | PF-T10 |
| PF-F11 | P1 | Apply merchant rate limits without making Redis the source of payment state. | PF-T11 |
| PF-F12 | P1 | Export ledger and operational reconciliation results for a bounded merchant and time range. | PF-T12 |
| PF-F13 | P2 | Support configurable provider routing while preserving business and operation identities. | PF-T13 |

### Nonfunctional requirements

PF-N01 correctness: all invariants hold under the finite test suite and every release benchmark. A throughput result with a duplicate journal or excess refund fails acceptance regardless of latency.

PF-N02 latency: measure API command acceptance separately from provider confirmation and merchant notification. The initial local objective is p95 command acceptance below 100 ms at a calibrated offered rate; the stretch target is p99 below 35 ms at 1,200 new commands/second in a declared lab. Neither is an achieved result in this guide.

PF-N03 durability: a committed local PostgreSQL transaction is the acceptance boundary. Host/disk failure tolerance depends on database replication and storage configuration. A one-node demo cannot claim high availability.

PF-N04 recovery: report time to healthy worker, time to first successful post-failure operation, time to reconcile all UNKNOWN operations, and time to drain backlog. The under-three-second hypothesis applies only to a named small-backlog process-failure scenario, not full disaster recovery.

PF-N05 security: protect credentials, verify webhook signatures, reject cross-tenant access, and prevent sensitive payloads from entering logs. Keep administrative and merchant endpoints distinct.

PF-N06 maintainability: domain logic is independent of Kafka and HTTP. All database mutations use reviewed transactions and versioned migrations. CI runs deterministic domain, real-PostgreSQL, and contract tests.

## 4 Architecture and runtime profiles

[[ARCHITECTURE]]

Start as a modular monolith with background workers. Modules share one PostgreSQL database and explicit transaction boundaries. Kafka decouples delivery but does not make the database and provider one atomic system. Redis is an optional admission-control service. A provider simulator models failures without real transactions.

### Modules

- api: HTTP DTOs, authentication, authorization, validation, and stable error responses.
- payment-domain: payment state transitions, amount/currency rules, and deterministic decisions.
- refund-domain: reservations, refundable balances, refund state, and concurrency rules.
- ledger: balanced posting commands, unique business references, reversal handling, and invariant queries.
- persistence: repositories, SQL, migrations, transaction boundaries, and optimistic version fields.
- outbox: committed-event selection, publication, acknowledgement marking, and retry accounting.
- provider-adapter: simulator requests, stable keys, timeouts, status lookup, and authoritative-state reconciliation.
- workers: durable operation claims and background execution; no long database transaction across a network call.
- webhook-inbox: signature checks, durable inbound receipts, and asynchronous processing.
- webhook-delivery: merchant endpoint delivery, signatures, retry scheduling, and dead-letter inspection.
- reconciliation: scheduled provider/database/ledger comparison and reviewed repair commands.
- observability: metrics, traces, redacted logs, health, and run identifiers.

### Version and deployment baseline

Use Java 21, Maven Wrapper, Spring Boot 3.5.x, PostgreSQL 17, and a compatible Kafka client managed by Spring Boot. Spring Boot 3.5's versioned documentation supports Java 17 and later within its declared range. [P1] Choose and pin a supported patch release and container digest at implementation time. Kafka 4.1 is a reference broker candidate; verify client integration. Redis 7.x is optional for the first implementation. Avoid overriding transitive library versions without a specific compatibility test.

Profile A, laptop without Docker/WSL: compile the domain, simulator, and unit tests. An in-memory adapter demonstrates workflows but cannot establish database locking or persistence correctness. Add native PostgreSQL only if it is available and isolated from other projects.

Profile B, 8GB integration: PostgreSQL plus one Java application and the simulator. Use a database-backed worker queue first, retaining an outbox abstraction. Add one Kafka broker only after the transaction tests pass. Start with application heap 512-768 MB, broker heap 512 MB, PostgreSQL budget approximately 512 MB, and small load-generator concurrency. These are starting budgets; measure actual resident memory and avoid swapping. Run Redis and dashboards only when their behavior is under test.

Profile C, resilience/benchmark lab: separate generator from the application, multiple application workers, protected PostgreSQL, three Kafka brokers with replication 3/minimum ISR 2, dedicated provider simulator, and recorded disk/network resources. Adding replicas without testing failover does not establish resilience.

## 5 API and event contracts

### Payment creation

```http
POST /v1/payments
Authorization: Bearer <merchant token>
Idempotency-Key: 2cb5a7a3-87bc-4a56-98f0-5895cdf39d33
Content-Type: application/json

{
  "merchant_order_id": "order_1042",
  "amount_minor": 25000,
  "currency": "INR",
  "payment_method_token": "sim_success"
}
```

The initial response is 202 Accepted with payment_id, status=CREATED, amount_minor, currency, created_at, and a Location header. It means the command is durable, not that funds have been captured. The merchant identity comes from authentication, not a trusted body field.

The request hash covers method, operation scope, normalized business fields, and schema version. Authentication tokens, trace IDs, and header ordering do not affect it. Repeating the same key and payload returns the stored initial status/body and the same resource ID even after the payment completes; GET returns its current state. A changed payload returns 409 IDEMPOTENCY_CONFLICT. This is PayFlow's chosen contract; it is not a claim to reproduce every provider's idempotency semantics. Provider documentation illustrates why safe retries require explicit keys and response semantics. [P2]

| Endpoint | Purpose | Important behavior |
|---|---|---|
| POST /v1/payments | Create durable capture intent | Idempotency key required; 202 after commit |
| GET /v1/payments/{id} | Read current payment | Merchant-scoped; includes captured/refunded/reserved totals |
| GET /v1/payments | List merchant payments | Stable cursor on created_at and ID; bounded limit |
| POST /v1/payments/{id}/refunds | Reserve and request refund | Key scoped to merchant and payment; 202 or 409 excess amount |
| GET /v1/refunds/{id} | Read refund | PENDING, UNKNOWN, SUCCEEDED, or FAILED |
| POST /v1/provider-webhooks/{provider} | Receive provider observations | Verify raw-body signature, commit inbox, then 200 |
| POST /v1/webhook-endpoints | Register merchant callback | Merchant admin only; URL policy and secret lifecycle |
| GET /v1/ledger/transactions | Read authorized journals | Currency and time filters; no mutation route |
| POST /v1/ops/reconciliations | Request bounded reconciliation | Operator-only, reason required, returns run ID |
| GET /v1/ops/reconciliations/{id} | Inspect results | Mismatches, actions, unresolved cases, audit trail |

Common errors: 400 invalid input, 401 invalid credential, 403 insufficient permission, 404 nonexistent or inaccessible resource where disclosure would leak ownership, 409 conflict, 413 oversized request, 429 admission limit, 503 dependency unavailable. Provide stable error code, safe message, trace_id, and retryable. A failed pre-transaction validation does not create a permanent idempotency record.

### Event envelope

```json
{
  "event_id": "a3bd4ea0-d8e4-4f9e-8070-044523a2c901",
  "schema_version": 1,
  "event_type": "payment.succeeded",
  "merchant_id": "merchant_demo",
  "aggregate_id": "payment_1042",
  "aggregate_version": 3,
  "occurred_at": "2026-11-07T10:00:05Z",
  "data": {"amount_minor": 25000, "currency": "INR"}
}
```

Use pf.payment-events.v1 and pf.refund-events.v1, keyed by payment ID for related payment/refund ordering. Keep outbox IDs stable on publication retries. Consumers tolerate gaps and duplicates using event IDs and aggregate versions; Kafka order is not a substitute for a valid state transition. Do not put payment-method credentials or raw provider payloads into public lifecycle events.

## 6 State machines and database design

### Payment states

CREATED means a durable command awaits work. PROCESSING means a worker has begun a provider operation. UNKNOWN means the provider outcome cannot yet be established. SUCCEEDED means an authoritative capture confirmation has been applied with its ledger transaction. FAILED means the provider definitively declined or rejected the capture with no capture side effect.

Allowed paths: CREATED to PROCESSING; PROCESSING to SUCCEEDED, FAILED, or UNKNOWN; UNKNOWN to SUCCEEDED or FAILED after authoritative lookup. Retrying an UNKNOWN operation uses the same stored operation/key; it need not change the business state back to CREATED. Network errors do not justify FAILED. SUCCEEDED is not changed to FAILED by an older callback. A contradictory authoritative result creates a reconciliation incident; a human-approved correction uses a defined reversal workflow.

Refunds use PENDING, UNKNOWN, SUCCEEDED, FAILED. Reserving a refund creates PENDING. A timed-out request remains UNKNOWN with its reservation held. Definitive rejection releases the reservation. Success moves the reserved amount into refunded_amount and posts the refund journal. Partial and full refund display statuses are derived from totals rather than overwriting the capture's historical success state.

### Logical relations and constraints

| Relation | Key or uniqueness | Essential fields and rules |
|---|---|---|
| merchant | merchant_id | active flag, credential reference, limits |
| payment | payment_id; merchant/order business rule if selected | merchant, amount, currency, state, refunded, reserved, version |
| refund | refund_id | payment, merchant, amount, state, provider operation |
| idempotency_record | merchant, operation_scope, key | canonical hash, resource ID, stored status/body |
| provider_operation | operation_id; provider/idempotency_key | resource, type, state, lease owner/token, lease expiry, attempts |
| ledger_account | account_id; merchant/currency/type | immutable currency, account classification |
| ledger_transaction | transaction_id; business_type/business_id | currency, posting status, reversal reference |
| ledger_entry | transaction_id, line_number | account, side, positive amount_minor |
| outbox_event | event_id; aggregate/version/type | payload, next_attempt_at, publication state |
| consumer_receipt | consumer_name, event_id | processed_at; stored in effect transaction |
| webhook_inbox | provider, provider_event_id | raw hash, verified timestamp, processing state |
| webhook_delivery | endpoint_id, event_id | attempts, next_attempt_at, response class, state |
| reconciliation_run | run_id | range, source versions, counts, unresolved cases |

Payment checks enforce amount_minor > 0, refunded_amount >= 0, reserved_amount >= 0, and refunded_amount + reserved_amount <= amount_minor. Use composite foreign keys or equivalent transaction checks to ensure a refund belongs to the same merchant as its payment. Monetary account currency must match the journal currency; enforce this in the posting path and database constraints/triggers where feasible.

### Ledger semantics

This simulator models a provider-clearing asset and merchant-payable liability. On a confirmed capture of INR 250.00, debit provider clearing 25,000 minor units and credit merchant payable 25,000. On a confirmed INR 100.00 refund, debit merchant payable 10,000 and credit provider clearing 10,000. Fees and settlement are excluded until their accounting model is explicitly designed.

The journal header has a unique business reference such as (CAPTURE, payment_id) or (REFUND, refund_id). Insert the header and entries in the same finalization transaction. Repeated finalization finds the existing business reference and verifies consistency instead of posting again.

A CHECK constraint on one entry cannot prove the sum of all entries is balanced. Use a database posting function that validates the complete entry set and a deferred constraint trigger validating the final transaction at commit, including at least two lines, valid account currencies, and equal debit/credit totals. Revoke direct ledger mutation from application roles; expose only the posting function. Also prevent updates/deletes to POSTED headers and entries. A superuser can still alter a database; append-only application permissions are not cryptographic tamper proofing.

Suggested invariant query:

```sql
SELECT transaction_id
FROM ledger_entry
GROUP BY transaction_id
HAVING count(*) < 2
   OR sum(CASE WHEN side = 'DEBIT'
               THEN amount_minor ELSE -amount_minor END) <> 0;
```

Also query posted headers with no entries, account/journal currency mismatches, duplicate business references, and refunded plus reserved totals above captured amount. The example assumes validated DEBIT/CREDIT values and is only one check in the full suite.

## 7 Transaction protocols and concurrency

### Creating an idempotent payment

1. Authenticate, authorize, validate, canonicalize, and hash the request before opening the write transaction.
2. Begin a short transaction and insert the idempotency record under its unique composite key using conflict-aware SQL.
3. If the key already exists, fetch the stored hash and response after the conflicting transaction settles. Different hash yields 409. Identical hash returns the saved response. Set a bounded lock/statement timeout; do not block forever.
4. For a new key, insert payment, provider operation, and outbox event. Save the initial response and resource ID in the idempotency row.
5. Commit, then return 202. If the process dies after commit but before the response reaches the client, the next request retrieves the same result.

Keep creation and stored response in one transaction so a visible committed record is never half-filled. Do not catch a unique-constraint exception and continue in an already aborted PostgreSQL transaction; use ON CONFLICT or a new transaction/savepoint intentionally.

### Provider operation worker

Claim due operations with a short transaction, lease expiry, and monotonically increasing fencing token. Commit the claim before calling the provider. Send a stable operation-specific idempotency key. On definitive success/failure, finalize in a new transaction after verifying the current lease/token and resource version. On timeout, record UNKNOWN and schedule lookup. Do not hold row locks across external HTTP calls.

Leases alone do not prevent duplicate external calls: a paused old worker can resume after its lease expires. Provider-side idempotency is therefore mandatory for the simulator contract. A stale worker cannot finalize under an old token. If a real provider lacks stable idempotency or queryable operations, stronger reconciliation/manual controls are necessary and the advertised semantics must change.

The simulator persists key-to-operation results, supports status lookup, and can deliberately perform a successful operation before dropping the response. Merely using a random in-memory success/failure mock cannot test the most important failure mode.

### Concurrent refunds

Begin by resolving the idempotency key. Then SELECT the payment FOR UPDATE, verify SUCCEEDED and currency, and compute available_refund = amount_minor - refunded_amount - reserved_amount. Reserve only if the requested amount fits. Insert refund, provider operation, and outbox event, increase reserved_amount, save the initial response, and commit together.

On successful refund confirmation, lock the payment and refund in a consistent order, verify no previous finalization, subtract the reservation, add refunded_amount, post the unique refund journal, and insert its outbox event atomically. On definitive failure release the reservation. An UNKNOWN refund keeps it. Apply this same locking order in callbacks and reconciliation. PostgreSQL row locking is the serialization mechanism in this design. [P3]

Example: a payment has 25,000 captured and no refunds. Two simultaneous requests for 20,000 each cannot both reserve: the first holds the payment lock and reserves 20,000; the second sees only 5,000 available and receives a conflict. Two different keys are not permission to exceed the financial invariant.

### Outbox publication

The payment transaction inserts an outbox row but does not synchronously call Kafka. A poller claims due rows using a bounded query with FOR UPDATE SKIP LOCKED, commits a publication lease, sends to Kafka, then marks successful publication under that lease. A crash after Kafka acknowledgement and before marking can cause another publish. Every consumer must therefore deduplicate event IDs in the same database transaction as its effect.

SKIP LOCKED is appropriate for distributing queue work, not for pretending a query saw a consistent full business dataset. [P4] Include lease expiration and attempts in the schema; a simple permanent processing flag strands work after a crash. Publication order across pollers can differ from aggregate order. A consumer must use aggregate versions or reload current state; if strict aggregate order is required, serialize claims for that aggregate and account for head-of-line blocking.

A later CDC relay can replace polling with Debezium's outbox router. Its aggregate key is relevant to partition routing. [P5] CDC adds connector deployment, replication-slot/WAL retention, and recovery concerns; it does not eliminate consumer deduplication or external-provider uncertainty.

### Inbound and outbound webhooks

Verify provider signatures on the exact raw request bytes and timestamp using the provider's documented protocol. Enforce body limits and a bounded replay window. Persist a verified inbox record before returning 200; if persistence fails return a retryable error. A duplicate provider event ID with the same hash is acknowledged without repeating effects; a changed hash is flagged. Distinct event IDs describing the same capture are still stopped by resource transitions and unique business journal references.

Callbacks can be out of order. Resolve an observation against current state and, when uncertain, query authoritative provider status. Provider documentation explicitly warns about duplicates and delivery ordering. [P6] Do not downgrade a completed payment because an earlier processing event arrived later.

Merchant webhook delivery is asynchronous. Sign event ID, timestamp, and body using a versioned endpoint secret. Use exponential backoff with jitter and a maximum retry horizon; distinguish 2xx success, 429/transient 5xx retry, and permanent endpoint errors. Preserve one delivery row per endpoint/event. Recipients must deduplicate event IDs. Secret rotation permits a short overlap and records the signing key version.

## 8 Security design

Use Spring Security with verified issuer/audience and algorithm allowlists for JWTs if JWT authentication is selected. Enforce merchant ownership in repository predicates, not just in controller annotations. Role suggestions are MERCHANT_READ, MERCHANT_WRITE, MERCHANT_ADMIN, and OPERATOR. Provider callbacks use their own signature authentication rather than merchant bearer tokens.

Outbound merchant URLs are an SSRF boundary. In deployment, require HTTPS, validate hostname and port, reject loopback/private/link-local/metadata addresses, control DNS rebinding, revalidate redirects or disable them, and restrict outbound networking. For a local simulator, use a fixed allowlist of test endpoints rather than allowing arbitrary URLs. Log only URL identifiers and safe response metadata.

Restrict database roles by module, keep provider and webhook secrets outside source control, redact payment-method tokens, and limit request size. Expose health/metrics on a restricted interface. Audit manual reconciliation and reversals with operator identity and reason. Test access denial and signature failures as normal application tests.

Redis rate limits use atomic token-bucket or sliding-window operations with bounded keys and expiry. Redis failure behavior must be selected explicitly: local demo can degrade to a conservative in-process limiter, while a higher-risk deployment may reject new writes temporarily. Either choice leaves financial idempotency and balances in PostgreSQL. Never use a cache lock as the only protection against duplicate refunds.

## 9 Build roadmap and repository layout

```text
payflow/
  app/                 Spring Boot entry point and wiring
  domain/              payment/refund decisions and money types
  api/                 controllers, DTOs, error contracts
  persistence/         JDBC/JPA repositories and migrations
  ledger/              posting functions and invariant checks
  outbox/              publisher and consumer receipts
  providers/           durable simulator and adapter contracts
  workers/             leased operation processing
  webhooks/            inbound inbox and merchant delivery
  reconciliation/      bounded comparison and repair jobs
  tests/               concurrency and crash scenarios
  deploy/              local and resilient profiles
  benchmarks/          k6 scenarios and result manifests
  docs/                SRS, decisions, runbooks, diagrams
```

PF-M1: implement Money, currencies, state transitions, and a pure refund decision function. Add property tests for amount bounds, terminal-state behavior, and reversal symmetry. Exit when the domain can be exercised without HTTP or Kafka.

PF-M2: create PostgreSQL migrations, merchant authorization, and idempotent payment/refund APIs. Implement ledger posting enforcement. Run concurrent requests against real PostgreSQL; an in-memory SQL substitute does not establish PostgreSQL locking behavior.

PF-M3: implement a durable simulator with configurable response delays, duplicate callbacks, definitive decline, and success-with-lost-response. Add operation leases and UNKNOWN reconciliation. Exit when PF-T04 passes repeatedly with no duplicate capture.

PF-M4: add polling outbox and duplicate-safe consumers, first against a database queue and then Kafka. Inject crashes before/after publication and consumption. Keep provider calls outside consumer database transactions.

PF-M5: add authenticated inbox processing, merchant webhook retries, rate limits, dashboards, and bounded operator reconciliation. Demonstrate correct behavior while Redis or a merchant endpoint is offline.

PF-M6: publish a fresh-clone setup, test reports, benchmark scripts, and demonstration recordings. Tune after collecting a baseline, preserving the correctness assertions throughout.

Proposed commands to implement as part of the project:

```bash
./mvnw test
./mvnw verify -Pintegration
docker compose --profile payflow up -d
./mvnw -pl app spring-boot:run
k6 run benchmarks/create-payment.js
./scripts/assert-ledger.sh --run-id RUN_ID
./scripts/reconcile-provider.sh --run-id RUN_ID
```

These are planned module/script interfaces, not an assertion that this documentation package contains the application or runnable infrastructure. On native Windows use mvnw.cmd; shell scripts assume WSL/Linux. Keep Maven Wrapper, full dependency versions, image digests, migration checksums, and simulator parameters under version control.

## 10 Performance and efficiency

Begin with an acceptance-path trace: authenticate, validate/hash, obtain a database connection, create command records, commit, serialize response. Measure pool wait separately from SQL execution. Avoid synchronous provider or Kafka calls on this path. Request acceptance can be fast while end-to-end completion is slow, so both must be reported.

| Bottleneck | Improvement experiment | Guard |
|---|---|---|
| Connection-pool waits | Reduce transaction scope and tune bounded pool size | Total pools across replicas must fit database budget |
| Slow point queries | Composite indexes on merchant and resource IDs | Verify plans on realistic cardinalities |
| Outbox scans | Partial due-row indexes and bounded claim batches | Retain expiry/reclaim semantics |
| Hot payment refund lock | Keep reservation transaction short | Do not bypass serialization for higher RPS |
| Excess ORM work | Explicit SQL for critical transactions; avoid N+1 reads | Keep constraints and atomicity identical |
| Provider saturation | Bounded worker concurrency and circuit breaker | UNKNOWN outcomes retain stable keys/reservations |
| Kafka overhead | Batch/compress event publication | Never mark published before acknowledgement |
| Webhook backlog | Separate notification worker pool and retries | Notifications never block ledger finalization |
| Event table growth | Partition/archive after measured need | Preserve uniqueness and reconciliation retention |

Start with a small pool, for example 5-10 connections for one local app, then measure. More threads or virtual threads do not create more database or provider capacity. Do not reduce fsync/durability settings merely to improve a headline result. If a deliberately relaxed-durability experiment is run, report it as a separate configuration.

Cache only safely stale reads or derived views. Do not serve a refundable balance from Redis for a write decision. Maintain ledger account balances as a transactional projection only after identifying a real query bottleneck; keep journals authoritative and regularly reconcile the projection.

Optimizing costs: avoid Kubernetes, service meshes, multiple databases, and a schema-registry service in the first laptop profile. Separate modules and contracts now so a measured bottleneck can later justify deployment separation. A modular monolith with correct transactions is a stronger starting point than several services sharing undocumented assumptions.

## 11 Benchmark contract and reporting

Define four latency distributions: HTTP command acceptance, provider-operation completion, ledger visibility, and merchant notification delivery. Use independent labels for create, refund, read, duplicate retry, and error responses. Include timeouts and rejected requests in reporting rather than calculating latency only over the fastest successes.

Use an open arrival-rate workload when evaluating sustainable RPS so the generator attempts the scheduled arrivals even if responses slow. Record dropped or unscheduled iterations; otherwise an overloaded generator can conceal the requested load. Grafana k6 explains the distinction between arrival-driven and response-paced workloads. [P7]

Scenarios: correctness smoke with 100 payments; 10,000 unique create requests; repeated same-key contention; mixed workload with 60% creates, 25% reads, 10% refunds, and 5% duplicate retries; hot-merchant skew; sustained steady load; provider degradation; worker kill; broker outage; Redis outage. The mixed proportions are proposed parameters, not representative production facts.

Ramp offered rate gradually after warmup, hold each step long enough to expose backlog, then drain. At least three comparable runs are required for an optimization claim. Record generator saturation, application errors, outbox depth, unresolved operations, provider outstanding requests, and database lock/pool waits. A service that accepts 1,200 RPS while the unprocessed queue grows forever has not demonstrated sustainable completion at 1,200 payments/second.

At 1,200 accepted commands/second, ten minutes contains 720,000 commands and thirty minutes 2.16 million. A 35 ms average latency at 1,200/second would imply about 42 in-flight requests by Little's law under steady conditions. That calculation uses the average, not p99; a p99 target alone cannot determine required concurrency.

Define a 35 ms p99 experiment precisely: new unique create requests, authentication enabled, PostgreSQL durability enabled, measured at the generator through HTTP 202 after commit, specified payload size, and provider work excluded from the response. Report provider-confirmation latency alongside it. Do not compare that number directly with an API that waits for provider capture.

Recovery trial: record fault time, restart-ready time, first successful post-fault completion, last pre-fault unresolved operation reconciled, and time backlog reaches the pre-fault baseline. Report data integrity after recovery. A process restarting in two seconds while operations remain UNKNOWN for a minute does not have two-second business recovery.

Every result bundle includes git commit, full dependency lock, OS/CPU/RAM/disk, network placement, database settings, broker replication, pool sizes, provider delays, worker counts, workload seed, payload sizes, histogram exports, errors, invariant-query outputs, provider reconciliation, and raw time series. State whether the generator shares the laptop with the service.

## 12 Verification and requirement traceability

| Test | Scenario | Required result |
|---|---|---|
| PF-T01 | Tenant B reads/refunds tenant A's resource | Denied without leaking private details |
| PF-T02 | 100 simultaneous same-key creates; one changed body | One resource and one stored response; conflict on changed body |
| PF-T03 | Crash just before/after command transaction commit | No partial accepted command; safe retry after lost response |
| PF-T04 | Simulator captures then drops response; worker restarts | Same provider key, one capture, UNKNOWN reconciles |
| PF-T05 | Crash between journal entries and state update | Entire finalization rolls back or commits consistently |
| PF-T06 | Two 80% refunds race against one captured payment | At most one reservation succeeds; totals stay bounded |
| PF-T07 | Duplicate, forged, stale, reordered callbacks | Invalid rejected; valid duplicates cause no repeated journal |
| PF-T08 | Crash after Kafka ack but before outbox mark | Duplicate event delivery is harmless to every consumer |
| PF-T09 | Merchant endpoint fails repeatedly then recovers | Payment unchanged; one logical event eventually acknowledged |
| PF-T10 | Provider and database disagree or outcome unknown | Incident or authoritative reconciliation with audit trail |
| PF-T11 | Redis unavailable and hot-merchant bursts | Documented admission behavior; no balance corruption |
| PF-T12 | Inject an unbalanced or zero-entry posting attempt | Database posting guard rejects it; reconciliation detects gaps |
| PF-T13 | Provider route changes during an existing operation | Existing operation retains route/key until resolved |

Add tests for lease expiry with a still-running old worker, duplicate provider callbacks with different event IDs, deadlocks, lock timeout, integer overflow, currency mismatch, refunded counters versus journals, and secret rotation. Run a long test where callbacks race with direct provider responses. Check both state and journal outcomes after all work drains.

Use JUnit for domain tests, a property-testing library for invariants, Testcontainers when Docker is available, and WireMock or the durable custom simulator for network failures. The real database is required for concurrency tests. CI can run a lightweight domain stage first, then database/contract stages, then an optional scheduled fault suite. Store reports per commit.

Release acceptance: all P0 requirements pass, no ledger imbalance or duplicate business posting appears, no excess refund is possible in the race suite, UNKNOWN outcomes converge or are explicitly unresolved incidents, a fresh clone can reproduce the demo, and performance results include correctness checks. Coverage percentage alone is not an acceptance gate.

## 13 Operations and failure runbooks

If outbox age rises, inspect database due-row scans, publication leases, broker reachability, and acknowledgement errors. Expired claims are reclaimable. Never delete rows to clear the dashboard without reconciling their events.

If UNKNOWN operations rise, stop routing new work to the unhealthy provider when appropriate, retain reservations, and query using the stored provider operation key. Do not issue a new key merely to make a request succeed. Escalate unreconcilable operations as incidents.

If a ledger mismatch appears, stop the affected write path, preserve evidence, compare provider operations and business references, identify the faulty transition, and apply a reviewed corrective journal. Editing historical entries hides the cause and invalidates auditability.

If the database is unavailable, new command acceptance returns a retryable failure. Already accepted commands remain in durable storage and recover when the database returns. Webhooks return retryable errors unless they were durably recorded. If Kafka is unavailable, commands may continue only within a measured outbox/disk backlog limit; admission control must eventually protect storage.

Use separate liveness and readiness probes. Liveness should not kill a healthy process merely because a provider is slow. Readiness reflects the service's ability to safely accept its assigned work. Expose oldest outbox age, oldest UNKNOWN age, refund reservations, provider failures, journal invariant failures, and per-stage completion rates.

Back up PostgreSQL and test restores. Preserve idempotency records, operation keys, inbox receipts, ledger, and outbox together. Restoring only the ledger or only payments creates inconsistency. Protect retained provider keys from accidental expiration. Recovery from a database restore requires provider reconciliation before accepting conflicting work.

## 14 Architecture decisions and improvement backlog

ADR-PF01: PostgreSQL is the initial business source of truth. Redis and Kafka are not balance stores. Consequence: clear transaction boundaries and a database capacity limit to measure.

ADR-PF02: asynchronous API acceptance. Consequence: explicit pending states and polling/webhooks; response latency is separate from capture latency.

ADR-PF03: provider idempotency plus UNKNOWN reconciliation. Consequence: some operations remain pending during outages, but retries do not create intentional new financial commands.

ADR-PF04: refund reservations under a payment row lock. Consequence: per-payment contention is acceptable to protect refund limits.

ADR-PF05: append-only balanced ledger with unique business posting references. Consequence: more schema and enforcement work, but auditable corrections and duplicate protection.

ADR-PF06: polling outbox before CDC. Consequence: simpler local setup; publication lag and polling overhead are measured and can later justify Debezium.

Improvement order: enforce transaction invariants; test ambiguous provider outcomes; add webhooks and reconciliation; profile pool and database contention; optimize indexes and batching; add CDC or independent workers only when justified; then investigate provider routing, settlement, disputes, and resilient deployment as separate scoped extensions.

For interviews, explain the exact crash window after provider success but before database commit, why Redis locks are insufficient, why database transactions cannot roll back an external capture, and how two partial refunds are serialized safely. Demonstrate these with tests, not only diagrams.

## References

[P1] Spring Boot 3.5 system requirements. https://docs.spring.io/spring-boot/3.5/system-requirements.html

[P2] Stripe idempotent request semantics. https://docs.stripe.com/api/idempotent_requests

[P3] PostgreSQL 17 explicit locking and deadlocks. https://www.postgresql.org/docs/17/explicit-locking.html

[P4] PostgreSQL 17 SELECT and SKIP LOCKED. https://www.postgresql.org/docs/17/sql-select.html

[P5] Debezium Outbox Event Router. https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html

[P6] Stripe webhook delivery, signatures, and duplicate/ordering behavior. https://docs.stripe.com/webhooks

[P7] Grafana k6 open and closed workload models. https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/

Sources consulted 7 November 2026. The requirements, simulator semantics, ledger model, resource budgets, and workload targets are project design decisions; source documentation does not establish achieved PayFlow performance.
