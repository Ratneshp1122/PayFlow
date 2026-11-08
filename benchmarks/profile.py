"""Bounded, secret-free profiling evidence. Only synthetic data; credentials stay in memory/env."""
import base64,collections,gzip,json,os,pathlib,subprocess,threading,time,urllib.request,urllib.parse
ROOT=pathlib.Path('evidence');ROOT.mkdir(exist_ok=True)
COMPOSE=['docker','compose','-f','compose.yml','-f','compose.profile.yml']
def command(*args):return subprocess.check_output(list(args),text=True).strip()
def sql(query):return command(*COMPOSE,'exec','-T','postgres','psql','-U','payflow','-Atc',query)
def http(path,token=None,method='GET',body=None,base='http://localhost:8080',basic=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if basic:headers['Authorization']='Basic '+base64.b64encode(basic.encode()).decode()
 req=urllib.request.Request(base+path,headers=headers,method=method,data=json.dumps(body).encode() if body is not None else None)
 with urllib.request.urlopen(req,timeout=60) as r:
  raw=r.read();return json.loads(raw) if raw else {}
def token(user):
 password=os.environ.get('OPERATOR_PASSWORD','local-operator-password') if user=='operator' else os.environ.get('MERCHANT_PASSWORD','local-merchant-password')
 return http('/auth/token',method='POST',basic=user+':'+password)['access_token']
def inventory(path):
 for name,args in [('cpu',['lscpu']),('memory',['free','-b']),('kernel',['uname','-a']),('docker',['docker','version'])]:
  (path/(name+'.txt')).write_text(command(*args)+'\n')
 (path/'commit.txt').write_text(command('git','rev-parse','HEAD')+'\n')
 (path/'configuration.json').write_text(json.dumps({key:os.environ.get(key,default) for key,default in [('DB_POOL_SIZE','10'),('PROFILE_ENABLED','true'),('WORKER_CONCURRENCY','1'),('OUTBOX_BATCH_SIZE','64'),('LAZY_INIT','false')]},indent=2))
def snapshot(operator):
 result={'time':time.time(),'metrics':{},'status':http('/ops/status',operator)}
 for name in ['payflow.pool.acquire','payflow.sql.execute','payflow.db.commit','payflow.db.rollback','hikaricp.connections.active','hikaricp.connections.pending','process.cpu.usage','system.cpu.usage','http.server.requests']:
  try:result['metrics'][name]=http('/actuator/metrics/'+name,operator)
  except Exception as e:result['metrics'][name]={'unavailable':type(e).__name__}
 result['databaseWaits']=json.loads(sql("SELECT coalesce(json_agg(x),'[]') FROM (SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity WHERE datname='payflow' GROUP BY state,wait_event_type,wait_event) x"))
 return result
def queries():
 return json.loads(sql("SELECT coalesce(json_agg(x),'[]') FROM (SELECT queryid::text,query,calls,total_exec_time,mean_exec_time,rows,shared_blks_hit,shared_blks_read,shared_blk_read_time,shared_blk_write_time FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC) x"))
class Session:
 def __init__(self,operator):self.operator=operator;self.active=False
 def start(self,name):
  if self.active:raise RuntimeError('profile already active')
  self.path=ROOT/name;self.path.mkdir(exist_ok=True);self.stop_event=threading.Event();self.active=True
  (self.path/'before.json').write_text(json.dumps(snapshot(self.operator),indent=2));(self.path/'sql-before.json').write_text(json.dumps(queries(),indent=2))
  http('/ops/profile/start',self.operator,'POST')
  def sample():
   with (self.path/'timeline.jsonl').open('w') as f:
    while not self.stop_event.is_set():
     try:f.write(json.dumps(snapshot(self.operator))+'\n');f.flush()
     except Exception as e:f.write(json.dumps({'time':time.time(),'error':type(e).__name__})+'\n')
     self.stop_event.wait(1)
  self.thread=threading.Thread(target=sample,daemon=True);self.thread.start()
 def stop(self):
  if not self.active:return
  self.stop_event.set();self.thread.join(timeout=65)
  (self.path/'after.json').write_text(json.dumps(snapshot(self.operator),indent=2));(self.path/'sql-after.json').write_text(json.dumps(queries(),indent=2));(self.path/'statements.json').write_text(json.dumps(http('/ops/profile/statements',self.operator),indent=2))
  http('/ops/profile/stop',self.operator,'POST')
  cid=command(*COMPOSE,'ps','-q','app');raw=pathlib.Path('/tmp/payflow-profile.jfr');command('docker','cp',cid+':/tmp/payflow-profile.jfr',str(raw))
  text=command('jfr','print','--json','--events','jdk.ExecutionSample,jdk.NativeMethodSample,jdk.CPULoad,jdk.GarbageCollection',str(raw))
  with gzip.open(self.path/'cpu-events.json.gz','wt') as f:f.write(text)
  data=json.loads(text);counts=collections.Counter();loads=[]
  for event in data.get('recording',{}).get('events',[]):
   values=event.get('values',{})
   if event['type']=='jdk.CPULoad':loads.append(values)
   trace=values.get('stackTrace') or {};frames=trace.get('frames',[])
   if frames:
    method=frames[0].get('method',{});counts[method.get('type',{}).get('name','?')+'.'+method.get('name','?')]+=1
  (self.path/'cpu-summary.json').write_text(json.dumps({'sampledTopFrames':counts.most_common(30),'cpuLoadSamples':loads,'interpretation':'stack sample frequency, not wall-time percentage'},indent=2))
  (self.path/'containers.txt').write_text(command('docker','stats','--no-stream'))
  self.active=False
 def drain(self,timeout=120):
  start=time.monotonic();last={}
  while time.monotonic()-start<timeout:
   last=http('/ops/status',self.operator)
   if all(last.get(k,-1)==0 for k in ['pendingOperations','pendingWebhooks','unpublishedEvents']):break
   time.sleep(1)
  (self.path/'drain.json').write_text(json.dumps({'seconds':time.monotonic()-start,'status':last,'fullyDrained':all(last.get(k,-1)==0 for k in ['pendingOperations','pendingWebhooks','unpublishedEvents'])},indent=2))
def initialize():
 sql('CREATE EXTENSION IF NOT EXISTS pg_stat_statements')
 inventory(ROOT)
 return Session(token('operator'))
if __name__=='__main__':
 session=initialize();auth=token('merchant-demo');failures=0
 for trial in range(1,4):
  name='run-'+str(trial);session.start(name)
  env=dict(os.environ,AUTH_TOKEN=auth,RUN_ID=os.environ.get('GITHUB_RUN_ID','local')+'-'+name)
  args=['docker','run','--rm','--network','host','--user',f'{os.getuid()}:{os.getgid()}','-v',str(pathlib.Path.cwd())+':/work','-w','/work/evidence/'+name,'-e','AUTH_TOKEN','-e','RATE','-e','DURATION','-e','RUN_ID','grafana/k6:0.57.0','run','/work/benchmarks/load.js']
  with (session.path/'k6.log').open('w') as log:result=subprocess.run(args,env=env,stdout=log,stderr=subprocess.STDOUT)
  (session.path/'exit-code.json').write_text(json.dumps({'k6':result.returncode}));failures+=result.returncode!=0
  session.stop();session.drain()
 raise SystemExit(1 if failures else 0)
