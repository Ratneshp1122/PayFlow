"""Two hosted runners, an authenticated temporary TLS relay, and encrypted credential handoff.

The relay is explicitly part of measured client latency; this is NOT a direct-LAN benchmark.
"""
import hmac,http.client,http.server,json,os,pathlib,re,secrets,subprocess,sys,threading,time,urllib.parse
import profile as profiler
ROOT=pathlib.Path.cwd();EXCHANGE=ROOT/'exchange';EXCHANGE.mkdir(exist_ok=True)
def wait_artifact(name,destination,seconds=420):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  result=subprocess.run(['gh','run','download',os.environ['GITHUB_RUN_ID'],'--repo',os.environ['GITHUB_REPOSITORY'],'--name',name,'--dir',str(destination)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if result.returncode==0:return
  time.sleep(5)
 raise TimeoutError('artifact unavailable: '+name)
def gateway():
 config=json.loads((ROOT/'.run-control.json').read_text());session=profiler.Session(config['operatorToken']);finished=threading.Event()
 class Handler(http.server.BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def do_GET(self):self.handle_request()
  def do_POST(self):self.handle_request()
  def handle_request(self):
   if self.path=='/health':self.send_response(200);self.end_headers();self.wfile.write(b'{}');return
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+config['merchantToken']):self.send_error(401);return
   length=int(self.headers.get('Content-Length','0'))
   if length>4096:self.send_error(413);return
   body=self.rfile.read(length)
   if self.path.startswith('/_benchmark/'):
    try:
     parts=self.path.split('/');action=parts[2]
     if self.command!='POST':raise ValueError('POST required')
     if action=='start' and len(parts)==4 and re.fullmatch(r'run-[1-3]',parts[3]):session.start(parts[3])
     elif action=='stop':session.stop()
     elif action=='drain':session.drain(90)
     elif action=='finish':session.stop();finished.set()
     else:raise ValueError('unknown control')
     self.send_response(200);self.end_headers();self.wfile.write(b'{}')
    except Exception as e:
     (profiler.ROOT/'gateway-error.json').write_text(json.dumps({'error':str(e),'type':type(e).__name__}));self.send_error(500,type(e).__name__)
    return
   if self.path!='/v1/payments' or self.command!='POST':self.send_error(404);return
   connection=http.client.HTTPConnection('127.0.0.1',8080,timeout=30)
   try:
    connection.request('POST',self.path,body,{'Authorization':'Bearer '+config['merchantToken'],'Content-Type':'application/json','Idempotency-Key':self.headers.get('Idempotency-Key','')})
    response=connection.getresponse();payload=response.read();self.send_response(response.status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
   except Exception:self.send_error(502)
   finally:connection.close()
 server=http.server.ThreadingHTTPServer(('127.0.0.1',8081),Handler)
 threading.Thread(target=server.serve_forever,daemon=True).start()
 completed=finished.wait(600)
 if session.active:session.stop()
 server.shutdown();(ROOT/'.server-finished').write_text(json.dumps({'finished':completed}))
def prepare():
 session=profiler.initialize();merchant=profiler.token('merchant-demo')
 (ROOT/'.run-control.json').write_text(json.dumps({'operatorToken':session.operator,'merchantToken':merchant}))
 os.chmod(ROOT/'.run-control.json',0o600)
 (profiler.ROOT/'topology.json').write_text(json.dumps({'topology':'separate GitHub-hosted app and load runner via Cloudflare Quick Tunnel','clientLatencyIncludesRelay':True,'relayInflightLimit':200,'publicIngress':'gateway allows only authenticated payment POSTs and bounded benchmark controls; app/ops ports stay loopback'},indent=2))
 with open('/tmp/payflow-gateway.log','w') as log:subprocess.Popen([sys.executable,__file__,'gateway'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 with open('/tmp/payflow-relay.log','w') as log:subprocess.Popen(['/tmp/cloudflared','tunnel','--url','http://127.0.0.1:8081','--no-autoupdate'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 end=time.monotonic()+120;url=None
 while time.monotonic()<end:
  match=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',pathlib.Path('/tmp/payflow-relay.log').read_text())
  if match:
   candidate=match.group(0)
   try:profiler.http('/health',base=candidate);url=candidate;break
   except Exception:pass
  time.sleep(2)
 if not url:raise RuntimeError('temporary relay unavailable')
 wait_artifact('load-public-certificate',EXCHANGE/'public')
 clear=ROOT/'.handoff.json';clear.write_text(json.dumps({'url':url,'token':merchant}));os.chmod(clear,0o600)
 subprocess.run(['openssl','cms','-encrypt','-aes-256-cbc','-binary','-outform','DER','-in',str(clear),'-out',str(EXCHANGE/'endpoint.cms'),str(EXCHANGE/'public'/'load-public.crt')],check=True)
 clear.unlink()
 (profiler.ROOT/'relay-build.txt').write_text(profiler.command('/tmp/cloudflared','--version')+'\n'+profiler.command('sha256sum','/tmp/cloudflared')+'\n')
def client():
 profiler.inventory(profiler.ROOT);wait_artifact('encrypted-endpoint',EXCHANGE/'endpoint')
 clear=ROOT/'.handoff.json';subprocess.run(['openssl','cms','-decrypt','-binary','-inform','DER','-in',str(EXCHANGE/'endpoint'/'endpoint.cms'),'-recip',str(EXCHANGE/'load-public.crt'),'-inkey',str(ROOT/'.load-private.pem'),'-out',str(clear)],check=True)
 config=json.loads(clear.read_text());clear.unlink();failures=0
 def control(action):return profiler.http('/_benchmark/'+action,config['token'],'POST',base=config['url'],timeout=120)
 try:
  for trial in range(1,4):
   name='run-'+str(trial);path=profiler.ROOT/name;path.mkdir(exist_ok=True);control('start/'+name)
   env=dict(os.environ,AUTH_TOKEN=config['token'],BASE_URL=config['url'],RUN_ID=os.environ['GITHUB_RUN_ID']+'-'+name)
   args=['docker','run','--rm','--user',f'{os.getuid()}:{os.getgid()}','-v',str(ROOT)+':/work','-w','/work/evidence/'+name,'-e','AUTH_TOKEN','-e','BASE_URL','-e','RATE','-e','DURATION','-e','RUN_ID','grafana/k6:0.57.0','run','/work/benchmarks/load.js']
   start=time.time()
   with (path/'k6.log').open('w') as log:result=subprocess.run(args,env=env,stdout=log,stderr=subprocess.STDOUT)
   (path/'run.json').write_text(json.dumps({'startedAt':start,'endedAt':time.time(),'exitCode':result.returncode,'separateHost':True,'relay':True,'rate':os.environ['RATE'],'duration':os.environ['DURATION']}));failures+=result.returncode!=0
   control('stop');control('drain')
 finally:
  try:control('finish')
  except Exception:pass
 raise SystemExit(1 if failures else 0)
if __name__=='__main__':
 mode=sys.argv[1]
 if mode=='secrets':
  path=ROOT/'.env';path.write_text('\n'.join(k+'='+secrets.token_hex(32) for k in ['MERCHANT_PASSWORD','OPERATOR_PASSWORD','WEBHOOK_SECRET','JWT_SECRET'])+'\n');os.chmod(path,0o600)
 elif mode=='prepare':prepare()
 elif mode=='gateway':gateway()
 elif mode=='client':client()
 elif mode=='wait':
  end=time.monotonic()+660
  while time.monotonic()<end and not (ROOT/'.server-finished').exists():time.sleep(3)
  if not (ROOT/'.server-finished').exists():raise TimeoutError('load runner did not finish')
