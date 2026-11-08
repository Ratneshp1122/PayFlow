# Recorded benchmark trials

All latency values below are client-observed. For separate-host runs, they include the public TLS relay; the relay has a 200-in-flight-request limit. Profile-enabled results include instrumentation overhead.

| Trial | HTTP count | p99 ms | Dropped | Mean pool wait ms | Mean SQL execute ms |
|---|---:|---:|---:|---:|---:|
| run-1 | 4501 | 1115.411 | 0 | 7.559 | 0.789 |
| run-2 | 4465 | 1107.891 | 35 | 7.331 | 0.347 |
| run-3 | 4501 | 269.773 | 0 | 2.300 | 0.320 |

Pool and SQL measurements include API and worker activity in the captured interval. SQL execution excludes connection acquisition, commit, and result iteration. CPU sampling is diagnostic; stack sample shares are not wall-time percentages.

Missing summaries are failed/incomplete runs, not successful zero-load runs.
Workflow: https://github.com/Ratneshp1122/PayFlow/actions/runs/42457083110
