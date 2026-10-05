<template>
	<el-menu
		router
		:default-active="state.defaultActive"
		background-color="transparent"
		:collapse="state.isCollapse"
		:unique-opened="getThemeConfig.isUniqueOpened"
		:collapse-transition="false"
	>
		<template v-for="val in menuLists">
			<el-sub-menu :index="val.path" v-if="val.children && val.children.length > 0" :key="val.path">
				<template #title>
					<SvgIcon :name="val.meta.icon" />
					<span>{{ $t(val.meta.title) }}</span>
				</template>
				<SubItem :chil="val.children" />
			</el-sub-menu>
			<template v-else>
				<el-menu-item :index="val.path" :key="val.path">
					<SvgIcon :name="val.meta.icon" />
					<template #title v-if="!val.meta.isLink || (val.meta.isLink && val.meta.isIframe)">
						<span>{{ $t(val.meta.title) }}</span>
					</template>
					<template #title v-else>
						<a class="w100" @click.prevent="onALinkClick(val)">{{ $t(val.meta.title) }}</a>
					</template>
				</el-menu-item>
			</template>
		</template>
	</el-menu>
</template>

<script setup lang="ts" name="navMenuVertical">
import { defineAsyncComponent, reactive, computed, onMounted, watch } from 'vue';
import { useRoute, onBeforeRouteUpdate, RouteRecordRaw } from 'vue-router';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';
import other from '/@/utils/other';

// 引入组件
const SubItem = defineAsyncComponent(() => import('/@/layout/navMenu/subItem.vue'));

// 定义父组件传过来的值
const props = defineProps({
	// 菜单列表
	menuList: {
		type: Array<RouteRecordRaw>,
		default: () => [],
	},
});

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);
const route = useRoute();
const state = reactive({
	// 修复：https://gitee.com/lyt-top/vue-next-admin/issues/I3YX6G
	defaultActive: route.meta.isDynamic ? route.meta.isDynamicPath : route.path,
	isCollapse: false,
});

// 获取父级菜单数据
const menuLists = computed(() => {
	return <RouteItems>props.menuList;
});
// 获取布局配置信息
const getThemeConfig = computed(() => {
	return themeConfig.value;
});
// 菜单高亮（详情时，父级高亮）
const setParentHighlight = (currentRoute: RouteToFrom) => {
	const { path, meta } = currentRoute;
	const pathSplit = meta?.isDynamic ? meta.isDynamicPath!.split('/') : path!.split('/');
	if (pathSplit.length >= 4 && meta?.isHide) return pathSplit.splice(0, 3).join('/');
	else return path;
};
// 打开外部链接
const onALinkClick = (val: RouteItem) => {
	other.handleOpenLink(val);
};
// 页面加载时
onMounted(() => {
	state.defaultActive = setParentHighlight(route);
});
// 路由更新时
onBeforeRouteUpdate((to) => {
	// 修复：https://gitee.com/lyt-top/vue-next-admin/issues/I3YX6G
	state.defaultActive = setParentHighlight(to);
	const clientWidth = document.body.clientWidth;
	if (clientWidth < 1000) themeConfig.value.isCollapse = false;
});
// 设置菜单的收起/展开
watch(
	themeConfig.value,
	() => {
		document.body.clientWidth <= 1000 ? (state.isCollapse = false) : (state.isCollapse = themeConfig.value.isCollapse);
	},
	{
		immediate: true,
	}
);
</script>

<style scoped lang="scss">
:deep(.el-menu) {
	width: 220px;
	padding: 9px 0 18px;
	background: transparent;
	.el-menu-item,
	.el-sub-menu__title {
		position: relative;
		display: flex;
		align-items: center;
		gap: 12px;
		min-height: 44px;
		height: auto !important;
		margin: 2px 10px;
		padding: 0 12px !important;
		border-radius: 5px;
		color: #e5d9c8;
		font-size: 13px;
		line-height: 1.35 !important;
		transition: background-color 140ms ease, color 140ms ease;
	}
	.el-menu-item .svg-icon,
	.el-sub-menu__title .svg-icon { color: #c4ad91; font-size: 16px; }
	.el-sub-menu { margin-top: 10px; }
	.el-sub-menu:first-child { margin-top: 0; }
	.el-sub-menu__title { color: #f0e6d7; font-weight: 600; }
	.el-menu-item:hover,
	.el-sub-menu__title:hover { color: #fffaf1; background: rgb(255 248 237 / 9%) !important; }
	.el-menu-item.is-active {
		color: #fff8ed !important;
		background: var(--agri-terracotta) !important;
		font-weight: 600;
		.svg-icon { color: #fff0d9; }
	}
	.el-menu--inline {
		margin: 3px 0 7px;
		padding: 2px 0;
		border-left: 1px solid rgb(214 193 164 / 24%);
		margin-left: 28px;
		.el-menu-item { min-height: 39px; margin: 1px 8px 1px 0; padding-left: 14px !important; color: #d5c7b4; font-size: 12px; }
	}
	.el-sub-menu__icon-arrow { color: #bca990; }
	&.el-menu--collapse { width: 64px; }
	&.el-menu--collapse > .el-menu-item,
	&.el-menu--collapse > .el-sub-menu > .el-sub-menu__title { justify-content: center; margin: 4px 8px; padding: 0 !important; }
}
</style>
