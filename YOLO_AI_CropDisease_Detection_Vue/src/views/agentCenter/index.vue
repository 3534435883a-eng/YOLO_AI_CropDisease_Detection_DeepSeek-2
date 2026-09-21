<template>
	<div class="agent-center">
		<header class="command-header">
			<div>
				<p class="eyebrow">TOMATO GREENHOUSE / SIMULATION</p>
				<h2>智能体指挥中心</h2>
				<p class="header-copy">以规则推演驱动虚拟设备，所有数值均为来源明确的模拟结果。</p>
			</div>
			<div class="run-status">
				<span class="status-dot" :class="`status-${runStatus.toLowerCase()}`"></span>
				<div>
					<strong>{{ runStatusLabel }}</strong>
					<span>{{ simulationTime }}</span>
				</div>
			</div>
		</header>

		<section v-if="!agentStore.hasActiveRun" class="empty-run">
			<div class="empty-icon"><i class="iconfontjs icon-znws"></i></div>
			<div>
				<p class="eyebrow">准备就绪</p>
				<h3>创建 8号温室番茄推演</h3>
				<p>系统会从历史温室基线建立独立运行、虚拟耗材和五类设备；不会修改旧温室或库存记录。</p>
			</div>
			<el-button type="primary" :loading="agentStore.isActionPending" @click="createRun">创建模拟</el-button>
		</section>

		<template v-else>
			<section class="control-band">
				<div class="control-meta">
					<span>8号温室</span>
					<el-divider direction="vertical" />
					<span>番茄 · 开花坐果期</span>
					<el-divider direction="vertical" />
					<span>第 {{ currentStep }} / 96 步</span>
				</div>
				<div class="command-actions">
					<el-button v-if="runStatus !== 'RUNNING'" type="primary" :loading="isAction('start')" @click="perform('start')">启动自动运行</el-button>
					<el-button v-else type="warning" plain :loading="isAction('pause')" @click="perform('pause')">暂停</el-button>
					<el-button :loading="isAction('step')" @click="perform('step')">单步推演</el-button>
					<el-tooltip content="重新从本次运行的历史基线开始，不修改旧业务数据" placement="top">
						<el-button :loading="isAction('reset')" @click="perform('reset')">重置</el-button>
					</el-tooltip>
					<el-button :loading="isAction('replay')" @click="perform('replay')">回放</el-button>
				</div>
			</section>

			<section class="overview-grid">
				<article v-for="metric in keyMetrics" :key="metric.code" class="metric-card">
					<div class="metric-label"><span>{{ metric.label }}</span><el-tag size="small" effect="plain">{{ metric.source }}</el-tag></div>
					<strong>{{ metric.value }}</strong>
					<p>{{ metric.description }}</p>
				</article>
			</section>

			<section class="workspace-grid">
				<div class="panel decision-panel">
					<div class="panel-heading">
						<div>
							<p class="eyebrow">DECISION TRACE</p>
							<h3>当前规则结论</h3>
						</div>
						<el-tag :type="riskTagType" effect="plain">{{ riskLabel }}</el-tag>
					</div>
					<p class="decision-copy">{{ strategySummary }}</p>
					<div class="risk-lines">
						<div>
							<div class="line-label"><span>环境风险</span><strong>{{ environmentRisk }}%</strong></div>
							<el-progress :percentage="environmentRisk" :stroke-width="9" :show-text="false" :color="riskColor" />
						</div>
						<div>
							<div class="line-label"><span>病害环境压力</span><strong>{{ diseasePressure }}%</strong></div>
							<el-progress :percentage="diseasePressure" :stroke-width="9" :show-text="false" :color="riskColor" />
						</div>
					</div>
					<div class="rule-note">策略按风险、资源消耗和动作频率排序。AI 只能解释已保存的规则结论，不能直接下达执行指令。</div>
				</div>

				<div class="panel comparison-panel">
					<div class="panel-heading">
						<div>
							<p class="eyebrow">COUNTERFACTUAL</p>
							<h3>自动策略对比</h3>
						</div>
						<el-tag type="info" effect="plain">同一初始条件</el-tag>
					</div>
					<div v-if="comparisonItems.length" class="comparison-list">
						<div v-for="item in comparisonItems" :key="item.code" class="comparison-row">
							<div><strong>{{ item.label }}</strong><span>{{ item.unit }}</span></div>
							<div class="comparison-values"><span>保持现状 {{ item.baseline }}</span><strong>自动 {{ item.strategy }}</strong></div>
						</div>
					</div>
					<p v-else class="muted">完成至少一个虚拟步后，将显示“保持现状”与自动策略的同期对比。</p>
				</div>

				<div class="panel devices-panel">
					<div class="panel-heading">
						<div>
							<p class="eyebrow">VIRTUAL ACTUATORS</p>
							<h3>设备协同</h3>
						</div>
						<el-tag type="info" effect="plain">模拟执行</el-tag>
					</div>
					<div class="device-list">
						<div v-for="device in devices" :key="device.code" class="device-row">
							<div class="device-status" :class="device.enabled ? 'is-on' : ''"></div>
							<div class="device-copy"><strong>{{ device.name }}</strong><span>{{ device.modeLabel }} · {{ device.healthLabel }}</span></div>
							<el-switch :model-value="device.enabled" :disabled="!device.canControl || agentStore.isActionPending" @change="takeOver(device, Boolean($event))" />
							<el-button v-if="device.controlMode === 'MANUAL'" link type="primary" @click="release(device)">自动</el-button>
						</div>
					</div>
				</div>

				<div class="panel resource-panel">
					<div class="panel-heading">
						<div><p class="eyebrow">RUN-LOCAL STOCK</p><h3>虚拟耗材</h3></div>
						<el-tag type="info" effect="plain">不扣旧库存</el-tag>
					</div>
					<div class="resource-list">
						<div v-for="resource in resources" :key="resource.code" class="resource-row">
							<span>{{ resource.name }}</span><strong>{{ resource.value }} {{ resource.unit }}</strong>
						</div>
					</div>
					<p class="resource-note">水、CO2 与能源仅属于本次运行；动作与扣减流水在同一事务中保存。</p>
				</div>
			</section>

			<section class="alerts-panel panel">
				<div class="panel-heading"><div><p class="eyebrow">ALERTS</p><h3>当前告警</h3></div><span class="alert-count">{{ alerts.length }}</span></div>
				<div v-if="alerts.length" class="alert-list"><div v-for="alert in alerts" :key="alert.id || alert.message" class="alert-row"><el-tag :type="alertType(alert.severity)">{{ alert.severity || 'INFO' }}</el-tag><span>{{ alert.message || alert.description || alert.title }}</span></div></div>
				<p v-else class="muted">当前没有需要确认的风险、库存或设备告警。</p>
			</section>
		</template>
	</div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useUserInfo } from '/@/stores/userInfo';
import { AgentDevice } from '/@/api/agent';
import { useAgentRunStore } from '/@/stores/agentRun';

const agentStore = useAgentRunStore();
const userStore = useUserInfo();
let refreshTimer: number | undefined;

const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' ? value as Record<string, unknown> : {};
const asArray = <T>(value: unknown): T[] => Array.isArray(value) ? value as T[] : [];
const currentState = computed(() => asRecord(agentStore.summary?.currentState || agentStore.summary?.state));
const readNumber = (keys: string[], fallback = 0) => {
	for (const key of keys) {
		const value = currentState.value[key];
		const numberValue = typeof value === 'number' ? value : Number(value);
		if (Number.isFinite(numberValue)) return numberValue;
	}
	return fallback;
};
const runStatus = computed(() => String(agentStore.activeRun?.status || agentStore.summary?.status || 'READY').toUpperCase());
const runStatusLabel = computed(() => ({ RUNNING: '自动模拟运行中', PAUSED: '模拟已暂停', COMPLETED: '模拟完成', READY: '等待创建' }[runStatus.value] || '模拟待命'));
const currentStep = computed(() => Number(agentStore.activeRun?.currentStep || agentStore.activeRun?.progress || 0));
const simulationTime = computed(() => String(agentStore.activeRun?.simulatedAt || agentStore.activeRun?.updatedAt || '尚未启动'));
const environmentRisk = computed(() => {
	return Math.max(0, Math.min(100, Math.round(readNumber(['environmentRisk', 'environment_risk'], 0))));
});
const diseasePressure = computed(() => {
	return Math.max(0, Math.min(100, Math.round(readNumber(['diseasePressure', 'disease_pressure'], 0))));
});
const riskLabel = computed(() => String(currentState.value.riskLevel || currentState.value.risk_level || 'LOW'));
const riskTagType = computed(() => riskLabel.value === 'HIGH' ? 'danger' : riskLabel.value === 'MEDIUM' ? 'warning' : 'success');
const riskColor = computed(() => riskLabel.value === 'HIGH' ? '#b84242' : riskLabel.value === 'MEDIUM' ? '#c68a25' : '#2d8a54');
const strategySummary = computed(() => String(agentStore.summary?.strategySummary || agentStore.summary?.strategy || '等待第一步规则推演。'));

const keyMetrics = computed(() => [
	{ code: 'temperature', label: '室内温度', value: `${readNumber(['temperatureC', 'temperature_c', 'temperature'], 24).toFixed(1)} C`, source: '模拟', description: '由环境模型推演' },
	{ code: 'humidity', label: '空气湿度', value: `${readNumber(['airHumidityPct', 'air_humidity_pct', 'airHumidity'], 75).toFixed(1)}%`, source: '模拟', description: '由环境模型推演' },
	{ code: 'vpd', label: 'VPD', value: `${readNumber(['vpdKpa', 'vpd_kpa'], .72).toFixed(2)} kPa`, source: '计算', description: '温湿度派生指标' },
	{ code: 'co2', label: 'CO2', value: `${readNumber(['co2Ppm', 'co2_ppm', 'co2Concentration'], 720).toFixed(0)} ppm`, source: '模拟', description: '受通风与补给约束' },
]);

const comparisonItems = computed(() => {
	const source = asArray<Record<string, unknown>>(agentStore.comparison?.items || agentStore.comparison?.comparisons);
	return source.map((item, index) => ({ code: String(item.code || index), label: String(item.label || item.name || '指标'), baseline: item.baseline ?? '--', strategy: item.strategy ?? '--', unit: String(item.unit || '') }));
});
const devices = computed(() => {
	const source = asArray<AgentDevice>(agentStore.summary?.devices || agentStore.summary?.deviceStates);
	return source.map((device) => {
		const row = device as AgentDevice & Record<string, unknown>;
		const actualState = String(row.actualState ?? row.actual_state ?? (row.enabled ? 'ON' : 'OFF')).toUpperCase();
		const controlMode = String(row.controlMode ?? row.control_mode ?? row.mode ?? 'AUTO').toUpperCase();
		const healthStatus = String(row.healthStatus ?? row.health_status ?? row.status ?? 'NORMAL').toUpperCase();
		return { ...row, name: String(row.name || row.label || row.deviceName || row.code), controlMode, healthStatus, enabled: actualState === 'ON' || row.enabled === true, modeLabel: controlMode === 'MANUAL' ? '人工接管' : '自动策略', healthLabel: ({ NORMAL: '正常', OFFLINE: '离线', FAULT: '故障' }[healthStatus] || healthStatus), canControl: healthStatus === 'NORMAL' };
	});
});
const resources = computed(() => asArray<Record<string, unknown>>(agentStore.summary?.resources).map((item, index) => ({ code: String(item.code || index), name: String(item.name || item.label || '资源'), value: item.value ?? item.availableQuantity ?? '--', unit: String(item.unit || '') })));
const alerts = computed(() => asArray<Record<string, unknown>>(agentStore.summary?.alerts));
const isAction = (name: string) => agentStore.actionName === name;
const alertType = (severity: unknown) => String(severity).toUpperCase() === 'HIGH' || String(severity).toUpperCase() === 'CRITICAL' ? 'danger' : String(severity).toUpperCase() === 'MEDIUM' ? 'warning' : 'info';

const createRun = async () => {
	try {
		await agentStore.createRun({ greenhouseId: 77, cropName: '番茄', cropCode: 'TOMATO', operatorUsername: userStore.userInfos.userName || 'operator' });
		ElMessage.success('番茄温室模拟已创建');
	} catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建模拟失败'); }
};
const perform = async (action: 'start' | 'pause' | 'step' | 'reset' | 'replay') => {
	try {
		await agentStore[action]();
		ElMessage.success(action === 'start' ? '已启动自动运行' : action === 'pause' ? '模拟已暂停' : action === 'step' ? '已完成一个虚拟步' : action === 'replay' ? '已从基线开始回放' : '已恢复初始基线');
	} catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败'); }
};
const takeOver = async (device: AgentDevice & { enabled: boolean }) => {
	try { await agentStore.setDeviceManualMode(device, { mode: 'MANUAL', enabled: !device.enabled, operatorUsername: userStore.userInfos.userName || 'operator' }); ElMessage.success('设备已切换为人工接管'); }
	catch (error) { ElMessage.error(error instanceof Error ? error.message : '设备接管失败'); }
};
const release = async (device: AgentDevice) => {
	try { await agentStore.setDeviceManualMode(device, { mode: 'AUTO', enabled: Boolean(device.enabled), operatorUsername: userStore.userInfos.userName || 'operator' }); ElMessage.success('设备已交还自动策略'); }
	catch (error) { ElMessage.error(error instanceof Error ? error.message : '交还自动策略失败'); }
};

onMounted(() => { agentStore.loadActiveRun(); refreshTimer = window.setInterval(() => agentStore.loadActiveRun(), 2000); });
onUnmounted(() => { if (refreshTimer !== undefined) window.clearInterval(refreshTimer); });
</script>

<style scoped lang="scss">
.agent-center { min-height: calc(100vh - 60px); padding: 24px; background: #f3f7f2; color: #1c2b22; }
.command-header, .control-band, .panel-heading, .run-status, .command-actions, .control-meta, .metric-label, .line-label, .device-row, .resource-row, .alert-row { display: flex; align-items: center; }
.command-header { justify-content: space-between; gap: 24px; padding: 4px 0 22px; border-bottom: 1px solid #d7e3d9; }
.eyebrow { margin: 0 0 6px; color: #708277; font-size: 11px; font-weight: 650; letter-spacing: 0; }
h2, h3 { margin: 0; letter-spacing: 0; } h2 { font-size: 27px; } h3 { font-size: 17px; }
.header-copy { margin: 7px 0 0; color: #617168; font-size: 13px; }
.run-status { min-width: 180px; gap: 10px; padding: 10px 12px; border: 1px solid #d7e6da; border-radius: 7px; background: #fff; }
.run-status strong, .run-status span { display: block; } .run-status strong { font-size: 13px; } .run-status span { margin-top: 3px; color: #718178; font-size: 11px; }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: #9ca89f; } .status-running { background: #2d8a54; box-shadow: 0 0 0 3px rgba(45,138,84,.14); } .status-paused { background: #c68a25; }
.empty-run { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 18px; max-width: 900px; margin: 78px auto; padding: 28px; border: 1px solid #d7e7da; border-radius: 8px; background: #fff; }
.empty-icon { display: grid; width: 52px; height: 52px; place-items: center; border-radius: 7px; background: #e4f1e7; color: #2d8a54; font-size: 25px; } .empty-run p { margin: 7px 0 0; color: #63756a; line-height: 1.6; font-size: 13px; }
.control-band { justify-content: space-between; gap: 12px; margin: 18px 0; padding: 12px 14px; border: 1px solid #dce8df; border-radius: 8px; background: #fff; } .control-meta { color: #5d6d63; font-size: 13px; } .command-actions { flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.overview-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 12px; margin-bottom: 14px; } .metric-card, .panel { border: 1px solid #dce8df; border-radius: 8px; background: #fff; } .metric-card { min-height: 132px; padding: 15px; } .metric-label { justify-content: space-between; gap: 8px; color: #627369; font-size: 13px; } .metric-card strong { display: block; margin: 22px 0 6px; font-size: 25px; font-weight: 650; } .metric-card p { margin: 0; color: #809087; font-size: 12px; }
.workspace-grid { display: grid; grid-template-columns: 1.15fr .85fr; gap: 14px; } .panel { padding: 17px; } .panel-heading { justify-content: space-between; gap: 12px; margin-bottom: 15px; }
.decision-copy { min-height: 66px; margin: 0 0 18px; color: #405147; line-height: 1.7; font-size: 14px; }.risk-lines { display: grid; gap: 15px; }.line-label { justify-content: space-between; margin-bottom: 7px; color: #627269; font-size: 13px; }.line-label strong { color: #26392d; }.rule-note, .resource-note { margin-top: 18px; color: #718178; font-size: 12px; line-height: 1.65; }
.comparison-list { display: grid; gap: 10px; }.comparison-row { display: flex; justify-content: space-between; gap: 16px; padding: 10px 0; border-bottom: 1px solid #edf2ee; }.comparison-row strong, .comparison-row span { display: block; }.comparison-row > div:first-child span { margin-top: 3px; color: #87968d; font-size: 11px; }.comparison-values { display: flex; gap: 10px; text-align: right; color: #738278; font-size: 12px; }.comparison-values strong { color: #2d8a54; }.devices-panel { grid-column: 1; }.resource-panel { grid-column: 2; }.device-list, .resource-list, .alert-list { display: grid; gap: 9px; }.device-row { gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2ee; }.device-status { width: 8px; height: 8px; border-radius: 50%; background: #a7b2aa; }.device-status.is-on { background: #2d8a54; box-shadow: 0 0 0 3px rgba(45,138,84,.14); }.device-copy { flex: 1; }.device-copy strong, .device-copy span { display: block; }.device-copy span { margin-top: 3px; color: #829188; font-size: 11px; }.resource-row { justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #edf2ee; color: #607066; font-size: 13px; }.resource-row strong { color: #24352b; }.alerts-panel { margin-top: 14px; }.alert-count { display: grid; min-width: 24px; height: 24px; place-items: center; border-radius: 50%; background: #edf3ee; color: #425548; font-size: 12px; }.alert-row { gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf2ee; color: #53645a; font-size: 13px; }.muted { margin: 8px 0 0; color: #84928a; line-height: 1.6; font-size: 13px; }
@media (max-width: 1050px) { .overview-grid { grid-template-columns: repeat(2,minmax(0,1fr)); }.workspace-grid { grid-template-columns: 1fr; }.devices-panel, .resource-panel { grid-column: auto; } }
@media (max-width: 680px) { .agent-center { padding: 14px; }.command-header, .control-band { align-items: flex-start; flex-direction: column; }.run-status { min-width: 0; }.command-actions { justify-content: flex-start; }.overview-grid { grid-template-columns: 1fr; }.empty-run { grid-template-columns: 1fr; margin: 32px 0; }.comparison-row { align-items: flex-start; flex-direction: column; }.comparison-values { text-align: left; } }
</style>
