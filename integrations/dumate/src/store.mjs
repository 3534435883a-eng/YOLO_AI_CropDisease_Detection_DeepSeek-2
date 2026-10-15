import { mkdir, readdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { ApiError } from './config.mjs';

export const digest = value => createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex');
export const isId = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);

// Single process owns this directory. PENDING records survive restart and block ambiguous POST replay.
export class EvidenceStore {
  constructor(config) { this.config = config; this.records = new Map(); this.keys = new Map(); }
  async init() {
    await mkdir(this.config.stateDir, { recursive: true, mode: 0o700 });
    for (const file of await readdir(this.config.stateDir)) {
      if (!file.endsWith('.json') || !isId(file.slice(0, -5))) continue;
      const record = JSON.parse(await readFile(join(this.config.stateDir, file), 'utf8'));
      if (record.id !== file.slice(0, -5)) throw new Error('Invalid evidence record');
      this.records.set(record.id, record);
      this.keys.set(record.keyHash, record.id);
    }
    await this.prune();
  }
  async prune() {
    for (const [id, record] of this.records) {
      if (Date.now() - record.createdAt > this.config.ttlMs && record.state !== 'PENDING') {
        await unlink(join(this.config.stateDir, `${id}.json`)).catch(error => { if (error.code !== 'ENOENT') throw error; });
        this.records.delete(id); this.keys.delete(record.keyHash);
      }
    }
  }
  async persist(record) {
    const path = join(this.config.stateDir, `${record.id}.json`);
    const temporary = `${path}.tmp`;
    await writeFile(temporary, JSON.stringify(record), { mode: 0o600 });
    await rename(temporary, path);
  }
  async begin(key, capability, input) {
    await this.prune();
    const keyHash = digest(key), inputHash = digest({ capability, input });
    const previous = this.records.get(this.keys.get(keyHash));
    if (previous) {
      if (previous.inputHash !== inputHash) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', '该调用键已用于其他参数');
      if (previous.state === 'PENDING') throw new ApiError(409, 'RESULT_INDETERMINATE', '调用仍在进行或进程中断；禁止自动重放上游 POST');
      return { record: previous, replay: true };
    }
    if (this.records.size >= this.config.maxRecords) throw new ApiError(503, 'EVIDENCE_CAPACITY', '证据空间已满；请由管理员归档，或等待24小时过期');
    const record = { id: randomUUID(), keyHash, inputHash, capability, createdAt: Date.now(), state: 'PENDING' };
    this.records.set(record.id, record); this.keys.set(keyHash, record.id);
    try { await this.persist(record); }
    catch (error) { this.records.delete(record.id); this.keys.delete(keyHash); throw error; }
    return { record, replay: false };
  }
  async complete(record, response, evidence) {
    const completed = { ...record, response, evidence, state: response.ok ? 'SUCCEEDED' : 'FAILED' };
    await this.persist(completed);
    Object.assign(record, completed);
  }
  get(id, capability) {
    if (!isId(id)) throw new ApiError(400, 'INVALID_INPUT', '证据编号必须为适配层返回的 UUID');
    const record = this.records.get(id);
    if (!record || record.state !== 'SUCCEEDED' || record.capability !== capability || Date.now() - record.createdAt > this.config.ttlMs) {
      throw new ApiError(404, 'EVIDENCE_NOT_FOUND', '证据不存在、已过期或能力不匹配');
    }
    return record;
  }
}
