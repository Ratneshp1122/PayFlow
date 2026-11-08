# Profiling interpretation

The November 8 experiments preserve the offered workload, failed latency gates, commit SHA, machine inventory, raw k6 summaries, SQL snapshots, Micrometer snapshots, and JFR events in `benchmarks/runs`. No laptop extrapolation is measured evidence.

## Findings that drove changes

1. Serial-worker local run `42456894463`: at 300 RPS, pool acquisition averaged 0.025–0.054 ms and SQL execution 0.145–0.448 ms, but end snapshots contained 1322–2448 pending operations and 1330–2054 pending webhooks. Connection-pool expansion was not justified by those averages. Averages do not rule out individual tail stalls.
2. Bounded-worker run `42457755091`: four provider and four webhook tasks plus batched outbox acknowledgements eliminated the end-snapshot backlog in the two warmed trials. The first trial still accumulated work. Acceptance p99 was not improved: 91.47 / 16.42 / 14.31 ms versus 88.76 / 12.91 / 10.28 ms before. The change improved completion capacity, not a proven reduction in acceptance latency.
3. In optimized trial 3, PostgreSQL recorded 1288 ms cumulative execution for 1298 webhook claim calls, compared with 764 ms for 4501 ledger-entry inserts and 622 ms for 1351 operation claims. These are aggregate statement times, not a per-request latency decomposition. Unlike operation/outbox claims, webhook claims lacked a partial due-time index. Migration V2 adds that index without changing delivery semantics.
4. The same trial's JFR CPU-load samples averaged approximately 0.094 JVM user, 0.051 JVM system, and 0.420 machine total (fractions reported by JFR). Java execution samples were too sparse to identify a reliable dominant CPU hotspot. Native waiting stacks must not be presented as CPU consumption. Future summaries separate event types; raw earlier events remain available.

## Measurement boundaries

- Pool timer includes `getConnection`; SQL timer wraps statement execution but excludes acquisition, commit and result iteration. Commit has its own timer. Samples include API and background work.
- Profiling and `pg_stat_statements` add overhead. Short-run warmup and runner differences matter.
- `after.json` is the end-of-load snapshot. Diagnostic extraction happens before `drain.json` polling; the latter duration is not end-to-end payment latency or total time since input stopped.
- Separate-host client latency includes a temporary public TLS relay and a Python forwarding gateway. Its 200-in-flight relay cap and network route constrain the experiment. It is neither direct LAN nor production capacity evidence.
- Startup CDS trials include both extraction-layout and archive changes, with warm dependencies on separate runners. Lazy startup does not establish first-payment readiness. Health and first accepted/completed payment are recorded separately.
- A p99 gate failure remains a failed run even if correctness checks and evidence archival succeed. Short offered-rate trials do not prove sustained production throughput.
