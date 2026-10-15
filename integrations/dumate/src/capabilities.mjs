import { ApiError } from './config.mjs';
import { digest } from './store.mjs';

const uuid = { type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' };
export const SCHEMAS = {
  'agri.analyze': { type: 'object', additionalProperties: false, required: ['text'], properties: {
    text: { type: 'string', minLength: 1, maxLength: 6000 },
  } },
  'agri.evaluate': { type: 'object', additionalProperties: false, required: ['analysisId', 'acknowledgeReferenceScenario'], properties: {
    analysisId: uuid, acknowledgeReferenceScenario: { type: 'boolean', enum: [true] },
    seed: { type: 'integer', minimum: 0, maximum: 2147483647, default: 20250419 },
    days: { type: 'integer', minimum: 1, maximum: 56, default: 56 },
  } },
  'agri.report': { type: 'object', additionalProperties: false, required: ['analysisId', 'evaluationId'], properties: {
    analysisId: uuid, evaluationId: uuid,
  } },
};

export function validate(capability, input) {
  const schema = SCHEMAS[capability];
  if (!schema || !input || typeof input !== 'object' || Array.isArray(input)) throw new ApiError(400, 'INVALID_INPUT', '请求必须是 JSON 对象');
  for (const key of Object.keys(input)) {
    if (!Object.hasOwn(schema.properties, key)) throw new ApiError(400, 'INVALID_INPUT', `不允许参数 ${key.slice(0, 60)}`);
  }
  for (const key of schema.required) if (!Object.hasOwn(input, key)) throw new ApiError(400, 'INVALID_INPUT', `缺少参数 ${key}`);
  for (const [key, value] of Object.entries(input)) {
    const rule = schema.properties[key];
    const bad = (rule.type === 'integer' && (!Number.isInteger(value) || value < rule.minimum || value > rule.maximum)) ||
      (rule.type === 'boolean' && typeof value !== 'boolean') ||
      (rule.type === 'string' && (typeof value !== 'string' || !value.trim() || value.length < (rule.minLength || 0) ||
        value.length > (rule.maxLength || 10000) || (rule.pattern && !new RegExp(rule.pattern).test(value)))) ||
      (rule.enum && !rule.enum.includes(value));
    if (bad) throw new ApiError(400, 'INVALID_INPUT', `参数 ${key} 超出允许格式或范围`);
  }
}

export const STRATEGIES = {
  P0_NONE: '不做调控', P1_FIXED_MANUAL: '人工定时管理', P2_RULE_ENGINE: '规则自动调控',
  P3_AGENT: '前瞻择优调控（不调用LLM）', P4_PID_PROPORTIONAL: '离线消融：仅P',
  P5_PID_PI: '离线消融：P+I', P6_PID_FULL: '离线消融：P+I+D',
};
export const METRICS = {
  marketableYieldKg: 'kg', waterUsedM3: 'm³', energyKWh: 'kWh', costYuan: '元', profitYuan: '元',
  highTemperatureMinutes: '分钟', highHumidityMinutes: '分钟', diseasePressureIntegral: '模型积分',
  constraintViolations: '次', finalSeverityTotal: '模型严重度',
};
export const BOUNDARIES = [
  'M3 是公开历史观测的顺序回放，不是当前实棚传感器流。',
  '温室设备响应为模型仿真，未控制或验证真实设备。',
  'P3 前瞻择优使用候选动作短程仿真，不等于 LLM 决策。',
  'P0–P6 为固定参考场景、合成天气及假设参数的确定性仿真；未注入本次用户农情。',
  '模拟产量、利润和病害指标不能作为实棚验证结果或实际收益承诺。',
  '知识库状态查询仅证明可用性与登记状态，不代表本报告执行了知识检索或获得文献结论。',
];
const FIELD_KEYS = new Set(['crop', 'variety', 'facilityType', 'areaM2', 'plantingDate', 'growthStage', 'cultivation',
  'temperatureC', 'humidityPct', 'soilMoisturePct', 'co2Ppm', 'lightPpfd', 'vpdKpa', 'irrigation', 'fertilization',
  'recentOperations', 'symptoms', 'detectedDisease', 'severity', 'goal', 'targetMarketDate', 'budgetYuan']);
const pick = (value, keys) => Object.fromEntries(keys.filter(k => Object.hasOwn(value, k)).map(k => [k, value[k]]));
const strings = value => Array.isArray(value) ? value.filter(v => typeof v === 'string').slice(0, 100) : [];
const publicTrace = (record, evidence) => ({ evidenceId: record.id, inputSha256: record.inputHash,
  calls: evidence.map(({ rawResponse, ...trace }) => trace) });

export class Capabilities {
  constructor(upstream, store) { this.upstream = upstream; this.store = store; }
  async execute(capability, input, record, signal) {
    validate(capability, input);
    const evidence = [];
    const call = async (op, body, batchId) => {
      try {
        const result = await this.upstream.call(op, body, signal, batchId);
        evidence.push(result.evidence); return result.data;
      } catch (error) { if (error.callTrace) evidence.push(error.callTrace); throw error; }
    };
    try {
      let data;
      if (capability === 'agri.analyze') {
        const draft = await call('parse', { text: input.text });
        if (!Array.isArray(draft.fields)) throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '农情草稿缺少 fields');
        // The existing parser asks for quotes but does not check substring membership; enforce it here.
        const fields = draft.fields.filter(f => f && FIELD_KEYS.has(f.key) && typeof f.rawText === 'string' &&
          f.rawText.trim() && input.text.includes(f.rawText) && ['number', 'string'].includes(typeof f.value) &&
          (typeof f.value !== 'number' || Number.isFinite(f.value)))
          .map(f => pick(f, ['key', 'label', 'unit', 'value', 'source', 'rawText']));
        if (!fields.length) throw new ApiError(422, 'ANALYSIS_UNAVAILABLE', '上游未抽取到带原文依据的字段；不得用猜测补全');
        const warnings = [];
        let environment = null, knowledge = null;
        try {
          const env = await call('environment');
          if (!['OBSERVED', 'SIMULATED_LOCATION'].includes(env.source)) throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '环境来源标识缺失或未知');
          environment = pick(env, ['location', 'temperatureC', 'humidityPct', 'observedAt', 'source', 'sourceLabel', 'note', 'unavailableFields']);
        } catch (error) { if (!(error instanceof ApiError) || signal.aborted) throw error; warnings.push({ tool: 'environment', code: error.code }); }
        try {
          const kb = await call('knowledge');
          knowledge = pick(kb, ['chunkCount', 'vectorCount', 'sourceCount', 'indexLoadedAtMillis',
            'embeddingAvailable', 'embeddingProbeAgeMillis', 'embeddingProbeTtlMillis', 'indexVectorSearchAvailable']);
        } catch (error) { if (!(error instanceof ApiError) || signal.aborted) throw error; warnings.push({ tool: 'knowledge', code: error.code }); }
        data = { analysisId: record.id, draft: { fields, missing: strings(draft.missing), conflicts: strings(draft.conflicts),
          notes: [...strings(draft.notes), ...(fields.length !== draft.fields.length ? ['已排除没有输入原文依据或未登记的抽取字段'] : [])] },
          riskClues: fields.filter(f => ['temperatureC', 'humidityPct', 'vpdKpa', 'symptoms', 'detectedDisease', 'severity'].includes(f.key))
            .map(f => ({ field: f.key, value: f.value, evidence: f.rawText, interpretation: '待人工复核的风险线索，不构成诊断或阈值判定' })),
          environment, knowledge, warnings, requiresHumanReview: true, boundaries: BOUNDARIES };
      } else if (capability === 'agri.evaluate') {
        this.store.get(input.analysisId, 'agri.analyze');
        const seed = input.seed ?? 20250419, days = input.days ?? 56;
        const batchId = record.id;
        const run = await call('run', { batchId, seed, days });
        const matrix = await call('matrix', undefined, batchId);
        if (run.batchId !== batchId || matrix.batchId !== batchId || matrix.seed !== seed || matrix.days !== days) {
          throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '批次或参数回显不匹配');
        }
        const outcomes = {};
        for (const strategy of Object.keys(STRATEGIES)) {
          const metrics = matrix.outcomes?.[strategy];
          if (!metrics || Object.keys(METRICS).some(k => typeof metrics[k] !== 'number' || !Number.isFinite(metrics[k]))) {
            throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', 'P0–P6 矩阵缺少有限数值指标');
          }
          outcomes[strategy] = pick(metrics, Object.keys(METRICS));
        }
        if (matrix.profile?.sourceStatus !== 'SIMULATED_UNCALIBRATED') throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '未知仿真来源状态，需重新审计');
        const profile = pick(matrix.profile, ['profileId', 'displayName', 'startDate', 'endDate', 'days', 'sourceStatus',
          'sourceUrl', 'paperUrl', 'calibrated', 'cohortScope', 'weatherStatus', 'initialPhenologyStatus', 'assumptions', 'plantCount', 'greenhouseFootprintM2']);
        data = { evaluationId: record.id, analysisId: input.analysisId, batchId, seed, days,
          elapsedMillis: matrix.elapsedMillis, profile, labels: STRATEGIES, units: METRICS, outcomes,
          scenarioBinding: 'REFERENCE_ONLY_NOT_USER_CONDITIONED', boundaries: BOUNDARIES };
      } else {
        const analysis = this.store.get(input.analysisId, 'agri.analyze');
        const evaluation = this.store.get(input.evaluationId, 'agri.evaluate');
        if (evaluation.response.data.analysisId !== input.analysisId) throw new ApiError(409, 'EVIDENCE_MISMATCH', '评测与农情证据不属于同一链路');
        data = report(record.id, analysis, evaluation);
      }
      data.trace = publicTrace(record, evidence);
      data.resultSha256 = digest(data);
      return { data, evidence };
    } catch (error) { error.evidence = evidence; throw error; }
  }
}

function report(id, analysis, evaluation) {
  const a = analysis.response.data, e = evaluation.response.data;
  const baseline = e.outcomes.P2_RULE_ENGINE;
  const comparisonToRule = Object.fromEntries(Object.entries(e.outcomes).map(([key, metrics]) => [key,
    Object.fromEntries(Object.keys(METRICS).map(metric => [metric, metrics[metric] - baseline[metric]]))]));
  const business = Object.keys(STRATEGIES).slice(0, 4);
  const leaders = {};
  for (const [metric, direction] of Object.entries({ profitYuan: 'max', marketableYieldKg: 'max', waterUsedM3: 'min',
    energyKWh: 'min', diseasePressureIntegral: 'min', constraintViolations: 'min' })) {
    const values = business.map(s => e.outcomes[s][metric]);
    const best = direction === 'max' ? Math.max(...values) : Math.min(...values);
    leaders[metric] = { direction, value: best, strategies: business.filter(s => e.outcomes[s][metric] === best) };
  }
  const review = [
    '逐条核对解析字段、原文依据、缺项和冲突；用户症状与确诊声明不是模型诊断。',
    '本轮固定场景对照未使用用户温湿度、面积或病害状态；不得宣传为该用户实棚的最优处方。',
    'P0–P3作为业务策略比较；P4–P6是控制项消融，不能混为真实农户可直接部署方案。',
    '结合产量、成本、用水、病害压力和约束违规共同复核；单指标第一不表示全面最优。',
    '核对环境 source、知识库快照时间及警告；天气服务的OBSERVED也不等于棚内传感器实测。',
    '仿真缺少实棚校准验证；设备操作、用药和经营安排必须由负责人另行核查。',
  ];
  const format = n => Number(n.toFixed(4));
  const p3Delta = comparisonToRule.P3_AGENT;
  const tradeoffExplanation = `相对P2，P3模拟用水差为${format(p3Delta.waterUsedM3)}m³，能耗差为${format(p3Delta.energyKWh)}kWh，` +
    `利润差为${format(p3Delta.profitYuan)}元，病害压力积分差为${format(p3Delta.diseasePressureIntegral)}，` +
    `最终模型严重度差为${format(p3Delta.finalSeverityTotal)}。差值小于0表示该指标减少，大于0表示增加；这些指标的优劣方向不同。`;
  const economicCaveat = business.every(s => e.outcomes[s].profitYuan < 0)
    ? '本次P0–P3模拟利润均为负值；利润较高只代表模拟亏损较小，不代表盈利。' : '利润是模型输出，不是已实现收益。';
  const rows = Object.keys(STRATEGIES).map(s => `| ${s} | ${e.outcomes[s].marketableYieldKg} | ${e.outcomes[s].waterUsedM3} | ${e.outcomes[s].profitYuan} | ${e.outcomes[s].diseasePressureIntegral} |`);
  const escape = value => String(value).replace(/[\\`*_<>{}\[\]|]/g, char => `\\${char}`).replace(/[\r\n]+/g, ' ');
  const fieldRows = a.draft.fields.map(f => `| ${escape(f.label || f.key)} | ${escape(f.value)} | ${escape(f.rawText)} |`);
  const markdown = ['# 农业策略对照实验说明', '', `报告 ${id}；批次 ${e.batchId}；seed=${e.seed}；days=${e.days}。`, '',
    '## 数据与实验边界', '', ...BOUNDARIES.map(t => `- ${t}`), '',
    '## 农情草稿（用户陈述，尚待人工复核）', '', '| 字段 | 抽取值 | 输入原文依据 |', '| --- | --- | --- |', ...fieldRows, '',
    `缺项：${a.draft.missing.map(escape).join('、') || '无返回缺项'}。`,
    `冲突：${a.draft.conflicts.map(escape).join('；') || '无返回冲突'}。`,
    `解析说明：${a.draft.notes.map(escape).join('；') || '无额外说明'}。`, '',
    `环境来源：${escape(a.environment?.source || 'UNAVAILABLE')}；时间：${escape(a.environment?.observedAt || '未获取')}。`,
    `知识状态：${a.knowledge ? `块数${escape(a.knowledge.chunkCount)}，来源数${escape(a.knowledge.sourceCount)}` : '未获取'}；未执行RAG检索。`,
    `工具警告：${a.warnings.map(w => escape(`${w.tool}:${w.code}`)).join('；') || '无'}。`, '',
    '## 实际工具指标', '', '| 策略 | 模拟可售产量 kg | 模拟用水 m³ | 模拟利润 元 | 病害压力模型积分 |', '| --- | ---: | ---: | ---: | ---: |', ...rows, '',
    '## 权衡', '', tradeoffExplanation, economicCaveat,
    '结构化 leaders 列出不同指标的并列领先策略；comparisonToRule 是相对 P2 的原值差。没有加权总分或全面最优结论。', '',
    '## 人工复核', '', ...review.map(t => `- ${t}`), '',
    `农情证据 ${a.analysisId}，结果摘要 ${a.resultSha256}。`, `评测证据 ${e.evaluationId}，结果摘要 ${e.resultSha256}。`].join('\n');
  return { reportId: id, analysisId: a.analysisId, evaluationId: e.evaluationId,
    experiment: pick(e, ['batchId', 'seed', 'days', 'profile', 'scenarioBinding']),
    riskClues: a.riskClues, draft: a.draft, environment: a.environment, knowledge: a.knowledge, warnings: a.warnings,
    matrix: e.outcomes, labels: STRATEGIES, units: METRICS, leaders, comparisonToRule, tradeoffExplanation, economicCaveat,
    evidence: [a.trace, e.trace], evidenceResultHashes: [a.resultSha256, e.resultSha256],
    humanReview: review, boundaries: BOUNDARIES, markdown,
    reportMethod: 'DETERMINISTIC_TOOL_RESULTS_TEMPLATE', fieldValidation: 'NOT_PERFORMED' };
}
