/**
 * 番茄温室数字孪生场景（Three.js）
 *
 * 棚体和设备采用参数化几何；地表使用项目内随附的 CC0 PBR 贴图。
 * 数据映射：
 *   lai / plantHeightCm / fruitCount / singleFruitWeightG / fruitSetRate / mature → 番茄冠层（见 plants.ts）
 *   devices.*        → 侧窗开合、遮阳幕滑动、风机转速、滴灌水滴、补光灯与光源、CO₂ 管路
 *   severity.*       → 4 种病害的半透明光晕 + 粒子（灰霉=灰雾 / 晚疫=暗水渍 / 白粉=白粉 / 叶霉=黄绿斑）
 *                       注意：severity 是 0~100 的百分比量纲，绘制前需 /100 归一到 0~1
 *   lightPpfd        → 日照强度系数（阴天整体变暗）
 *   时/日（simulatedAt）→ 太阳方位、色温、天空渐变、雾色、夜间灯光
 */
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { FreeCameraControls } from './freeCamera';
import { GREENHOUSE_LAYOUT, roofHeightAt } from './greenhouseLayout';
import { buildEquipment, type EquipmentEntry } from './equipment';
import { buildSiteDetails } from './siteDetails';
import { TomatoCanopy, createRadialTexture, type PlantSlot } from './plants';

const DEG = Math.PI / 180;
const UP_AXIS_X = new THREE.Vector3(1, 0, 0);
const SCRATCH_A = new THREE.Color();
const SCRATCH_B = new THREE.Color();
const SCRATCH_C = new THREE.Color();
const SCRATCH_D = new THREE.Color();
const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const smoothstep = (t: number) => t * t * (3 - 2 * t);

/* -------------------------------------------------------------------------- */
/*                                  温室尺寸                                   */
/* -------------------------------------------------------------------------- */

const LEN = GREENHOUSE_LAYOUT.length;
const WIDTH = GREENHOUSE_LAYOUT.width;
const HALF_L = LEN / 2;
const HALF_W = WIDTH / 2;
const EAVE_H = GREENHOUSE_LAYOUT.eaveHeight;
const ARCH_RISE = GREENHOUSE_LAYOUT.archRise;
const RIDGE_H = EAVE_H + ARCH_RISE;
const BED_LEN = GREENHOUSE_LAYOUT.bedLength;
const BED_HALF = BED_LEN / 2;
const BED_WIDTH = GREENHOUSE_LAYOUT.bedWidth;
const BED_Z = [...GREENHOUSE_LAYOUT.bedCenters];
const ROWS = BED_Z.length;
const PLANTS_PER_ROW = GREENHOUSE_LAYOUT.plantsPerBed;
const PLANT_SPACING = BED_LEN / PLANTS_PER_ROW;
export const PLANT_TOTAL = ROWS * PLANTS_PER_ROW;

const AISLE_HALF = GREENHOUSE_LAYOUT.centerAisleWidth / 2;
const VENT_SLATS = 6;
const LAMP_ROWS = 8;

/** 拱形屋面的高度剖面（t: 0→1 从 -Z 侧到 +Z 侧） */
const archY = (t: number) => EAVE_H + ARCH_RISE * Math.pow(Math.sin(Math.PI * clamp(t, 0, 1)), 0.86);
const archPoint = (t: number, bayCenter: number, target = new THREE.Vector3()) =>
	target.set(0, archY(t), bayCenter - WIDTH / 4 + (WIDTH / 2) * t);

/* -------------------------------------------------------------------------- */
/*                                  工具函数                                   */
/* -------------------------------------------------------------------------- */

const mulberry32 = (seed: number) => {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
};

/** 太阳高度角/方位角（纬度 34.3°N，郑州） */
const solarAngles = (dayOfYear: number, hour: number) => {
	const lat = 34.3 * DEG;
	const decl = 23.44 * DEG * Math.sin((2 * Math.PI * (284 + dayOfYear)) / 365);
	const ha = (hour - 12) * 15 * DEG;
	const sinEl = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(ha);
	const el = Math.asin(clamp(sinEl, -1, 1));
	const cosAz = (Math.sin(decl) - Math.sin(el) * Math.sin(lat)) / Math.max(1e-4, Math.cos(el) * Math.cos(lat));
	let az = Math.acos(clamp(cosAz, -1, 1));
	if (ha > 0) az = Math.PI * 2 - az;
	return { el, az };
};

/** 太阳方向向量（+X 东，-Z 北，+Z 南） */
const sunDirection = (el: number, az: number, target = new THREE.Vector3()) =>
	target.set(Math.cos(el) * Math.sin(az), Math.sin(el), -Math.cos(el) * Math.cos(az)).normalize();

const SUN_STOPS: Array<[number, string, number]> = [
	[-12, '#20304f', 0.0],
	[-4, '#4a3358', 0.05],
	[-1, '#a04a2a', 0.35],
	[2, '#ff6a22', 0.9],
	[7, '#ff9a44', 1.7],
	[16, '#ffcf9a', 2.6],
	[28, '#fff0d8', 3.3],
	[90, '#fffaf0', 3.7],
];

const sampleSun = (elDeg: number, outColor: THREE.Color) => {
	for (let i = 0; i < SUN_STOPS.length - 1; i++) {
		const [d0, c0, i0] = SUN_STOPS[i];
		const [d1, c1, i1] = SUN_STOPS[i + 1];
		if (elDeg <= d1) {
			const k = clamp((elDeg - d0) / (d1 - d0), 0, 1);
			outColor.set(c0).lerp(new THREE.Color(c1), k);
			return lerp(i0, i1, k);
		}
	}
	outColor.set(SUN_STOPS[SUN_STOPS.length - 1][1]);
	return SUN_STOPS[SUN_STOPS.length - 1][2];
};

/* -------------------------------------------------------------------------- */
/*                              程序化结构贴图                                 */
/* -------------------------------------------------------------------------- */

const noiseCanvas = (size: number, draw: (ctx: CanvasRenderingContext2D, size: number, rnd: () => number) => void, seed: number) => {
	const canvas = document.createElement('canvas');
	canvas.width = size;
	canvas.height = size;
	draw(canvas.getContext('2d')!, size, mulberry32(seed));
	return canvas;
};

const canvasTexture = (canvas: HTMLCanvasElement, repeat = 1) => {
	const tex = new THREE.CanvasTexture(canvas);
	tex.colorSpace = THREE.SRGBColorSpace;
	tex.wrapS = THREE.RepeatWrapping;
	tex.wrapT = THREE.RepeatWrapping;
	tex.repeat.set(repeat, repeat);
	tex.anisotropy = 4;
	return tex;
};

const loadPbrTexture = (url: string, repeat: number, colorSpace: THREE.ColorSpace = THREE.NoColorSpace) => {
	const texture = new THREE.TextureLoader().load(url);
	texture.wrapS = THREE.RepeatWrapping;
	texture.wrapT = THREE.RepeatWrapping;
	texture.repeat.set(repeat, repeat);
	texture.anisotropy = 8;
	texture.colorSpace = colorSpace;
	return texture;
};

/** 混凝土路面 */
const createConcreteTexture = () => {
	const canvas = noiseCanvas(
		256,
		(ctx, size, rnd) => {
			ctx.fillStyle = '#8d8b85';
			ctx.fillRect(0, 0, size, size);
			for (let i = 0; i < 5200; i++) {
				const v = 110 + Math.floor(rnd() * 70);
				ctx.fillStyle = `rgba(${v},${v - 2},${v - 6},${0.15 + rnd() * 0.4})`;
				ctx.fillRect(rnd() * size, rnd() * size, 1 + rnd() * 2.4, 1 + rnd() * 2.4);
			}
			// 伸缩缝
			ctx.strokeStyle = 'rgba(70,68,64,0.55)';
			ctx.lineWidth = 2;
			ctx.beginPath();
			ctx.moveTo(0, size / 2);
			ctx.lineTo(size, size / 2);
			ctx.stroke();
		},
		20260921
	);
	return canvasTexture(canvas, 8);
};

/** 土壤（含团粒与残茬） */
const createSoilTexture = () => {
	const canvas = noiseCanvas(
		256,
		(ctx, size, rnd) => {
			ctx.fillStyle = '#4a3527';
			ctx.fillRect(0, 0, size, size);
			for (let i = 0; i < 7000; i++) {
				const v = rnd();
				ctx.fillStyle = v > 0.72 ? `rgba(112,86,60,${0.2 + rnd() * 0.4})` : `rgba(${30 + Math.floor(v * 46)},${22 + Math.floor(v * 34)},${16 + Math.floor(v * 22)},${0.3 + rnd() * 0.5})`;
				const r = 1 + rnd() * 4;
				ctx.beginPath();
				ctx.arc(rnd() * size, rnd() * size, r, 0, Math.PI * 2);
				ctx.fill();
			}
			for (let i = 0; i < 90; i++) {
				ctx.strokeStyle = `rgba(150,140,96,${0.1 + rnd() * 0.2})`;
				ctx.lineWidth = 0.8;
				ctx.beginPath();
				const x = rnd() * size;
				const y = rnd() * size;
				ctx.moveTo(x, y);
				ctx.lineTo(x + (rnd() - 0.5) * 22, y + (rnd() - 0.5) * 22);
				ctx.stroke();
			}
		},
		771
	);
	return canvasTexture(canvas, 6);
};

/** 室外地面 */
const createGroundTexture = () => {
	const canvas = noiseCanvas(
		256,
		(ctx, size, rnd) => {
			ctx.fillStyle = '#5c6242';
			ctx.fillRect(0, 0, size, size);
			for (let i = 0; i < 4200; i++) {
				const v = rnd();
				ctx.fillStyle = v > 0.6 ? `rgba(${96 + Math.floor(v * 42)},${104 + Math.floor(v * 36)},${62 + Math.floor(v * 26)},${0.25 + rnd() * 0.35})` : `rgba(${52 + Math.floor(v * 34)},${56 + Math.floor(v * 30)},${38 + Math.floor(v * 20)},${0.3 + rnd() * 0.4})`;
				const r = 1.5 + rnd() * 6;
				ctx.beginPath();
				ctx.arc(rnd() * size, rnd() * size, r, 0, Math.PI * 2);
				ctx.fill();
			}
		},
		4242
	);
	return canvasTexture(canvas, 72);
};

const createTreeFoliageTexture = () => {
	const canvas = document.createElement('canvas');
	canvas.width = canvas.height = 512;
	const context = canvas.getContext('2d')!;
	const random = mulberry32(1986);
	const clusters = [
		[252, 157, 99], [153, 210, 91], [350, 200, 94],
		[204, 297, 102], [310, 302, 104], [251, 253, 120],
	] as const;
	for (const [centerX, centerY, radius] of clusters) {
		for (let index = 0; index < 135; index++) {
			const angle = random() * Math.PI * 2;
			const distance = Math.sqrt(random()) * radius;
			const x = centerX + Math.cos(angle) * distance;
			const y = centerY + Math.sin(angle) * distance;
			const leafWidth = 5 + random() * 15;
			const leafHeight = 4 + random() * 12;
			const shade = Math.floor(random() * 65);
			context.fillStyle = `rgba(${43 + shade},${69 + shade},${32 + Math.floor(shade * 0.6)},${0.68 + random() * 0.3})`;
			context.beginPath();
			context.ellipse(x, y, leafWidth, leafHeight, angle, 0, Math.PI * 2);
			context.fill();
		}
	}
	const texture = new THREE.CanvasTexture(canvas);
	texture.colorSpace = THREE.SRGBColorSpace;
	texture.anisotropy = 8;
	return texture;
};

/** 遮阳幕（铝箔条状编织幕布） */
const createCurtainTexture = () => {
	const canvas = noiseCanvas(128, (ctx, size) => {
		ctx.fillStyle = '#e8e6dc';
		ctx.fillRect(0, 0, size, size);
		ctx.fillStyle = 'rgba(150,168,150,0.85)';
		for (let i = 0; i < size; i += 6) ctx.fillRect(0, i, size, 2.4);
		ctx.fillStyle = 'rgba(255,255,255,0.30)';
		for (let i = 0; i < size; i += 6) ctx.fillRect(0, i + 3, size, 0.9);
	}, 9);
	const tex = canvasTexture(canvas, 1);
	tex.repeat.set(10, 6);
	return tex;
};

/** 遮阳幕用的镂空 alpha（网眼） */
const createMeshAlphaTexture = () => {
	const canvas = noiseCanvas(64, (ctx, size) => {
		ctx.fillStyle = '#ffffff';
		ctx.fillRect(0, 0, size, size);
		ctx.fillStyle = 'rgba(0,0,0,0.45)';
		for (let y = 0; y < size; y += 4) {
			for (let x = 0; x < size; x += 4) ctx.fillRect(x + 1, y + 1, 2, 2);
		}
	}, 11);
	const tex = new THREE.CanvasTexture(canvas);
	tex.wrapS = THREE.RepeatWrapping;
	tex.wrapT = THREE.RepeatWrapping;
	tex.repeat.set(40, 4);
	return tex;
};

/* -------------------------------------------------------------------------- */
/*                                  对外类型                                   */
/* -------------------------------------------------------------------------- */

export type CameraPreset = 'overview' | 'closeup' | 'top' | 'fruit';

export interface TwinFrameState {
	lai: number;
	plantHeightCm: number;
	fruitCount: number;
	singleFruitWeightG: number;
	fruitSetRate: number;
	mature: boolean;
	ripeness: number;
	lightPpfd: number;
	irrigation: boolean;
	ventilation: boolean;
	supplementalLight: boolean;
	shade: boolean;
	co2: boolean;
	circulationFan?: boolean;
	exhaustFan?: boolean;
	coolingPad?: boolean;
	roofVent?: boolean;
	temperatureC?: number;
	airHumidityPct?: number;
	co2Ppm?: number;
	soilMoisturePct?: number;
	sensorReadings?: Record<string, number>;
	/** 病害严重度：与后端一致，百分比量纲 0~100 */
	severity: Record<string, number>;
	/** 日内时刻 0~24（浮点，来自 simulatedAt 或昼夜循环） */
	hour: number;
	dayOfYear: number;
}

export interface TwinStats {
	/** 实测帧率（基于真实耗时统计，低帧率会被如实反映） */
	fps: number;
	drawCalls: number;
	triangles: number;
	quality: 'high' | 'medium' | 'low';
	/** 是否已经因为帧率不足而降级 */
	degraded: boolean;
	/** 当前像素比（渲染分辨率 = CSS 尺寸 × pixelRatio） */
	pixelRatio: number;
	/** 渲染缓冲区分辨率 */
	width: number;
	height: number;
}

export interface DiseaseMarker {
	code: string;
	x: number;
	y: number;
	visible: boolean;
}

export type InspectionInfo = Omit<EquipmentEntry, 'object'>;

const DISEASE_STYLE: Record<string, { color: number; size: number; additive: boolean; opacity: number; drift: number }> = {
	BOTRYTIS: { color: 0x9fae9c, size: 0.2, additive: false, opacity: 0.6, drift: 0.1 }, // 灰霉：灰色绒毛状雾团
	LATE_BLIGHT: { color: 0x33381f, size: 0.13, additive: false, opacity: 0.72, drift: -0.05 }, // 晚疫：暗色水渍
	POWDERY_MILDEW: { color: 0xf4f5e8, size: 0.085, additive: true, opacity: 0.5, drift: 0.06 }, // 白粉：白色粉层
	LEAF_MOLD: { color: 0xbda645, size: 0.11, additive: false, opacity: 0.55, drift: 0.03 }, // 叶霉：黄绿霉斑
};
const DISEASE_STYLE_KEYS = Object.keys(DISEASE_STYLE);

/* -------------------------------------------------------------------------- */
/*                                  场景主体                                   */
/* -------------------------------------------------------------------------- */

export class GreenhouseTwin {
	private canvas: HTMLCanvasElement;
	private container: HTMLElement;
	private renderer!: THREE.WebGLRenderer;
	private scene!: THREE.Scene;
	private camera!: THREE.PerspectiveCamera;
	private controls!: OrbitControls;
	private freeControls!: FreeCameraControls;
	private navigationMode: 'orbit' | 'fly' = 'orbit';

	private pmrem!: THREE.PMREMGenerator;
	private envTarget: THREE.WebGLRenderTarget | null = null;
	private envCanvas!: HTMLCanvasElement;
	private envCtx!: CanvasRenderingContext2D;
	private envTex!: THREE.CanvasTexture;
	private envHour = -99;

	private sky!: THREE.Mesh;
	private skyMat!: THREE.ShaderMaterial;
	private sun!: THREE.DirectionalLight;
	private hemi!: THREE.HemisphereLight;
	private fill!: THREE.HemisphereLight;
	private ambient!: THREE.AmbientLight;
	private lampLights: THREE.PointLight[] = [];
	private lampMat!: THREE.MeshStandardMaterial;
	private lampConeMat!: THREE.MeshBasicMaterial;

	private canopy!: TomatoCanopy;
	private equipment!: ReturnType<typeof buildEquipment>;
	private inspectHandler: ((entry: InspectionInfo | null) => void) | null = null;
	private raycaster = new THREE.Raycaster();
	private pointer = new THREE.Vector2();
	private disposables: Array<{ dispose: () => void }> = [];
	private matSteel!: THREE.MeshStandardMaterial;
	private matDark!: THREE.MeshStandardMaterial;
	private matSoil!: THREE.MeshStandardMaterial;
	private matConcrete!: THREE.MeshStandardMaterial;
	private matGlass!: THREE.MeshPhysicalMaterial;
	private matGlassFallback!: THREE.MeshPhysicalMaterial;
	private shellRoof: THREE.Mesh[] = [];
	private shellWalls: THREE.Mesh[] = [];
	private legacyFrame = new THREE.Group();
	private shellMode: 'solid' | 'translucent' | 'cutaway' = 'solid';
	private assetStatus: 'loading' | 'blender' | 'fallback' = 'loading';
	private insideShell = false;

	// —— 设备 ——
	private ventMesh!: THREE.InstancedMesh;
	private ventOpen = 0;
	private ventTarget = 0;
	private curtainGroup = new THREE.Group();
	private curtainDeploy = 0;
	private curtainTarget = 0;
	private droplets!: THREE.Points;
	private dropVel: number[] = [];
	private dropPhase: number[] = [];
	private irrigationMix = 0;
	private irrigationGlowMat!: THREE.MeshBasicMaterial;
	private wetMesh!: THREE.InstancedMesh;
	private wetMat!: THREE.MeshBasicMaterial;
	private co2Haze!: THREE.Points;
	private co2Mix = 0;
	private dust!: THREE.Points;
	private lampGlow = 0;

	// —— 病害 ——
	private diseaseGroups: Record<string, THREE.Group> = {};
	private diseasePoints: Record<string, THREE.Points> = {};
	private diseasePatchMat: Record<string, THREE.MeshBasicMaterial> = {};
	private diseaseAnchors: Record<string, THREE.Vector3> = {};
	private diseaseMix: Record<string, number> = { BOTRYTIS: 0, LATE_BLIGHT: 0, POWDERY_MILDEW: 0, LEAF_MOLD: 0 };

	// —— 运行时 ——
	private clock = { last: 0 };
	private elapsed = 0;
	private raf = 0;
	private disposed = false;
	private paused = false;
	private frameCount = 0;
	private fpsAccum = 0;
	private fpsTimer = 0;
	private fps = 0;
	private lowFpsTime = 0;
	private degraded = false;
	private autoRotate = false;
	private idleTimer = 0;
	private sizeW = 1;
	private sizeH = 1;
	private tween: { p0: THREE.Vector3; p1: THREE.Vector3; t0: THREE.Vector3; t1: THREE.Vector3; k: number; dur: number } | null = null;
	private followFruit = false;
	private quality: TwinStats['quality'] = 'high';
	private automaticQuality = true;
	private wetMix = 0;
	private target: TwinFrameState = {
		lai: 0.06,
		plantHeightCm: 5.2,
		fruitCount: 0,
		singleFruitWeightG: 0,
		fruitSetRate: 0,
		mature: false,
		ripeness: 0,
		lightPpfd: 500,
		irrigation: false,
		ventilation: false,
		supplementalLight: false,
		shade: false,
		co2: false,
		circulationFan: true,
		exhaustFan: false,
		coolingPad: false,
		roofVent: false,
		temperatureC: 24,
		airHumidityPct: 68,
		co2Ppm: 600,
		soilMoisturePct: 55,
		severity: { BOTRYTIS: 0, LATE_BLIGHT: 0, POWDERY_MILDEW: 0, LEAF_MOLD: 0 },
		hour: 12,
		dayOfYear: 264,
	};
	private current: TwinFrameState = {
		...this.target,
		severity: { BOTRYTIS: 0, LATE_BLIGHT: 0, POWDERY_MILDEW: 0, LEAF_MOLD: 0 },
	};
	private tmpVec = new THREE.Vector3();
	private sunColor = new THREE.Color();
	private tmpColor = new THREE.Color();
	private tmpColor2 = new THREE.Color();
	private onVisibility = () => this.handleVisibility();

	constructor(canvas: HTMLCanvasElement, container: HTMLElement) {
		this.canvas = canvas;
		this.container = container;
		this.initRenderer();
		this.initScene();
		this.initSky();
		this.initLights();
		this.initEnvironment();
		this.buildStructure();
		this.buildBeds();
		buildSiteDetails(this.scene, this.matConcrete, this.matSteel);
		this.buildIrrigation();
		this.buildLamps();
		this.buildVents();
		this.buildCurtain();
		this.buildCO2();
		this.buildAtmosphere();
		this.buildDiseases();
		this.buildCanopy();
		this.equipment = buildEquipment(this.scene);
		void this.loadBlenderAssets();

		this.resize();
		this.canvas.addEventListener('click', this.handleInspection);
		document.addEventListener('visibilitychange', this.onVisibility);
		this.loop();
	}

	private async loadBlenderAssets() {
		const loader = new GLTFLoader();
		const base = `${import.meta.env.BASE_URL}models/greenhouse/`;
		try {
			const [structure, equipment] = await Promise.all([
				loader.loadAsync(`${base}greenhouse-structure.glb`),
				loader.loadAsync(`${base}greenhouse-equipment.glb`),
			]);
			if (this.disposed) return;
			structure.scene.name = 'blender-greenhouse-structure';
			structure.scene.traverse((node) => {
				if (node instanceof THREE.Mesh) {
					node.castShadow = true;
					node.receiveShadow = true;
				}
			});
			equipment.scene.traverse((node) => {
				if (node instanceof THREE.Mesh) node.castShadow = true;
			});
			this.equipment.replaceVisuals(equipment.scene);
			this.scene.add(structure.scene);
			this.legacyFrame.visible = false;
			this.assetStatus = 'blender';
		} catch (error) {
			this.assetStatus = 'fallback';
			if (!this.disposed) console.error('Blender greenhouse assets could not be loaded; using the procedural model.', error);
		}
	}

	getAssetStatus() {
		return this.assetStatus;
	}

	/* ------------------------------------------------------------------ */
	/*                          渲染器 / 相机 / 控制器                       */
	/* ------------------------------------------------------------------ */

	private initRenderer() {
		this.renderer = new THREE.WebGLRenderer({
			canvas: this.canvas,
			antialias: true,
			alpha: false,
			powerPreference: 'high-performance',
			stencil: false,
		});
		this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
		this.renderer.outputColorSpace = THREE.SRGBColorSpace;
		this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
		this.renderer.toneMappingExposure = 1.02;
		this.renderer.shadowMap.enabled = true;
		this.renderer.shadowMap.type = THREE.PCFShadowMap; // three r186 已移除 PCFSoftShadowMap
		this.renderer.setClearColor(0x0a0e14, 1);
		// 关闭自动重置，改为每帧手动重置，让 HUD 统计整帧绘制次数
		this.renderer.info.autoReset = false;
	}

	private initScene() {
		this.scene = new THREE.Scene();
		this.scene.fog = new THREE.Fog(0x9fb4c4, 45, 165);

		this.camera = new THREE.PerspectiveCamera(46, 1, 0.08, 400);
		this.camera.position.set(17.5, 8.6, 16.5);

		this.controls = new OrbitControls(this.camera, this.canvas);
		this.controls.enableDamping = true;
		this.controls.dampingFactor = 0.055;
		this.controls.target.set(0, 1.6, 0);
		this.controls.minDistance = 1.4;
		this.controls.maxDistance = 300;
		this.controls.maxPolarAngle = Math.PI - 0.01;
		this.controls.autoRotateSpeed = 0.34;
		this.controls.enablePan = true;
		this.controls.screenSpacePanning = false;
		this.controls.addEventListener('start', () => {
			this.autoRotate = false;
			this.controls.autoRotate = false;
			this.followFruit = false;
			this.tween = null;
			this.idleTimer = 0;
		});
		this.disposables.push(this.controls);
		this.freeControls = new FreeCameraControls(this.camera, this.canvas, () => this.setNavigationMode('orbit'));
		this.disposables.push(this.freeControls);
	}

	private initSky() {
		this.skyMat = new THREE.ShaderMaterial({
			side: THREE.BackSide,
			depthWrite: false,
			fog: false,
			uniforms: {
				uTop: { value: new THREE.Color('#3f7fc4') },
				uHorizon: { value: new THREE.Color('#cfe0ec') },
				uBottom: { value: new THREE.Color('#39404a') },
				uSunColor: { value: new THREE.Color('#fff2d8') },
				uSunDir: { value: new THREE.Vector3(0, 1, 0) },
				uSunI: { value: 1 },
				uStars: { value: 0 },
			},
			vertexShader: /* glsl */ `
				varying vec3 vDir;
				void main() {
					vDir = position;
					gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
				}
			`,
			fragmentShader: /* glsl */ `
				varying vec3 vDir;
				uniform vec3 uTop;
				uniform vec3 uHorizon;
				uniform vec3 uBottom;
				uniform vec3 uSunColor;
				uniform vec3 uSunDir;
				uniform float uSunI;
				uniform float uStars;
				void main() {
					vec3 d = normalize( vDir );
					float h = d.y;
					vec3 col = mix( uHorizon, uTop, pow( clamp( h, 0.0, 1.0 ), 0.52 ) );
					col = mix( col, uBottom, pow( clamp( -h, 0.0, 1.0 ), 0.45 ) );
					float sd = max( dot( d, normalize( uSunDir ) ), 0.0 );
					col += uSunColor * pow( sd, 300.0 ) * 4.0 * uSunI;
					col += uSunColor * pow( sd, 7.0 ) * 0.30 * uSunI;
					col += uSunColor * pow( sd, 2.0 ) * 0.06 * uSunI;
					if ( uStars > 0.001 && h > 0.0 ) {
						vec3 p = floor( d * 320.0 );
						float n = fract( sin( dot( p, vec3( 12.9898, 78.233, 45.164 ) ) ) * 43758.5453 );
						float star = smoothstep( 0.9974, 1.0, n );
						col += vec3( star ) * uStars * clamp( h * 4.0, 0.0, 1.0 );
					}
					gl_FragColor = vec4( col, 1.0 );
				}
			`,
		});
		this.sky = new THREE.Mesh(new THREE.SphereGeometry(230, 32, 18), this.skyMat);
		this.sky.frustumCulled = false;
		this.sky.name = 'sky';
		this.scene.add(this.sky);
		this.disposables.push(this.sky.geometry, this.skyMat);
	}

	private initLights() {
		this.sun = new THREE.DirectionalLight(0xffffff, 3.2);
		this.sun.castShadow = true;
		this.sun.shadow.mapSize.set(2048, 2048);
		this.sun.shadow.camera.near = 1;
		this.sun.shadow.camera.far = 170;
		this.sun.shadow.camera.left = -26;
		this.sun.shadow.camera.right = 26;
		this.sun.shadow.camera.top = 24;
		this.sun.shadow.camera.bottom = -20;
		this.sun.shadow.bias = -0.0006;
		this.sun.shadow.normalBias = 0.028;
		this.sun.shadow.radius = 2.2;
		this.scene.add(this.sun);
		this.scene.add(this.sun.target);

		this.hemi = new THREE.HemisphereLight(0xa8ccf0, 0x4a4636, 0.85);
		this.scene.add(this.hemi);
		this.fill = new THREE.HemisphereLight(0x4c6a86, 0x241f18, 0.5);
		this.scene.add(this.fill);
		this.ambient = new THREE.AmbientLight(0xffffff, 0.16);
		this.scene.add(this.ambient);

		// 补光灯真实光源（每个苗床一盏，不投影以保证帧率）
		for (let i = 0; i < ROWS; i++) {
			const l = new THREE.PointLight(0xffd9a8, 0, 14, 2);
			l.position.set(0, 3.15, BED_Z[i]);
			this.scene.add(l);
			this.lampLights.push(l);
		}
	}

	/** 用天空颜色生成 PMREM 环境贴图，让玻璃与金属有真实反射 */
	private initEnvironment() {
		this.pmrem = new THREE.PMREMGenerator(this.renderer);
		this.pmrem.compileEquirectangularShader();
		this.envCanvas = document.createElement('canvas');
		this.envCanvas.width = 128;
		this.envCanvas.height = 64;
		this.envCtx = this.envCanvas.getContext('2d')!;
		this.envTex = new THREE.CanvasTexture(this.envCanvas);
		this.envTex.mapping = THREE.EquirectangularReflectionMapping;
		this.envTex.colorSpace = THREE.SRGBColorSpace;
		this.disposables.push(this.pmrem, this.envTex);
	}

	/* ------------------------------------------------------------------ */
	/*                              温室结构                                */
	/* ------------------------------------------------------------------ */

	private buildStructure() {
		this.legacyFrame.name = 'legacy-structure-fallback';
		this.scene.add(this.legacyFrame);
		const glassHigh = new THREE.MeshPhysicalMaterial({
			color: 0xf4f3e9,
			roughness: 0.7,
			metalness: 0,
			transparent: true,
			opacity: 0.18,
			depthWrite: false,
			clearcoat: 0.05,
			clearcoatRoughness: 0.75,
			side: THREE.DoubleSide,
			envMapIntensity: 0.2,
		});
		const glassLow = new THREE.MeshPhysicalMaterial({
			color: 0xf5f4ee,
			roughness: 0.8,
			metalness: 0,
			transparent: true,
			opacity: 0.035,
			side: THREE.DoubleSide,
			depthWrite: false,
			envMapIntensity: 0.1,
		});
		this.disposables.push(glassHigh, glassLow);

		const steel = new THREE.MeshStandardMaterial({ color: 0xb9c2c8, roughness: 0.36, metalness: 0.88, envMapIntensity: 0.9 });
		const steelDark = new THREE.MeshStandardMaterial({ color: 0x6f7a80, roughness: 0.5, metalness: 0.7 });
		const doorFrame = new THREE.MeshStandardMaterial({ color: 0x465c61, roughness: 0.4, metalness: 0.72 });
		const doorLeaf = new THREE.MeshPhysicalMaterial({ color: 0xc6d2c9, roughness: 0.38, metalness: 0.1, transparent: true, opacity: 0.5, side: THREE.DoubleSide });
		const textureRoot = `${import.meta.env.BASE_URL}textures/greenhouse/`;
		const concrete = new THREE.MeshStandardMaterial({
			color: 0xd0cbc3, roughness: 0.94, metalness: 0,
			map: loadPbrTexture(`${textureRoot}concrete_diff_1k.jpg`, 9, THREE.SRGBColorSpace),
			normalMap: loadPbrTexture(`${textureRoot}concrete_nor_gl_1k.jpg`, 9),
			roughnessMap: loadPbrTexture(`${textureRoot}concrete_rough_1k.jpg`, 9),
		});
		const soilMat = new THREE.MeshStandardMaterial({
			color: 0xbdb2a4, roughness: 0.96, metalness: 0,
			map: loadPbrTexture(`${textureRoot}brown_mud_diff_1k.jpg`, 12, THREE.SRGBColorSpace),
			normalMap: loadPbrTexture(`${textureRoot}brown_mud_nor_gl_1k.jpg`, 12),
			roughnessMap: loadPbrTexture(`${textureRoot}brown_mud_rough_1k.jpg`, 12),
		});
		this.disposables.push(steel, steelDark, doorFrame, doorLeaf, concrete, soilMat);
		this.matSteel = steel;
		this.matDark = steelDark;
		this.matSoil = soilMat;
		this.matConcrete = concrete;
		this.matGlass = glassHigh;
		this.matGlassFallback = glassLow;

		// —— 拱形覆盖面 ——
		const segX = 26;
		const segA = 30;
		for (const bayCenter of GREENHOUSE_LAYOUT.bayCenters) {
			const cover = new THREE.Mesh(this.archSurface(segX, segA, bayCenter), glassHigh);
			cover.name = `cover-bay-${bayCenter}`;
			this.shellRoof.push(cover);
			this.scene.add(cover);
		}

		// 侧墙玻璃
		for (const s of [-1, 1]) {
			const wall = new THREE.Mesh(new THREE.PlaneGeometry(LEN, EAVE_H), glassHigh);
			wall.position.set(0, EAVE_H / 2, s * HALF_W);
			wall.rotation.y = s > 0 ? Math.PI : 0;
			this.shellWalls.push(wall);
			this.scene.add(wall);
		}
		// 山墙（含拱形轮廓）
		for (const s of [-1, 1]) {
			const geo = new THREE.ShapeGeometry(this.gableShape(s < 0), 52);
			geo.rotateY(s > 0 ? Math.PI / 2 : -Math.PI / 2);
			const mesh = new THREE.Mesh(geo, glassHigh);
			mesh.position.x = s * HALF_L;
			this.shellWalls.push(mesh);
			this.scene.add(mesh);
		}
		const entrance = new THREE.Group();
		entrance.position.x = -HALF_L - 0.08;
		for (const side of [-1, 1]) {
			const upright = new THREE.Mesh(new THREE.BoxGeometry(0.13, 2.8, 0.09), doorFrame);
			upright.position.set(0, 1.4, side * 1.12);
			entrance.add(upright);
		}
		const lintel = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.1, 2.33), doorFrame);
		lintel.position.y = 2.78;
		entrance.add(lintel);
		const slidingDoor = new THREE.Mesh(new THREE.BoxGeometry(0.05, 2.55, 1.05), doorLeaf);
		slidingDoor.position.set(-0.06, 1.4, 1.75);
		entrance.add(slidingDoor);
		const handle = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.24, 0.035), doorFrame);
		handle.position.set(-0.12, 1.42, 1.28);
		entrance.add(handle);
		this.legacyFrame.add(entrance);

		// —— 钢拱架（沿 X 复制） ——
		const ribPts: THREE.Vector3[] = [];
		for (let i = 0; i <= 30; i++) ribPts.push(archPoint(i / 30, GREENHOUSE_LAYOUT.bayCenters[0]).clone());
		const ribCurve = new THREE.CatmullRomCurve3(ribPts);
		const ribGeo = new THREE.TubeGeometry(ribCurve, 40, 0.038, 6, false);
		const ribCount = 14;
		const ribs = new THREE.InstancedMesh(ribGeo, steel, ribCount * 2);
		const m4 = new THREE.Matrix4();
		for (let bay = 0; bay < 2; bay++) {
			for (let i = 0; i < ribCount; i++) {
				m4.makeTranslation(-HALF_L + (LEN * i) / (ribCount - 1), 0, bay * WIDTH / 2);
				ribs.setMatrixAt(bay * ribCount + i, m4);
			}
		}
		ribs.castShadow = true;
		ribs.instanceMatrix.needsUpdate = true;
		this.legacyFrame.add(ribs);
		this.disposables.push(ribGeo, ribs);

		// —— 纵向檩条 ——
		const purlinZ = [0, -3.25, 3.25, -HALF_W + 0.001, HALF_W - 0.001];
		const purlinGeo = new THREE.CylinderGeometry(0.032, 0.032, LEN, 6).rotateZ(Math.PI / 2);
		const purlins = new THREE.InstancedMesh(purlinGeo, steel, purlinZ.length);
		purlinZ.forEach((z, i) => {
			m4.makeTranslation(0, roofHeightAt(z) + 0.02, z);
			purlins.setMatrixAt(i, m4);
		});
		purlins.instanceMatrix.needsUpdate = true;
		purlins.castShadow = true;
		this.legacyFrame.add(purlins);
		this.disposables.push(purlinGeo, purlins);

		// —— 立柱（两侧墙 + 山墙） ——
		const postGeo = new THREE.CylinderGeometry(0.048, 0.048, EAVE_H, 6).translate(0, EAVE_H / 2, 0);
		const postPos: Array<[number, number]> = [];
		for (let i = 0; i <= 8; i++) {
			const x = -HALF_L + (LEN * i) / 8;
			postPos.push([x, -HALF_W], [x, 0], [x, HALF_W]);
		}
		for (let i = 1; i < 4; i++) {
			const z = -HALF_W + (WIDTH * i) / 4;
			postPos.push([-HALF_L, z], [HALF_L, z]);
		}
		const posts = new THREE.InstancedMesh(postGeo, steel, postPos.length);
		postPos.forEach(([x, z], i) => {
			m4.makeTranslation(x, 0, z);
			posts.setMatrixAt(i, m4);
		});
		posts.instanceMatrix.needsUpdate = true;
		posts.castShadow = true;
		this.legacyFrame.add(posts);
		this.disposables.push(postGeo, posts);

		// —— 天沟 ——
		const gutterGeo = new THREE.CylinderGeometry(0.13, 0.13, LEN, 8, 1, true).rotateZ(Math.PI / 2);
		for (const s of [-1, 0, 1]) {
			const g = new THREE.Mesh(gutterGeo, steelDark);
			g.position.set(0, EAVE_H - 0.06, s * (HALF_W + 0.02));
			this.legacyFrame.add(g);
		}
		this.disposables.push(gutterGeo);

		// —— 四周矮墙 ——
		const plinthMat = concrete;
		const plinthH = 0.34;
		const mk = (w: number, d: number, x: number, z: number) => {
			const b = new THREE.Mesh(new THREE.BoxGeometry(w, plinthH, d), plinthMat);
			b.position.set(x, plinthH / 2, z);
			b.castShadow = true;
			b.receiveShadow = true;
			this.legacyFrame.add(b);
			this.disposables.push(b.geometry);
		};
		mk(LEN + 0.4, 0.3, 0, -HALF_W - 0.05);
		mk(LEN + 0.4, 0.3, 0, HALF_W + 0.05);
		mk(0.3, WIDTH + 0.4, -HALF_L - 0.05, 0);
		mk(0.3, WIDTH + 0.4, HALF_L + 0.05, 0);

		// —— 中央混凝土通道 ——
		const aisle = new THREE.Mesh(new THREE.PlaneGeometry(BED_LEN + 3.4, AISLE_HALF * 2), concrete);
		aisle.rotation.x = -Math.PI / 2;
		aisle.position.set(0, 0.012, 0);
		aisle.receiveShadow = true;
		this.scene.add(aisle);
		this.disposables.push(aisle.geometry);

		// —— 室内地面 ——
		const floor = new THREE.Mesh(new THREE.PlaneGeometry(LEN, WIDTH), soilMat);
		floor.rotation.x = -Math.PI / 2;
		floor.position.y = 0.002;
		floor.receiveShadow = true;
		this.scene.add(floor);
		this.disposables.push(floor.geometry);

		// —— 室外地面 ——
		const groundMat = new THREE.MeshStandardMaterial({
			color: 0xb5b6a4, roughness: 1, metalness: 0,
			map: loadPbrTexture(`${textureRoot}grass_diff_1k.jpg`, 120, THREE.SRGBColorSpace),
			normalMap: loadPbrTexture(`${textureRoot}grass_nor_gl_1k.jpg`, 120),
			roughnessMap: loadPbrTexture(`${textureRoot}grass_rough_1k.jpg`, 120),
		});
		const ground = new THREE.Mesh(new THREE.PlaneGeometry(400, 400), groundMat);
		ground.rotation.x = -Math.PI / 2;
		ground.position.y = -0.02;
		ground.receiveShadow = true;
		this.scene.add(ground);
		this.disposables.push(ground.geometry, groundMat);

		// —— 远处树线 ——
		const treeRnd = mulberry32(88);
		const trunkGeo = new THREE.CylinderGeometry(0.22, 0.34, 3.2, 5).translate(0, 1.6, 0);
		const crownGeo = new THREE.PlaneGeometry(4.5, 4.5);
		const trunkMat = new THREE.MeshStandardMaterial({ color: 0x4a3a2a, roughness: 0.95 });
		const crownMat = new THREE.MeshStandardMaterial({ map: createTreeFoliageTexture(), roughness: 0.94, alphaTest: 0.38, side: THREE.DoubleSide });
		const TREES = 34;
		const trunks = new THREE.InstancedMesh(trunkGeo, trunkMat, TREES);
		const crowns = new THREE.InstancedMesh(crownGeo, crownMat, TREES * 2);
		for (let i = 0; i < TREES; i++) {
			const a = (i / TREES) * Math.PI * 2 + treeRnd() * 0.3;
			const r = 46 + treeRnd() * 42;
			const s = 0.8 + treeRnd() * 0.9;
			const treeX = Math.cos(a) * r;
			const treeZ = Math.sin(a) * r;
			m4.makeScale(s, s, s).setPosition(treeX, 0, treeZ);
			trunks.setMatrixAt(i, m4);
			for (let face = 0; face < 2; face++) {
				const rotation = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), a + face * Math.PI / 2);
				m4.compose(new THREE.Vector3(treeX, 3.28 * s, treeZ), rotation, new THREE.Vector3(s, s, s));
				crowns.setMatrixAt(i * 2 + face, m4);
				crowns.setColorAt(i * 2 + face, new THREE.Color().setHSL(0.28, 0.07, 0.75 + treeRnd() * 0.16));
			}
		}
		trunks.instanceMatrix.needsUpdate = true;
		crowns.instanceMatrix.needsUpdate = true;
		if (crowns.instanceColor) crowns.instanceColor.needsUpdate = true;
		this.scene.add(trunks, crowns);
		this.disposables.push(trunkGeo, crownGeo, trunkMat, crownMat, crownMat.map!, trunks, crowns);
	}

	private archSurface(segX: number, segA: number, bayCenter: number) {
		const geo = new THREE.BufferGeometry();
		const pos: number[] = [];
		const uv: number[] = [];
		const idx: number[] = [];
		for (let i = 0; i <= segX; i++) {
			const x = -HALF_L + (LEN * i) / segX;
			for (let j = 0; j <= segA; j++) {
				const t = j / segA;
				pos.push(x, archY(t), bayCenter - WIDTH / 4 + (WIDTH / 2) * t);
				uv.push(i / segX, t);
			}
		}
		for (let i = 0; i < segX; i++) {
			for (let j = 0; j < segA; j++) {
				const a = i * (segA + 1) + j;
				const b = a + 1;
				const c = a + segA + 1;
				const d = c + 1;
				idx.push(a, b, c, b, d, c);
			}
		}
		geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
		geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
		geo.setIndex(idx);
		geo.computeVertexNormals();
		this.disposables.push(geo);
		return geo;
	}

	private gableShape(withDoor: boolean) {
		const shape = new THREE.Shape();
		shape.moveTo(-HALF_W, 0.001);
		shape.lineTo(-HALF_W, EAVE_H);
		for (let i = 1; i <= 52; i++) {
			const z = -HALF_W + (WIDTH * i) / 52;
			shape.lineTo(z, roofHeightAt(z));
		}
		shape.lineTo(HALF_W, 0.001);
		shape.closePath();
		if (withDoor) {
			const opening = new THREE.Path();
			opening.moveTo(-1.08, -0.04);
			opening.lineTo(1.08, -0.04);
			opening.lineTo(1.08, 2.72);
			opening.lineTo(-1.08, 2.72);
			opening.closePath();
			shape.holes.push(opening);
		}
		return shape;
	}

	/* ------------------------------------------------------------------ */
	/*                            苗床 / 灌溉 / 补光                         */
	/* ------------------------------------------------------------------ */

	private buildBeds() {
		const m4 = new THREE.Matrix4();
		const bedGeo = new THREE.BoxGeometry(BED_LEN, 0.24, BED_WIDTH);
		const beds = new THREE.InstancedMesh(bedGeo, this.matSoil, ROWS);
		BED_Z.forEach((z, i) => {
			m4.makeTranslation(0, 0.12, z);
			beds.setMatrixAt(i, m4);
		});
		beds.instanceMatrix.needsUpdate = true;
		beds.castShadow = true;
		beds.receiveShadow = true;
		this.scene.add(beds);
		this.disposables.push(bedGeo, beds);

		const frameMat = new THREE.MeshStandardMaterial({ color: 0xd6d4ca, roughness: 0.72, metalness: 0.04 });
		const edgeGeo = new THREE.BoxGeometry(BED_LEN, 0.28, 0.07);
		const edges = new THREE.InstancedMesh(edgeGeo, frameMat, ROWS * 2);
		let k = 0;
		BED_Z.forEach((z) => {
			for (const s of [-1, 1]) {
				m4.makeTranslation(0, 0.14, z + s * (BED_WIDTH / 2 + 0.035));
				edges.setMatrixAt(k++, m4);
			}
		});
		edges.instanceMatrix.needsUpdate = true;
		edges.castShadow = true;
		edges.receiveShadow = true;
		this.scene.add(edges);
		this.disposables.push(edgeGeo, frameMat, edges);

		// 灌溉湿斑：IRRIGATION 为真时逐渐显现，停灌后缓慢消退
		const wetTex = createRadialTexture('rgba(0,0,0,0.92)', 'rgba(0,0,0,0)', 64, 0.85);
		this.wetMat = new THREE.MeshBasicMaterial({
			map: wetTex,
			color: 0x241a12,
			transparent: true,
			opacity: 0,
			depthWrite: false,
		});
		const wetGeo = new THREE.PlaneGeometry(0.62, 0.62).rotateX(-Math.PI / 2);
		this.wetMesh = new THREE.InstancedMesh(wetGeo, this.wetMat, PLANT_TOTAL);
		let wi = 0;
		for (let r = 0; r < ROWS; r++) {
			for (let i = 0; i < PLANTS_PER_ROW; i++) {
				const x = -BED_HALF + PLANT_SPACING * (i + 0.5);
				m4.makeTranslation(x, 0.247, BED_Z[r]);
				this.wetMesh.setMatrixAt(wi++, m4);
			}
		}
		this.wetMesh.instanceMatrix.needsUpdate = true;
		this.wetMesh.frustumCulled = false;
		this.scene.add(this.wetMesh);
		this.disposables.push(wetGeo, wetTex, this.wetMat, this.wetMesh);
	}

	private buildIrrigation() {
		// 滴灌支管（沿苗床铺设）
		const pipeMat = new THREE.MeshStandardMaterial({ color: 0x1d1f22, roughness: 0.62, metalness: 0.1 });
		const pipeGeo = new THREE.CylinderGeometry(0.016, 0.016, BED_LEN, 8).rotateZ(Math.PI / 2);
		const pipes = new THREE.InstancedMesh(pipeGeo, pipeMat, ROWS);
		const m4 = new THREE.Matrix4();
		BED_Z.forEach((z, i) => {
			m4.makeTranslation(0, 0.265, z - BED_WIDTH / 2 + 0.28);
			pipes.setMatrixAt(i, m4);
		});
		pipes.instanceMatrix.needsUpdate = true;
		pipes.castShadow = true;
		this.scene.add(pipes);
		this.disposables.push(pipeGeo, pipeMat, pipes);

		// 主管（沿走道）
		const mainGeo = new THREE.CylinderGeometry(0.032, 0.032, BED_LEN + 2, 8).rotateZ(Math.PI / 2);
		const main = new THREE.Mesh(mainGeo, pipeMat);
		main.position.set(0, 0.13, -AISLE_HALF + 0.1);
		main.castShadow = true;
		this.scene.add(main);
		this.disposables.push(mainGeo);

		// 灌水时主管发淡蓝色微光
		const glowGeo = new THREE.CylinderGeometry(0.05, 0.05, BED_LEN + 2, 8, 1, true).rotateZ(Math.PI / 2);
		const glowMat = new THREE.MeshBasicMaterial({ color: 0x59a8e8, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending });
		const glow = new THREE.Mesh(glowGeo, glowMat);
		glow.position.copy(main.position);
		this.scene.add(glow);
		this.disposables.push(glowGeo, glowMat);

		// 滴头水滴
		const dropTex = createRadialTexture('rgba(228,244,255,0.95)', 'rgba(160,210,255,0)', 32, 0.6);
		const dropGeo = new THREE.BufferGeometry();
		const count = PLANT_TOTAL * 2;
		const pos = new Float32Array(count * 3);
		this.dropVel = new Array(count).fill(0);
		this.dropPhase = new Array(count).fill(0);
		const rnd = mulberry32(5150);
		let di = 0;
		for (let r = 0; r < ROWS; r++) {
			for (let i = 0; i < PLANTS_PER_ROW; i++) {
				const x = -BED_HALF + PLANT_SPACING * (i + 0.5);
				const z = BED_Z[r] - BED_WIDTH / 2 + 0.28;
				for (let s = 0; s < 2; s++) {
					pos[di * 3] = x + (s === 0 ? -0.06 : 0.06);
					pos[di * 3 + 1] = 0.245;
					pos[di * 3 + 2] = z;
					this.dropPhase[di] = rnd();
					di++;
				}
			}
		}
		dropGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
		const dropMat = new THREE.PointsMaterial({
			map: dropTex,
			size: 0.026,
			sizeAttenuation: true,
			transparent: true,
			opacity: 0.95,
			depthWrite: false,
			color: 0xbfe2ff,
			blending: THREE.AdditiveBlending,
		});
		this.droplets = new THREE.Points(dropGeo, dropMat);
		this.droplets.frustumCulled = false;
		this.droplets.visible = false;
		this.scene.add(this.droplets);
		this.disposables.push(dropGeo, dropTex, dropMat, this.droplets);

		this.irrigationGlowMat = glowMat;
	}

	private buildLamps() {
		const lampRnd = mulberry32(606);
		const housingMat = new THREE.MeshStandardMaterial({ color: 0xcfd4d8, roughness: 0.35, metalness: 0.85 });
		const housingGeo = new THREE.BoxGeometry(1.55, 0.085, 0.3);
		const tubeGeo = new THREE.BoxGeometry(1.44, 0.038, 0.22);
		this.lampMat = new THREE.MeshStandardMaterial({
			color: 0x30302c,
			emissive: new THREE.Color(0xffcf8a),
			emissiveIntensity: 0,
			roughness: 0.5,
			metalness: 0.1,
		});
		const hangerGeo = new THREE.CylinderGeometry(0.006, 0.006, 0.62, 4);
		const coneTex = createRadialTexture('rgba(255,214,150,0.55)', 'rgba(255,190,110,0)', 64, 1.5);
		this.lampConeMat = new THREE.MeshBasicMaterial({
			map: coneTex,
			color: 0xffd08a,
			transparent: true,
			opacity: 0,
			depthWrite: false,
			side: THREE.FrontSide,
			blending: THREE.AdditiveBlending,
		});
		// 光锥：锥尖在灯具处、向下张开（ConeGeometry 顶点在 +height/2，故中心放在灯下 0.5m）
		const coneGeo = new THREE.ConeGeometry(1.15, 1.0, 14, 1, true);

		const housings = new THREE.InstancedMesh(housingGeo, housingMat, LAMP_ROWS);
		const tubes = new THREE.InstancedMesh(tubeGeo, this.lampMat, LAMP_ROWS);
		const hangers = new THREE.InstancedMesh(hangerGeo, housingMat, LAMP_ROWS * 2);
		const cones = new THREE.InstancedMesh(coneGeo, this.lampConeMat, LAMP_ROWS);
		const m4 = new THREE.Matrix4();
		let idx = 0;
		let hi = 0;
		for (let r = 0; r < ROWS; r++) {
			for (const x of [-5.4, 5.4]) {
				const y = 3.28;
				m4.makeTranslation(x, y, BED_Z[r]);
				housings.setMatrixAt(idx, m4);
				m4.makeTranslation(x, y - 0.062, BED_Z[r]);
				tubes.setMatrixAt(idx, m4);
				m4.makeTranslation(x, y - 0.5, BED_Z[r]);
				cones.setMatrixAt(idx, m4);
				for (const s of [-1, 1]) {
					m4.makeTranslation(x + s * 0.62, y + 0.31, BED_Z[r]);
					hangers.setMatrixAt(hi++, m4);
				}
				idx++;
			}
		}
		for (const mesh of [housings, tubes, hangers, cones]) {
			mesh.instanceMatrix.needsUpdate = true;
			mesh.frustumCulled = false;
			this.scene.add(mesh);
			this.disposables.push(mesh);
		}
		housings.castShadow = true;
		this.disposables.push(housingGeo, housingMat, tubeGeo, hangerGeo, coneGeo, coneTex, this.lampMat, this.lampConeMat);
		void lampRnd;
	}

	private buildVents() {
		const slatMat = new THREE.MeshPhysicalMaterial({
			color: 0xe8eee7,
			roughness: 0.78,
			metalness: 0,
			transparent: true,
			opacity: 0.07,
			side: THREE.DoubleSide,
			clearcoat: 0.04,
			clearcoatRoughness: 0.76,
			depthWrite: false,
			envMapIntensity: 0.12,
		});
		// 天窗叶片：枢轴在叶片上沿，绕 X 轴外翻
		const slatGeo = new THREE.BoxGeometry(BED_LEN + 1.6, 0.44, 0.035).translate(0, -0.22, 0);
		this.ventMesh = new THREE.InstancedMesh(slatGeo, slatMat, VENT_SLATS * 2);
		this.ventMesh.frustumCulled = false;
		this.scene.add(this.ventMesh);
		this.disposables.push(slatGeo, slatMat, this.ventMesh);

		// 通风口框
		const frameMat = this.matDark;
		const railGeo = new THREE.BoxGeometry(BED_LEN + 1.7, 0.05, 0.09);
		const rails = new THREE.InstancedMesh(railGeo, frameMat, 4);
		const m4 = new THREE.Matrix4();
		let ri = 0;
		for (const s of [-1, 1]) {
			for (const y of [1.0, 3.62]) {
				m4.makeTranslation(0, y, s * (HALF_W + 0.02));
				rails.setMatrixAt(ri++, m4);
			}
		}
		rails.instanceMatrix.needsUpdate = true;
		this.scene.add(rails);
		this.disposables.push(railGeo, rails);
	}

	/* ------------------------------------------------------------------ */
	/*                       遮阳幕 / 风机 / CO₂ / 氛围                      */
	/* ------------------------------------------------------------------ */

	private buildCurtain() {
		const mat = new THREE.MeshStandardMaterial({
			map: createCurtainTexture(),
			alphaMap: createMeshAlphaTexture(),
			transparent: true,
			alphaTest: 0.06,
			side: THREE.DoubleSide,
			roughness: 0.82,
			metalness: 0.08,
			color: 0xffffff,
			emissive: new THREE.Color(0x14140f),
			emissiveIntensity: 0.35,
		});
		this.disposables.push(mat);
		const y = EAVE_H - 0.24;
		const rollerMat = this.matDark;
		this.curtainGroup = new THREE.Group();
		this.curtainGroup.name = 'shade-curtain';
		for (const s of [-1, 1]) {
			const geo = new THREE.PlaneGeometry(BED_LEN + 2, 1, 1, 1).translate(0, 0.5, 0).rotateX(s > 0 ? Math.PI / 2 : -Math.PI / 2);
			const panel = new THREE.Mesh(geo, mat);
			panel.position.set(0, y, 0);
			panel.userData.side = s;
			panel.name = `curtain-panel-${s > 0 ? 'pos' : 'neg'}`;
			this.curtainGroup.add(panel);
			this.disposables.push(geo);

			const rollerGeo = new THREE.CylinderGeometry(0.055, 0.055, BED_LEN + 2, 8).rotateZ(Math.PI / 2);
			const roller = new THREE.Mesh(rollerGeo, rollerMat);
			roller.position.set(0, y, 0);
			roller.userData.side = s;
			roller.name = `curtain-roller-${s > 0 ? 'pos' : 'neg'}`;
			this.curtainGroup.add(roller);
			this.disposables.push(rollerGeo);
		}
		this.curtainGroup.visible = false;
		this.curtainGroup.scale.z = 1;
		this.scene.add(this.curtainGroup);
	}

	private buildCO2() {
		const pipeMat = new THREE.MeshStandardMaterial({ color: 0xd8dde0, roughness: 0.5, metalness: 0.2 });
		const pipeGeo = new THREE.CylinderGeometry(0.026, 0.026, BED_LEN + 2, 8).rotateZ(Math.PI / 2);
		const pipe = new THREE.Mesh(pipeGeo, pipeMat);
		pipe.position.set(0, 0.24, AISLE_HALF - 0.12);
		pipe.castShadow = true;
		this.scene.add(pipe);
		this.disposables.push(pipeGeo, pipeMat);

		const tex = createRadialTexture('rgba(220,240,255,0.55)', 'rgba(190,220,255,0)', 64, 1.1);
		const geo = new THREE.BufferGeometry();
		const N = 260;
		const pos = new Float32Array(N * 3);
		const rnd = mulberry32(2027);
		for (let i = 0; i < N; i++) {
			pos[i * 3] = (rnd() - 0.5) * (BED_LEN + 4);
			pos[i * 3 + 1] = 0.25 + rnd() * 1.9;
			pos[i * 3 + 2] = AISLE_HALF - 0.12 + (rnd() - 0.5) * 1.4;
		}
		geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
		const mat = new THREE.PointsMaterial({
			map: tex,
			size: 0.4,
			sizeAttenuation: true,
			transparent: true,
			opacity: 0,
			depthWrite: false,
			color: 0xcfe4ff,
			blending: THREE.AdditiveBlending,
		});
		this.co2Haze = new THREE.Points(geo, mat);
		this.co2Haze.frustumCulled = false;
		this.scene.add(this.co2Haze);
		this.disposables.push(geo, tex, mat, this.co2Haze);
	}

	private buildAtmosphere() {
		const tex = createRadialTexture('rgba(255,250,235,0.85)', 'rgba(255,250,235,0)', 32, 0.9);
		const geo = new THREE.BufferGeometry();
		const N = 420;
		const pos = new Float32Array(N * 3);
		const rnd = mulberry32(31);
		for (let i = 0; i < N; i++) {
			pos[i * 3] = (rnd() - 0.5) * (LEN - 1.4);
			pos[i * 3 + 1] = 0.3 + rnd() * (RIDGE_H - 1.1);
			pos[i * 3 + 2] = (rnd() - 0.5) * (WIDTH - 1.4);
		}
		geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
		const mat = new THREE.PointsMaterial({
			map: tex,
			size: 0.032,
			sizeAttenuation: true,
			transparent: true,
			opacity: 0.32,
			depthWrite: false,
			color: 0xfff2dc,
			blending: THREE.AdditiveBlending,
		});
		this.dust = new THREE.Points(geo, mat);
		this.dust.frustumCulled = false;
		this.scene.add(this.dust);
		this.disposables.push(geo, tex, mat, this.dust);
	}

	/* ------------------------------------------------------------------ */
	/*                              病害可视化                              */
	/* ------------------------------------------------------------------ */

	private buildDiseases() {
		const rnd = mulberry32(90210);
		for (const code of DISEASE_STYLE_KEYS) {
			const style = DISEASE_STYLE[code];
			const group = new THREE.Group();
			group.name = `disease-${code}`;

			// 悬浮粒子（灰霉=灰绒毛雾 / 晚疫=暗色孢子 / 白粉=白色粉层 / 叶霉=黄绿霉斑）
			const N = 520;
			const pos = new Float32Array(N * 3);
			for (let i = 0; i < N; i++) {
				const bed = BED_Z[Math.floor(rnd() * ROWS) % ROWS];
				pos[i * 3] = (rnd() - 0.5) * (BED_LEN - 0.6);
				pos[i * 3 + 1] = 0.5 + rnd() * 1.9;
				pos[i * 3 + 2] = bed + (rnd() - 0.5) * (BED_WIDTH + 0.5);
			}
			const geo = new THREE.BufferGeometry();
			geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
			const tex = createRadialTexture(
				code === 'POWDERY_MILDEW' ? 'rgba(255,255,250,0.9)' : 'rgba(255,255,255,0.8)',
				'rgba(255,255,255,0)',
				64,
				code === 'BOTRYTIS' ? 1.6 : 0.8
			);
			const mat = new THREE.PointsMaterial({
				map: tex,
				size: style.size,
				sizeAttenuation: true,
				transparent: true,
				opacity: 0,
				depthWrite: false,
				color: style.color,
				blending: style.additive ? THREE.AdditiveBlending : THREE.NormalBlending,
			});
			const pts = new THREE.Points(geo, mat);
			pts.frustumCulled = false;
			pts.visible = false;
			group.add(pts);
			this.diseasePoints[code] = pts;

			// 苗床上方的半透明病斑
			const patchTex = createRadialTexture('rgba(255,255,255,0.85)', 'rgba(255,255,255,0)', 128, 0.75);
			const patchMat = new THREE.MeshBasicMaterial({
				map: patchTex,
				color: style.color,
				transparent: true,
				opacity: 0,
				depthWrite: false,
				blending: style.additive ? THREE.AdditiveBlending : THREE.NormalBlending,
				side: THREE.DoubleSide,
			});
			const patchGeo = new THREE.PlaneGeometry(BED_LEN - 1.5, BED_WIDTH + 1.6).rotateX(-Math.PI / 2);
			for (const z of BED_Z) {
				const p = new THREE.Mesh(patchGeo, patchMat);
				p.position.set(0, 1.85 + rnd() * 0.35, z);
				p.renderOrder = 3;
				group.add(p);
			}
			this.diseasePatchMat[code] = patchMat;
			this.diseaseAnchors[code] = new THREE.Vector3(0, 1.9, 0);
			group.visible = false;
			this.diseaseGroups[code] = group;
			this.scene.add(group);
			this.disposables.push(geo, tex, mat, patchGeo, patchTex, patchMat, pts);
		}
	}

	private buildCanopy() {
		const rnd = mulberry32(31337);
		const slots: PlantSlot[] = [];
		for (let r = 0; r < ROWS; r++) {
			for (let i = 0; i < PLANTS_PER_ROW; i++) {
				slots.push({
					x: -BED_HALF + PLANT_SPACING * (i + 0.5) + (rnd() - 0.5) * 0.07,
					z: BED_Z[r] + (rnd() - 0.5) * 0.16,
					row: r,
					phase: rnd() * Math.PI * 2,
					seed: 1000 + r * 977 + i * 31,
				});
			}
		}
		this.canopy = new TomatoCanopy({ slots, maxLeaves: 20, maxTrusses: 6, maxFruitPerTruss: 5, maxFlowers: 10 });
		this.scene.add(this.canopy.group);
	}

	/* ------------------------------------------------------------------ */
	/*                              数据 → 场景                             */
	/* ------------------------------------------------------------------ */

	/** 接收当前（可插值的）推演状态。所有动画都向目标值平滑过渡，不会跳变。 */
	applyState(s: TwinFrameState) {
		const t = this.target;
		t.lai = s.lai;
		t.plantHeightCm = s.plantHeightCm;
		t.fruitCount = s.fruitCount;
		t.singleFruitWeightG = s.singleFruitWeightG;
		t.fruitSetRate = s.fruitSetRate;
		t.mature = s.mature;
		t.ripeness = s.ripeness;
		t.lightPpfd = s.lightPpfd;
		t.irrigation = s.irrigation;
		t.ventilation = s.ventilation;
		t.supplementalLight = s.supplementalLight;
		t.shade = s.shade;
		t.co2 = s.co2;
		t.circulationFan = s.circulationFan ?? true;
		t.exhaustFan = s.exhaustFan ?? false;
		t.coolingPad = s.coolingPad ?? false;
		t.roofVent = s.roofVent ?? s.ventilation;
		t.temperatureC = s.temperatureC ?? 24;
		t.airHumidityPct = s.airHumidityPct ?? 68;
		t.co2Ppm = s.co2Ppm ?? 600;
		t.soilMoisturePct = s.soilMoisturePct ?? 55;
		t.sensorReadings = s.sensorReadings;
		t.hour = s.hour;
		t.dayOfYear = s.dayOfYear;
		// 注意：后端 severity 是 0~100，这里只保证非负，不要在这里截断成 0~1（否则真实数据会全部失真）
		for (const code of DISEASE_STYLE_KEYS) t.severity[code] = Math.max(0, s.severity?.[code] ?? 0);
	}

	private tick(dt: number) {
		const k = 1 - Math.exp(-dt * 6.5);
		const c = this.current;
		const t = this.target;

		c.lai = lerp(c.lai, t.lai, k);
		c.plantHeightCm = lerp(c.plantHeightCm, t.plantHeightCm, k);
		c.fruitCount = lerp(c.fruitCount, t.fruitCount, k);
		c.singleFruitWeightG = lerp(c.singleFruitWeightG, t.singleFruitWeightG, k);
		c.fruitSetRate = lerp(c.fruitSetRate, t.fruitSetRate, k);
		c.ripeness = lerp(c.ripeness, t.ripeness, k);
		c.lightPpfd = lerp(c.lightPpfd, t.lightPpfd, k);
		c.airHumidityPct = lerp(c.airHumidityPct ?? 68, t.airHumidityPct ?? 68, k);
		c.mature = t.mature;
		c.hour = t.hour;
		c.dayOfYear = t.dayOfYear;

		// —— 番茄冠层 ——
		this.canopy.update({
			lai: c.lai,
			plantHeightCm: c.plantHeightCm,
			fruitCount: c.fruitCount,
			singleFruitWeightG: c.singleFruitWeightG,
			fruitSetRate: c.fruitSetRate,
			mature: c.mature,
			ripeness: c.ripeness,
			airHumidityPct: c.airHumidityPct ?? 68,
		});
		this.canopy.setTime(this.elapsed);

		// —— 侧窗开合 ——
		this.ventOpen = lerp(this.ventOpen, t.ventilation ? 1 : 0, 1 - Math.exp(-dt * 1.5));
		this.writeVents();
		this.canopy.setWind(0.45 + this.ventOpen * 1.1 + (t.circulationFan ? 0.32 : 0));

		// —— 遮阳幕滑动 ——
		this.curtainDeploy = lerp(this.curtainDeploy, t.shade ? 1 : 0, 1 - Math.exp(-dt * 0.85));
		const len = Math.max(0.02, this.curtainDeploy) * (HALF_W - 0.35);
		this.curtainGroup.visible = this.curtainDeploy > 0.015;
		for (const child of this.curtainGroup.children) {
			if (child.name.startsWith('curtain-panel')) child.scale.z = len;
			else child.position.z = (child.userData.side as number) * len;
		}

		this.equipment.update({
			irrigation: t.irrigation,
			ventilation: t.ventilation,
			supplementalLight: t.supplementalLight,
			shade: t.shade,
			co2: t.co2 && !t.ventilation && !t.roofVent && !t.exhaustFan,
			circulationFan: Boolean(t.circulationFan),
			exhaustFan: Boolean(t.exhaustFan),
			coolingPad: Boolean(t.coolingPad),
			roofVent: Boolean(t.roofVent),
			temperatureC: t.temperatureC ?? 24,
			airHumidityPct: t.airHumidityPct ?? 68,
			co2Ppm: t.co2Ppm ?? 600,
			lightPpfd: t.lightPpfd,
			soilMoisturePct: t.soilMoisturePct ?? 55,
			sensorReadings: t.sensorReadings,
		}, dt);

		// —— 滴灌 ——
		this.irrigationMix = lerp(this.irrigationMix, t.irrigation ? 1 : 0, 1 - Math.exp(-dt * 2.2));
		this.wetMix = lerp(this.wetMix, t.irrigation ? 1 : 0, 1 - Math.exp(-dt * (t.irrigation ? 0.55 : 0.14)));
		this.wetMat.opacity = this.wetMix * 0.66;
		this.irrigationGlowMat.opacity = this.irrigationMix * 0.55;
		this.droplets.visible = this.irrigationMix > 0.03;
		if (this.droplets.visible) {
			(this.droplets.material as THREE.PointsMaterial).opacity = this.irrigationMix * 0.95;
			const arr = (this.droplets.geometry.getAttribute('position') as THREE.BufferAttribute).array as Float32Array;
			for (let i = 0; i < this.dropPhase.length; i++) {
				this.dropPhase[i] += dt * (1.55 + (i % 7) * 0.14);
				if (this.dropPhase[i] > 1) this.dropPhase[i] -= 1;
				arr[i * 3 + 1] = 0.3 - this.dropPhase[i] * 0.058;
			}
			(this.droplets.geometry.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true;
		}

		// —— CO₂ 补给 ——
		this.co2Mix = lerp(this.co2Mix, t.co2 && !t.ventilation && !t.roofVent && !t.exhaustFan ? 1 : 0, 1 - Math.exp(-dt * 0.7));
		(this.co2Haze.material as THREE.PointsMaterial).opacity = this.co2Mix * 0.3;
		this.co2Haze.rotation.y += dt * 0.026;

		// —— 补光灯 ——
		this.lampGlow = lerp(this.lampGlow, t.supplementalLight ? 1 : 0, 1 - Math.exp(-dt * 2.4));
		this.lampMat.emissiveIntensity = this.lampGlow * 1.7;
		this.lampConeMat.opacity = this.lampGlow * 0.075;
		for (const l of this.lampLights) l.intensity = this.lampGlow * 8;

		// —— 病害覆盖 ——
		let anchorY = 0.6;
		for (const code of DISEASE_STYLE_KEYS) {
			// 后端 severity 是 0~100 的百分比，视觉强度归一到 0~1（否则病斑会瞬间饱和）
			const v = clamp((t.severity[code] ?? 0) / 100, 0, 1);
			this.diseaseMix[code] = lerp(this.diseaseMix[code], v, 1 - Math.exp(-dt * 1.3));
			const m = this.diseaseMix[code];
			const group = this.diseaseGroups[code];
			group.visible = m > 0.012;
			if (!group.visible) continue;
			const style = DISEASE_STYLE[code];
			(this.diseasePoints[code].material as THREE.PointsMaterial).opacity = clamp(m * style.opacity * 1.5, 0, 0.88);
			this.diseasePatchMat[code].opacity = clamp(m * 0.6, 0, 0.68);
			this.diseasePoints[code].position.y = Math.sin(this.elapsed * 0.35 + code.length) * 0.07;
			this.diseasePoints[code].rotation.y += dt * 0.018 * (1 + style.drift);
			anchorY = Math.max(anchorY, 1.25 + (c.plantHeightCm / 100) * 0.62);
		}
		// 标签锚点沿温室长度方向铺开、并做高低错位，避免 4 个标签叠在同一个屏幕位置
		DISEASE_STYLE_KEYS.forEach((code, i) => {
			this.diseaseAnchors[code].set(-6.5 + i * 4.4, anchorY + (i % 2) * 0.55, (i % 2 === 0 ? -1 : 1) * 2.8);
		});

		// —— 尘埃 ——
		this.dust.rotation.y += dt * 0.008;

		// —— 昼夜 / 太阳 ——
		this.updateSky();
		this.updateEnvironment();
	}

	private writeVents() {
		const m4 = new THREE.Matrix4();
		const q = new THREE.Quaternion();
		const one = new THREE.Vector3(1, 1, 1);
		const p = new THREE.Vector3();
		let i = 0;
		for (const s of [-1, 1]) {
			for (let k = 0; k < VENT_SLATS; k++) {
				const y = 1.16 + k * 0.42;
				const ang = this.ventOpen * (s > 0 ? -1.16 : 1.16);
				q.setFromAxisAngle(UP_AXIS_X, ang);
				p.set(0, y, s * (HALF_W + 0.012));
				m4.compose(p, q, one);
				this.ventMesh.setMatrixAt(i++, m4);
			}
		}
		this.ventMesh.instanceMatrix.needsUpdate = true;
	}

	private updateSky() {
		const { el, az } = solarAngles(this.current.dayOfYear, this.current.hour);
		const elDeg = el / DEG;
		const dayF = smoothstep(clamp((elDeg + 3) / 14, 0, 1));
		const duskF = clamp(1 - Math.abs(elDeg - 1.5) / 11, 0, 1);
		const daylight = clamp(this.current.lightPpfd / 650, 0.32, 1.3);

		const top = SCRATCH_A.set('#070c1c').lerp(SCRATCH_B.set('#2e6cbb'), dayF);
		const hor = SCRATCH_B.set('#141d36').lerp(SCRATCH_C.set('#cfe0ec'), dayF).lerp(SCRATCH_D.set('#f2a45c'), duskF * 0.72);
		const bot = SCRATCH_C.set('#0d1018').lerp(SCRATCH_D.set('#4a4a42'), dayF);

		const uTop = this.skyMat.uniforms.uTop.value as THREE.Color;
		const uHor = this.skyMat.uniforms.uHorizon.value as THREE.Color;
		const uBot = this.skyMat.uniforms.uBottom.value as THREE.Color;
		uTop.copy(top).multiplyScalar(0.35 + 0.65 * dayF * daylight + 0.1);
		uHor.copy(hor).multiplyScalar(0.4 + 0.6 * dayF * daylight + 0.06);
		uBot.copy(bot).multiplyScalar(0.4 + 0.6 * dayF);
		this.skyMat.uniforms.uStars.value = clamp(1 - dayF * 1.6, 0, 1) * 0.9;

		const sunI = sampleSun(elDeg, this.sunColor) * daylight;
		this.sun.color.copy(this.sunColor);
		this.sun.intensity = sunI;
		sunDirection(el, az, this.tmpVec);
		(this.skyMat.uniforms.uSunDir.value as THREE.Vector3).copy(this.tmpVec);
		(this.skyMat.uniforms.uSunColor.value as THREE.Color).copy(this.sunColor);
		this.skyMat.uniforms.uSunI.value = clamp(sunI / 3.4, 0, 1.4);
		this.sun.position.copy(this.tmpVec).multiplyScalar(72);
		this.sun.visible = sunI > 0.03;

		const fog = this.scene.fog as THREE.Fog;
		fog.color.copy(hor).multiplyScalar(0.42 + 0.5 * dayF);
		fog.near = 42;
		fog.far = 170;

		this.hemi.color.copy(hor);
		this.hemi.groundColor.set('#4a4636').multiplyScalar(0.35 + 0.5 * dayF);
		this.hemi.intensity = 0.2 + 0.92 * dayF * daylight + 0.28 * this.lampGlow;
		this.fill.intensity = 0.3 + 0.5 * (1 - dayF) + 0.22 * this.lampGlow;
		this.ambient.intensity = 0.06 + 0.1 * dayF + 0.05 * this.lampGlow;
		this.renderer.toneMappingExposure = 0.98 + 0.12 * (1 - dayF) + 0.06 * this.lampGlow;
	}

	/** 用当前天空色重绘等距圆柱环境贴图并做 PMREM（按小时节流） */
	private updateEnvironment(force = false) {
		const hour = this.current.hour;
		if (!force && Math.abs(hour - this.envHour) < 0.3) return;
		this.envHour = hour;
		const { el, az } = solarAngles(this.current.dayOfYear, hour);
		const elDeg = el / DEG;
		const dayF = smoothstep(clamp((elDeg + 3) / 14, 0, 1));
		const W = this.envCanvas.width;
		const H = this.envCanvas.height;
		const ctx = this.envCtx;
		const top = `rgb(${Math.round(lerp(7, 52, dayF))},${Math.round(lerp(12, 108, dayF))},${Math.round(lerp(28, 180, dayF))})`;
		const mid = `rgb(${Math.round(lerp(20, 178, dayF))},${Math.round(lerp(28, 205, dayF))},${Math.round(lerp(48, 228, dayF))})`;
		const bot = `rgb(${Math.round(lerp(14, 96, dayF))},${Math.round(lerp(16, 92, dayF))},${Math.round(lerp(22, 74, dayF))})`;
		const grad = ctx.createLinearGradient(0, 0, 0, H);
		grad.addColorStop(0, top);
		grad.addColorStop(0.5, mid);
		grad.addColorStop(1, bot);
		ctx.fillStyle = grad;
		ctx.fillRect(0, 0, W, H);
		const dir = sunDirection(el, az, this.tmpVec);
		const sx = ((Math.atan2(dir.x, -dir.z) / (Math.PI * 2) + 0.5) * W + W) % W;
		const sy = (0.5 - Math.asin(clamp(dir.y, -1, 1)) / Math.PI) * H;
		const rad = ctx.createRadialGradient(sx, sy, 0, sx, sy, 30);
		rad.addColorStop(0, `rgba(255,246,220,${(0.95 * dayF + 0.08).toFixed(3)})`);
		rad.addColorStop(0.22, `rgba(255,216,164,${(0.44 * dayF).toFixed(3)})`);
		rad.addColorStop(1, 'rgba(255,200,140,0)');
		ctx.fillStyle = rad;
		ctx.fillRect(0, 0, W, H);
		this.envTex.needsUpdate = true;
		if (this.envTarget) this.envTarget.dispose();
		this.envTarget = this.pmrem.fromEquirectangular(this.envTex);
		this.scene.environment = this.envTarget.texture;
	}

	/* ------------------------------------------------------------------ */
	/*                              相机预设                                */
	/* ------------------------------------------------------------------ */

	setCameraPreset(preset: CameraPreset) {
		const target = this.controls.target.clone();
		let pos: THREE.Vector3;
		switch (preset) {
			case 'overview':
				pos = new THREE.Vector3(17.5, 8.6, 16.5);
				target.set(0, 1.7, 0);
				this.followFruit = false;
				break;
			case 'closeup':
				pos = new THREE.Vector3(8.4, 1.7, 0.75);
				target.set(0.5, 1.55, 0.75);
				this.followFruit = false;
				break;
			case 'top':
				pos = new THREE.Vector3(0.01, 21.5, 0.7);
				target.set(0, 0, 0);
				this.followFruit = false;
				break;
			case 'fruit':
			default: {
				const f = this.canopy.getFocusPoint().clone();
				target.copy(f);
				pos = f.clone().add(new THREE.Vector3(-1.35, 0.75, 1.55));
				this.followFruit = true;
				break;
			}
		}
		this.autoRotate = false;
		this.controls.autoRotate = false;
		if (this.navigationMode === 'fly') {
			this.camera.position.copy(pos);
			this.camera.lookAt(target);
			this.controls.target.copy(target);
			this.freeControls.syncOrientation();
			this.followFruit = false;
			return;
		}
		this.tween = {
			p0: this.camera.position.clone(),
			p1: pos,
			t0: this.controls.target.clone(),
			t1: target,
			k: 0,
			dur: preset === 'top' ? 1.5 : 1.2,
		};
	}

	setAutoRotate(on: boolean) {
		this.autoRotate = on && this.navigationMode === 'orbit';
		this.controls.autoRotate = this.autoRotate;
		if (on) this.followFruit = false;
	}

	setNavigationMode(mode: 'orbit' | 'fly') {
		if (this.navigationMode === mode) return;
		if (mode === 'fly') {
			this.autoRotate = false;
			this.controls.autoRotate = false;
			this.followFruit = false;
			this.tween = null;
			this.controls.enabled = false;
			this.navigationMode = 'fly';
			this.freeControls.activate();
			return;
		}
		this.freeControls.deactivate();
		this.camera.getWorldDirection(this.tmpVec);
		const distance = THREE.MathUtils.clamp(this.camera.position.distanceTo(this.controls.target), 1.5, 6);
		this.controls.target.copy(this.camera.position).addScaledVector(this.tmpVec, distance);
		this.controls.enabled = true;
		this.navigationMode = 'orbit';
		this.controls.update();
	}

	getNavigationMode(): 'orbit' | 'fly' {
		return this.navigationMode;
	}

	setShellMode(mode: 'solid' | 'translucent' | 'cutaway') {
		this.shellMode = mode;
		for (const roof of this.shellRoof) {
			roof.visible = mode !== 'cutaway';
			roof.material = mode === 'solid' && this.quality !== 'low' && !this.insideShell ? this.matGlass : this.matGlassFallback;
		}
		for (const wall of this.shellWalls) {
			wall.visible = mode !== 'cutaway' || wall.position.z < 0;
			wall.material = mode === 'solid' && this.quality !== 'low' && !this.insideShell ? this.matGlass : this.matGlassFallback;
		}
	}

	setQuality(preference: 'auto' | TwinStats['quality']) {
		this.automaticQuality = preference === 'auto';
		this.degraded = false;
		this.lowFpsTime = 0;
		this.applyQuality(preference === 'auto' ? 'high' : preference);
	}

	private applyQuality(quality: TwinStats['quality']) {
		this.quality = quality;
		this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, quality === 'high' ? 2 : quality === 'medium' ? 1.5 : 1));
		const shadowSize = quality === 'high' ? 2048 : quality === 'medium' ? 1536 : 1024;
		this.sun.shadow.mapSize.set(shadowSize, shadowSize);
		if (this.sun.shadow.map) {
			this.sun.shadow.map.dispose();
			this.sun.shadow.map = null;
		}
		this.setShellMode(this.shellMode);
		this.resize();
	}

	onInspect(handler: (entry: InspectionInfo | null) => void) {
		this.inspectHandler = handler;
	}

	getInspectionInfo(code: string): InspectionInfo | null {
		const entry = this.equipment.entries.find((item) => item.code === code);
		if (!entry) return null;
		const { object, ...info } = entry;
		return { ...info };
	}

	getEquipmentList(): Array<Pick<EquipmentEntry, 'code' | 'name' | 'zone' | 'kind'>> {
		return this.equipment.entries.map(({ code, name, zone, kind }) => ({ code, name, zone, kind }));
	}

	focusEquipment(code: string): InspectionInfo | null {
		const entry = this.equipment.entries.find((item) => item.code === code);
		if (!entry) return null;
		const target = entry.object.getWorldPosition(new THREE.Vector3());
		const direction = target.z > 0 ? -1 : 1;
		const position = target.clone().add(new THREE.Vector3(2.2, 1.15, direction * 2));
		this.followFruit = false;
		this.autoRotate = false;
		this.controls.autoRotate = false;
		if (this.navigationMode === 'fly') {
			this.camera.position.copy(position);
			this.camera.lookAt(target);
			this.controls.target.copy(target);
			this.freeControls.syncOrientation();
			this.canvas.focus();
		} else {
			this.tween = {
				p0: this.camera.position.clone(), p1: position,
				t0: this.controls.target.clone(), t1: target,
				k: 0, dur: 1.1,
			};
		}
		return this.getInspectionInfo(code);
	}

	private handleInspection = (event: MouseEvent) => {
		if (!this.inspectHandler) return;
		const rect = this.canvas.getBoundingClientRect();
		if (this.navigationMode === 'fly' && document.pointerLockElement === this.canvas) this.pointer.set(0, 0);
		else this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
		this.raycaster.setFromCamera(this.pointer, this.camera);
		for (const hit of this.raycaster.intersectObject(this.equipment.roots, true)) {
			let node: THREE.Object3D | null = hit.object;
			while (node && !node.userData.deviceCode) node = node.parent;
			const entry = this.equipment.entries.find((item) => item.code === node?.userData.deviceCode);
			if (entry) {
				const { object, ...info } = entry;
				this.inspectHandler({ ...info });
				return;
			}
		}
		this.inspectHandler(null);
	};

	/** 供 HUD 叠加层使用：4 种病害标签的屏幕坐标 */
	getDiseaseMarkers(): DiseaseMarker[] {
		const out: DiseaseMarker[] = [];
		for (const code of DISEASE_STYLE_KEYS) {
			const group = this.diseaseGroups[code];
			if (!group || !group.visible) {
				out.push({ code, x: 0, y: 0, visible: false });
				continue;
			}
			const v = this.diseaseAnchors[code].clone().project(this.camera);
			out.push({
				code,
				x: (v.x * 0.5 + 0.5) * this.sizeW,
				y: (-v.y * 0.5 + 0.5) * this.sizeH,
				visible: v.z < 1 && v.z > -1,
			});
		}
		return out;
	}

	getStats(): TwinStats {
		const info = this.renderer.info.render;
		const size = this.renderer.getDrawingBufferSize(new THREE.Vector2());
		return {
			fps: this.fps,
			drawCalls: info.calls,
			triangles: info.triangles,
			quality: this.quality,
			degraded: this.degraded,
			pixelRatio: this.renderer.getPixelRatio(),
			width: Math.round(size.x),
			height: Math.round(size.y),
		};
	}

	/* ------------------------------------------------------------------ */
	/*                              尺寸 / 帧循环                           */
	/* ------------------------------------------------------------------ */

	resize() {
		const rect = this.container.getBoundingClientRect();
		const w = Math.max(2, Math.floor(rect.width || this.container.clientWidth || 2));
		const h = Math.max(2, Math.floor(rect.height || this.container.clientHeight || 2));
		this.sizeW = w;
		this.sizeH = h;
		this.camera.aspect = w / h;
		this.camera.updateProjectionMatrix();
		this.renderer.setSize(w, h, false);
	}

	private loop = () => {
		if (this.disposed || this.paused) {
			this.raf = 0;
			return;
		}
		this.raf = requestAnimationFrame(this.loop);
		const now = performance.now();
		const rawDt = this.clock.last ? (now - this.clock.last) / 1000 : 0.016;
		// 动画用钳制后的 dt（卡顿后不跳变）；帧率统计必须用真实 dt，否则低帧率会被误判为平稳
		const dt = Math.min(0.05, rawDt);
		this.clock.last = now;
		this.elapsed += dt;

		this.tick(dt);

		// 相机补间
		if (this.tween) {
			this.tween.k = Math.min(1, this.tween.k + dt / this.tween.dur);
			const e = smoothstep(this.tween.k);
			this.camera.position.lerpVectors(this.tween.p0, this.tween.p1, e);
			this.controls.target.lerpVectors(this.tween.t0, this.tween.t1, e);
			if (this.tween.k >= 1) this.tween = null;
		} else if (this.followFruit) {
			const f = this.canopy.getFocusPoint();
			const delta = this.tmpVec.copy(f).sub(this.controls.target).multiplyScalar(0.045);
			this.controls.target.add(delta);
			this.camera.position.add(delta);
		}

		if (this.navigationMode === 'fly') this.freeControls.update(dt);
		else this.controls.update();
		const inside = Math.abs(this.camera.position.x) < HALF_L && Math.abs(this.camera.position.z) < HALF_W && this.camera.position.y < roofHeightAt(this.camera.position.z);
		if (inside !== this.insideShell) {
			this.insideShell = inside;
			this.setShellMode(this.shellMode);
		}
		this.renderer.info.reset();
		this.renderer.render(this.scene, this.camera);

		// 帧率统计与自动降级（基于真实耗时）
		this.frameCount++;
		this.fpsTimer += rawDt;
		if (this.fpsTimer >= 0.5) {
			this.fps = this.frameCount / this.fpsTimer;
			this.frameCount = 0;
			this.fpsTimer = 0;
			this.lowFpsTime = this.fps < 38 ? this.lowFpsTime + 0.5 : Math.max(0, this.lowFpsTime - 0.3);
			if (this.automaticQuality && this.lowFpsTime >= 2.5) this.degrade();
		}
	};

	private degrade() {
		if (this.degraded) return;
		this.degraded = true;
		this.applyQuality('medium');
	}

	private handleVisibility() {
		if (document.hidden) this.pause();
		else this.resume();
	}

	pause() {
		this.paused = true;
		if (this.raf) cancelAnimationFrame(this.raf);
		this.raf = 0;
	}

	resume() {
		if (this.disposed || !this.paused) return;
		this.paused = false;
		this.clock.last = 0; // 丢弃暂停期间的时间差
		if (!this.raf) this.raf = requestAnimationFrame(this.loop);
	}

	dispose() {
		if (this.disposed) return;
		this.disposed = true;
		this.paused = true;
		if (this.raf) cancelAnimationFrame(this.raf);
		this.raf = 0;
		document.removeEventListener('visibilitychange', this.onVisibility);
		this.canvas.removeEventListener('click', this.handleInspection);
		this.inspectHandler = null;

		this.scene.traverse((obj) => {
			const mesh = obj as THREE.Mesh;
			if (mesh.geometry && typeof mesh.geometry.dispose === 'function') mesh.geometry.dispose();
			const mat = mesh.material as THREE.Material | THREE.Material[] | undefined;
			if (Array.isArray(mat)) mat.forEach((m) => disposeMaterial(m));
			else if (mat) disposeMaterial(mat);
			const inst = obj as THREE.InstancedMesh;
			if (inst.isInstancedMesh) inst.dispose();
		});

		for (const d of this.disposables) {
			try {
				d.dispose?.();
			} catch {
				/* 忽略重复释放 */
			}
		}
		if (this.envTarget) this.envTarget.dispose();
		this.envTarget = null;
		if (this.canopy) this.canopy.dispose();
		this.renderer.renderLists?.dispose?.();
		this.renderer.dispose();
		try {
			this.renderer.forceContextLoss();
		} catch {
			/* 某些浏览器不支持 */
		}
		this.scene.clear();
	}
}

const disposeMaterial = (m: THREE.Material) => {
	for (const key of Object.keys(m as unknown as Record<string, unknown>)) {
		const v = (m as unknown as Record<string, { isTexture?: boolean; dispose?: () => void }>)[key];
		if (v && v.isTexture && typeof v.dispose === 'function') v.dispose();
	}
	m.dispose();
};
