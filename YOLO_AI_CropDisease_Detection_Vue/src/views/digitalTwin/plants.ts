/**
 * 程序化番茄植株（数字孪生用）
 *
 * 设计要点：
 * 1. 全部几何体与贴图在运行时生成，不依赖任何外部资源 / CAD / 二进制素材。
 * 2. 所有器官（茎、叶柄、叶片、果穗、果实、花、花萼）都用 InstancedMesh 渲染，
 *    整栋温室 ≈ 8 个 draw call。
 * 3. 风摆动写在顶点着色器里（onBeforeCompile 注入），每帧只更新一个 uTime uniform，
 *    CPU 零开销，因此几十株植物也能稳定在 45fps 以上。
 * 4. 器官形态由数据驱动：
 *      lai               → 叶片数量（lai<0.1 只显示 2 片叶，呈幼苗状）
 *      plantHeightCm     → 茎高与整体缩放
 *      singleFruitWeightG→ 果实半径（按鲜重→体积→半径反算，密度 0.95 g/cm³）
 *      fruitCount        → 果实数量（按果穗依次填充）
 *      fruitSetRate      → 花数量（坐果后花逐渐消失）
 *      mature / 成熟度    → 果实颜色（绿 → 黄绿 → 橙 → 红）
 */
import * as THREE from 'three';

const UP = new THREE.Vector3(0, 1, 0);
const DEG = Math.PI / 180;

/* -------------------------------------------------------------------------- */
/*                              运行时贴图生成                                 */
/* -------------------------------------------------------------------------- */

/** 复叶叶片：主脉 + 3 对侧生小叶 + 顶小叶，带锯齿边缘，透明背景 */
export const createLeafTexture = (size = 256): THREE.Texture => {
	const canvas = document.createElement('canvas');
	canvas.width = size;
	canvas.height = size;
	const ctx = canvas.getContext('2d')!;
	ctx.clearRect(0, 0, size, size);

	const drawLeaflet = (x: number, y: number, len: number, wid: number, angle: number, shade: number) => {
		ctx.save();
		ctx.translate(x, y);
		ctx.rotate(angle);
		const steps = 30;
		ctx.beginPath();
		ctx.moveTo(0, 0);
		for (let i = 0; i <= steps; i++) {
			const t = i / steps;
			const w = wid * Math.sin(Math.PI * Math.pow(t, 0.62)) * (1 + 0.1 * Math.sin(t * 34));
			ctx.lineTo(w, -len * t);
		}
		for (let i = steps; i >= 0; i--) {
			const t = i / steps;
			const w = wid * Math.sin(Math.PI * Math.pow(t, 0.62)) * (1 + 0.1 * Math.sin(t * 34 + 1.7));
			ctx.lineTo(-w, -len * t);
		}
		ctx.closePath();
		const g = ctx.createLinearGradient(0, 0, 0, -len);
		g.addColorStop(0, `rgb(${56 + shade}, ${96 + shade}, ${34 + shade * 0.6})`);
		g.addColorStop(0.45, `rgb(${74 + shade}, ${132 + shade}, ${44 + shade * 0.6})`);
		g.addColorStop(1, `rgb(${48 + shade}, ${92 + shade}, ${30 + shade * 0.6})`);
		ctx.fillStyle = g;
		ctx.fill();

		// 主脉
		ctx.strokeStyle = 'rgba(196, 222, 156, 0.5)';
		ctx.lineWidth = Math.max(1, size / 220);
		ctx.beginPath();
		ctx.moveTo(0, -2);
		ctx.lineTo(0, -len * 0.95);
		ctx.stroke();

		// 侧脉
		ctx.lineWidth = Math.max(0.6, size / 420);
		ctx.strokeStyle = 'rgba(180, 210, 140, 0.32)';
		for (let i = 1; i <= 7; i++) {
			const t = i / 8.5;
			const w = wid * Math.sin(Math.PI * Math.pow(t, 0.62));
			ctx.beginPath();
			ctx.moveTo(0, -len * t);
			ctx.lineTo(w * 0.72, -len * (t + 0.11));
			ctx.moveTo(0, -len * t);
			ctx.lineTo(-w * 0.72, -len * (t + 0.11));
			ctx.stroke();
		}
		ctx.restore();
	};

	const S = size / 256;
	// 叶轴
	ctx.strokeStyle = 'rgba(104, 148, 62, 0.95)';
	ctx.lineWidth = 3.6 * S;
	ctx.lineCap = 'round';
	ctx.beginPath();
	ctx.moveTo(128 * S, 246 * S);
	ctx.quadraticCurveTo(126 * S, 150 * S, 128 * S, 24 * S);
	ctx.stroke();

	// 顶小叶
	drawLeaflet(128 * S, 44 * S, 52 * S, 20 * S, 0, 4);
	// 3 对侧生小叶
	const pairs: Array<[number, number, number, number]> = [
		[88, 56, 44, 44],
		[150, 92, 50, 46],
		[74, 142, 44, 50],
		[164, 186, 40, 56],
		[86, 196, 36, 60],
	];
	for (let i = 0; i < pairs.length; i++) {
		const [y, len, wid, ang] = pairs[i];
		const side = i % 2 === 0 ? -1 : 1;
		const x = side < 0 ? 128 * S - 6 * S : 128 * S + 6 * S;
		drawLeaflet(x, y * S, len * S, wid * S, side * ang * DEG, i * 2);
	}

	// 叶面斑驳（提升真实感）
	const rnd = mulberry32(20260921);
	for (let i = 0; i < 260; i++) {
		const px = rnd() * size;
		const py = rnd() * size;
		const r = 0.6 * S + rnd() * 2.6 * S;
		ctx.fillStyle = rnd() > 0.5 ? `rgba(150, 190, 110, ${0.05 + rnd() * 0.09})` : `rgba(30, 60, 20, ${0.04 + rnd() * 0.08})`;
		ctx.beginPath();
		ctx.arc(px, py, r, 0, Math.PI * 2);
		ctx.fill();
	}

	const tex = new THREE.CanvasTexture(canvas);
	tex.colorSpace = THREE.SRGBColorSpace;
	tex.anisotropy = 4;
	return tex;
};

/** 番茄花：5 枚黄色花瓣 + 橙色花心 */
export const createFlowerTexture = (size = 64): THREE.Texture => {
	const canvas = document.createElement('canvas');
	canvas.width = size;
	canvas.height = size;
	const ctx = canvas.getContext('2d')!;
	const c = size / 2;
	ctx.clearRect(0, 0, size, size);
	for (let i = 0; i < 5; i++) {
		const a = (i / 5) * Math.PI * 2 - Math.PI / 2;
		ctx.save();
		ctx.translate(c, c);
		ctx.rotate(a);
		const g = ctx.createLinearGradient(0, 0, 0, -c * 0.95);
		g.addColorStop(0, '#e8c22a');
		g.addColorStop(1, '#f7e46a');
		ctx.fillStyle = g;
		ctx.beginPath();
		ctx.moveTo(0, 0);
		ctx.quadraticCurveTo(c * 0.34, -c * 0.5, 0, -c * 0.95);
		ctx.quadraticCurveTo(-c * 0.34, -c * 0.5, 0, 0);
		ctx.fill();
		ctx.restore();
	}
	const cg = ctx.createRadialGradient(c, c, 0, c, c, c * 0.32);
	cg.addColorStop(0, '#f6d33a');
	cg.addColorStop(1, '#b8860b');
	ctx.fillStyle = cg;
	ctx.beginPath();
	ctx.arc(c, c, c * 0.3, 0, Math.PI * 2);
	ctx.fill();
	const tex = new THREE.CanvasTexture(canvas);
	tex.colorSpace = THREE.SRGBColorSpace;
	return tex;
};

/** 通用径向渐变贴图（粒子 / 光晕 / 湿斑） */
export const createRadialTexture = (inner = 'rgba(255,255,255,1)', outer = 'rgba(255,255,255,0)', size = 64, power = 0.5): THREE.Texture => {
	const canvas = document.createElement('canvas');
	canvas.width = size;
	canvas.height = size;
	const ctx = canvas.getContext('2d')!;
	const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
	for (let i = 0; i <= 8; i++) {
		const t = i / 8;
		g.addColorStop(t, i === 0 ? inner : mixRgba(inner, outer, Math.pow(t, power)));
	}
	g.addColorStop(1, outer);
	ctx.fillStyle = g;
	ctx.fillRect(0, 0, size, size);
	const tex = new THREE.CanvasTexture(canvas);
	tex.colorSpace = THREE.SRGBColorSpace;
	return tex;
};

const parseRgba = (s: string): [number, number, number, number] => {
	const m = /rgba?\(([^)]+)\)/.exec(s);
	if (!m) return [255, 255, 255, 1];
	const p = m[1].split(',').map((v) => parseFloat(v));
	return [p[0] || 0, p[1] || 0, p[2] || 0, p.length > 3 ? p[3] : 1];
};

const mixRgba = (a: string, b: string, t: number): string => {
	const A = parseRgba(a);
	const B = parseRgba(b);
	return `rgba(${Math.round(A[0] + (B[0] - A[0]) * t)},${Math.round(A[1] + (B[1] - A[1]) * t)},${Math.round(A[2] + (B[2] - A[2]) * t)},${(A[3] + (B[3] - A[3]) * t).toFixed(3)})`;
};

/** 确定性伪随机 */
const mulberry32 = (seed: number) => {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
};

/* -------------------------------------------------------------------------- */
/*                                  工具函数                                   */
/* -------------------------------------------------------------------------- */

/** 鲜重(g) → 果实半径(m)：按密度 0.95 g/cm³ 反算球体半径 */
export const fruitRadiusFromMass = (gram: number): number => {
	if (!Number.isFinite(gram) || gram <= 0) return 0;
	const volumeM3 = (gram / 0.95) * 1e-6;
	return Math.cbrt((3 * volumeM3) / (4 * Math.PI));
};

const HIDE = 1e-4; // 隐藏实例使用极小缩放而不是 0，避免法线矩阵退化

/* -------------------------------------------------------------------------- */
/*                                  植株布局                                   */
/* -------------------------------------------------------------------------- */

export interface PlantSlot {
	x: number;
	z: number;
	row: number;
	/** 每株固定的相位，保证摆动不同步 */
	phase: number;
	/** 形态随机数种子 */
	seed: number;
}

export interface CanopyState {
	lai: number;
	plantHeightCm: number;
	fruitCount: number;
	singleFruitWeightG: number;
	fruitSetRate: number;
	mature: boolean;
	/** 0~1 成熟度（用于果色渐变） */
	ripeness: number;
}

/* -------------------------------------------------------------------------- */
/*                              风摆动着色器注入                                */
/* -------------------------------------------------------------------------- */

const WIND_PROJECT = /* glsl */ `
	vec4 mvPosition = vec4( transformed, 1.0 );
	#ifdef USE_INSTANCING
		mvPosition = instanceMatrix * mvPosition;
	#endif
	float wHeight = clamp( mvPosition.y / max( uPlantH, 0.05 ), 0.0, 1.0 );
	float wPhase = mvPosition.x * 0.85 + mvPosition.z * 1.25;
	float wSway = sin( uTime * 1.15 + wPhase ) * 0.62 + sin( uTime * 2.35 + wPhase * 1.7 ) * 0.38;
	float wAmp = uWind * wHeight * wHeight;
	mvPosition.x += wSway * 0.048 * wAmp;
	mvPosition.z += cos( uTime * 0.9 + wPhase * 0.8 ) * 0.032 * wAmp;
	mvPosition = modelViewMatrix * mvPosition;
	gl_Position = projectionMatrix * mvPosition;
`;

const injectWind = (material: THREE.Material, uTime: THREE.IUniform, uWind: THREE.IUniform, uPlantH: THREE.IUniform, key: string) => {
	material.onBeforeCompile = (shader) => {
		shader.uniforms.uTime = uTime;
		shader.uniforms.uWind = uWind;
		shader.uniforms.uPlantH = uPlantH;
		shader.vertexShader = shader.vertexShader
			.replace('#include <common>', '#include <common>\nuniform float uTime;\nuniform float uWind;\nuniform float uPlantH;')
			.replace('#include <project_vertex>', WIND_PROJECT);
	};
	material.customProgramCacheKey = () => key;
};

/* -------------------------------------------------------------------------- */
/*                              实例化器官容器                                 */
/* -------------------------------------------------------------------------- */

/**
 * 单个实例化器官的描述。
 * 布局在「H_MAX 高度下的米制坐标」中一次性烘焙好，运行时用两个标量驱动：
 *   posScale —— 位置缩放（= 实际株高 / H_MAX），让整株按 plantHeightCm 长高
 *   factor   —— 器官尺寸缩放（叶片/茎粗等，可与 posScale 不同）
 * 果实的 factor 直接用物理半径（米），因此不受株高缩放影响。
 */
interface OrganEntry {
	/** 植株根部世界坐标 */
	origin: THREE.Vector3;
	/** 相对植株根部的位置（H_MAX 下的米制） */
	rel: THREE.Vector3;
	quat: THREE.Quaternion;
	scale: THREE.Vector3;
	/** 植株序号 */
	plant: number;
	/** 器官在植株内的序号（叶片序号 / 果穗序号 / 果序号 / 花序号） */
	slot: number;
}

class Organ {
	readonly mesh: THREE.InstancedMesh;
	readonly entries: OrganEntry[] = [];
	private m = new THREE.Matrix4();
	private s = new THREE.Vector3();
	private p = new THREE.Vector3();

	constructor(geometry: THREE.BufferGeometry, material: THREE.Material, capacity: number, castShadow: boolean, receiveShadow = false) {
		this.mesh = new THREE.InstancedMesh(geometry, material, Math.max(1, capacity));
		this.mesh.castShadow = castShadow;
		this.mesh.receiveShadow = receiveShadow;
		this.mesh.frustumCulled = false;
		this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
		this.mesh.count = 0;
	}

	add(origin: THREE.Vector3, rel: THREE.Vector3, quat: THREE.Quaternion, scale: THREE.Vector3, plant: number, slot: number) {
		this.entries.push({ origin, rel, quat, scale, plant, slot });
	}

	/** 写入实例矩阵；factor <= 0 时用极小缩放把实例“藏起来” */
	write(index: number, factor: number, posScale = factor) {
		const e = this.entries[index];
		const f = factor <= 0 ? HIDE : factor;
		const ps = posScale <= 0 ? HIDE : posScale;
		this.s.set(e.scale.x * f, e.scale.y * f, e.scale.z * f);
		this.p.set(e.origin.x + e.rel.x * ps, e.origin.y + e.rel.y * ps, e.origin.z + e.rel.z * ps);
		this.m.compose(this.p, e.quat, this.s);
		this.mesh.setMatrixAt(index, this.m);
	}

	/** 取实例当前世界坐标（用于相机跟随果实） */
	worldPos(index: number, posScale: number, target: THREE.Vector3) {
		const e = this.entries[index];
		const ps = posScale <= 0 ? HIDE : posScale;
		return target.set(e.origin.x + e.rel.x * ps, e.origin.y + e.rel.y * ps, e.origin.z + e.rel.z * ps);
	}

	hide(index: number) {
		this.write(index, -1);
	}

	commit() {
		this.mesh.count = this.entries.length;
		this.mesh.instanceMatrix.needsUpdate = true;
	}

	dispose() {
		this.mesh.dispose();
	}
}

/* -------------------------------------------------------------------------- */
/*                                 番茄冠层                                    */
/* -------------------------------------------------------------------------- */

/** 布局归一化高度：植株布局按此高度设计，运行时用实际株高做等比缩放 */
const H_MAX = 2.6;
const FRUIT_RAMP: Array<[number, THREE.Color]> = [
	[0.0, new THREE.Color('#3f6b26')],
	[0.42, new THREE.Color('#7d9c3a')],
	[0.68, new THREE.Color('#d9821f')],
	[1.0, new THREE.Color('#bf2a17')],
];

const rampColor = (out: THREE.Color, t: number) => {
	const x = Math.min(1, Math.max(0, t));
	for (let i = 0; i < FRUIT_RAMP.length - 1; i++) {
		const [t0, c0] = FRUIT_RAMP[i];
		const [t1, c1] = FRUIT_RAMP[i + 1];
		if (x <= t1) return out.copy(c0).lerp(c1, (x - t0) / (t1 - t0));
	}
	return out.copy(FRUIT_RAMP[FRUIT_RAMP.length - 1][1]);
};

export interface CanopyOptions {
	slots: PlantSlot[];
	maxLeaves?: number;
	maxTrusses?: number;
	maxFruitPerTruss?: number;
	maxFlowers?: number;
}

export class TomatoCanopy {
	readonly group = new THREE.Group();

	private opts: Required<CanopyOptions>;
	private uTime: THREE.IUniform = { value: 0 };
	private uWind: THREE.IUniform = { value: 0.6 };
	private uPlantH: THREE.IUniform = { value: 2.4 };

	private stem!: Organ;
	private petiole!: Organ;
	private leaf!: Organ;
	private truss!: Organ;
	private fruit!: Organ;
	private calyx!: Organ;
	private flower!: Organ;

	private geometries: THREE.BufferGeometry[] = [];
	private materials: THREE.Material[] = [];
	private textures: THREE.Texture[] = [];
	private tmpColor = new THREE.Color();
	private focusPoint = new THREE.Vector3(0, 1.2, 0);
	private topPoint = new THREE.Vector3(0, 1.4, 0);
	private fruitAccum = new THREE.Vector3();
	private tmpVec2 = new THREE.Vector3();
	private lastKey = '';

	constructor(options: CanopyOptions) {
		this.opts = {
			slots: options.slots,
			maxLeaves: options.maxLeaves ?? 20,
			maxTrusses: options.maxTrusses ?? 6,
			maxFruitPerTruss: options.maxFruitPerTruss ?? 5,
			maxFlowers: options.maxFlowers ?? 10,
		};
		this.build();
	}

	private build() {
		const { slots, maxLeaves, maxTrusses, maxFruitPerTruss, maxFlowers } = this.opts;
		const n = slots.length;

		/* ---------- 几何体（单位尺寸，靠实例矩阵缩放） ---------- */
		const stemGeo = new THREE.CylinderGeometry(0.55, 1, 1, 7, 1, true).translate(0, 0.5, 0);
		const petioleGeo = new THREE.CylinderGeometry(0.6, 1, 1, 5, 1, true).translate(0, 0.5, 0);
		const bladeGeo = new THREE.PlaneGeometry(1, 1).translate(0, 0.5, 0);
		const fruitGeo = new THREE.SphereGeometry(1, 12, 8);
		const calyxGeo = new THREE.ConeGeometry(1, 1, 5, 1, true).translate(0, 0.5, 0);
		const flowerGeo = new THREE.PlaneGeometry(1, 1);
		this.geometries.push(stemGeo, petioleGeo, bladeGeo, fruitGeo, calyxGeo, flowerGeo);

		/* ---------- 材质 ---------- */
		const leafTex = createLeafTexture(256);
		const flowerTex = createFlowerTexture(64);
		this.textures.push(leafTex, flowerTex);

		const barkMat = new THREE.MeshStandardMaterial({ color: 0x4a6b2c, roughness: 0.86, metalness: 0 });
		const leafMat = new THREE.MeshStandardMaterial({
			map: leafTex,
			alphaTest: 0.42,
			side: THREE.DoubleSide,
			roughness: 0.58,
			metalness: 0,
			emissive: new THREE.Color(0x0e2a06),
			emissiveIntensity: 0.5,
		});
		const fruitMat = new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.28, metalness: 0, clearcoat: 0.6, clearcoatRoughness: 0.3 });
		const flowerMat = new THREE.MeshStandardMaterial({
			map: flowerTex,
			alphaTest: 0.45,
			side: THREE.DoubleSide,
			roughness: 0.7,
			metalness: 0,
			emissive: new THREE.Color(0x3a2c00),
			emissiveIntensity: 0.6,
		});
		injectWind(barkMat, this.uTime, this.uWind, this.uPlantH, 'twin-bark');
		injectWind(leafMat, this.uTime, this.uWind, this.uPlantH, 'twin-leaf');
		injectWind(fruitMat, this.uTime, this.uWind, this.uPlantH, 'twin-fruit');
		injectWind(flowerMat, this.uTime, this.uWind, this.uPlantH, 'twin-flower');
		this.materials.push(barkMat, leafMat, fruitMat, flowerMat);

		/* ---------- 分配容量 ---------- */
		this.stem = new Organ(stemGeo, barkMat, n, true, true);
		this.petiole = new Organ(petioleGeo, barkMat, n * maxLeaves, false);
		this.leaf = new Organ(bladeGeo, leafMat, n * maxLeaves, true, true);
		this.truss = new Organ(petioleGeo, barkMat, n * maxTrusses, false);
		const fruitCapacity = n * maxTrusses * maxFruitPerTruss;
		this.fruit = new Organ(fruitGeo, fruitMat, fruitCapacity, false, true);
		this.calyx = new Organ(calyxGeo, barkMat, fruitCapacity, false);
		this.flower = new Organ(flowerGeo, flowerMat, n * maxFlowers, false);

		/* ---------- 逐株生成静态布局（H_MAX 下的米制坐标） ---------- */
		for (let p = 0; p < n; p++) {
			const slot = slots[p];
			const rnd = mulberry32(slot.seed);
			const base = new THREE.Vector3(slot.x, 0, slot.z);
			const zero = new THREE.Vector3(0, 0, 0);

			// 茎：烘焙高度 H_MAX，运行时按 posScale 缩放为实际株高
			this.stem.add(base.clone(), zero.clone(), new THREE.Quaternion(), new THREE.Vector3(0.0092, H_MAX, 0.0092), p, 0);

			for (let i = 0; i < maxLeaves; i++) {
				// i=0 为顶端最幼嫩叶，i 越大越靠下、越老、越大
				const fTop = i / (maxLeaves - 1);
				const hFrac = 0.95 - 0.88 * fTop;
				const sizeProfile = 0.66 + 0.34 * fTop;
				const az = slot.phase + i * 2.39996 + slot.row * 0.3;
				const elev = (46 - 22 * fTop) * DEG;

				const rel = new THREE.Vector3(0, hFrac * H_MAX, 0);
				const dir = new THREE.Vector3(Math.cos(az) * Math.cos(elev), Math.sin(elev), Math.sin(az) * Math.cos(elev)).normalize();
				const petioleLen = 0.17 * sizeProfile * (0.9 + 0.2 * rnd());
				this.petiole.add(
					base.clone(),
					rel.clone(),
					new THREE.Quaternion().setFromUnitVectors(UP, dir),
					new THREE.Vector3(0.0032, petioleLen, 0.0032),
					p,
					i
				);

				// 叶片：挂在叶柄末端，向外下方铺展
				const bladeLen = 0.46 * sizeProfile * (0.92 + 0.16 * rnd());
				const bladeWid = bladeLen / 1.34;
				const bElev = (-14 - 10 * rnd()) * DEG;
				const bDir = new THREE.Vector3(Math.cos(az) * Math.cos(bElev), Math.sin(bElev), Math.sin(az) * Math.cos(bElev)).normalize();
				const q = new THREE.Quaternion().setFromUnitVectors(UP, bDir);
				q.multiply(new THREE.Quaternion().setFromAxisAngle(UP, Math.PI / 2 + (rnd() - 0.5) * 1.5));
				const relBlade = rel.clone().add(dir.clone().multiplyScalar(petioleLen));
				this.leaf.add(base.clone(), relBlade, q, new THREE.Vector3(bladeWid, bladeLen, 1), p, i);
			}

			for (let j = 0; j < maxTrusses; j++) {
				const jf = maxTrusses > 1 ? j / (maxTrusses - 1) : 0;
				const hFrac = 0.24 + 0.6 * jf;
				const az = slot.phase + Math.PI * 0.5 + j * 2.39996;
				const elev = -20 * DEG;
				const len = 0.3 * (0.94 + 0.12 * rnd());
				const rel = new THREE.Vector3(0, hFrac * H_MAX, 0);
				const dir = new THREE.Vector3(Math.cos(az) * Math.cos(elev), Math.sin(elev), Math.sin(az) * Math.cos(elev)).normalize();
				this.truss.add(base.clone(), rel.clone(), new THREE.Quaternion().setFromUnitVectors(UP, dir), new THREE.Vector3(0.0036, len, 0.0036), p, j);

				// 果实：沿果穗穗轴呈鱼骨状排列，果柄朝下
				const perp = new THREE.Vector3(-dir.z, 0, dir.x).normalize();
				for (let k = 0; k < maxFruitPerTruss; k++) {
					const kf = maxFruitPerTruss > 1 ? k / (maxFruitPerTruss - 1) : 0;
					const t = 0.3 + 0.62 * kf;
					const lateral = perp.clone().multiplyScalar((k % 2 === 0 ? 1 : -1) * 0.036);
					const relFruit = rel
						.clone()
						.add(dir.clone().multiplyScalar(len * t))
						.add(lateral)
						.add(new THREE.Vector3(0, -0.052, 0));
					const jitter = 0.86 + 0.14 * (1 - kf) * (0.94 + 0.12 * rnd());
					const idx = j * maxFruitPerTruss + k;
					this.fruit.add(base.clone(), relFruit.clone(), new THREE.Quaternion(), new THREE.Vector3(jitter, jitter, jitter), p, idx);
					this.calyx.add(base.clone(), relFruit.clone(), new THREE.Quaternion(), new THREE.Vector3(1.15, 0.85, 1.15), p, idx);
				}
			}

			// 花：位于上部果穗，随坐果推进逐渐减少
			for (let f = 0; f < maxFlowers; f++) {
				const trussIdx = Math.max(0, maxTrusses - 1 - Math.floor(f / 4));
				const jf = maxTrusses > 1 ? trussIdx / (maxTrusses - 1) : 0;
				const hFrac = 0.24 + 0.6 * jf + 0.03 + (f % 4) * 0.012;
				const az = slot.phase + Math.PI * 0.5 + trussIdx * 2.39996 + (f % 4) * 0.5 - 0.8;
				const rad = 0.13 + ((f % 4) + 1) * 0.028;
				const rel = new THREE.Vector3(Math.cos(az) * rad, hFrac * H_MAX, Math.sin(az) * rad);
				const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(-Math.PI / 2 + 0.35, 0, az, 'YXZ'));
				const fs = 0.026 * (0.9 + 0.2 * rnd());
				this.flower.add(base.clone(), rel, q, new THREE.Vector3(fs, fs, fs), p, f);
			}
		}

		this.stem.commit();
		this.petiole.commit();
		this.leaf.commit();
		this.truss.commit();
		this.fruit.commit();
		this.calyx.commit();
		this.flower.commit();

		const child = [this.stem, this.petiole, this.leaf, this.truss, this.fruit, this.calyx, this.flower];
		for (const o of child) this.group.add(o.mesh);
		this.group.name = 'tomato-canopy';

		// 初始全部隐藏，等第一帧数据到来
		this.update({ lai: 0.06, plantHeightCm: 5.2, fruitCount: 0, singleFruitWeightG: 0, fruitSetRate: 0, mature: false, ripeness: 0 });
	}

	/* ---------------------------------------------------------------------- */

	/** 用当日数据刷新全部器官（内部做脏检查，避免每帧重算） */
	update(s: CanopyState) {
		const key = [
			s.lai.toFixed(3),
			s.plantHeightCm.toFixed(1),
			Math.round(s.fruitCount),
			s.singleFruitWeightG.toFixed(1),
			s.fruitSetRate.toFixed(3),
			s.mature ? 1 : 0,
			s.ripeness.toFixed(3),
		].join('|');
		if (key === this.lastKey) return;
		this.lastKey = key;

		const { maxLeaves, maxTrusses, maxFruitPerTruss, maxFlowers } = this.opts;
		const heightM = Math.min(3.4, Math.max(0.015, s.plantHeightCm / 100));
		// posScale：整株按实际株高等比长高（plantHeightCm 直接驱动）
		const posScale = Math.min(1.35, Math.max(0.006, heightM / H_MAX));
		// sizeFactor：器官尺寸。幼苗期给一个下限，让子叶相对更大，避免苗期只剩几根细丝
		const sizeFactor = posScale * (1 + 0.85 * Math.exp(-posScale * 14));
		this.uPlantH.value = Math.max(0.05, heightM);

		// LAI → 叶片数量；lai < 0.1 时只有 2 片叶，呈幼苗状
		const visibleLeaves = Math.min(maxLeaves, Math.max(2, Math.round(s.lai * 6.2)));
		const fruitCount = Math.max(0, Math.min(maxTrusses * maxFruitPerTruss, Math.round(s.fruitCount)));
		const nTrussesNeeded = fruitCount > 0 ? Math.max(1, Math.ceil(fruitCount / maxFruitPerTruss)) : 0;
		const perTruss = fruitCount > 0 ? Math.ceil(fruitCount / nTrussesNeeded) : 0;
		const flowerCount =
			s.fruitSetRate > 0.02 ? Math.max(0, Math.min(maxFlowers, Math.round(maxFlowers * s.fruitSetRate * (1 - s.ripeness)))) : 0;
		const radius = fruitRadiusFromMass(s.singleFruitWeightG);

		let i = 0;
		const n = this.opts.slots.length;

		// 茎（高度直接等于株高）
		for (i = 0; i < n; i++) {
			this.stem.write(i, posScale, posScale);
		}
		this.stem.commit();

		// 叶柄 + 叶片
		i = 0;
		for (let p = 0; p < n; p++) {
			for (let k = 0; k < maxLeaves; k++) {
				if (k < visibleLeaves) {
					this.petiole.write(i, sizeFactor, posScale);
					this.leaf.write(i, sizeFactor, posScale);
				} else {
					this.petiole.hide(i);
					this.leaf.hide(i);
				}
				i++;
			}
		}
		this.petiole.commit();
		this.leaf.commit();

		// 果穗穗轴
		i = 0;
		for (let p = 0; p < n; p++) {
			for (let j = 0; j < maxTrusses; j++) {
				if (j < nTrussesNeeded) this.truss.write(i, posScale, posScale);
				else this.truss.hide(i);
				i++;
			}
		}
		this.truss.commit();

		// 果实 + 花萼（颜色随成熟度由绿→橙→红渐变，下部果穗先熟）
		// 果色只与果穗序号 j 有关，先按果穗算好，避免在内层循环里重复分配 Color
		// 下部果穗（j 小）先转色：同一株上从下到上呈 红→橙→绿 的梯度
		const trussColor: THREE.Color[] = [];
		const baseRipe = s.mature ? 0.66 + 0.34 * s.ripeness : s.ripeness * 0.6;
		for (let j = 0; j < maxTrusses; j++) {
			const jNorm = maxTrusses > 1 ? j / (maxTrusses - 1) : 0;
			trussColor.push(rampColor(new THREE.Color(), baseRipe * (1 - 0.35 * jNorm)));
		}

		this.fruitAccum.set(0, 0, 0);
		let visibleFruit = 0;
		i = 0;
		for (let p = 0; p < n; p++) {
			for (let j = 0; j < maxTrusses; j++) {
				const color = trussColor[j];
				for (let k = 0; k < maxFruitPerTruss; k++) {
					const show = fruitCount > 0 && j < nTrussesNeeded && k < perTruss && j * perTruss + k < fruitCount;
					if (show && radius > 0.002) {
						// 果实尺寸 = 物理半径（不随株高缩放），位置随株高缩放
						this.fruit.write(i, radius, posScale);
						this.calyx.write(i, radius * 0.9, posScale);
						this.fruit.mesh.setColorAt(i, color);
						this.fruit.worldPos(i, posScale, this.tmpVec2);
						this.fruitAccum.add(this.tmpVec2);
						visibleFruit++;
					} else {
						this.fruit.hide(i);
						this.calyx.hide(i);
						if (!this.fruit.mesh.instanceColor) this.fruit.mesh.setColorAt(i, color);
					}
					i++;
				}
			}
		}
		if (this.fruit.mesh.instanceColor) this.fruit.mesh.instanceColor.needsUpdate = true;
		this.fruit.commit();
		this.calyx.commit();

		// 花
		i = 0;
		for (let p = 0; p < n; p++) {
			for (let k = 0; k < maxFlowers; k++) {
				if (k < flowerCount) this.flower.write(i, sizeFactor, posScale);
				else this.flower.hide(i);
				i++;
			}
		}
		this.flower.commit();

		if (visibleFruit > 0) this.focusPoint.copy(this.fruitAccum).multiplyScalar(1 / visibleFruit);
		this.topPoint.set(this.focusPoint.x, Math.max(0.2, heightM * 0.72), this.focusPoint.z);
	}

	setTime(t: number) {
		this.uTime.value = t;
	}

	setWind(strength: number) {
		this.uWind.value = strength;
	}

	/** 跟随果实相机的注视点：果实质心；无果实时退化为冠层中上部 */
	getFocusPoint(): THREE.Vector3 {
		return this.focusPoint.lengthSq() > 1e-6 ? this.focusPoint : this.topPoint;
	}

	dispose() {
		for (const g of this.geometries) g.dispose();
		for (const m of this.materials) m.dispose();
		for (const t of this.textures) t.dispose();
		this.stem.dispose();
		this.petiole.dispose();
		this.leaf.dispose();
		this.truss.dispose();
		this.fruit.dispose();
		this.calyx.dispose();
		this.flower.dispose();
		this.group.clear();
	}
}


