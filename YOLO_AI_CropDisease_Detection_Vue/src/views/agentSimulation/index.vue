<template>
	<div class="plan-page">
		<!-- ============ 左：农情录入（三通道） ============ -->
		<section class="panel entry-panel">
			<header class="panel-head">
				<h3>农情录入</h3>
				<el-button link type="primary" @click="loadHistory">刷新历史</el-button>
			</header>

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
									/>
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
				<p class="draft-title">
					{{ draft.fields.length }} 个字段已填好，请核对后确认
				</p>
				<ul class="draft-list">
					<li v-for="item in draft.fields" :key="item.key">
						<span class="draft-label">{{ item.label }}</span>
						<span class="draft-value">{{ item.value }}{{ item.unit ? ' ' + item.unit : '' }}</span>
						<span v-if="item.rawText" class="draft-evidence">依据：「{{ item.rawText }}」</span>
						<span v-else class="draft-evidence muted">来自表单/表格</span>
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
				/>
				<div class="demand-row">
					<span class="demand-label">基线天数</span>
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

			<div class="answer-banner">模型推演，非实测、非知识库依据，请结合当地实际复核</div>

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
				录入农情后点「开始推演」。推演会先算出机理模型参考基线，再让模型结合你的棚况给方案。
			</div>

			<!-- 结果区：基线对照 + 结构化方案 -->
			<div v-if="final" class="results">
				<div class="baseline-card">
					<h4>机理模型参考基线</h4>
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
					<p class="recompute">
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
						<p class="group-title">动作清单（可勾选）</p>
						<el-checkbox-group v-model="checkedActions">
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
import { computed, onMounted, reactive, ref } from 'vue';
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
const days = ref(120);
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

const startDeduction = async () => {
	const situation = collectSituation();
	if (!Object.keys(situation).length && !question.value.trim()) {
		ElMessage.warning('至少填一项农情，或写一句诉求');
		return;
	}
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
			{ situation, question: question.value.trim(), days: days.value },
			{
				onStage: (_phase, message) => {
					stageMessage.value = message;
				},
				onBaseline: () => {
					stageMessage.value = '参考基线已算出，模型推演中…';
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
	try {
		fields.value = await fetchSituationFields();
		// 默认展开前两组（基本情况、环境读数）——它们决定推演的最小可用输入。
		expandedGroups.value = groupedFields.value.slice(0, 2).map((g) => g.name);
	} catch {
		ElMessage.error('农情字段定义加载失败，请确认后端已启动');
	}
	loadHistory();
});
</script>

<style scoped>
.plan-page {
	display: grid;
	grid-template-columns: 400px 1fr;
	gap: 12px;
	padding: 12px;
	height: calc(100vh - 90px);
	box-sizing: border-box;
}

.panel {
	background: #fff;
	border-radius: 6px;
	padding: 12px 14px;
	overflow-y: auto;
	box-shadow: 0 1px 4px rgb(0 0 0 / 6%);
}

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
	color: #2f5d33;
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
	background: #f7faf7;
	border-radius: 4px;
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
	color: #3a7d44;
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
	border-top: 1px solid #eee;
	background: #fff;
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
	color: #aaa;
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
	background: #fafcfa;
	border-radius: 6px;
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
	background: #fff;
	border-radius: 4px;
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
	color: #2f5d33;
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
	color: #2f5d33;
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
</style>
