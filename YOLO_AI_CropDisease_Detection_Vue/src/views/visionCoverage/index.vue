<template>
	<div class="vision-coverage">
		<header class="page-header">
			<div>
				<p class="eyebrow">VISION → KNOWLEDGE COVERAGE</p>
				<h2>视觉—知识覆盖</h2>
				<p class="header-copy">
					检测模型能认出哪些类别、知识库能解释多少，全部列出并标注依据。
					<span class="strong">没有可核对依据的类别一律标为“暂无对应条目”，不用推测填充。</span>
				</p>
			</div>
			<div class="header-side">
				<el-button link type="primary" @click="goChat">去 AI 决策对话 →</el-button>
				<el-button link type="primary" :loading="loading" @click="load">刷新</el-button>
			</div>
		</header>

		<section class="overview-grid">
			<article v-for="card in summaryCards" :key="card.code" class="metric-card">
				<div class="metric-label"><span>{{ card.label }}</span><el-tag size="small" effect="plain">{{ card.tag }}</el-tag></div>
				<strong>{{ card.value }}</strong>
				<p>{{ card.description }}</p>
			</article>
		</section>

		<section class="panel">
			<div class="panel-heading">
				<div><p class="eyebrow">BY CROP</p><h3>逐作物覆盖</h3></div>
				<el-tag type="info" size="small" effect="plain">灰色段为暂无对应条目</el-tag>
			</div>
			<div class="crop-list">
				<div v-for="row in cropRows" :key="row.crop" class="crop-row">
					<span class="crop-name">{{ row.crop }}</span>
					<div class="crop-bar">
						<div class="bar-covered" :style="{ width: row.coveredPercent + '%' }"></div>
					</div>
					<span class="crop-stat">
						可检出 {{ row.detectable }} · 可解释 <b>{{ row.explainable }}</b> · 暂无对应 <b class="gap">{{ row.unexplained }}</b>
					</span>
				</div>
			</div>
		</section>

		<section class="panel">
			<div class="panel-heading">
				<div><p class="eyebrow">CLASS DETAIL</p><h3>逐类别明细</h3></div>
				<div class="filters">
					<el-radio-group v-model="filter" size="small">
						<el-radio-button label="gap">仅暂无对应（{{ gapCount }}）</el-radio-button>
						<el-radio-button label="covered">仅可解释（{{ coveredCount }}）</el-radio-button>
						<el-radio-button label="all">全部（{{ items.length }}）</el-radio-button>
					</el-radio-group>
				</div>
			</div>
			<el-table :data="filteredItems" size="small" max-height="520" class="detail-table">
				<el-table-column prop="cropType" label="作物" width="80" />
				<el-table-column prop="classLabel" label="模型类别" min-width="200" />
				<el-table-column label="知识库条目" min-width="160">
					<template #default="{ row }">
						<span v-if="row.kbDiseaseName" class="covered-text">{{ row.kbDiseaseName }}</span>
						<span v-else-if="row.healthy" class="muted">健康类</span>
						<span v-else class="gap-text">暂无对应条目</span>
					</template>
				</el-table-column>
				<el-table-column label="依据" width="130">
					<template #default="{ row }">
						<el-tag :type="ruleTagType(row.matchRule)" size="small" effect="plain">
							{{ ruleLabel(row.matchRule) }}
						</el-tag>
					</template>
				</el-table-column>
				<el-table-column label="证据" min-width="320">
					<template #default="{ row }">
						<span class="evidence-text">{{ row.evidence }}</span>
						<a v-if="row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener" class="source-link">来源</a>
					</template>
				</el-table-column>
			</el-table>
		</section>

		<section class="panel notes-panel">
			<div class="panel-heading"><div><p class="eyebrow">DISCIPLINE</p><h3>判定规则与来源说明</h3></div></div>
			<ul class="notes">
				<li><b>名称完全一致 / 去作物前缀一致 / 名称包含</b>：类别中文名与知识库条目名在名称层面可直接复核。</li>
				<li><b>知识库原文枚举</b>：知识库原文把该类别列为某病害的一种（如“稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟…”）。</li>
				<li><b>外部权威来源</b>：由可引用的公开权威记录确认（如番茄壳针孢 <i>Septoria lycopersici</i> ↔ 番茄斑枯病）。</li>
				<li><b>暂无对应条目</b>：知识库中找不到可核对的依据。<span class="strong">此时智能体会明确回答“没有可核对的对应条目”，不会推测病名。</span></li>
				<li>曾用“类别名出现在原文里”作为依据，7 条候选错 5 条（如原文其实是“<i>不同于</i>…枯萎病”），该规则已废弃。</li>
				<li>核验过程、被否决的候选与理由见仓库内 <code>docs/vision-class-kb-mapping.md</code>。</li>
			</ul>
		</section>
	</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getVisionClassMap, MATCH_RULE_LABELS, VisionClassMapItem, VisionMapSummary } from '/@/api/knowledge';

const router = useRouter();
const loading = ref(false);
const items = ref<VisionClassMapItem[]>([]);
const summary = ref<VisionMapSummary>({});
const filter = ref<'gap' | 'covered' | 'all'>('gap');

const load = async () => {
	loading.value = true;
	try {
		const data = await getVisionClassMap();
		items.value = data?.items || [];
		summary.value = data?.summary || {};
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : '视觉—知识覆盖数据加载失败');
	} finally {
		loading.value = false;
	}
};

/** 健康类不算“可解释/不可解释”，与前端口径一致：只看需要知识库支撑的类别。 */
const judged = computed(() => items.value.filter((item) => !item.healthy));
const coveredCount = computed(() => judged.value.filter((item) => item.explainable).length);
const gapCount = computed(() => judged.value.length - coveredCount.value);
const coveragePercent = computed(() =>
	judged.value.length === 0 ? 0 : Math.round((coveredCount.value / judged.value.length) * 100)
);

const summaryCards = computed(() => [
	{ code: 'detectable', label: '模型可检出类别', value: String(judged.value.length), tag: '9 个作物模型', description: '来自项目自训练的 9 个检测模型（排除健康类）' },
	{ code: 'covered', label: '知识库可解释', value: String(coveredCount.value), tag: '有依据', description: '每条都附名称/原文/外部来源层面的可核对依据' },
	{ code: 'gap', label: '暂无对应条目', value: String(gapCount.value), tag: '如实标注', description: '知识库中找不到可核对依据，检测结果暂无法被解释' },
	{ code: 'rate', label: '可解释率', value: coveragePercent.value + '%', tag: '覆盖缺口', description: '补齐需要可回溯的出处，不生成无来源内容' },
]);

const cropRows = computed(() => {
	const detectable = summary.value.detectableCrops || {};
	const explainable = summary.value.explainable || {};
	const unexplained = summary.value.unexplained || {};
	return Object.keys(detectable)
		.map((crop) => {
			const total = detectable[crop] || 0;
			const covered = explainable[crop] || 0;
			return {
				crop,
				detectable: total,
				explainable: covered,
				unexplained: unexplained[crop] || total - covered,
				coveredPercent: total === 0 ? 0 : Math.round((covered / total) * 100),
			};
		})
		.sort((left, right) => right.detectable - left.detectable);
});

const filteredItems = computed(() => {
	if (filter.value === 'gap') return judged.value.filter((item) => !item.explainable);
	if (filter.value === 'covered') return judged.value.filter((item) => item.explainable);
	return items.value;
});

const ruleLabel = (rule?: string) => MATCH_RULE_LABELS[rule || ''] || rule || '-';
const ruleTagType = (rule?: string) => {
	if (rule === 'NONE') return 'info';
	if (rule === 'HEALTHY') return 'info';
	if (rule === 'EXTERNAL') return 'warning';
	return 'success';
};

const goChat = () => router.push('/agentChat');

onMounted(load);
</script>

<style scoped lang="scss">
.vision-coverage {
	min-height: calc(100vh - 60px);
	// 底部留白：避让右下角既有全局浮窗（"番茄智能体"状态卡），否则会压住表格最后几行。
	padding: 24px 24px 112px;
	background: #f3f7f2;
	color: #1c2b22;
}
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; padding: 4px 0 20px; border-bottom: 1px solid #d7e3d9; }
.eyebrow { margin: 0 0 6px; color: #708277; font-size: 11px; font-weight: 650; }
h2, h3 { margin: 0; } h2 { font-size: 27px; } h3 { font-size: 17px; }
.header-copy { max-width: 820px; margin: 7px 0 0; color: #617168; font-size: 13px; line-height: 1.7; }
.header-side { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.strong { color: #223428; font-weight: 650; }
.overview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 16px 0 14px; }
.panel { padding: 17px; margin-bottom: 14px; border: 1px solid #dce8df; border-radius: 8px; background: #fff; }
.metric-card { min-height: 128px; padding: 15px; border: 1px solid #dce8df; border-radius: 8px; background: #fff; }
.metric-label { display: flex; justify-content: space-between; align-items: center; gap: 8px; color: #627369; font-size: 13px; }
.metric-card strong { display: block; margin: 20px 0 6px; font-size: 25px; font-weight: 650; }
.metric-card p { margin: 0; color: #809087; font-size: 12px; line-height: 1.6; }
.panel-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.crop-list { display: grid; gap: 10px; }
.crop-row { display: grid; grid-template-columns: 64px 1fr 300px; align-items: center; gap: 12px; }
.crop-name { font-size: 13px; font-weight: 650; }
.crop-bar { height: 10px; border-radius: 5px; background: #eef2ef; overflow: hidden; }
.bar-covered { height: 100%; background: #2d8a54; }
.crop-stat { color: #617168; font-size: 12px; }
.crop-stat b { color: #223428; } .crop-stat b.gap { color: #b07316; }
.detail-table { width: 100%; }
.covered-text { color: #2d8a54; font-weight: 600; }
.gap-text { color: #b07316; font-weight: 600; }
.muted { color: #84928a; }
.evidence-text { color: #5c6d63; font-size: 12px; line-height: 1.6; }
.source-link { margin-left: 6px; color: #2d8a54; font-size: 12px; }
.notes { margin: 0; padding-left: 18px; color: #5c6d63; font-size: 13px; line-height: 1.85; }
.notes code { padding: 1px 5px; border-radius: 4px; background: #eef3ef; color: #3d4f44; font-size: 12px; }
@media (max-width: 1180px) {
	.overview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.crop-row { grid-template-columns: 64px 1fr; }
	.crop-stat { grid-column: 2; }
}
@media (max-width: 680px) {
	.vision-coverage { padding: 14px; }
	.page-header { flex-direction: column; }
	.header-side { align-items: flex-start; }
	.overview-grid { grid-template-columns: 1fr; }
}
</style>