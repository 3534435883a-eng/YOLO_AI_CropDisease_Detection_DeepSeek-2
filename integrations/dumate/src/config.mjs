import { fileURLToPath } from 'node:url';

export class ApiError extends Error {
  constructor(status, code, message, retryable = false) {
    super(message);
    Object.assign(this, { status, code, retryable });
  }
}

export function configFromEnv(env = process.env) {
  const token = env.DUMATE_TOKEN || '';
  if (token.length < 32 || /[\r\n]/.test(token)) throw new Error('DUMATE_TOKEN must contain at least 32 characters');
  const upstream = new URL(env.DUMATE_UPSTREAM || 'http://127.0.0.1:9999');
  const local = ['127.0.0.1', 'localhost', '[::1]'].includes(upstream.hostname);
  if (!['https:', 'http:'].includes(upstream.protocol) || (!local && upstream.protocol !== 'https:') ||
      upstream.username || upstream.password || upstream.search || upstream.hash) {
    throw new Error('Upstream requires HTTPS or loopback HTTP, without credentials/query/fragment');
  }
  const timeoutMs = Number(env.DUMATE_TIMEOUT_MS || 75000);
  const port = Number(env.DUMATE_PORT || 9998);
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 120000) throw new Error('Invalid timeout');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
  return {
    token, upstream: upstream.href.replace(/\/$/, ''), timeoutMs,
    upstreamToken: env.DUMATE_UPSTREAM_TOKEN || '',
    host: env.DUMATE_HOST || '127.0.0.1', port,
    stateDir: fileURLToPath(new URL('../.state/', import.meta.url)),
    maxRecords: 200, ttlMs: 24 * 60 * 60 * 1000, maxConcurrent: 2,
  };
}
