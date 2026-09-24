<template>
	<main class="workbench">
		<header class="page-head">
			<div><h1>农业决策工作台</h1><p>8号温室 · 番茄</p></div>
			<el-button :icon="Refresh" :loading="refreshing" @click="refresh">刷新</el-button>
		</header>

		<section class="run-band" aria-label="当前运行">
			<div class="run-heading"><div><span class="section-label">当前运行</span><h2>{{ runTitle }}</h2></div><el-tag :type="statusTone" effect="plain">{{ statusLabel }}</el-tag></div>
			<p v-if="!agentStore.serviceAvailable" class="service-error">推演服务未连接，运行状态暂时无法读取。</p>
			<p v-else class="run-meta">{{ runMeta }}</p>
			<div class="run-actions">
				<el-button type="primary" :icon="Operation" @click="go('/agentCenter')">{{ agentStore.hasActiveRun ? '进入推演' : '创建推演' }}</el-button>
				<el-button :icon="View" @click="go('/digitalTwin?mode=agent')">查看三维场景</el-button>
			</div>
		</section>

		<nav class="workflow" aria-label="主要工作流程">
			<button type="button" @click="go('/imgPredict')"><el-icon><Picture /></el-icon><span><strong>病害识别</strong><small>上传图片并查看检测结果</small></span><el-icon><ArrowRight /></el-icon></button>
			<button type="button" @click="go('/agentChat')"><el-icon><ChatLineRound /></el-icon><span><strong>决策助手</strong><small>核对知识依据和处置建议</small></span><el-icon><ArrowRight /></el-icon></button>
			<button type="button" @click="go('/agentCenter')"><el-icon><DataAnalysis /></el-icon><span><strong>温室推演</strong><small>比较策略与查看虚拟设备</small></span><el-icon><ArrowRight /></el-icon></button>
		</nav>

		<div class="content-grid">
			<section class="data-section">
				<div class="section-head"><h2>温室状态</h2><span>模拟推演 / 规则计算</span></div>
				<div v-if="agentStore.hasActiveRun && stateMetrics.length" class="metric-grid">
					<div v-for="item in stateMetrics" :key="item.label" class="metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.source }}</small></div>
				</div>
				<p v-else class="empty">{{ agentStore.loading ? '正在读取运行状态…' : '暂无运行数据。创建推演后显示环境状态。' }}</p>
			</section>
			<section class="data-section">
				<div class="section-head"><h2>待关注事项</h2><span>{{ alerts.length }} 项</span></div>
				<div v-if="alerts.length" class="alert-list">
					<div v-for="alert in alerts.slice(0, 4)" :key="String(alert.id || alert.message || alert.title)" class="alert-row"><el-tag size="small" :type="alertTone(alert.severity || alert.level)">{{ alertLabel(alert.severity || alert.level) }}</el-tag><span>{{ alert.message || alert.description || alert.title }}</span></div>
				</div>
				<p v-else class="empty">{{ agentStore.hasActiveRun ? '当前运行没有待关注告警。' : '暂无运行告警。' }}</p>
			</section>
		</div>

		<section class="data-section latest-section">
			<div class="section-head"><h2>最近识别信号</h2><el-button link type="primary" @click="go('/imgRecord')">查看识别记录</el-button></div>
			<div v-if="latestVision" class="vision-row">
				<div class="vision-main"><strong>{{ latestVision.detectedLabel || '类别未提供' }}</strong><span>{{ latestVision.cropType || '作物未提供' }} · {{ latestVision.observedAt || '时间未提供' }}</span></div>
				<el-tag :type="latestVision.explainable ? 'success' : 'warning'" effect="plain">{{ latestVision.explainable ? '知识库可核对' : '暂无对应条目' }}</el-tag>
				<el-button :icon="ChatLineRound" @click="goVisionChat">到决策助手</el-button>
			</div>
			<p v-else class="empty">{{ visionLoading ? '正在读取识别信号…' : '当前运行尚无导入的识别信号。' }}</p>
		</section>
	</main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowRight, ChatLineRound, DataAnalysis, Operation, Picture, Refresh, View } from '@element-plus/icons-vue';
import { AgentAlert, AgentVisionEvent, getAgentVisionEvents } from '/@/api/agent';
import { useAgentRunStore } from '/@/stores/agentRun';

const router = useRouter();
const agentStore = useAgentRunStore();
const refreshing = ref(false);
const visionLoading = ref(false);
const latestVision = ref<AgentVisionEvent | null>(null);
const go = (path: string) => router.push(path);
const currentState = computed<Record<string, unknown>>(() => agentStore.summary?.currentState || agentStore.summary?.state || {});
const status = computed(() => String(agentStore.activeRun?.status || 'READY').toUpperCase());
const statusLabel = computed(() => agentStore.serviceAvailable
	? ({ RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '已完成', DRAFT: '待启动', READY: '未创建' }[status.value] || status.value)
	: '服务未连接');
const statusTone = computed<'success' | 'warning' | 'info'>(() => status.value === 'RUNNING' ? 'success' : status.value === 'PAUSED' ? 'warning' : 'info');
const runTitle = computed(() => !agentStore.serviceAvailable ? '推演服务未连接' : agentStore.hasActiveRun ? '番茄温室规则推演' : '尚未创建推演');
const runMeta = computed(() => agentStore.hasActiveRun
	? `运行 ${agentStore.activeRun?.runCode || agentStore.runId} · 第 ${agentStore.activeRun?.currentStep ?? 0} 步 · 仿真数据，非实测`
	: '创建运行后可查看规则结论、虚拟设备和同期策略对比。');
const goVisionChat = () => {
	const recordId = latestVision.value?.sourceRecordId;
	void router.push(recordId === undefined || recordId === null
		? '/agentChat'
		: { path: '/agentChat', query: { recordId: String(recordId) } });
};
const alerts = computed<AgentAlert[]>(() => Array.isArray(agentStore.summary?.alerts) ? agentStore.summary.alerts : []);
const alertTone = (severity: unknown): 'danger' | 'warning' | 'info' => {
	const value = String(severity || '').toUpperCase();
	return value === 'HIGH' || value === 'CRITICAL' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'info';
};
const alertLabel = (severity: unknown) => ({ HIGH: '高风险', CRITICAL: '紧急', MEDIUM: '需关注', LOW: '提示' }[String(severity || '').toUpperCase()] || '提示');
const metric = (label: string, keys: string[], unit: string, digits: number, source: string) => {
	const value = keys.map((key) => currentState.value[key]).find((item) => item !== null && item !== undefined && item !== '');
	const number = Number(value);
	return { label, value: value === undefined || !Number.isFinite(number) ? '--' : `${number.toFixed(digits)} ${unit}`, source };
};
const stateMetrics = computed(() => agentStore.summary ? [
	metric('室内温度', ['temperatureC', 'temperature_c', 'temperature'], '°C', 1, '模拟'),
	metric('空气湿度', ['airHumidityPct', 'air_humidity_pct', 'airHumidity'], '%', 1, '模拟'),
	metric('VPD', ['vpdKpa', 'vpd_kpa'], 'kPa', 2, '计算'),
	metric('环境风险', ['environmentRisk', 'environment_risk'], '%', 0, '规则计算'),
] : []);

async function refresh() {
	refreshing.value = true;
	await agentStore.loadActiveRun();
	latestVision.value = null;
	if (agentStore.runId !== null) {
		visionLoading.value = true;
		try {
			const events = await getAgentVisionEvents(agentStore.runId, 1);
			latestVision.value = events[0] || null;
		} catch {
			latestVision.value = null;
		} finally {
			visionLoading.value = false;
		}
	}
	refreshing.value = false;
}

onMounted(() => { void refresh(); });
</script>

<style scoped>
.workbench { min-height: 100%; padding: 24px; background: #f5f7f5; color: #243129; }
.page-head, .run-heading, .run-actions, .section-head, .vision-row, .alert-row { display: flex; align-items: center; }
.page-head, .run-heading, .section-head { justify-content: space-between; gap: 16px; }
.page-head { margin-bottom: 20px; }
h1, h2, p { margin: 0; }
h1 { font-size: 24px; font-weight: 650; }
.page-head p { margin-top: 4px; color: #69756c; font-size: 13px; }
h2 { font-size: 16px; font-weight: 650; }
.run-band { padding: 22px 24px; border-left: 4px solid #3c805d; background: #fff; }
.section-label { display: block; margin-bottom: 5px; color: #6b786f; font-size: 12px; }
.run-band h2 { font-size: 20px; }
.run-meta, .service-error { margin-top: 10px; color: #627068; font-size: 13px; }
.service-error { color: #a83f39; }
.run-actions { gap: 10px; margin-top: 18px; }
.workflow { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin: 20px 0; background: #dce4de; border: 1px solid #dce4de; }
.workflow button { display: flex; align-items: center; gap: 13px; min-width: 0; min-height: 88px; padding: 18px; border: 0; background: #fff; color: #274334; text-align: left; cursor: pointer; }
.workflow button:hover, .workflow button:focus-visible { background: #edf5ee; }
.workflow button > .el-icon:first-child { flex: 0 0 auto; font-size: 23px; }
.workflow button > .el-icon:last-child { flex: 0 0 auto; margin-left: auto; color: #77877a; }
.workflow strong, .workflow small { display: block; }
.workflow strong { margin-bottom: 4px; font-size: 15px; }
.workflow small { color: #718077; font-size: 12px; line-height: 1.4; }
.content-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 20px; }
.data-section { min-width: 0; padding: 18px 20px; background: #fff; border: 1px solid #e0e7e1; }
.section-head { min-height: 28px; margin-bottom: 15px; }
.section-head span { color: #748178; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.metric { padding: 13px 0; border-top: 1px solid #e8eee9; }
.metric span, .metric strong, .metric small { display: block; }
.metric span { color: #6c7a70; font-size: 12px; }
.metric strong { margin: 6px 0 3px; font-size: 21px; font-weight: 650; }
.metric small { color: #718779; font-size: 11px; }
.alert-list { display: grid; gap: 8px; }
.alert-row { gap: 10px; padding: 9px 0; border-top: 1px solid #e8eee9; font-size: 13px; line-height: 1.4; }
.empty { padding: 14px 0; color: #7d8980; font-size: 13px; }
.latest-section { margin-top: 20px; }
.vision-row { gap: 15px; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid #e8eee9; }
.vision-main { flex: 1; min-width: 180px; }
.vision-main strong, .vision-main span { display: block; }
.vision-main strong { font-size: 14px; }
.vision-main span { margin-top: 4px; color: #7b887e; font-size: 12px; }
@media (max-width: 900px) { .content-grid { grid-template-columns: 1fr; } }
@media (max-width: 680px) { .workbench { padding: 14px; }.workflow { grid-template-columns: 1fr; }.run-band { padding: 18px; }.run-actions { flex-wrap: wrap; } }
</style>
