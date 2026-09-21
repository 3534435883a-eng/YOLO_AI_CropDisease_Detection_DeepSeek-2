import request from '/@/utils/request';

export type AgentRunId = number | string;

export type AgentRunStatus = 'DRAFT' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | string;

export interface AgentRun {
	id: AgentRunId;
	name?: string;
	runName?: string;
	greenhouseName?: string;
	cropName?: string;
	cropType?: string;
	status?: AgentRunStatus;
	progress?: number;
	currentStep?: number;
	totalSteps?: number;
	startedAt?: string;
	updatedAt?: string;
	[key: string]: unknown;
}

export interface AgentMetric {
	code?: string;
	label?: string;
	name?: string;
	value?: number | string | null;
	unit?: string;
	status?: string;
	[key: string]: unknown;
}

export interface AgentAlert {
	id?: AgentRunId;
	level?: string;
	severity?: string;
	title?: string;
	message?: string;
	description?: string;
	createdAt?: string;
	[key: string]: unknown;
}

export interface AgentDevice {
	code: string;
	name?: string;
	label?: string;
	mode?: string;
	controlMode?: string;
	manual?: boolean;
	enabled?: boolean;
	status?: string;
	[key: string]: unknown;
}

export interface AgentResource {
	code?: string;
	label?: string;
	name?: string;
	value?: number | string | null;
	unit?: string;
	delta?: number | string | null;
	[key: string]: unknown;
}

export interface AgentRunSummary {
	run?: AgentRun;
	status?: AgentRunStatus;
	currentState?: Record<string, unknown>;
	state?: Record<string, unknown>;
	metrics?: AgentMetric[] | Record<string, unknown>;
	devices?: AgentDevice[];
	deviceStates?: AgentDevice[];
	alerts?: AgentAlert[];
	resources?: AgentResource[] | Record<string, unknown>;
	strategy?: string;
	strategySummary?: string;
	updatedAt?: string;
	[key: string]: unknown;
}

export interface AgentComparisonItem {
	code?: string;
	label?: string;
	name?: string;
	baseline?: number | string | null;
	strategy?: number | string | null;
	change?: number | string | null;
	unit?: string;
	[key: string]: unknown;
}

export interface AgentRunComparison {
	items?: AgentComparisonItem[];
	comparisons?: AgentComparisonItem[];
	baseline?: Record<string, unknown>;
	strategy?: Record<string, unknown>;
	resources?: AgentResource[] | Record<string, unknown>;
	[key: string]: unknown;
}

export interface CreateAgentRunPayload {
	greenhouseId?: AgentRunId;
	cropCode?: string;
	cropName?: string;
	runName?: string;
	[key: string]: unknown;
}

export interface ManualDevicePayload {
	mode: 'AUTO' | 'MANUAL';
	enabled: boolean;
	[key: string]: unknown;
}

interface ResultEnvelope<T> {
	code?: number | string;
	msg?: string;
	data?: T;
}

const isResultEnvelope = <T>(value: unknown): value is ResultEnvelope<T> => {
	return Boolean(value && typeof value === 'object' && ('code' in value || 'data' in value));
};

const unwrap = <T>(response: unknown): T => {
	if (!isResultEnvelope<T>(response)) return response as T;

	const code = response.code;
	if (code !== undefined && code !== 0 && code !== '0' && code !== 200 && code !== '200') {
		throw new Error(response.msg || '模拟服务请求失败');
	}
	return response.data as T;
};

const runPath = (runId: AgentRunId) => `/api/agent/runs/${encodeURIComponent(String(runId))}`;

export const getActiveAgentRun = async (): Promise<AgentRun | null> => {
	return unwrap<AgentRun | null>(await request.get('/api/agent/runs/active'));
};

export const getAgentRunSummary = async (runId: AgentRunId): Promise<AgentRunSummary> => {
	return unwrap<AgentRunSummary>(await request.get(`${runPath(runId)}/summary`));
};

export const getAgentRunComparison = async (runId: AgentRunId): Promise<AgentRunComparison> => {
	return unwrap<AgentRunComparison>(await request.get(`${runPath(runId)}/comparison`));
};

export const createAgentRun = async (payload: CreateAgentRunPayload = {}): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post('/api/agent/runs', payload));
};

export const startAgentRun = async (runId: AgentRunId): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post(`${runPath(runId)}/start`));
};

export const pauseAgentRun = async (runId: AgentRunId): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post(`${runPath(runId)}/pause`));
};

export const stepAgentRun = async (runId: AgentRunId): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post(`${runPath(runId)}/step`));
};

export const resetAgentRun = async (runId: AgentRunId): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post(`${runPath(runId)}/reset`));
};

export const replayAgentRun = async (runId: AgentRunId): Promise<AgentRun> => {
	return unwrap<AgentRun>(await request.post(`${runPath(runId)}/replay`));
};

export const setAgentDeviceManualMode = async (
	runId: AgentRunId,
	deviceCode: string,
	payload: ManualDevicePayload
): Promise<AgentRunSummary | AgentDevice> => {
	const path = `${runPath(runId)}/devices/${encodeURIComponent(deviceCode)}/manual`;
	return unwrap<AgentRunSummary | AgentDevice>(await request.post(path, payload));
};

export const importAgentVisionEvent = async (runId: AgentRunId, payload: { sourceType?: string; sourceRecordId: AgentRunId }) => {
	return unwrap<AgentRunSummary>(await request.post(`${runPath(runId)}/vision-events`, payload));
};

export const explainAgentRun = async (runId: AgentRunId, question?: string) => {
	return unwrap<{ content: string; source?: string; runId?: string }>(await request.post(`${runPath(runId)}/explanation`, { question }));
};
