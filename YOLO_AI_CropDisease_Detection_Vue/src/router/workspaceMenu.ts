import { RouteRecordRaw } from 'vue-router';

/** The menu mirrors the existing page routes without changing their URLs. */
export function buildWorkspaceMenu(routes: RouteRecordRaw[]): RouteRecordRaw[] {
	const byPath = new Map<string, RouteRecordRaw>();
	const indexRoutes = (items: RouteRecordRaw[]) => {
		items.forEach((route) => {
			byPath.set(route.path, route);
			if (route.children) indexRoutes(route.children as RouteRecordRaw[]);
		});
	};
	indexRoutes(routes);

	const entries = (paths: string[]) => paths.map((path) => byPath.get(path)).filter((route): route is RouteRecordRaw => Boolean(route));
	const group = (path: string, title: string, icon: string, paths: string[]): RouteRecordRaw | null => {
		const children = entries(paths);
		return children.length
			? { path, redirect: children[0].path, meta: { title, icon }, children }
			: null;
	};

	return [
		...entries(['/homePage']),
		group('/diseaseWorkspace', '病害识别', 'iconfontjs icon-tpjc', [
			'/imgPredict', '/videoPredict', '/cameraPredict', '/imgRecord', '/videoRecord', '/cameraRecord',
		]),
		group('/decisionWorkspace', '决策与规划', 'iconfontjs icon-znwd', ['/agentChat', '/agentSimulation']),
		group('/greenhouseWorkspace', '温室推演', 'iconfontjs icon-znws', [
			'/agentCenter', '/digitalTwin', '/modelCalibration', '/detailsEnv',
		]),
		group('/knowledgeWorkspace', '知识与数据', 'iconfontjs icon-bingchonghai-1haichong', ['/infoDisease', '/visionCoverage', '/referenceLibrary']),
		group('/systemWorkspace', '系统管理', 'iconfontjs icon-yh', ['/purchaseManage', '/storageManage', '/usermanage', '/personal']),
	].filter((route): route is RouteRecordRaw => Boolean(route));
}
