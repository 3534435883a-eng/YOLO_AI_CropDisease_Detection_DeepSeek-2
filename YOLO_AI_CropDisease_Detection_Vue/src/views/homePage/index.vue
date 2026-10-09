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

		<section class="run-band" aria-label="当前M3大棚运行">
			<div class="run-copy">
				<div class="run-kicker"><span class="section-index">A</span><span>当前推演记录</span><span class="source-pill">M3回放 · 虚拟干预</span></div>
				<div class="run-heading"><h2>{{ runTitle }}</h2><el-tag :type="statusTone" effect="plain">{{ statusLabel }}</el-tag></div>
				<p v-if="!!greenhouse.error" class="service-error">推演服务未连接，运行状态暂时无法读取。</p>
				<p v-else class="run-meta">{{ runMeta }}</p>
			</div>
			<div class="run-actions">
				<el-button type="primary" :icon="Operation" @click="go('/digitalTwin')">{{ !!greenhouse.run ? '进入推演' : '创建推演' }}</el-button>
				<el-button :icon="View" @click="go('/digitalTwin')">查看三维场景</el-button>
			</div>
		</section>

		<section class="workflow-section" aria-label="农业工作流程">
			<div class="workflow-heading"><span class="section-index">B</span><div><span class="eyebrow">FROM OBSERVATION TO REVIEW</span><h2>工作流程</h2></div><span class="workflow-caption">四步连贯记录 · 同一任务 · 识别、决策与效果相连</span></div>
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
					<strong>方案</strong><small>农情解析与同任务规划</small><span class="step-link">农事规划 <el-icon><ArrowRight /></el-icon></span>
				</button>
				<button type="button" class="workflow-step" @click="go('/digitalTwin')">
					<span class="step-top"><span class="step-number">04</span><el-icon><DataAnalysis /></el-icon></span>
					<strong>复盘</strong><small>同任务处置反馈与管理报告</small><span class="step-link">温室推演 <el-icon><ArrowRight /></el-icon></span>
				</button>
			</nav>
		</section>

		<div class="content-grid">
			<section class="data-section greenhouse-section">
				<div class="section-head"><div><span class="section-index">C</span><h2>温室状态</h2></div><span class="data-origin">{{ !!greenhouse.run ? '当前M3场景快照' : '等待推演数据' }}</span></div>
				<div v-if="!!greenhouse.run && stateMetrics.length" class="metric-grid">
					<div v-for="item in stateMetrics" :key="item.label" class="metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.source }}</small></div>
				</div>
				<p v-else class="empty">{{ greenhouse.busy ? '正在读取运行状态…' : '暂无运行数据。进入大棚接入后显示当前环境状态。' }}</p>
			</section>
			<section class="data-section attention-section">
				<div class="section-head"><div><span class="section-index">D</span><h2>待关注事项</h2></div><span class="data-origin">{{ alerts.length }} 项</span></div>
				<div v-if="alerts.length" class="alert-list">
					<div v-for="alert in alerts.slice(0, 4)" :key="alert.code" class="alert-row"><el-tag size="small" :type="alertTone(alert.level)">{{ alertLabel(alert.level) }}</el-tag><span>{{ alert.title }}</span></div>
				</div>
				<p v-else class="empty">{{ !!greenhouse.run ? '当前运行没有待关注告警。' : '暂无运行告警。' }}</p>
			</section>
		</div>

		<section class="data-section latest-section">
			<div class="section-head"><div><span class="section-index">E</span><h2>最近识别信号</h2></div><el-button link type="primary" @click="go('/imgRecord')">查看识别记录</el-button></div>
			<div v-if="latestVision" class="vision-row">
				<div class="vision-main"><strong>{{ latestVision.detectedLabel || '类别未提供' }}</strong><span>{{ latestVision.cropType || '作物未提供' }} · {{ latestVision.observedAt || '时间未提供' }}</span></div>
				<el-tag :type="latestVision.explainable ? 'success' : 'warning'" effect="plain">候选证据 · 待核验</el-tag>
				<el-button :icon="ChatLineRound" @click="goVisionChat">到决策助手</el-button>
			</div>
			<p v-else class="empty">{{ visionLoading ? '正在读取识别信号…' : '当前运行尚无导入的识别信号。' }}</p>
		</section>
	</main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowRight, ChatLineRound, DataAnalysis, EditPen, Operation, Picture, Refresh, View } from '@element-plus/icons-vue';
import { useGreenhouseStore } from '/@/stores/greenhouse';
const router=useRouter(),greenhouse=useGreenhouseStore(),refreshing=ref(false),visionLoading=ref(false);
const unsubscribe=greenhouse.subscribe();onBeforeUnmount(unsubscribe);
const go=(path:string)=>router.push({path,query:greenhouse.linkedQuery()});
const status=computed(()=>greenhouse.run?.finished?'COMPLETED':greenhouse.run?.playback?.waitingForAi?'WAITING':greenhouse.run?.playback?.playing?'RUNNING':greenhouse.run?'PAUSED':'READY');
const statusLabel=computed(()=>greenhouse.error?'读取失败':({RUNNING:'回放中',WAITING:'AI处置中',PAUSED:'已暂停',COMPLETED:'已完成',READY:'未接入'}[status.value]||status.value));
const statusTone=computed<'success'|'warning'|'info'>(()=>status.value==='RUNNING'?'success':status.value==='WAITING'?'warning':'info');
const runTitle=computed(()=>greenhouse.task?.title||(greenhouse.run?'番茄温室当前推演':'尚未接入大棚'));
const runMeta=computed(()=>greenhouse.run?`运行 ${greenhouse.run.runId.slice(0,8)} · ${greenhouse.run.current.at.replace('T',' ')} · ${greenhouse.run.cursor}/${greenhouse.run.totalSlots} 个时段`:'进入大棚后，识别、问答、规划和报告可沿用同一农情任务。');
const current=computed(()=>greenhouse.run?.current);
const alerts=computed(()=>current.value?.scenario?.risk.alerts||current.value?.risk.alerts||[]);
const alertTone=(severity:unknown):'danger'|'warning'|'info'=>String(severity)==='HIGH'?'danger':String(severity)==='MEDIUM'?'warning':'info';
const alertLabel=(severity:unknown)=>String(severity)==='HIGH'?'高风险':String(severity)==='MEDIUM'?'需关注':'提示';
const fmt=(value:unknown,unit:string,digits=1)=>typeof value==='number'&&Number.isFinite(value)?value.toFixed(digits)+' '+unit:'—';
const stateMetrics=computed(()=>{const env=current.value?.scenario?.environment||current.value?.environment;return current.value?[
 {label:'室内温度',value:fmt(env?.temperatureC,'°C'),source:current.value.scenario?'虚拟干预推演':'历史观测回放'},
 {label:'空气湿度',value:fmt(env?.airHumidityPct,'%'),source:current.value.scenario?'虚拟干预推演':'历史观测回放'},
 {label:'VPD',value:fmt((current.value.scenario?.risk||current.value.risk).vpdKpa,'kPa',2),source:'温湿度计算'},
 {label:'根区含水率',value:fmt(current.value.scenario?.agronomy?.soilMoistureVwcPct,'%'),source:'根区模型估计'}]:[];});
const latestVision=computed(()=>{const evidence=[...(greenhouse.task?.evidence||[])].reverse().find(e=>e.type==='IMAGE');return evidence?{detectedLabel:evidence.label,cropType:greenhouse.task?.crop,observedAt:evidence.createdAt,explainable:false}:null;});
const goVisionChat=()=>go('/agentChat');
async function refresh(){refreshing.value=true;try{await greenhouse.refreshRun(true);await greenhouse.refreshTask();}finally{refreshing.value=false;}}
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
