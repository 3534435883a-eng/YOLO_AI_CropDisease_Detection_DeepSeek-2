<template>
	<div class="environment-page">
		<header class="page-toolbar">
			<div>
				<p class="eyebrow">统一运行状态</p>
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

		<el-alert
			v-if="selectedGreenhouse !== '8号温室'"
			title="当前智能体一期仅覆盖 8号温室番茄场景，以下其他温室数据为历史展示。"
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
			</div>

			<aside class="panel strategy-panel">
				<p class="eyebrow">规则推演</p>
				<h3>当前策略</h3>
				<p class="strategy-copy">{{ strategySummary }}</p>
				<div class="risk-row">
					<span>环境风险</span>
					<el-progress :percentage="environmentRisk" :stroke-width="8" :color="riskColor" />
				</div>
				<div class="risk-row">
					<span>病害环境压力</span>
					<el-progress :percentage="diseasePressure" :stroke-width="8" :color="riskColor" />
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

const fallbackState = {
	temperatureC: 24,
	airHumidityPct: 75,
	soilMoisturePct: 40,
	co2Ppm: 720,
	soilPh: 6.8,
	lightPpfd: 310,
	vpdKpa: 0.72,
	environmentRisk: 24,
	diseasePressure: 31,
};

const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' ? value as Record<string, unknown> : {};
const state = computed(() => ({ ...fallbackState, ...asRecord(agentStore.summary?.currentState || agentStore.summary?.state) }));
const numberValue = (keys: string[], fallback: number): number => {
	for (const key of keys) {
		const value = state.value[key];
		const parsed = typeof value === 'number' ? value : Number(value);
		if (Number.isFinite(parsed)) return parsed;
	}
	return fallback;
};
const format = (keys: string[], fallback: number, unit: string, digits = 1) => `${numberValue(keys, fallback).toFixed(digits)}${unit}`;

const metrics = computed(() => [
	{ code: 'temperature', icon: 'icon-daqiwendu', label: '室内温度', value: format(['temperatureC', 'temperature_c', 'temperature'], 24, ' C'), hint: '模拟推演' },
	{ code: 'humidity', icon: 'icon-kongqishidu_kongqishidu', label: '空气湿度', value: format(['airHumidityPct', 'air_humidity_pct', 'airHumidity'], 75, '%'), hint: '模拟推演' },
	{ code: 'soil', icon: 'icon-turangshidu', label: '土壤水分', value: format(['soilMoisturePct', 'soil_moisture_pct', 'soilHumidity'], 40, '%'), hint: '模拟推演' },
	{ code: 'co2', icon: 'icon-eryanghuatan', label: 'CO2', value: format(['co2Ppm', 'co2_ppm', 'co2Concentration'], 720, ' ppm', 0), hint: '模拟推演' },
	{ code: 'ph', icon: 'icon-turangPH', label: '土壤 pH', value: format(['soilPh', 'soil_ph'], 6.8, '', 1), hint: '历史基线/推演' },
	{ code: 'light', icon: 'icon-guangzhaoqiangdu', label: '光照', value: format(['lightPpfd', 'light_ppfd', 'lightIntensity'], 310, ' PPFD', 0), hint: '模拟推演' },
	{ code: 'vpd', icon: 'icon-huanjingjiance', label: 'VPD', value: format(['vpdKpa', 'vpd_kpa'], 0.72, ' kPa', 2), hint: '规则计算' },
]);

const defaultDevices: AgentDevice[] = [
	{ code: 'IRRIGATION', name: '灌溉水泵', controlMode: 'AUTO', enabled: false, status: 'NORMAL' },
	{ code: 'VENTILATION', name: '通风风机', controlMode: 'AUTO', enabled: false, status: 'NORMAL' },
	{ code: 'SUPPLEMENTAL_LIGHT', name: '补光灯', controlMode: 'AUTO', enabled: false, status: 'NORMAL' },
	{ code: 'SHADE', name: '遮阳帘', controlMode: 'AUTO', enabled: false, status: 'NORMAL' },
	{ code: 'CO2_SUPPLY', name: 'CO2 补给', controlMode: 'AUTO', enabled: false, status: 'NORMAL' },
];
const devices = computed(() => {
	const source = agentStore.summary?.devices || agentStore.summary?.deviceStates || defaultDevices;
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

const environmentRisk = computed(() => Math.round(numberValue(['environmentRisk', 'environment_risk'], 24)));
const diseasePressure = computed(() => Math.round(numberValue(['diseasePressure', 'disease_pressure'], 31)));
const riskColor = computed(() => Math.max(environmentRisk.value, diseasePressure.value) >= 70 ? '#b84242' : Math.max(environmentRisk.value, diseasePressure.value) >= 45 ? '#c68a25' : '#2d8a54');
const strategySummary = computed(() => String(agentStore.summary?.strategySummary || agentStore.summary?.strategy || '尚未创建运行。可在指挥中心启动番茄温室模拟。'));
const sourceLabel = computed(() => agentStore.hasActiveRun ? '数据来源：模拟推演、规则计算与已导入的风险信号' : '数据来源：8号温室历史基线；尚未启动模拟运行');

const modeLabel = (value: string) => value === 'MANUAL' ? '人工接管' : '自动策略';
const healthLabel = (value: string) => ({ NORMAL: '正常', OFFLINE: '离线', FAULT: '故障' }[value] || value);
const healthType = (value: string) => value === 'NORMAL' ? 'success' : value === 'OFFLINE' ? 'info' : 'danger';
const canTakeOver = (device: { healthStatus: string }) => device.healthStatus === 'NORMAL' && agentStore.hasActiveRun;

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
.environment-page { padding: 20px; color: #203128; }
.page-toolbar, .panel-header, .toolbar-actions, .device-control { display: flex; align-items: center; }
.page-toolbar { justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.toolbar-actions { flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.greenhouse-select { width: 132px; }
.eyebrow { margin: 0 0 4px; color: #6d7c72; font-size: 12px; }
h2, h3 { margin: 0; letter-spacing: 0; }
h2 { font-size: 22px; }
h3 { font-size: 17px; }
.subtitle { margin: 6px 0 0; color: #607067; font-size: 13px; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
.metric-item, .panel { border: 1px solid #dce8df; border-radius: 8px; background: #fff; }
.metric-item { min-height: 118px; padding: 14px; }
.metric-heading { display: flex; align-items: center; gap: 8px; color: #5a6d60; font-size: 13px; }
.metric-heading i { color: #2d8a54; font-size: 19px; }
.metric-item strong { display: block; margin: 16px 0 5px; font-size: 23px; font-weight: 650; }
.metric-item small { color: #809087; font-size: 12px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .85fr); gap: 16px; }
.panel { padding: 18px; }
.panel-header { justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.device-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.device-item { display: flex; justify-content: space-between; gap: 12px; padding: 13px; border: 1px solid #e2ebe4; border-radius: 7px; background: #f9fcfa; }
.device-name { font-size: 14px; font-weight: 600; }
.device-item p { margin: 5px 0 8px; color: #68786f; font-size: 12px; }
.device-item .el-tag + .el-tag { margin-left: 5px; }
.device-control { flex-direction: column; align-items: flex-end; justify-content: center; gap: 8px; white-space: nowrap; }
.strategy-copy { min-height: 70px; color: #435349; line-height: 1.7; font-size: 14px; }
.risk-row { margin: 15px 0; }
.risk-row span { display: block; margin-bottom: 7px; color: #617168; font-size: 13px; }
.disclaimer { margin: 0; color: #718178; font-size: 12px; line-height: 1.65; }
@media (max-width: 1000px) { .metrics-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .content-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .environment-page { padding: 14px; } .page-toolbar { align-items: flex-start; flex-direction: column; } .toolbar-actions { justify-content: flex-start; } .metrics-grid, .device-grid { grid-template-columns: 1fr; } .device-item { align-items: center; } }
</style>
