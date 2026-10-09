<template>
	<div class="agent-chat">
		<header class="chat-header">
			<div>
				<p class="eyebrow">AGRICULTURE / LLM AGENT</p>
				<h2>AI 决策对话</h2>
				<p class="header-copy">
                    {{ chatScope === 'general' ? '讨论病害、栽培与农事问题。需要时查资料，也能直接解释原理并给出建议。' : '围绕当前大棚交流。读取本轮环境与处置反馈，讨论并应用管理方案。' }}
				</p>
				<div class="chat-scopes" role="group" aria-label="问答范围">
					<button :class="{active:chatScope==='general'}" :aria-pressed="chatScope==='general'" :disabled="isRunning" @click="switchChat('general')">普通问答</button>
					<button :class="{active:chatScope==='greenhouse'}" :aria-pressed="chatScope==='greenhouse'" :disabled="isRunning" @click="switchChat('greenhouse')">关联大棚</button>
				</div>
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
							<h3>{{ chatScope === 'general' ? '从一个农业问题开始' : '从一个问题开始，和你的大棚一起判断' }}</h3>
							<p class="empty-copy">
								{{ chatScope === 'general' ? '描述作物、症状或你想了解的内容。普通问答使用独立会话，不带入模拟大棚的数据。' : '可以问为什么报警、该如何处理，也可以讨论生长与栽培。助手会读取关联大棚的本轮环境和处置反馈。' }}
							</p>
						</div>
					</div>

					<article v-for="turn in turns" :key="turn.id" class="turn">
						<div class="question">
							<span class="question-label">提问</span>
							<p>{{ turn.question }}</p>
							<span v-if="turn.crop" class="question-crop">{{ turn.crop }}</span>
							<span v-if="turn.detection" class="question-vision">
								📷 {{ turn.detection.cropType }} · {{ turn.detection.detectedLabel }} →
								{{ turn.detection.explainable ? turn.detection.kbDiseaseName : "知识库暂无对应条目" }}
							</span>
						</div>

                        <div v-if="turn.status==='running'" class="reply-progress"><span class="progress-dot"></span>{{ turn.stage }}<small>{{ elapsedLabel(turn) }}</small></div>
                        <details v-if="turn.steps.length" class="tool-details">
                            <summary>查看 {{ turn.steps.length }} 条工具记录</summary>
                            <div v-for="step in turn.steps" :key="step.stepNo" class="step" :class="{'is-failed':step.failed}">
                                <span class="step-index">{{ pad(step.stepNo) }}</span>
                                <div class="step-body"><strong>{{ toolLabel(step.toolName) }}</strong><span>{{ step.summary }}</span></div>
                                <small>{{ step.durationMs }} ms</small>
                            </div>
                        </details>
						<div v-if="turn.answer" class="answer" :class="`answer-${turn.status}`">
							<div class="answer-head">
								<span class="answer-kind">{{ answerKind(turn.status, turn.reason) }}</span>
								<span v-if="turn.status === 'done'" class="answer-meta">
									{{ turn.citations.length ? turn.citations.length+" 条引用" : "模型解释" }} · {{ (turn.elapsedMs / 1000).toFixed(1) }}s
								</span>
								<span v-else class="answer-meta">{{ reasonLabel(turn.reason) }}</span>
							</div>
                            <AnswerBody :text="turn.answer" :citations="turn.citations" @cite="focusReplyCitation(turn,$event)" />
                            <div class="reply-actions"><button @click="copyReply(turn)">复制</button><button :disabled="isRunning" @click="retryReply(turn)">重新回答</button><span v-if="turn.context">大棚时间 {{ turn.context.at }}</span></div>
                            <details v-if="turn.citations.length" class="reply-sources">
                                <summary>{{ turn.citations.length }} 条引用来源</summary>
                                <button v-for="item in turn.citations" :key="item.index" @click="focusReplyCitation(turn,item.index)">[{{ item.index }}] {{ item.sourceName }} · {{ item.diseaseName }}</button>
                            </details>
							<p v-if="turn.degraded" class="answer-flag">
								本次检索已降级为纯关键词模式（向量服务不可达），结果可能漏召语义相近的条目。
							</p>

						</div>
					</article>
				</div>

				<details class="vision-details"><summary>附带图像识别结果</summary><div class="vision-bar">
					<span class="vision-icon">📷</span>
					<template v-if="latestVision">
						<span class="vision-text">
							{{ latestVision.cropType }} · {{ latestVision.detectedLabel }} · 置信度 {{ latestVision.confidence }}
							<template v-if="latestVision.explainable"> → 知识库《{{ latestVision.kbDiseaseName }}》（{{ latestVision.mappingRule }}）</template>
							<template v-else> → <b class="vision-gap">识别证据待检索核对</b></template>
						</span>
						<el-switch v-model="attachVision" size="small" active-text="附带进对话" />
					</template>
					<span v-else class="vision-text vision-empty">当前运行还没有导入识别结果</span>
					<el-button link type="primary" :loading="visionLoading" @click="loadVision">刷新</el-button>
					<el-button link type="primary" :disabled="!latestVision || isRunning" @click="askVision">解释这次识别结果</el-button>
				</div>
</details>
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
						maxlength="1200"
						show-word-limit
						:placeholder="chatScope === 'general' ? '描述症状或想问的问题…（Enter 发送，Shift+Enter 换行）' : '问问当前大棚，或描述你遇到的问题…（Enter 发送，Shift+Enter 换行）'"
						@keydown.enter.exact.prevent="send"
					/>
					<div class="composer-actions">
						<el-button v-if="isRunning" type="warning" plain @click="stop">停止</el-button>
						<el-button type="primary" :loading="isRunning" :disabled="!draft.trim()" @click="send">提问</el-button>
					</div>
				</footer>
			</main>

			<aside class="chat-aside">
                <GreenhouseContext v-if="chatScope==='greenhouse'" /><section v-if="chatScope==='greenhouse'" class="panel aside-panel greenhouse-context">
                    <p class="eyebrow">关联大棚</p><h3>{{ simulation ? '当前大棚状态' : '尚未关联运行' }}</h3>
                    <p class="muted">{{ simulation ? simulation.current.at+' · '+simulation.year+'年 M3参考' : '先开启大棚推演，助手即可读取环境与设备。' }}</p>
                    <template v-if="simulation?.current.scenario">
                        <div class="context-readings"><div><span>温度</span><strong>{{ simulation.current.scenario.environment.temperatureC.toFixed(1) }}<small>°C</small></strong></div><div><span>湿度</span><strong>{{ simulation.current.scenario.environment.airHumidityPct.toFixed(0) }}<small>%</small></strong></div></div>
                        <p class="context-event">{{ simulation.current.scenario.weather?.title || '暂无天气事件' }} · {{ decisionStatus(simulation.current.scenario.decision.status) }}</p>
                        <p v-if="simulation.current.scenario.decision.plan" class="muted">{{ simulation.current.scenario.decision.plan.summary }}</p>
                    </template>
                    <p v-if="simulationError" class="context-error">{{ simulationError }}</p>
                    <div class="context-links"><button v-if="simulationId" @click="openSimulation">打开大棚 ↗</button><button @click="refreshSimulation()">刷新关联</button><button v-if="simulationId" @click="unlinkSimulation">解除关联</button></div>
                    <details class="context-note"><summary>查看数据来源</summary><p>M3为历史观测回放；天气和设备响应由模型计算。</p></details>
                </section>
				<section v-else class="panel aside-panel">
					<p class="eyebrow">CONVERSATION</p><h3>独立会话</h3>
					<p class="muted">病害解释、知识查询和普通交流照常使用。切换到关联大棚后，可继续另一侧的对话。</p>
					<div class="conversation-stats"><span>已完成问答<strong>{{ completedTurns }}</strong></span><span>工具调用<strong>{{ totalSteps }}</strong></span></div>
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
								{{ item.sourceName || '来源未登记' }}<span v-if="item.sourceType"> · {{ item.sourceType }} 类</span>
								· {{ item.sourceTable }}#{{ item.sourceId }} · 片段 {{ item.chunkNo }}
								<a v-if="verifiedSourceUrl(item)" :href="verifiedSourceUrl(item)" target="_blank" rel="noopener noreferrer">查看原文</a>
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

<script setup lang="ts" name="agentChat">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AnswerBody from '/@/components/agent/AnswerBody.vue';
import {type M3LiveFrame} from '/@/api/m3/live';
import {useGreenhouseStore} from '/@/stores/greenhouse';
import {getFarmTask,updateFarmTask,type FarmTask} from '/@/api/agent/tasks';
import GreenhouseContext from '/@/components/GreenhouseContext.vue';
import { AgentVisionEvent, getActiveAgentRun, getAgentVisionEvents } from '/@/api/agent';
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
    context?:M3LiveFrame;
	/** 本轮附带进对话的识别结果（若有），用于在提问气泡里显示来源。 */
	detection?: AgentVisionEvent | null;
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
const route = useRoute();
type ChatScope = 'general' | 'greenhouse';
interface ChatState { sessionId:string; crop:string; draft:string; attachVision:boolean; turns:Turn[]; }
const greenhouse=useGreenhouseStore(),independentTask=ref<FarmTask|null>(null);
function createChatState(key:string):ChatState {
 const storageKey='agri-chat-session:'+key;
 const id=sessionStorage.getItem(storageKey)||createSessionId();sessionStorage.setItem(storageKey,id);
 return {sessionId:id,crop:'番茄',draft:'',attachVision:false,turns:[]};
}
const chatScope=ref<ChatScope>(typeof route.query.liveRun==='string'?'greenhouse':'general');
const chatStates=reactive<Record<string,ChatState>>({general:createChatState('general')});
const simulationId=computed(()=>greenhouse.activeRunId);
const simulation=computed(()=>chatScope.value==='greenhouse'?greenhouse.run:null),simulationError=computed(()=>greenhouse.error),selectedTurn=ref('');
const linkedKey=computed(()=> 'greenhouse:'+simulationId.value+':'+(greenhouse.task?.id||route.query.taskId||''));
const activeChat=computed(()=>{
 const key=chatScope.value==='general'?'general:'+(!route.query.liveRun&&route.query.taskId?route.query.taskId:'independent'):linkedKey.value;
 if(!chatStates[key])chatStates[key]=createChatState(key);
 return chatStates[key];
});
let chatDisposed=false,unsubscribe:(()=>void)|undefined;
async function refreshSimulation(){
 if(chatScope.value!=='greenhouse')return;
 const id=typeof route.query.liveRun==='string'?route.query.liveRun:greenhouse.activeRunId;
 if(id)await greenhouse.attachRun(id);
 if(typeof route.query.taskId==='string'&&greenhouse.task?.id!==route.query.taskId)await greenhouse.attachTask(route.query.taskId);
 restoreTaskConversation();
}
function restoreTaskConversation(){
 const saved=chatScope.value==='greenhouse'?greenhouse.task:independentTask.value;
 if(!saved||activeChat.value.turns.length)return;
 let pending='';
 for(const item of saved.turns||[]){
  if(item.role.toUpperCase()==='USER'){pending=item.content;continue;}
  if(item.role.toUpperCase()!=='ASSISTANT')continue;
  activeChat.value.turns.push({id:item.id||'saved-'+activeChat.value.turns.length,question:pending||'已有农情讨论',crop:saved.crop,steps:[],status:'done',answer:item.content,reason:'',citations:(item.sources||[]) as AgentCitation[],segments:buildSegments(item.content),stepCount:0,startedAt:0,elapsedMs:0,stage:'',degraded:false});pending='';
 }
 if(!activeChat.value.draft)activeChat.value.draft=saved.question||'';
}
function unlinkSimulation(){switchChat('general');void router.replace({path:'/agentChat',query:{}});}
async function restoreIndependentTask(){
 independentTask.value=null;
 if(chatScope.value==='general'&&!route.query.liveRun&&typeof route.query.taskId==='string'){independentTask.value=await getFarmTask(route.query.taskId);restoreTaskConversation();void loadVision();}
}
watch(()=>[route.path,route.query.liveRun,route.query.taskId] as const,([path,value])=>{
 if(path!=='/agentChat')return;
 if(typeof value==='string'&&value){switchChat('greenhouse');void refreshSimulation().catch(e=>ElMessage.error(String(e)));}
 else {switchChat('general');void restoreIndependentTask().catch(e=>ElMessage.error(String(e)));}
});
function switchChat(scope:ChatScope){
 if(isRunning.value)return;
 chatScope.value=scope;activeCitation.value=undefined;selectedTurn.value='';
 if(scope==='greenhouse')void refreshSimulation().catch(e=>ElMessage.error(String(e)));
 scrollToBottom();
}
function openSimulation(){router.push({path:'/digitalTwin',query:greenhouse.linkedQuery()});}
function decisionStatus(s:string){return ({IDLE:'等待事件',NEEDS_DECISION:'待分析',ANALYZING:'AI分析中',OBSERVING:'动作已应用，观察中',PROPOSED:'方案未执行',MITIGATED:'环境风险已缓解',UNRESOLVED:'风险仍存在',BLOCKED:'动作被约束拦截',FAILED:'AI请求失败',STALE:'方案已过期'} as Record<string,string>)[s]||s;}
function toolLabel(s:string){return ({'knowledge.search':'查询资料','simulation.snapshot':'读取当前大棚','simulation.decide':'制定仿真方案','platform.greenhouseState':'读取温室','vision.explain':'核对识别依据'} as Record<string,string>)[s]||s.replace('knowledge.','资料 · ').replace('platform.','平台 · ');}
function focusReplyCitation(turn:Turn,index?:number){selectedTurn.value=turn.id;focusCitation(index);}
async function copyReply(turn:Turn){try{await navigator.clipboard.writeText(turn.answer);ElMessage.success('已复制回答');}catch{ElMessage.info('复制不可用，请选中正文复制');}}
function retryReply(turn:Turn){draft.value=turn.question;if(crops.includes(turn.crop))crop.value=turn.crop;void send(false);}

const crops = ['番茄', '玉米', '水稻', '小麦', '马铃薯', '棉花', '苹果', '葡萄', '草莓'];
const greenhousePresets = [
 {label:'理解当前大棚',items:['现在大棚最需要关注什么？','为什么高湿时不宜一直开湿帘？']},
 {label:'形成处置方案',items:['结合当前事件，给一个能执行的仿真方案。','请执行当前大棚的仿真处置，并观察反馈。']},
 {label:'研究与栽培',items:['株高修正能说明产量也准确吗？','阴雨天气的补光和水分管理该怎么配合？']},
];
const generalPresets=[
 {label:'病害与识别',items:['番茄叶片出现褐色轮纹斑，可能是什么原因？','请解释这次图像识别结果。']},
 {label:'种植与管理',items:['连续阴雨时，番茄的水肥和补光怎么配合？','玉米叶片发黄，应该先检查哪些原因？']},
 {label:'原理与资料',items:['空气湿度与土壤湿度有什么区别？','怎样判断一篇论文的结论适不适合我的作物？']},
];
const presetGroups=computed(()=>chatScope.value==='general'?generalPresets:greenhousePresets);

const crop = computed({get:()=>activeChat.value.crop,set:(value:string)=>{activeChat.value.crop=value;}});
/** 当前运行最近一条识别结果；null 表示还没导入。 */
const latestVision = ref<AgentVisionEvent | null>(null);
const visionLoading = ref(false);
/** 是否把识别结果附带进对话。默认关闭——否则用户问别的问题也会被识别结果带偏。 */
const attachVision = computed({get:()=>activeChat.value.attachVision,set:(value:boolean)=>{activeChat.value.attachVision=value;}});
const draft = computed({get:()=>activeChat.value.draft,set:(value:string)=>{activeChat.value.draft=value;}});
const turns = computed(()=>activeChat.value.turns);
const streamRef = ref<HTMLElement | null>(null);
const activeCitation = ref<number | undefined>(undefined);
const sessionId = computed(()=>activeChat.value.sessionId);
const controller = ref<AbortController | null>(null);
const ticker = ref<number | undefined>(undefined);

const isRunning = computed(() => turns.value.some((turn) => turn.status === 'running'));
const connectionLabel = computed(() => (isRunning.value ? '流式连接中' : '就绪'));
const connectionTone = computed<'success' | 'warning' | 'info'>(() => (isRunning.value ? 'warning' : 'success'));
const modelLabel = computed(() => 'deepseek-flash');
const sessionShort = computed(() => sessionId.value.slice(0, 8));
const completedTurns = computed(() => turns.value.filter((turn) => turn.status !== 'running').length);
const totalSteps = computed(() => turns.value.reduce((sum, turn) => sum + turn.steps.length, 0));
const runningHint = computed(() => '正在结合上下文准备回答…');

/** 右侧证据面板跟随最近一轮：正在跑用当前轮，跑完保留该轮结果。 */
const focusTurn = computed<Turn | undefined>(() => {
	const selected=turns.value.find(turn=>turn.id===selectedTurn.value);
    if(selected)return selected;
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

/**
 * 徽标文案。
 *
 * 后端把「模型调用失败」也归在 REFUSED 终态（reason=ANSWER_EMPTY / LLM_ERROR），
 * 若只看 status 会把一次上游故障标成「已拒答」——与正文里"作答失败…这不是知识库缺少依据"自相矛盾。
 * 因此这里同时看 reason：失败类原因的徽标要说「执行失败」。
 * 后端为什么不直接改 status：REFUSED 这个终态已被落库记录与多处消费方依赖，
 * 为了一处措辞动它是更大的改动；文案归属放在展示层更稳妥。
 */
function answerKind(status: TurnStatus, reason?: string): string {
	if (status === 'refused') {
		// 检索降级不是"拒答"——是能力不完整，用户该重试而不是换问法
		if (reason === 'DEGRADED_RETRIEVAL') return '检索降级';
		return reason === 'ANSWER_EMPTY' || reason === 'LLM_ERROR' ? '执行失败' : '已拒答';
	}
	if (status === 'error') return '执行失败';
	if (status === 'running') return '生成中';
	// 库缺依据时改用模型自身通用知识作答（2026-09-27 起）：仍是 DONE，但必须与
	// "有知识库依据的结论"区分开，否则用户会把模型经验读成有出处的结论。
	if (reason === 'GENERAL_KNOWLEDGE') return '回答';
	return '回答';
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
		GENERAL_KNOWLEDGE: '未引用知识库：以下为模型掌握的通用农艺经验，需人工确认',
		DEGRADED_RETRIEVAL: '检索降级：向量服务不可达，本轮只用关键词检索，未取得依据；请稍后重试（这不代表知识库没有依据）',
		KNOWLEDGE_INSUFFICIENT: '资料库不足：知识库中没有该检测类别的可核对条目',
		NEED_CROP: '需要先确认作物（同名类别在多个作物上都存在）',
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
	const summary = failed ? String(data.error) : String(event.message||'工具已完成');
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

function verifiedSourceUrl(item: AgentCitation): string {
	if (!item.sourceUrl) return '';
	try {
		const url = new URL(item.sourceUrl);
		return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : '';
	} catch {
		return '';
	}
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

/**
 * 读取当前运行最近一条识别结果（含后端按已核验映射表给出的知识库结论）。
 * 只展示、不自动附带：附带与否由用户显式决定。
 */
async function loadVision(): Promise<void> {
	visionLoading.value = true;
	try {
        const evidenceTask=chatScope.value==='greenhouse'?greenhouse.task:independentTask.value;
        if(evidenceTask){
            const evidence=[...evidenceTask.evidence].reverse().find(e=>e.type==='IMAGE');
            const details=evidence?.details as Record<string,unknown>|undefined;
            latestVision.value=evidence?{detectedLabel:evidence.label,cropType:evidenceTask.crop,confidence:String(details?.confidence||'未提供'),explainable:false,observedAt:evidence.createdAt} as AgentVisionEvent:null;
            return;
        }
        if(!route.query.recordId){latestVision.value=null;return;}
        const run = await getActiveAgentRun();
		if (!run || run.id === undefined || run.id === null) {
			latestVision.value = null;
			return;
		}
		const recordId = typeof route.query.recordId === 'string' ? route.query.recordId : '';
		const events = await getAgentVisionEvents(run.id, recordId ? 20 : 1);
		latestVision.value = recordId
			? events.find((event) => String(event.sourceRecordId) === recordId) || null
			: events[0] || null;
		attachVision.value = Boolean(recordId && latestVision.value);
		if (recordId && latestVision.value) {
			crop.value = crops.includes(latestVision.value.cropType || '') ? latestVision.value.cropType! : crop.value;
			draft.value = '请解释这次识别结果，并给出有依据的处置建议。';
		}
	} catch {
		latestVision.value = null;
	} finally {
		visionLoading.value = false;
	}
}

/** 把识别结果拼进提问：写明作物、类别、置信度，以及知识库能否解释（不能就是不能）。 */
function buildVisionQuestion(userQuestion: string, detection: AgentVisionEvent): string {
	const mapping = detection.explainable
		? `知识库已核验对应条目《${detection.kbDiseaseName}》（依据：${detection.mappingRule}）`
		: detection.mappingRule ? '知识库中暂无该类别可核对的对应条目' : '图像结果尚未完成知识依据和田间核对，请按候选证据检索验证';
	return [
		'【本次会话附带识别结果】',
		`作物：${detection.cropType || '未知'}；检测类别：${detection.detectedLabel}；置信度：${detection.confidence ?? '未提供'}；${mapping}。`,
		'',
		`用户问题：${userQuestion}`,
	].join('\n');
}

/** 一键解释最近一次识别结果：打开附带并发送。 */
function askVision(): void {
	if (!latestVision.value) {
		ElMessage.info('当前运行还没有导入识别结果');
		return;
	}
	attachVision.value = true;
	draft.value = '请解释这次识别结果，并给出有依据的处置建议。';
	void send();
}

async function send(allowSimulationActions = true): Promise<void> {
	const question = draft.value.trim();
	if (!question || isRunning.value) return;
    const linked=chatScope.value==='greenhouse';
    let taskId:string|undefined;
    if(linked){
        if(!greenhouse.run){ElMessage.info('请先接入大棚运行，或切换普通问答');return;}
        try{taskId=(await greenhouse.ensureTask(question)).id;}catch(e){ElMessage.error(String(e));return;}
    }else if(independentTask.value){
        try{independentTask.value=await updateFarmTask(independentTask.value.id,{question});taskId=independentTask.value.id;}catch(e){ElMessage.error(String(e));return;}
    }
	draft.value = '';
	activeCitation.value = undefined;selectedTurn.value='';

	const detection = attachVision.value ? latestVision.value : null;
	// 附带识别结果时：作物用检测结果的作物（避免歧义），提问里写明检测类别与知识库映射结论。
	const requestCrop = detection?.cropType || crop.value;
	const requestQuestion = detection ? buildVisionQuestion(question, detection) : question;

	const turn: Turn = {
		id: `t-${Date.now()}`,
		detection,
		question,
		crop: requestCrop,
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
			{ question: requestQuestion, crop: active.crop, sessionId: sessionId.value,taskId,requestId:crypto.randomUUID(),simulationRunId:linked?(simulationId.value||undefined):undefined,allowSimulationActions:chatScope.value==='greenhouse'&&allowSimulationActions!==false },
			{
				onEvent: (event) => {
                    if(event.type==='taskWarning'){ElMessage.warning(event.message||'回答已完成，但任务保存失败');return;}
                    if(event.type==='context'){active.context=event.data?.snapshot as M3LiveFrame;active.stage='已读取当前大棚，准备回答…';return;}
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
						active.citations = finalCitations;
                        if(data.simulationContext)active.context=data.simulationContext as M3LiveFrame;
                        if(linked){void greenhouse.refreshRun();void greenhouse.refreshTask().catch(()=>undefined);}
                        else if(taskId)void getFarmTask(taskId).then(value=>{independentTask.value=value;}).catch(()=>undefined);
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
	const active=turns.value.find(turn=>turn.status==='running');
	if(active){active.status='error';active.reason='CANCELLED';active.stage='';active.answer=chatScope.value==='greenhouse'?'回答已停止。已提交的仿真方案请查看右侧大棚状态。':'回答已停止，可以继续提问。';active.elapsedMs=Date.now()-active.startedAt;}
	if (controller.value) controller.value.abort();
	controller.value = null;
}

let lastIncomingQuestion='';
function applyIncomingQuestion(){
	if(route.path!=='/agentChat'||isRunning.value)return;
	const incomingCrop = typeof route.query.crop === 'string' ? route.query.crop : '';
	const incomingDetection = typeof route.query.detection === 'string' ? route.query.detection.trim().slice(0, 120) : '';
	const incomingScore = typeof route.query.score === 'string' ? route.query.score.trim().slice(0, 60) : '';
	if(!incomingCrop&&!incomingDetection)return;
	const signature=JSON.stringify([incomingCrop,incomingDetection,incomingScore]);
	if(signature===lastIncomingQuestion)return;
	lastIncomingQuestion=signature;
	if (crops.includes(incomingCrop)) crop.value = incomingCrop;
	if (incomingDetection) {
		draft.value = `图像模型检出的候选类别为“${incomingDetection}”${incomingScore ? `，未校准模型分数为 ${incomingScore}` : ''}。请先核对知识库是否有该作物的对应依据，再解释可能含义；依据不足时请明确说明。`;
	}
}
watch(()=>[route.path,route.query.crop,route.query.detection,route.query.score],applyIncomingQuestion);
watch(()=>greenhouse.task?.id,()=>{restoreTaskConversation();void loadVision();});
onMounted(() => {
	applyIncomingQuestion();
	unsubscribe=greenhouse.subscribe();void loadVision();void refreshSimulation().catch(e=>ElMessage.error(String(e)));void restoreIndependentTask().catch(e=>ElMessage.error(String(e)));
});

onUnmounted(() => {
    chatDisposed=true;unsubscribe?.();
	if (ticker.value !== undefined) window.clearInterval(ticker.value);
	if (controller.value) controller.value.abort();
});
</script>

<style scoped lang="scss">
.agent-chat{min-height:calc(100vh - 60px);padding:28px 34px;background:#f6f4ed;color:#38463c;font-family:"Microsoft YaHei","PingFang SC",sans-serif}
.chat-scopes{display:inline-flex;gap:4px;margin-top:15px;padding:3px;background:#eaece1;border:1px solid #dce0d0}.chat-scopes button{border:0;background:transparent;color:#87907a;font:inherit;font-size:12px;padding:7px 16px;cursor:pointer}.chat-scopes button.active{background:#fffdf7;color:#49603e;box-shadow:0 1px 3px #43503812}.conversation-stats{display:flex;gap:28px;margin-top:18px;font-size:12px;color:#89947d}.conversation-stats strong{display:block;font-size:24px;font-weight:400;color:#526647;margin-top:8px}
.chat-header{display:flex;justify-content:space-between;gap:24px;align-items:flex-start;max-width:1440px;margin:0 auto;padding:5px 0 23px;border-bottom:1px solid #dedfd1}
.eyebrow{font-size:12px;letter-spacing:1.7px;color:#8b917f;margin:0 0 9px}h2,h3{margin:0}h2{font-size:27px;letter-spacing:-.6px;font-weight:500}h3{font-size:16px;font-weight:500}.header-copy{font-size:12px;line-height:1.8;color:#87907d;margin:9px 0 0;max-width:640px}.header-side{display:grid;justify-items:end;gap:7px}.badge-stack{display:flex;gap:8px}.badge{padding:5px 9px;font-size:12px;background:#e8eddf;color:#56724d}.badge-quiet{background:transparent;color:#9b9b88}.badge-warning{background:#f1e9d4;color:#9b7d3c}
.chat-layout{max-width:1440px;display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:32px;margin:22px auto 0;align-items:start}.chat-main{background:#fffdf7;display:flex;flex-direction:column;min-width:0;border:1px solid #e0e0d3}.stream{height:calc(100vh - 410px);min-height:260px;max-height:780px;overflow:auto;padding:26px 32px;scrollbar-width:thin}.empty-state{display:flex;align-items:center;gap:20px;min-height:300px;max-width:590px;margin:auto}.empty-icon{height:48px;width:48px;display:grid;place-items:center;font-size:27px;color:#657d52;background:#e9eddf;flex-shrink:0}.empty-state h3{font-size:22px;line-height:1.6}.empty-copy{font-size:13px;line-height:1.9;color:#8a917e;margin-top:12px}
.turn{margin:0 0 30px}.question{display:flex;align-items:flex-start;gap:12px;border-bottom:1px solid #ecebe1;padding:0 0 18px;margin-bottom:17px}.question p{font-size:14px;line-height:1.75;margin:0;flex:1;color:#384b3b}.question-label{font-size:11px;letter-spacing:1px;color:#97a08b;padding-top:5px}.question-crop{font-size:12px;color:#8c9580;padding-top:5px}.question-vision{font-size:12px;color:#929b83}.answer{padding:4px 0}.answer-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}.answer-kind{font-size:12px;color:#71855d;letter-spacing:1px}.answer-meta{font-size:12px;color:#9da08c}.answer-error,.answer-refused{color:#9a7557}.answer-flag{font-size:12px;line-height:1.7;color:#aa8857}
.reply-progress{display:flex;align-items:center;gap:9px;color:#809166;font-size:12px;margin:16px 0}.reply-progress small{margin-left:auto;color:#a6aa97}.progress-dot{height:5px;width:5px;border-radius:50%;background:#7e9968;animation:pulse 1.4s infinite}.tool-details,.reply-sources{color:#929b85;font-size:12px;margin:12px 0}.tool-details summary,.reply-sources summary{cursor:pointer;padding:6px 0}.step{display:flex;gap:10px;padding:10px 0;border-bottom:1px solid #eeeee5;font-size:12px}.step-body{display:grid;gap:5px;flex:1;line-height:1.6}.step-body strong{font-weight:500;color:#687c56}.step-index,.step small{color:#adb19f}.step.is-failed{color:#a66f55}.reply-actions{display:flex;gap:14px;margin:17px 0 7px;align-items:center}.reply-actions button,.context-links button{font:inherit;font-size:12px;border:0;background:transparent;color:#89967c;padding:4px 0;cursor:pointer}.reply-actions button:hover,.context-links button:hover{color:#4f7448}.reply-actions span{font-size:11px;color:#a9ac9b;margin-left:auto}.reply-sources button{display:block;text-align:left;width:100%;font:inherit;background:none;border:0;border-bottom:1px solid #e9ebdf;padding:9px 0;color:#71835f;cursor:pointer}
.composer{display:grid;grid-template-columns:88px minmax(0,1fr) auto;gap:12px;align-items:center;border-top:1px solid #dfdfd2;padding:18px 20px;background:#faf9f1}.composer-input :deep(textarea){background:#fffef8;border-color:#dedfd1;font-size:12px;line-height:1.8}.composer-input :deep(.el-input__count){background:transparent;font-size:11px}.composer-actions{display:grid;gap:6px}.composer-actions :deep(.el-button){margin:0}.composer :deep(.el-button--primary){background:#60764f;border-color:#60764f}.vision-details{font-size:12px;color:#a0a58f;padding:9px 22px;border-top:1px solid #eeeee5}.vision-details summary{cursor:pointer}.vision-bar{display:flex;align-items:center;gap:10px;padding-top:10px;flex-wrap:wrap;font-size:12px}.vision-text{flex:1}.vision-icon{display:none}.vision-gap{color:#a18457}
.chat-aside{display:flex;flex-direction:column;gap:24px;min-width:0;max-height:calc(100vh - 310px);overflow:auto;padding-right:7px;scrollbar-width:thin}.aside-panel{padding:0 0 23px;border-bottom:1px solid #dedfd0}.panel-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:15px}.muted{font-size:12px;line-height:1.8;color:#929981}.context-readings{display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:16px 0}.context-readings span{display:block;font-size:12px;color:#8d967e}.context-readings strong{display:block;font-size:27px;font-weight:400;margin-top:6px;font-variant-numeric:tabular-nums}.context-readings small{font-size:12px;margin-left:6px}.context-event{font-size:12px;border-left:2px solid #9bad82;padding:8px 11px;background:#ecefe3}.context-links{display:flex;gap:13px;margin:12px 0}.context-note{font-size:11px;line-height:1.7;color:#a1a591;display:block}.context-error{font-size:12px;color:#a66f51}.evidence-list{max-height:370px;overflow:auto}.evidence{padding:13px 0;border-bottom:1px solid #e5e5d8}.evidence.is-active{border-left:2px solid #779061;padding-left:12px;background:#edf0e4}.evidence-head{display:flex;gap:7px;align-items:center;font-size:12px}.evidence-head strong{flex:1;font-weight:500;line-height:1.6}.evidence-index{color:#778c62}.evidence-snippet{font-size:12px;line-height:1.85;color:#838d74;margin:10px 0}.evidence-source{font-size:11px;color:#a0a78f;line-height:1.8;overflow-wrap:anywhere}.evidence-source a{color:#607c4f;margin-left:7px}.preset-group{margin-bottom:12px}.preset-label{font-size:12px;color:#a2a68f}.preset{display:block;text-align:left;width:100%;background:none;border:0;border-bottom:1px solid #e6e6d9;color:#728264;line-height:1.8;font-size:12px;padding:9px 0;cursor:pointer}.preset:hover{color:#355838}.preset:disabled{opacity:.4}button:focus-visible,summary:focus-visible{outline:2px solid #839771;outline-offset:3px}button:disabled{cursor:default;opacity:.5}
@keyframes pulse{50%{opacity:.3}}@media(prefers-reduced-motion:reduce){.progress-dot{animation:none}}@media(max-width:1100px){.chat-layout{grid-template-columns:minmax(0,1fr) 265px;gap:20px}.agent-chat{padding:22px}.stream{padding:22px}.composer{grid-template-columns:100px minmax(0,1fr) auto}}@media(max-width:850px){.chat-layout{grid-template-columns:1fr}.chat-aside{display:none}.header-side{display:none}.agent-chat{padding:16px}.stream{height:calc(100vh - 320px)}.chat-header{padding-bottom:18px}}
</style>
