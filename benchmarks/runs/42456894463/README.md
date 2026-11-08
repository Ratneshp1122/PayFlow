# Recorded benchmark trials

All latency values below are client-observed. For separate-host runs, they include the public TLS relay; the relay has a 200-in-flight-request limit. Profile-enabled results include instrumentation overhead.

| Trial | HTTP count | p99 ms | Dropped | Mean pool wait ms | Mean SQL execute ms |
|---|---:|---:|---:|---:|---:|
| run-1 | 4501 | 88.758 | 0 | 0.054 | 0.448 |
| run-2 | 4501 | 12.908 | 0 | 0.025 | 0.164 |
| run-3 | 4501 | 10.281 | 0 | 0.033 | 0.145 |

Pool and SQL measurements include API and worker activity in the captured interval. SQL execution excludes connection acquisition, commit, and result iteration. CPU sampling is diagnostic; stack sample shares are not wall-time percentages.

Missing summaries are failed/incomplete runs, not successful zero-load runs.
Workflow: https://github.com/Ratneshp1122/PayFlow/actions/runs/42456894463
