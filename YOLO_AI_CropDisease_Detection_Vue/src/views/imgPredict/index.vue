<template>
	<div class="system-predict-container layout-padding">
		<div class="system-predict-padding layout-padding-auto layout-padding-view">
			<DetectionNav mode="image" view="detect" />
			<div class="header">
				<div class="weight">
					<el-select v-model="kind" placeholder="请选择作物种类" size="large" style="width: 200px" @change="getData">
						<el-option v-for="item in state.kind_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="weight">
					<el-select v-model="weight" placeholder="请选择模型" size="large" style="margin-left: 20px;width: 200px">
						<el-option v-for="item in state.weight_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="conf" style="margin-left: 20px;display: flex; flex-direction: row;">
					<div
						style="font-size: 14px;margin-right: 20px;display: flex;justify-content: start;align-items: center;color: #909399;">
						设置最小置信度阈值</div>
					<el-slider v-model="conf" :format-tooltip="formatTooltip" style="width: 300px;" />
				</div>
				<div class="button-section" style="margin-left: 20px">
					<el-button type="primary" @click="upData" class="predict-button">开始预测</el-button>
				</div>
			</div>
			<!-- 图片检测结果是候选信号，诊断建议统一由决策助手检索后给出。 -->
			<el-row :gutter="10" class="image-display">
				<!-- 原图展示 -->
				<el-col :xs="24" :sm="12">
					<el-card shadow="hover" class="card">
						<div class="image-title">原图片</div>
						<el-upload v-model="state.img" ref="uploadFile" class="avatar-uploader"
							action="http://localhost:9999/files/upload" :show-file-list="false"
							:on-success="handleAvatarSuccessone">
							<el-image v-if="imageUrl" :src="imageUrl" class="preview-image" fit="contain" />
							<div v-else class="uploader-content"> 
								<el-icon class="upload-icon">
									<Plus />
								</el-icon>
								<div class="upload-text">点击上传图片</div>
							</div>
						</el-upload>
					</el-card>
				</el-col>

				<!-- 预测结果图 -->
				<el-col :xs="24" :sm="12">
					<el-card shadow="hover" class="card">
						<div class="image-title">预测结果</div>
						<el-image v-if="predictedImageUrl" :src="predictedImageUrl" class="preview-image"
							fit="contain" />
						<div v-else class="placeholder">
							<el-icon>
								<Picture />
							</el-icon>
							<span>预测后将在此显示结果</span>
						</div>
					</el-card>
				</el-col>
			</el-row>
			<div v-if="state.predictionResult.label" class="review-action">
				<span>模型识别仅为候选结果，建议结合知识库核对。</span>
				<el-button type="primary" :icon="ChatLineRound" @click="reviewPrediction">到决策助手核对</el-button>
			</div>
			<el-row class="result-section">
				<el-col :span="24">
					<el-card>
						<div class="bottom" v-if="state.predictionResult.label">
							<div class="result-column">
								<div class="result-title">识别结果：</div>
								<div v-for="(label, index) in formatLabelArray(state.predictionResult.label)" :key="index" class="result-item">
									<span class="result-value">{{ label }}</span>
								</div>
							</div>
							<div class="result-column">
								<div class="result-title">模型分数（未校准）：</div>
								<div v-for="(conf, index) in formatConfidenceArray(state.predictionResult.confidence)" :key="index" class="result-item">
									<span class="result-value">{{ conf }}</span>
								</div>
							</div>
							<div class="result-column">
								<div class="result-title">总时间：</div>
								<div class="result-item">
									<span class="result-value">{{ formatTime(state.predictionResult.allTime) }}</span>
								</div>
							</div>
						</div>
						<div class="bottom placeholder" v-else>
							<div style="width: 100%; text-align: center; color: #909399;">
								<el-icon style="margin-right: 8px; vertical-align: middle;"><Picture /></el-icon>
								<span>预测结果将在这里显示</span>
							</div>
						</div>
					</el-card>
				</el-col>
			</el-row>
		</div>
	</div>
</template>


<script setup lang="ts" name="personal">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import type { UploadInstance, UploadProps } from 'element-plus';
import { ElMessage } from 'element-plus';
import request from '/@/utils/request';
import { Plus, ChatLineRound, Picture } from '@element-plus/icons-vue';
import { useUserInfo } from '/@/stores/userInfo';
import { storeToRefs } from 'pinia';
import { formatDate } from '/@/utils/formatTime';
import DetectionNav from '/@/components/detectionNav/index.vue';

const router = useRouter();
const imageUrl = ref('');
const conf = ref('');
const weight = ref('');
const kind = ref('');
const uploadFile = ref<UploadInstance>();
const stores = useUserInfo();
const { userInfos } = storeToRefs(stores);
// 新增响应式变量
const predictedImageUrl = ref('');
const state = reactive({
	weight_items: [] as any,
	kind_items: [
		{
			value: 'corn',
			label: '玉米',
		},
		{
			value: 'rice',
			label: '水稻',
		},
		{
			value: 'wheat',
			label: '小麦',
		},
		{
			value: 'potato',
			label: '马铃薯',
		},
    {
      value: 'tomato',
      label: '番茄',
    },
    {
      value: 'cotton',
      label: '棉花',
    },
    {
      value: 'apple',
      label: '苹果',
    },
    {
      value: 'grape',
      label: '葡萄',
    },
    {
      value: 'strawberry',
      label: '草莓',
    },
	],
	img: '',
	predictionResult: {
		label: '',
		confidence: '',
		allTime: '',
	},
	form: {
		username: '',
		inputImg: null as any,
		weight: '',
		conf: null as any,
		kind: '',
		startTime: ''
	},
});

const formatTooltip = (val: number) => {
	return val / 100
}

const handleAvatarSuccessone: UploadProps['onSuccess'] = (response, uploadFile) => {
	imageUrl.value = URL.createObjectURL(uploadFile.raw!);
	state.img = response.data;
};

const getData = () => {
	request.get('/api/flask/file_names').then((res) => {
		if (res.code == 0) {
			res.data = JSON.parse(res.data);
			state.weight_items = res.data.weight_items.filter(item => item.value.includes(kind.value));
		} else {
			ElMessage.error(res.msg);
		}
	});
};


const upData = () => {
	state.form.weight = weight.value;
	state.form.conf = (parseFloat(conf.value) / 100);
	state.form.username = userInfos.value.userName;
	state.form.inputImg = state.img;
	state.form.kind = kind.value;
	state.form.startTime = formatDate(new Date(), 'YYYY-mm-dd HH:MM:SS');
	console.log(state.form);
	request.post('/api/flask/predict', state.form).then((res) => {
		if (res.code == 0) {
			const originalImage = imageUrl.value;
			try {
				res.data = JSON.parse(res.data);

				// 如果 res.data.label 是字符串，则解析为数组
				if (typeof res.data.label === 'string') {
					res.data.label = JSON.parse(res.data.label);
				}

				// 确保 res.data.label 是数组后再调用 map
				if (Array.isArray(res.data.label)) {
					state.predictionResult.label = res.data.label.map(item => item.replace(/\\u([\dA-Fa-f]{4})/g, (_, code) =>
						String.fromCharCode(parseInt(code, 16))
					));
				} else {
					console.error("res.data.label 不是数组:", res.data.label);
				}
				state.predictionResult.confidence = res.data.confidence;
				state.predictionResult.allTime = res.data.allTime;

				// 覆盖原图片
				if (res.data.outImg) {
					// 使用服务器返回的新图片路径
					predictedImageUrl.value = res.data.outImg;
				} else {
					// 否则保留原图片路径
					imageUrl.value = imageUrl.value;
				}
				console.log(state.predictionResult);
			} catch (error) {
				console.error('解析 JSON 时出错:', error);
			}
			ElMessage.success('预测成功！');
		} else {
			ElMessage.error(res.msg);
		}
	});
};
const reviewPrediction = () => {
	const label = formatLabelArray(state.predictionResult.label).filter((item) => item && item !== '未知').slice(0, 3).join('、');
	const crop = state.kind_items.find((item) => item.value === kind.value)?.label || '';
	if (!label || !crop) return;
	const score = formatConfidenceArray(state.predictionResult.confidence).slice(0, 3).join('、');
	void router.push({ path: '/agentChat', query: { crop, detection: label, score } });
};

// 格式化函数
const formatLabelArray = (label: any) => {
	if (Array.isArray(label)) {
		return label.map(item => item.replace(/[\[\]"]/g, '').trim());
	} else if (typeof label === 'string') {
		return [label.replace(/[\[\]"]/g, '').trim()];
	}
	return ['未知'];
};

const formatConfidenceArray = (confidence: string) => {
	if (!confidence) return ['0%'];
	try {
		let confidences = confidence;
		if (typeof confidence === 'string') {
			confidences = JSON.parse(confidence);
		}
		if (Array.isArray(confidences)) {
			return confidences.map(conf => {
				const confValue = parseFloat(String(conf).replace(/[\[\]"%]/g, ''));
				return confValue.toFixed(2) + '%';
			});
		} else {
			const confValue = parseFloat(String(confidence).replace(/[\[\]"%]/g, ''));
			return [confValue.toFixed(2) + '%'];
		}
	} catch (error) {
		console.error('解析置信度出错:', error);
		return ['0%'];
	}
};

const formatTime = (time: string) => {
	if (!time) return '0秒';
	return parseFloat(time).toFixed(3) + '秒';
};

onMounted(() => {
	getData();
});
</script>

<style scoped lang="scss">
.system-predict-container {
	width: 100%;
	height: 100vh;
	display: flex;
	flex-direction: column;
	overflow-y: auto;
	background: #f5f7fa;

	.system-predict-padding {
		padding: 15px;
		padding-bottom: 0;
		padding-top: 0;  /* 移除顶部内边距 */
		min-height: calc(100vh - 60px);
	}
}

.header {
	width: 100%;
	padding: 10px 0;
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 15px;
	margin-bottom: 1px;
	background: white;
	padding: 10px;
	border-radius: 8px;
	box-shadow: none;  /* 移除阴影 */
}

.image-display {
	margin-top: 15px;

	.card {
		height: 100%;
		background: white;
		border-radius: 8px;
		box-shadow: none;
		transition: all 0.3s ease;
		
		&:hover {
			transform: none;
			box-shadow: none;
		}

		.image-title {
			font-size: 16px;
			font-weight: 600;
			color: #303133;
			margin-bottom: 15px;
			padding-bottom: 10px;
			border-bottom: 1px solid #ebeef5;
		}

		.avatar-uploader {
			width: 100%;
			height: 358px;
			border: 1px dashed #d9d9d9;
			border-radius: 4px;
			cursor: pointer;
			position: relative;
			overflow: hidden;
			transition: var(--el-transition-duration-fast);
			display: flex;
			justify-content: center;
			align-items: center;

			&:hover {
				border-color: var(--el-color-primary);
			}
		}

		.preview-image {
			width: 100%;
			height: 358px;
			object-fit: contain;
		}

		.uploader-content {
			width: 100%;
			height: 100%;
			display: flex;
			flex-direction: column;
			justify-content: center;
			align-items: center;
			gap: 10px;
		}

		.upload-icon {
			font-size: 28px;
			color: #909399;
		}

		.upload-text {
			color: #909399;
			font-size: 14px;
		}

		.placeholder {
			height: 358px;
			display: flex;
			flex-direction: column;
			align-items: center;
			justify-content: center;
			gap: 10px;
			color: #909399;

			.el-icon {
				font-size: 28px;
			}
		}

		.suggestion-content {
			height: 358px;
			padding: 15px;
			overflow-y: auto;
			background: #f5f7fa;
			border-radius: 4px;

			.suggestion-text {
				font-size: 14px;
				line-height: 1.8;
				color: #303133;
				white-space: pre-wrap;
				text-align: justify;
			}

			&::-webkit-scrollbar {
				width: 6px;
			}

			&::-webkit-scrollbar-thumb {
				background-color: #dcdfe6;
				border-radius: 3px;
			}

			&::-webkit-scrollbar-track {
				background-color: #f8f9fa;
			}
		}
	}
}
.review-action { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-top: 12px; padding: 12px 16px; background: #fff; color: #66776c; font-size: 13px; }
.result-section {
	margin-top: 10px;
	padding: 0;

	:deep(.el-card) {
		background: white;
		border-radius: 8px;
		box-shadow: none;
		margin: 0;
		
		.el-card__body {
			padding: 0;
		}
	}

	.bottom {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		padding: 15px 20px;
		background: white;
		border-radius: 8px;
		min-height: 115px;

		.result-column {
			width: 33%;
			padding: 0 15px;
			border-right: 1px solid #ebeef5;

			&:last-child {
				border-right: none;
			}

			.result-title {
				font-size: 14px;
				color: #606266;
				font-weight: normal;
				margin-bottom: 10px;
			}

			.result-item {
				margin: 5px 0;
				
				.result-value {
					color: #409EFF;
					font-weight: 500;
				}
			}
		}

		&.placeholder {
			color: #909399;
			justify-content: center;
			align-items: center;
			
			.el-icon {
				margin-right: 8px;
				font-size: 16px;
			}
		}
	}
}

.predict-button {
	background: linear-gradient(45deg, #409eff, #36a2f1);
	border: none;
	box-shadow: 0 2px 6px rgba(64, 158, 255, 0.3);
	transition: all 0.3s ease;


	&:hover {
		transform: translateY(-2px);
		box-shadow: 0 4px 12px rgba(64, 158, 255, 0.4);
	}
}
</style>
