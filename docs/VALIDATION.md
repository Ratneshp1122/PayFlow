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

## Token-authenticated experiment

Commit `68f238f` passed all 12 unit/integration tests and the token-aware Docker smoke test in [CI run 42373868253](https://github.com/Ratneshp1122/PayFlow/actions/runs/42373868253). The smoke test also verifies that bearer tokens cannot renew themselves and merchant tokens cannot access operator endpoints.

[Benchmark run 42373872941](https://github.com/Ratneshp1122/PayFlow/actions/runs/42373872941) offered 100 commands/sec for 15 seconds, using a JWT obtained once in setup:

- 1,500 payment commands plus one token exchange completed; zero scheduled iterations were dropped.
- Payment-command mean latency: 5.76ms; p95: 15.96ms; **p99: 28.43ms**.
- The configured HTTP error and p99 thresholds passed for this short 100-RPS workload.
- Observed API restart availability was 6.86 seconds; observed payment success was 7.05 seconds. The <3s target was not met.
- The immediate post-restart snapshot showed zero pending provider operations and zero unbalanced journals, but 1 unpublished event and 545 pending webhooks. This is not a fully drained recovery measurement.

The authentication configurations differ, so the comparison demonstrates removal of repeated password-hashing overhead, not an isolated database speedup. This short experiment does not establish sustainable 1,200-RPS processing or delivery throughput. Raw results: `evidence/token-auth-100rps.json` and `evidence/token-auth-recovery.json`.

## Requested-rate trial, original 10-connection pool

[Run 42374014180](https://github.com/Ratneshp1122/PayFlow/actions/runs/42374014180), still at commit `68f238f`, offered 1,200 commands/sec for 15 seconds:

- 16,540 create requests plus one token exchange completed; no HTTP request failures were reported.
- k6 reported 1,096.36 total HTTP requests/sec over its measured runtime, and 1,461 scheduled iterations were dropped.
- Payment-acceptance mean latency was 507.92ms; p95 1,017.54ms; p99 **1,178.86ms**.
- The latency and no-dropped-iterations thresholds failed.
- API restart availability was 5.53 seconds; the observed payment became SUCCEEDED after 85.76 seconds with existing provider backlog.
- Final snapshot: 13 pending provider operations, 0 unpublished events, 13,189 pending webhooks, and 0 unbalanced journals. Full end-to-end recovery had not completed.

This trial explicitly does not support the requested 1,200-RPS/<35ms/<3s claim. Raw evidence is in `evidence/token-auth-1200rps-pool10.json` and the matching recovery file. The next experiment raises the configurable connection pool from 10 to 24 while retaining the same workload and correctness checks; it does not assume that more connections will improve performance.
