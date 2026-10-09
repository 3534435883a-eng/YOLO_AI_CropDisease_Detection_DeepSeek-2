#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createHash } = require('node:crypto');
const root = path.resolve(__dirname, '..');
const frontend = path.join(root, 'YOLO_AI_CropDisease_Detection_Vue');
const requireFrontend = createRequire(path.join(frontend, 'package.json'));
const ts = requireFrontend('typescript');
const sourcePath = path.join(frontend, 'src/utils/agent/taskSituation.ts');
const source = fs.readFileSync(sourcePath, 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const moduleObject = { exports: {} };
vm.runInNewContext('(function(module,exports){\n' + compiled + '\n})', { console })(moduleObject, moduleObject.exports);
const restore = moduleObject.exports.restoreTaskSituation;
const plain = value => JSON.parse(JSON.stringify(value));
const specs = [
  ['crop', '作物', 'TEXT', '基本情况'], ['growthStage', '当前生育期', 'TEXT', '基本情况'], ['variety', '品种', 'TEXT', '基本情况'],
  ['temperatureC', '温度', 'NUMBER', '环境读数', '℃'], ['humidityPct', '空气湿度', 'NUMBER', '环境读数', '%'],
  ['soilMoisturePct', '土壤含水率', 'NUMBER', '环境读数', '%'], ['co2Ppm', 'CO₂浓度', 'NUMBER', '环境读数', 'ppm'],
  ['lightPpfd', '光照', 'NUMBER', '环境读数', 'μmol·m⁻²·s⁻¹'], ['vpdKpa', '水汽压亏缺', 'NUMBER', '环境读数', 'kPa'],
  ['recentOperations', '近期农事操作', 'TEXT', '管理情况'], ['symptoms', '田间症状描述', 'TEXT', '病虫害'], ['goal', '生产目标', 'TEXT', '生产目标'],
].map(([key, label, kind, group, unit]) => ({ key, label, kind, group, unit }));
const csv = (input, createdAt = '2026-10-08T19:43:51+08:00', extra = {}) => ({
  id: 'csv-1', type: 'CSV', label: '农情表格', source: '用户上传，已核对', createdAt,
  details: {
    input: { ...input }, fields: Object.entries(input).map(([key, value]) => ({ key, value, source: 'USER' })),
    conflicts: [], notes: [], missing: [], unrecognizedColumns: [],
  }, ...extra,
});
const agri = (input, createdAt = '2026-10-08T20:00:00+08:00') => ({
  id: 'agri-1', type: 'AGRI_INPUT', label: '已核对农情', source: '用户表单 / 已核对，非传感器实测', createdAt,
  details: { situation: { ...input }, fields: [], conflicts: [] },
});
const tests = [];
const test = (name, body) => tests.push({ name, body });

test('规划页面脚本和模板可以编译', () => {
  const compiler = requireFrontend('@vue/compiler-sfc');
  const filename = path.join(frontend, 'src/views/agentSimulation/index.vue');
  const parsed = compiler.parse(fs.readFileSync(filename, 'utf8'), { filename });
  assert.equal(parsed.errors.length, 0);
  const script = compiler.compileScript(parsed.descriptor, { id: 'task-situation-validation' });
  const template = compiler.compileTemplate({ source: parsed.descriptor.template.content, filename, id: 'task-situation-validation', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
});

test('真实 CSV 结构恢复 9 项用户农情及来源', () => {
  const input = { crop: '番茄', growthStage: '结果初期', temperatureC: 22, humidityPct: 92, co2Ppm: 500, lightPpfd: 120,
    recentOperations: '尚未用药，先核查叶背', symptoms: '流程验证样例：下层叶片褐斑，非现场实测', goal: '先辨别病因与安排巡查' };
  const result = restore([csv(input)], specs, {});
  assert.deepEqual(plain(result.patch), input); assert.equal(result.draft.fields.length, 9);
  assert.equal(result.sources.temperatureC.evidenceSource, '用户上传，已核对');
  assert.equal(result.sources.temperatureC.fieldSource, 'USER'); assert.equal(result.sources.temperatureC.evidenceId, 'csv-1');
  assert.ok(result.sourceNotes.some(note => note.includes('非传感器实测')));
  assert.ok(result.draft.missing.includes('土壤含水率'));
});

test('已填写、主动清空和零值字段保持原值', () => {
  const current = { temperatureC: 18, humidityPct: '', co2Ppm: 0 };
  const result = restore([csv({ crop: '番茄', temperatureC: 22, humidityPct: 92, co2Ppm: 500 })], specs, current, new Set(['humidityPct']));
  assert.deepEqual(plain(result.patch), { crop: '番茄' });
  assert.deepEqual(current, { temperatureC: 18, humidityPct: '', co2Ppm: 0 });
});

test('只采用最新 CSV 修订，不使用更早 CSV 补旧字段', () => {
  const old = csv({ crop: '番茄', temperatureC: 19 }, '2026-10-08T18:00:00+08:00');
  const recent = csv({ temperatureC: 22 }, '2026-10-08T19:00:00+08:00', { id: 'csv-new' });
  const result = restore([recent, old], specs, {});
  assert.deepEqual(plain(result.patch), { temperatureC: 22 }); assert.equal(result.sources.temperatureC.evidenceId, 'csv-new');
});

test('更新的已核对表单优先，使用 situation 而非旧草稿值', () => {
  const submitted = agri({ temperatureC: 24 }); submitted.details.fields = [{ key: 'temperatureC', value: 22, source: 'USER_EDITED' }];
  const result = restore([csv({ temperatureC: 22, humidityPct: 92 }), submitted], specs, {});
  assert.deepEqual(plain(result.patch), { temperatureC: 24, humidityPct: 92 });
  assert.equal(result.sources.temperatureC.fieldSource, 'USER_EDITED');
});

test('CSV 比表单更新时采用 CSV 的最新值', () => {
  const result = restore([agri({ temperatureC: 24 }, '2026-10-08T18:00:00+08:00'), csv({ temperatureC: 22 })], specs, {});
  assert.equal(result.patch.temperatureC, 22); assert.equal(result.sources.temperatureC.evidenceId, 'csv-1');
});

test('字段来源与原文依据保留', () => {
  const evidence = csv({ temperatureC: 22 });
  evidence.details.fields[0].source = 'PARSED'; evidence.details.fields[0].rawText = '棚温二十二度';
  const result = restore([evidence], specs, {});
  assert.equal(result.draft.fields[0].source, 'PARSED'); assert.equal(result.draft.fields[0].rawText, '棚温二十二度');
  assert.equal(result.draft.fields[0].unit, '℃');
});

test('旧版仅 fields 的已核对 CSV 仍能恢复', () => {
  const evidence = csv({ temperatureC: 22 }); delete evidence.details.input;
  assert.equal(restore([evidence], specs, {}).patch.temperatureC, 22);
});

test('最新 CSV 未确认时提示待核对，不回退到旧 CSV', () => {
  const result = restore([csv({ temperatureC: 19 }), csv({ temperatureC: 22 }, '2026-10-08T20:00:00+08:00', { source: '用户上传，待核对' })], specs, {});
  assert.deepEqual(plain(result.patch), {}); assert.ok(result.sourceNotes[0].includes('待核对'));
});

test('有冲突的 CSV 不自动填入', () => {
  const evidence = csv({ temperatureC: 22 }); evidence.details.conflicts = ['温度存在两种取值'];
  assert.deepEqual(plain(restore([evidence], specs, {}).patch), {});
});

test('未知字段、对象、无效数值和空值均不填入', () => {
  const evidence = csv({ crop: { value: '番茄' }, temperatureC: 'warm', humidityPct: null, co2Ppm: NaN, unknown: 100, lightPpfd: '' });
  assert.deepEqual(plain(restore([evidence], specs, {}).patch), {});
});

test('证据和当前表单保持不变，未识别列仍供核对', () => {
  const evidence = csv({ temperatureC: 22 }); evidence.details.unrecognizedColumns = ['自定义列']; evidence.details.notes = ['请核对测点'];
  const before = JSON.stringify(evidence), current = { crop: '番茄' };
  const result = restore([evidence], specs, current);
  assert.equal(JSON.stringify(evidence), before); assert.deepEqual(current, { crop: '番茄' });
  assert.deepEqual(plain(result.draft.unrecognizedColumns), ['自定义列']); assert.ok(result.draft.notes.includes('请核对测点'));
});

test('不从图像、天气或模拟环境填入农情', () => {
  const result = restore([
    { type: 'IMAGE', label: '叶片', source: '已核对', details: { input: { temperatureC: 99 } } },
    { type: 'SCENARIO', label: '模拟环境', source: '已核对', details: { input: { temperatureC: 98 } } },
    { ...csv({}), details: { environment: { temperatureC: 97 }, fields: [], conflicts: [] } },
  ], specs, {});
  assert.deepEqual(plain(result.patch), {});
});

test('禁止特殊原型键进入表单', () => {
  const input = JSON.parse('{"__proto__":{"polluted":true},"constructor":"bad","prototype":"bad","temperatureC":22}');
  const maliciousSpecs = [...specs, ...['__proto__', 'constructor', 'prototype'].map(key => ({ key, label: key, kind: 'TEXT', group: 'test' }))];
  const result = restore([csv(input)], maliciousSpecs, {});
  assert.deepEqual(plain(result.patch), { temperatureC: 22 }); assert.equal({}.polluted, undefined);
});

let failures = 0;
console.log('Source: ' + path.relative(root, sourcePath));
console.log('Source SHA-256: ' + createHash('sha256').update(source).digest('hex'));
for (const item of tests) {
  try { item.body(); console.log('PASS ' + item.name); }
  catch (error) { failures++; console.error('FAIL ' + item.name + '\n' + (error.stack || error)); }
}
console.log(`${tests.length - failures}/${tests.length} passed`);
process.exitCode = failures ? 1 : 0;
