<template>
	<nav class="detection-nav" aria-label="病害识别工作区">
		<div class="nav-title">
			<p class="eyebrow">FIELD OBSERVATION</p>
			<div class="title-line"><h2>病害识别</h2><span class="view-state">{{ view === 'history' ? '识别记录' : '候选观察信号' }}</span></div>
		</div>
		<div class="mode-block">
			<span class="mode-label">输入方式</span>
			<el-radio-group :model-value="mode" size="default" @change="switchMode">
			<el-radio-button label="image">图片</el-radio-button>
			<el-radio-button label="video">视频</el-radio-button>
			<el-radio-button label="camera">摄像</el-radio-button>
			</el-radio-group>
		</div>
		<el-button v-if="view === 'detect'" class="nav-action" :icon="Clock" @click="openHistory">识别记录</el-button>
		<el-button v-else class="nav-action" :icon="ArrowLeft" @click="openDetect">返回检测</el-button>
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

<style scoped lang="scss">
.detection-nav { display: flex; align-items: center; gap: 22px; flex-wrap: wrap; padding: 18px 22px; margin-bottom: 16px; border: 1px solid #e8dfd3; border-radius: 4px; background: #fbf8f1; }
.nav-title { margin-right: auto; }
.eyebrow { margin: 0 0 5px; color: #8a7665; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.title-line { display: flex; align-items: center; gap: 10px; }
.nav-title h2 { margin: 0; color: #382b25; font: 600 22px/1.2 Georgia, 'Songti SC', serif; }
.view-state { padding: 4px 8px; border: 1px solid #e4d7c6; border-radius: 2px; color: #765a49; background: #f5eee4; font-size: 11px; }
.mode-block { display: flex; align-items: center; gap: 10px; }
.mode-label { color: #887b70; font-size: 12px; }
.nav-action { border-color: #dfcfc0; color: #654a3b; background: transparent; }
.nav-action:hover { border-color: #ab6b4e; color: #8d4f35; background: #f7eee7; }
:deep(.el-radio-button__inner) { border-color: #e4d8c8; color: #62564d; box-shadow: none !important; }
:deep(.el-radio-button:first-child .el-radio-button__inner) { border-left-color: #e4d8c8; }
:deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { border-color: #9a563d; background: #9a563d; box-shadow: -1px 0 0 0 #9a563d; color: #fffaf4; }
@media (max-width: 650px) { .detection-nav { gap: 12px; padding: 14px; }.nav-title { flex: 0 0 100%; }.mode-block { flex: 1; justify-content: space-between; } }
</style>
