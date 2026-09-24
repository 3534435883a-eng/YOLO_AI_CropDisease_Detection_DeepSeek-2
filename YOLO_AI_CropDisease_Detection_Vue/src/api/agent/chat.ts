import { Session } from '/@/utils/storage';

/**
 * 智能体会话（SSE）客户端。
 *
 * 后端 `/ai/agent/chat` 是 **POST + text/event-stream**，浏览器原生 `EventSource` 只支持 GET，
 * 因此这里用 `fetch` + `ReadableStream` 手工解析 SSE 帧。
 *
 * 帧格式（Spring `SseEmitter` 输出）：
 * ```
 * event:step
 * data:{"type":"step","stepNo":1,...}
 *
 * ```
 * 分片不保证落在帧边界上，所以必须跨 chunk 缓冲，不能对单个 chunk 直接 split。
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
	let response: Response;
	try {
		const headers: Record<string, string> = {
			'Content-Type': 'application/json;charset=UTF-8',
			Accept: 'text/event-stream',
		};
		const token = Session.get('token');
		if (token) {
			headers['Authorization'] = `${token}`;
		}
		response = await fetch(ENDPOINT, {
			method: 'POST',
			headers,
			body: JSON.stringify(request),
			signal,
		});
	} catch (error) {
		if (isAbort(error)) {
			handlers.onClosed();
			return;
		}
		handlers.onFatal('无法连接智能体服务，请确认后端已在 9999 端口启动。');
		return;
	}

	if (!response.ok) {
		handlers.onFatal(`智能体服务返回 HTTP ${response.status}，请检查后端日志。`);
		return;
	}
	if (!response.body) {
		handlers.onFatal('当前浏览器不支持流式响应（缺少 ReadableStream）。');
		return;
	}

	const reader = response.body.getReader();
	const decoder = new TextDecoder('utf-8');
	let buffer = '';
	try {
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			buffer += decoder.decode(value, { stream: true });
			// SSE 帧之间以空行分隔；\n\n 与 \r\n\r\n 都要兼容。
			let boundary = findFrameBoundary(buffer);
			while (boundary.index >= 0) {
				dispatchFrame(buffer.slice(0, boundary.index), handlers);
				buffer = buffer.slice(boundary.index + boundary.length);
				boundary = findFrameBoundary(buffer);
			}
		}
		buffer += decoder.decode();
		if (buffer.trim()) {
			dispatchFrame(buffer, handlers);
		}
		handlers.onClosed();
	} catch (error) {
		if (isAbort(error)) {
			handlers.onClosed();
			return;
		}
		handlers.onFatal('流式连接中断，请重试。');
	} finally {
		reader.releaseLock();
	}
}

function findFrameBoundary(text: string): { index: number; length: number } {
	const lf = text.indexOf('\n\n');
	const crlf = text.indexOf('\r\n\r\n');
	if (lf < 0 && crlf < 0) return { index: -1, length: 0 };
	if (crlf >= 0 && (lf < 0 || crlf < lf)) return { index: crlf, length: 4 };
	return { index: lf, length: 2 };
}

function dispatchFrame(frame: string, handlers: AgentStreamHandlers): void {
	let eventName = 'message';
	const dataLines: string[] = [];
	for (const rawLine of frame.split(/\r?\n/)) {
		if (!rawLine || rawLine.startsWith(':')) continue;
		const separator = rawLine.indexOf(':');
		const field = separator < 0 ? rawLine : rawLine.slice(0, separator);
		let value = separator < 0 ? '' : rawLine.slice(separator + 1);
		if (value.startsWith(' ')) value = value.slice(1);
		if (field === 'event') eventName = value;
		else if (field === 'data') dataLines.push(value);
	}
	if (!dataLines.length) return;
	let payload: Record<string, unknown>;
	try {
		payload = JSON.parse(dataLines.join('\n')) as Record<string, unknown>;
	} catch {
		handlers.onFatal('收到无法解析的流式数据。');
		return;
	}
	// 后端把真实事件名写在 event 行；data 里也带一份 type 作为兜底。
	const type = String(payload.type || eventName || 'message');
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

function isAbort(error: unknown): boolean {
	return error instanceof DOMException && error.name === 'AbortError';
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

export function fieldTypeLabel(fieldType?: string): string {
	if (fieldType === 'SYMPTOM') return '症状';
	if (fieldType === 'CAUSE') return '诱因';
	if (fieldType === 'CONTROL') return '防治';
	return '其他';
}
