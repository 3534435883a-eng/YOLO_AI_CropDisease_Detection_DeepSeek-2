const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const md = value => String(value ?? '').replace(/[\\`*_<>{}\[\]|]/g, c => `\\${c}`).replace(/[\r\n]+/g, ' ');
const n = value => Number(value.toFixed(4));

export function createReport(analysis, evaluation, report) {
  const matrix = evaluation.outcomes;
  const order = Object.keys(matrix);
  if (order.length !== 7) throw new Error('Expected seven strategy results');
  const baseline = matrix.P2_RULE_ENGINE, p3 = matrix.P3_AGENT;
  const winner = (metric, direction, keys = order) => {
    const value = Math[direction === 'min' ? 'min' : 'max'](...keys.map(k => matrix[k][metric]));
    return { value, strategies: keys.filter(k => matrix[k][metric] === value) };
  };
  const business = order.filter(k => /^P[0-3]_/.test(k));
  const leaders = {
    scope: 'ALL_SEVEN_STRATEGIES',
    lowestDiseaseIntegral: winner('diseasePressureIntegral', 'min'), highestCost: winner('costYuan', 'max'),
    lowestFinalSeverity: winner('finalSeverityTotal', 'min'),
    businessLowestDiseaseIntegral: { scope: 'P0_TO_P3_ONLY', ...winner('diseasePressureIntegral', 'min', business) },
  };
  const facts = [
    `相对P2，P3用水变化${n(p3.waterUsedM3 - baseline.waterUsedM3)}m³，能耗变化${n(p3.energyKWh - baseline.energyKWh)}kWh，模拟利润变化${n(p3.profitYuan - baseline.profitYuan)}元。`,
    `相对P2，P3病害压力积分变化${n(p3.diseasePressureIntegral - baseline.diseasePressureIntegral)}，最终模型严重度变化${n(p3.finalSeverityTotal - baseline.finalSeverityTotal)}。积分和终值是不同指标，不能互相替代。`,
    `全部七组中病害压力积分最低的是${leaders.lowestDiseaseIntegral.strategies.join('、')}（${leaders.lowestDiseaseIntegral.value}）；P3的最低排名仅适用于P0–P3业务组。`,
    `全部七组中成本最高的是${leaders.highestCost.strategies.join('、')}（${leaders.highestCost.value}元）。P0用水、能耗为0，但仍有${matrix.P0_NONE.costYuan}元模型成本，不能称为零投入。`,
    order.every(k => matrix[k].profitYuan < 0) ? '七组模拟利润均为负值；利润较高只表示模拟亏损较小，不代表盈利。' : '利润为模型输出，不是已实现的收益。',
  ];
  const boundaries = report.boundaries;
  const executionNote = '执行方式：本机 HTTP 调用（客户端 → 农业适配层 → 原农业后端）。本报告不声称已创建 Workflow、公开发布或完成实棚验证。';
  const headers = ['策略', '可售产量kg', '用水m³', '能耗kWh', '成本元', '利润元'];
  const rows = order.map(k => [k, matrix[k].marketableYieldKg, matrix[k].waterUsedM3, matrix[k].energyKWh, matrix[k].costYuan, matrix[k].profitYuan]);
  const riskHeaders = ['策略', '高温分钟', '高湿分钟', '病害压力积分', '最终模型严重度', '约束违规'];
  const riskRows = order.map(k => [k, matrix[k].highTemperatureMinutes, matrix[k].highHumidityMinutes, matrix[k].diseasePressureIntegral, matrix[k].finalSeverityTotal, matrix[k].constraintViolations]);
  const mdTable = (h, r) => [`| ${h.join(' | ')} |`, `| ${h.map(() => '---').join(' | ')} |`, ...r.map(row => `| ${row.map(md).join(' | ')} |`)].join('\n');
  const htmlTable = (h, r) => `<table><thead><tr>${h.map(v => `<th>${esc(v)}</th>`).join('')}</tr></thead><tbody>${r.map(row => `<tr>${row.map(v => `<td>${esc(v)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
  const references = [analysis.trace, evaluation.trace];
  const markdown = ['# 禾序农业研究实验报告', '',
    `分析编号：${analysis.analysisId}；评测/批次：${evaluation.evaluationId}；报告编号：${report.reportId}。`,
    `seed=${evaluation.seed}，days=${evaluation.days}。场景=${evaluation.scenarioBinding}。`, '',
    '## 农情草稿：用户陈述，待人工复核', '',
    mdTable(['字段', '取值', '原文依据'], analysis.draft.fields.map(f => [f.label || f.key, f.value, f.rawText])), '',
    `缺项：${analysis.draft.missing.map(md).join('、')}。`, `冲突：${analysis.draft.conflicts.map(md).join('；') || '无返回冲突'}。`,
    `环境source=${md(analysis.environment?.source || 'UNAVAILABLE')}；知识状态不表示执行了RAG检索。`, '',
    '## 资源与经济指标：全部为模拟值', '', mdTable(headers, rows), '',
    '## 风险指标：高温/高湿为暴露时长', '', mdTable(riskHeaders, riskRows), '',
    '## 权衡及比较范围', '', ...facts.map(f => `- ${f}`), '',
    '## 人工复核', '', ...report.humanReview.map(f => `- ${f}`), '',
    '## 数据和验证边界', '', ...boundaries.map(f => `- ${f}`), '',
    `农情摘要：${analysis.resultSha256}`, `评测摘要：${evaluation.resultSha256}`, `上游报告摘要：${report.resultSha256}`, '',
    '本文件由客户端依据实际工具响应排版；原始JSON另行保存。没有实棚验证。', '', executionNote,
  ].join('\n');
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>禾序农业研究实验报告</title><style>body{margin:0;background:#f4f3ee;color:#202c26;font:16px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1100px;margin:36px auto;background:#fff;padding:36px}h1{font-size:30px}h2{margin-top:30px;color:#245746}table{border-collapse:collapse;width:100%;font-size:13px;overflow-wrap:anywhere}th,td{padding:10px 8px;border-bottom:1px solid #d8e0da;text-align:left}th{background:#edf3ef}code{overflow-wrap:anywhere;font-size:13px}li{margin:9px 0}.tag{color:#547164;font-size:13px}.table{overflow-x:auto}@media print{main{margin:0;padding:12px}body{background:white}table{font-size:11px}h2{break-after:avoid}tr{break-inside:avoid}}</style></head><body><main><div class="tag">HE XU · AGRICULTURAL RESEARCH · MODEL SIMULATION</div><h1>禾序农业研究实验报告</h1><p>seed=${evaluation.seed} · days=${evaluation.days}<br>批次 <code>${esc(evaluation.batchId)}</code></p><p>本轮固定番茄参考场景，没有注入用户农情作为初始条件。</p><h2>农情草稿</h2><div class="table">${htmlTable(['字段','取值','原文依据'],analysis.draft.fields.map(f=>[f.label||f.key,f.value,f.rawText]))}</div><p>缺项：${esc(analysis.draft.missing.join('、'))}。环境来源：${esc(analysis.environment?.source||'UNAVAILABLE')}。</p><h2>资源与经济指标（模拟值）</h2><div class="table">${htmlTable(headers,rows)}</div><h2>风险指标（暴露时长与模型量）</h2><div class="table">${htmlTable(riskHeaders,riskRows)}</div><h2>权衡与比较范围</h2><ul>${facts.map(f=>`<li>${esc(f)}</li>`).join('')}</ul><h2>人工复核</h2><ul>${report.humanReview.map(f=>`<li>${esc(f)}</li>`).join('')}</ul><h2>数据边界</h2><ul>${boundaries.map(f=>`<li>${esc(f)}</li>`).join('')}</ul><h2>证据</h2><p>分析 <code>${esc(analysis.analysisId)}</code><br>评测 <code>${esc(evaluation.evaluationId)}</code><br>报告 <code>${esc(report.reportId)}</code></p><p>农情摘要 <code>${esc(analysis.resultSha256)}</code><br>评测摘要 <code>${esc(evaluation.resultSha256)}</code></p><p class="tag">本文件按实际JSON结果生成；原始响应未修改。没有实棚验证。</p><p class="tag">${esc(executionNote)}</p></main></body></html>`;
  return { markdown, html, leaders, facts, references };
}
