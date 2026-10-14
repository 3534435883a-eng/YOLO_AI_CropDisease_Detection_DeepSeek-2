import { readFile, writeFile, mkdir, rename, open, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { createReport } from './report.mjs';
import { DecisionError, validateGoal, selectStrategies } from './decision.mjs';

const routes = { analyze: '/v1/agri/analyze', evaluate: '/v1/agri/evaluate', report: '/v1/agri/report' };
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
const sha = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
export class ClientError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const fail = (code, message) => { throw new ClientError(code, message); };
const json = async path => JSON.parse(await readFile(path, 'utf8'));
async function save(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await rename(temporary, path);
}

export async function settings(env = process.env) {
  const paths = env.DUMATE_ENV_FILE ? [resolve(env.DUMATE_ENV_FILE)] : [
    fileURLToPath(new URL('../../../.env', import.meta.url)),
    join(env.DUMATE_PROJECT_DIR || 'E:/domate match', 'integrations', 'dumate', '.env'),
  ];
  const values = {};
  if (!env.DUMATE_TOKEN) {
    for (const path of paths) {
      try {
        const content = await readFile(path, 'utf8');
        for (const line of content.replace(/^\uFEFF/, '').split(/\r?\n/)) {
          const matched = /^\s*(DUMATE_TOKEN|DUMATE_PORT|DUMATE_ADAPTER_URL)\s*=\s*(.*?)\s*$/.exec(line);
          if (matched) values[matched[1]] = matched[2].replace(/^(['"])(.*)\1$/, '$2');
        }
        if (values.DUMATE_TOKEN) break;
      } catch (error) { if (error.code !== 'ENOENT') throw new ClientError('CONFIG_READ_FAILED', '无法读取本机凭证配置'); }
    }
  }
  const token = env.DUMATE_TOKEN || values.DUMATE_TOKEN || '';
  if (token.length < 32 || /[\r\n]/.test(token) || token.startsWith('replace-with-')) fail('CREDENTIAL_MISSING', '请由管理员配置DUMATE_TOKEN或DUMATE_ENV_FILE；不要把密钥发送到对话');
  const base = new URL(env.DUMATE_ADAPTER_URL || values.DUMATE_ADAPTER_URL || `http://127.0.0.1:${values.DUMATE_PORT || 9998}`);
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(base.hostname);
  if (!['http:', 'https:'].includes(base.protocol) || (!local && base.protocol !== 'https:') || base.username || base.password || base.search || base.hash) fail('INVALID_CONFIG', '适配地址需为本机HTTP或受控HTTPS，不允许URL凭证、查询串或片段');
  const timeoutMs = Number(env.DUMATE_CLIENT_TIMEOUT_MS || 90000);
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 125000) fail('INVALID_CONFIG', '客户端预算需为100–125000ms');
  return { token, base: base.href.replace(/\/$/, ''), timeoutMs };
}

export async function request(operation, input, key, config) {
  if (!Object.hasOwn(routes, operation)) fail('CAPABILITY_FORBIDDEN', '只开放analyze/evaluate/report');
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(key)) fail('INVALID_KEY', '调用键格式错误');
  const signal = AbortSignal.timeout(config.timeoutMs);
  try {
    const response = await fetch(`${config.base}${routes[operation]}`, {
      method: 'POST', headers: { authorization: `Bearer ${config.token}`, 'content-type': 'application/json', 'idempotency-key': key },
      body: JSON.stringify(input), redirect: 'error', signal,
    });
    const chunks = []; let bytes = 0;
    const reader = response.body.getReader();
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      bytes += value.length;
      if (bytes > 2 * 1024 * 1024) { await reader.cancel(); fail('RESPONSE_TOO_LARGE', '响应超过2MiB'); }
      chunks.push(Buffer.from(value));
    }
    let result;
    try { result = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { fail('INVALID_RESPONSE', '接口未返回可解析JSON；不输出原始错误页'); }
    if (response.status !== 200 || result.ok !== true) {
      const code = typeof result.error?.code === 'string' && /^[A-Z0-9_]+$/.test(result.error.code) ? result.error.code : 'API_FAILED';
      fail(code, `农业接口失败（HTTP ${response.status}）；本次任务停止，不换键自动重试`);
    }
    if (!uuid(result.requestId) || !result.data || typeof result.data !== 'object') fail('INVALID_RESPONSE', '响应缺少真实UUID或data');
    const { resultSha256, ...data } = result.data;
    if (sha(data) !== resultSha256) fail('EVIDENCE_HASH_MISMATCH', '返回结果摘要不一致');
    return result;
  } catch (error) {
    if (error instanceof ClientError) throw error;
    fail(signal.aborted ? 'RESULT_INDETERMINATE' : 'CONNECTION_FAILED', '连接失败或结果不确定；不要更换调用键自动重发POST');
  }
}

export async function prepare(outdir, text, seed = 20250419, days = 56, goal = null) {
  if (typeof text !== 'string' || !text.trim() || text.length > 6000) fail('INVALID_INPUT', '农情原文需为1–6000字符');
  if (!Number.isInteger(seed) || seed < 0 || seed > 2147483647 || !Number.isInteger(days) || days < 1 || days > 56) fail('INVALID_INPUT', 'seed需为非负32位整数，days需为1–56');
  const experimentPlan = goal === null ? null : validateGoal(goal);
  await mkdir(outdir, { recursive: true });
  const path = join(outdir, 'task.json');
  const task = { version: 1, taskId: randomUUID(), createdAt: new Date().toISOString(), text, seed, days,
    scenarioBinding: 'REFERENCE_ONLY_NOT_USER_CONDITIONED', attempts: {} };
  if (experimentPlan) Object.assign(task, { experimentPlan, experimentPlanSha256: sha(experimentPlan) });
  let handle;
  try { handle = await open(path, 'wx', 0o600); await handle.writeFile(`${JSON.stringify(task, null, 2)}\n`); }
  catch (error) { if (error.code === 'EEXIST') fail('TASK_EXISTS', '目录已有任务；继续原任务，不覆盖原文或UUID'); throw error; }
  finally { await handle?.close(); }
  return { taskId: task.taskId, outdir, state: 'PREPARED', scenarioBinding: task.scenarioBinding,
    goalEnabled: Boolean(experimentPlan), experimentPlanSha256: task.experimentPlanSha256 };
}

function checkPlan(task) {
  if (task.experimentPlan !== undefined) {
    validateGoal(task.experimentPlan);
    if (sha(task.experimentPlan) !== task.experimentPlanSha256) fail('EVIDENCE_HASH_MISMATCH', '实验目标已变更；请保留原任务并另建任务');
  }
}
async function stored(operation, outdir, task) {
  const result = await json(join(outdir, `${operation}-response.json`));
  const { resultSha256, ...data } = result.data || {};
  if (result.ok !== true || !uuid(result.requestId) || sha(data) !== resultSha256 ||
      task.attempts[operation]?.state !== 'SUCCEEDED' || task.attempts[operation].requestId !== result.requestId ||
      task.attempts[operation].resultSha256 !== resultSha256) fail('EVIDENCE_HASH_MISMATCH', '任务存档、响应编号或结果摘要不一致');
  return result;
}
async function decisionEvidence(outdir, task) {
  if (!task.experimentPlan) fail('GOAL_MISSING', '本任务没有已确认实验目标；需先澄清条件并新建目标任务');
  if (task.attempts.evaluate?.state !== 'SUCCEEDED') fail('DEPENDENCY_MISSING', '请先完成evaluate');
  const a = (await stored('analyze', outdir, task)).data, e = (await stored('evaluate', outdir, task)).data;
  if (a.analysisId !== task.attempts.analyze.requestId || e.analysisId !== a.analysisId || e.evaluationId !== task.attempts.evaluate.requestId || e.batchId !== e.evaluationId ||
      e.seed !== task.seed || e.days !== task.days || e.scenarioBinding !== task.scenarioBinding) fail('EVIDENCE_MISMATCH', '评测存档的场景、参数或关联编号已改变');
  return { a, e, selection: selectStrategies(task.experimentPlan, e) };
}
async function verifiedDecision(outdir, task) {
  if (task.attempts.decide?.state !== 'SUCCEEDED') fail('DEPENDENCY_MISSING', '目标任务请先完成本机decide筛选，再生成报告');
  const { a, e, selection } = await decisionEvidence(outdir, task);
  const d = (await stored('decide', outdir, task)).data;
  if (d.decisionId !== task.attempts.decide.requestId || d.taskId !== task.taskId || d.analysisId !== a.analysisId ||
      d.evaluationId !== e.evaluationId || d.experimentPlanSha256 !== task.experimentPlanSha256 ||
      d.evaluationSha256 !== e.resultSha256 || sha(d.selection) !== sha(selection)) fail('EVIDENCE_MISMATCH', '决策结果与目标/评测复算不一致');
  return d;
}

export async function step(operation, outdir, config) {
  if (!Object.hasOwn(routes, operation) && operation !== 'decide') fail('CAPABILITY_FORBIDDEN', '能力不在固定白名单');
  const lockPath = join(outdir, '.task.lock'); let lock;
  try { lock = await open(lockPath, 'wx', 0o600); }
  catch (error) { if (error.code === 'EEXIST') fail('TASK_LOCKED', '本任务正在执行或曾中断，人工核对后再继续'); throw error; }
  try {
    const path = join(outdir, 'task.json'), task = await json(path);
    if (!uuid(task.taskId)) fail('INVALID_TASK', '任务UUID无效');
    checkPlan(task);
    const old = task.attempts[operation];
    if (old?.state === 'SUCCEEDED') {
      if (operation === 'decide' || (operation === 'report' && task.experimentPlan)) await verifiedDecision(outdir, task);
      return { result: task.experimentPlan ? await stored(operation, outdir, task) : await json(join(outdir, `${operation}-response.json`)), replay: true };
    }
    if (old) fail('RESULT_INDETERMINATE', '本步骤曾失败或中断；保持原键并人工核查，不自动重放');
    if (operation === 'decide') {
      const { a, e, selection } = await decisionEvidence(outdir, task);
      const id = randomUUID();
      const data = { decisionId: id, taskId: task.taskId, analysisId: a.analysisId, evaluationId: e.evaluationId,
        experimentPlanSha256: task.experimentPlanSha256, evaluationSha256: e.resultSha256, selection };
      data.resultSha256 = sha(data);
      const result = { ok: true, requestId: id, execution: 'LOCAL_NO_HTTP', data };
      await save(join(outdir, 'decide-response.json'), result);
      task.attempts.decide = { state: 'SUCCEEDED', execution: 'LOCAL_NO_HTTP', key: `${task.taskId}-decide`,
        requestId: id, resultSha256: data.resultSha256, finishedAt: new Date().toISOString() };
      await save(path, task);
      return { result, replay: false };
    }
    const decision = operation === 'report' && task.experimentPlan ? await verifiedDecision(outdir, task) : null;
    let input;
    if (operation === 'analyze') input = { text: task.text };
    else {
      if (task.attempts.analyze?.state !== 'SUCCEEDED') fail('DEPENDENCY_MISSING', '请先完成analyze');
      const analysis = (await json(join(outdir, 'analyze-response.json'))).data;
      if (operation === 'evaluate') input = { analysisId: analysis.analysisId, acknowledgeReferenceScenario: true, seed: task.seed, days: task.days };
      else {
        if (task.attempts.evaluate?.state !== 'SUCCEEDED') fail('DEPENDENCY_MISSING', '请先完成evaluate');
        const evaluation = (await json(join(outdir, 'evaluate-response.json'))).data;
        input = { analysisId: analysis.analysisId, evaluationId: evaluation.evaluationId };
      }
    }
    const attempt = { key: `${task.taskId}-${operation}`, inputSha256: sha(input), state: 'PENDING', startedAt: new Date().toISOString() };
    task.attempts[operation] = attempt;
    await save(path, task);
    try {
      const result = await request(operation, input, attempt.key, config);
      if (operation === 'analyze' && (!Array.isArray(result.data.draft?.fields) || result.data.analysisId !== result.requestId)) fail('INVALID_RESPONSE', '解析结果缺少字段或编号不匹配');
      if (operation === 'evaluate' && (result.data.evaluationId !== result.requestId || result.data.batchId !== result.requestId || result.data.analysisId !== input.analysisId || result.data.seed !== task.seed || result.data.days !== task.days || result.data.scenarioBinding !== task.scenarioBinding)) fail('EVIDENCE_MISMATCH', '评测参数或关联编号不一致');
      if (operation === 'report' && (result.data.reportId !== result.requestId || result.data.analysisId !== input.analysisId || result.data.evaluationId !== input.evaluationId)) fail('EVIDENCE_MISMATCH', '报告关联编号不一致');
      await save(join(outdir, `${operation}-response.json`), result);
      attempt.state = 'SUCCEEDED'; attempt.requestId = result.requestId; attempt.resultSha256 = result.data.resultSha256;
      attempt.finishedAt = new Date().toISOString();
      await save(path, task);
      if (operation === 'report') {
        const a = (await json(join(outdir, 'analyze-response.json'))).data;
        const e = (await json(join(outdir, 'evaluate-response.json'))).data;
        if (sha(result.data.matrix) !== sha(e.outcomes)) fail('EVIDENCE_MISMATCH', '报告改写了矩阵');
        const rendered = createReport(a, e, result.data, decision);
        await writeFile(join(outdir, 'report.md'), rendered.markdown, 'utf8');
        await writeFile(join(outdir, 'report.html'), rendered.html, 'utf8');
        await save(join(outdir, 'comparison-scopes.json'), rendered.leaders);
      }
      return { result, replay: false };
    } catch (error) {
      attempt.state = 'FAILED'; attempt.errorCode = error.code || 'LOCAL_ERROR'; attempt.finishedAt = new Date().toISOString();
      await save(path, task); throw error;
    }
  } finally { await lock.close(); await unlink(lockPath); }
}

function options(args) {
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    const flag = args[i];
    if (!['--outdir', '--text-file', '--goal-file', '--seed', '--days'].includes(flag) || !args[i + 1] || Object.hasOwn(values, flag)) fail('INVALID_ARGUMENT', '只支持--outdir/--text-file/--goal-file/--seed/--days；不要传入用户提供的URL或接口路径');
    values[flag] = args[i + 1];
  }
  return values;
}
export async function main(args) {
  const command = args[0];
  if (!['prepare', 'analyze', 'evaluate', 'decide', 'report', 'run', 'check'].includes(command)) fail('INVALID_ARGUMENT', '命令：prepare/analyze/evaluate/decide/report/run/check');
  const flags = options(args.slice(1));
  if (flags['--goal-file'] && command !== 'prepare' && !(command === 'run' && flags['--text-file'])) fail('INVALID_ARGUMENT', '实验目标必须在prepare时冻结；更改目标需新任务');
  if (command !== 'prepare' && !(command === 'run' && flags['--text-file']) &&
      ['--text-file', '--seed', '--days'].some(k => flags[k])) fail('INVALID_ARGUMENT', '原文、seed和days必须在prepare时冻结；后续步骤只接受任务目录');
  if (command === 'check') {
    const config = await settings();
    try { await request('analyze', { text: '' }, `check-${randomUUID()}`, config); }
    catch (error) { if (error.code === 'INVALID_INPUT') return { authenticated: true, upstreamInvoked: false, adapter: config.base }; throw error; }
    fail('INVALID_RESPONSE', '预检未得到预期参数拒绝');
  }
  if (!flags['--outdir']) fail('INVALID_ARGUMENT', '需要显式--outdir以保存可追溯任务');
  const outdir = resolve(flags['--outdir']);
  if (command === 'prepare' || (command === 'run' && flags['--text-file'])) {
    if (!flags['--text-file']) fail('INVALID_ARGUMENT', '请将用户原文保存为UTF-8文件，并使用--text-file');
    const text = await readFile(resolve(flags['--text-file']), 'utf8');
    const goal = flags['--goal-file'] ? await json(resolve(flags['--goal-file'])) : null;
    const result = await prepare(outdir, text.trim(), Number(flags['--seed'] || 20250419), Number(flags['--days'] || 56), goal);
    if (command === 'prepare') return result;
  }
  const config = command === 'decide' ? undefined : await settings();
  if (command === 'run') {
    await step('analyze', outdir, config);
    await step('evaluate', outdir, config);
    if ((await json(join(outdir, 'task.json'))).experimentPlan) await step('decide', outdir);
    await step('report', outdir, config);
    const task = await json(join(outdir, 'task.json'));
    return { completed: true, taskId: task.taskId, outdir, calls: task.attempts, report: join(outdir, 'report.html') };
  }
  const { result, replay } = await step(command, outdir, config);
  return { operation: command, replay, requestId: result.requestId, data: result.data, outdir };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify(await main(process.argv.slice(2)), null, 2)); }
  catch (error) {
    console.error(JSON.stringify({ ok: false, error: { code: error.code || 'LOCAL_ERROR', message: error instanceof ClientError || error instanceof DecisionError ? error.message : '本机配置、任务文件或输出操作失败；未显示敏感内容' } }));
    process.exitCode = 1;
  }
}
