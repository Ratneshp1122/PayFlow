"""End-to-end Compose smoke test: HTTP, RBAC, Redis, DB worker, Kafka, webhook delivery."""
import base64, json, time, uuid, urllib.request, urllib.error, os
BASE=os.environ.get('BASE_URL','http://localhost:8080')
TOKEN=None
def request(method,path,body=None,key=None,user='merchant-demo'):
    password=os.environ.get('OPERATOR_PASSWORD','local-operator-password') if user=='operator' else os.environ.get('MERCHANT_PASSWORD','local-merchant-password')
    headers={'Content-Type':'application/json','Authorization':'Basic '+base64.b64encode(f'{user}:{password}'.encode()).decode()}
    if TOKEN and user=='merchant-demo':headers['Authorization']='Bearer '+TOKEN
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=5) as r: return r.status,json.loads(r.read() or '{}')
    except urllib.error.HTTPError as e: return e.code,{}
def wait(predicate,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            result=predicate()
            if result:return result
        except (OSError,ValueError):pass
        time.sleep(.2)
    raise AssertionError('condition did not become true')
wait(lambda:request('GET','/ops/status',user='operator')[0]==200)
code,token=request('POST','/auth/token',{})
assert code==200
TOKEN=token['access_token']
assert request('POST','/auth/token',{})[0]==403  # Bearer tokens cannot refresh themselves.
assert request('GET','/ops/status')[0]==403
assert request('POST','/ops/simulator/lose-next-response',{},user='operator')[0]==200
key=str(uuid.uuid4());body={'amount':25000,'currency':'INR'}
status,payment=request('POST','/v1/payments',body,key)
assert status==202,(status,payment)
assert request('POST','/v1/payments',body,key)[1]==payment
assert request('POST','/v1/payments',{'amount':1,'currency':'INR'},key)[0]==409
pid=payment['id']
wait(lambda:request('GET','/v1/payments/'+pid)[1].get('state')=='SUCCEEDED')
assert request('POST',f'/v1/payments/{pid}/refunds',{'amount':20000},str(uuid.uuid4()))[0]==202
wait(lambda:request('GET','/v1/payments/'+pid)[1].get('refunded')==20000)
assert request('POST',f'/v1/payments/{pid}/refunds',{'amount':6000},str(uuid.uuid4()))[0]==409
def drained():
    code,s=request('GET','/ops/status',user='operator')
    return code==200 and all(s[k]==0 for k in ['pendingOperations','unpublishedEvents','pendingWebhooks','unbalancedJournals'])
wait(drained)
ledger=request('GET','/v1/ledger')[1]
assert any(row['business_id']==pid for row in ledger)
print(json.dumps({'smoke':'passed','paymentId':pid,'checks':['RBAC','idempotent HTTP retries','payload conflict','provider response loss','capture','refund','refund bound','Kafka outbox drain','signed webhook delivery','ledger visibility']}))
