import axios from 'axios';

export type AiMessage = {
	role: 'system' | 'user' | 'assistant';
	content: string;
};

type AiChatPayload = {
	content: string;
	model: string;
};

type Result<T> = {
	code: string;
	msg: string;
	data: T;
};

export async function requestAiChat(messages: AiMessage[]): Promise<AiChatPayload> {
	const response = await axios.post<Result<AiChatPayload>>('/api/ai/chat', { messages });
	if (response.data.code !== '0') {
		throw new Error(response.data.msg || 'AI 服务暂时不可用');
	}
	return response.data.data;
}
