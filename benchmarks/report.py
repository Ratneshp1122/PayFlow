import json,os,pathlib
root=pathlib.Path('benchmarks/runs')/os.environ['RUN_ID'];root.mkdir(parents=True,exist_ok=True)
lines=['# Recorded benchmark trials','','All latency values below are client-observed. For separate-host runs, they include the public TLS relay; the relay has a 200-in-flight-request limit. Profile-enabled results include instrumentation overhead.','','| Trial | HTTP count | p99 ms | Dropped | Mean pool wait ms | Mean SQL execute ms |','|---|---:|---:|---:|---:|---:|']
def mean_delta(before,after,name):
 def values(d):return {x['statistic']:x['value'] for x in d.get('metrics',{}).get(name,{}).get('measurements',[])}
 a,b=values(before),values(after);count=b.get('COUNT',0)-a.get('COUNT',0)
 return (b.get('TOTAL_TIME',0)-a.get('TOTAL_TIME',0))*1000/count if count>0 else None
for p in sorted(root.rglob('summary.json')):
 d=json.loads(p.read_text());metrics=d['results']['metrics'];lat=metrics.get('http_req_duration{endpoint:create}',{}).get('values',{});trial=p.parent.name
 matches=list(root.rglob(trial+'/before.json'));pool=sql=None
 if matches:
  before=json.loads(matches[0].read_text());after_path=matches[0].with_name('after.json')
  if after_path.exists():
   after=json.loads(after_path.read_text());pool=mean_delta(before,after,'payflow.pool.acquire');sql=mean_delta(before,after,'payflow.sql.execute')
 fmt=lambda value:'n/a' if value is None else f'{value:.3f}'
 lines.append(f"| {trial} | {metrics.get('http_reqs',{}).get('values',{}).get('count','n/a')} | {fmt(lat.get('p(99)'))} | {metrics.get('dropped_iterations',{}).get('values',{}).get('count',0)} | {fmt(pool)} | {fmt(sql)} |")
lines+=['','Pool and SQL measurements include API and worker activity in the captured interval. SQL execution excludes connection acquisition, commit, and result iteration. CPU sampling is diagnostic; stack sample shares are not wall-time percentages.','','Missing summaries are failed/incomplete runs, not successful zero-load runs.',f"Workflow: https://github.com/{os.environ.get('GITHUB_REPOSITORY','Ratneshp1122/PayFlow')}/actions/runs/{os.environ['RUN_ID']}"]
(root/'README.md').write_text('\n'.join(lines)+'\n')
