<template>
	<nav class="detection-nav" aria-label="病害识别方式">
		<div class="nav-title"><h2>病害识别</h2><span>{{ view === 'history' ? '识别记录' : '模型检测' }}</span></div>
		<el-radio-group :model-value="mode" size="default" @change="switchMode">
			<el-radio-button label="image">图片</el-radio-button>
			<el-radio-button label="video">视频</el-radio-button>
			<el-radio-button label="camera">摄像</el-radio-button>
		</el-radio-group>
		<el-button v-if="view === 'detect'" :icon="Clock" @click="openHistory">查看记录</el-button>
		<el-button v-else :icon="ArrowLeft" @click="openDetect">返回检测</el-button>
	</nav>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { ArrowLeft, Clock } from '@element-plus/icons-vue';

type DetectionMode = 'image' | 'video' | 'camera';
const props = defineProps<{ mode: DetectionMode; view: 'detect' | 'history' }>();
const router = useRouter();
const paths: Record<DetectionMode, { detect: string; history: string }> = {
	image: { detect: '/imgPredict', history: '/imgRecord' },
	video: { detect: '/videoPredict', history: '/videoRecord' },
	camera: { detect: '/cameraPredict', history: '/cameraRecord' },
};
const switchMode = (value: string | number | boolean) => {
	if (value === 'image' || value === 'video' || value === 'camera') void router.push(paths[value][props.view]);
};
const openHistory = () => { void router.push(paths[props.mode].history); };
const openDetect = () => { void router.push(paths[props.mode].detect); };
</script>

<style scoped>
.detection-nav { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 13px 16px; margin-bottom: 14px; background: #fff; border-bottom: 1px solid #e1e8e3; }
.nav-title { margin-right: auto; }
.nav-title h2 { margin: 0; color: #26382e; font-size: 16px; }
.nav-title span { color: #748078; font-size: 12px; }
@media (max-width: 650px) { .detection-nav { gap: 10px; }.nav-title { flex: 0 0 100%; } }
</style>
