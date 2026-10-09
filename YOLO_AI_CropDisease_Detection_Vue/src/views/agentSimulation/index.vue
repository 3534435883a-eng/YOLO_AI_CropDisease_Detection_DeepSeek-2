<template>
	<div class="plan-page">
		<header class="plan-header">
			<div class="plan-title"><p class="eyebrow">{{ linkedMode ? 'CONNECTED GREENHOUSE / FARM PLAN' : 'FARM PLAN' }}</p><h1>农事规划推演</h1><p>沿用当前农情任务、识别证据和大棚状态，结合人工补充或 CSV 农情生成方案。执行与复查分别记录。</p></div>
			<div class="flow-rail" aria-label="规划流程">
				<div class="flow-step" :class="{ 'is-current': !draft && !running && !final, 'is-ready': !!draft || !!final }"><span>01</span><b>输入农情</b></div><i></i>
				<div class="flow-step" :class="{ 'is-current': !!draft && !running && !final, 'is-ready': running || !!final }"><span>02</span><b>核对内容</b></div><i></i>
				<div class="flow-step" :class="{ 'is-current': running, 'is-ready': !!final }"><span>03</span><b>模型推演</b></div><i></i>
				<div class="flow-step" :class="{ 'is-current': !!final }"><span>04</span><b>查看方案</b></div>
			</div>
		</header>
		<GreenhouseContext class="plan-context" />
		<!-- ============ 左：农情录入（三通道） ============ -->
		<section class="panel entry-panel">
			<header class="panel-head">
				<div><p class="section-kicker">01 / INPUT</p><h3>农情录入</h3></div>
				<el-button link type="primary" @click="loadHistory">刷新历史</el-button>
			</header>
			<p class="input-source-note">输入来源：人工填写、文本解析或 CSV 文件。解析与导入内容均为待核对农情，不是传感器实测。</p>
			<p v-for="(note, index) in restoredSourceNotes" :key="index" class="restored-source-note">{{ note }}</p>

			<el-tabs v-model="entryTab" stretch>
				<el-tab-pane label="表单填写" name="form">
					<!-- 分组折叠：22 个字段平铺时，用户只会看到滚动区里露出的那几个，
					     并以为那就全部了。分组让"还能填什么"一眼可见。 -->
					<el-collapse v-model="expandedGroups" class="entry-groups">
						<el-collapse-item v-for="g in groupedFields" :key="g.name" :name="g.name">
							<template #title>
								<span class="group-title">{{ g.name }}</span>
								<span class="group-count">{{ filledCount(g) }}/{{ g.items.length }}</span>
							</template>
							<el-form label-position="top" size="small">
								<el-form-item v-for="f in g.items" :key="f.key" :label="fieldLabel(f)">
									<el-input
										v-model="formValues[f.key]"
										:placeholder="f.unit ? `单位 ${f.unit}` : ''"
										:class="{ 'is-missing': isMissing(f.key) }"
										clearable
										@update:model-value="markFieldEdited(f.key)"
									/>
									<span v-if="fieldSources[f.key]" class="field-source">{{ fieldSources[f.key].manualAdjusted ? '人工调整；原始来源：' : '沿用任务 · ' }}{{ fieldSources[f.key].evidenceSource }} · {{ sourceLabel(fieldSources[f.key].fieldSource) }}</span>
								</el-form-item>
							</el-form>
						</el-collapse-item>
					</el-collapse>
				</el-tab-pane>

				<el-tab-pane label="上传 CSV" name="csv">
					<el-upload
						drag
						:show-file-list="false"
						:auto-upload="false"
						accept=".csv,.txt"
						:on-change="onCsvPicked"
					>
						<div class="upload-hint">
							<p>把 CSV 拖到这里，或点击选择文件</p>
							<p class="sub">支持 UTF-8 / GBK 编码，分隔符自动识别</p>
						</div>
					</el-upload>
					<p class="hint">
						表头认不出的列会被列出来，不会按列位置猜。
						<el-button link type="primary" @click="downloadTemplate">下载列名模板</el-button>
					</p>
				</el-tab-pane>

				<el-tab-pane label="一句话描述" name="nl">
					<el-input
						v-model="nlText"
						type="textarea"
						:rows="6"
						placeholder="例：我这棚番茄定植两个月了，白天棚温 27 度左右，湿度 82%，下位叶开始发黄，最近阴天多。"
						@update:model-value="nlEdited = true"
					/>
					<el-button
						type="primary"
						size="small"
						:loading="parsing"
						class="nl-button"
						@click="runParse"
					>
						解析成农情字段
					</el-button>
					<p class="hint">
						只抽取你明确说过的字段。<strong>没说的不会替你补</strong>，
						相对时间（"上个月"）也不会换算成日期。
					</p>
				</el-tab-pane>
			</el-tabs>

			<!-- 回显确认：抽取/上传的结果逐字段展示依据 -->
			<div v-if="draft" class="draft">
				<p class="draft-step">02 / 核对解析草稿</p>
				<p class="draft-title">
					{{ draft.fields.length }} 个字段已填好，请核对后确认
				</p>
				<ul class="draft-list">
					<li v-for="item in draft.fields" :key="item.key">
						<span class="draft-label">{{ item.label }}</span>
						<span class="draft-value">{{ item.value }}{{ item.unit ? ' ' + item.unit : '' }}</span>
						<span v-if="item.rawText" class="draft-evidence">依据：「{{ item.rawText }}」</span>
						<span v-else class="draft-evidence muted">来自表单/表格</span>
						<span class="draft-evidence muted">来源：{{ sourceLabel(item.source) }}</span>
					</li>
				</ul>
				<el-alert
					v-for="(note, i) in draft.notes"
					:key="'n' + i"
					:title="note"
					type="info"
					:closable="false"
					show-icon
					class="draft-note"
				/>
				<el-alert
					v-for="(conflict, i) in draft.conflicts"
					:key="'c' + i"
					:title="conflict"
					type="warning"
					:closable="false"
					show-icon
					class="draft-note"
				/>
				<el-alert
					v-if="draft.unrecognizedColumns && draft.unrecognizedColumns.length"
					:title="`未识别的列（这些数据没有进入推演）：${draft.unrecognizedColumns.join('、')}`"
					type="warning"
					:closable="false"
					show-icon
					class="draft-note"
				/>
			</div>

			<div class="demand">
				<el-input
					v-model="question"
					type="textarea"
					:rows="2"
					placeholder="你这次想解决什么？（可空）例：接下来两周我该怎么管？"
					@update:model-value="questionEdited = true"
				/>
				<div class="demand-row">
					<span class="demand-label">{{ linkedMode ? '规划天数' : '基线天数' }}</span>
					<el-input-number v-model="days" :min="1" :max="365" size="small" />
					<el-button link type="primary" @click="startDeduction" :loading="running">
						{{ running ? '推演中…' : '开始推演' }}
					</el-button>
					<el-button v-if="running" link type="danger" @click="cancelDeduction">中止</el-button>
				</div>
			</div>

			<div class="history">
				<p class="history-title">最近推演</p>
				<ul>
					<li v-for="item in history" :key="item.id" @click="exportRecord(item.id)">
						<span class="history-id">#{{ item.id }}</span>
						<span class="history-q">{{ item.question || '(无诉求)' }}</span>
						<span class="history-meta">{{ item.days }}天 · {{ item.streamMode }}</span>
					</li>
					<li v-if="!history.length" class="history-empty">暂无记录</li>
				</ul>
			</div>
		</section>

		<!-- ============ 右：推演与结果 ============ -->
		<section class="panel main-panel">
			<header class="result-heading"><div><p class="section-kicker">03–04 / DEDUCTION & REVIEW</p><h3>方案推演与结果</h3></div><span class="channel-chip">{{ linkedMode ? '当前农情方案' : '独立农情规划' }}</span></header>
			<div v-if="fatal" class="fatal">{{ fatal }}</div>

			<div class="stage-bar">
				<el-tag v-if="running" type="primary" effect="plain">{{ stageMessage || '准备中…' }}</el-tag>
				<el-tag v-else-if="final" type="success" effect="plain">推演完成 · {{ elapsedText }}</el-tag>
				<el-tag v-else type="info" effect="plain">尚未开始</el-tag>
				<el-tag v-if="final && final.streamMode !== 'STREAM'" type="warning" effect="dark">
					降级：{{ final.fallbackReason }}
				</el-tag>
				<el-tag v-if="recordId" type="success" effect="plain" @click="exportRecord(recordId)">
					导出报告 #{{ recordId }}
				</el-tag>
			</div>

            <div class="answer-banner">{{ linkedMode ? '结合当前大棚与农情记录，安排管理措施和复查事项。' : '模型推演，非实测、非知识库依据，请结合当地实际复核' }}</div>

			<div v-if="reasoning" class="reasoning">
				<p class="reasoning-head" @click="showReasoning = !showReasoning">
					推演思路{{ showReasoning ? '（点击收起）' : '（点击展开）' }}
				</p>
				<pre v-if="showReasoning" class="reasoning-body">{{ reasoning }}</pre>
			</div>

			<div v-if="content" class="answer">
				<template v-for="(block, index) in blocks" :key="index">
					<h4 v-if="block.type === 'h'" class="md-h">{{ block.text }}</h4>
					<pre v-else-if="block.type === 'code'" class="md-code">{{ block.text }}</pre>
					<table v-else-if="block.type === 'table'" class="md-table">
						<thead>
							<tr>
								<th v-for="(cell, ci) in block.head" :key="ci">{{ cell }}</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="(row, ri) in block.rows" :key="ri">
								<td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td>
							</tr>
						</tbody>
					</table>
					<p v-else-if="block.type === 'li'" class="md-li">
						<span class="dot">·</span>
						<span>
							<template v-for="(seg, si) in block.segments" :key="si">
								<strong v-if="seg.bold">{{ seg.text }}</strong>
								<span v-else>{{ seg.text }}</span>
							</template>
						</span>
					</p>
					<p v-else class="md-p">
						<template v-for="(seg, si) in block.segments" :key="si">
							<strong v-if="seg.bold">{{ seg.text }}</strong>
							<span v-else>{{ seg.text }}</span>
						</template>
					</p>
				</template>
			</div>
			<div v-else-if="!running" class="empty">
				{{ linkedMode ? '核对已带入的农情后点「开始推演」，结合本任务证据与当前大棚状态，生成行动清单和复查安排。' : '录入农情后点「开始推演」。推演会先算出机理模型参考基线，再让模型结合你的棚况给方案。' }}
			</div>

			<!-- 结果区：基线对照 + 结构化方案 -->
			<div v-if="final" class="results">
				<div class="baseline-card">
					<h4>{{ final.baseline.available ? '机理模型参考基线' : '本次规划依据' }}</h4>
					<p class="scope">{{ final.baseline.scopeNote }}</p>
					<div v-if="final.baseline.available" class="metrics">
						<div class="metric">
							<span class="k">推荐策略</span>
							<span class="v">{{ final.baseline.strategyLabel }}</span>
						</div>
						<div class="metric">
							<span class="k">商品产量</span>
							<span class="v">{{ fmt(final.baseline.marketableYieldKg, 1) }} kg</span>
						</div>
						<div class="metric">
							<span class="k">折合单产</span>
							<span class="v">{{ fmt(final.baseline.yieldPerSquareMeter, 2) }} kg/m²</span>
						</div>
						<div class="metric" :class="{ negative: (final.baseline.profitYuan || 0) < 0 }">
							<span class="k">利润</span>
							<span class="v">{{ fmt(final.baseline.profitYuan, 0) }} 元</span>
						</div>
					</div>
					<p v-else class="scope">{{ final.baseline.unavailableReason }}</p>
					<!-- 窗口不足的解释必须紧贴那个负利润数字，否则它会被读成经营亏损 -->
					<el-alert
						v-if="final.baseline.windowNote"
						:title="final.baseline.windowNote"
						type="warning"
						:closable="false"
						show-icon
						class="window-note"
					/>
					<p v-if="final.baseline.available" class="recompute">
						批次 {{ final.baseline.batchId }} · 种子 {{ final.baseline.seed }} ·
						{{ final.baseline.days }} 天，可逐值复算
					</p>
				</div>

				<div class="plan-card">
					<h4>可执行方案</h4>
					<p v-if="structured && structured.conclusion" class="conclusion">
						{{ structured.conclusion }}
					</p>
					<template v-if="structured && structured.actions && structured.actions.length">
						<p class="group-title">人工行动清单（完成后登记）</p>
						<el-checkbox-group v-model="checkedActions" :disabled="actionSaving || running" @change="saveActionChecks">
							<div v-for="(a, i) in structured.actions" :key="i" class="action">
								<el-checkbox :label="i">
									<span class="action-main">
										<el-tag size="small" effect="plain">{{ a.stage }}</el-tag>
										{{ a.action }}
									</span>
								</el-checkbox>
								<p v-if="a.trigger || a.detail" class="action-detail">
									<span v-if="a.trigger">触发：{{ a.trigger }}</span>
									<span v-if="a.detail">做到：{{ a.detail }}</span>
								</p>
							</div>
						</el-checkbox-group>
					</template>

					<template v-if="structured && structured.schedule && structured.schedule.length">
						<p class="group-title">农事日程</p>
						<ul class="schedule">
							<li v-for="(s, i) in structured.schedule" :key="i">
								<span class="day">D+{{ s.dayOffset }}</span>
								<span class="task">{{ s.task }}</span>
							</li>
						</ul>
					</template>

					<template v-if="structured && structured.riskAlerts && structured.riskAlerts.length">
						<p class="group-title">风险提示</p>
						<ul class="risks">
							<li v-for="(r, i) in structured.riskAlerts" :key="i">
								<strong>{{ r.risk }}</strong>
								<span v-if="r.window">（{{ r.window }}）</span>
								<p v-if="r.mitigation" class="mitigation">{{ r.mitigation }}</p>
							</li>
						</ul>
					</template>

					<template v-if="structured && structured.cautions && structured.cautions.length">
						<p class="group-title">注意事项</p>
						<ul class="cautions">
							<li v-for="(c, i) in structured.cautions" :key="i">{{ c }}</li>
						</ul>
					</template>

					<p v-if="!structured" class="no-structured">
						模型本次没有返回结构化清单（正文里仍有内容），已按原文展示。
					</p>
				</div>
			</div>
		</section>
	</div>
</template>

<script setup lang="ts" name="agentSimulation">
import { HORTI_M3_PROFILE } from '/@/views/digitalTwin/hortiM3Profile';
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue';
import {useRoute} from 'vue-router';
import {useGreenhouseStore} from '/@/stores/greenhouse';
import GreenhouseContext from '/@/components/GreenhouseContext.vue';
import {restoreTaskSituation, type TaskFieldSource} from '/@/utils/agent/taskSituation';
const route=useRoute(),greenhouse=useGreenhouseStore();
const linkedMode=computed(()=>!!greenhouse.activeRunId);
let pageActive=true;
const unsubscribe=greenhouse.subscribe();onBeforeUnmount(()=>{pageActive=false;unsubscribe();controller?.abort();});
const planRequestId=ref('');

import { ElMessage } from 'element-plus';
import {
	fetchPlanHistory,
	fetchSituationFields,
	parseSituation,
	planExportUrl,
	planTemplateUrl,
	streamPlanDeduction,
	uploadSituation,
	type PlanFinalPayload,
	type PlanHistoryItem,
	type PlanStructured,
	type SituationDraft,
	type SituationFieldSpec,
} from '/@/api/agent/plan';

/* ------------------------------ 字段与表单 ------------------------------ */

const fields = ref<SituationFieldSpec[]>([]);
const formValues = reactive<Record<string, unknown>>({});
const editedFields = new Set<string>();
const manuallyEditedFields = new Set<string>();
const fieldSources = reactive<Record<string, TaskFieldSource>>({});
const restoredSourceNotes = ref<string[]>([]);
const questionEdited=ref(false),nlEdited=ref(false);
const markFieldEdited=(key:string)=>{editedFields.add(key);manuallyEditedFields.add(key);if(fieldSources[key])fieldSources[key].manualAdjusted=true;};
const sourceLabel=(source:string)=>({USER:'用户填写',PARSED:'文本解析',USER_EDITED:'人工调整'} as Record<string,string>)[source]||source;
const entryTab = ref('form');
const nlText = ref('');
const parsing = ref(false);
const draft = ref<SituationDraft | null>(null);

const fieldLabel = (f: SituationFieldSpec) => (f.unit ? `${f.label}（${f.unit}）` : f.label);
const isMissing = (key: string) => {
	const value = formValues[key];
	return value === undefined || value === null || String(value).trim() === '';
};

/** 按登记表顺序分组；分组名与顺序都由后端 `/fields` 决定，前端不硬编码。 */
const groupedFields = computed(() => {
	const groups: { name: string; items: SituationFieldSpec[] }[] = [];
	for (const f of fields.value) {
		let group = groups.find((g) => g.name === f.group);
		if (!group) {
			group = { name: f.group, items: [] };
			groups.push(group);
		}
		group.items.push(f);
	}
	return groups;
});

// 默认展开前两组：基本情况与环境读数是必填主体，其余默认收起。
const expandedGroups = ref<string[]>([]);

const filledCount = (group: { items: SituationFieldSpec[] }) =>
	group.items.filter((f) => !isMissing(f.key)).length;

/** 把草稿里的值回填进表单，并把它的字段清单展示出来供核对。 */
const applyDraft = (result: SituationDraft) => {
	draft.value = result;
	for (const item of result.fields) {
		formValues[item.key] = item.value === null || item.value === undefined ? '' : item.value;
		editedFields.add(item.key);manuallyEditedFields.delete(item.key);delete fieldSources[item.key];
	}
};

const runParse = async () => {
	if (!nlText.value.trim()) {
		ElMessage.warning('先写一段农情描述');
		return;
	}
	parsing.value = true;
	try {
		applyDraft(await parseSituation(nlText.value));
		entryTab.value = 'form';
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : '解析失败');
	} finally {
		parsing.value = false;
	}
};

const onCsvPicked = async (uploadFile: { raw?: File }) => {
	const file = uploadFile.raw;
	if (!file) return;
	parsing.value = true;
	try {
		applyDraft(await uploadSituation(file));
		entryTab.value = 'form';
		ElMessage.success(`已读取 ${file.name}`);
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : 'CSV 解析失败');
	} finally {
		parsing.value = false;
	}
};

const downloadTemplate = () => {
	window.open(planTemplateUrl(), '_blank');
};

/* ------------------------------ 推演 ------------------------------ */

const question = ref('');
const days = ref<number>(HORTI_M3_PROFILE.days);
const running = ref(false);
const fatal = ref('');
const stageMessage = ref('');
const content = ref('');
const reasoning = ref('');
const showReasoning = ref(false);
const final = ref<PlanFinalPayload | null>(null);
const structured = computed<PlanStructured | null>(() => final.value?.structured ?? null);
const recordId = ref<number | null>(null);
const checkedActions = ref<number[]>([]);
let controller: AbortController | null = null;

const actionSaving=ref(false);
async function saveActionChecks(values:number[]){
 if(actionSaving.value)return;actionSaving.value=true;
 try{
  await greenhouse.refreshTask();
  for(let index=0;index<(structured.value?.actions?.length||0);index++){
   const id=planRequestId.value+':action:'+index;
   const action=greenhouse.task?.actions.find(a=>a.id===id);
   if(!action)throw new Error('行动记录尚未保存，请稍后重试');
   const status=values.includes(index)?'DONE':'PENDING';
   if(action.status!==status)await greenhouse.setActionStatus(id,status);
  }
 }catch(e){
  checkedActions.value=(structured.value?.actions||[]).map((_,i)=>i).filter(i=>greenhouse.task?.actions.some(a=>a.id===planRequestId.value+':action:'+i&&['DONE','REVIEWED'].includes(a.status)));
  ElMessage.error(e instanceof Error?e.message:'行动状态保存失败');
 }finally{actionSaving.value=false;}
}
const elapsedText = computed(() => {
	const ms = final.value?.elapsedMillis ?? 0;
	return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`;
});

const collectSituation = (): Record<string, unknown> => {
	const payload: Record<string, unknown> = {};
	for (const f of fields.value) {
		const value = formValues[f.key];
		if (value === undefined || value === null || String(value).trim() === '') continue;
		payload[f.key] = value;
	}
	return payload;
};

const collectFieldEvidence=(situation:Record<string,unknown>)=>fields.value.filter(field=>Object.prototype.hasOwnProperty.call(situation,field.key)).map(field=>{
 const original=draft.value?.fields.find(value=>value.key===field.key);
 return {key:field.key,label:field.label,unit:field.unit,value:situation[field.key],source:manuallyEditedFields.has(field.key)?'USER_EDITED':fieldSources[field.key]?.fieldSource||original?.source||'USER',rawText:original?.rawText||null};
});

const startDeduction = async () => {
	const situation = collectSituation();
	if (!Object.keys(situation).length && !question.value.trim()) {
		ElMessage.warning('至少填一项农情，或写一句诉求');
		return;
	}
    let taskId:string;
    planRequestId.value=crypto.randomUUID();
    try{
        taskId=(await greenhouse.ensureTask(question.value.trim())).id;
        if(Object.keys(situation).length)await greenhouse.addEvidence({id:planRequestId.value+':input',type:'AGRI_INPUT',label:'本次已核对的农情输入',source:entryTab.value==='nl'?'用户文本解析 / 已核对，非传感器实测':'用户表单或CSV / 已核对，非传感器实测',details:{situation,fields:collectFieldEvidence(situation),fieldProvenance:{...fieldSources},missing:draft.value?.missing||[],sensorMeasured:false}});
    }catch(e){ElMessage.error(e instanceof Error?e.message:'农情任务保存失败');return;}
    running.value = true;
	fatal.value = '';
	content.value = '';
	reasoning.value = '';
	final.value = null;
	recordId.value = null;
	checkedActions.value = [];
	stageMessage.value = '';
	controller = new AbortController();

	try {
		await streamPlanDeduction(
			{ situation, question: question.value.trim(), days: days.value, taskId, requestId:planRequestId.value, simulationRunId:greenhouse.activeRunId||undefined },
			{
                onWarning:message=>ElMessage.warning(message),
				onStage: (_phase, message) => {
					stageMessage.value = message;
				},
				onBaseline: (baseline) => {
					stageMessage.value = baseline.available ? '参考基线已算出，模型推演中…' : linkedMode.value ? '已读取关联任务与大棚，方案推演中…' : '参考基线不可用，正结合所填农情规划…';
				},
				onDelta: (text, isReasoning) => {
					if (isReasoning) reasoning.value += text;
					else content.value += text;
				},
				// 截断重试时后端会先发 reset：不处理的话新旧正文会被拼在一起。
				onReset: () => {
					content.value = '';
					reasoning.value = '';
				},
				onFinal: (payload) => {
					final.value = payload;
					// 终态正文以 final 为准（可能经过重试），流式累积的只是过程展示。
					content.value = payload.markdown;
				},
				onRecord: (id) => {
					recordId.value = Number.isFinite(id) ? id : null;
					loadHistory();
				},
				onFatal: (message) => {
					fatal.value = message;
				},
				onClosed: () => {
                    void greenhouse.refreshTask().catch(e=>ElMessage.error(String(e)));
					running.value = false;
					stageMessage.value = '';
				},
			},
			controller.signal
		);
	} finally {
		running.value = false;
		controller = null;
	}
};

const cancelDeduction = () => {
	// 中止会同时关闭后端的上游连接（后端把断开识别为取消），不是只停前端渲染。
	controller?.abort();
	ElMessage.info('已中止推演');
};

/* ------------------------------ 历史与导出 ------------------------------ */

const history = ref<PlanHistoryItem[]>([]);

const loadHistory = async () => {
	try {
		history.value = await fetchPlanHistory(10);
	} catch {
		// 历史是旁路，取不到不影响主流程
		history.value = [];
	}
};

const exportRecord = (id: number) => {
	window.open(planExportUrl(id), '_blank');
};

/* ------------------------------ 受限 Markdown 渲染 ------------------------------ */

/**
 * 只渲染提示词要求模型输出的那个子集：`##` 标题、段落、`-` 列表、`**粗体**`、```代码块```。
 *
 * **刻意不用 `v-html`**（决策助手页也避开了它）：推演正文是模型生成的文本，
 * 直接塞进 `v-html` 等于把 XSS 面交给模型的输出。这里解析成结构再逐段插值，
 * 未知语法原样当文本显示。
 */
type Segment = { text: string; bold: boolean };
type Block =
	| { type: 'h' | 'li' | 'code'; text: string; segments?: Segment[] }
	| { type: 'p'; text: string; segments: Segment[] }
	| { type: 'table'; head: string[]; rows: string[][] };

const blocks = computed<Block[]>(() => parseMarkdown(content.value));

function parseMarkdown(text: string): Block[] {
	const result: Block[] = [];
	let inCode = false;
	let codeBuffer: string[] = [];
	// 表格缓冲区：连续的 `|...|` 行合成一个表格。
	let tableLines: string[] = [];

	const flushTable = () => {
		if (!tableLines.length) return;
		const cells = tableLines
			.filter((l) => !/^\|[\s:|-]+\|$/.test(l))
			.map((l) =>
				l
					.replace(/^\|/, '')
					.replace(/\|$/, '')
					.split('|')
					.map((c) => c.trim())
			);
		tableLines = [];
		if (!cells.length) return;
		// 第一行是表头；分隔行（|---|---|）已在上面滤掉。行宽不齐时按最长行补齐。
		const width = cells.reduce((max, row) => Math.max(max, row.length), 0);
		const pad = (row: string[]) => {
			const copy = row.slice();
			while (copy.length < width) copy.push('');
			return copy;
		};
		const [head, ...body] = cells.map(pad);
		// 表格内不再做 `**粗体**` 解析：单元格里同时出现竖线与标记时，
		// 继续拆分会把本来就难对齐的行宽弄乱，而表格的读法本来就是整格读。
		result.push({ type: 'table', head, rows: body });
	};

	for (const rawLine of text.split(/\r?\n/)) {
		const line = rawLine.trimEnd();
		if (line.trim().startsWith('```')) {
			flushTable();
			if (inCode) {
				result.push({ type: 'code', text: codeBuffer.join('\n') });
				codeBuffer = [];
			}
			inCode = !inCode;
			continue;
		}
		if (inCode) {
			codeBuffer.push(line);
			continue;
		}
		const trimmed = line.trim();
		if (trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.length > 1) {
			tableLines.push(trimmed);
			continue;
		}
		flushTable();
		if (!trimmed) continue;
		if (trimmed.startsWith('#')) {
			result.push({ type: 'h', text: trimmed.replace(/^#+\s*/, '') });
			continue;
		}
		if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
			result.push({ type: 'li', text: trimmed.slice(2), segments: toSegments(trimmed.slice(2)) });
			continue;
		}
		result.push({ type: 'p', text: trimmed, segments: toSegments(trimmed) });
	}
	flushTable();
	if (inCode && codeBuffer.length) {
		result.push({ type: 'code', text: codeBuffer.join('\n') });
	}
	return result;
}

/** `**粗体**` 切成片段；其它标记（如表格、链接）原样当文本，不猜语法。 */
function toSegments(text: string): Segment[] {
	const segments: Segment[] = [];
	let rest = text;
	for (;;) {
		const open = rest.indexOf('**');
		if (open < 0) break;
		const close = rest.indexOf('**', open + 2);
		if (close < 0) break;
		if (open > 0) segments.push({ text: rest.slice(0, open), bold: false });
		segments.push({ text: rest.slice(open + 2, close), bold: true });
		rest = rest.slice(close + 2);
	}
	if (rest) segments.push({ text: rest, bold: false });
	return segments.length ? segments : [{ text, bold: false }];
}

const fmt = (value: number | undefined, digits: number) => {
	if (value === undefined || value === null || !Number.isFinite(value)) return '—';
	return value.toLocaleString('zh-CN', {
		minimumFractionDigits: digits,
		maximumFractionDigits: digits,
	});
};

onMounted(async () => {
 const results=await Promise.allSettled([
  (async()=>{
   const definitions=await fetchSituationFields();if(!pageActive)return;
   fields.value=definitions;expandedGroups.value=groupedFields.value.slice(0,2).map(group=>group.name);
  })(),
  (async()=>{
   if(typeof route.query.liveRun==='string')await greenhouse.attachRun(route.query.liveRun);
   if(typeof route.query.taskId==='string')return greenhouse.attachTask(route.query.taskId);
   return greenhouse.refreshTask();
  })(),
 ] as const);
 if(!pageActive)return;
 if(results[0].status==='rejected')ElMessage.error('农情字段定义加载失败，请确认后端已启动');
 if(results[1].status==='rejected')ElMessage.error(String(results[1].reason));
 const saved=results[1].status==='fulfilled'?results[1].value:null;
 if(saved&&greenhouse.task?.id===saved.id){
  if(!questionEdited.value&&!question.value.trim())question.value=saved.question||'';
  if(!nlEdited.value&&!nlText.value.trim())nlText.value=saved.question||'';
  const restored=restoreTaskSituation(saved.evidence,fields.value,formValues,editedFields);
  Object.assign(formValues,restored.patch);Object.assign(fieldSources,restored.sources);
  restoredSourceNotes.value=restored.sourceNotes;
  if(restored.draft.fields.length&&!draft.value)draft.value=restored.draft;
 }
	loadHistory();
});
</script>

<style scoped>
.plan-page {
	display: grid;
	grid-template-columns: minmax(320px, 390px) minmax(0, 1fr);
	grid-template-rows: auto auto minmax(0, 1fr);
	gap: 14px;
	padding: 20px 24px 24px;
	height: calc(100vh - 90px);
	box-sizing: border-box;
}
.plan-context{grid-column:1 / -1;margin:0!important}.plan-header { grid-column:1 / -1; display:flex; align-items:flex-end; justify-content:space-between; gap:24px; padding:2px 0 15px; border-bottom:1px solid #e3d8c9; }
.plan-title .eyebrow,.section-kicker { margin:0 0 5px; color:#8a7665; font-size:10px; font-weight:700; letter-spacing:.12em; }
.plan-title h1 { margin:0; color:#382b25; font:600 27px/1.25 Georgia,'Songti SC',serif; }
.plan-title p:last-child { margin:6px 0 0; color:#756b62; font-size:13px; line-height:1.6; }
.flow-rail { display:flex; align-items:center; gap:10px; color:#887b70; white-space:nowrap; }
.flow-rail > i { width:24px; border-top:1px solid #dfd2c3; }
.flow-step { display:flex; align-items:center; gap:6px; font-size:11px; }
.flow-step span { display:grid; width:22px; height:22px; place-items:center; border:1px solid #dfd2c3; border-radius:50%; color:#897869; font-size:10px; }
.flow-step.is-current span { border-color:#98553d; background:#98553d; color:#fffaf4; }
.flow-step.is-ready span { border-color:#818a53; background:#818a53; color:white; }
.flow-step.is-current b { color:#754530; }

.panel {
	background: #fffdf9;
	border:1px solid #e5dbcf;
	border-radius: 3px;
	padding: 16px;
	overflow-y: auto;
	box-shadow: none;
}
.panel-head h3,.result-heading h3 { margin:0; color:#49372c; font-size:16px; }
.panel-head,.result-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; padding-bottom:13px; margin-bottom:8px; border-bottom:1px solid #eee5da; }
.section-kicker { margin-bottom:4px; }
.channel-chip { padding:5px 8px; border:1px solid #ead9c9; background:#f8efe5; color:#84533a; font-size:11px; }
.input-source-note { margin:0 0 12px; padding:9px 10px; border-left:2px solid #a56b4d; background:#f8f4ed; color:#75675b; font-size:11px; line-height:1.55; }
.restored-source-note{margin:0 0 9px;color:#58744e;font-size:11px;line-height:1.65}.field-source{display:block;width:100%;margin-top:3px;color:#849078;font-size:10px;line-height:1.4}
.draft-step { margin:0 0 7px; color:#8a674e; font-size:10px; font-weight:700; letter-spacing:.1em; }

.panel-head {
	display: flex;
	align-items: center;
	justify-content: space-between;
}

.panel-head h3 {
	margin: 0;
	font-size: 15px;
}

.entry-groups {
	border-top: none;
}

.entry-groups :deep(.el-collapse-item__header) {
	height: 34px;
	line-height: 34px;
	font-size: 13px;
}

.entry-groups :deep(.el-collapse-item__content) {
	padding-bottom: 4px;
}

.group-title {
	font-weight: 600;
	color: #76523a;
}

.group-count {
	margin-left: 8px;
	font-size: 11px;
	color: #999;
}

.entry-form :deep(.is-missing .el-input__wrapper) {
	background: #fffbf5;
	box-shadow: 0 0 0 1px #e6b980 inset;
}

.hint {
	font-size: 12px;
	color: #8a8a8a;
	line-height: 1.6;
}

.hint strong {
	color: #c0392b;
}

.upload-hint {
	padding: 18px 8px;
	color: #666;
}

.upload-hint .sub {
	font-size: 12px;
	color: #999;
}

.nl-button {
	margin-top: 8px;
}

.draft {
	margin-top: 10px;
	padding: 10px;
	background: #f8f4ed;
	border:1px solid #ebe1d5;
	border-radius: 3px;
}

.draft-title {
	margin: 0 0 6px;
	font-size: 13px;
	font-weight: 600;
}

.draft-list {
	margin: 0;
	padding: 0;
	list-style: none;
	font-size: 12px;
}

.draft-list li {
	padding: 3px 0;
	border-bottom: 1px dashed #e4ece4;
}

.draft-label {
	display: inline-block;
	width: 110px;
	color: #666;
}

.draft-value {
	font-weight: 600;
}

.draft-evidence {
	margin-left: 8px;
	color: #6e7b4b;
}

.draft-evidence.muted {
	color: #aaa;
}

.draft-note {
	margin-top: 6px;
}

/**
 * 诉求与「开始推演」固定在面板底部。
 * 分组展开后表单会很长，按钮若跟着滚走，用户填完最后一个字段还得回头找它。
 */
.demand {
	position: sticky;
	bottom: -12px;
	z-index: 2;
	margin-top: 12px;
	padding: 10px 0 12px;
	border-top: 1px solid #e9dfd4;
	background: #fffdf9;
}

.demand-row {
	display: flex;
	align-items: center;
	gap: 8px;
	margin-top: 6px;
}

.demand-label {
	font-size: 12px;
	color: #888;
}

.history {
	margin-top: 14px;
	padding-top: 10px;
	border-top: 1px solid #eee;
}

.history-title {
	margin: 0 0 6px;
	font-size: 13px;
	font-weight: 600;
}

.history ul {
	margin: 0;
	padding: 0;
	list-style: none;
}

.history li {
	display: flex;
	gap: 6px;
	align-items: baseline;
	padding: 5px 6px;
	border-radius: 4px;
	cursor: pointer;
	font-size: 12px;
}

.history li:hover {
	background: #f3f7f3;
}

.history-id {
	color: #3a7d44;
	font-weight: 600;
}

.history-q {
	flex: 1;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.history-meta {
	color: #aaa;
}

.history-empty {
	color: #bbb;
	cursor: default;
}

.fatal {
	margin-bottom: 10px;
	padding: 8px 10px;
	background: #fdecea;
	border-left: 3px solid #d9534f;
	font-size: 13px;
}

.stage-bar {
	display: flex;
	gap: 8px;
	align-items: center;
	flex-wrap: wrap;
	margin-bottom: 10px;
}

.answer-banner {
	padding: 6px 10px;
	background: #fff8e6;
	border-left: 3px solid #c68a25;
	font-size: 12px;
	color: #7a5a12;
	margin-bottom: 10px;
}

.reasoning {
	margin-bottom: 10px;
	border: 1px dashed #dfe6df;
	border-radius: 4px;
	padding: 6px 10px;
	background: #fafcf9;
}

.reasoning-head {
	margin: 0;
	font-size: 12px;
	color: #7a9a7a;
	cursor: pointer;
}

.reasoning-body {
	margin: 6px 0 0;
	font-size: 12px;
	color: #777;
	white-space: pre-wrap;
	word-break: break-word;
	max-height: 220px;
	overflow-y: auto;
	font-family: inherit;
}

.answer {
	font-size: 14px;
	line-height: 1.75;
}

.md-h {
	margin: 16px 0 6px;
	font-size: 15px;
	color: #2f5d33;
	border-left: 3px solid #79a87d;
	padding-left: 8px;
}

.md-p {
	margin: 6px 0;
	white-space: pre-wrap;
}

.md-li {
	margin: 3px 0 3px 6px;
	display: flex;
	gap: 6px;
}

.md-li .dot {
	color: #79a87d;
}

.md-code {
	background: #f6f8f6;
	padding: 8px;
	border-radius: 4px;
	font-size: 12px;
	overflow-x: auto;
}

/* 模型在"风险提示"这类清单里经常用表格；不渲染的话会退化成一串竖线文本。 */
.md-table {
	width: 100%;
	border-collapse: collapse;
	margin: 8px 0;
	font-size: 12.5px;
}

.md-table th,
.md-table td {
	border: 1px solid #e2eae2;
	padding: 6px 8px;
	text-align: left;
	vertical-align: top;
	line-height: 1.6;
}

.md-table th {
	background: #f2f7f2;
	color: #2f5d33;
	font-weight: 600;
	white-space: nowrap;
}

.empty {
	color: #8b8075;
	font-size: 13px;
	padding: 40px 0;
	text-align: center;
}

.results {
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 12px;
	margin-top: 18px;
	padding-top: 14px;
	border-top: 1px solid #eee;
}

.baseline-card,
.plan-card {
	background: #f8f4ed;
	border:1px solid #ebe1d5;
	border-radius: 3px;
	padding: 12px;
}

.baseline-card h4,
.plan-card h4 {
	margin: 0 0 8px;
	font-size: 14px;
}

.scope {
	font-size: 12px;
	color: #8a6d3b;
	margin: 0 0 8px;
}

.metrics {
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 8px;
}

.metric {
	background: #fffdf9;
	border:1px solid #eee5da;
	border-radius: 2px;
	padding: 8px;
}

.metric .k {
	display: block;
	font-size: 11px;
	color: #999;
}

.metric .v {
	font-size: 15px;
	font-weight: 600;
	color: #687448;
}

.metric.negative .v {
	color: #c0392b;
}

.window-note {
	margin-top: 8px;
}

.recompute {
	margin: 8px 0 0;
	font-size: 11px;
	color: #aaa;
}

.conclusion {
	font-weight: 600;
	color: #687448;
	margin: 0 0 8px;
}

.group-title {
	margin: 10px 0 4px;
	font-size: 12px;
	color: #888;
	font-weight: 600;
}

/**
 * `line-height` 必须显式写。
 * Element Plus 的 `.el-checkbox-group` 设了 `line-height: 0`（用于消除复选框之间的行内空白），
 * 组内元素会继承它——表现为每一行高度塌成 0、文字溢出重叠到下一行上。
 * 实测截图里正是如此。组件自己的布局不该依赖继承来的行高。
 */
.action {
	margin-bottom: 8px;
	line-height: 1.6;
}

.action-main {
	font-size: 13px;
}

.action-detail {
	margin: 2px 0 0 24px;
	font-size: 12px;
	line-height: 1.6;
	color: #888;
	display: flex;
	flex-wrap: wrap;
	gap: 4px 10px;
}

.schedule,
.risks,
.cautions {
	margin: 0;
	padding-left: 18px;
	font-size: 12px;
	color: #555;
}

.schedule li {
	display: flex;
	gap: 8px;
}

.schedule .day {
	color: #3a7d44;
	font-weight: 600;
	min-width: 44px;
}

.mitigation {
	margin: 2px 0 4px;
	color: #888;
}

.no-structured {
	font-size: 12px;
	color: #b8860b;
	margin: 8px 0 0;
}
.stage-bar { margin:10px 0 12px; }
.answer-banner { border-left-color:#a56b4d; background:#f8efe5; color:#76533e; line-height:1.6; }
.reasoning { border-color:#e6dccf; border-radius:3px; background:#fbf8f1; }
.reasoning-head { color:#8a674e; }
.md-h { color:#584333; border-left-color:#a15d40; }
.md-li .dot { color:#8b925e; }
.md-code { border:1px solid #e8dfd3; border-radius:2px; background:#f7f3eb; }
.md-table th,.md-table td { border-color:#e6dccf; }
.md-table th { background:#f4eee4; color:#604a39; }
.results { border-color:#e9dfd4; }
.scope { color:#8a674e; }
.metric .k,.recompute { color:#8b8075; }
.metric .v { color:#687448; }
.schedule .day { color:#747f4d; }
@media(max-width:1120px) { .plan-page { grid-template-columns:minmax(280px, 340px) minmax(0,1fr); padding:16px; }.flow-rail { gap:6px; }.flow-rail > i { width:12px; } }
@media(max-width:850px) { .plan-page { display:flex; flex-direction:column; height:auto; min-height:calc(100vh - 90px); }.plan-header { align-items:flex-start; flex-direction:column; }.flow-rail { width:100%; justify-content:space-between; white-space:normal; }.flow-rail > i { flex:1; }.panel { max-height:none; }.results { grid-template-columns:1fr; } }
</style>
