<template>
	<div class="environment-page">
		<header class="page-toolbar">
			<div>
				<p class="eyebrow">8 号温室 · 番茄</p>
				<h2>番茄温室环境监测</h2>
				<p class="subtitle">{{ sourceLabel }}</p>
			</div>
			<div class="toolbar-actions">
				<el-select v-model="selectedGreenhouse" class="greenhouse-select" aria-label="选择温室">
					<el-option v-for="greenhouse in greenhouses" :key="greenhouse" :label="greenhouse" :value="greenhouse" />
				</el-select>
				<el-button :loading="agentStore.loading" @click="agentStore.loadActiveRun()">刷新状态</el-button>
				<el-button type="primary" @click="router.push('/agentCenter')">进入指挥中心</el-button>
			</div>
		</header>
		<el-alert v-if="!agentStore.serviceAvailable" title="仿真服务暂不可用" :description="agentStore.errorMessage || '无法读取运行数据。页面不会使用默认环境值填充。'" type="error" :closable="false" show-icon class="service-alert" />
		<el-alert v-else-if="isSimulationGreenhouse && !agentStore.hasActiveRun" title="尚无活动仿真运行" description="下方环境值与设备状态仅在创建规则仿真后显示；当前没有实测遥测数据。" type="info" :closable="false" show-icon class="service-alert" />
		<el-alert v-else-if="isSimulationGreenhouse && agentStore.hasActiveRun && !hasEnvironmentState" title="环境快照尚未加载" description="运行记录存在，但当前没有可用的环境状态字段。" type="warning" :closable="false" show-icon class="service-alert" />

		<el-alert
			v-if="selectedGreenhouse !== '8号温室'"
			title="当前智能体仅覆盖 8号温室番茄场景；所选温室暂无可读取的智能体运行数据。"
			type="info"
			:closable="false"
			show-icon
		/>

		<section class="metrics-grid" aria-label="环境指标">
			<article v-for="metric in metrics" :key="metric.code" class="metric-item">
				<div class="metric-heading">
					<i :class="`iconfontjs ${metric.icon}`"></i>
					<span>{{ metric.label }}</span>
				</div>
				<strong>{{ metric.value }}</strong>
				<small>{{ metric.hint }}</small>
			</article>
		</section>

		<section class="content-grid">
			<div class="panel device-panel">
				<div class="panel-header">
					<div>
						<p class="eyebrow">执行层</p>
						<h3>虚拟设备协同</h3>
					</div>
					<el-tag effect="plain" type="info">仅模拟执行</el-tag>
				</div>
				<div class="device-grid">
					<article v-for="device in devices" :key="device.code" class="device-item">
						<div>
							<div class="device-name">{{ device.name }}</div>
							<p>{{ device.description }}</p>
							<el-tag size="small" :type="healthType(device.healthStatus)">{{ healthLabel(device.healthStatus) }}</el-tag>
							<el-tag size="small" effect="plain">{{ modeLabel(device.controlMode) }}</el-tag>
						</div>
						<div class="device-control">
							<el-switch
								:model-value="device.enabled"
								:disabled="!canTakeOver(device) || agentStore.isActionPending"
								active-text="开"
								inactive-text="关"
								@change="toggleDevice(device, Boolean($event))"
							/>
							<el-button v-if="device.controlMode === 'MANUAL'" link type="primary" @click="releaseDevice(device)">交还自动</el-button>
						</div>
					</article>
				</div>
				<p v-if="!devices.length" class="empty-copy">{{ isSimulationGreenhouse ? '尚无保存的设备快照。' : '该温室尚未接入智能体仿真。' }}</p>
			</div>

			<aside class="panel strategy-panel">
				<p class="eyebrow">规则推演</p>
				<h3>当前策略</h3>
				<p class="strategy-copy">{{ strategySummary }}</p>
				<div class="risk-row">
					<span>环境风险 <b>{{ environmentRisk === null ? '—' : `${environmentRisk}%` }}</b></span>
					<el-progress v-if="environmentRisk !== null" :percentage="environmentRisk" :stroke-width="8" :color="riskColor" :show-text="false" />
				</div>
				<div class="risk-row">
					<span>病害环境压力 <b>{{ diseasePressure === null ? '—' : `${diseasePressure}%` }}</b></span>
					<el-progress v-if="diseasePressure !== null" :percentage="diseasePressure" :stroke-width="8" :color="riskColor" :show-text="false" />
				</div>
				<el-divider />
				<p class="disclaimer">AI 仅解释已保存的规则结论。视觉结果在人工确认前只作为待核验风险信号。</p>
			</aside>
		</section>
	</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { AgentDevice } from '/@/api/agent';
import { useAgentRunStore } from '/@/stores/agentRun';

const router = useRouter();
const agentStore = useAgentRunStore();
const greenhouses = ['1号温室', '2号温室', '3号温室', '4号温室', '5号温室', '6号温室', '7号温室', '8号温室', '9号温室'];
const selectedGreenhouse = ref('8号温室');
const isSimulationGreenhouse = computed(() => selectedGreenhouse.value === '8号温室');

const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' ? value as Record<string, unknown> : {};
const state = computed(() => asRecord(agentStore.summary?.currentState || agentStore.summary?.state));
const hasEnvironmentState = computed(() => Object.keys(state.value).length > 0);
const numberValue = (keys: string[]): number | null => {
	for (const key of keys) {
		const value = state.value[key];
		const parsed = typeof value === 'number' ? value : Number(value);
		if (value !== null && value !== undefined && value !== '' && Number.isFinite(parsed)) return parsed;
	}
	return null;
};
const format = (keys: string[], unit: string, digits = 1) => {
	const value = numberValue(keys);
	return value === null ? '—' : `${value.toFixed(digits)}${unit}`;
};
const metricHint = computed(() => agentStore.hasActiveRun ? '规则仿真快照 · 非实测' : '暂无可用数据');

const metrics = computed(() => [
	{ code: 'temperature', icon: 'icon-daqiwendu', label: '室内温度', value: format(['temperatureC', 'temperature_c', 'temperature'], ' ℃'), hint: metricHint.value },
	{ code: 'humidity', icon: 'icon-kongqishidu_kongqishidu', label: '空气湿度', value: format(['airHumidityPct', 'air_humidity_pct', 'airHumidity'], '%'), hint: metricHint.value },
	{ code: 'soil', icon: 'icon-turangshidu', label: '土壤水分', value: format(['soilMoisturePct', 'soil_moisture_pct', 'soilHumidity'], '%'), hint: metricHint.value },
	{ code: 'co2', icon: 'icon-eryanghuatan', label: 'CO₂', value: format(['co2Ppm', 'co2_ppm', 'co2Concentration'], ' ppm', 0), hint: metricHint.value },
	{ code: 'ph', icon: 'icon-turangPH', label: '土壤 pH', value: format(['soilPh', 'soil_ph'], '', 1), hint: metricHint.value },
	{ code: 'light', icon: 'icon-guangzhaoqiangdu', label: '光照', value: format(['lightPpfd', 'light_ppfd', 'lightIntensity'], ' PPFD', 0), hint: metricHint.value },
	{ code: 'vpd', icon: 'icon-huanjingjiance', label: 'VPD', value: format(['vpdKpa', 'vpd_kpa'], ' kPa', 2), hint: '规则计算 · 非实测' },
].map((metric) => isSimulationGreenhouse.value ? metric : { ...metric, value: '—', hint: '该温室暂无智能体数据' }));

const devices = computed(() => {
	if (!isSimulationGreenhouse.value) return [];
	const source = agentStore.summary?.devices || agentStore.summary?.deviceStates || [];
	return source.map((device) => {
		const row = device as AgentDevice & Record<string, unknown>;
		const actualState = String((row.actualState ?? row.actual_state ?? (row.enabled ? 'ON' : 'OFF'))).toUpperCase();
		const healthStatus = String(row.healthStatus ?? row.health_status ?? row.status ?? 'NORMAL').toUpperCase();
		const controlMode = String(row.controlMode ?? row.control_mode ?? row.mode ?? 'AUTO').toUpperCase();
		return {
			...row,
			name: String(row.name ?? row.label ?? row.deviceName ?? row.code),
			description: actualState === 'ON' ? '当前虚拟执行中' : '当前虚拟待机',
			enabled: actualState === 'ON' || row.enabled === true,
			healthStatus,
			controlMode,
		};
	});
});

const environmentRisk = computed(() => isSimulationGreenhouse.value ? numberValue(['environmentRisk', 'environment_risk']) : null);
const diseasePressure = computed(() => isSimulationGreenhouse.value ? numberValue(['diseasePressure', 'disease_pressure']) : null);
const riskScore = computed(() => Math.max(environmentRisk.value ?? 0, diseasePressure.value ?? 0));
const riskColor = computed(() => riskScore.value >= 70 ? '#b84242' : riskScore.value >= 45 ? '#c68a25' : '#2d8a54');
const strategySummary = computed(() => !isSimulationGreenhouse.value ? '智能体规则仿真当前仅关联 8 号温室；此页不显示其他温室的运行状态。' : String(agentStore.summary?.strategySummary || agentStore.summary?.strategy || '尚未创建运行。可在指挥中心启动番茄温室模拟。'));
const sourceLabel = computed(() => !isSimulationGreenhouse.value ? '所选温室暂无智能体数据' : agentStore.hasActiveRun ? '规则仿真与保存快照 · 非现场传感器数据' : agentStore.serviceAvailable ? '暂无活动仿真；当前无可展示的环境数据' : '仿真服务不可用 · 环境数据暂不可读取');

const modeLabel = (value: string) => value === 'MANUAL' ? '人工接管' : '自动策略';
const healthLabel = (value: string) => ({ NORMAL: '正常', OFFLINE: '离线', FAULT: '故障' }[value] || value);
const healthType = (value: string) => value === 'NORMAL' ? 'success' : value === 'OFFLINE' ? 'info' : 'danger';
const canTakeOver = (device: { healthStatus: string }) => isSimulationGreenhouse.value && device.healthStatus === 'NORMAL' && agentStore.hasActiveRun;

const toggleDevice = async (device: AgentDevice & { enabled: boolean; healthStatus: string }) => {
	try {
		await agentStore.setDeviceManualMode(device, { mode: 'MANUAL', enabled: !device.enabled });
		ElMessage.success('已记录人工接管，自动策略不会覆盖该设备');
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : '设备接管失败');
	}
};

const releaseDevice = async (device: AgentDevice) => {
	try {
		await agentStore.setDeviceManualMode(device, { mode: 'AUTO', enabled: Boolean(device.enabled) });
		ElMessage.success('设备已交还自动策略');
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : '交还自动策略失败');
	}
};

onMounted(() => agentStore.loadActiveRun());
</script>

<style scoped lang="scss">
.environment-page { padding: 28px clamp(20px, 2.5vw, 40px); color: #2c3028; background: #f7f3ea; min-height: calc(100vh - 60px); }
.service-alert { margin: 14px 0; }
.page-toolbar, .panel-header, .toolbar-actions, .device-control { display: flex; align-items: center; }
.page-toolbar { justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.toolbar-actions { flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.greenhouse-select { width: 132px; }
.eyebrow { margin: 0 0 4px; color: #6d7c72; font-size: 12px; }
h2, h3 { margin: 0; letter-spacing: 0; }
h2 { font-family: Georgia, 'Noto Serif SC', serif; font-size: 28px; }
h3 { font-size: 17px; }
.subtitle { margin: 6px 0 0; color: #607067; font-size: 13px; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
.metric-item, .panel { border: 1px solid #ded5c7; border-radius: 3px; background: #fffdf8; }
.metric-item { min-height: 118px; padding: 14px; }
.metric-heading { display: flex; align-items: center; gap: 8px; color: #5a6d60; font-size: 13px; }
.metric-heading i { color: #2d8a54; font-size: 19px; }
.metric-item strong { display: block; margin: 16px 0 5px; font-size: 23px; font-weight: 650; }
.metric-item small { color: #809087; font-size: 12px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .85fr); gap: 16px; }
.panel { padding: 18px; }
.panel-header { justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.device-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.device-item { display: flex; justify-content: space-between; gap: 12px; padding: 13px; border: 1px solid #e2dbcf; border-radius: 3px; background: #faf7f0; }
.empty-copy { margin: 8px 0 0; color: #817969; font-size: 13px; }
.device-name { font-size: 14px; font-weight: 600; }
.device-item p { margin: 5px 0 8px; color: #68786f; font-size: 12px; }
.device-item .el-tag + .el-tag { margin-left: 5px; }
.device-control { flex-direction: column; align-items: flex-end; justify-content: center; gap: 8px; white-space: nowrap; }
.strategy-copy { min-height: 70px; color: #435349; line-height: 1.7; font-size: 14px; }
.risk-row { margin: 15px 0; }
.risk-row span { display: flex; justify-content: space-between; margin-bottom: 7px; color: #617168; font-size: 13px; }
.risk-row b { color: #3d433a; font-variant-numeric: tabular-nums; }
.disclaimer { margin: 0; color: #718178; font-size: 12px; line-height: 1.65; }
@media (max-width: 1000px) { .metrics-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .content-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .environment-page { padding: 14px; } .page-toolbar { align-items: flex-start; flex-direction: column; } .toolbar-actions { justify-content: flex-start; } .metrics-grid, .device-grid { grid-template-columns: 1fr; } .device-item { align-items: center; } }
</style>
