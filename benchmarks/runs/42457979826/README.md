# Recorded benchmark trials

All latency values below are client-observed. For separate-host runs, they include the public TLS relay; the relay has a 200-in-flight-request limit. Profile-enabled results include instrumentation overhead.

| Trial | HTTP count | p99 ms | Dropped | Mean pool wait ms | Mean SQL execute ms |
|---|---:|---:|---:|---:|---:|
| run-1 | 17450 | 1235.986 | 551 | 42.204 | 1.623 |
| run-2 | 18001 | 29.955 | 0 | 0.153 | 0.759 |
| run-3 | 18001 | 16.782 | 0 | 0.056 | 0.699 |

Pool and SQL measurements include API and worker activity in the captured interval. SQL execution excludes connection acquisition, commit, and result iteration. CPU sampling is diagnostic; stack sample shares are not wall-time percentages.

Missing summaries are failed/incomplete runs, not successful zero-load runs.
Workflow: https://github.com/Ratneshp1122/PayFlow/actions/runs/42457979826
