<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<header class="manage-heading">
				<div>
					<span class="manage-kicker">WORKSPACE · ACCESS</span>
					<h1>用户管理</h1>
					<p>维护平台账号、联系信息与角色权限。</p>
				</div>
			</header>
			<div class="system-user-search mb15">
				<div class="filter-fields">
					<el-input v-model="state.tableData.param.search" size="default" placeholder="请输入用户名" clearable />
				</div>
				<div class="filter-actions">
				<el-button size="default" @click="getTableData()">
					<el-icon>
						<ele-Search />
					</el-icon>
					查询
				</el-button>
				<el-button size="default" type="primary" @click="onOpenAddRole('add')">
					<el-icon>
						<ele-FolderAdd />
					</el-icon>
					添加
				</el-button>
				</div>
			</div>
			<el-table :data="state.tableData.data" v-loading="state.tableData.loading" style="width: 100%">
				<el-table-column prop="num" label="序号" width="80" align="center" />
				<el-table-column prop="username" label="账号" show-overflow-tooltip width="100" align="center"></el-table-column>
				<el-table-column prop="password" label="密码" width="100" align="center" />
				<el-table-column prop="name" label="姓名" show-overflow-tooltip width="100" align="center"></el-table-column>
				<el-table-column prop="sex" label="性别" show-overflow-tooltip width="80" align="center"></el-table-column>
				<el-table-column prop="email" label="邮箱" align="center" />
				<el-table-column prop="tel" label="手机号码" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="role" label="角色" show-overflow-tooltip align="center"></el-table-column>
			<el-table-column prop="avatar" label="头像" align="center">
				<template #default="scope">
					<img :src="scope.row.avatar" width="36" height="36" class="user-avatar" />
					</template>
				</el-table-column>
				<el-table-column label="操作" width="150" align="center">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onOpenEditRole('edit', scope.row)">修改</el-button>
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
		<RoleDialog ref="roleDialogRef" @refresh="getTableData()" />
	</div>
</template>

<script setup lang="ts" name="systemRole">
import { defineAsyncComponent, reactive, onMounted, ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import request from '/@/utils/request';

// 引入组件
const RoleDialog = defineAsyncComponent(() => import('./dialog.vue'));

// 定义变量内容
const roleDialogRef = ref();
const state = reactive<SysRoleState>({
	tableData: {
		data: [] as any,
		total: 0,
		loading: false,
		param: {
			search: '',
			pageNum: 1,
			pageSize: 10,
		},
	},
});

const getTableData = () => {
	state.tableData.loading = true;
	request
		.get('/api/user', {
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
					if (state.tableData.data[i]['role'] == 'admin') {
						state.tableData.data[i]['role'] = '管理员';
					} else if (state.tableData.data[i]['role'] == 'common') {
						state.tableData.data[i]['role'] = '普通用户';
					} else if (state.tableData.data[i]['role'] == 'others') {
						state.tableData.data[i]['role'] = '其他用户';
					}
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

// 打开新增角色弹窗
const onOpenAddRole = (type: string) => {
	roleDialogRef.value.openDialog(type);
};
// 打开修改角色弹窗
const onOpenEditRole = (type: string, row: Object) => {
	roleDialogRef.value.openDialog(type, row);
};

// 删除角色
const onRowDel = (row: any) => {
	ElMessageBox.confirm(`此操作将永久删除该信息，是否继续?`, '提示', {
		confirmButtonText: '确认',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			console.log(row);
			request.delete('/api/user/' + row.id).then((res) => {
				if (res.code == 0) {
					console.log(res.data);
					ElMessage({
						type: 'success',
						message: '删除成功！',
					});
				} else {
					ElMessage({
						type: 'error',
						message: res.msg,
					});
				}
			});
			setTimeout(() => {
				getTableData();
			}, 500);
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
});
</script>

<style scoped lang="scss">
.system-role-container {
	.system-role-padding {
		padding: 22px 24px;
		overflow: auto;
		.manage-heading {
			margin-bottom: 18px;
			.manage-kicker { color: var(--agri-olive); font-size: 10px; font-weight: 700; letter-spacing: .16em; }
			h1 { margin: 5px 0 4px; color: var(--agri-wood); font: 600 26px/1.2 Georgia, 'Songti SC', 'SimSun', serif; }
			p { margin: 0; color: var(--agri-muted); font-size: 13px; }
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
			.filter-fields { flex: 1; }
			.filter-fields :deep(.el-input) { width: 240px; max-width: 100%; }
		}
		.el-table {
			flex: 1;
			border-radius: 6px;
			:deep(th.el-table__cell) { color: var(--agri-wood); font-weight: 600; }
			:deep(.el-table__row) { height: 48px; }
			:deep(.user-avatar) { display: block; object-fit: cover; margin: 0 auto; border-radius: 50%; border: 1px solid var(--agri-line); }
		}
		.el-pagination { justify-content: flex-end; }
	}
}

@media (max-width: 900px) {
	.system-role-container .system-role-padding { padding: 18px; }
	.system-role-container .system-user-search .filter-fields :deep(.el-input) { width: 100%; }
}
</style>
