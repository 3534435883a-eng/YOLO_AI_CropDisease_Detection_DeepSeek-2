import http from 'node:http';
import { createHash, timingSafeEqual, randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { configFromEnv, ApiError } from './config.mjs';
import { EvidenceStore } from './store.mjs';
import { Upstream } from './upstream.mjs';
import { Capabilities, validate } from './capabilities.mjs';

const routes = { '/v1/agri/analyze': 'agri.analyze', '/v1/agri/evaluate': 'agri.evaluate', '/v1/agri/report': 'agri.report' };
const hash = text => createHash('sha256').update(text).digest();

async function bodyOf(req) {
  if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') {
    throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', '需要 application/json');
  }
  const chunks = await new Promise((resolve, reject) => {
    let size = 0, overflow = false; const pieces = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > 32768) { overflow = true; reject(new ApiError(413, 'PAYLOAD_TOO_LARGE', '请求体超过32KiB')); }
      if (!overflow) pieces.push(chunk);
    });
    req.on('end', () => resolve(pieces));
    req.on('error', reject);
    req.on('aborted', () => reject(new ApiError(400, 'INCOMPLETE_REQUEST', '请求中断')));
  });
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new ApiError(400, 'INVALID_JSON', '无法解析 JSON'); }
}
function failure(error, requestId) {
  const known = error instanceof ApiError;
  return { ok: false, requestId, status: known ? error.status : 500,
    error: { code: known ? error.code : 'INTERNAL_ERROR', message: known ? error.message : '适配层内部错误', retryable: known && error.retryable } };
}

export async function createAdapter(config, dependencies = {}) {
  const store = dependencies.store || new EvidenceStore(config);
  await store.init();
  const capabilities = new Capabilities(dependencies.upstream || new Upstream(config), store);
  let active = 0;
  // One key also suppresses parallel duplicate submissions before disk persistence completes.
  const pendingKeys = new Set();
  const server = http.createServer(async (req, res) => {
    const requestId = randomUUID();
    let record, key, acquired = false;
    const send = response => {
      res.writeHead(response.ok ? 200 : response.status, { 'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'x-request-id': response.requestId });
      res.end(JSON.stringify(response));
    };
    try {
      const supplied = req.headers.authorization || '';
      if (!timingSafeEqual(hash(supplied), hash(`Bearer ${config.token}`))) throw new ApiError(401, 'UNAUTHORIZED', '缺少或无效的适配层凭证');
      const capability = Object.hasOwn(routes, req.url) ? routes[req.url] : null;
      if (!capability) throw new ApiError(404, 'CAPABILITY_NOT_FOUND', '未开放此接口');
      if (req.method !== 'POST') throw new ApiError(405, 'METHOD_NOT_ALLOWED', '仅支持 POST');
      key = req.headers['idempotency-key'];
      if (typeof key !== 'string' || !/^[A-Za-z0-9_-]{8,80}$/.test(key)) throw new ApiError(400, 'INVALID_IDEMPOTENCY_KEY', '需要8–80位字母、数字、下划线或连字符的 Idempotency-Key');
      const input = await bodyOf(req);
      validate(capability, input);
      if (pendingKeys.has(key)) throw new ApiError(409, 'RESULT_INDETERMINATE', '同一调用正在执行，请等待原请求完成');
      if (active >= config.maxConcurrent) throw new ApiError(429, 'ADAPTER_BUSY', '并发请求已满，尚未调用上游', true);
      active++; acquired = true; pendingKeys.add(key);
      const begun = await store.begin(key, capability, input); record = begun.record;
      if (begun.replay) { send(record.response); return; }
      const signal = AbortSignal.timeout(config.timeoutMs);
      let response, evidence;
      try {
        const result = await capabilities.execute(capability, input, record, signal);
        response = { ok: true, requestId: record.id, data: result.data }; evidence = result.evidence;
      } catch (error) {
        response = failure(error, record.id); evidence = error.evidence || [];
      }
      await store.complete(record, response, evidence);
      send(response);
    } catch (error) { send(failure(error, requestId)); }
    finally { if (acquired) { active--; pendingKeys.delete(key); } }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 5000;
  server.maxHeadersCount = 40;
  server.keepAliveTimeout = 5000;
  return { server, store };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = configFromEnv();
  const { server } = await createAdapter(config);
  server.listen(config.port, config.host, () => console.log(`DuMate agriculture adapter listening on ${config.host}:${config.port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
}
