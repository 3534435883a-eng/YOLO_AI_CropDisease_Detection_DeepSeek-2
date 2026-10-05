<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<header class="manage-heading">
				<div class="manage-heading__copy">
					<span class="manage-kicker">SUPPLY · PROCUREMENT</span>
					<h1>农资采购</h1>
					<p>登记采购信息，按产品、供货商与经办人查找历史记录。</p>
				</div>
			</header>
			<div class="agent-resource-strip mb15">
				<div>
					<el-tag size="small" effect="plain" type="info">运行内虚拟资源</el-tag>
					<span>{{ agentResourceSummary }}</span>
					<small>虚拟耗材流水与旧采购记录隔离，不会写入本页采购数据。</small>
				</div>
				<el-button link type="primary" @click="router.push('/agentCenter')">查看指挥中心</el-button>
			</div>
			<div class="system-user-search mb15">
				<div class="filter-fields">
					<el-input v-model="state.tableData.param.search" size="default" placeholder="请输入产品名称" clearable />
					<el-input v-model="state.tableData.param.supplier" size="default" placeholder="请输入供货商" clearable />
					<el-input v-model="state.tableData.param.region" size="default" placeholder="请输入地区" clearable />
					<el-input v-model="state.tableData.param.manager" size="default" placeholder="请输入采购人" clearable />
				</div>
				<div class="filter-actions">
				<el-button size="default" @click="getTableData()">
					<el-icon>
						<ele-Search />
					</el-icon>
					查询
				</el-button>
				<el-button size="default" type="primary" @click="onOpenAddPurchase('add')">
					<el-icon>
						<ele-FolderAdd />
					</el-icon>
					添加
				</el-button>
				</div>
			</div>
			<el-table :data="state.tableData.data" v-loading="state.tableData.loading" style="width: 100%">
				<el-table-column prop="num" label="序号" width="80" align="center" />
				<el-table-column prop="productName" label="产品名称" show-overflow-tooltip width="120" align="center" />
				<el-table-column prop="price" label="价格(元)" width="120" align="center" />
				<el-table-column prop="quantity" label="采购数量" width="120" align="center" />
				<el-table-column prop="supplier" label="供货商" show-overflow-tooltip width="120" align="center" />
				<el-table-column prop="region" label="地区" show-overflow-tooltip width="120" align="center" />
				<el-table-column prop="phone" label="电话" show-overflow-tooltip width="150" align="center" />
				<el-table-column prop="manager" label="采购人" show-overflow-tooltip width="120" align="center" />
				<el-table-column prop="remark" label="备注" show-overflow-tooltip width="150" align="center" />
				<el-table-column label="操作" width="150" fixed="right" align="center">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onOpenEditPurchase('edit', scope.row)">修改</el-button>
						<el-button size="small" text type="primary" @click="onRowDel(scope.row)">删除</el-button>
					</template>
				</el-table-column>
			</el-table>
			<el-pagination
				@size-change="onHandleSizeChange"
				@current-change="onHandleCurrentChange"
				class="mt15"
				:pager-count="5"
				:page-sizes="[10, 20, 30]"
				v-model:current-page="state.tableData.param.pageNum"
				background
				v-model:page-size="state.tableData.param.pageSize"
				layout="total, sizes, prev, pager, next, jumper"
				:total="state.tableData.total"
			>
			</el-pagination>
		</div>
		<PurchaseDialog ref="purchaseDialogRef" @refresh="getTableData()" />
	</div>
</template>

<script setup lang="ts" name="systemRole">
import { computed, defineAsyncComponent, reactive, onMounted, ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import request from '/@/utils/request';
import { useAgentRunStore } from '/@/stores/agentRun';

const router = useRouter();
const agentStore = useAgentRunStore();

const asResourceItems = (value: unknown): Record<string, unknown>[] => {
	if (Array.isArray(value)) return value.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'));
	if (!value || typeof value !== 'object') return [];
	return Object.entries(value as Record<string, unknown>).map(([name, resource]) => {
		if (resource && typeof resource === 'object') return { name, ...(resource as Record<string, unknown>) };
		return { name, value: resource };
	});
};

const agentResourceSummary = computed(() => {
	if (!agentStore.hasActiveRun) return '8号温室番茄模拟尚未创建';
	const resources = asResourceItems(agentStore.summary?.resources);
	if (!resources.length) return '资源账本等待首个虚拟步';
	return resources.slice(0, 3).map((resource) => {
		const name = String(resource.name || resource.label || resource.code || '资源');
		const value = resource.value ?? resource.availableQuantity ?? '--';
		return `${name} ${value}${resource.unit || ''}`;
	}).join(' · ');
});

// 引入组件
const PurchaseDialog = defineAsyncComponent(() => import('./dialog.vue'));

// 定义变量内容
const purchaseDialogRef = ref();
const state = reactive({
	tableData: {
		data: [] as any,
		total: 0,
		loading: false,
		param: {
			search: '',
			supplier: '',
			region: '',
			manager: '',
			pageNum: 1,
			pageSize: 10,
		},
	},
});

// 获取表格数据
const getTableData = () => {
	state.tableData.loading = true;
	request
		.get('/api/purchase', {
			params: state.tableData.param,
		})
		.then((res) => {
			if (res.code == 0) {
				state.tableData.data = [];
				setTimeout(() => {
					state.tableData.loading = false;
				}, 500);
				for (let i = 0; i < res.data.records.length; i++) {
					state.tableData.data[i] = res.data.records[i];
					state.tableData.data[i]['num'] = i + 1;
				}
				state.tableData.total = res.data.total;
			} else {
				ElMessage({
					type: 'error',
					message: res.msg,
				});
			}
		});
};

// 打开新增采购弹窗
const onOpenAddPurchase = (type: string) => {
	purchaseDialogRef.value.openDialog(type);
};

// 打开修改采购弹窗
const onOpenEditPurchase = (type: string, row: Object) => {
	purchaseDialogRef.value.openDialog(type, row);
};

// 删除采购
const onRowDel = (row: any) => {
	ElMessageBox.confirm(`此操作将永久删除该采购信息，是否继续?`, '提示', {
		confirmButtonText: '确认',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			request.delete('/api/purchase/' + row.id).then((res) => {
				if (res.code == 0) {
					ElMessage({
						type: 'success',
						message: '删除成功！',
					});
					setTimeout(() => {
						getTableData();
					}, 500);
				} else {
					ElMessage({
						type: 'error',
						message: res.msg,
					});
				}
			});
		})
		.catch(() => {});
};

// 分页改变
const onHandleSizeChange = (val: number) => {
	state.tableData.param.pageSize = val;
	getTableData();
};

// 分页改变
const onHandleCurrentChange = (val: number) => {
	state.tableData.param.pageNum = val;
	getTableData();
};

// 页面加载时
onMounted(() => {
	getTableData();
	if (!agentStore.loading) agentStore.loadActiveRun();
});
</script>

<style scoped lang="scss">
.system-role-container {
	.system-role-padding {
		padding: 22px 24px;
		gap: 0;
		overflow: auto;
		.manage-heading {
			display: flex;
			align-items: flex-end;
			justify-content: space-between;
			margin-bottom: 18px;
			.manage-kicker { color: var(--agri-olive); font-size: 10px; font-weight: 700; letter-spacing: .16em; }
			h1 { margin: 5px 0 4px; color: var(--agri-wood); font: 600 26px/1.2 Georgia, 'Songti SC', 'SimSun', serif; }
			p { margin: 0; color: var(--agri-muted); font-size: 13px; }
		}
		.agent-resource-strip {
			display: flex;
			align-items: center;
			justify-content: space-between;
			gap: 12px;
			padding: 9px 12px;
			border: 1px solid var(--agri-line);
			border-radius: 6px;
			background: #f2f1e8;
			color: var(--agri-ink);
			font-size: 13px;

			.el-tag {
				margin-right: 8px;
			}

			small {
				margin-left: 8px;
				color: var(--agri-muted);
				font-size: 11px;
			}
		}
		.system-user-search {
			display: flex;
			align-items: center;
			justify-content: space-between;
			gap: 12px;
			padding: 12px;
			border: 1px solid var(--agri-line);
			border-radius: 6px;
			background: #f7f2e8;
			.filter-fields, .filter-actions { display: flex; align-items: center; gap: 8px; }
			.filter-fields { flex: 1; flex-wrap: wrap; }
			.filter-fields :deep(.el-input) { width: 175px; }
			.filter-actions { flex-shrink: 0; }
		}
		.el-table {
			flex: 1;
			border-radius: 6px;
			:deep(th.el-table__cell) { color: var(--agri-wood); font-weight: 600; }
			:deep(.el-table__row) { height: 44px; }
		}
		.el-pagination { justify-content: flex-end; }
	}
}

@media (max-width: 1100px) {
	.system-role-container .system-role-padding { padding: 18px; }
	.system-role-container .system-user-search { align-items: stretch; flex-direction: column; }
	.system-role-container .system-user-search .filter-actions { justify-content: flex-end; }
}
@media (max-width: 700px) {
	.system-role-container .system-role-padding .agent-resource-strip { align-items: flex-start; flex-direction: column; }
	.system-role-container .system-role-padding .agent-resource-strip small { display: block; margin: 4px 0 0; }
	.system-role-container .system-user-search .filter-fields :deep(.el-input) { width: 100%; }
}
</style>
