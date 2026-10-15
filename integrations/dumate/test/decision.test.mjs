import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { selectStrategies, validateGoal } from '../skill/agri-research/scripts/decision.mjs';

const recorded = JSON.parse(await readFile(new URL('../docs/submission/evidence/evaluate-response.json', import.meta.url), 'utf8')).data;
const goal = () => ({ goalText: '相对P2至少节水10%，病害压力积分不得增加，按用水升序比较。', confirmed: true,
  baseline: 'P2_RULE_ENGINE', minWaterSavingPct: 10,
  objectives: [{ metric: 'waterUsedM3', direction: 'min' }],
  constraints: [{ metric: 'diseasePressureIntegral', maxIncreasePct: 0 }] });

test('actual recorded matrix recommends P3 only under explicitly chosen integral constraint', () => {
  const before = JSON.stringify(recorded);
  const result = selectStrategies(goal(), recorded);
  assert.equal(result.status, 'RECOMMENDED');
  assert.deepEqual(result.recommendedStrategies, ['P3_AGENT']);
  assert.deepEqual(result.eligibleStrategies, ['P3_AGENT']);
  assert.equal(result.candidates.length, 4);
  assert.equal(result.ablationStrategies.length, 3);
  assert.ok(result.candidates.find(r => r.strategy === 'P3_AGENT').waterSavingPct > 88);
  assert.equal(JSON.stringify(recorded), before);
});

test('both disease endpoints constrained at zero increase honestly yield no feasible water-saving plan', () => {
  const plan = goal(); plan.constraints.push({ metric: 'finalSeverityTotal', maxIncreasePct: 0 });
  const result = selectStrategies(plan, recorded);
  assert.equal(result.status, 'NO_FEASIBLE');
  assert.deepEqual(result.recommendedStrategies, []);
  const p3 = result.candidates.find(r => r.strategy === 'P3_AGENT');
  assert.equal(p3.checks.find(c => c.metric === 'finalSeverityTotal').passed, false);
  assert.equal(p3.checks.find(c => c.metric === 'diseasePressureIntegral').passed, true);
});

test('baseline eligible without an improving candidate gives NO_IMPROVEMENT', () => {
  const plan = goal(); plan.minWaterSavingPct = 0;
  plan.constraints.push({ metric: 'finalSeverityTotal', maxIncreasePct: 0 });
  const result = selectStrategies(plan, recorded);
  assert.equal(result.status, 'NO_IMPROVEMENT');
  assert.deepEqual(result.eligibleStrategies, ['P2_RULE_ENGINE']);
  assert.deepEqual(result.recommendedStrategies, []);
});

test('exact constraint boundary passes and any larger value fails; no rounded input comparison', () => {
  const e = structuredClone(recorded), plan = goal();
  e.outcomes.P3_AGENT.diseasePressureIntegral = e.outcomes.P2_RULE_ENGINE.diseasePressureIntegral;
  assert.equal(selectStrategies(plan, e).status, 'RECOMMENDED');
  e.outcomes.P3_AGENT.diseasePressureIntegral += .001;
  assert.equal(selectStrategies(plan, e).status, 'NO_FEASIBLE');
});

test('zero baseline has no division by zero and cannot support a positive relative saving target', () => {
  const plan = goal(); plan.baseline = 'P0_NONE';
  assert.throws(() => selectStrategies(plan, recorded), e => e.code === 'ZERO_WATER_BASELINE');
  plan.minWaterSavingPct = 0;
  const result = selectStrategies(plan, recorded);
  assert.equal(result.status, 'NO_IMPROVEMENT');
  assert.equal(result.candidates[0].waterSavingPct, null);
});

test('negative profits maximize correctly and tied objective vectors preserve every tied strategy', () => {
  const e = structuredClone(recorded), plan = goal();
  plan.objectives.push({ metric: 'profitYuan', direction: 'max' });
  e.outcomes.P1_FIXED_MANUAL = { ...e.outcomes.P3_AGENT, profitYuan: -100 };
  assert.deepEqual(selectStrategies(plan, e).recommendedStrategies, ['P1_FIXED_MANUAL']);
  e.outcomes.P1_FIXED_MANUAL.profitYuan = e.outcomes.P3_AGENT.profitYuan;
  assert.deepEqual(selectStrategies(plan, e).recommendedStrategies, ['P1_FIXED_MANUAL', 'P3_AGENT']);
});

test('goal cannot contain assumed thresholds, ablation baseline, arbitrary keys or inverted objective directions', () => {
  for (const mutate of [p => delete p.confirmed, p => delete p.minWaterSavingPct,
    p => { p.baseline = 'P6_PID_FULL'; }, p => { p.constraints = []; },
    p => { delete p.constraints[0].maxIncreasePct; }, p => { p.constraints[0].maxIncreasePct = '显著'; },
    p => { p.objectives[0].direction = 'max'; }, p => { p.url = 'http://unsafe'; },
    p => { p.constraints.push({ ...p.constraints[0] }); }]) {
    const p = goal(); mutate(p); assert.throws(() => validateGoal(p), e => e.code === 'INVALID_GOAL');
  }
});

test('malformed, missing, non-finite and negative metrics fail rather than silently choosing a winner', () => {
  for (const mutate of [e => delete e.outcomes.P4_PID_PROPORTIONAL,
    e => delete e.outcomes.P3_AGENT.finalSeverityTotal,
    e => { e.outcomes.P3_AGENT.waterUsedM3 = NaN; },
    e => { e.outcomes.P3_AGENT.costYuan = -1; },
    e => { e.scenarioBinding = 'USER_CONDITIONED'; }]) {
    const e = structuredClone(recorded); mutate(e);
    assert.throws(() => selectStrategies(goal(), e), x => x.code === 'INVALID_MATRIX');
  }
});

test('simulator constraint violation disqualifies otherwise best row and PID ablations never win', () => {
  const e = structuredClone(recorded); e.outcomes.P3_AGENT.constraintViolations = 1;
  e.outcomes.P6_PID_FULL.waterUsedM3 = 0;
  const result = selectStrategies(goal(), e);
  assert.equal(result.status, 'NO_FEASIBLE');
  assert.equal(result.candidates.find(r => r.strategy === 'P3_AGENT').checks.at(-1).passed, false);
});
