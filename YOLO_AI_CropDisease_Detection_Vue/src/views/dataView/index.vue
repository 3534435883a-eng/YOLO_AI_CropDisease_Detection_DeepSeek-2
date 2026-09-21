<template>
	<div class="data-view-page">
		<iframe id="fream" src="/data/index.html" scrolling="auto" frameborder="0" title="农业数据大屏"></iframe>
		<section v-if="agentStore.hasActiveRun" class="agent-overlay">
			<div>
				<p>番茄温室智能体</p>
				<strong>{{ statusLabel }}</strong>
				<span>{{ strategySummary }}</span>
			</div>
			<el-button size="small" type="primary" @click="router.push('/agentCenter')">查看</el-button>
		</section>
	</div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAgentRunStore } from '/@/stores/agentRun';

const router = useRouter();
const agentStore = useAgentRunStore();
const statusLabel = computed(() => String(agentStore.activeRun?.status || agentStore.summary?.status || 'PAUSED') === 'RUNNING' ? '模拟运行中' : '模拟已暂停');
const strategySummary = computed(() => String(agentStore.summary?.strategySummary || agentStore.summary?.strategy || '规则推演已接管环境策略。'));

onMounted(() => agentStore.loadActiveRun());
</script>

<style scoped>
.data-view-page { position: relative; width: 100%; }
iframe { display: block; width: 100%; height: 100vh; }
.agent-overlay {
	position: absolute;
	top: 18px;
	right: 18px;
	display: flex;
	align-items: center;
	gap: 18px;
	max-width: min(520px, calc(100% - 36px));
	padding: 12px 14px;
	border: 1px solid rgba(132, 221, 160, .62);
	border-radius: 7px;
	background: rgba(8, 26, 18, .9);
	color: #edf8ef;
}
.agent-overlay p { margin: 0 0 3px; color: #a2dcae; font-size: 12px; }
.agent-overlay strong { display: block; margin-bottom: 3px; font-size: 14px; }
.agent-overlay span { display: block; overflow: hidden; max-width: 380px; color: #c9d9ce; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 640px) { .agent-overlay { top: 10px; right: 10px; gap: 10px; max-width: calc(100% - 20px); } .agent-overlay span { max-width: 180px; } }
</style>
