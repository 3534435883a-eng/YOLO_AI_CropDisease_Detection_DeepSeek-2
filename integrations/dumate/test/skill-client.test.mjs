import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID, createHash } from 'node:crypto';
import { prepare, step, request, settings, main } from '../skill/agri-research/scripts/client.mjs';
import { createReport } from '../skill/agri-research/scripts/report.mjs';
const hash = data => createHash('sha256').update(JSON.stringify(data)).digest('hex');
async function fixture(t, handler) {
  const outdir = await mkdtemp(join(tmpdir(), 'agri-client-'));
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const config = { base: `http://127.0.0.1:${server.address().port}`, token: 'local-test-token-that-is-long-enough', timeoutMs: 2000 };
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(outdir, { recursive: true, force: true }); });
  return { outdir, config };
}
test('task persists UUID and key, successful replay never calls HTTP again', async t => {
  let count = 0, key;
  const f = await fixture(t, (req, res) => {
    count++; key = req.headers['idempotency-key'];
    const id = randomUUID(), data = { analysisId: id, draft: { fields: [] } }; data.resultSha256 = hash(data);
    res.end(JSON.stringify({ ok: true, requestId: id, data }));
  });
  const task = await prepare(f.outdir, '番茄温室温度30℃');
  const first = await step('analyze', f.outdir, f.config), again = await step('analyze', f.outdir, f.config);
  assert.equal(key, `${task.taskId}-analyze`); assert.equal(count, 1);
  assert.deepEqual(again.result, first.result); assert.equal(again.replay, true);
  await assert.rejects(prepare(f.outdir, '替换原文'), /已有任务/);
});
test('HTTP200 ok=false fails and cannot automatically resubmit', async t => {
  let count = 0;
  const f = await fixture(t, (req, res) => { count++; res.end(JSON.stringify({ ok: false, error: { code: 'UPSTREAM_BUSINESS_ERROR', message: 'PRIVATE ERROR' } })); });
  await prepare(f.outdir, '番茄温室');
  await assert.rejects(step('analyze', f.outdir, f.config), error => error.code === 'UPSTREAM_BUSINESS_ERROR' && !error.message.includes('PRIVATE'));
  await assert.rejects(step('analyze', f.outdir, f.config), error => error.code === 'RESULT_INDETERMINATE');
  assert.equal(count, 1);
});
test('dependency, lock and range validation happen before HTTP', async t => {
  let count = 0; const f = await fixture(t, () => { count++; });
  await assert.rejects(prepare(f.outdir, '番茄', 1, 57), /days/);
  await prepare(f.outdir, '番茄');
  await assert.rejects(step('evaluate', f.outdir, f.config), error => error.code === 'DEPENDENCY_MISSING');
  await writeFile(join(f.outdir, '.task.lock'), 'interrupted');
  await assert.rejects(step('analyze', f.outdir, f.config), error => error.code === 'TASK_LOCKED');
  assert.equal(count, 0);
});
test('incorrect response digest or associated identifier is rejected', async t => {
  const f = await fixture(t, (req, res) => res.end(JSON.stringify({ ok: true, requestId: randomUUID(), data: { analysisId: randomUUID(), resultSha256: 'wrong' } })));
  await assert.rejects(request('analyze', { text: '番茄' }, 'digest-test', f.config), error => error.code === 'EVIDENCE_HASH_MISMATCH');
  const mismatched = await fixture(t, (req, res) => {
    const data = { analysisId: randomUUID(), draft: { fields: [] } }; data.resultSha256 = hash(data);
    res.end(JSON.stringify({ ok: true, requestId: randomUUID(), data }));
  });
  await prepare(mismatched.outdir, '番茄');
  await assert.rejects(step('analyze', mismatched.outdir, mismatched.config), error => error.code === 'INVALID_RESPONSE');
});
test('redirect and arbitrary capability are blocked', async t => {
  let count = 0;
  const f = await fixture(t, (req, res) => { count++; res.writeHead(302, { location: '/private' }); res.end(); });
  await assert.rejects(request('analyze', { text: '番茄' }, 'redirect-test', f.config), error => error.code === 'CONNECTION_FAILED');
  assert.equal(count, 1);
  await assert.rejects(request('files', {}, 'forbidden-test', f.config), error => error.code === 'CAPABILITY_FORBIDDEN');
});
test('client total deadline marks uncertainty', async t => {
  const f = await fixture(t, () => {});
  await assert.rejects(request('analyze', { text: '番茄' }, 'timeout-test', { ...f.config, timeoutMs: 100 }), error => error.code === 'RESULT_INDETERMINATE');
});
test('configuration loads local credential and rejects insecure remote target', async t => {
  const f = await fixture(t, () => {});
  const path = join(f.outdir, '.env'); await writeFile(path, `DUMATE_TOKEN=${f.config.token}\nDUMATE_PORT=9998\n`);
  const config = await settings({ DUMATE_ENV_FILE: path }); assert.equal(config.token, f.config.token);
  await assert.rejects(settings({ DUMATE_TOKEN: f.config.token, DUMATE_ADAPTER_URL: 'http://untrusted.example' }), /受控HTTPS/);
});
test('report keeps exact metrics and separates business/all-seven rankings', async () => {
  const recorded = JSON.parse(await readFile(new URL('../docs/evidence/e2e.json', import.meta.url), 'utf8'));
  const a = recorded.calls[0].result.data, e = recorded.calls[1].result.data, r = recorded.calls[4].result.data;
  const before = JSON.stringify(e.outcomes), rendered = createReport(a, e, r);
  assert.equal(JSON.stringify(e.outcomes), before);
  assert.deepEqual(rendered.leaders.lowestDiseaseIntegral.strategies, ['P6_PID_FULL']);
  assert.deepEqual(rendered.leaders.businessLowestDiseaseIntegral.strategies, ['P3_AGENT']);
  assert.deepEqual(rendered.leaders.highestCost.strategies, ['P6_PID_FULL']);
  assert.match(rendered.markdown, /仍有1456元模型成本/);
  assert.match(rendered.markdown, /七组模拟利润均为负值/);
  assert.match(rendered.html, /<head>.*<\/head><body>.*<\/body><\/html>/s);
  for (const format of [rendered.markdown, rendered.html]) {
    assert.match(format, /执行方式：本机 HTTP 调用/);
    assert.match(format, /不声称已创建 Workflow、公开发布或完成实棚验证/);
  }
});

const goal = () => ({ goalText: '以P2为基准，至少节水10%，病害压力积分不增加。', confirmed: true,
  baseline: 'P2_RULE_ENGINE', minWaterSavingPct: 10, objectives: [{metric:'waterUsedM3',direction:'min'}],
  constraints: [{metric:'diseasePressureIntegral',maxIncreasePct:0}] });
async function goalTask(t) {
  const own = new URL('../docs/submission/evidence/', import.meta.url);
  const recorded = {};
  for (const op of ['analyze','evaluate','report']) recorded[op] = JSON.parse(await readFile(new URL(`${op}-response.json`, own), 'utf8'));
  const calls = [];
  const f = await fixture(t, (req, res) => {
    const op = req.url.split('/').at(-1); calls.push(op); res.end(JSON.stringify(recorded[op]));
  });
  await prepare(f.outdir, '番茄温室', 20250419, 56, goal());
  await step('analyze', f.outdir, f.config); await step('evaluate', f.outdir, f.config);
  return { ...f, recorded, calls };
}
test('goal chain requires local decide, makes only three HTTP calls and report retains original matrix', async t => {
  const f = await goalTask(t);
  await assert.rejects(step('report', f.outdir, f.config), e => e.code === 'DEPENDENCY_MISSING');
  assert.equal(f.calls.length, 2);
  const d = await step('decide', f.outdir), replay = await step('decide', f.outdir);
  assert.equal(d.result.execution, 'LOCAL_NO_HTTP'); assert.equal(replay.replay, true);
  assert.equal(f.calls.length, 2);
  await step('report', f.outdir, f.config);
  assert.deepEqual(f.calls, ['analyze','evaluate','report']);
  const r=JSON.parse(await readFile(join(f.outdir,'report-response.json'),'utf8'));
  assert.deepEqual(r, f.recorded.report);
  const report=await readFile(join(f.outdir,'report.md'),'utf8');
  assert.match(report,/RECOMMENDED/); assert.match(report,/P0_TO_P3_ONLY|P0–P3/);
  assert.ok(report.includes(d.result.data.decisionId));
});
test('goal change and retrofit flags stop before further calls', async t => {
  const f=await goalTask(t), path=join(f.outdir,'task.json');
  const task=JSON.parse(await readFile(path,'utf8')); task.experimentPlan.minWaterSavingPct=20;
  await writeFile(path,JSON.stringify(task));
  await assert.rejects(step('decide',f.outdir),e=>e.code==='EVIDENCE_HASH_MISMATCH');
  await assert.rejects(main(['report','--goal-file','changed.json','--outdir',f.outdir]),e=>e.code==='INVALID_ARGUMENT');
  assert.equal(f.calls.length,2);
});
test('rehashed forged selection still fails deterministic report recomputation without HTTP', async t => {
  const f=await goalTask(t); await step('decide',f.outdir);
  const path=join(f.outdir,'decide-response.json'), forged=JSON.parse(await readFile(path,'utf8'));
  forged.data.selection.recommendedStrategies=['P6_PID_FULL'];
  const {resultSha256,...data}=forged.data; forged.data.resultSha256=hash(data);
  await writeFile(path,JSON.stringify(forged));
  const taskPath=join(f.outdir,'task.json'),task=JSON.parse(await readFile(taskPath,'utf8'));
  task.attempts.decide.resultSha256=forged.data.resultSha256; await writeFile(taskPath,JSON.stringify(task));
  await assert.rejects(step('report',f.outdir,f.config),e=>e.code==='EVIDENCE_MISMATCH');
  await assert.rejects(step('decide',f.outdir),e=>e.code==='EVIDENCE_MISMATCH');
  assert.equal(f.calls.length,2);
});
test('rehashed wrong evaluation seed cannot enter decision chain', async t => {
  const f=await goalTask(t), path=join(f.outdir,'evaluate-response.json');
  const e=JSON.parse(await readFile(path,'utf8')); e.data.seed++;
  const {resultSha256,...data}=e.data; e.data.resultSha256=hash(data); await writeFile(path,JSON.stringify(e));
  const taskPath=join(f.outdir,'task.json'),task=JSON.parse(await readFile(taskPath,'utf8'));
  task.attempts.evaluate.resultSha256=e.data.resultSha256; await writeFile(taskPath,JSON.stringify(task));
  await assert.rejects(step('decide',f.outdir),x=>x.code==='EVIDENCE_MISMATCH');
  assert.equal(f.calls.length,2);
});
