#!/usr/bin/env node
'use strict';

// Runs the actual TypeScript store against existing Vue/Pinia dependencies.
// Only HTTP and browser storage/timers are replaced; no new dependency is required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createHash } = require('node:crypto');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '..');
const frontend = path.join(root, 'YOLO_AI_CropDisease_Detection_Vue');
const requireFrontend = createRequire(path.join(frontend, 'package.json'));
const ts = requireFrontend('typescript');
const vue = requireFrontend('vue');
const pinia = requireFrontend('pinia');
const storePath = path.join(frontend, 'src', 'stores', 'greenhouse.ts');
const source = fs.readFileSync(storePath, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
  fileName: storePath,
}).outputText;
const plain = value => JSON.parse(JSON.stringify(value));
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };

function memoryStorage() {
  const values = new Map();
  return {
    getItem: key => values.has(String(key)) ? values.get(String(key)) : null,
    setItem: (key, value) => values.set(String(key), String(value)),
    removeItem: key => values.delete(String(key)),
    clear: () => values.clear(),
  };
}

function frame(cursor, version = cursor + 1) {
  return {
    cursor, at: `2025-01-01T${String(Math.floor(cursor / 2)).padStart(2, '0')}:${cursor % 2 ? '30' : '00'}:00`,
    updates: [], predictedHeightCm: 20 + cursor,
    scenario: { version, tick: cursor },
  };
}

function run(id, cursor = 0, version = cursor + 1, history) {
  const current = frame(cursor, version);
  return {
    runId: id, cursor, current, frames: history === undefined ? [current] : history,
    events: [], playback: { playing: false, stepCount: 1, waitingForAi: false, restored: false },
    year: 2025, totalSlots: 100, finished: false,
  };
}

function task(id, runId, updatedAt = '2026-10-08T10:00:00+08:00', extra = {}) {
  return {
    id, title: '番茄农情管理', crop: '番茄', question: '', simulationRunId: runId,
    createdAt: '2026-10-08T09:00:00+08:00', updatedAt,
    evidence: [], turns: [], actions: [], observations: [], ...extra,
  };
}

function harness() {
  const calls = [], hooks = {}, timers = new Map(), serverRuns = new Map(), serverTasks = new Map();
  const localStorage = memoryStorage(), sessionStorage = memoryStorage();
  let timerId = 0, taskId = 0, updateSequence = 0, now = 10000;
  class ClockDate extends Date { static now() { return now; } }
  const defaults = {
    getM3Live: (id, compact, after) => {
      if (!serverRuns.has(id)) throw new Error('Unknown test run: ' + id);
      const value = plain(serverRuns.get(id));
      if (compact) { value.frames = []; value.events = []; }
      else if (after !== undefined) value.frames = value.frames.filter(f => f.cursor > after);
      return value;
    },
    startM3Live: () => run('new-run'),
    setM3Playback: (id, playing, stepCount) => {
      const value = plain(serverRuns.get(id));
      value.playback = { ...value.playback, playing, stepCount };
      serverRuns.set(id, value);
      return value;
    },
    createFarmTask: input => {
      const value = task('task-' + (++taskId), input.simulationRunId, undefined, input);
      serverTasks.set(value.id, plain(value));
      return value;
    },
    getFarmTask: id => {
      if (!serverTasks.has(id)) throw new Error('Unknown test task: ' + id);
      return plain(serverTasks.get(id));
    },
    updateFarmTask: (id, input) => saveTask(id, input),
    addFarmEvidence: (id, input) => append(id, 'evidence', input),
    addFarmTurn: (id, input) => append(id, 'turns', input),
    addFarmAction: (id, input) => append(id, 'actions', input),
    addFarmObservation: (id, input) => append(id, 'observations', input),
    updateFarmAction: (id, actionId, status) => {
      const value = plain(serverTasks.get(id));
      const action = value.actions.find(item => item.id === actionId);
      if (!action) throw new Error('Unknown test action: ' + actionId);
      action.status = status;
      return saveTask(id, value);
    },
  };
  function saveTask(id, input) {
    const value = { ...plain(serverTasks.get(id)), ...plain(input), updatedAt: new Date(1800000000000 + ++updateSequence * 1000).toISOString() };
    serverTasks.set(id, plain(value));
    return value;
  }
  function append(id, collection, input) {
    const value = plain(serverTasks.get(id));
    // Models the server's idempotent record projection. This does not assert backend behaviour.
    if (!input.id || !value[collection].some(item => item.id === input.id)) value[collection].push(plain(input));
    return saveTask(id, value);
  }
  const api = Object.fromEntries(Object.keys(defaults).map(name => [name, (...args) => {
    calls.push({ name, args: plain(args) });
    try { return Promise.resolve((hooks[name] || defaults[name])(...args)); }
    catch (error) { return Promise.reject(error); }
  }]));
  const context = vm.createContext({
    localStorage, sessionStorage, Date: ClockDate, console,
    setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id),
  });
  const module = { exports: {} };
  const load = id => {
    if (id === 'vue') return vue;
    if (id === 'pinia') return pinia;
    if (id === '/@/api/m3/live' || id === '/@/api/agent/tasks') return api;
    throw new Error('Unexpected store import: ' + id);
  };
  vm.runInContext('(function(require,module,exports){\n' + compiled + '\n})', context, { filename: storePath })(load, module, module.exports);
  const store = module.exports.useGreenhouseStore(pinia.createPinia());
  return {
    store, calls, hooks, timers, serverRuns, serverTasks, localStorage, sessionStorage,
    count: name => calls.filter(call => call.name === name).length,
    advanceTime: milliseconds => { now += milliseconds; },
    dispose: () => store.$dispose(),
  };
}

const tests = [];
function test(name, body) { tests.push({ name, body }); }

test('增量历史从最后连续游标补齐 compact 跳帧，并合并事件', async h => {
  const first = run('A', 1, 2, [frame(0), frame(1)]);
  const event = { type: 'OBSERVATION', at: frame(1).at, plantId: 'p1', code: 'height', afterCm: 21 };
  first.events = [event]; h.store.acceptRun(first, true);
  h.store.acceptRun(run('A', 4, 5, []));
  const complete = run('A', 4, 5, [frame(0), frame(1), frame(2), frame(3), frame(4)]);
  complete.events = [event]; complete.current.updates = [event]; h.serverRuns.set('A', complete);
  await h.store.refreshRun();
  assert.equal(h.calls.find(c => c.name === 'getM3Live').args[2], 1);
  assert.deepEqual(Array.from(h.store.frames, f => f.cursor), [0, 1, 2, 3, 4]);
  assert.equal(h.store.events.length, 1);
});

test('没有从 0 开始的连续历史时请求完整历史，忽略未来帧', async h => {
  h.store.acceptRun(run('A', 3, 4, [frame(2), frame(3), frame(8)]), true);
  assert.deepEqual(Array.from(h.store.frames, f => f.cursor), [2, 3]);
  h.serverRuns.set('A', run('A', 3, 4, [frame(0), frame(1), frame(2), frame(3)]));
  await h.store.refreshRun();
  assert.equal(h.calls.find(c => c.name === 'getM3Live').args[2], null);
  assert.deepEqual(Array.from(h.store.frames, f => f.cursor), [0, 1, 2, 3]);
});

test('同一轮并发刷新只发送一次 GET', async h => {
  h.store.acceptRun(run('A'), true);
  const pending = deferred(); h.hooks.getM3Live = () => pending.promise;
  const one = h.store.refreshRun(), two = h.store.refreshRun();
  assert.equal(h.count('getM3Live'), 1);
  pending.resolve(run('A', 1, 2, [frame(0), frame(1)]));
  await Promise.all([one, two]);
  assert.equal(h.store.run.cursor, 1);
});

test('拒绝旧游标、旧场景版本、错误运行和无效数据', async h => {
  h.store.acceptRun(run('A', 5, 8), true);
  assert.equal(h.store.acceptRun(run('A', 4, 9)), false);
  assert.equal(h.store.acceptRun(run('A', 6, 7)), false);
  assert.equal(h.store.acceptRun(run('B', 6, 9)), false);
  assert.equal(h.store.acceptRun({ runId: 'A' }), false);
  assert.equal(h.store.run.cursor, 5);
  assert.equal(h.store.run.current.scenario.version, 8);
});

test('切换大棚后旧 GET 不覆盖新运行或错误状态', async h => {
  h.store.acceptRun(run('A'), true);
  const old = deferred(); h.hooks.getM3Live = id => id === 'A' ? old.promise : run('B', 2, 3);
  const readA = h.store.refreshRun();
  await h.store.attachRun('B');
  old.resolve(run('A', 9, 10)); await readA;
  assert.equal(h.store.activeRunId, 'B'); assert.equal(h.store.run.cursor, 2); assert.equal(h.store.error, '');
});

test('暂停命令隔离命令之前在途的运行响应', async h => {
  const current = run('A'); current.playback.playing = true;
  h.store.acceptRun(current, true); h.serverRuns.set('A', current);
  const old = deferred(); h.hooks.getM3Live = () => old.promise;
  const read = h.store.refreshRun(); await h.store.setPlayback(false);
  const late = run('A', 4, 5); late.playback.playing = true;
  old.resolve(late); await read;
  assert.equal(h.store.run.playback.playing, false); assert.equal(h.store.run.cursor, 0);
  await assert.rejects(h.store.setPlayback(true, 2), /不支持的回放步长/);
  assert.equal(h.count('setM3Playback'), 1);
});

test('多个订阅共享 GET 和定时器，取消订阅不暂停服务器时钟', async h => {
  const current = run('A'); current.playback.playing = true;
  h.store.acceptRun(current, true); h.serverRuns.set('A', current);
  const unsubscribeOne = h.store.subscribe(), unsubscribeTwo = h.store.subscribe();
  await flush();
  assert.equal(h.count('getM3Live'), 1); assert.equal(h.timers.size, 1);
  unsubscribeOne(); assert.equal(h.timers.size, 1);
  unsubscribeTwo(); unsubscribeTwo();
  assert.equal(h.timers.size, 0); assert.equal(h.count('setM3Playback'), 0);
  assert.equal(h.store.run.playback.playing, true);
});

test('取消最后订阅后在途 GET 完成也不会重新启动轮询', async h => {
  h.store.acceptRun(run('A'), true);
  const pending = deferred(); h.hooks.getM3Live = () => pending.promise;
  const unsubscribe = h.store.subscribe(); unsubscribe(); pending.resolve(run('A'));
  await flush(); assert.equal(h.timers.size, 0); assert.equal(h.count('setM3Playback'), 0);
});

test('任务读取拒绝跨大棚关联，较旧任务响应不会回滚新记录', async h => {
  h.store.acceptRun(run('A'), true);
  h.serverTasks.set('case-A', task('case-A', 'A', '2026-10-08T11:00:00+08:00', { question: '新问题' }));
  await h.store.attachTask('case-A');
  h.serverTasks.set('case-A', task('case-A', 'A', '2026-10-08T10:00:00+08:00', { question: '旧问题' }));
  await h.store.refreshTask(); assert.equal(h.store.task.question, '新问题');
  h.serverTasks.set('case-B', task('case-B', 'B'));
  await assert.rejects(h.store.attachTask('case-B'), /另一轮大棚/);
  assert.equal(h.store.task.id, 'case-A');
});

test('切换大棚后旧任务读取和在途写入不污染新任务，排队旧写入被拒绝', async h => {
  h.store.acceptRun(run('A'), true); h.serverTasks.set('case-A', task('case-A', 'A'));
  await h.store.attachTask('case-A');
  const pendingWrite = deferred(); h.hooks.addFarmEvidence = () => pendingWrite.promise;
  const first = h.store.addEvidence({ id: 'e1', type: 'IMAGE', label: '叶片', source: 'USER' });
  const firstRejected = assert.rejects(first, /任务已切换/); await flush();
  const second = h.store.addAction({ id: 'a1', type: 'HUMAN', title: '复查', status: 'PENDING' });
  const secondRejected = assert.rejects(second, /任务已切换/);
  const pendingRead = deferred(); h.hooks.getFarmTask = () => pendingRead.promise;
  const read = h.store.refreshTask(); const readRejected = assert.rejects(read, /任务已切换/);
  h.store.acceptRun(run('B'), true);
  pendingWrite.resolve(task('case-A', 'A', undefined, { evidence: [{ id: 'e1' }] }));
  pendingRead.resolve(task('case-A', 'A'));
  await firstRejected; await secondRejected; await readRejected;
  assert.equal(h.count('addFarmAction'), 0); assert.equal(h.store.task, null);
  assert.equal(h.store.activeRunId, 'B');
});

test('并发任务写入按顺序发送，幂等服务器投影不会生成重复 UI 记录', async h => {
  h.store.acceptRun(run('A'), true); h.serverTasks.set('case-A', task('case-A', 'A')); await h.store.attachTask('case-A');
  const one = h.store.addObservation({ id: 'review-1', note: '叶片已复查' });
  const duplicate = h.store.addObservation({ id: 'review-1', note: '叶片已复查' });
  const evidence = h.store.addEvidence({ id: 'photo-1', type: 'IMAGE', label: '叶片', source: 'USER' });
  await Promise.all([one, duplicate, evidence]);
  assert.equal(h.store.task.observations.length, 1); assert.equal(h.store.task.evidence.length, 1);
  assert.deepEqual(h.calls.filter(c => /^addFarm/.test(c.name)).map(c => c.name), ['addFarmObservation', 'addFarmObservation', 'addFarmEvidence']);
  assert.equal(h.calls.find(c => c.name === 'addFarmObservation').args[1].id, 'review-1');
  assert.equal(h.store.linkedQuery().taskId, 'case-A'); assert.equal(h.store.linkedQuery().liveRun, 'A');
});

test('并发 ensureTask 只创建一个任务并保留各调用的最新问题', async h => {
  h.store.acceptRun(run('A'), true);
  const create = deferred(); h.hooks.createFarmTask = () => create.promise;
  const first = h.store.ensureTask('连续阴雨'), second = h.store.ensureTask('叶片有斑');
  assert.equal(h.count('createFarmTask'), 1);
  const value = task('case-A', 'A', undefined, { question: '连续阴雨' }); h.serverTasks.set(value.id, value); create.resolve(value);
  await Promise.all([first, second]);
  assert.equal(h.store.task.id, 'case-A'); assert.equal(h.store.task.question, '叶片有斑');
  assert.equal(h.count('createFarmTask'), 1); assert.equal(h.count('updateFarmTask'), 1);
});

test('初始任务创建中切换运行时排队写入被拒绝，不写到新任务', async h => {
  h.store.acceptRun(run('A'), true);
  const create = deferred(); h.hooks.createFarmTask = () => create.promise;
  const write = h.store.addObservation({ id: 'review-1', note: '旧大棚复查' });
  const rejected = assert.rejects(write, /任务已切换/); await flush();
  h.store.acceptRun(run('B'), true); create.resolve(task('case-A', 'A', undefined, { question: '旧大棚问题' }));
  await rejected;
  assert.equal(h.count('addFarmObservation'), 0); assert.equal(h.store.task, null);
});

test('同一大棚切换农情任务时旧任务读取不能覆盖当前任务', async h => {
  h.store.acceptRun(run('A'), true);
  h.serverTasks.set('case-A1', task('case-A1', 'A'));
  h.serverTasks.set('case-A2', task('case-A2', 'A'));
  await h.store.attachTask('case-A1');
  const pending = deferred();
  h.hooks.getFarmTask = id => id === 'case-A1' ? pending.promise : plain(h.serverTasks.get(id));
  const old = h.store.refreshTask();
  await h.store.attachTask('case-A2');
  pending.resolve(task('case-A1', 'A'));
  await old.catch(() => undefined);
  assert.equal(h.store.task.id, 'case-A2');
});

test('创建任务时切换大棚向旧调用者报错，不返回可误用于新运行的旧任务', async h => {
  h.store.acceptRun(run('A'), true);
  const create = deferred(); h.hooks.createFarmTask = () => create.promise;
  const old = h.store.ensureTask('旧大棚问题');
  const rejected = assert.rejects(old, /任务已切换/);
  h.store.acceptRun(run('B'), true); create.resolve(task('case-A', 'A', undefined, { question: '旧大棚问题' }));
  await rejected; assert.equal(h.store.task, null); assert.equal(h.store.activeRunId, 'B');
});

test('同一大棚切换任务后在途和排队旧写入被隔离，新任务写入仍可执行', async h => {
  h.store.acceptRun(run('A'), true);
  h.serverTasks.set('case-A1', task('case-A1', 'A')); h.serverTasks.set('case-A2', task('case-A2', 'A'));
  await h.store.attachTask('case-A1');
  const pending = deferred(); h.hooks.addFarmEvidence = () => pending.promise;
  const old = h.store.addEvidence({ id: 'e1', type: 'IMAGE', label: '旧任务照片', source: 'USER' });
  const oldRejected = assert.rejects(old, /任务已切换/); await flush();
  const queued = h.store.addAction({ id: 'a1', type: 'HUMAN', title: '旧任务复查', status: 'PENDING' });
  const queuedRejected = assert.rejects(queued, /任务已切换/);
  await h.store.attachTask('case-A2');
  pending.resolve(task('case-A1', 'A', undefined, { evidence: [{ id: 'e1' }] }));
  await oldRejected; await queuedRejected;
  assert.equal(h.count('addFarmAction'), 0); assert.equal(h.store.task.id, 'case-A2');
  await h.store.addObservation({ id: 'review-new', note: '当前任务复查' });
  assert.equal(h.store.task.observations.length, 1);
  assert.equal(h.calls.find(c => c.name === 'addFarmObservation').args[0], 'case-A2');
});

test('新大棚任务不等待另一大棚旧任务创建完成', async h => {
  h.store.acceptRun(run('A'), true);
  const oldCreate = deferred();
  h.hooks.createFarmTask = input => input.simulationRunId === 'A' ? oldCreate.promise : task('case-B', 'B', undefined, input);
  const old = h.store.ensureTask('旧问题'); const oldRejected = assert.rejects(old, /任务已切换/);
  h.store.acceptRun(run('B'), true);
  const current = await h.store.ensureTask('新问题');
  assert.equal(h.count('createFarmTask'), 2); assert.equal(current.id, 'case-B');
  oldCreate.resolve(task('case-A', 'A', undefined, { question: '旧问题' })); await oldRejected;
  assert.equal(h.store.task.id, 'case-B'); assert.equal(h.store.task.question, '新问题');
});

test('同一大棚切换任务后在途问题更新不会返回或展示旧任务', async h => {
  h.store.acceptRun(run('A'), true);
  h.serverTasks.set('case-A1', task('case-A1', 'A')); h.serverTasks.set('case-A2', task('case-A2', 'A'));
  await h.store.attachTask('case-A1');
  const pending = deferred(); h.hooks.updateFarmTask = () => pending.promise;
  const old = h.store.ensureTask('更新问题'); const oldRejected = assert.rejects(old, /任务已切换/);
  await h.store.attachTask('case-A2');
  pending.resolve(task('case-A1', 'A', undefined, { question: '更新问题' })); await oldRejected;
  assert.equal(h.store.task.id, 'case-A2'); assert.equal(h.store.task.question, '');
});

(async () => {
  let failures = 0;
  const filter = process.argv[2];
  const selected = filter ? tests.filter(item => item.name.includes(filter)) : tests;
  if (!selected.length) throw new Error('No matching test: ' + filter);
  console.log('Source: ' + path.relative(root, storePath));
  console.log('Source SHA-256: ' + createHash('sha256').update(source).digest('hex'));
  for (const item of selected) {
    const h = harness();
    try { await item.body(h); console.log('PASS ' + item.name); }
    catch (error) { failures++; console.error('FAIL ' + item.name + '\n' + (error.stack || error)); }
    finally { h.dispose(); }
  }
  console.log(`${selected.length - failures}/${selected.length} passed`);
  process.exitCode = failures ? 1 : 0;
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
