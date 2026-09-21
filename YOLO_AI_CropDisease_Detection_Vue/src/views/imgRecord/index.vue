<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<div class="agent-vision-note mb15">
				<div>
					<el-tag size="small" effect="plain" type="info">视觉信号</el-tag>
					<span v-if="agentStore.hasActiveRun">识别记录将作为番茄温室模拟的待核验事件导入，不会直接触发设备或处置。</span>
					<span v-else>请先创建番茄温室模拟，才可将识别记录导入为待核验事件。</span>
				</div>
				<el-button link type="primary" @click="router.push('/agentCenter')">{{ agentStore.hasActiveRun ? '查看指挥中心' : '创建模拟' }}</el-button>
			</div>
			<div class="system-user-search mb15">
				<el-input v-model="state.tableData.param.search1" size="default" placeholder="请输入农作物类型"
					style="max-width: 180px"> </el-input>
				<!-- <el-input v-model="state.tableData.param.search2" size="default" placeholder="请输入识别结果"
					style="max-width: 180px; margin-left: 15px">
				</el-input> -->
				<el-button size="default" type="primary" class="ml10" @click="getTableData()">
					<el-icon>
						<ele-Search />
					</el-icon>
					查询
				</el-button>
			</div>
			<el-table :data="state.tableData.data" style="width: 100%">
				<el-table-column type="expand">
					<template #default="props">
						<div m="4">
							<p style="margin-left: 20px; font-size: 16px; font-weight: 800;">详细识别结果：</p>
							<el-table :data="props.row.family">
								<el-table-column prop="label" label="识别结果" align="center" />
								<el-table-column prop="confidence" label="置信度" show-overflow-tooltip
									align="center"></el-table-column>
								<el-table-column prop="startTime" label="识别时间" align="center" />
							</el-table>
						</div>
					</template>
				</el-table-column>
				<el-table-column prop="num" label="序号" width="80" align="center" />
				<el-table-column prop="inputImg" label="原始图片" width="120" align="center">
					<template #default="scope">
						<img :src="scope.row.inputImg" width="120" height="60" />
					</template>
				</el-table-column>
				<el-table-column prop="outImg" label="预测图片" width="120" align="center">
					<template #default="scope">
						<img :src="scope.row.outImg" width="120" height="60" />
					</template>
				</el-table-column>
				<el-table-column prop="cropType" label="农作物种类" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="weight" label="识别权重" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="conf" label="最小阈值" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="allTime" label="总用时" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column prop="startTime" label="识别时间" width="200" align="center" />
				<el-table-column prop="username" label="识别用户" show-overflow-tooltip align="center"></el-table-column>
				<el-table-column label="操作" width="180">
					<template #default="scope">
						<el-tooltip :disabled="agentStore.hasActiveRun" content="请先在智能体指挥中心创建番茄温室模拟" placement="top">
							<span>
								<el-button
									size="small"
									text
									type="success"
									:loading="importingId === scope.row.id"
									:disabled="!agentStore.hasActiveRun || importingId !== null"
									@click="importVisionEvent(scope.row)"
								>
									导入信号
								</el-button>
							</span>
						</el-tooltip>
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
import { defineAsyncComponent, reactive, onMounted, ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import request from '/@/utils/request';
import { useUserInfo } from '/@/stores/userInfo';
import { useAgentRunStore } from '/@/stores/agentRun';
import { storeToRefs } from 'pinia';

const router = useRouter();
const stores = useUserInfo();
const { userInfos } = storeToRefs(stores);
const agentStore = useAgentRunStore();
const importingId = ref<number | string | null>(null);

const state = reactive({
	tableData: {
		data: [] as any,
		total: 0,
		loading: false,
		param: {
			search: '',
			search1: '',
			search2: '',
			pageNum: 1,
			pageSize: 10,
		},
	},
});

const getTableData = () => {
	state.tableData.loading = true;
	if (userInfos.value.userName != 'admin') {
		state.tableData.param.search = userInfos.value.userName;
	}
	request
		.get('/api/imgRecords', {
			params: state.tableData.param,
		})
		.then((res) => {
			if (res.code == 0) {
				state.tableData.data = [];
				setTimeout(() => {
					state.tableData.loading = false;
				}, 500);
				for (let i = 0; i < res.data.records.length; i++) {
					const confidences = JSON.parse(res.data.records[i].confidence);
					const labels = JSON.parse(res.data.records[i].label);
					const transformedData = transformData(res.data.records[i], confidences, labels);
					transformedData["num"] = i + 1;
					state.tableData.data[i] = transformedData
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

const transformData = (originalData, confidences, labels) => {
    const family = labels.map((label, index) => ({
        label: label,
        confidence: confidences[index],
        startTime: originalData.startTime
    }));

    const result = {
        id: originalData.id,
        inputImg: originalData.inputImg,
        outImg: originalData.outImg,
        cropType: originalData.kind || '-',  // 使用后端返回的kind字段
        weight: originalData.weight,
        allTime: originalData.allTime,
        conf: originalData.conf,
        startTime: originalData.startTime,
        username: originalData.username,
        family: family
    };

    return result;
}

const importVisionEvent = async (row: { id: number | string }) => {
	if (agentStore.runId === null) {
		ElMessage.warning('请先在智能体指挥中心创建番茄温室模拟');
		return;
	}

	importingId.value = row.id;
	try {
		const response = await request.post(`/api/agent/runs/${encodeURIComponent(String(agentStore.runId))}/vision-events`, {
			sourceRecordId: row.id,
			sourceType: 'IMG_RECORD',
			operatorUsername: userInfos.value.userName || 'operator',
		});
		const code = response?.code;
		if (code !== undefined && code !== 0 && code !== '0' && code !== 200 && code !== '200') {
			throw new Error(response?.msg || '导入视觉事件失败');
		}
		ElMessage.success('已导入为待核验视觉信号');
		await agentStore.loadActiveRun();
	} catch (error) {
		ElMessage.error(error instanceof Error ? error.message : '导入视觉事件失败');
	} finally {
		importingId.value = null;
	}
};


// 删除
const onRowDel = (row: any) => {
	ElMessageBox.confirm(`此操作将永久删除该信息，是否继续?`, '提示', {
		confirmButtonText: '确认',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			console.log(row);
			request.delete('/api/imgRecords/' + row.id).then((res) => {
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
		.catch(() => { });
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
		.agent-vision-note {
			display: flex;
			align-items: center;
			justify-content: space-between;
			gap: 12px;
			padding: 9px 12px;
			border: 1px solid #d9e6db;
			border-radius: 4px;
			background: #f8fbf8;
			color: #5f6f64;
			font-size: 13px;
			line-height: 1.5;

			.el-tag {
				margin-right: 8px;
			}
		}
		.el-table {
			flex: 1;
			:deep(.el-table__row) {
				height: 70px;  // 设置行高
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
		:deep(.el-input) {
			height: 32px;
			line-height: 32px;
		}
	}
}

@media (max-width: 700px) {
	.system-role-padding .agent-vision-note {
		align-items: flex-start;
		flex-direction: column;
	}
}

// 展开行的样式
:deep(.el-table__expanded-cell) {
	padding: 10px !important;
	.el-table {
		margin-top: 5px;
		.el-table__row {
			height: 40px !important;  // 设置展开行的表格行高
		}
	}
}
</style>
