<template>
	<main class="workbench">
		<header class="page-head">
			<div class="page-title">
				<span class="eyebrow">FIELD NOTES <i></i> 01 / WORKSPACE</span>
				<h1>田间档案台</h1>
				<p>M3 场景 · 番茄 <span class="scene-separator">/</span> 从病叶观察到方案复盘的工作记录</p>
			</div>
			<el-button class="refresh-button" :icon="Refresh" :loading="refreshing" @click="refresh">刷新状态</el-button>
		</header>

		<section class="run-band" aria-label="当前规则仿真运行">
			<div class="run-copy">
				<div class="run-kicker"><span class="section-index">A</span><span>当前推演记录</span><span class="source-pill">规则仿真 · 非实测</span></div>
				<div class="run-heading"><h2>{{ runTitle }}</h2><el-tag :type="statusTone" effect="plain">{{ statusLabel }}</el-tag></div>
				<p v-if="!agentStore.serviceAvailable" class="service-error">推演服务未连接，运行状态暂时无法读取。</p>
				<p v-else class="run-meta">{{ runMeta }}</p>
			</div>
			<div class="run-actions">
				<el-button type="primary" :icon="Operation" @click="go('/agentCenter')">{{ agentStore.hasActiveRun ? '进入推演' : '创建推演' }}</el-button>
				<el-button :icon="View" @click="go('/digitalTwin?mode=agent')">查看三维场景</el-button>
			</div>
		</section>

		<section class="workflow-section" aria-label="农业工作流程">
			<div class="workflow-heading"><span class="section-index">B</span><div><span class="eyebrow">FROM OBSERVATION TO REVIEW</span><h2>工作流程</h2></div><span class="workflow-caption">四步连贯记录 · 两种 AI 通道各自标明依据</span></div>
			<nav class="workflow" aria-label="观察、研判、方案和复盘">
				<button type="button" class="workflow-step" @click="go('/imgPredict')">
					<span class="step-top"><span class="step-number">01</span><el-icon><Picture /></el-icon></span>
					<strong>观察</strong><small>图片、视频与摄像识别</small><span class="step-link">进入识别 <el-icon><ArrowRight /></el-icon></span>
				</button>
				<button type="button" class="workflow-step" @click="go('/agentChat')">
					<span class="step-top"><span class="step-number">02</span><el-icon><ChatLineRound /></el-icon></span>
					<strong>研判</strong><small>知识检索与引用核对</small><span class="step-link">决策助手 <el-icon><ArrowRight /></el-icon></span>
				</button>
				<button type="button" class="workflow-step" @click="go('/agentSimulation')">
					<span class="step-top"><span class="step-number">03</span><el-icon><EditPen /></el-icon></span>
					<strong>方案</strong><small>农情解析与独立规划</small><span class="step-link">农事规划 <el-icon><ArrowRight /></el-icon></span>
				</button>
				<button type="button" class="workflow-step" @click="go('/agentCenter')">
					<span class="step-top"><span class="step-number">04</span><el-icon><DataAnalysis /></el-icon></span>
					<strong>复盘</strong><small>规则推演、孪生回放与报告</small><span class="step-link">温室推演 <el-icon><ArrowRight /></el-icon></span>
				</button>
			</nav>
		</section>

		<div class="content-grid">
			<section class="data-section greenhouse-section">
				<div class="section-head"><div><span class="section-index">C</span><h2>温室状态</h2></div><span class="data-origin">{{ agentStore.hasActiveRun ? '规则仿真快照' : '等待推演数据' }}</span></div>
				<div v-if="agentStore.hasActiveRun && stateMetrics.length" class="metric-grid">
					<div v-for="item in stateMetrics" :key="item.label" class="metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.source }}</small></div>
				</div>
				<p v-else class="empty">{{ agentStore.loading ? '正在读取运行状态…' : '暂无运行数据。创建规则推演后显示环境状态。' }}</p>
			</section>
			<section class="data-section attention-section">
				<div class="section-head"><div><span class="section-index">D</span><h2>待关注事项</h2></div><span class="data-origin">{{ alerts.length }} 项</span></div>
				<div v-if="alerts.length" class="alert-list">
					<div v-for="alert in alerts.slice(0, 4)" :key="String(alert.id || alert.message || alert.title)" class="alert-row"><el-tag size="small" :type="alertTone(alert.severity || alert.level)">{{ alertLabel(alert.severity || alert.level) }}</el-tag><span>{{ alert.message || alert.description || alert.title }}</span></div>
				</div>
				<p v-else class="empty">{{ agentStore.hasActiveRun ? '当前运行没有待关注告警。' : '暂无运行告警。' }}</p>
			</section>
		</div>

		<section class="data-section latest-section">
			<div class="section-head"><div><span class="section-index">E</span><h2>最近识别信号</h2></div><el-button link type="primary" @click="go('/imgRecord')">查看识别记录</el-button></div>
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
import { ArrowRight, ChatLineRound, DataAnalysis, EditPen, Operation, Picture, Refresh, View } from '@element-plus/icons-vue';
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
	metric('室内温度', ['temperatureC', 'temperature_c', 'temperature'], '°C', 1, '规则仿真'),
	metric('空气湿度', ['airHumidityPct', 'air_humidity_pct', 'airHumidity'], '%', 1, '规则仿真'),
	metric('VPD', ['vpdKpa', 'vpd_kpa'], 'kPa', 2, '模型计算'),
	metric('环境风险', ['environmentRisk', 'environment_risk'], '%', 0, '规则计算'),
] : []);

async function refresh() {
	refreshing.value = true;
	try {
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
	} finally {
		refreshing.value = false;
	}
}

onMounted(() => { void refresh(); });
</script>

<style scoped lang="scss">
.workbench { --journal-ink: var(--agri-ink, #352d27); --journal-muted: var(--agri-muted, #76695c); --journal-line: var(--agri-line, #ded2be); min-height: 100%; padding: 30px clamp(20px, 3.1vw, 48px) 38px; background: var(--agri-paper, #f3eddf); color: var(--journal-ink); }
.page-head, .run-heading, .run-actions, .section-head, .vision-row, .alert-row { display: flex; align-items: center; }
.page-head, .run-heading, .section-head { justify-content: space-between; gap: 16px; }
.page-head { align-items: flex-start; margin: 0 auto 22px; max-width: 1440px; }
h1, h2, p { margin: 0; }
.eyebrow { color: var(--journal-muted); font-size: 10px; font-weight: 650; letter-spacing: .15em; }
.eyebrow i { display: inline-block; width: 18px; height: 1px; margin: 0 7px 3px; background: #a68c6b; }
h1 { margin-top: 8px; font-family: "Noto Serif SC", "Songti SC", Georgia, serif; font-size: clamp(28px, 2.2vw, 36px); font-weight: 600; letter-spacing: .025em; }
.page-title p { margin-top: 7px; color: var(--journal-muted); font-size: 13px; }
.scene-separator { padding: 0 8px; color: #b9a98f; }
.refresh-button { margin-top: 30px; color: var(--journal-ink); border-color: var(--journal-line); background: var(--agri-surface); }
.run-band { display: flex; align-items: center; justify-content: space-between; gap: 26px; max-width: 1440px; min-height: 154px; margin: auto; padding: 25px 30px; border: 1px solid var(--journal-line); border-left: 4px solid var(--agri-olive, #718560); background: var(--agri-surface, #fcf8ef); }
.run-copy { min-width: 0; }
.run-kicker { display: flex; align-items: center; gap: 10px; color: var(--journal-muted); font-size: 11px; }
.section-index { display: inline-grid; place-items: center; width: 24px; height: 24px; flex: 0 0 auto; border: 1px solid #c9b99e; color: #765d3e; font-family: Georgia, serif; font-size: 11px; }
.source-pill { padding: 4px 8px; border: 1px solid #d8cbb7; color: #6d6256; font-size: 10px; letter-spacing: .02em; }
.run-heading { justify-content: flex-start; gap: 13px; margin-top: 11px; }
.run-band h2 { font-family: "Noto Serif SC", "Songti SC", Georgia, serif; font-size: 22px; font-weight: 600; }
.run-meta, .service-error { margin-top: 8px; color: var(--journal-muted); font-size: 12px; line-height: 1.5; }
.service-error { color: #934330; }
.run-actions { gap: 10px; flex: 0 0 auto; }
.run-actions :deep(.el-button) { min-height: 38px; }
.workflow-section { max-width: 1440px; margin: 27px auto 24px; }
.workflow-heading { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.workflow-heading > div { display: flex; flex-direction: column; gap: 2px; }
.workflow-heading h2, .section-head h2 { font-family: "Noto Serif SC", "Songti SC", Georgia, serif; font-size: 17px; font-weight: 600; }
.workflow-caption { margin-left: auto; color: var(--journal-muted); font-size: 11px; }
.workflow { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--journal-line); background: var(--journal-line); gap: 1px; }
.workflow-step { position: relative; display: flex; flex-direction: column; align-items: flex-start; min-width: 0; min-height: 158px; padding: 17px 20px 15px; border: 0; background: var(--agri-surface, #fcf8ef); color: var(--journal-ink); text-align: left; cursor: pointer; transition: background-color 140ms ease; }
.workflow-step:hover, .workflow-step:focus-visible { background: #f7f0e4; }
.workflow-step:focus-visible { outline: 2px solid var(--agri-terracotta); outline-offset: -3px; }
.step-top { display: flex; align-items: center; justify-content: space-between; width: 100%; color: var(--agri-terracotta); }
.step-number { color: #9d8e79; font-family: Georgia, serif; font-size: 11px; letter-spacing: .09em; }
.step-top .el-icon { font-size: 18px; }
.workflow-step strong { margin-top: 13px; font-family: "Noto Serif SC", "Songti SC", Georgia, serif; font-size: 18px; font-weight: 600; }
.workflow-step small { margin-top: 4px; color: var(--journal-muted); font-size: 11px; line-height: 1.45; }
.step-link { display: flex; align-items: center; gap: 5px; margin-top: auto; padding-top: 12px; color: var(--agri-terracotta); font-size: 11px; font-weight: 600; }
.content-grid { display: grid; grid-template-columns: 1.35fr 1fr; gap: 16px; max-width: 1440px; margin: auto; }
.data-section { min-width: 0; padding: 18px 21px; border: 1px solid var(--journal-line); background: var(--agri-surface, #fcf8ef); }
.section-head { min-height: 28px; margin-bottom: 13px; }
.section-head > div { display: flex; align-items: center; gap: 10px; }
.data-origin { color: var(--journal-muted); font-size: 11px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metric { min-width: 0; padding: 11px 13px 7px; border-left: 1px solid var(--journal-line); }
.metric:first-child { padding-left: 0; border-left: 0; }
.metric span, .metric strong, .metric small { display: block; }
.metric span { color: var(--journal-muted); font-size: 11px; }
.metric strong { margin: 8px 0 5px; font-family: Georgia, "Times New Roman", serif; font-size: clamp(17px, 1.6vw, 23px); font-weight: 600; white-space: nowrap; }
.metric small { color: #6c7b5e; font-size: 10px; }
.alert-list { display: grid; gap: 0; }
.alert-row { gap: 10px; min-height: 41px; padding: 7px 0; border-top: 1px solid #e9dfcf; font-size: 12px; line-height: 1.45; }
.alert-row:first-child { border-top: 0; }
.empty { min-height: 56px; display: flex; align-items: center; color: #837666; font-size: 12px; }
.latest-section { max-width: 1440px; margin: 16px auto 0; }
.vision-row { gap: 14px; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid #e9dfcf; }
.vision-main { flex: 1; min-width: 180px; }
.vision-main strong, .vision-main span { display: block; }
.vision-main strong { font-size: 13px; }
.vision-main span { margin-top: 4px; color: var(--journal-muted); font-size: 11px; }
@media (max-width: 1120px) { .workbench { padding-inline: 22px; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); row-gap: 8px; }.metric:nth-child(3) { padding-left: 0; border-left: 0; } }
@media (max-width: 900px) { .content-grid { grid-template-columns: 1fr; }.workflow { grid-template-columns: repeat(2, minmax(0, 1fr)); }.workflow-caption { display: none; } }
@media (max-width: 680px) { .workbench { padding: 18px 14px 26px; }.page-head { margin-bottom: 16px; }.page-head p { max-width: 270px; line-height: 1.5; }.refresh-button { margin-top: 26px; }.run-band { align-items: flex-start; flex-direction: column; padding: 18px; }.run-actions { flex-wrap: wrap; }.workflow { grid-template-columns: 1fr 1fr; }.workflow-step { min-height: 140px; padding: 14px; }.data-section { padding: 15px; }.metric { padding: 10px 8px; }.metric strong { font-size: 19px; } }
</style>
