import { writeFile } from 'node:fs/promises';
import { SCHEMAS, STRATEGIES, METRICS } from '../src/capabilities.mjs';

const ref = name => ({ $ref: `#/components/schemas/${name}` });
const id = { type: 'string', format: 'uuid' };
const matrix = { type: 'object', required: Object.keys(STRATEGIES), additionalProperties: false,
  properties: Object.fromEntries(Object.keys(STRATEGIES).map(s => [s, ref('Metrics')])) };
const trace = { type: 'object', required: ['evidenceId', 'inputSha256', 'calls'], properties: {
  evidenceId: id, inputSha256: { type: 'string' }, calls: { type: 'array', items: { type: 'object', properties: {
    operation: { type: 'string' }, method: { type: 'string' }, path: { type: 'string' },
    requestedAt: { type: 'string', format: 'date-time' }, elapsedMs: { type: 'integer' },
    requestSha256: { type: 'string' }, responseSha256: { type: 'string' }, resultCode: { type: 'string' }, errorCode: { type: 'string' },
  } } },
} };
const shared = { boundaries: { type: 'array', items: { type: 'string' } }, trace: ref('Trace'), resultSha256: { type: 'string' } };
const schemas = {
  Trace: trace, Matrix: matrix,
  Metrics: { type: 'object', required: Object.keys(METRICS), additionalProperties: false,
    properties: Object.fromEntries(Object.entries(METRICS).map(([m, unit]) => [m, { type: 'number', description: `模型指标；单位：${unit}` }])) },
  AnalyzeResult: { type: 'object', required: ['analysisId', 'draft', 'riskClues', 'requiresHumanReview', 'trace', 'boundaries', 'resultSha256'], properties: {
    ...shared, analysisId: id, draft: { type: 'object', properties: {
      fields: { type: 'array', items: { type: 'object', required: ['key', 'value', 'rawText'], properties: {
        key: { type: 'string' }, value: { oneOf: [{ type: 'string' }, { type: 'number' }] }, rawText: { type: 'string' }, label: { type: 'string' }, unit: { type: 'string', nullable: true }, source: { type: 'string' },
      } } }, missing: { type: 'array', items: { type: 'string' } }, conflicts: { type: 'array', items: { type: 'string' } }, notes: { type: 'array', items: { type: 'string' } },
    } }, riskClues: { type: 'array', items: { type: 'object' } }, environment: { type: 'object', nullable: true },
    knowledge: { type: 'object', nullable: true }, warnings: { type: 'array', items: { type: 'object' } }, requiresHumanReview: { type: 'boolean', enum: [true] },
  } },
  EvaluateResult: { type: 'object', required: ['evaluationId', 'analysisId', 'batchId', 'seed', 'days', 'profile', 'outcomes', 'scenarioBinding', 'trace', 'boundaries', 'resultSha256'], properties: {
    ...shared, evaluationId: id, analysisId: id, batchId: id, seed: SCHEMAS['agri.evaluate'].properties.seed,
    days: SCHEMAS['agri.evaluate'].properties.days, elapsedMillis: { type: 'integer' }, profile: { type: 'object', description: '原参考场景元数据；sourceStatus=SIMULATED_UNCALIBRATED' },
    labels: { type: 'object' }, units: { type: 'object' }, outcomes: ref('Matrix'),
    scenarioBinding: { type: 'string', enum: ['REFERENCE_ONLY_NOT_USER_CONDITIONED'] },
  } },
  ReportResult: { type: 'object', required: ['reportId', 'analysisId', 'evaluationId', 'matrix', 'humanReview', 'evidence', 'boundaries', 'markdown', 'resultSha256'], properties: {
    ...shared, reportId: id, analysisId: id, evaluationId: id, matrix: ref('Matrix'), experiment: { type: 'object' },
    leaders: { type: 'object' }, comparisonToRule: ref('Matrix'), tradeoffExplanation: { type: 'string' }, economicCaveat: { type: 'string' },
    humanReview: { type: 'array', items: { type: 'string' } }, evidence: { type: 'array', items: ref('Trace') },
    markdown: { type: 'string' }, reportMethod: { type: 'string', enum: ['DETERMINISTIC_TOOL_RESULTS_TEMPLATE'] },
    fieldValidation: { type: 'string', enum: ['NOT_PERFORMED'] },
  } },
  Error: { type: 'object', required: ['ok', 'requestId', 'status', 'error'], properties: {
    ok: { type: 'boolean', enum: [false] }, requestId: id, status: { type: 'integer' },
    error: { type: 'object', required: ['code', 'message', 'retryable'], properties: {
      code: { type: 'string' }, message: { type: 'string' }, retryable: { type: 'boolean' },
    } },
  } },
};
const paths = {};
for (const [name, schema] of Object.entries(SCHEMAS)) {
  const operation = name.split('.')[1];
  const result = `${operation[0].toUpperCase()}${operation.slice(1)}Result`;
  paths[`/v1/agri/${operation}`] = { post: {
    operationId: `agri_${operation}`, summary: name, tags: ['农业研究与虚拟仿真'],
    description: operation === 'evaluate' ? '固定番茄参考场景P0–P6对照；不使用解析农情作为初始条件。' : '仅农业研究；不操作真实设备。',
    parameters: [{ name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9_-]{8,80}$' },
      description: '同一请求重复提交复用原键；不确定POST禁止自动换键重试。' }],
    requestBody: { required: true, content: { 'application/json': { schema } } },
    responses: {
      200: { description: '实际工具结果或存档复用', content: { 'application/json': { schema: {
        type: 'object', required: ['ok', 'requestId', 'data'], properties: { ok: { type: 'boolean', enum: [true] }, requestId: id, data: ref(result) },
      } } } },
      ...Object.fromEntries([400, 401, 404, 405, 409, 410, 413, 415, 422, 429, 500, 502, 503, 504].map(status => [status,
        { description: `失败；HTTP ${status}，见error.code；上游POST超时结果可能不确定`, content: { 'application/json': { schema: ref('Error') } } }])),
    },
  } };
}
const document = { openapi: '3.0.3', info: { title: 'DuMate Agricultural Research Adapter', version: '0.1.0',
  description: '仅三能力；固定参考仿真、用户草稿需复核、无实棚验证。不会暴露原用户/文件/设备接口。' },
  servers: [{ url: 'http://127.0.0.1:9998', description: '本机联调；云端节点需管理员配置HTTPS地址' }],
  security: [{ adapterBearer: [] }], paths,
  components: { securitySchemes: { adapterBearer: { type: 'http', scheme: 'bearer', description: '管理员设置DUMATE_TOKEN；不是原后端用户令牌' } }, schemas },
};
await writeFile(new URL('../openapi.json', import.meta.url), `${JSON.stringify(document, null, 2)}\n`, 'utf8');
console.log('Generated openapi.json from implemented capability schemas');
