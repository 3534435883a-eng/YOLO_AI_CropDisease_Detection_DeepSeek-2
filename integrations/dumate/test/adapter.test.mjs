import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createAdapter } from '../src/server.mjs';
import { configFromEnv } from '../src/config.mjs';
import { STRATEGIES, METRICS, SCHEMAS } from '../src/capabilities.mjs';
import { digest } from '../src/store.mjs';

// Contract fixtures, NOT measured simulation results or demonstration evidence.
const text = '番茄温室温度30℃，湿度88%，下部叶片有黄斑。';
const token = 'test-token-0123456789-0123456789-0123456789';
const draft = { fields: [{ key: 'crop', value: '番茄', rawText: '番茄', label: '作物', source: 'PARSED' },
  { key: 'temperatureC', value: 30, rawText: '温度30℃' },
  { key: 'symptoms', value: '黄斑', rawText: '下部叶片有黄斑' }], missing: ['预算'], conflicts: [], notes: [] };
const outcomes = Object.fromEntries(Object.keys(STRATEGIES).map((s, index) => [s,
  Object.fromEntries(Object.keys(METRICS).map(m => [m, index + 1]))]));
const ok = data => ({ code: '0', msg: '成功', data });
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`)));
const close = server => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });

async function setup(t, options = {}) {
  const stateDir = await mkdtemp(join(tmpdir(), 'dumate-test-'));
  const calls = []; let batch;
  const upstream = http.createServer(async (req, res) => {
    const chunks = []; for await (const c of req) chunks.push(c);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null;
    calls.push({ path: req.url, method: req.method, body, headers: req.headers });
    res.setHeader('content-type', 'application/json');
    if (options.handler && await options.handler(req, res, body)) return;
    if (req.url.endsWith('/situation/parse')) res.end(JSON.stringify(ok(options.draft || draft)));
    else if (req.url === '/ai/knowledge/status') res.end(JSON.stringify(ok({ chunkCount: 495, sourceCount: 41, internalDirectory: 'private' })));
    else if (req.url === '/ai/agri/environment/current') res.end(JSON.stringify(ok({ source: 'SIMULATED_LOCATION', temperatureC: 24, internalDirectory: 'private' })));
    else if (req.url === '/eval/runs') { batch = body; res.end(JSON.stringify(ok(body))); }
    else if (req.url.endsWith('/matrix')) res.end(JSON.stringify(ok({ ...batch, outcomes: options.outcomes || outcomes,
      profile: { sourceStatus: 'SIMULATED_UNCALIBRATED', calibrated: false, privatePath: 'secret' } })));
    else { res.statusCode = 404; res.end('{}'); }
  });
  const upstreamUrl = await listen(upstream);
  const config = { ...configFromEnv({ DUMATE_TOKEN: token, DUMATE_UPSTREAM: upstreamUrl }), stateDir,
    timeoutMs: options.timeoutMs || 3000, upstreamToken: 'server-only-credential', ...options.config };
  let adapter = await createAdapter(config);
  let url = await listen(adapter.server);
  const request = async (path, body, key = `key-${calls.length}-${Date.now()}`, headers = {}) => {
    const response = await fetch(`${url}${path}`, { method: 'POST', headers: {
      authorization: `Bearer ${token}`, 'content-type': 'application/json', 'idempotency-key': key, ...headers,
    }, body: typeof body === 'string' ? body : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  };
  t.after(async () => { await close(adapter.server); await close(upstream); await rm(stateDir, { recursive: true, force: true }); });
  return { calls, request, stateDir, config, get url() { return url; }, get store() { return adapter.store; },
    restart: async () => { await close(adapter.server); adapter = await createAdapter(config); url = await listen(adapter.server); } };
}
async function chain(f) {
  const a = await f.request('/v1/agri/analyze', { text }, 'analysis-key');
  assert.equal(a.status, 200);
  const input = { analysisId: a.body.data.analysisId, acknowledgeReferenceScenario: true, days: 56, seed: 20250419 };
  const e = await f.request('/v1/agri/evaluate', input, 'evaluation-key');
  assert.equal(e.status, 200);
  return { a, e, input };
}

test('three capabilities use real HTTP contract, fixed paths, stored evidence and original values', async t => {
  const f = await setup(t); const { a, e } = await chain(f);
  const r = await f.request('/v1/agri/report', { analysisId: a.body.data.analysisId, evaluationId: e.body.data.evaluationId }, 'report-key');
  assert.equal(r.status, 200);
  assert.deepEqual(e.body.data.outcomes, outcomes);
  assert.deepEqual(r.body.data.matrix, outcomes);
  assert.equal(r.body.data.comparisonToRule.P2_RULE_ENGINE.waterUsedM3, 0);
  assert.equal(r.body.data.fieldValidation, 'NOT_PERFORMED');
  assert.match(r.body.data.markdown, /不等于 LLM/);
  assert.equal(f.calls.length, 5);
  assert.equal(f.calls[3].body.batchId, e.body.data.evaluationId);
  assert.equal(f.calls.every(c => c.headers.authorization === 'Bearer server-only-credential'), true);
  assert.equal(JSON.stringify(r.body).includes('privatePath'), false);
  assert.equal(JSON.stringify(r.body).includes('internalDirectory'), false);
  assert.equal(JSON.stringify(r.body).includes('rawResponse'), false);
  const record = JSON.parse(await readFile(join(f.stateDir, `${e.body.data.evaluationId}.json`)));
  assert.equal(record.evidence[1].rawResponse.code, '0');
  const { resultSha256, ...withoutHash } = e.body.data;
  assert.equal(digest(withoutHash), resultSha256);
});

test('idempotent replay after restart never re-submits upstream POST, changed parameters conflict', async t => {
  const f = await setup(t); const { e, input } = await chain(f);
  await f.restart(); const before = f.calls.length;
  const replay = await f.request('/v1/agri/evaluate', input, 'evaluation-key');
  assert.deepEqual(replay.body, e.body); assert.equal(f.calls.length, before);
  const conflict = await f.request('/v1/agri/evaluate', { ...input, days: 5 }, 'evaluation-key');
  assert.equal(conflict.status, 409); assert.equal(conflict.body.error.code, 'IDEMPOTENCY_CONFLICT');
});

test('auth, methods, direct backend and filesystem/device/user paths are denied without upstream calls', async t => {
  const f = await setup(t);
  assert.equal((await f.request('/v1/agri/analyze', { text }, 'auth-key', { authorization: 'Bearer wrong' })).status, 401);
  for (const path of ['/user', '/files', '/greenhouse', '/eval/runs', '/m3/live/start', '/v1/agri/analyze?url=/user', '/__proto__', '/.state/record.json']) {
    assert.equal((await f.request(path, {})).status, 404);
  }
  assert.equal((await fetch(`${f.url}/v1/agri/analyze`, { headers: { authorization: `Bearer ${token}` } })).status, 405);
  assert.equal((await f.request('/v1/agri/analyze', { text }, 'short')).status, 400);
  assert.equal(f.calls.length, 0);
});

test('strict JSON input rejects injection, overlong text, wrong media, malformed JSON and large payload', async t => {
  const f = await setup(t);
  for (const input of [{ text, url: 'http://internal/users' }, { text: '' }, { text: 'x'.repeat(6001) }, [], null]) {
    assert.equal((await f.request('/v1/agri/analyze', input)).status, 400);
  }
  assert.equal((await f.request('/v1/agri/analyze', '{broken')).status, 400);
  assert.equal((await f.request('/v1/agri/analyze', { text }, 'media-key', { 'content-type': 'text/plain' })).status, 415);
  assert.equal((await f.request('/v1/agri/analyze', JSON.stringify({ text: 'x'.repeat(33000) }))).status, 413);
  assert.equal(f.calls.length, 0);
});

test('limits, acknowledgment, references and cross-chain report are enforced', async t => {
  const f = await setup(t); const { a, e, input } = await chain(f);
  for (const patch of [{ days: 0 }, { days: 57 }, { days: 1.5 }, { seed: -1 }, { seed: '1' }, { acknowledgeReferenceScenario: false }]) {
    assert.equal((await f.request('/v1/agri/evaluate', { ...input, ...patch })).status, 400);
  }
  assert.equal((await f.request('/v1/agri/evaluate', { ...input, analysisId: '../../file' })).status, 400);
  const second = await f.request('/v1/agri/analyze', { text }, 'second-analysis-key');
  assert.equal((await f.request('/v1/agri/report', { analysisId: second.body.data.analysisId, evaluationId: e.body.data.evaluationId }, 'mismatch-key')).status, 409);
  assert.equal((await f.request('/v1/agri/report', { analysisId: a.body.data.analysisId, evaluationId: e.body.data.evaluationId, matrix: outcomes })).status, 400);
});

test('invented original quotes are excluded and empty extraction fails without fake fallback', async t => {
  const f = await setup(t, { draft: { ...draft, fields: [...draft.fields, { key: 'humidityPct', value: 99, rawText: '从未说过的湿度' }] } });
  const a = await f.request('/v1/agri/analyze', { text });
  assert.equal(a.status, 200); assert.equal(a.body.data.draft.fields.length, 3);
  const empty = await setup(t, { draft: { fields: [], notes: ['AI调用异常'] } });
  assert.equal((await empty.request('/v1/agri/analyze', { text })).body.error.code, 'ANALYSIS_UNAVAILABLE');
});

for (const [status, code, expected, mapped] of [
  [401, '0', 502, 'UPSTREAM_AUTH_FAILED'], [403, '0', 502, 'UPSTREAM_AUTH_FAILED'],
  [429, '0', 503, 'UPSTREAM_BUSY'], [500, '0', 502, 'UPSTREAM_HTTP_ERROR'],
  [200, 'FAILURE', 502, 'UPSTREAM_BUSINESS_ERROR'],
]) test(`upstream HTTP ${status} / business ${code} maps safely`, async t => {
  const f = await setup(t, { handler: async (req, res) => { res.statusCode = status; res.end(JSON.stringify({ code, msg: 'SECRET', data: {} })); return true; } });
  const result = await f.request('/v1/agri/analyze', { text }, 'failure-key');
  assert.equal(result.status, expected); assert.equal(result.body.error.code, mapped);
  assert.equal(JSON.stringify(result).includes('SECRET'), false);
  const again = await f.request('/v1/agri/analyze', { text }, 'failure-key');
  assert.deepEqual(again, result); assert.equal(f.calls.length, 1);
});

test('optional status failure stays explicit; successful matrix cannot omit a strategy', async t => {
  const f = await setup(t, { handler: async (req, res) => {
    if (req.url === '/ai/knowledge/status') { res.end(JSON.stringify({ code: 'FAIL' })); return true; } return false;
  }, outcomes: { ...outcomes, P6_PID_FULL: null } });
  const a = await f.request('/v1/agri/analyze', { text });
  assert.equal(a.body.data.knowledge, null); assert.equal(a.body.data.warnings[0].tool, 'knowledge');
  const result = await f.request('/v1/agri/evaluate', { analysisId: a.body.data.analysisId, acknowledgeReferenceScenario: true });
  assert.equal(result.body.error.code, 'UPSTREAM_INVALID_RESPONSE');
});

test('total deadline aborts slow HTTP and caches uncertainty; pending record survives restart', async t => {
  const f = await setup(t, { timeoutMs: 100, handler: async () => true });
  const started = Date.now();
  const failed = await f.request('/v1/agri/analyze', { text }, 'timeout-key');
  assert.equal(failed.status, 504); assert.equal(failed.body.error.retryable, false);
  assert.ok(Date.now() - started < 1500);
  assert.deepEqual(await f.request('/v1/agri/analyze', { text }, 'timeout-key'), failed);
  const pending = await f.store.begin('pending-key', 'agri.analyze', { text });
  assert.equal(pending.record.state, 'PENDING');
  await f.restart();
  assert.equal((await f.request('/v1/agri/analyze', { text }, 'pending-key')).body.error.code, 'RESULT_INDETERMINATE');
  assert.equal(f.calls.length, 1);
});

test('invalid config cannot open arbitrary insecure remote targets', () => {
  for (const url of ['http://remote.example', 'https://user:pass@example.com', 'https://example.com/?x=1', 'file:///tmp/file']) {
    assert.throws(() => configFromEnv({ DUMATE_TOKEN: token, DUMATE_UPSTREAM: url }));
  }
  assert.throws(() => configFromEnv({ DUMATE_TOKEN: 'short' }));
});

test('published OpenAPI request contracts match implemented strict schemas', async () => {
  const api = JSON.parse(await readFile(new URL('../openapi.json', import.meta.url), 'utf8'));
  assert.equal(Object.keys(api.paths).length, 3);
  for (const [capability, schema] of Object.entries(SCHEMAS)) {
    const operation = api.paths[`/v1/agri/${capability.split('.')[1]}`].post;
    assert.deepEqual(operation.requestBody.content['application/json'].schema, schema);
  }
  assert.deepEqual(api.security, [{ adapterBearer: [] }]);
});

test('expired upstream batch maps to 410 and persists partial run evidence', async t => {
  const f = await setup(t, { handler: async (req, res) => {
    if (req.url.endsWith('/matrix')) { res.end(JSON.stringify({ code: 'EVAL_BATCH_NOT_FOUND', msg: 'expired' })); return true; }
    return false;
  } });
  const a = await f.request('/v1/agri/analyze', { text }, 'expiry-analysis');
  const e = await f.request('/v1/agri/evaluate', { analysisId: a.body.data.analysisId, acknowledgeReferenceScenario: true }, 'expiry-eval');
  assert.equal(e.status, 410); assert.equal(e.body.error.code, 'BATCH_EXPIRED');
  const saved = JSON.parse(await readFile(join(f.stateDir, `${e.body.requestId}.json`)));
  assert.equal(saved.evidence[0].operation, 'run'); assert.equal(saved.evidence[1].errorCode, 'BATCH_EXPIRED');
});

test('redirects are not followed to any non-allowlisted path', async t => {
  const f = await setup(t, { handler: async (req, res) => { res.statusCode = 302; res.setHeader('location', '/private-files'); res.end(); return true; } });
  assert.equal((await f.request('/v1/agri/analyze', { text })).status, 502);
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].path, '/ai/agri/plan/situation/parse');
});

test('concurrent duplicate key cannot submit two upstream requests', async t => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  const f = await setup(t, { handler: async (req, res) => {
    if (req.url.endsWith('/situation/parse')) { entered(); await gate; }
    return false;
  } });
  const first = f.request('/v1/agri/analyze', { text }, 'parallel-key');
  await started;
  const duplicate = await f.request('/v1/agri/analyze', { text }, 'parallel-key');
  assert.equal(duplicate.status, 409); release(); assert.equal((await first).status, 200);
  assert.equal(f.calls.filter(c => c.path.endsWith('/situation/parse')).length, 1);
});

test('record TTL prevents stale report references; capacity does not overwrite evidence', async t => {
  const f = await setup(t, { config: { maxRecords: 1 } });
  const a = await f.request('/v1/agri/analyze', { text }, 'capacity-key');
  assert.equal((await f.request('/v1/agri/analyze', { text }, 'capacity-next')).status, 503);
  const record = f.store.records.get(a.body.data.analysisId);
  record.createdAt -= f.config.ttlMs + 1;
  assert.throws(() => f.store.get(record.id, 'agri.analyze'), /过期/);
  const next = await f.request('/v1/agri/analyze', { text }, 'capacity-next');
  assert.equal(next.status, 200); assert.equal(f.store.records.size, 1);
});
