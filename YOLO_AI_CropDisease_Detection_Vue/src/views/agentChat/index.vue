<template>
	<div class="agent-chat">
		<header class="chat-header">
			<div>
				<p class="eyebrow">TOMATO GREENHOUSE / LLM AGENT</p>
				<h2>AI 决策对话</h2>
				<p class="header-copy">
					大模型按步选择工具、检索病害知识库，回答带出处引用；依据不足时明确拒答，不会编造结论。
				</p>
			</div>
			<div class="header-side">
				<div class="badge-stack">
					<span class="badge" :class="`badge-${connectionTone}`">{{ connectionLabel }}</span>
					<span class="badge badge-quiet">{{ modelLabel }}</span>
				</div>
				<el-button link type="primary" @click="goCoverage">视觉—知识覆盖 →</el-button>
				<el-button link type="primary" @click="goCenter">推演指挥中心 →</el-button>
			</div>
		</header>

		<div class="chat-layout">
			<main class="panel chat-main">
				<div ref="streamRef" class="stream">
					<div v-if="!turns.length" class="empty-state">
						<div class="empty-icon"><i class="iconfontjs icon-znwd"></i></div>
						<div>
							<p class="eyebrow">准备就绪</p>
							<h3>问一个田间问题，看智能体怎么一步步找依据</h3>
							<p class="empty-copy">
								每个工具调用、命中条数、耗时与降级情况都会实时出现在对话里；点右侧示例可快速开始。
							</p>
						</div>
					</div>

					<article v-for="turn in turns" :key="turn.id" class="turn">
						<div class="question">
							<span class="question-label">提问</span>
							<p>{{ turn.question }}</p>
							<span v-if="turn.crop" class="question-crop">{{ turn.crop }}</span>
						</div>

						<div v-if="turn.steps.length || turn.status === 'running'" class="steps">
							<div
								v-for="step in turn.steps"
								:key="step.stepNo"
								class="step"
								:class="{ 'is-failed': step.failed, 'is-degraded': step.degraded }"
							>
								<span class="step-index">{{ pad(step.stepNo) }}</span>
								<div class="step-body">
									<strong>{{ step.toolName }}</strong>
									<span>{{ step.summary }}</span>
								</div>
								<span class="step-time">{{ step.durationMs === undefined ? '' : `${step.durationMs} ms` }}</span>
								<el-tag :type="step.badgeType" size="small" effect="plain">{{ step.badge }}</el-tag>
							</div>
							<div v-if="turn.status === 'running'" class="step is-running">
								<span class="step-index">··</span>
								<div class="step-body"><strong>{{ turn.stage }}</strong><span>{{ runningHint }}</span></div>
								<span class="step-time">{{ elapsedLabel(turn) }}</span>
							</div>
						</div>

						<div v-if="turn.answer" class="answer" :class="`answer-${turn.status}`">
							<div class="answer-head">
								<span class="answer-kind">{{ answerKind(turn.status) }}</span>
								<span v-if="turn.status === 'done'" class="answer-meta">
									依据 {{ turn.citations.length }} 条 · {{ turn.stepCount }} 步 · {{ (turn.elapsedMs / 1000).toFixed(1) }}s
								</span>
								<span v-else class="answer-meta">{{ reasonLabel(turn.reason) }}</span>
							</div>
							<p class="answer-text">
								<template v-for="(segment, index) in turn.segments" :key="index">
									<span v-if="segment.type === 'text'">{{ segment.text }}</span>
									<button
										v-else
										class="cite"
										:class="{ 'is-active': activeCitation === segment.index }"
										@click="focusCitation(segment.index)"
									>
										[{{ segment.index }}]
									</button>
								</template>
							</p>
							<p v-if="turn.degraded" class="answer-flag">
								本次检索已降级为纯关键词模式（向量服务不可达），结果可能漏召语义相近的条目。
							</p>
							<p class="answer-note">
								依据来自病害知识库片段，需结合田间实际人工确认；药剂使用须遵循当地登记与用药规范。
							</p>
						</div>
					</article>
				</div>

				<footer class="composer">
					<el-select v-model="crop" class="crop-select" size="large">
						<el-option v-for="item in crops" :key="item" :label="item" :value="item" />
					</el-select>
					<el-input
						v-model="draft"
						class="composer-input"
						type="textarea"
						:rows="2"
						resize="none"
						maxlength="300"
						show-word-limit
						placeholder="描述症状或想问的问题，例如：番茄叶片出现褐色轮纹斑，湿度大时扩展迅速（Enter 发送，Shift+Enter 换行）"
						@keydown.enter.exact.prevent="send"
					/>
					<div class="composer-actions">
						<el-button v-if="isRunning" type="warning" plain @click="stop">停止</el-button>
						<el-button type="primary" :loading="isRunning" :disabled="!draft.trim()" @click="send">提问</el-button>
					</div>
				</footer>
			</main>

			<aside class="chat-aside">
				<section class="panel aside-panel">
					<div class="panel-heading">
						<div><p class="eyebrow">SESSION</p><h3>会话状态</h3></div>
						<el-tag :type="connectionTone" size="small" effect="plain">{{ connectionLabel }}</el-tag>
					</div>
					<div class="stat-list">
						<div class="stat-row"><span>会话编号</span><strong>{{ sessionShort }}</strong></div>
						<div class="stat-row"><span>已完成轮次</span><strong>{{ completedTurns }}</strong></div>
						<div class="stat-row"><span>工具调用</span><strong>{{ totalSteps }}</strong></div>
						<div class="stat-row"><span>引用证据</span><strong>{{ activeCitations.length }}</strong></div>
					</div>
				</section>

				<section class="panel aside-panel">
					<div class="panel-heading">
						<div><p class="eyebrow">EVIDENCE</p><h3>本轮证据</h3></div>
						<el-tag type="info" size="small" effect="plain">可核对来源</el-tag>
					</div>
					<div v-if="activeCitations.length" class="evidence-list">
						<article
							v-for="item in activeCitations"
							:key="`${item.sourceTable}-${item.sourceId}-${item.fieldType}-${item.chunkNo}`"
							class="evidence"
							:class="{ 'is-active': activeCitation === item.index }"
						>
							<div class="evidence-head">
								<span class="evidence-index">[{{ item.index }}]</span>
								<strong>{{ item.diseaseName }}</strong>
								<el-tag size="small" effect="plain">{{ fieldTypeLabel(item.fieldType) }}</el-tag>
							</div>
							<p class="evidence-snippet">{{ item.snippet }}</p>
							<p class="evidence-source">
								{{ item.cropType || '—' }} · 来源 {{ item.sourceTable }}#{{ item.sourceId }} · 片段 {{ item.chunkNo }}
							</p>
						</article>
					</div>
					<p v-else class="muted">提问后这里会列出本轮引用的知识库片段，编号与回答中的 [n] 一致。</p>
				</section>

				<section class="panel aside-panel">
					<div class="panel-heading">
						<div><p class="eyebrow">PROMPTS</p><h3>示例问题</h3></div>
					</div>
					<div class="preset-group" v-for="group in presetGroups" :key="group.label">
						<p class="preset-label">{{ group.label }}</p>
						<button v-for="item in group.items" :key="item" class="preset" :disabled="isRunning" @click="usePreset(item)">
							{{ item }}
						</button>
					</div>
				</section>
			</aside>
		</div>
	</div>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
	AgentCitation,
	AgentStepEvent,
	fieldTypeLabel,
	readFinalCitations,
	readStepCitations,
	streamAgentChat,
} from '/@/api/agent/chat';

interface AnswerSegment {
	type: 'text' | 'cite';
	text?: string;
	index?: number;
}

interface StepView {
	stepNo: number;
	toolName: string;
	summary: string;
	badge: string;
	badgeType: 'success' | 'warning' | 'danger' | 'info';
	durationMs?: number;
	failed: boolean;
	degraded: boolean;
}

type TurnStatus = 'running' | 'done' | 'refused' | 'error';

interface Turn {
	id: string;
	question: string;
	crop: string;
	steps: StepView[];
	status: TurnStatus;
	answer: string;
	reason: string;
	citations: AgentCitation[];
	segments: AnswerSegment[];
	stepCount: number;
	startedAt: number;
	elapsedMs: number;
	stage: string;
	degraded: boolean;
}

const router = useRouter();
const crops = ['番茄', '玉米', '水稻', '小麦', '马铃薯', '棉花', '苹果', '葡萄', '草莓'];
const presetGroups = [
	{
		label: '番茄 · 病害诊断',
		items: [
			'番茄叶片出现褐色轮纹斑，湿度大时扩展迅速，该如何处置？',
			'番茄叶背出现灰褐色绒状霉层，正面有淡黄色褪绿斑，是什么病？',
		],
	},
	{
		label: '跨作物验证',
		items: ['玉米叶片上出现梭形大斑，边缘褐色中间灰色，怎么防治？'],
	},
	{
		label: '边界演示（应拒答）',
		items: ['量子计算机的原理是什么？', '帮我写一首关于春天的诗'],
	},
];

const crop = ref('番茄');
const draft = ref('');
const turns = ref<Turn[]>([]);
const streamRef = ref<HTMLElement | null>(null);
const activeCitation = ref<number | undefined>(undefined);
const sessionId = ref(createSessionId());
const controller = ref<AbortController | null>(null);
const ticker = ref<number | undefined>(undefined);

const isRunning = computed(() => turns.value.some((turn) => turn.status === 'running'));
const connectionLabel = computed(() => (isRunning.value ? '流式连接中' : '就绪'));
const connectionTone = computed<'success' | 'warning' | 'info'>(() => (isRunning.value ? 'warning' : 'success'));
const modelLabel = computed(() => 'deepseek-flash');
const sessionShort = computed(() => sessionId.value.slice(0, 8));
const completedTurns = computed(() => turns.value.filter((turn) => turn.status !== 'running').length);
const totalSteps = computed(() => turns.value.reduce((sum, turn) => sum + turn.steps.length, 0));
const runningHint = computed(() => '等待模型返回下一步动作…');

/** 右侧证据面板跟随最近一轮：正在跑用当前轮，跑完保留该轮结果。 */
const focusTurn = computed<Turn | undefined>(() => {
	const running = turns.value.find((turn) => turn.status === 'running');
	if (running) return running;
	return turns.value[turns.value.length - 1];
});
const activeCitations = computed<AgentCitation[]>(() => focusTurn.value?.citations || []);

function createSessionId(): string {
	if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
		return crypto.randomUUID();
	}
	return `s-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

function pad(value: number): string {
	return String(value).padStart(2, '0');
}

function elapsedLabel(turn: Turn): string {
	return `${(turn.elapsedMs / 1000).toFixed(1)}s`;
}

function answerKind(status: TurnStatus): string {
	if (status === 'refused') return '已拒答';
	if (status === 'error') return '执行失败';
	if (status === 'running') return '生成中';
	return '结论';
}

/** 把后端的原因码翻成可读说明，避免界面直接暴露英文枚举。 */
function reasonLabel(reason: string): string {
	const table: Record<string, string> = {
		NO_RELIABLE_EVIDENCE: '知识库里没有足够依据',
		PLAN_UNPARSEABLE: '模型输出无法解析为工具动作',
		DUPLICATE_TOOL_CALL: '重复调用了相同参数的同一个工具',
		TOOL_REPEAT_LIMIT: '同一工具调用次数达到上限',
		TOTAL_TIMEOUT: '整轮执行超时',
		AUTO_EXECUTION_CLAIM: '回答声称已自动执行设备，被安全守门拦下',
		LLM_ERROR: '大模型调用失败',
		ANSWER_EMPTY: '模型未能生成回答（输出被截断或为空），请重试',
		STREAM_ERROR: '流式连接异常',
		'': '已完成',
	};
	return table[reason] ?? reason;
}

/** 把回答里的 [n] 拆成可点击的引用块，其余按纯文本渲染（不用 v-html，避免注入）。 */
function buildSegments(answer: string): AnswerSegment[] {
	const segments: AnswerSegment[] = [];
	const pattern = /\[(\d{1,3})\]/g;
	let cursor = 0;
	let match = pattern.exec(answer);
	while (match) {
		if (match.index > cursor) {
			segments.push({ type: 'text', text: answer.slice(cursor, match.index) });
		}
		segments.push({ type: 'cite', index: Number(match[1]) });
		cursor = match.index + match[0].length;
		match = pattern.exec(answer);
	}
	if (cursor < answer.length) {
		segments.push({ type: 'text', text: answer.slice(cursor) });
	}
	return segments;
}

function describeStep(event: AgentStepEvent): StepView {
	const data = event.data || {};
	const durationMs = Number(data.durationMs);
	const failed = Boolean(data.error);
	const degraded = Boolean(data.degraded);
	const lowScore = Boolean(data.lowScore);
	const citationCount = readStepCitations(event).length;
	const errorText = data.error ? String(data.error) : '';
	const summary = failed
		? `执行失败：${errorText}`
		: `命中 ${citationCount} 条证据${lowScore ? '（相关性不足）' : ''}`;
	let badge = citationCount > 0 && !lowScore ? '有依据' : '无依据';
	let badgeType: StepView['badgeType'] = citationCount > 0 && !lowScore ? 'success' : 'info';
	if (degraded) {
		badge = '已降级';
		badgeType = 'warning';
	}
	if (failed) {
		badge = '失败';
		badgeType = 'danger';
	}
	return {
		stepNo: event.stepNo,
		toolName: String(event.toolName || '工具'),
		summary,
		badge,
		badgeType,
		durationMs: Number.isFinite(durationMs) ? durationMs : undefined,
		failed,
		degraded,
	};
}

function mergeCitations(existing: AgentCitation[], incoming: AgentCitation[]): AgentCitation[] {
	const merged = new Map<string, AgentCitation>();
	for (const item of existing) merged.set(citationKey(item), item);
	for (const item of incoming) merged.set(citationKey(item), item);
	return Array.from(merged.values()).sort((left, right) => (left.index ?? 0) - (right.index ?? 0));
}

function citationKey(item: AgentCitation): string {
	return `${item.sourceTable}|${item.sourceId}|${item.fieldType}|${item.chunkNo}`;
}

function scrollToBottom(): void {
	nextTick(() => {
		const element = streamRef.value;
		if (element) element.scrollTop = element.scrollHeight;
	});
}

function scrollToEvidence(index: number): void {
	nextTick(() => {
		const target = document.querySelector(`.evidence.is-active`);
		if (target && typeof target.scrollIntoView === 'function') {
			target.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
		}
		void index;
	});
}

function focusCitation(index?: number): void {
	if (index === undefined) return;
	activeCitation.value = activeCitation.value === index ? undefined : index;
	scrollToEvidence(index);
}

function usePreset(question: string): void {
	draft.value = question;
}

function goCenter(): void {
	router.push('/agentCenter');
}

const goCoverage = () => router.push('/visionCoverage');

async function send(): Promise<void> {
	const question = draft.value.trim();
	if (!question || isRunning.value) return;
	draft.value = '';
	activeCitation.value = undefined;

	const turn: Turn = {
		id: `t-${Date.now()}`,
		question,
		crop: crop.value,
		steps: [],
		status: 'running',
		answer: '',
		reason: '',
		citations: [],
		segments: [],
		stepCount: 0,
		startedAt: Date.now(),
		elapsedMs: 0,
		stage: '正在理解问题…',
		degraded: false,
	};
	turns.value.push(turn);
	// **必须通过数组代理再读一次**：push 进去的是原始对象，直接改原始对象不会触发渲染。
	// 之前所有状态都改在原始对象上，界面只靠 100ms 计时器顺带重渲染；计时器一停（流结束）
	// 最后一批状态就渲染不出来——实测表现为右侧"可核对来源"证据面板空白。
	const active = turns.value[turns.value.length - 1];
	scrollToBottom();

	controller.value = new AbortController();
	// 计时器只用于展示耗时，停止时一并清理，避免页面切换后仍在跑。
	if (ticker.value !== undefined) window.clearInterval(ticker.value);
	ticker.value = window.setInterval(() => {
		const running = turns.value.find((item) => item.status === 'running');
		if (running) running.elapsedMs = Date.now() - running.startedAt;
	}, 100);

	try {
		await streamAgentChat(
			{ question, crop: active.crop, sessionId: sessionId.value },
			{
				onEvent: (event) => {
					if (event.type === 'step') {
						active.steps.push(describeStep(event));
						active.stepCount = active.steps.length;
						if (active.steps[active.steps.length - 1]?.degraded) active.degraded = true;
						const cumulative = (event.data?.citations as AgentCitation[]) || [];
						active.citations = cumulative.length
							? cumulative
							: mergeCitations(active.citations, readStepCitations(event));
						active.stage = '已获得观测，规划下一步…';
						scrollToBottom();
						return;
					}
					if (event.type === 'final') {
						const data = event.data || {};
						const status = String(data.status || 'DONE').toUpperCase();
						active.answer = event.message || '';
						active.segments = buildSegments(active.answer);
						active.reason = String(data.reason || '');
						const finalCitations = readFinalCitations(event);
						if (finalCitations.length) active.citations = finalCitations;
						const reportedCount = Number(data.citationCount);
						active.stepCount = Number.isFinite(Number(data.steps)) ? Number(data.steps) : active.stepCount;
						if (!active.citations.length && Number.isFinite(reportedCount) && reportedCount === 0) {
							active.citations = [];
						}
						active.status = status === 'REFUSED' ? 'refused' : status === 'ERROR' ? 'error' : 'done';
						active.elapsedMs = Date.now() - active.startedAt;
						active.stage = '';
						scrollToBottom();
					}
				},
				onFatal: (message) => {
					active.status = 'error';
					active.answer = message;
					active.segments = buildSegments(message);
					active.reason = 'STREAM_ERROR';
					active.elapsedMs = Date.now() - active.startedAt;
					ElMessage.error(message);
				},
				onClosed: () => {
					active.elapsedMs = Date.now() - active.startedAt;
					if (active.status === 'running') {
						// 连接正常结束但没收到 final：如实标记为失败，不假装成功。
						active.status = active.answer ? 'done' : 'error';
						if (!active.answer) {
							active.answer = '连接已结束但没有收到结论，请重试或查看后端日志。';
							active.segments = buildSegments(active.answer);
							active.reason = 'STREAM_ERROR';
						}
					}
					scrollToBottom();
				},
			},
			controller.value.signal
		);
	} finally {
		if (ticker.value !== undefined) {
			window.clearInterval(ticker.value);
			ticker.value = undefined;
		}
	}
}

function stop(): void {
	if (controller.value) controller.value.abort();
	controller.value = null;
}

onUnmounted(() => {
	if (ticker.value !== undefined) window.clearInterval(ticker.value);
	if (controller.value) controller.value.abort();
});
</script>

<style scoped lang="scss">
.agent-chat {
	min-height: calc(100vh - 60px);
	padding: 24px;
	background: #f3f7f2;
	color: #1c2b22;
}
.chat-header {
	display: flex;
	justify-content: space-between;
	align-items: flex-start;
	gap: 24px;
	padding: 4px 0 20px;
	border-bottom: 1px solid #d7e3d9;
}
.eyebrow {
	margin: 0 0 6px;
	color: #708277;
	font-size: 11px;
	font-weight: 650;
}
h2,
h3 {
	margin: 0;
}
h2 {
	font-size: 27px;
}
h3 {
	font-size: 17px;
}
.header-copy {
	max-width: 720px;
	margin: 7px 0 0;
	color: #617168;
	font-size: 13px;
	line-height: 1.65;
}
.header-side {
	display: flex;
	flex-direction: column;
	align-items: flex-end;
	gap: 10px;
}
.badge-stack {
	display: flex;
	gap: 8px;
}
.badge {
	padding: 5px 10px;
	border-radius: 6px;
	font-size: 12px;
	font-weight: 600;
	border: 1px solid transparent;
}
.badge-success {
	background: #e6f3ea;
	color: #2d8a54;
	border-color: #cbe4d4;
}
.badge-warning {
	background: #fbf2e0;
	color: #a9741d;
	border-color: #edd9ad;
}
.badge-info {
	background: #eef2ef;
	color: #56675c;
	border-color: #dbe4dd;
}
.badge-quiet {
	background: #fff;
	color: #617168;
	border-color: #dce8df;
}
.chat-layout {
	display: grid;
	grid-template-columns: minmax(0, 1fr) 340px;
	gap: 14px;
	margin-top: 18px;
	align-items: start;
}
.panel {
	border: 1px solid #dce8df;
	border-radius: 8px;
	background: #fff;
}
.chat-main {
	display: flex;
	flex-direction: column;
	height: calc(100vh - 210px);
	min-height: 520px;
	overflow: hidden;
}
.stream {
	flex: 1;
	padding: 18px;
	overflow-y: auto;
}
.empty-state {
	display: grid;
	grid-template-columns: auto 1fr;
	gap: 16px;
	max-width: 720px;
	margin: 34px auto;
	padding: 22px;
	border: 1px dashed #cfe2d6;
	border-radius: 8px;
	background: #fbfdfb;
}
.empty-icon {
	display: grid;
	width: 50px;
	height: 50px;
	place-items: center;
	border-radius: 8px;
	background: #e4f1e7;
	color: #2d8a54;
	font-size: 24px;
}
.empty-copy {
	margin: 8px 0 0;
	color: #63756a;
	font-size: 13px;
	line-height: 1.7;
}
.turn + .turn {
	margin-top: 22px;
	padding-top: 20px;
	border-top: 1px solid #eef3ef;
}
.question {
	display: flex;
	align-items: baseline;
	gap: 10px;
	padding: 11px 14px;
	border-radius: 8px;
	background: #f2f7f3;
}
.question-label {
	flex: none;
	color: #2d8a54;
	font-size: 11px;
	font-weight: 700;
	letter-spacing: 0.4px;
}
.question p {
	flex: 1;
	margin: 0;
	font-size: 14px;
	line-height: 1.6;
}
.question-crop {
	flex: none;
	padding: 3px 8px;
	border: 1px solid #d7e6da;
	border-radius: 5px;
	color: #617168;
	font-size: 11px;
}
.steps {
	display: grid;
	gap: 8px;
	margin: 14px 0 0 4px;
	padding-left: 14px;
	border-left: 2px solid #e5efe8;
}
.step {
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 9px 11px;
	border: 1px solid #e3ece6;
	border-radius: 7px;
	background: #fcfdfc;
}
.step.is-failed {
	border-color: #f0d5d5;
	background: #fdf7f7;
}
.step.is-degraded {
	border-color: #efe0bd;
	background: #fffcf5;
}
.step.is-running {
	border-style: dashed;
	border-color: #cfdfd5;
	background: #fafdfb;
}
.step-index {
	flex: none;
	width: 26px;
	color: #8aa094;
	font-size: 12px;
	font-weight: 700;
}
.step-body {
	flex: 1;
	min-width: 0;
}
.step-body strong {
	display: block;
	font-size: 13px;
}
.step-body span {
	display: block;
	margin-top: 3px;
	color: #7a8a80;
	font-size: 12px;
	overflow-wrap: anywhere;
}
.step-time {
	flex: none;
	color: #93a29a;
	font-size: 11px;
}
.answer {
	margin-top: 14px;
	padding: 15px 16px;
	border: 1px solid #dbe9e0;
	border-radius: 8px;
	background: #fff;
}
.answer-done {
	border-left: 3px solid #2d8a54;
}
.answer-refused {
	border-left: 3px solid #c68a25;
	background: #fffdf8;
}
.answer-error {
	border-left: 3px solid #b84242;
	background: #fdf8f8;
}
.answer-head {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
	margin-bottom: 10px;
}
.answer-kind {
	font-size: 13px;
	font-weight: 700;
}
.answer-meta {
	color: #7c8c82;
	font-size: 11px;
}
.answer-text {
	margin: 0;
	font-size: 14px;
	line-height: 1.85;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
.cite {
	margin: 0 1px;
	padding: 1px 5px;
	border: 1px solid #cfe2d6;
	border-radius: 4px;
	background: #f1f8f3;
	color: #2d8a54;
	font-size: 11px;
	font-weight: 700;
	cursor: pointer;
}
.cite.is-active {
	background: #2d8a54;
	border-color: #2d8a54;
	color: #fff;
}
.answer-flag {
	margin: 12px 0 0;
	padding: 8px 10px;
	border-radius: 6px;
	background: #fdf6e7;
	color: #9a6a16;
	font-size: 12px;
	line-height: 1.6;
}
.answer-note {
	margin: 12px 0 0;
	color: #84928a;
	font-size: 12px;
	line-height: 1.65;
}
.composer {
	display: flex;
	align-items: flex-end;
	gap: 10px;
	padding: 12px 14px;
	border-top: 1px solid #e6efe9;
	background: #fbfdfb;
}
.crop-select {
	flex: none;
	width: 108px;
}
.composer-input {
	flex: 1;
}
.composer-actions {
	display: flex;
	flex: none;
	gap: 8px;
	padding-bottom: 2px;
}
.chat-aside {
	display: grid;
	gap: 14px;
	// 给右下角既有全局浮窗（"番茄智能体"状态卡）让出空间，否则会压住示例问题列表底部条目。
	padding-bottom: 104px;
}
.aside-panel {
	padding: 16px;
}
.panel-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
	margin-bottom: 13px;
}
.stat-list {
	display: grid;
	gap: 9px;
}
.stat-row {
	display: flex;
	justify-content: space-between;
	padding-bottom: 8px;
	border-bottom: 1px solid #eef3ef;
	color: #617168;
	font-size: 13px;
}
.stat-row strong {
	color: #223428;
}
.evidence-list {
	display: grid;
	gap: 10px;
	max-height: 320px;
	overflow-y: auto;
}
.evidence {
	padding: 10px 11px;
	border: 1px solid #e3ece6;
	border-radius: 7px;
	background: #fcfdfc;
}
.evidence.is-active {
	border-color: #2d8a54;
	background: #f4faf6;
	box-shadow: 0 0 0 3px rgba(45, 138, 84, 0.1);
}
.evidence-head {
	display: flex;
	align-items: center;
	gap: 7px;
}
.evidence-index {
	color: #2d8a54;
	font-size: 11px;
	font-weight: 700;
}
.evidence-head strong {
	flex: 1;
	font-size: 13px;
}
.evidence-snippet {
	margin: 7px 0 0;
	color: #5c6d63;
	font-size: 12px;
	line-height: 1.65;
}
.evidence-source {
	margin: 7px 0 0;
	color: #93a29a;
	font-size: 11px;
}
.preset-group + .preset-group {
	margin-top: 12px;
}
.preset-label {
	margin: 0 0 7px;
	color: #7c8c82;
	font-size: 11px;
	font-weight: 650;
}
.preset {
	display: block;
	width: 100%;
	margin-bottom: 7px;
	padding: 9px 10px;
	border: 1px solid #e0eae3;
	border-radius: 7px;
	background: #fcfdfc;
	color: #3d4f44;
	font-size: 12px;
	line-height: 1.6;
	text-align: left;
	cursor: pointer;
	transition: border-color 0.15s ease, background 0.15s ease;
}
.preset:hover:not(:disabled) {
	border-color: #bcd9c6;
	background: #f4faf6;
}
.preset:disabled {
	cursor: not-allowed;
	opacity: 0.55;
}
.muted {
	margin: 8px 0 0;
	color: #84928a;
	font-size: 13px;
	line-height: 1.65;
}
@media (max-width: 1180px) {
	.chat-layout {
		grid-template-columns: 1fr;
	}
	.chat-main {
		height: auto;
		min-height: 460px;
		max-height: 640px;
	}
	.evidence-list {
		max-height: none;
	}
}
@media (max-width: 680px) {
	.agent-chat {
		padding: 14px;
	}
	.chat-header {
		flex-direction: column;
	}
	.header-side {
		align-items: flex-start;
	}
	.composer {
		flex-wrap: wrap;
	}
	.crop-select {
		width: 100%;
	}
}
</style>
