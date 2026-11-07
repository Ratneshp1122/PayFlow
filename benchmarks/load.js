import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
export const options = {scenarios:{create:{executor:'constant-arrival-rate',rate:Number(__ENV.RATE||100),timeUnit:'1s',duration:__ENV.DURATION||'60s',preAllocatedVUs:100,maxVUs:1000}},thresholds:{http_req_failed:['rate<0.01'],http_req_duration:['p(99)<35'],dropped_iterations:['count==0']}};
const base=__ENV.BASE_URL||'http://localhost:8080';
const authorization='Basic '+encoding.b64encode('merchant-demo:'+(__ENV.MERCHANT_PASSWORD||'local-merchant-password'));
const run=__ENV.RUN_ID||Date.now().toString();
export default function(){const response=http.post(base+'/v1/payments',JSON.stringify({amount:100,currency:'INR'}),{headers:{'Content-Type':'application/json','Authorization':authorization,'Idempotency-Key':`${run}-${__VU}-${__ITER}`}});check(response,{'durable acceptance':r=>r.status===202});}
export function handleSummary(data){return {'summary.json':JSON.stringify({runId:run,environment:{rate:__ENV.RATE||100,duration:__ENV.DURATION||'60s'},results:data},null,2)};}
