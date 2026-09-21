import { defineStore } from 'pinia';
import {
	AgentDevice,
	AgentRun,
	AgentRunComparison,
	AgentRunId,
	AgentRunSummary,
	CreateAgentRunPayload,
	ManualDevicePayload,
	createAgentRun,
	getActiveAgentRun,
	getAgentRunComparison,
	getAgentRunSummary,
	pauseAgentRun,
	resetAgentRun,
	replayAgentRun,
	setAgentDeviceManualMode,
	startAgentRun,
	stepAgentRun,
} from '/@/api/agent';

type AsyncRunAction = (runId: AgentRunId) => Promise<AgentRun>;

const toMessage = (error: unknown, fallback: string): string => {
	if (error instanceof Error && error.message) return error.message;
	return fallback;
};

const statusCode = (error: unknown): number | undefined => {
	return (error as { response?: { status?: number } })?.response?.status;
};

const asRecord = (value: unknown): Record<string, unknown> | null => {
	return value && typeof value === 'object' ? (value as Record<string, unknown>) : null;
};

const resolveRun = (value: unknown): AgentRun | null => {
	const record = asRecord(value);
	if (!record) return null;
	if (record.id !== undefined || record.runId !== undefined) {
		return {
			...(record as AgentRun),
			id: (record.id ?? record.runId) as AgentRunId,
		};
	}
	return resolveRun(record.run ?? record.activeRun);
};

const isSummary = (value: unknown): value is AgentRunSummary => {
	const record = asRecord(value);
	return Boolean(record && ('metrics' in record || 'devices' in record || 'deviceStates' in record || 'alerts' in record));
};

export const useAgentRunStore = defineStore('agentRun', {
	state: () => ({
		activeRun: null as AgentRun | null,
		summary: null as AgentRunSummary | null,
		comparison: null as AgentRunComparison | null,
		loading: false,
		actionName: '',
		serviceAvailable: true,
		errorMessage: '',
	}),
	getters: {
		runId: (state): AgentRunId | null => state.activeRun?.id ?? null,
		hasActiveRun: (state): boolean => Boolean(state.activeRun?.id),
		isActionPending: (state): boolean => Boolean(state.actionName),
	},
	actions: {
		clearRun() {
			this.activeRun = null;
			this.summary = null;
			this.comparison = null;
		},
		async loadRunDetails(runId: AgentRunId) {
			const [summaryResult, comparisonResult] = await Promise.allSettled([
				getAgentRunSummary(runId),
				getAgentRunComparison(runId),
			]);

			if (summaryResult.status === 'fulfilled') {
				this.summary = summaryResult.value;
			}
			if (comparisonResult.status === 'fulfilled') {
				this.comparison = comparisonResult.value;
			}
		},
		async loadActiveRun() {
			this.loading = true;
			this.errorMessage = '';
			try {
				const payload = await getActiveAgentRun();
				const run = resolveRun(payload);
				this.serviceAvailable = true;
				this.activeRun = run;
				this.summary = null;
				this.comparison = null;

				if (run?.id !== undefined) {
					await this.loadRunDetails(run.id);
				}
			} catch (error) {
				this.clearRun();
				this.serviceAvailable = false;
				this.errorMessage = statusCode(error) === 404 ? '' : toMessage(error, '暂时无法连接模拟服务');
			} finally {
				this.loading = false;
			}
		},
		applyRunUpdate(payload: unknown) {
			const run = resolveRun(payload);
			if (run) {
				this.activeRun = { ...(this.activeRun || {}), ...run } as AgentRun;
			}
			if (isSummary(payload)) {
				this.summary = payload;
			}
		},
		async createRun(payload: CreateAgentRunPayload = {}) {
			this.actionName = 'create';
			this.errorMessage = '';
			try {
				const result = await createAgentRun(payload);
				this.serviceAvailable = true;
				this.applyRunUpdate(result);
				if (this.runId !== null) {
					await this.loadRunDetails(this.runId);
				} else {
					await this.loadActiveRun();
				}
				return this.activeRun;
			} catch (error) {
				this.serviceAvailable = false;
				this.errorMessage = toMessage(error, '创建模拟失败');
				throw error;
			} finally {
				this.actionName = '';
			}
		},
		async executeRunAction(name: string, action: AsyncRunAction) {
			if (this.runId === null) throw new Error('暂无可操作的模拟');

			this.actionName = name;
			this.errorMessage = '';
			try {
				const result = await action(this.runId);
				this.applyRunUpdate(result);
				await this.loadRunDetails(this.runId);
				return result;
			} catch (error) {
				this.errorMessage = toMessage(error, '模拟控制失败');
				throw error;
			} finally {
				this.actionName = '';
			}
		},
		start() {
			return this.executeRunAction('start', startAgentRun);
		},
		pause() {
			return this.executeRunAction('pause', pauseAgentRun);
		},
		step() {
			return this.executeRunAction('step', stepAgentRun);
		},
		reset() {
			return this.executeRunAction('reset', resetAgentRun);
		},
		replay() {
			return this.executeRunAction('replay', replayAgentRun);
		},
		async setDeviceManualMode(device: AgentDevice, payload: ManualDevicePayload) {
			if (this.runId === null) throw new Error('暂无可操作的模拟');

			this.actionName = `device:${device.code}`;
			this.errorMessage = '';
			try {
				const result = await setAgentDeviceManualMode(this.runId, device.code, payload);
				this.serviceAvailable = true;
				this.applyRunUpdate(result);
				await this.loadRunDetails(this.runId);
				return result;
			} catch (error) {
				this.errorMessage = toMessage(error, '设备控制失败');
				throw error;
			} finally {
				this.actionName = '';
			}
		},
	},
});
