# Executed validation

On 7 November 2026, [CI run 42373401207](https://github.com/Ratneshp1122/PayFlow/actions/runs/42373401207) passed on commit `11ec04f` with Java 21 and Docker on an Ubuntu GitHub-hosted runner:

- 3 pure domain tests passed.
- 9 real PostgreSQL recovery/integration tests passed.
- The Compose smoke test passed: HTTP authentication and RBAC, Redis admission control, idempotent retries, request conflicts, lost provider response, capture, partial refund, excess-refund rejection, Kafka outbox drain, signed webhook delivery, and ledger visibility.

## Initial performance experiment

[Benchmark run 42373517434](https://github.com/Ratneshp1122/PayFlow/actions/runs/42373517434) used the same commit and offered 100 create requests/sec for 15 seconds on a 4-vCPU AMD EPYC GitHub runner. HTTP Basic BCrypt verification ran on every request.

- 1,205 HTTP requests completed; k6's observed rate including completion time was approximately 60.63/sec.
- 296 scheduled iterations were dropped.
- Mean HTTP latency was 4,002.60ms; p95 was 7,276.02ms.
- The p99 <35ms threshold failed; this version of the summary did not export the numeric p99, so no numeric p99 is inferred.
- After a scoped app SIGKILL/restart, the API became reachable in 4.64 seconds and the observed payment was SUCCEEDED in 7.64 seconds. This is not proof that its capture was interrupted; the command could have completed before the kill.
- The immediate final snapshot had zero pending provider operations and no unbalanced journals, but still had 28 unpublished events and 46 pending webhooks. Thus it did not demonstrate fully drained recovery at that snapshot.

The experiment **did not meet the requested performance targets**. Raw summary and recovery records are in `evidence/`. A subsequent implementation adds short-lived bearer tokens to avoid repeating expensive password hashing while retaining authentication. Its results must be evaluated as a separate workload configuration.

These are CI-hosted measurements, not a local-laptop Docker benchmark. See the latest CI and benchmark runs for later commits.
