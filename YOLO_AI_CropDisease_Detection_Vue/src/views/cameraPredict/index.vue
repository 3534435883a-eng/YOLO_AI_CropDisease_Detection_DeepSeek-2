<template>
	<div class="system-predict-container layout-padding">
		<div class="system-predict-padding layout-padding-auto layout-padding-view">
			<DetectionNav mode="camera" view="detect" />
			<div class="page-intro"><div><p class="eyebrow">OBSERVE / CAMERA</p><h1>实时画面观察</h1><p>连接摄像画面并运行现有检测服务；检测类别仍需人工结合知识依据核对。</p></div><div class="source-note"><span class="source-dot" :class="{ 'is-live': state.cameraisShow }"></span>{{ state.cameraisShow ? '摄像画面处理中' : '摄像画面未启动' }}</div></div>
			<div class="header">
				<div class="control-caption"><span>01</span><b>检测设置</b></div>
				<div class="kind">
					<el-select v-model="kind" placeholder="请选择作物种类" size="large" style="width: 180px" @change="getData">
						<el-option v-for="item in state.kind_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="weight">
					<el-select v-model="weight" placeholder="请选择模型" size="large" style="margin-left: 20px;width: 180px">
						<el-option v-for="item in state.weight_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="conf" style="margin-left: 20px;display: flex; flex-direction: row;">
					<div
						style="font-size: 14px;margin-right: 20px;display: flex;justify-content: start;align-items: center;color: #909399;">
						设置最小置信度阈值</div>
					<el-slider v-model="conf" :format-tooltip="formatTooltip" style="width: 280px;" />
				</div>
				<div class="button-section" style="margin-left: 20px">
					<el-button type="primary" @click="start" class="predict-button">开始录制</el-button>
				</div>
                <div class="button-section" style="margin-left: 20px">
					<el-button type="primary" @click="stop" class="predict-button">结束录制</el-button>
				</div>
				<div class="demo-progress" v-if="state.isShow">
					<el-progress :text-inside="true" :stroke-width="20" :percentage=state.percentage style="width: 380px;">
						<span>{{ state.type_text }} {{ state.percentage }}%</span>
					</el-progress>
				</div>
			</div>
			<div class="cards" ref="cardsContainer">
				<div v-if="!state.cameraisShow" class="camera-empty"><span class="camera-mark">◉</span><b>摄像画面未连接</b><small>设置作物与检测模型后，可在此启动现有摄像检测服务</small></div>
				<img v-if="state.cameraisShow" class="video" :src="state.video_path">
			</div>
		</div>
	</div>
</template>


<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import request from '/@/utils/request';
import { useUserInfo } from '/@/stores/userInfo';
import { storeToRefs } from 'pinia';
import type { UploadInstance, UploadProps } from 'element-plus';
import { SocketService } from '/@/utils/socket';
import { formatDate } from '/@/utils/formatTime';
import DetectionNav from '/@/components/detectionNav/index.vue';

const stores = useUserInfo();
const conf = ref('');
const kind = ref('');
const weight = ref('');
const { userInfos } = storeToRefs(stores);

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
	data: {} as any,
	video_path: '',
	type_text: "正在保存",
	percentage: 50,
	isShow: false,
	cameraisShow: false,
	form: {
		username: '',
		weight: '',
		conf: null as any,
		kind: '',
		startTime: ''
	},
});

const socketService = new SocketService();

socketService.on('message', (data) => {
	console.log('Received message:', data);
	ElMessage.success(data);
});

const formatTooltip = (val: number) => {
	return val / 100
}

socketService.on('progress', (data) => {
	state.percentage = parseInt(data);
	if (parseInt(data) < 100) {
		state.isShow = true;
	} else {
		//两秒后隐藏进度条
		ElMessage.success("保存成功！");
		setTimeout(() => {
			state.isShow = false;
			state.percentage = 0;
		}, 2000);
	}
	console.log('Received message:', data);
});

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


const start = () => {
	state.form.weight = weight.value;
	state.form.kind = kind.value;
	state.form.conf = (parseFloat(conf.value)/100);
	state.form.username = userInfos.value.userName;
	state.form.startTime = formatDate(new Date(), 'YYYY-mm-dd HH:MM:SS');
	console.log(state.form);
	const queryParams = new URLSearchParams(state.form).toString();
	state.cameraisShow = true
	state.video_path = `http://127.0.0.1:5000/predictCamera?${queryParams}`;
};

const stop = () => {
	request.get('/flask/stopCamera').then((res) => {
		if (res.code == 0) {
			res.data = JSON.parse(res.data);
			console.log(res.data);
			state.weight_items = res.data.weight_items;
		} else {
			ElMessage.error(res.msg);
		}
	});
	state.cameraisShow = false
};

onMounted(() => {
	getData();
});
</script>

<style scoped lang="scss">
.system-predict-container {
	width: 100%;
	height: 100%;
	display: flex;
	flex-direction: column;

	.system-predict-padding {
		padding: 15px;

		.el-table {
			flex: 1;
		}
	}
}

.page-intro { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; padding:4px 2px 17px; }
.page-intro .eyebrow { margin:0 0 5px; color:#887564; font-size:10px; font-weight:700; letter-spacing:.12em; }
.page-intro h1 { margin:0; color:#382b25; font:600 25px/1.3 Georgia,'Songti SC',serif; }
.page-intro p:last-child { margin:6px 0 0; color:#756b62; font-size:13px; line-height:1.65; }
.source-note { display:flex; align-items:center; gap:8px; padding:8px 11px; border:1px solid #eadbc8; color:#755740; background:#f7efe4; font-size:12px; white-space:nowrap; }
.source-dot { width:7px; height:7px; border-radius:50%; background:#a99b8d; }
.source-dot.is-live { background:#7b8d4b; }

.header {
	width: 100%;
	height: auto;
	min-height: 72px;
	display: flex;
	justify-content: start;
	align-items: center;
	font-size: 20px;
	align-content: center;
	flex-wrap: wrap;
	gap: 12px;
	padding: 12px;
	background: #fdfbf7;
	border: 1px solid #e9e0d5;
	border-radius: 3px;
}
.control-caption { display:flex; align-items:center; gap:8px; padding:0 12px 0 2px; border-right:1px solid #e5dbcf; color:#514137; font-size:13px; white-space:nowrap; }
.control-caption span { color:#a46a4d; font:600 12px Georgia,serif; }

.cards {
	width: 100%;
	height: calc(100vh - 330px);
	min-height: 360px;
	border: 1px solid #e4dbcf;
	border-radius: 3px;
	margin-top: 14px;
	padding: 20px;
	background: #f7f3eb;
	overflow: hidden;
	display: flex;
	justify-content: center;
	align-items: center;
	/* 防止视频溢出 */
}

.video {
	width: 100%;
	max-height: 100%;
	/* 限制视频最大高度不超过父元素高度 */
	height: auto;
	object-fit: contain;
}
.camera-empty { display:flex; flex-direction:column; align-items:center; gap:9px; color:#55473c; text-align:center; }
.camera-empty small { color:#8d8176; font-size:12px; }
.camera-mark { display:grid; width:48px; height:48px; place-items:center; border:1px solid #dfd2c3; border-radius:50%; color:#9b5d41; background:#fffdf9; font-size:21px; }
.predict-button { background:#98553d; border-color:#98553d; box-shadow:none; }
.predict-button:hover { background:#7c432f; border-color:#7c432f; }

.button-section {
	display: flex;
	justify-content: center;
}

.predict-button {
	width: 100%;
	/* 按钮宽度填满 */
}

.demo-progress .el-progress--line {
	margin-left: 20px;
	width: 600px;
}
@media(max-width:900px) { .page-intro { align-items:flex-start; flex-direction:column; }.control-caption { width:100%; padding-bottom:10px; border-right:0; border-bottom:1px solid #e5dbcf; }.cards { height:auto; min-height:340px; } }
</style>
