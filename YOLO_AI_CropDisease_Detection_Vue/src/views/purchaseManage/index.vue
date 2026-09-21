<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<div class="agent-resource-strip mb15">
				<div>
					<el-tag size="small" effect="plain" type="info">运行内虚拟资源</el-tag>
					<span>{{ agentResourceSummary }}</span>
					<small>虚拟耗材流水与旧采购记录隔离，不会写入本页采购数据。</small>
				</div>
				<el-button link type="primary" @click="router.push('/agentCenter')">查看指挥中心</el-button>
			</div>
			<div class="system-user-search mb15">
				<el-input v-model="state.tableData.param.search" size="default" placeholder="请输入产品名称" style="max-width: 180px"> </el-input>
				<el-input v-model="state.tableData.param.supplier" size="default" placeholder="请输入供货商" class="ml10" style="max-width: 180px"> </el-input>
				<el-input v-model="state.tableData.param.region" size="default" placeholder="请输入地区" class="ml10" style="max-width: 180px"> </el-input>
				<el-input v-model="state.tableData.param.manager" size="default" placeholder="请输入采购人" class="ml10" style="max-width: 180px"> </el-input>
				<el-button size="default" type="primary" class="ml10" @click="getTableData()">
					<el-icon>
						<ele-Search />
					</el-icon>
					查询
				</el-button>
				<el-button size="default" type="success" class="ml10" @click="onOpenAddPurchase('add')">
					<el-icon>
						<ele-FolderAdd />
					</el-icon>
					添加
				</el-button>
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
		padding: 15px;
		.agent-resource-strip {
			display: flex;
			align-items: center;
			justify-content: space-between;
			gap: 12px;
			padding: 9px 12px;
			border: 1px solid #d9e6db;
			border-radius: 4px;
			background: #f8fbf8;
			color: #536257;
			font-size: 13px;

			.el-tag {
				margin-right: 8px;
			}

			small {
				margin-left: 8px;
				color: #84918a;
				font-size: 11px;
			}
		}
		.el-table {
			flex: 1;
		}
	}
}

@media (max-width: 700px) {
	.system-role-padding .agent-resource-strip {
		align-items: flex-start;
		flex-direction: column;

		small {
			display: block;
			margin: 4px 0 0;
		}
	}
}
</style>
