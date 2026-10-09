import type { FarmEvidence } from '/@/api/agent/tasks';
import type { SituationDraft, SituationFieldSpec, SituationFieldValue } from '/@/api/agent/plan';

export interface TaskFieldSource {
 evidenceId?: string;
 evidenceSource: string;
 fieldSource: string;
 manualAdjusted?: boolean;
}
export interface RestoredTaskSituation {
 patch: Record<string, unknown>;
 draft: SituationDraft;
 sources: Record<string, TaskFieldSource>;
 sourceNotes: string[];
}

const object = (value: unknown): Record<string, unknown> | null =>
 value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
const empty = (value: unknown) => value === undefined || value === null || (typeof value === 'string' && !value.trim());
const scalar = (value: unknown) => typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value));
const forbidden = new Set(['__proto__', 'prototype', 'constructor']);

/** Restore confirmed user evidence only. This helper has no greenhouse/scenario input. */
export function restoreTaskSituation(
 evidence: readonly FarmEvidence[], fields: readonly SituationFieldSpec[],
 current: Readonly<Record<string, unknown>>, edited: ReadonlySet<string> = new Set(),
): RestoredTaskSituation {
 const result: RestoredTaskSituation = {
  patch: {}, sources: {}, sourceNotes: [],
  draft: { fields: [], missing: [], conflicts: [], notes: [], input: {}, unrecognizedColumns: [] },
 };
 const ordered = evidence.map((item, index) => ({ item, index, at: Date.parse(item.createdAt || '') || 0 }))
  .sort((a, b) => b.at - a.at || b.index - a.index);
 // Keep the latest record of each input channel; an older CSV cannot silently replace its latest revision.
 const candidates = ['CSV', 'AGRI_INPUT'].map(type => ordered.find(row => row.item.type === type)).filter(Boolean)
  .sort((a, b) => b!.at - a!.at || b!.index - a!.index);
 const allowed = new Map(fields.filter(field => !forbidden.has(field.key)).map(field => [field.key, field]));
 for (const row of candidates) {
  const item = row!.item, details = object(item.details);
  if (!details) continue;
  if (!/已核对|已确认/.test(item.source || '') || strings(details.conflicts).length) {
   result.sourceNotes.push(`${item.label || item.type}尚有待核对内容，请核对后再用于规划。`);
   continue;
  }
  const rawFields = Array.isArray(details.fields) ? details.fields.map(object).filter(Boolean) : [];
  const metadata = new Map(rawFields.filter(value => typeof value!.key === 'string').map(value => [value!.key as string, value!]));
  const input = object(item.type === 'CSV' ? details.input : details.situation);
  const entries = input ? Object.entries(input) : rawFields.map(value => [value!.key, value!.value] as [unknown, unknown]);
  let added = 0;
  for (const [rawKey, value] of entries) {
   if (typeof rawKey !== 'string' || forbidden.has(rawKey)) continue;
   const spec = allowed.get(rawKey);
   if (!spec || edited.has(rawKey) || !empty(current[rawKey]) || Object.prototype.hasOwnProperty.call(result.patch, rawKey)) continue;
   if (empty(value) || !scalar(value) || (spec.kind === 'NUMBER' && !Number.isFinite(Number(value)))) continue;
   const original = metadata.get(rawKey);
   const restored: SituationFieldValue = {
    key: rawKey, label: spec.label, unit: spec.unit, value,
    source: typeof original?.source === 'string' ? original.source : 'USER',
    rawText: typeof original?.rawText === 'string' ? original.rawText : null,
   };
   result.patch[rawKey] = value; result.draft.fields.push(restored);
   result.sources[rawKey] = { evidenceId: item.id, evidenceSource: item.source, fieldSource: restored.source };
   added++;
  }
  if (added) {
   result.sourceNotes.push(`沿用${item.label || item.type} ${added} 项；来源：${item.source}。上传农情仍需结合现场复核，非传感器实测。`);
   result.draft.notes.push(...strings(details.notes));
   result.draft.unrecognizedColumns!.push(...strings(details.unrecognizedColumns));
  }
 }
 result.draft.input = { ...result.patch };
 result.draft.missing = fields.filter(field => empty(result.patch[field.key]) && empty(current[field.key])).map(field => field.label);
 result.draft.notes.push(...result.sourceNotes);
 return result;
}
