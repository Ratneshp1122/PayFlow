# Claim-to-evidence map

| Requested capability or claim | Implementation / evidence | Status |
|---|---|---|
| Idempotent payment and refund APIs | Payments.java; concurrent retry/refund integration tests | Implemented; test results are in CI |
| Transactional outbox and Kafka events | Workers.java, Webhooks.consume; Compose smoke drain | Implemented |
| Redis rate limiting and RBAC | RateLimit.java, Security.java, API ownership filters | Implemented; local role accounts |
| Immutable double-entry ledger | V1 SQL triggers plus atomic finalization | Implemented; privileged DBA can still change schema |
| Duplicate delivery and reordered callbacks | RecoveryTest + webhook inbox and consumer receipts | Implemented |
| Worker crash and ambiguous provider response | Durable simulator result, leases, fencing, recovery harness | Implemented; failure boundaries tested |
| Webhook failures | Bounded retries, delayed retry queue, duplicate suppression | Implemented; exhausted deliveries require operator action |
| 1,200+ RPS and p99 <35ms | k6 open arrival-rate workload | Target only, no benchmark success claimed |
| Recovery under 3 seconds | Process-failure harness | Target only; report restart, first completion, and backlog drain separately |

Build/test results do not establish performance results. GitHub Actions is a CI environment, not the user's local Docker benchmark machine. SRS proposals such as multiple real providers, OIDC, CDC, and generalized merchant onboarding are future work.
