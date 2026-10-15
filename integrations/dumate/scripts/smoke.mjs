import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { STRATEGIES } from '../src/capabilities.mjs';
import { digest } from '../src/store.mjs';

// This is an explicit user-supplied demonstration situation, not a greenhouse observation.
const text = '我种植番茄，温室面积600平方米，目前坐果期，温度30℃，空气湿度88%，土壤含水率62%，下部叶片有少量黄斑，尚未确诊病害。希望比较节水、病害风险和收益之间的权衡，仅做农业研究与虚拟仿真，不操作真实设备。';
const url = process.env.DUMATE_URL || 'http://127.0.0.1:9998';
const token = process.env.DUMATE_TOKEN;
if (!token) throw new Error('Set DUMATE_TOKEN before running real E2E');
const prefix = randomUUID();
const evidence = { execution: 'REAL_HTTP_E2E', startedAt: new Date().toISOString(), demoInputIsMeasured: false, calls: [] };
const output = resolve(process.argv[2] || `.state/smoke-${prefix}`);
await mkdir(output, { recursive: true });
const call = async (operation, input, key) => {
  const response = await fetch(`${url}/v1/agri/${operation}`, { method: 'POST', headers: {
    authorization: `Bearer ${token}`, 'content-type': 'application/json', 'idempotency-key': `${prefix}-${key}`,
  }, body: JSON.stringify(input), signal: AbortSignal.timeout(125000) });
  const result = await response.json();
  evidence.calls.push({ operation, input, httpStatus: response.status, result });
  assert.equal(response.status, 200, JSON.stringify(result)); assert.equal(result.ok, true);
  return result;
};
try {
  const a = await call('analyze', { text }, 'analyze');
  const evalInput = { analysisId: a.data.analysisId, seed: 20250419, days: 56, acknowledgeReferenceScenario: true };
  const e = await call('evaluate', evalInput, 'evaluate');
  assert.equal(Object.keys(e.data.outcomes).length, 7);
  for (const key of Object.keys(STRATEGIES)) assert.ok(e.data.outcomes[key]);
  const replay = await call('evaluate', evalInput, 'evaluate');
  assert.deepEqual(replay, e);
  const recomputed = await call('evaluate', evalInput, 'recompute');
  assert.deepEqual(recomputed.data.outcomes, e.data.outcomes);
  const r = await call('report', { analysisId: a.data.analysisId, evaluationId: e.data.evaluationId }, 'report');
  assert.deepEqual(r.data.matrix, e.data.outcomes);
  assert.equal(r.data.fieldValidation, 'NOT_PERFORMED');
  const { resultSha256, ...content } = e.data;
  assert.equal(digest(content), resultSha256);
  await writeFile(resolve(output, 'report.md'), r.data.markdown, 'utf8');
  evidence.passed = true;
} catch (error) { evidence.passed = false; evidence.failure = error.message; process.exitCode = 1; }
finally {
  evidence.completedAt = new Date().toISOString();
  await writeFile(resolve(output, 'e2e.json'), JSON.stringify(evidence, null, 2), 'utf8');
  console.log(JSON.stringify({ passed: evidence.passed, output, calls: evidence.calls.length }));
}
