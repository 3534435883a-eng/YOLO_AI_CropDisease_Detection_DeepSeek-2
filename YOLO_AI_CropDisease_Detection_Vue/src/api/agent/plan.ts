import request from '/@/utils/request';
import { SseFrame, streamSse } from './sse';

/**
 * 农事规划推演客户端。
 *
 * 后端 `/ai/agri/plan/*` 是**与决策助手并行的另一条通道**：它不检索、不强制引用、
 * 不因缺依据拒答，而是让模型用自己的知识做条件推演。两条通道的可信度来源完全不同，
 * 因此界面上的标注也必须不同——推演结果顶部固定带「模型推演，非实测、非知识库依据」。
 */

const BASE = '/api/ai/agri/plan';

/**
 * 字段定义（键名与后端 `SituationFields` 一致）。
 *
 * **由后端下发而不是前端写死**：加字段时前端不用改代码，
 * 也不会出现"后端有、前端没有"这种界面上根本填不了却不报错的不一致。
 */
export interface SituationFieldSpec {
	key: string;
	label: string;
	/**
	 * 所属分组。22 个字段平铺会让用户以为只有看得见的那几个，
	 * 分组定义同样来自后端登记表，前端不另抄一份。
	 */
	group: string;
	unit?: string | null;
	kind: 'TEXT' | 'NUMBER' | 'DATE';
	/** CSV 列名别名，仅供界面提示"支持哪些表头"。 */
	aliases?: string[];
	/** 数值区间描述，供表单校验提示。 */
	rangeText?: string;
}

export async function fetchSituationFields(): Promise<SituationFieldSpec[]> {
	return unwrap<SituationFieldSpec[]>(await request.get(`${BASE}/fields`));
}

/** 一条字段的取值与来路。`rawText` 是支撑它的原话，手填时为空。 */
export interface SituationFieldValue {
	key: string;
	label: string;
	unit?: string | null;
	value: unknown;
	source: 'USER' | 'PARSED' | string;
	/** 支撑这个取值的用户原话。**没有它就不该相信这个值**，界面上要显示出来。 */
	rawText?: string | null;
}

/** 三通道录入的统一回显形状，用户确认后才进推演。 */
export interface SituationDraft {
	fields: SituationFieldValue[];
	/** 没有值的字段（中文标签）。 */
	missing: string[];
	/** 同一字段被提到两次且不一致，交用户裁决。 */
	conflicts: string[];
	notes: string[];
	input: Record<string, unknown>;
	/** 仅 CSV 上传时有：认出来的列。 */
	mappedColumns?: string[];
	/** 仅 CSV 上传时有：**没能映射的列**，必须展示，否则用户以为那些数据进了推演。 */
	unrecognizedColumns?: string[];
	dataRowCount?: number;
}

export interface PlanBaseline {
	available: boolean;
	unavailableReason?: string | null;
	batchId?: string | null;
	seed?: number;
	days?: number;
	strategyLabel?: string | null;
	strategyCode?: string | null;
	marketableYieldKg?: number;
	yieldPerSquareMeter?: number;
	profitYuan?: number;
	waterUsedM3?: number;
	energyKwh?: number;
	highTemperatureMinutes?: number;
	/** 必读：说明这份数值不采用用户输入。 */
	scopeNote?: string;
	/** 窗口不足的解释（利润为负时非 null）。旁边的负利润数字必须靠它才不会被读反。 */
	windowNote?: string | null;
}

/** 结构化动作清单。模型没给出可解析结果时整体为 null。 */
export interface PlanStructured {
	conclusion?: string;
	actions?: { stage?: string; action?: string; trigger?: string; detail?: string }[];
	cautions?: string[];
	schedule?: { dayOffset?: number; stage?: string; task?: string }[];
	riskAlerts?: { risk?: string; window?: string; mitigation?: string }[];
	budget?: { note?: string };
}

export interface PlanFinalPayload {
	markdown: string;
	structured?: PlanStructured | null;
	/** 首行推演声明是否由系统补的（模型漏写）。 */
	bannerInjected: boolean;
	sections: string[];
	missingFields: string[];
	streamMode: 'STREAM' | 'FALLBACK' | string;
	fallbackReason?: string | null;
	elapsedMillis: number;
	baseline: PlanBaseline;
}

export interface PlanStreamHandlers {
	onStage: (phase: string, message: string) => void;
	onBaseline: (baseline: PlanBaseline) => void;
	/** 一个 token 增量；`reasoning` 为 true 表示属于思考过程，与正文分栏展示。 */
	onDelta: (text: string, reasoning: boolean) => void;
	/** **丢弃已收到的正文**：截断重试时会发，不处理的话新旧正文会被拼在一起。 */
	onReset: () => void;
	onFinal: (payload: PlanFinalPayload) => void;
	/** 落库成功后的记录编号，用于导出与回查。 */
	onRecord: (id: number) => void;
	onFatal: (message: string) => void;
	onClosed: () => void;
}

export interface PlanDeduceRequest {
	situation?: Record<string, unknown>;
	question?: string;
	seed?: number;
	days?: number;
}

export async function streamPlanDeduction(
	body: PlanDeduceRequest,
	handlers: PlanStreamHandlers,
	signal?: AbortSignal
): Promise<void> {
	await streamSse(
		{
			url: `${BASE}/deduce`,
			body,
			signal,
			connectErrorMessage: '无法连接推演服务，请确认后端已在 9999 端口启动。',
			httpErrorMessage: (status) => `推演服务返回 HTTP ${status}，请检查后端日志。`,
			interruptedMessage: '推演连接中断。已生成的部分不会保留，请重试。',
		},
		{
			onFrame: (frame) => dispatchPlanFrame(frame, handlers),
			onFatal: handlers.onFatal,
			onClosed: handlers.onClosed,
		}
	);
}

function dispatchPlanFrame(frame: SseFrame, handlers: PlanStreamHandlers): void {
	if (!frame.data) return;
	let payload: Record<string, unknown>;
	try {
		payload = JSON.parse(frame.data) as Record<string, unknown>;
	} catch {
		handlers.onFatal('收到无法解析的流式数据。');
		return;
	}
	switch (frame.event) {
		case 'stage':
			handlers.onStage(String(payload.phase ?? ''), String(payload.message ?? ''));
			return;
		case 'baseline':
			handlers.onBaseline(payload as unknown as PlanBaseline);
			return;
		case 'delta':
			handlers.onDelta(String(payload.text ?? ''), payload.reasoning === true);
			return;
		case 'reset':
			handlers.onReset();
			return;
		case 'final':
			handlers.onFinal(payload as unknown as PlanFinalPayload);
			return;
		case 'record':
			handlers.onRecord(Number(payload.id));
			return;
		case 'error':
			handlers.onFatal(String(payload.message || '推演执行失败'));
			return;
		default:
			return;
	}
}

/** 自然语言描述 → 待确认的农情草稿。**这不是最终输入**，用户确认后才调推演。 */
export async function parseSituation(text: string): Promise<SituationDraft> {
	return unwrap<SituationDraft>(await request.post(`${BASE}/situation/parse`, { text }));
}

/** CSV 上传 → 待确认的农情草稿 + 列映射报告。原地解析，不落盘。 */
export async function uploadSituation(file: File): Promise<SituationDraft> {
	const form = new FormData();
	form.append('file', file);
	return unwrap<SituationDraft>(
		await request.post(`${BASE}/situation/upload`, form, {
			headers: { 'Content-Type': 'multipart/form-data' },
		})
	);
}

export interface PlanHistoryItem {
	id: number;
	createdAt: string;
	seed: number;
	days: number;
	question?: string | null;
	streamMode: string;
	bannerInjected: boolean;
	baselineBatchId?: string | null;
	sections: string[];
	missingFields: string[];
	elapsedMs: number;
}

export async function fetchPlanHistory(limit = 20): Promise<PlanHistoryItem[]> {
	return unwrap<PlanHistoryItem[]>(await request.get(`${BASE}/history`, { params: { limit } }));
}

/** 导出地址。导出的是文档本身，不套 Result 信封，所以直接给浏览器下载。 */
export function planExportUrl(id: number): string {
	return `${BASE}/${id}/export`;
}

/** 模板下载地址。 */
export function planTemplateUrl(): string {
	return `${BASE}/template.csv`;
}

/**
 * 解开 `Result` 信封。
 *
 * 与 `./index.ts` 里的同名函数保持同一口径：`request` 的响应拦截器已经剥掉了一层 axios
 * 包装，所以这里拿到的**就是** `Result` 信封本身（`{code, msg, data}`），
 * 不能再按 axios 响应去读 `.data`——那会多剥一层，把真正的业务数据当成信封。
 */
interface ResultEnvelope<T> {
	code?: number | string;
	msg?: string;
	data?: T;
}

const isResultEnvelope = <T>(value: unknown): value is ResultEnvelope<T> => {
	return Boolean(value && typeof value === 'object' && ('code' in value || 'data' in value));
};

function unwrap<T>(response: unknown): T {
	if (!isResultEnvelope<T>(response)) return response as T;

	const code = response.code;
	if (code !== undefined && code !== 0 && code !== '0' && code !== 200 && code !== '200') {
		throw new Error(response.msg || '推演请求失败');
	}
	return response.data as T;
}
