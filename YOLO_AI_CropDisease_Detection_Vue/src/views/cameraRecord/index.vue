<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<DetectionNav mode="camera" view="history" />
			<div class="record-intro"><div><p class="eyebrow">OBSERVATION LOG / CAMERA</p><h1>摄像识别记录</h1><p>回看已保存的摄像检测片段与模型设置，历史结果仅作为候选观察信号。</p></div><span class="record-count">{{ state.tableData.total }} 条记录</span></div>
			<div class="system-user-search mb15">
				<el-input v-model="state.tableData.param.search1" size="default" placeholder="请输入农作物类型" style="max-width: 180px"> </el-input>
				<!-- <el-input v-model="state.tableData.param.search3" size="default" placeholder="请输入最低阈值" style="max-width: 180px; margin-left: 15px"></el-input> -->
				<el-button size="default" type="primary" class="ml10" @click="getTableData()">
					<el-icon>
						<ele-Search />
					</el-icon>
					查询
				</el-button>
			</div>
			<el-table :data="state.tableData.data" v-loading="state.tableData.loading" style="width: 100%">
				<el-table-column prop="num" label="序号" width="100" align="center" />
				<el-table-column prop="outVideo" label="处理结果" width="200" align="center">
					<template #default="scope">
						<video class="video" preload="auto" controls :key="scope.row.outVideo + uniqueKey" style="max-height: 120px;">
							<source :src="scope.row.outVideo" type="video/mp4" />
						</video>
					</template>
				</el-table-column>
				<el-table-column prop="kind" label="农作物种类" align="center" />
				<el-table-column prop="weight" label="识别权重" align="center" />
				<el-table-column prop="conf" label="最小阈值" show-overflow-tooltip width="100" align="center"></el-table-column>
				<el-table-column prop="username" label="识别用户" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="startTime" label="识别时间" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column label="操作" width="130" align="center">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onRowDel(scope.row)">删除</el-button>
					</template>
				</el-table-column>
			</el-table>
			<el-pagination @size-change="onHandleSizeChange" @current-change="onHandleCurrentChange" class="mt15"
				:pager-count="5" :page-sizes="[10, 20, 30]" v-model:current-page="state.tableData.param.pageNum"
				background v-model:page-size="state.tableData.param.pageSize"
				layout="total, sizes, prev, pager, next, jumper" :total="state.tableData.total">
			</el-pagination>
		</div>
	</div>
</template>

<script setup lang="ts" name="systemRole">
import { reactive, onMounted, ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import request from '/@/utils/request';
import { useUserInfo } from '/@/stores/userInfo';
import { storeToRefs } from 'pinia';
import DetectionNav from '/@/components/detectionNav/index.vue';

const stores = useUserInfo();
const { userInfos } = storeToRefs(stores);

const state = reactive<SysRoleState>({
	tableData: {
		data: [] as any,
		total: 0,
		loading: false,
		param: {
			search: '',
			search3: '',
			search2: '',
			pageNum: 1,
			pageSize: 10,
		},
	},
});

// 唯一标识符，动态刷新
const uniqueKey = ref(0);

const getTableData = () => {
	state.tableData.loading = true;
	if (userInfos.value.userName != 'admin') {
		state.tableData.param.search = userInfos.value.userName;
	}
	request
		.get('/api/cameraRecords', {
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

				// 更新唯一标识符
				uniqueKey.value++;
			} else {
				ElMessage({
					type: 'error',
					message: res.msg,
				});
			}
		});
};

const onRowDel = (row: any) => {
	ElMessageBox.confirm(`此操作将永久删除该信息，是否继续?`, '提示', {
		confirmButtonText: '确认',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			request.delete('/api/cameraRecords/' + row.id).then((res) => {
				if (res.code == 0) {
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
		.catch(() => { });
};

const onHandleSizeChange = (val: number) => {
	state.tableData.param.pageSize = val;
	getTableData();
};

const onHandleCurrentChange = (val: number) => {
	state.tableData.param.pageNum = val;
	getTableData();
};

onMounted(() => {
	getTableData();
});
</script>


<style scoped lang="scss">
.record-intro { display:flex; align-items:flex-end; justify-content:space-between; gap:16px; padding:4px 2px 17px; }
.record-intro .eyebrow { margin:0 0 5px; color:#887564; font-size:10px; font-weight:700; letter-spacing:.12em; }
.record-intro h1 { margin:0; color:#382b25; font:600 25px/1.3 Georgia,'Songti SC',serif; }
.record-intro p:last-child { margin:6px 0 0; color:#756b62; font-size:13px; line-height:1.65; }
.record-count { padding:7px 10px; border:1px solid #e5d7c8; color:#765a49; background:#f7efe4; font-size:12px; white-space:nowrap; }
.system-role-container {

	// background: radial-gradient(circle, #d3e3f1 0%, #ffffff 100%);
	.system-role-padding {
		padding: 15px;
		.el-table {
			flex: 1;
			--el-table-header-bg-color: #f5efe6;
			--el-table-row-hover-bg-color: #faf6ef;
			--el-table-border-color: #ebe3d9;
			--el-table-text-color: #493d34;
			--el-table-header-text-color: #756457;
			:deep(.el-table__row) {
				height: 140px;  // 设置行高
			}
			:deep(.el-table__header) {
				th {
					padding: 6px 0;  // 减小表头padding
				}
			}
			:deep(.el-table__cell) {
				padding: 3px 0;  // 减小单元格padding
			}
			:deep(.cell) {
				line-height: 1.3;  // 减小文字行高
			}
		}
		.system-user-search { display:flex; align-items:center; gap:8px; padding:12px; border:1px solid #e9e0d5; background:#fdfbf7; }
		:deep(.el-input) {
			height: 32px;
			line-height: 32px;
		}
	}
}

@media(max-width:700px) { .record-intro { align-items:flex-start; flex-direction:column; } }

.video {
	width: 100%;
	max-height: 120px;  // 限制视频最大高度
	height: auto;
	object-fit: contain;
}
</style>
