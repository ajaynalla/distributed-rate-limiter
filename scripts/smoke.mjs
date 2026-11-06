// Real HTTP integration check; Node 22+; does not require browser automation.
import assert from 'node:assert/strict';
const base = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '');
const configuration = await fetch(`${base}/v1/dashboard/config`).then(r => r.json());
assert.equal(configuration.demoEnabled, true, 'Enable DASHBOARD_ENABLED');
const request = async (path, body) => {
  const response = await fetch(base + path, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) });
  return { status: response.status, data: await response.json(), retry: response.headers.get('Retry-After') };
};
const create = async (mode, outage = false, failurePolicy = 'FAIL_CLOSED') => {
  const r = await request('/v1/lab/sessions', { limit: 5, windowMillis: 60000, mode, outage, failurePolicy });
  assert.equal(r.status, 201); return r.data.id;
};
const check = (id, node = 'A') => request(`/v1/lab/sessions/${id}/check`, { clientId: 'smoke', node });
const dispose = id => fetch(`${base}/v1/lab/sessions/${id}`, {method:'DELETE'});
for (const mode of configuration.redisEnabled ? ['IN_MEMORY', 'REDIS'] : ['IN_MEMORY']) {
  const id = await create(mode);
  // 16 simultaneous HTTP requests, alternating independent limiter objects.
  const results = await Promise.all(Array.from({length:16}, (_,i) => check(id, i%2 ? 'B' : 'A')));
  assert.equal(results.filter(r=>r.data.allowed).length, mode === 'REDIS' ? 5 : 10);
  assert.ok(results.every(r=>!r.data.degraded));
  assert.ok(results.filter(r=>!r.data.allowed).every(r=>r.status===429 && Number(r.retry)>=1));
  await dispose(id);
  console.log(`${mode}: quota enforced across A + B`);
}
for (const policy of ['FAIL_OPEN','FAIL_CLOSED']) {
  const id = await create('IN_MEMORY', true, policy);
  for(let i=0;i<4;i++) {
    const r=await check(id); assert.equal(r.data.degraded,true);
    assert.equal(r.data.allowed,policy==='FAIL_OPEN');
    if(i===3) assert.equal(r.data.circuitOpen,true);
  }
  await dispose(id); console.log(`${policy}: degraded decision and open circuit verified`);
}
const preflight = await fetch(`${base}/v1/lab/sessions`, { method:'OPTIONS', headers:{Origin:'http://localhost:5173','Access-Control-Request-Method':'POST'} });
assert.equal(preflight.headers.get('access-control-allow-origin'),'http://localhost:5173');
console.log('Real HTTP integration checks passed.');
