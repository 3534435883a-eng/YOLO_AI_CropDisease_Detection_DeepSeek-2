<template>
	<div class="layout-navbars-container">
		<div class="header-row"><BreadcrumbIndex /><div class="scene-context"><span>模拟场景</span><strong>Horti-M3 · 番茄</strong></div></div>
		<TagsView v-if="setShowTagsView" />
	</div>
</template>

<script setup lang="ts" name="layoutNavBars">
import { defineAsyncComponent, computed } from 'vue';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';

// 引入组件
const BreadcrumbIndex = defineAsyncComponent(() => import('/@/layout/navBars/breadcrumb/index.vue'));
const TagsView = defineAsyncComponent(() => import('/@/layout/navBars/tagsView/tagsView.vue'));

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);

// 是否显示 tagsView
const setShowTagsView = computed(() => {
	let { layout, isTagsview } = themeConfig.value;
	return layout !== 'classic' && isTagsview;
});
</script>

<style scoped lang="scss">
.layout-navbars-container {
	display: flex;
	flex-direction: column;
	width: 100%;
	height: 100%;
	background: var(--agri-surface);
	.header-row { display: flex; align-items: center; min-height: 58px; padding-right: 22px; border-bottom: 1px solid var(--agri-line); }
	.header-row :deep(.layout-navbars-breadcrumb-index) { flex: 1; min-width: 0; width: auto; }
	.scene-context { display: flex; align-items: center; gap: 10px; flex: 0 0 auto; padding-left: 18px; color: var(--agri-muted); font-size: 12px; }
	.scene-context strong { color: var(--agri-ink); font-size: 13px; font-weight: 600; }
}

@media (max-width: 1000px) { .layout-navbars-container .scene-context { display: none; } }
</style>
