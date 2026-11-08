"""Three app-process restarts with already-running dependencies; not host cold boot."""
import json,os,pathlib,subprocess,time,uuid,urllib.request
import profile as profiler
root=profiler.ROOT;profiler.inventory(root)
variant=os.environ.get('VARIANT','baseline')
compose=['docker','compose','-f','compose.yml']
if variant=='cds':compose+=['-f','compose.cds.yml']
merchant=profiler.token('merchant-demo');operator=profiler.token('operator')
lines=['# Three startup trials','','Application process restarts; dependencies and filesystem caches remain warm. Time starts before docker compose start. Health, first durable acceptance, and first completed payment are distinct. No queued-command recovery claim.','','| Trial | Health seconds | Accepted seconds | Completed seconds |','|---|---:|---:|---:|']
failures=0
for trial in range(1,4):
 path=root/('run-'+str(trial));path.mkdir(exist_ok=True)
 result={'variant':variant,'trial':trial,'scenario':'warm-dependency app restart','healthSeconds':None,'acceptedSeconds':None,'completedSeconds':None}
 try:
  subprocess.run(compose+['stop','app'],check=True,stdout=subprocess.DEVNULL)
  start=time.monotonic();subprocess.run(compose+['start','app'],check=True,stdout=subprocess.DEVNULL)
  deadline=start+120
  while time.monotonic()<deadline:
   try:
    if profiler.http('/actuator/health',timeout=1).get('status')=='UP':break
   except Exception:pass
   time.sleep(.05)
  else:raise TimeoutError('health unavailable')
  result['healthSeconds']=time.monotonic()-start
  request=urllib.request.Request('http://localhost:8080/v1/payments',method='POST',data=b'{"amount":100,"currency":"INR"}',headers={'Authorization':'Bearer '+merchant,'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
  with urllib.request.urlopen(request,timeout=30) as response:
   assert response.status==202;payment=json.load(response)
  result['acceptedSeconds']=time.monotonic()-start
  while time.monotonic()<deadline:
   if profiler.http('/v1/payments/'+payment['id'],merchant)['state']=='SUCCEEDED':break
   time.sleep(.05)
  else:raise TimeoutError('payment did not complete')
  result['completedSeconds']=time.monotonic()-start
  session=profiler.Session(operator);session.path=path;session.drain(30)
  result['status']=profiler.http('/ops/status',operator)
  assert all(v==0 for v in result['status'].values()),'pending work or ledger imbalance'
 except Exception as error:
  failures+=1;result['error']=type(error).__name__+': '+str(error)
 finally:
  (path/'result.json').write_text(json.dumps(result,indent=2))
  with (path/'app.log').open('w') as log:subprocess.run(compose+['logs','--no-color','--tail','160','app'],stdout=log,stderr=subprocess.STDOUT)
  fmt=lambda value:'FAILED' if value is None else f'{value:.3f}'
  lines.append('| '+str(trial)+' | '+' | '.join(fmt(result[k]) for k in ['healthSeconds','acceptedSeconds','completedSeconds'])+' |')
  (root/'README.md').write_text('\n'.join(lines)+'\n')
raise SystemExit(1 if failures else 0)
