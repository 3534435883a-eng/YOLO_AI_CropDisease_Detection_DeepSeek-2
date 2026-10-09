<template>
	<div class="system-predict-container layout-padding" :class="{embedded}">
		<div class="system-predict-padding layout-padding-auto layout-padding-view">
			<DetectionNav v-if="!embedded" mode="image" view="detect" />
			<div class="page-intro">
				<div><p class="eyebrow">OBSERVE / IMAGE</p><h1>从一张叶片开始</h1><p>上传作物图像，查看模型标出的候选信号；结果需要结合知识依据与田间情况进一步核对。</p></div>
				<div class="source-note"><span class="source-dot"></span>视觉模型候选 · 不等同于确诊</div>
			</div>
			<div class="header">
				<div class="control-caption"><span>01</span><b>检测设置</b></div>
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
					<el-button type="primary" :loading="predicting" @click="upData" class="predict-button">开始预测</el-button>
				</div>
			</div>
			<!-- 图片检测结果是候选信号，诊断建议统一由决策助手检索后给出。 -->
			<el-row :gutter="10" class="image-display">
				<!-- 原图展示 -->
				<el-col :xs="24" :sm="12">
					<el-card shadow="hover" class="card">
						<div class="image-title"><span>02 / 输入图像</span><small>{{ imageUrl ? '已载入待检测样本' : '等待上传样本' }}</small></div>
						<el-upload :disabled="predicting" v-model="state.img" ref="uploadFile" class="avatar-uploader"
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
						<div class="image-title"><span>03 / 模型标注</span><small>{{ predictedImageUrl ? '候选检测结果' : '尚无检测结果' }}</small></div>
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
import {useGreenhouseStore} from '/@/stores/greenhouse';
import {createFarmTask,addFarmEvidence,type FarmTask,type FarmEvidence} from '/@/api/agent/tasks';
const props=withDefaults(defineProps<{embedded?:boolean}>(),{embedded:false});
const emit=defineEmits<{(event:'evidence',task:FarmTask):void;(event:'handoff',task:FarmTask):void}>();
const greenhouse=useGreenhouseStore();
let evidenceSaving=false,lastEvidenceId='';
let lastEvidenceTask:FarmTask|null=null;

const router = useRouter();
const imageUrl = ref(''),predicting=ref(false);
const conf = ref(25);
const weight = ref('');
const kind = ref(props.embedded?'tomato':'');
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
	if(imageUrl.value.startsWith('blob:'))URL.revokeObjectURL(imageUrl.value);
    state.predictionResult.label='';state.predictionResult.confidence='';predictedImageUrl.value='';lastEvidenceId='';lastEvidenceTask=null;
	imageUrl.value = URL.createObjectURL(uploadFile.raw!);
	state.img = response.data;
};

const getData = () => {
    state.predictionResult.label='';state.predictionResult.confidence='';predictedImageUrl.value='';lastEvidenceId='';lastEvidenceTask=null;
	request.get('/api/flask/file_names').then((res) => {
		if (res.code == 0) {
			res.data = JSON.parse(res.data);
			state.weight_items = res.data.weight_items.filter(item => item.value.includes(kind.value));
            if(!state.weight_items.some((item:{value:string})=>item.value===weight.value))weight.value=state.weight_items[0]?.value||'';
		} else {
			ElMessage.error(res.msg);
		}
	});
};


const upData = () => {
    if(predicting.value)return;
    if(!state.img||!kind.value||!weight.value){ElMessage.info('请先选择作物和模型，并上传图像');return;}
    predicting.value=true;
	state.form.weight = weight.value;
	state.form.conf = (Math.max(1,Math.min(100,Number(conf.value)||25)) / 100);
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
                ElMessage.error('识别结果无法解析，请重试');return;
			}
			ElMessage.success('预测成功！');
            lastEvidenceId='';lastEvidenceTask=null;if(props.embedded)void savePrediction(false);
		} else {
			ElMessage.error(res.msg);
		}
	}).catch(()=>undefined).finally(()=>{predicting.value=false;});
};
async function savePrediction(handoff=true){
 const labels=formatLabelArray(state.predictionResult.label).filter((item:string)=>item&&item!=='未知');
 const crop=state.kind_items.find((item:{value:string;label:string})=>item.value===kind.value)?.label||'';
 if(!labels.length||!crop||evidenceSaving)return;
 const independent=!!greenhouse.run&&crop!=='番茄';
 if(independent&&props.embedded){ElMessage.warning('当前大棚为番茄，请使用图片识别页面讨论其他作物。');return;}
 evidenceSaving=true;
 try{
  let task=lastEvidenceTask|| (independent?await createFarmTask({crop,title:crop+'农情管理'}):await greenhouse.ensureTask());
  if(!independent&&!greenhouse.run&&task.crop!==crop)task=await greenhouse.updateTask({crop,title:crop+'农情管理'});
  if(!lastEvidenceId){
   const evidence:FarmEvidence={id:'image-'+Date.now(),type:'IMAGE',label:labels.join('、'),source:'YOLO候选识别 / 待人工核验',imageUrl:state.img,candidates:labels.map((label:string,index:number)=>({label,score:formatConfidenceArray(state.predictionResult.confidence)[index]||null})),details:{crop,originalImageUrl:state.img,annotatedImageUrl:predictedImageUrl.value,confidence:state.predictionResult.confidence,weight:state.form.weight,threshold:state.form.conf,observedAt:state.form.startTime}};
   task=independent?await addFarmEvidence(task.id,evidence):await greenhouse.addEvidence(evidence);lastEvidenceId=evidence.id!;lastEvidenceTask=task;emit('evidence',task);
  }
  if(handoff){
   emit('handoff',task);
   if(!props.embedded)await router.push({path:'/agentChat',query:{...(independent?{taskId:task.id}:greenhouse.linkedQuery()),crop,detection:labels.slice(0,3).join('、'),score:formatConfidenceArray(state.predictionResult.confidence).slice(0,3).join('、')}});
  }
 }catch(e){ElMessage.error(e instanceof Error?e.message:'识别证据保存失败，请重试交接');}finally{evidenceSaving=false;}
}
const reviewPrediction=()=>savePrediction(true);

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
	background: #98553d;
	border: 1px solid #98553d;
	box-shadow: none;
	transition: background .18s ease;


	&:hover {
		background: #7c432f;
		border-color: #7c432f;
	}
}
.page-intro { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; padding: 4px 2px 17px; }
.page-intro .eyebrow { margin:0 0 5px; color:#887564; font-size:10px; font-weight:700; letter-spacing:.12em; }
.page-intro h1 { margin:0; color:#382b25; font:600 25px/1.3 Georgia,'Songti SC',serif; }
.page-intro p:last-child { margin:6px 0 0; color:#756b62; font-size:13px; line-height:1.65; }
.source-note { display:flex; align-items:center; gap:8px; padding:8px 11px; border:1px solid #eadbc8; color:#755740; background:#f7efe4; font-size:12px; white-space:nowrap; }
.source-dot { width:7px; height:7px; border-radius:50%; background:#b06b45; }
.header { height:auto; min-height:72px; align-content:center; background:#fdfbf7; border:1px solid #e9e0d5; border-radius:3px; box-shadow:none; }
.control-caption { display:flex; align-items:center; gap:8px; padding:0 14px 0 2px; margin-right:2px; border-right:1px solid #e5dbcf; color:#514137; font-size:13px; white-space:nowrap; }
.control-caption span { color:#a46a4d; font:600 12px Georgia,serif; }
.image-display { margin-top:14px; }
.image-display .card { border:1px solid #e7ded3; border-radius:3px; background:#fffdf9; }
.image-display .card :deep(.el-card__body) { padding:16px; }
.image-display .image-title { display:flex; align-items:center; justify-content:space-between; color:#49372c; border-color:#ece3d8; font-size:13px; }
.image-display .image-title small { color:#988b7e; font-size:11px; font-weight:400; }
.image-display .avatar-uploader { border-color:#d8cabc; border-radius:2px; background:#f9f6ef; }
.image-display .upload-icon { color:#a56c4d; }
.image-display .upload-text { color:#76685d; }
.image-display .placeholder { background:#f8f5ee; color:#8d8075; }
.review-action { border:1px solid #e8ded2; background:#f9f5ed; color:#675448; }
.result-section :deep(.el-card) { border:1px solid #e8ded2; border-radius:3px; background:#fffdf9; }
.result-section .bottom .result-column .result-title { color:#75665a; }
.result-section .bottom .result-column .result-value { color:#88523a; }
@media (max-width: 900px) { .page-intro { align-items:flex-start; flex-direction:column; }.control-caption { width:100%; padding:0 0 10px; border-right:0; border-bottom:1px solid #e5dbcf; } }
.embedded{height:auto!important;max-height:72vh;overflow:auto;padding:0!important}.embedded .system-predict-padding{min-height:0!important;padding:0!important}.embedded .image-display .preview-image,.embedded .image-display .avatar-uploader,.embedded .image-display .placeholder{height:260px}.embedded .header{flex-wrap:wrap;gap:10px}.embedded .source-note{display:none}
</style>
