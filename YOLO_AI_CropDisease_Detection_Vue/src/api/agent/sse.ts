import { Session } from '/@/utils/storage';

/**
 * 通用 SSE 传输层（POST + text/event-stream）。
 *
 * 浏览器原生 `EventSource` 只支持 GET，所以这里用 `fetch` + `ReadableStream` 手工解析帧。
 *
 * 帧格式（Spring `SseEmitter` 输出）：
 * ```
 * event:step
 * data:{"type":"step","stepNo":1,...}
 *
 * ```
 *
 * **分片不保证落在帧边界上**，所以必须跨 chunk 缓冲，不能对单个 chunk 直接 split。
 * 这是这个文件存在的唯一理由：既有的 `chat.ts` 已经把这段易错逻辑写对了，
 * 推演接口需要同一段逻辑。复制一份的代价不是多几十行，而是**两份边界处理会各自腐坏**，
 * 而它的失败模式是静默丢帧——界面上表现为"回答莫名其妙少了半段"，很难查到传输层。
 *
 * 解码留给调用方：`chat.ts` 收到的是 JSON，推演收到的是纯文本增量，两者对 `data` 的解释不同。
 */

/** 一个已解析的 SSE 帧。 */
export interface SseFrame {
	/** `event:` 行的值；没有该行时为 `message`。 */
	event: string;
	/** `data:` 行的内容；多行 `data:` 按规范用换行连接。 */
	data: string;
}

export interface SseHandlers {
	onFrame: (frame: SseFrame) => void;
	/** 连接层失败（网络、HTTP 状态），与业务层的结果区分开。 */
	onFatal: (message: string) => void;
	onClosed: () => void;
}

export interface SseRequest {
	url: string;
	body: unknown;
	signal?: AbortSignal;
	/** 连接失败提示。默认值沿用决策助手的措辞，其它入口应覆盖成自己的排查指引。 */
	connectErrorMessage?: string;
	httpErrorMessage?: (status: number) => string;
	interruptedMessage?: string;
}

export async function streamSse(request: SseRequest, handlers: SseHandlers): Promise<void> {
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
		response = await fetch(request.url, {
			method: 'POST',
			headers,
			body: JSON.stringify(request.body),
			signal: request.signal,
		});
	} catch (error) {
		if (isAbort(error)) {
			handlers.onClosed();
			return;
		}
		handlers.onFatal(request.connectErrorMessage ?? '无法连接服务端，请确认后端已在 9999 端口启动。');
		return;
	}

	if (!response.ok) {
		handlers.onFatal(
			request.httpErrorMessage
				? request.httpErrorMessage(response.status)
				: `服务端返回 HTTP ${response.status}，请检查后端日志。`
		);
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
				handlers.onFrame(parseFrame(buffer.slice(0, boundary.index)));
				buffer = buffer.slice(boundary.index + boundary.length);
				boundary = findFrameBoundary(buffer);
			}
		}
		buffer += decoder.decode();
		if (buffer.trim()) {
			handlers.onFrame(parseFrame(buffer));
		}
		handlers.onClosed();
	} catch (error) {
		if (isAbort(error)) {
			handlers.onClosed();
			return;
		}
		handlers.onFatal(request.interruptedMessage ?? '流式连接中断，请重试。');
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

/** 拆出 `event:` 与 `data:` 字段。以 `:` 开头的是注释/心跳，按规范忽略。 */
function parseFrame(frame: string): SseFrame {
	let event = 'message';
	const dataLines: string[] = [];
	for (const rawLine of frame.split(/\r?\n/)) {
		if (!rawLine || rawLine.startsWith(':')) continue;
		const separator = rawLine.indexOf(':');
		const field = separator < 0 ? rawLine : rawLine.slice(0, separator);
		let value = separator < 0 ? '' : rawLine.slice(separator + 1);
		if (value.startsWith(' ')) value = value.slice(1);
		if (field === 'event') event = value;
		else if (field === 'data') dataLines.push(value);
	}
	return { event, data: dataLines.join('\n') };
}

function isAbort(error: unknown): boolean {
	return error instanceof DOMException && error.name === 'AbortError';
}
