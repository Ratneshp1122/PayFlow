"""Run from repository root against Compose; kills only this project's app container."""
import sys,pathlib,time,subprocess,json,uuid
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'scripts'))
# Standalone client; deliberately do not import smoke.py, which performs a full test on import.
import urllib.request,base64
def call(method,path,body=None,key=None):
    headers={'Authorization':'Basic '+base64.b64encode(b'merchant-demo:local-merchant-password').decode(),'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    req=urllib.request.Request('http://localhost:8080'+path,data=json.dumps(body).encode() if body else None,headers=headers,method=method)
    with urllib.request.urlopen(req,timeout=2) as r:return json.load(r)
payment=call('POST','/v1/payments',{'amount':100,'currency':'INR'},str(uuid.uuid4()))
start=time.monotonic()
subprocess.run(['docker','compose','kill','-s','SIGKILL','app'],check=True)
subprocess.run(['docker','compose','up','-d','app'],check=True)
ready=None;completed=None
while time.monotonic()-start<120:
    try:
        state=call('GET','/v1/payments/'+payment['id'])['state']
        if ready is None:ready=time.monotonic()-start
        if state=='SUCCEEDED':completed=time.monotonic()-start;break
    except Exception:pass
    time.sleep(.05)
result={'scenario':'single accepted command, SIGKILL app, compose restart','paymentId':payment['id'],'apiReadySeconds':ready,'observedSuccessSeconds':completed,'under3Seconds':completed is not None and completed<3,'limitation':'command may have completed before kill; this is availability observation, not proof of interrupted-command recovery'}
print(json.dumps(result,indent=2))
if completed is None:raise SystemExit(1)
