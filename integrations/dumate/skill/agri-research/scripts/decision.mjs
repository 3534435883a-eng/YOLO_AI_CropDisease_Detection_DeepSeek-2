// Independent finite-set analysis; never invokes HTTP, devices or a new simulator.
export class DecisionError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const fail = (code, message) => { throw new DecisionError(code, message); };
const management = ['P0_NONE', 'P1_FIXED_MANUAL', 'P2_RULE_ENGINE', 'P3_AGENT'];
const ablations = ['P4_PID_PROPORTIONAL', 'P5_PID_PI', 'P6_PID_FULL'];
const directions = { waterUsedM3: 'min', energyKWh: 'min', costYuan: 'min', profitYuan: 'max',
  marketableYieldKg: 'max', diseasePressureIntegral: 'min', finalSeverityTotal: 'min',
  highTemperatureMinutes: 'min', highHumidityMinutes: 'min' };
const limitMetrics = Object.keys(directions).filter(k => !['profitYuan', 'marketableYieldKg'].includes(k));
const plain = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const exactKeys = (v, keys) => plain(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const percent = v => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 100;

export function validateGoal(goal) {
  if (!exactKeys(goal, ['goalText', 'confirmed', 'baseline', 'minWaterSavingPct', 'objectives', 'constraints']) ||
      typeof goal.goalText !== 'string' || !goal.goalText.trim() || goal.goalText.length > 4000 ||
      goal.confirmed !== true || !management.includes(goal.baseline) || !percent(goal.minWaterSavingPct) ||
      !Array.isArray(goal.objectives) || goal.objectives.length < 1 || goal.objectives.length > 3 ||
      !Array.isArray(goal.constraints) || goal.constraints.length < 1 || goal.constraints.length > 7) {
    fail('INVALID_GOAL', '需明确目标原文、confirmed=true、P0–P3基准、0–100节水百分比、1–3个目标及1–7个约束；模糊阈值应先澄清');
  }
  const unique = (entries, validate) => {
    const seen = new Set();
    for (const entry of entries) {
      if (!validate(entry) || seen.has(entry.metric)) fail('INVALID_GOAL', '指标、方向或允许增幅无效；同类指标不得重复');
      seen.add(entry.metric);
    }
  };
  unique(goal.objectives, e => exactKeys(e, ['metric', 'direction']) && Object.hasOwn(directions, e.metric) && e.direction === directions[e.metric]);
  unique(goal.constraints, e => exactKeys(e, ['metric', 'maxIncreasePct']) && limitMetrics.includes(e.metric) && percent(e.maxIncreasePct));
  return structuredClone(goal);
}

export function selectStrategies(input, evaluation) {
  const goal = validateGoal(input), matrix = evaluation?.outcomes;
  const keys = [...management, ...ablations];
  if (evaluation?.scenarioBinding !== 'REFERENCE_ONLY_NOT_USER_CONDITIONED' || !exactKeys(matrix, keys)) {
    fail('INVALID_MATRIX', '需真实七组完整固定参考场景矩阵');
  }
  for (const key of keys) {
    const row = matrix[key];
    for (const metric of [...Object.keys(directions), 'constraintViolations']) {
      if (!plain(row) || typeof row[metric] !== 'number' || !Number.isFinite(row[metric]) ||
          (metric !== 'profitYuan' && row[metric] < 0) ||
          (metric === 'constraintViolations' && !Number.isInteger(row[metric]))) {
        fail('INVALID_MATRIX', '矩阵包含缺失、非有限或非法指标；停止筛选');
      }
    }
  }
  const base = matrix[goal.baseline];
  if (base.waterUsedM3 === 0 && goal.minWaterSavingPct > 0) fail('ZERO_WATER_BASELINE', '用水为0的基准无法定义正百分比节水目标；请明确其他基准');
  const candidates = management.map(strategy => {
    const row = matrix[strategy];
    const waterLimit = base.waterUsedM3 * (1 - goal.minWaterSavingPct / 100);
    const checks = [{ metric: 'waterUsedM3', value: row.waterUsedM3, limit: waterLimit, operator: '<=',
      rule: 'MINIMUM_WATER_SAVING', requiredSavingPct: goal.minWaterSavingPct, passed: row.waterUsedM3 <= waterLimit },
    ...goal.constraints.map(c => {
      const limit = base[c.metric] * (1 + c.maxIncreasePct / 100);
      return { metric: c.metric, value: row[c.metric], baselineValue: base[c.metric], limit,
        operator: '<=', rule: 'MAXIMUM_RELATIVE_INCREASE', maxIncreasePct: c.maxIncreasePct, passed: row[c.metric] <= limit };
    }), { metric: 'constraintViolations', value: row.constraintViolations, limit: 0, operator: '<=',
      rule: 'SIMULATOR_CONSTRAINTS', passed: row.constraintViolations === 0 }];
    return { strategy, eligible: checks.every(c => c.passed), metrics: structuredClone(row), checks,
      objectiveValues: goal.objectives.map(o => row[o.metric]),
      waterSavingPct: base.waterUsedM3 === 0 ? null : (base.waterUsedM3 - row.waterUsedM3) / base.waterUsedM3 * 100,
      deltasFromBaseline: Object.fromEntries(Object.keys(directions).map(k => [k, row[k] - base[k]])) };
  });
  const compare = (a, b) => {
    for (const o of goal.objectives) {
      const delta = a[o.metric] - b[o.metric];
      if (delta !== 0) return o.direction === 'min' ? Math.sign(delta) : -Math.sign(delta);
    }
    return 0;
  };
  const ranked = candidates.filter(c => c.eligible).sort((a, b) => compare(a.metrics, b.metrics));
  const best = ranked[0];
  const status = !best ? 'NO_FEASIBLE' : compare(best.metrics, base) >= 0 ? 'NO_IMPROVEMENT' : 'RECOMMENDED';
  const recommendedStrategies = status === 'RECOMMENDED' ? ranked.filter(c => compare(c.metrics, best.metrics) === 0).map(c => c.strategy) : [];
  const warnings = [
    '只筛选P0–P3已有管理策略；P4–P6为消融对照，不进入推荐。',
    '使用未校准固定参考场景，没有注入用户农情；推荐仅为模型内的研究候选，不是实棚处方或收益承诺。',
    'confirmed字段记录客户端目标确认状态，不是第三方授权或人工签名。',
    '未列入constraints的指标没有“不增加”保证；需同时查看产量、能耗、利润和最终严重度。',
  ];
  if (!goal.constraints.some(c => c.metric === 'finalSeverityTotal')) warnings.push('本目标未约束最终严重度；病害积分下降不代表最终严重度下降。');
  if (recommendedStrategies.includes('P0_NONE')) warnings.push('P0不做调控，即使用水最少也不代表农业生产可行；仍有模型成本，产量须复核。');
  return { source: 'LOCAL_DETERMINISTIC_SELECTOR', scope: 'P0_TO_P3_ONLY', goal, status,
    baselineMetrics: structuredClone(base), candidates, eligibleStrategies: ranked.map(c => c.strategy),
    recommendedStrategies, ablationStrategies: [...ablations],
    rankingMethod: 'LEXICOGRAPHIC_USER_PRIORITY_NO_WEIGHTS', warnings,
    nextResearchSteps: status === 'RECOMMENDED'
      ? ['人工复核候选的未约束指标、模型参数和场景边界，再决定是否开展新的模拟实验。']
      : ['本次不推荐新方案。核对约束是否符合研究目的；如需调整，先明确新的条件并创建新任务，不能静默放宽阈值。'] };
}
