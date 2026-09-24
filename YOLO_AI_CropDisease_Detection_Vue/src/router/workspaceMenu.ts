import { RouteRecordRaw } from 'vue-router';

// The menu follows the user's workflow; route registration retains every legacy URL.
export function buildWorkspaceMenu(routes: RouteRecordRaw[]): RouteRecordRaw[] {
	const byPath = new Map(routes.map((route) => [route.path, route]));
	const detection = byPath.get('/diseaseDetection');
	const detectionChildren = new Map((detection?.children || []).map((route) => [route.path, route]));
	const get = (path: string) => byPath.get(path) || detectionChildren.get(path);
	const children = (paths: string[]) => paths.map(get).filter((route): route is RouteRecordRaw => Boolean(route));
	const group = (path: string, title: string, icon: string, paths: string[]): RouteRecordRaw => ({
		path,
		redirect: paths[0],
		meta: { title, icon, roles: ['admin', 'common', 'others'] },
		children: children(paths),
	});

	return [
		...children(['/homePage']),
		...children(['/imgPredict']),
		...children(['/agentChat']),
		group('/greenhouseWorkspace', '温室推演', 'iconfontjs icon-znws', [
			'/agentCenter', '/digitalTwin', '/detailsEnv', '/infoGreenhouse',
		]),
		group('/knowledgeWorkspace', '知识与数据', 'iconfontjs icon-bingchonghai-1haichong', [
			'/infoDisease', '/visionCoverage', '/dataView',
		]),
		group('/systemWorkspace', '系统管理', 'iconfontjs icon-yh', [
			'/purchaseManage', '/storageManage', '/usermanage', '/personal',
		]),
	];
}
