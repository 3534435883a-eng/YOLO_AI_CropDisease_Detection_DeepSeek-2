<template>
	<aside v-if="agentStore.hasActiveRun" class="agent-run-indicator" aria-label="番茄温室智能体运行状态">
		<div class="indicator-heading">
			<span class="indicator-dot" :class="statusClass"></span>
			<span>番茄智能体</span>
			<el-tag size="small" effect="plain" :type="tagType">{{ statusLabel }}</el-tag>
		</div>
		<div class="indicator-metrics">
			<span>{{ temperature }}</span>
			<span>{{ riskLabel }}</span>
		</div>
		<el-button link type="primary" @click="router.push('/agentCenter')">查看指挥中心</el-button>
	</aside>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAgentRunStore } from '/@/stores/agentRun';

const router = useRouter();
const agentStore = useAgentRunStore();
let refreshTimer: number | undefined;

const asRecord = (value: unknown): Record<string, unknown> => {
	return value && typeof value === 'object' ? value as Record<string, unknown> : {};
};

const state = computed(() => asRecord(agentStore.summary?.currentState || agentStore.summary?.state));
const status = computed(() => String(agentStore.activeRun?.status || agentStore.summary?.status || 'PAUSED').toUpperCase());
const statusLabel = computed(() => ({ RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '已完成' }[status.value] || '模拟待命'));
const tagType = computed(() => status.value === 'RUNNING' ? 'success' : status.value === 'PAUSED' ? 'warning' : 'info');
const statusClass = computed(() => status.value === 'RUNNING' ? 'is-running' : 'is-paused');
const temperature = computed(() => {
	const raw = state.value.temperatureC ?? state.value.temperature ?? '--';
	return raw === '--' ? '温度 --' : `温度 ${Number(raw).toFixed(1)} C`;
});
const riskLabel = computed(() => {
	const raw = state.value.riskLevel ?? state.value.risk_level ?? '--';
	return `风险 ${raw}`;
});

onMounted(() => {
	agentStore.loadActiveRun();
	refreshTimer = window.setInterval(() => agentStore.loadActiveRun(), 5000);
});

onUnmounted(() => {
	if (refreshTimer !== undefined) window.clearInterval(refreshTimer);
});
</script>

<style scoped lang="scss">
.agent-run-indicator {
	position: fixed;
	right: 18px;
	bottom: 18px;
	z-index: 40;
	width: min(280px, calc(100vw - 36px));
	padding: 12px 14px;
	border: 1px solid #cfe4d5;
	border-radius: 8px;
	background: #ffffff;
	box-shadow: 0 8px 22px rgba(24, 51, 35, 0.14);
	color: #1d2b24;
}

.indicator-heading,
.indicator-metrics {
	display: flex;
	align-items: center;
	gap: 8px;
}

.indicator-heading {
	font-size: 14px;
	font-weight: 600;
}

.indicator-metrics {
	justify-content: space-between;
	margin: 8px 0 4px;
	font-size: 12px;
	color: #56655c;
}

.indicator-dot {
	width: 8px;
	height: 8px;
	border-radius: 50%;
	background: #9aa6a0;
}

.indicator-dot.is-running {
	background: #2d8a54;
	box-shadow: 0 0 0 3px rgba(45, 138, 84, 0.15);
}

.indicator-dot.is-paused {
	background: #c68a25;
}

@media (max-width: 640px) {
	.agent-run-indicator {
		right: 12px;
		bottom: 12px;
		width: min(260px, calc(100vw - 24px));
	}
}
</style>
