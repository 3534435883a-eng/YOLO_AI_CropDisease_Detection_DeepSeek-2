import { GreenhouseTwin, type TwinFrameState } from './scene';

const canvas = document.querySelector<HTMLCanvasElement>('#viewport')!;
const panel = document.querySelector<HTMLElement>('#panel')!;
const status = document.querySelector<HTMLElement>('#status')!;
const selected = document.querySelector<HTMLElement>('#selected')!;
const metrics = document.querySelector<HTMLElement>('#metrics')!;
const togglePanel = document.querySelector<HTMLButtonElement>('#toggle-panel')!;
const autoSync = document.querySelector<HTMLInputElement>('#auto-sync')!;
const demoDevices = document.querySelector<HTMLInputElement>('#demo-devices')!;

const twin = new GreenhouseTwin(canvas, document.body);
twin.setQuality('high');
twin.setAutoRotate(false);
twin.setCameraPreset('overview');

const frame: TwinFrameState = {
	lai: 3.1,
	plantHeightCm: 220,
	fruitCount: 22,
	singleFruitWeightG: 120,
	fruitSetRate: 0.74,
	mature: true,
	ripeness: 0.62,
	lightPpfd: 690,
	irrigation: false,
	ventilation: false,
	supplementalLight: false,
	shade: false,
	co2: false,
	circulationFan: false,
	exhaustFan: false,
	coolingPad: false,
	roofVent: false,
	temperatureC: 25,
	airHumidityPct: 68,
	co2Ppm: 620,
	soilMoisturePct: 56,
	severity: { BOTRYTIS: 0, LATE_BLIGHT: 0, POWDERY_MILDEW: 0, LEAF_MOLD: 0 },
	hour: 10.5,
	dayOfYear: 264,
};
twin.applyState(frame);
twin.onInspect((entry) => {
	selected.textContent = entry ? `${entry.name} · ${entry.code}` : '未选择设备';
});

function selectButton(containerId: string, attribute: string, selectedValue: string) {
	document.querySelectorAll<HTMLButtonElement>(`#${containerId} button`).forEach((button) => {
		button.classList.toggle('active', button.dataset[attribute] === selectedValue);
	});
}

document.querySelectorAll<HTMLButtonElement>('#presets button').forEach((button) => {
	button.addEventListener('click', () => twin.setCameraPreset(button.dataset.preset as 'overview' | 'closeup' | 'top' | 'fruit'));
});
document.querySelectorAll<HTMLButtonElement>('#navigation button').forEach((button) => {
	button.addEventListener('click', () => {
		const mode = button.dataset.navigation as 'orbit' | 'fly';
		twin.setNavigationMode(mode);
		selectButton('navigation', 'navigation', mode);
	});
});
document.querySelectorAll<HTMLButtonElement>('#shell-modes button').forEach((button) => {
	button.addEventListener('click', () => {
		const mode = button.dataset.shell as 'solid' | 'translucent' | 'cutaway';
		twin.setShellMode(mode);
		selectButton('shell-modes', 'shell', mode);
	});
});
togglePanel.addEventListener('click', () => {
	panel.hidden = !panel.hidden;
	togglePanel.setAttribute('aria-expanded', String(!panel.hidden));
});
demoDevices.addEventListener('change', () => {
	const active = demoDevices.checked;
	frame.irrigation = active;
	frame.supplementalLight = active;
	frame.circulationFan = active;
	frame.exhaustFan = active;
	frame.coolingPad = active;
	frame.roofVent = active;
	frame.ventilation = active;
	frame.shade = active;
	frame.co2 = false;
	frame.hour = active ? 19 : 10.5;
	twin.applyState(frame);
});

const assetPaths = ['greenhouse-structure.glb', 'greenhouse-equipment.glb'];
let assetSignature = '';
let checking = false;
async function checkAssets() {
	if (!autoSync.checked || checking || document.hidden) return;
	checking = true;
	try {
		const responses = await Promise.all(assetPaths.map((name) => fetch(`/models/greenhouse/${name}`, { method: 'HEAD', cache: 'no-store' })));
		if (responses.some((response) => !response.ok)) throw new Error('模型文件不可用');
		const signature = responses.map((response) => `${response.headers.get('etag')}:${response.headers.get('last-modified')}:${response.headers.get('content-length')}`).join('|');
		if (assetSignature && assetSignature !== signature) {
			status.textContent = '检测到新导出模型，正在刷新';
			location.reload();
			return;
		}
		assetSignature = signature;
		const assetState = twin.getAssetStatus();
		status.textContent = `${assetState === 'blender' ? '已加载 Blender 模型' : assetState === 'fallback' ? '旧模型回退' : '正在加载 Blender 模型'} · ${new Date().toLocaleTimeString('zh-CN')}`;
	} catch (error) {
		status.textContent = `同步失败 · ${String(error)}`;
	} finally {
		checking = false;
	}
}
void checkAssets();
const syncTimer = window.setInterval(() => void checkAssets(), 3000);
const statsTimer = window.setInterval(() => {
	const stats = twin.getStats();
	metrics.textContent = `${stats.fps.toFixed(0)} fps · ${stats.drawCalls} draw calls · ${(stats.triangles / 1000).toFixed(0)}k triangles`;
}, 1000);
window.addEventListener('resize', () => twin.resize());
window.addEventListener('beforeunload', () => {
	window.clearInterval(syncTimer);
	window.clearInterval(statsTimer);
	twin.dispose();
});
