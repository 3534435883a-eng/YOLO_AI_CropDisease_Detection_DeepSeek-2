import { ApiError } from './config.mjs';
import { digest, isId } from './store.mjs';

const endpoints = {
  parse: ['POST', '/ai/agri/plan/situation/parse'],
  knowledge: ['GET', '/ai/knowledge/status'],
  environment: ['GET', '/ai/agri/environment/current'],
  run: ['POST', '/eval/runs'],
};

export class Upstream {
  constructor(config) { this.config = config; }
  async call(operation, input, signal, batchId) {
    let endpoint = endpoints[operation];
    if (operation === 'matrix' && isId(batchId)) endpoint = ['GET', `/eval/${batchId}/matrix`];
    if (!endpoint) throw new ApiError(403, 'TOOL_FORBIDDEN', '上游操作未列入白名单');
    const [method, path] = endpoint;
    const headers = { accept: 'application/json' };
    if (method === 'POST') headers['content-type'] = 'application/json';
    if (this.config.upstreamToken) headers.authorization = `Bearer ${this.config.upstreamToken}`;
    const started = Date.now();
    try {
      const response = await fetch(`${this.config.upstream}${path}`, {
        method, headers, redirect: 'error', signal,
        body: method === 'POST' ? JSON.stringify(input) : undefined,
      });
      if (!response.ok) {
        const mappings = { 401: [502, 'UPSTREAM_AUTH_FAILED'], 403: [502, 'UPSTREAM_AUTH_FAILED'],
          404: [502, 'UPSTREAM_NOT_FOUND'], 429: [503, 'UPSTREAM_BUSY'] };
        const [status, code] = mappings[response.status] || [502, 'UPSTREAM_HTTP_ERROR'];
        throw new ApiError(status, code, '上游 HTTP 调用失败');
      }
      if (!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '上游未返回 JSON');
      const reader = response.body.getReader();
      const chunks = []; let size = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 8 * 1024 * 1024) { await reader.cancel(); throw new ApiError(502, 'UPSTREAM_RESPONSE_TOO_LARGE', '上游响应超过8MiB'); }
        chunks.push(Buffer.from(value));
      }
      const raw = Buffer.concat(chunks).toString('utf8');
      let envelope;
      try { envelope = JSON.parse(raw); }
      catch { throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '上游 JSON 无法解析'); }
      if (envelope?.code !== '0') {
        const code = envelope?.code;
        throw new ApiError(code === 'EVAL_BATCH_NOT_FOUND' ? 410 : 502,
          code === 'EVAL_BATCH_NOT_FOUND' ? 'BATCH_EXPIRED' : 'UPSTREAM_BUSINESS_ERROR', '上游返回业务失败');
      }
      if (!envelope.data || typeof envelope.data !== 'object') throw new ApiError(502, 'UPSTREAM_INVALID_RESPONSE', '上游 data 为空');
      return { data: envelope.data, evidence: {
        operation, method, path, requestedAt: new Date(started).toISOString(), elapsedMs: Date.now() - started,
        requestSha256: digest(input ?? {}), responseSha256: digest(raw), resultCode: '0',
        // Local evidence only; never served by an HTTP file endpoint.
        rawResponse: envelope,
      } };
    } catch (error) {
      const mapped = error instanceof ApiError ? error : signal.aborted
        ? new ApiError(504, 'UPSTREAM_TIMEOUT', '调用超过总预算；上游POST可能仍在执行，禁止自动重试')
        : new ApiError(502, 'UPSTREAM_UNAVAILABLE', '无法连接指定农业服务；上游POST结果可能不确定');
      mapped.callTrace = { operation, method, path, requestedAt: new Date(started).toISOString(),
        elapsedMs: Date.now() - started, requestSha256: digest(input ?? {}), errorCode: mapped.code };
      throw mapped;
    }
  }
}
