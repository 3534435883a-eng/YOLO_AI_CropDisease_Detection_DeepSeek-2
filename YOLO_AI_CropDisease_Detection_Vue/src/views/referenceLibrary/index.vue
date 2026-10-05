<template>
	<div class="reference-library">
		<header class="page-heading">
			<div class="heading-copy">
				<p class="eyebrow">FIELD REFERENCE / READING ROOM</p>
				<h1>文献与来源</h1>
				<p>查阅仓库本地存档，并核对知识库登记来源的版本与出处。</p>
			</div>
			<div class="heading-summary" aria-label="资料数量">
				<div class="summary-item">
					<span>本地 PDF</span>
					<strong>{{ library?.available ? library.count : '—' }}</strong>
				</div>
				<div class="summary-divider"></div>
				<div class="summary-item">
					<span>登记来源</span>
					<strong>{{ sourcesLoaded ? sources.length : '—' }}</strong>
				</div>
			</div>
		</header>

        <section class="m3-reference" aria-label="模型模拟参考数据集">
            <div><p class="eyebrow">SIMULATION REFERENCE / HORTI-M3</p><h2>番茄多模态试验数据</h2><p>2023–2025 环境、表型与农事记录。平台默认采用 2025 / 广辉201 / CK 参数参考场景，56 天窗口、半小时步长。</p><small>已适配公开场地与栽培规模；原始数据尚未导入，当前为未校准模拟。</small></div>
            <div class="m3-reference-links"><a href="https://www.nature.com/articles/s41597-026-07074-w" target="_blank" rel="noopener noreferrer">研究论文 ↗</a><a href="https://doi.org/10.5281/zenodo.17217565" target="_blank" rel="noopener noreferrer">公开数据 ↗</a><router-link to="/digitalTwin">查看 M3 推演 →</router-link></div>
        </section>

		<section class="library-panel">
			<el-tabs v-model="activeTab" class="library-tabs">
				<el-tab-pane name="local">
					<template #label>
						<span class="tab-label">本地文献</span>
						<span class="tab-count">{{ library?.available ? library.count : '—' }}</span>
					</template>

					<div class="panel-intro">
						<div>
							<h2>项目本地文献</h2>
							<p>从仓库的“农业论文”目录读取；打开时才读取对应 PDF 原文件。</p>
						</div>
						<el-tag effect="plain" type="info">本地存档 · 不代表已入知识库</el-tag>
					</div>

					<div class="filter-bar">
						<el-input v-model="documentSearch" clearable placeholder="搜索论文或标准名称" class="search-input">
							<template #prefix><el-icon><Search /></el-icon></template>
						</el-input>
						<el-radio-group v-model="categoryFilter" size="small" class="category-filter">
							<el-radio-button label="全部资料">全部资料 {{ library?.available ? library.count : '—' }}</el-radio-button>
							<el-radio-button label="研究论文">研究论文 {{ library?.available ? countCategory('研究论文') : '—' }}</el-radio-button>
							<el-radio-button label="设施番茄标准">设施番茄标准 {{ library?.available ? countCategory('设施番茄标准') : '—' }}</el-radio-button>
							<el-radio-button label="其他作物标准">其他作物标准 {{ library?.available ? countCategory('其他作物标准') : '—' }}</el-radio-button>
						</el-radio-group>
						<el-button :loading="localLoading" text @click="loadDocuments">刷新</el-button>
					</div>

					<div v-if="localError" class="state-panel state-error">
						<div><strong>本地文献目录暂不可用</strong><p>{{ localError }}</p></div>
						<el-button type="primary" plain @click="loadDocuments">重试</el-button>
					</div>
					<div v-else-if="library && !library.available" class="state-panel">
						<div><strong>没有读到本地文献目录</strong><p>请确认项目本地“农业论文”目录可访问后再刷新。</p></div>
						<el-button type="primary" plain @click="loadDocuments">重新读取</el-button>
					</div>
					<el-table
						v-else
						:data="filteredDocuments"
						v-loading="localLoading"
						max-height="560"
						class="reference-table"
						:empty-text="localLoading ? '正在读取本地文献…' : '没有符合条件的文献'"
					>
						<el-table-column label="文件 / 标题" min-width="420" show-overflow-tooltip>
							<template #default="{ row }">
								<div class="file-cell">
									<strong>{{ row.fileName }}</strong>
									<small>{{ row.folder }}</small>
								</div>
							</template>
						</el-table-column>
						<el-table-column label="资料类别" width="170">
							<template #default="{ row }">
								<el-tag size="small" effect="plain" :class="categoryClass(row.category)">{{ row.category }}</el-tag>
							</template>
						</el-table-column>
						<el-table-column label="文件大小" width="135" align="right">
							<template #default="{ row }">{{ formatFileSize(row.sizeBytes) }}</template>
						</el-table-column>
						<el-table-column label="阅读" width="135" fixed="right" align="center">
							<template #default="{ row }">
								<el-button text type="primary" :loading="openingDocumentId === row.id" @click="openDocument(row)">打开 PDF</el-button>
							</template>
						</el-table-column>
					</el-table>
				</el-tab-pane>

				<el-tab-pane name="sources">
					<template #label>
						<span class="tab-label">知识库来源</span>
						<span class="tab-count">{{ sourcesLoaded ? sources.length : '—' }}</span>
					</template>

					<div class="panel-intro">
						<div>
							<h2>已登记的引用来源</h2>
							<p>查看知识库已登记的来源名称、版本与出处。</p>
						</div>
						<el-tag effect="plain" type="info">来源登记目录</el-tag>
					</div>

					<div class="source-notice">
						<strong>来源边界</strong>
						<span>这里展示来源登记元数据，不代表来源已出现在某次回答或当前索引一定可检索；决策助手本轮实际采用的依据请看对话中的“本轮证据”。本地 PDF 也不会因为出现在上方目录就自动进入知识库。</span>
					</div>

					<div class="filter-bar source-filter-bar">
						<el-input v-model="sourceSearch" clearable placeholder="搜索来源名称、类型或版本" class="search-input">
							<template #prefix><el-icon><Search /></el-icon></template>
						</el-input>
						<span class="source-count">{{ sourcesLoaded ? `${filteredSources.length} / ${sources.length} 条来源` : '读取来源目录中' }}</span>
						<el-button :loading="sourceLoading" text @click="loadSources">刷新</el-button>
					</div>

					<div v-if="sourceError" class="state-panel state-error">
						<div><strong>知识库来源暂不可用</strong><p>{{ sourceError }}</p></div>
						<el-button type="primary" plain @click="loadSources">重试</el-button>
					</div>
					<el-table
						v-else
						:data="filteredSources"
						v-loading="sourceLoading"
						max-height="560"
						class="reference-table source-table"
						row-key="sourceCode"
						:empty-text="sourceLoading ? '正在读取来源目录…' : '没有符合条件的来源'"
					>
						<el-table-column type="expand" width="42">
							<template #default="{ row }">
								<div class="source-evidence">
									<template v-if="row.reviewedSummaries?.length">
										<h3>已核对的人工摘要 · {{ row.summaryCount }} 条</h3>
										<p class="evidence-boundary">保留论文页码、实验条件与适用边界，供核对原文；此清单不代表本轮助手实际采用的证据，也不是论文全文索引。</p>
										<dl class="summary-list">
											<div v-for="summary in row.reviewedSummaries" :key="summary.id" class="summary-item">
												<dt>{{ summary.topic }}</dt><dd>{{ summary.text }}</dd>
											</div>
										</dl>
										<p v-if="row.localDocument" class="fingerprint-note">核对日期 {{ row.localDocument.reviewedAt }} · 本地原件打开时核验 SHA-256<br /><code>{{ row.localDocument.sha256 }}</code></p>
									</template>
									<p v-else class="evidence-boundary">该来源尚未登记与本地 PDF 关联的人工摘要，请通过原文链接核对出处。</p>
								</div>
							</template>
						</el-table-column>
						<el-table-column prop="sourceName" label="来源名称" min-width="285" show-overflow-tooltip>
							<template #default="{ row }">
								<div class="file-cell">
									<strong>{{ row.sourceName || '来源未命名' }}</strong>
									<small>{{ row.sourceCode || '未登记代码' }}</small>
									<small v-if="row.summaryCount">{{ row.summaryCount }} 条人工摘要 · 展开查看</small>
								</div>
							</template>
						</el-table-column>
						<el-table-column label="类型" width="125" show-overflow-tooltip>
							<template #default="{ row }">{{ row.sourceType || '—' }}</template>
						</el-table-column>
						<el-table-column label="权威等级" width="125">
							<template #default="{ row }">
								<el-tag size="small" effect="plain" :type="authorityTag(row.authorityLevel)">{{ authorityLabel(row.authorityLevel) }}</el-tag>
							</template>
						</el-table-column>
						<el-table-column label="版本" min-width="175" show-overflow-tooltip>
							<template #default="{ row }">{{ row.version || '—' }}</template>
						</el-table-column>
						<el-table-column prop="licenseNote" label="许可说明" min-width="250" show-overflow-tooltip>
							<template #default="{ row }">{{ row.licenseNote || '—' }}</template>
						</el-table-column>
						<el-table-column label="原文" width="135" fixed="right" align="center">
							<template #default="{ row }">
								<div class="source-actions">
									<el-button v-if="row.localDocument?.available" text size="small" :loading="openingDocumentId === row.localDocument.id" @click="openSourceDocument(row)">本地原文</el-button>
									<span v-else-if="row.localDocument" class="muted local-file-status">本地原件不可用</span>
								<a v-if="safeSourceUrl(row.url)" :href="safeSourceUrl(row.url)" target="_blank" rel="noopener noreferrer" class="source-link">查看来源 ↗</a>
								<span v-else-if="!row.localDocument" class="muted">—</span>
								</div>
							</template>
						</el-table-column>
					</el-table>
				</el-tab-pane>
			</el-tabs>
		</section>
	</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import {
	getKnowledgeSources,
	getLocalKnowledgeLibrary,
	getLocalPdfBlob,
	getSourcePdfBlob,
	KnowledgeSourceItem,
	LocalKnowledgeDocument,
	LocalKnowledgeLibrary,
} from '/@/api/knowledge';

const activeTab = ref('local');
const library = ref<LocalKnowledgeLibrary | null>(null);
const sources = ref<KnowledgeSourceItem[]>([]);
const sourcesLoaded = ref(false);
const localLoading = ref(false);
const sourceLoading = ref(false);
const localError = ref('');
const sourceError = ref('');
const documentSearch = ref('');
const sourceSearch = ref('');
const categoryFilter = ref('全部资料');
const openingDocumentId = ref('');
const documents = computed(() => library.value?.documents || []);

const filteredDocuments = computed(() => {
	const query = documentSearch.value.trim().toLocaleLowerCase();
	return documents.value.filter((document) => {
		const categoryMatches = categoryFilter.value === '全部资料' || document.category === categoryFilter.value;
		const queryMatches = !query || `${document.fileName} ${document.folder} ${document.category}`.toLocaleLowerCase().includes(query);
		return categoryMatches && queryMatches;
	});
});

const filteredSources = computed(() => {
	const query = sourceSearch.value.trim().toLocaleLowerCase();
	if (!query) return sources.value;
	return sources.value.filter((source) =>
		[source.sourceName, source.sourceCode, source.sourceType, source.version, source.licenseNote]
			.some((value) => String(value || '').toLocaleLowerCase().includes(query))
	);
});

function countCategory(category: string): number {
	return documents.value.filter((document) => document.category === category).length;
}

function categoryClass(category: string): string {
	if (category === '研究论文') return 'tag-paper';
	if (category === '设施番茄标准') return 'tag-tomato-standard';
	return 'tag-other-standard';
}

function formatFileSize(bytes: number): string {
	if (!Number.isFinite(bytes) || bytes < 0) return '—';
	if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
	return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function authorityLabel(level?: number): string {
	if (!level || level < 1 || level > 5) return '未标等级';
	return `层级 ${String.fromCharCode(64 + level)}`;
}

function authorityTag(level?: number): 'success' | 'warning' | 'info' {
	if (level === 1) return 'success';
	if (level === 2) return 'warning';
	return 'info';
}

function safeSourceUrl(value?: string | null): string {
	if (!value) return '';
	try {
		const raw = value.trim();
		if (!raw) return '';
		const hasUrlScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw);
		if (hasUrlScheme && !/^https?:\/\//i.test(raw)) return '';
		const candidate = raw.startsWith('//') ? `https:${raw}` : hasUrlScheme ? raw : `https://${raw}`;
		const parsed = new URL(candidate);
		return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : '';
	} catch {
		return '';
	}
}

async function loadDocuments(): Promise<void> {
	localLoading.value = true;
	localError.value = '';
	try {
		library.value = await getLocalKnowledgeLibrary();
	} catch {
		localError.value = '读取失败。请确认本地文献目录可访问，并检查后端服务状态。';
	} finally {
		localLoading.value = false;
	}
}

async function loadSources(): Promise<void> {
	sourceLoading.value = true;
	sourceError.value = '';
	try {
		sources.value = await getKnowledgeSources();
		sourcesLoaded.value = true;
	} catch {
		sourceError.value = '读取失败。请检查知识库服务状态后重试。';
	} finally {
		sourceLoading.value = false;
	}
}

function trackPdfTab(viewer: Window, url: string): void {
	const poll = window.setInterval(() => {
		if (viewer.closed) {
			window.clearInterval(poll);
			URL.revokeObjectURL(url);
		}
	}, 1500);
}

async function openDocument(document: LocalKnowledgeDocument): Promise<void> {
	return openPdf(document.id, () => getLocalPdfBlob(document.id), 'PDF 暂时无法打开，请刷新文献目录后重试。');
}

async function openSourceDocument(source: KnowledgeSourceItem): Promise<void> {
	if (!source.localDocument?.available || !source.sourceCode) return;
	return openPdf(source.localDocument.id, () => getSourcePdfBlob(source.sourceCode!),
		'本地原件无法核验或读取，可能已被移动或替换，请核对存档文件后刷新。');
}

async function openPdf(documentId: string, load: () => Promise<Blob>, errorMessage: string): Promise<void> {
	const viewer = window.open('about:blank', '_blank');
	if (!viewer) {
		ElMessage.warning('浏览器拦截了新标签页，请允许此站点打开 PDF。');
		return;
	}
	viewer.opener = null;
	openingDocumentId.value = documentId;
	let objectUrl = '';
	try {
		const blob = await load();
		objectUrl = URL.createObjectURL(blob);
		viewer.location.href = objectUrl;
		trackPdfTab(viewer, objectUrl);
	} catch {
		viewer.close();
		if (objectUrl) URL.revokeObjectURL(objectUrl);
		ElMessage.error(errorMessage);
	} finally {
		openingDocumentId.value = '';
	}
}

onMounted(() => {
	void loadDocuments();
	void loadSources();
});
</script>

<style scoped lang="scss">
.reference-library {
	min-height: calc(100vh - 60px);
	padding: 26px 30px 112px;
	background: #f7f3eb;
	color: #382b25;
}

.page-heading {
	display: flex;
	align-items: flex-end;
	justify-content: space-between;
	gap: 28px;
	margin: 0 auto 22px;
	max-width: 1600px;
}

.eyebrow {
	margin: 0 0 7px;
	color: #897665;
	font-size: 10px;
	font-weight: 700;
	letter-spacing: .14em;
}

.heading-copy h1 {
	margin: 0;
	color: #382b25;
	font: 600 28px/1.2 Georgia, 'Songti SC', serif;
}

.heading-copy > p:last-child {
	margin: 8px 0 0;
	color: #776c62;
	font-size: 13px;
	line-height: 1.65;
}

.heading-summary {
	display: flex;
	align-items: center;
	gap: 22px;
	min-width: 245px;
	padding: 12px 18px;
	border: 1px solid #e7dbcc;
	background: #fbf9f4;
}

.summary-item { display: grid; gap: 3px; }
.summary-item span { color: #8b7c6e; font-size: 11px; }
.summary-item strong { color: #493a30; font: 600 20px/1.1 Georgia, 'Songti SC', serif; }
.summary-divider { width: 1px; height: 30px; background: #e7dbcc; }

.library-panel {
	max-width: 1600px;
	margin: 0 auto;
	padding: 8px 22px 24px;
	border: 1px solid #e7dbcc;
	background: #fcfaf6;
}

.library-tabs :deep(.el-tabs__header) { margin: 0 0 18px; }
.library-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: #eadfD2; }
.library-tabs :deep(.el-tabs__item) { height: 52px; color: #75695f; font-size: 13px; }
.library-tabs :deep(.el-tabs__item.is-active) { color: #a3533b; }
.library-tabs :deep(.el-tabs__active-bar) { background: #a3533b; }
.tab-label { vertical-align: middle; }
.tab-count { margin-left: 7px; color: #958475; font-size: 11px; }

.panel-intro {
	display: flex;
	align-items: flex-end;
	justify-content: space-between;
	gap: 20px;
	margin-bottom: 16px;
}

.panel-intro h2 { margin: 0; color: #493a30; font: 600 19px/1.3 Georgia, 'Songti SC', serif; }
.panel-intro p { margin: 5px 0 0; color: #807469; font-size: 12px; line-height: 1.55; }

.filter-bar {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 16px 0 13px;
}

.search-input { width: 290px; flex: 0 0 290px; }
.category-filter { flex: 1; min-width: 0; }
.category-filter :deep(.el-radio-button__inner) { padding: 8px 11px; color: #74675b; border-color: #e5d8c9; box-shadow: none; }
.category-filter :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { color: #fffaf4; border-color: #8b5b44; background: #8b5b44; box-shadow: -1px 0 0 0 #8b5b44; }
.category-filter :deep(.el-radio-button:first-child .el-radio-button__inner) { border-radius: 2px 0 0 2px; }
.category-filter :deep(.el-radio-button:last-child .el-radio-button__inner) { border-radius: 0 2px 2px 0; }

.reference-table { width: 100%; border-top: 1px solid #e9dfd4; }
.reference-table :deep(th.el-table__cell) { height: 42px; color: #75695f; background: #f4efe7; font-size: 11px; font-weight: 650; }
.reference-table :deep(td.el-table__cell) { padding: 10px 0; color: #51463e; border-bottom-color: #eee7df; font-size: 12px; }
.reference-table :deep(.el-table__inner-wrapper::before) { display: none; }
.file-cell { display: grid; gap: 4px; min-width: 0; }
.file-cell strong { overflow: hidden; color: #473a31; font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.file-cell small { overflow: hidden; color: #998b7e; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.tag-paper { color: #486344 !important; border-color: #c8d3bf !important; background: #f1f5ed !important; }
.tag-tomato-standard { color: #8d513d !important; border-color: #e2c8b9 !important; background: #faf0e9 !important; }
.tag-other-standard { color: #6c6258 !important; border-color: #d9d0c6 !important; background: #f4f0eb !important; }
.source-link { color: #a3533b; font-size: 11px; text-decoration: none; white-space: nowrap; }
.source-link:hover { color: #7c3d2a; text-decoration: underline; }
.muted { color: #a2988d; }

.source-notice {
	display: flex;
	align-items: flex-start;
	gap: 12px;
	padding: 11px 14px;
	border-left: 3px solid #87956d;
	background: #f2f4ec;
	color: #5e6652;
	font-size: 12px;
	line-height: 1.65;
}
.source-notice strong { flex: 0 0 auto; color: #526045; font-weight: 650; }
.source-notice span { max-width: 1100px; }
.source-filter-bar { justify-content: flex-start; }
.source-count { flex: 1; color: #8f8377; font-size: 11px; text-align: right; }
.source-table :deep(.el-table__cell) { vertical-align: top; }
.source-actions { display: grid; justify-items: center; gap: 5px; }
.source-actions :deep(.el-button) { color: #486344; }
.local-file-status { font-size: 11px; }
.source-evidence { padding: 20px 28px 24px; max-width: 1150px; }
.source-evidence h3 { margin: 0 0 8px; color: #493a30; font-size: 14px; }
.evidence-boundary { color: #807469; font-size: 12px; line-height: 1.7; }
.summary-list { margin: 18px 0; }
.summary-item + .summary-item { margin-top: 18px; }
.summary-item dt { color: #486344; font-size: 13px; font-weight: 600; }
.summary-item dd { margin: 6px 0 0; color: #51463e; font-size: 12px; line-height: 1.85; }
.fingerprint-note { color: #807469; font-size: 11px; line-height: 1.8; overflow-wrap: anywhere; }

.state-panel {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18px;
	min-height: 118px;
	padding: 22px 24px;
	border: 1px dashed #ded2c3;
	background: #f8f5ef;
}
.state-panel strong { color: #4a3a30; font-size: 14px; }
.state-panel p { margin: 6px 0 0; color: #87796d; font-size: 12px; }
.state-error { border-color: #dec7b6; background: #fbf4ef; }
.state-error strong { color: #8e4e39; }

@media (max-width: 1100px) {
	.reference-library { padding-right: 18px; padding-left: 18px; }
	.filter-bar { flex-wrap: wrap; }
	.search-input { width: min(100%, 360px); flex-basis: 290px; }
	.category-filter { order: 3; flex-basis: 100%; overflow-x: auto; }
	.category-filter :deep(.el-radio-group) { white-space: nowrap; }
}

@media (max-width: 700px) {
	.reference-library { padding: 18px 12px 90px; }
	.page-heading { align-items: flex-start; flex-direction: column; gap: 14px; }
	.heading-summary { width: 100%; box-sizing: border-box; }
	.library-panel { padding: 5px 12px 16px; }
	.panel-intro { align-items: flex-start; flex-direction: column; }
	.filter-bar { align-items: stretch; }
	.search-input { width: 100%; flex: 1 1 100%; }
	.category-filter { display: flex; flex-basis: 100%; width: 100%; }
	.category-filter :deep(.el-radio-group) { display: flex; width: max-content; }
	.source-notice { flex-direction: column; gap: 3px; }
	.source-count { text-align: left; }
}

.m3-reference { display:flex; justify-content:space-between; align-items:center; gap:28px; padding:22px 26px; margin-bottom:22px; border:1px solid #d1d8c4; background:#edf0e5; border-radius:12px; }
.m3-reference h2 { margin:7px 0; color:#394b31; font-size:20px; }
.m3-reference p:not(.eyebrow) { margin:0 0 7px; color:#617054; font-size:13px; line-height:1.7; }
.m3-reference small { color:#817154; }
.m3-reference-links { display:flex; flex-direction:column; gap:10px; flex-shrink:0; font-size:13px; }
.m3-reference-links a { color:#4e663e; }
@media(max-width:1000px) { .m3-reference { align-items:flex-start; flex-direction:column; }.m3-reference-links { flex-direction:row; flex-wrap:wrap; gap:18px; } }
</style>
