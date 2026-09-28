import { SseFrame, streamSse } from './sse';

/**
 * 智能体会话（SSE）客户端。
 *
 * 传输层（POST + text/event-stream、跨 chunk 缓冲、帧边界处理）已抽到 `./sse`，
 * 与农事规划推演共用同一份实现——那段边界逻辑的失败模式是**静默丢帧**，
 * 界面上的表现是"回答少了半段"，复制第二份等于等着两份实现各自腐坏。
 *
 * 本文件只负责把帧里的 JSON 解释成 {@link AgentStepEvent}。
 */

export interface AgentChatRequest {
	question: string;
	crop?: string;
	sessionId?: string;
}

/** 引用条目，键名与后端 `CitationFormatter` 输出保持一致。 */
export interface AgentCitation {
	index?: number;
	diseaseName?: string;
	cropType?: string;
	fieldType?: string;
	chunkNo?: number;
	startOffset?: number;
	/**
	 * RRF 融合分。**不要当置信度用**：RRF 按构造只保留排名、丢弃分数量级，
	 * 实测负样本与真实提问的融合分几乎完全重叠。界面上只用于排序展示，不得渲染成"相关度百分比"。
	 */
	score?: number;
	sourceTable?: string;
	sourceId?: number | string;
	sourceCode?: string;
	sourceName?: string;
	sourceType?: string;
	sourceUrl?: string;
	sourceVersion?: string;
	snippet?: string;
	label?: string;
}

export interface AgentStepEvent {
	type: 'step' | 'final' | 'error' | string;
	stepNo: number;
	toolName?: string | null;
	message?: string;
	data?: Record<string, unknown> | null;
}

export interface AgentStreamHandlers {
	onEvent: (event: AgentStepEvent) => void;
	/** 连接层失败（网络、HTTP 状态、无法解析），与业务层的"拒答"区分开。 */
	onFatal: (message: string) => void;
	onClosed: () => void;
}

const ENDPOINT = '/api/ai/agent/chat';

export async function streamAgentChat(
	request: AgentChatRequest,
	handlers: AgentStreamHandlers,
	signal?: AbortSignal
): Promise<void> {
	await streamSse(
		{
			url: ENDPOINT,
			body: request,
			signal,
			connectErrorMessage: '无法连接智能体服务，请确认后端已在 9999 端口启动。',
			httpErrorMessage: (status) => `智能体服务返回 HTTP ${status}，请检查后端日志。`,
			interruptedMessage: '流式连接中断，请重试。',
		},
		{
			onFrame: (frame) => dispatchFrame(frame, handlers),
			onFatal: handlers.onFatal,
			onClosed: handlers.onClosed,
		}
	);
}

function dispatchFrame(frame: SseFrame, handlers: AgentStreamHandlers): void {
	if (!frame.data) return;
	let payload: Record<string, unknown>;
	try {
		payload = JSON.parse(frame.data) as Record<string, unknown>;
	} catch {
		handlers.onFatal('收到无法解析的流式数据。');
		return;
	}
	// 后端把真实事件名写在 event 行；data 里也带一份 type 作为兜底。
	const type = String(payload.type || frame.event || 'message');
	const stepNo = Number(payload.stepNo ?? 0);
	if (type === 'error') {
		handlers.onFatal(String(payload.message || '智能体执行失败'));
		return;
	}
	handlers.onEvent({
		type,
		stepNo: Number.isFinite(stepNo) ? stepNo : 0,
		toolName: (payload.toolName as string | null) ?? null,
		message: payload.message === undefined || payload.message === null ? '' : String(payload.message),
		data: (payload.data as Record<string, unknown> | null) ?? null,
	});
}

/** 从 step 事件的 data 中取出本次新增的引用。 */
export function readStepCitations(event: AgentStepEvent): AgentCitation[] {
	const data = event.data || {};
	const stepCitations = data.stepCitations;
	if (Array.isArray(stepCitations)) return stepCitations as AgentCitation[];
	const all = data.citations;
	return Array.isArray(all) ? (all as AgentCitation[]) : [];
}

/** 最终回答携带的全局引用列表。 */
export function readFinalCitations(event: AgentStepEvent): AgentCitation[] {
	const data = event.data || {};
	const all = data.citations;
	return Array.isArray(all) ? (all as AgentCitation[]) : [];
}

/**
 * 知识块类别 → 界面用语。
 *
 * 与后端 `KnowledgeChunk.FieldType` 一一对应；新增类别时两边必须同时改，
 * 否则界面上会整片显示成"其他"（本函数是唯一的中文映射点）。
 */
export function fieldTypeLabel(fieldType?: string): string {
	if (fieldType === 'SYMPTOM') return '症状';
	if (fieldType === 'CAUSE') return '诱因';
	if (fieldType === 'CONTROL') return '防治';
	if (fieldType === 'CULTIVATION') return '栽培管理';
	if (fieldType === 'WATER_FERT') return '水肥管理';
	if (fieldType === 'ENVIRONMENT') return '环境调控';
	if (fieldType === 'CTRL_AGRI') return '农业防治';
	if (fieldType === 'CTRL_PHYS') return '物理防治';
	if (fieldType === 'CTRL_BIO') return '生物防治';
	if (fieldType === 'CTRL_CHEM') return '化学防治';
	return '其他';
}
