<template>
	<div class="layout-logo" v-if="setShowLogo" @click="onThemeConfigChange">
		<div class="brand-mark" aria-hidden="true">禾</div>
		<div class="brand-copy"><strong>禾序</strong><span>农业智能体</span></div>
	</div>
	<div class="layout-logo-size" v-else @click="onThemeConfigChange">
		<div class="brand-mark" aria-label="禾序">禾</div>
	</div>
</template>

<script setup lang="ts" name="layoutLogo">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';
// import logoMini from '/@/assets/logo-mini.svg';

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);

// 设置 logo 的显示。classic 经典布局默认显示 logo
const setShowLogo = computed(() => {
	let { isCollapse, layout } = themeConfig.value;
	return !isCollapse || layout === 'classic' || document.body.clientWidth < 1000;
});
// logo 点击实现菜单展开/收起
const onThemeConfigChange = () => {
	if (themeConfig.value.layout === 'transverse') return false;
	themeConfig.value.isCollapse = !themeConfig.value.isCollapse;
};
</script>

<style scoped lang="scss">
.layout-logo {
	width: 220px;
	height: 76px;
	display: flex;
	align-items: center;
	justify-content: flex-start;
	gap: 11px;
	padding: 0 22px;
	border-bottom: 1px solid #ded3c2;
	color: #493e33;
	cursor: pointer;
	.brand-mark { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid #9b8668; color: #96462f; font-family: Georgia, serif; font-size: 20px; }
	.brand-copy { display: flex; flex-direction: column; gap: 2px; }
	.brand-copy strong { font-family: "Noto Serif SC", "Songti SC", serif; font-size: 19px; font-weight: 600; letter-spacing: 0.08em; }
	.brand-copy span { color: #786b5b; font-size: 11px; letter-spacing: 0.08em; }
}
.layout-logo-size {
	width: 100%;
	height: 76px;
	display: flex;
	align-items: center;
	justify-content: center;
	cursor: pointer;
	.brand-mark { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid #9b8668; color: #96462f; font-family: Georgia, serif; font-size: 20px; }
}
</style>
