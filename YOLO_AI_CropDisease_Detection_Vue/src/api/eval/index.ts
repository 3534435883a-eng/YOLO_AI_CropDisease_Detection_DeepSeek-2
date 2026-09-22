/**
 * 数字孪生推演数据接口（/api/eval）
 *
 * 说明：
 * 1. 本模块使用独立的 axios 实例，不经过 /@/utils/request 的全局拦截器，
 *    因此后端离线时不会弹出 ElMessage 报错，也不会抛出未捕获的 Promise 异常。
 * 2. 所有导出方法都以 { data, source, error } 形式返回，永不 reject。
 * 3. 当接口不可用或返回空数据时，自动回退到内置的「示例数据」（source = 'demo'），
 *    调用方必须在界面上标注「示例数据」。
 */
import axios, { AxiosInstance } from 'axios';

/* -------------------------------------------------------------------------- */
/*                                  类型定义                                   */
/* -------------------------------------------------------------------------- */

export type StrategyCode = 'P0_NONE' | 'P1_FIXED_MANUAL' | 'P2_RULE_ENGINE' | 'P3_AGENT';

export type DiseaseCode = 'BOTRYTIS' | 'LATE_BLIGHT' | 'POWDERY_MILDEW' | 'LEAF_MOLD';

export type DeviceCode = 'IRRIGATION' | 'VENTILATION' | 'SUPPLEMENTAL_LIGHT' | 'SHADE' | 'CO2_SUPPLY';

export type StageCode = 'SEEDLING' | 'FLOWERING' | 'FRUIT_SET' | 'FRUIT_GROWTH' | 'MATURITY' | string;

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | string;

/** 病害严重度：**百分比量纲 0~100**（42.7 表示 42.7%）。四种病害之和可达数百。 */
export interface DiseaseSeverity {
	BOTRYTIS: number;
	LATE_BLIGHT: number;
	POWDERY_MILDEW: number;
	LEAF_MOLD: number;
	[key: string]: number;
}

/** 设备开关状态 */
export interface DeviceState {
	IRRIGATION: boolean;
	VENTILATION: boolean;
	SUPPLEMENTAL_LIGHT: boolean;
	SHADE: boolean;
	CO2_SUPPLY: boolean;
	[key: string]: boolean;
}

/** 推演序列中的单日数据点 */
export interface EvalDayPoint {
	day: number;
	simulatedAt: string;
	gdd: number;
	lai: number;
	plantHeightCm: number;
	wLeaf: number;
	wStem: number;
	wRoot: number;
	wFruit: number;
	wTotal: number;
	fruitSetRate: number;
	fruitCount: number;
	singleFruitWeightG: number;
	mature: boolean;
	stage: StageCode;
	temperatureC: number;
	airHumidityPct: number;
	co2Ppm: number;
	lightPpfd: number;
	soilMoisturePct: number;
	riskLevel: RiskLevel;
	severity: DiseaseSeverity;
	pestPopulation: number;
	nutrientFactor: number;
	devices: DeviceState;
	waterUsedM3: number;
	energyKWh: number;
	/** 累计成本（元）——注意是**累计值**，后端取自 EconomicsState 的逐日累加字段 */
	costYuan: number;
	/** 累计产值（元）——**累计值** */
	revenueYuan: number;
	/** 累计利润 = revenueYuan - costYuan ——**累计值** */
	profitYuan: number;
}

/** GET /api/eval/{batchId}/series */
export interface EvalSeriesResponse {
	batchId: string;
	strategies: string[];
	series: Record<string, EvalDayPoint[]>;
}

/** 单一策略的终局指标 */
export interface EvalOutcome {
	wFruit: number;
	fruitSetRate: number;
	waterUsedM3: number;
	highTemperatureMinutes: number;
	constraintViolations: number;
	costYuan: number;
	revenueYuan: number;
	profitYuan: number;
	[key: string]: number;
}

/** POST /api/eval/runs */
export interface EvalRunsResponse {
	batchId: string;
	outcomes: Record<string, EvalOutcome>;
}

export interface CreateRunsPayload {
	batchId: string;
	seed: number;
	days: number;
}

/** 统一返回结构：永不抛异常 */
export interface EvalResult<T> {
	data: T;
	source: 'api' | 'demo';
	error?: string;
}

/* -------------------------------------------------------------------------- */
/*                                  常量表                                     */
/* -------------------------------------------------------------------------- */

export const STRATEGY_ORDER: StrategyCode[] = ['P0_NONE', 'P1_FIXED_MANUAL', 'P2_RULE_ENGINE', 'P3_AGENT'];

export const STRATEGY_LABELS: Record<string, string> = {
	P0_NONE: 'P0 无干预',
	P1_FIXED_MANUAL: 'P1 定时人工',
	P2_RULE_ENGINE: 'P2 规则引擎',
	P3_AGENT: 'P3 智能体',
};

export const DISEASE_ORDER: DiseaseCode[] = ['BOTRYTIS', 'LATE_BLIGHT', 'POWDERY_MILDEW', 'LEAF_MOLD'];

export const DISEASE_LABELS: Record<string, string> = {
	BOTRYTIS: '灰霉病',
	LATE_BLIGHT: '晚疫病',
	POWDERY_MILDEW: '白粉病',
	LEAF_MOLD: '叶霉病',
};

export const DEVICE_ORDER: DeviceCode[] = ['IRRIGATION', 'VENTILATION', 'SUPPLEMENTAL_LIGHT', 'SHADE', 'CO2_SUPPLY'];

export const DEVICE_LABELS: Record<string, string> = {
	IRRIGATION: '灌溉',
	VENTILATION: '通风',
	SUPPLEMENTAL_LIGHT: '补光',
	SHADE: '遮阳',
	CO2_SUPPLY: 'CO₂补充',
};

export const STAGE_LABELS: Record<string, string> = {
	SEEDLING: '幼苗期',
	FLOWERING: '开花期',
	FRUIT_SET: '坐果期',
	FRUIT_GROWTH: '果实膨大期',
	MATURITY: '成熟期',
};

export const RISK_LABELS: Record<string, string> = {
	LOW: '低',
	MEDIUM: '中',
	HIGH: '高',
	CRITICAL: '极高',
};

export const EMPTY_SEVERITY: DiseaseSeverity = { BOTRYTIS: 0, LATE_BLIGHT: 0, POWDERY_MILDEW: 0, LEAF_MOLD: 0 };

export const EMPTY_DEVICES: DeviceState = {
	IRRIGATION: false,
	VENTILATION: false,
	SUPPLEMENTAL_LIGHT: false,
	SHADE: false,
	CO2_SUPPLY: false,
};

/** 默认批次号；父级路由可通过 /digitalTwin/:batchId 覆盖 */
export const DEFAULT_BATCH_ID = 'TOMATO-WINTER-2026A';

export const DEFAULT_SEED = 20260921;

export const DEFAULT_DAYS = 120;

/* -------------------------------------------------------------------------- */
/*                                 axios 实例                                  */
/* -------------------------------------------------------------------------- */

// 不设置 baseURL：开发环境下由 Vite 代理把 /api 转发到后端（并剥离 /api 前缀）
const http: AxiosInstance = axios.create({
	timeout: 12000,
	headers: { 'Content-Type': 'application/json;charset=UTF-8' },
});

/* -------------------------------------------------------------------------- */
/*                                  取值工具                                   */
/* -------------------------------------------------------------------------- */

const isObj = (v: unknown): v is Record<string, any> => Boolean(v) && typeof v === 'object';

/**
 * 后端所有 /eval/** 接口都返回项目的 `Result` 信封：
 *   { "code": "0", "msg": "成功", "data": { ...payload } }
 * 因此这里必须取 `resp.data.data`。信封存在但取不到 data 时**抛明确错误**，
 * 由调用方转成界面上的失败原因，绝不静默当成"空数据"。
 */
const unwrapEnvelope = (raw: unknown, what: string): unknown => {
	if (!isObj(raw)) throw new Error(`${what}：返回体不是 JSON 对象`);
	const obj = raw as Record<string, unknown>;
	const looksLikeEnvelope = 'code' in obj || 'status' in obj || 'msg' in obj;
	if (!looksLikeEnvelope) return raw; // 兼容未包信封的裸 payload

	const code = obj.code ?? obj.status;
	if (code !== undefined && code !== null && code !== 0 && code !== '0' && code !== 200 && code !== '200') {
		throw new Error(`${what}：接口返回错误码 ${String(code)}${obj.msg ? `（${String(obj.msg)}）` : ''}`);
	}
	if (!('data' in obj) || obj.data === null || obj.data === undefined) {
		throw new Error(`${what}：Result 信封中没有 data 字段（顶层字段：${Object.keys(obj).join(', ') || '空'}）`);
	}
	return obj.data;
};

const num = (v: unknown, fallback = 0): number => {
	if (typeof v === 'number' && Number.isFinite(v)) return v;
	if (typeof v === 'string' && v.trim() !== '') {
		const n = Number(v);
		if (Number.isFinite(n)) return n;
	}
	return fallback;
};

const bool = (v: unknown): boolean => v === true || v === 'true' || v === 1 || v === '1';

const str = (v: unknown, fallback = ''): string => (typeof v === 'string' && v ? v : typeof v === 'number' ? String(v) : fallback);

const normalizeSeverity = (raw: unknown): DiseaseSeverity => {
	const out: DiseaseSeverity = { ...EMPTY_SEVERITY };
	if (isObj(raw)) {
		for (const code of DISEASE_ORDER) {
			// 后端是 0~100 的百分比量纲，这里只保证非负有限，不做上限截断（避免掩盖异常数据）
			out[code] = Math.max(0, num(raw[code], 0));
		}
	}
	return out;
};

const normalizeDevices = (raw: unknown): DeviceState => {
	const out: DeviceState = { ...EMPTY_DEVICES };
	if (isObj(raw)) {
		for (const code of DEVICE_ORDER) {
			out[code] = bool(raw[code]);
		}
	}
	return out;
};

/** 把后端返回的任意形状的单日记录规整成 EvalDayPoint，缺字段用 0/false 兜底 */
export const normalizeDayPoint = (raw: unknown, index: number): EvalDayPoint => {
	const r = isObj(raw) ? raw : {};
	return {
		day: num(r.day, index + 1),
		simulatedAt: str(r.simulatedAt, ''),
		gdd: num(r.gdd),
		lai: Math.max(0, num(r.lai)),
		plantHeightCm: Math.max(0, num(r.plantHeightCm)),
		wLeaf: num(r.wLeaf),
		wStem: num(r.wStem),
		wRoot: num(r.wRoot),
		wFruit: num(r.wFruit),
		wTotal: num(r.wTotal),
		fruitSetRate: Math.max(0, Math.min(1, num(r.fruitSetRate))),
		fruitCount: Math.max(0, num(r.fruitCount)),
		singleFruitWeightG: Math.max(0, num(r.singleFruitWeightG)),
		mature: bool(r.mature),
		stage: str(r.stage, 'SEEDLING'),
		temperatureC: num(r.temperatureC),
		airHumidityPct: num(r.airHumidityPct),
		co2Ppm: num(r.co2Ppm),
		lightPpfd: num(r.lightPpfd),
		soilMoisturePct: num(r.soilMoisturePct),
		riskLevel: str(r.riskLevel, 'LOW').toUpperCase(),
		severity: normalizeSeverity(r.severity),
		pestPopulation: Math.max(0, num(r.pestPopulation)),
		nutrientFactor: num(r.nutrientFactor, 1),
		devices: normalizeDevices(r.devices),
		waterUsedM3: num(r.waterUsedM3),
		energyKWh: num(r.energyKWh),
		costYuan: num(r.costYuan),
		revenueYuan: num(r.revenueYuan),
		profitYuan: num(r.profitYuan),
	};
};

const normalizeSeriesMap = (raw: unknown, strategies: string[]): Record<string, EvalDayPoint[]> => {
	const out: Record<string, EvalDayPoint[]> = {};
	if (!isObj(raw)) return out;
	for (const key of Object.keys(raw)) {
		const arr = (raw as any)[key];
		if (Array.isArray(arr) && arr.length) {
			out[key] = arr.map((item, i) => normalizeDayPoint(item, i));
		}
	}
	// 兜底：后端只返回了 strategy 列表但没有数据时，补齐空数组
	for (const key of strategies) if (!out[key]) out[key] = [];
	return out;
};

const normalizeOutcomes = (raw: unknown): Record<string, EvalOutcome> => {
	const out: Record<string, EvalOutcome> = {};
	if (!isObj(raw)) return out;
	for (const key of Object.keys(raw)) {
		const r = isObj((raw as any)[key]) ? (raw as any)[key] : {};
		out[key] = {
			wFruit: num(r.wFruit),
			fruitSetRate: num(r.fruitSetRate),
			waterUsedM3: num(r.waterUsedM3),
			highTemperatureMinutes: num(r.highTemperatureMinutes),
			constraintViolations: num(r.constraintViolations),
			costYuan: num(r.costYuan),
			revenueYuan: num(r.revenueYuan),
			profitYuan: num(r.profitYuan),
		};
	}
	return out;
};

const errorText = (e: unknown): string => {
	const err = e as any;
	if (!err) return '未知错误';
	if (err.response?.status) return `接口返回 ${err.response.status}`;
	if (err.code === 'ECONNABORTED') return '请求超时';
	if (err.message === 'Network Error') return '后端未启动或网络不可达';
	return String(err.message || err);
};

/* -------------------------------------------------------------------------- */
/*                          内置示例数据（示例数据）                            */
/* -------------------------------------------------------------------------- */
/**
 * ⚠️ 以下全部为「示例数据」：后端 /api/eval 不可用时用于离线演示。
 * 该数据由一个确定性的番茄温室日尺度作物模型生成（积温驱动 + 病害侵染 +
 * 设备调控 + 成本核算），**不是实测值，也不是后端推演结果**，界面必须标注。
 *
 * —— 与后端对齐的三条语义（务必保持一致，否则界面会静默失真）——
 * 1) costYuan / revenueYuan / profitYuan 均为**累计值**（profitYuan = revenueYuan - costYuan），
 *    不是当日发生额；当日额需要由相邻两天求差。
 * 2) severity.* 为**百分比量纲 0~100**，不是 0~1。
 * 3) stage 只有五个取值：SEEDLING / FLOWERING / FRUIT_SET / FRUIT_GROWTH / MATURITY。
 *
 * 标定：第 1 天（2026-09-21）与接口契约样例同量级——
 *      lai 0.06 / 株高 5.2cm / 叶 1.0 茎 0.6 根 0.8 / wTotal 2.4（完全一致），
 *      gdd 10~15 / 温度 20~25℃ / 湿度 70% / CO₂ 530~640ppm / PPFD 490~620 /
 *      土壤 61~63%；第 1 天累计成本 ≈ 61 元。
 *
 * 经济量级参考后端实测（120 天）：成本 3.9k~8.4k 元、利润 −1.5k~+3.2k 元、
 *      P3 产量 ≈ 3.86 t（后端 3,647.6 kg）。为此成本计入了人工、折旧、管理
 *      与种苗摊销——若只计水电，「不干预」反而会显得最赚钱，基准对比就失去意义。
 */

const START_MS = Date.UTC(2026, 8, 21, 6, 0, 0); // 2026-09-21T06:00
const PLANT_DENSITY = 2.2; // 株/m²
const HOUSE_AREA_M2 = 338; // 26m × 13m
const PLANT_COUNT = Math.round(PLANT_DENSITY * HOUSE_AREA_M2);
const FRUIT_DRY_RATIO = 0.055; // 鲜果干物质率
const TOMATO_PRICE_YUAN_PER_KG = 3.0; // 产地批发价（对齐后端实测经济量级）
const DAILY_HARVEST_FRACTION = 0.048; // 成熟后每日采收比例（占当时挂果量）
const WATER_PRICE_YUAN_PER_M3 = 4.0;
const ENERGY_PRICE_YUAN_PER_KWH = 0.65;
const HEAT_KWH_PER_C = 4.0; // 加温能耗系数：kWh / (℃·日)，双层膜温室
const BASE_DAILY_COST_YUAN = 0.35; // 待机成本（与契约样例 day1 costYuan 同量级）
const MANAGEMENT_COST_YUAN_PER_DAY = 22.0; // 人工 + 折旧 + 管理，按 338 m² 温室折算
const ESTABLISH_COST_TOTAL_YUAN = PLANT_COUNT * 0.9 + 400; // 种苗 + 基质/底肥，定植后 30 天摊销

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
/** 龚帕兹生长曲线：x<=0 时约为 0，x>>a 时趋于 1 */
const gompertz = (x: number, a: number, b: number) => Math.exp(-Math.exp(-(x - a) / b));
const bell = (x: number, mu: number, sigma: number) => Math.exp(-Math.pow((x - mu) / sigma, 2));
const round = (v: number, digits = 2) => {
	const f = Math.pow(10, digits);
	return Math.round(v * f) / f;
};

/** 确定性伪随机（保证每次刷新示例数据完全一致） */
const mulberry32 = (seed: number) => {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
};

const dayOfYearUTC = (ms: number) => {
	const d = new Date(ms);
	return Math.floor((ms - Date.UTC(d.getUTCFullYear(), 0, 0)) / 86400000);
};

const formatSimulatedAt = (ms: number) => {
	const d = new Date(ms);
	const p2 = (n: number) => String(n).padStart(2, '0');
	return `${d.getUTCFullYear()}-${p2(d.getUTCMonth() + 1)}-${p2(d.getUTCDate())}T${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}`;
};

interface DemoProfile {
	code: StrategyCode;
	vegMaxG: number;
	laiMax: number;
	heightMaxCm: number;
	trussMax: number;
	fruitWeightMaxG: number;
	setScale: number;
	/** 0~1：植保/环境管理水平，越高病害侵染越慢 */
	protection: number;
	/** 加温最低温度设定（℃）；无加温设备时为很低的值 */
	heatSetpointC: number;
	waterPerEventM3: number;
	deviceMode: 'NONE' | 'FIXED' | 'RULE' | 'AGENT';
}

const DEMO_PROFILES: DemoProfile[] = [
	{
		code: 'P0_NONE',
		vegMaxG: 178,
		laiMax: 1.35,
		heightMaxCm: 172,
		trussMax: 4,
		fruitWeightMaxG: 106,
		setScale: 0.55,
		protection: 0.04,
		heatSetpointC: 13,
		waterPerEventM3: 0,
		deviceMode: 'NONE',
	},
	{
		code: 'P1_FIXED_MANUAL',
		vegMaxG: 352,
		laiMax: 2.15,
		heightMaxCm: 216,
		trussMax: 5,
		fruitWeightMaxG: 134,
		setScale: 0.74,
		protection: 0.34,
		heatSetpointC: 15,
		waterPerEventM3: 0.34,
		deviceMode: 'FIXED',
	},
	{
		code: 'P2_RULE_ENGINE',
		vegMaxG: 470,
		laiMax: 2.86,
		heightMaxCm: 244,
		trussMax: 6,
		fruitWeightMaxG: 153,
		setScale: 0.88,
		protection: 0.6,
		heatSetpointC: 16.5,
		waterPerEventM3: 0.28,
		deviceMode: 'RULE',
	},
	{
		code: 'P3_AGENT',
		vegMaxG: 560,
		laiMax: 3.26,
		heightMaxCm: 258,
		trussMax: 6,
		fruitWeightMaxG: 166,
		setScale: 0.96,
		protection: 0.82,
		heatSetpointC: 18.5,
		waterPerEventM3: 0.22,
		deviceMode: 'AGENT',
	},
];

interface DemoAggregate {
	highTemperatureMinutes: number;
	constraintViolations: number;
	waterUsedM3: number;
	energyKWh: number;
	costYuan: number;
	revenueYuan: number;
	profitYuan: number;
	fruitSetRate: number;
	wFruit: number;
	fruitCount: number;
	harvestKg: number;
	avgSeverity: number;
	waterStressDays: number;
}

interface DemoRun {
	points: EvalDayPoint[];
	agg: DemoAggregate;
}

/** 单策略 120 天日尺度推演（示例数据） */
const simulateStrategy = (p: DemoProfile, days: number, seed: number): DemoRun => {
	const rnd = mulberry32(seed);
	const points: EvalDayPoint[] = [];
	const severity: DiseaseSeverity = { BOTRYTIS: 0.012, LATE_BLIGHT: 0.008, POWDERY_MILDEW: 0.01, LEAF_MOLD: 0.006 };

	const agg: DemoAggregate = {
		highTemperatureMinutes: 0,
		constraintViolations: 0,
		waterUsedM3: 0,
		energyKWh: 0,
		costYuan: 0,
		revenueYuan: 0,
		profitYuan: 0,
		fruitSetRate: 0,
		wFruit: 0,
		fruitCount: 0,
		harvestKg: 0,
		avgSeverity: 0,
		waterStressDays: 0,
	};

	let gdd = 0;
	let soil = 65;
	let pest = 5.0;
	let firstFruitDay = -1;
	let cumCost = 0;
	let cumRevenue = 0;
	let severitySum = 0;
	let prevLai = 0.06;

	for (let d = 1; d <= days; d++) {
		const ms = START_MS + (d - 1) * 86400000;
		const doy = dayOfYearUTC(ms);
		const season = Math.cos((2 * Math.PI * (doy - 172)) / 365); // +1 夏至 / -1 冬至
		const dayLen = clamp(12 + 3.3 * season, 8.4, 15.2);
		const weather = 0.76 + 0.24 * rnd();

		// —— 光环境（温室内部日代表光强，PPFD μmol/m²/s）——
		const skyFactor = clamp(0.55 + (0.45 * (season + 1)) / 2, 0.35, 1);
		const ppfd = clamp(820 * skyFactor * weather, 60, 1100);
		const solar = clamp(ppfd / 900, 0.05, 1.15);

		// —— 未调控环境基线（室外温度 + 拱棚太阳得热：晴天密闭棚升温可达 15~18℃）——
		const tOut = 13.4 + 11 * season + 1.4 * (rnd() - 0.5);
		const rhOut = clamp(64 + 16 * (1 - solar) + 4 * (rnd() - 0.5), 40, 92);
		let temp = tOut + 1.5 + 30.0 * Math.pow(solar, 1.6);
		let rh = clamp(84 - 24 * solar + 3 * (rnd() - 0.5), 34, 96);
		let co2 = clamp(430 + 300 * solar, 380, 1500);

		// 土壤蒸散（随土壤水势递减，干旱时蒸散受限）
		const etDemand = (0.8 + 3.0 * solar) * clamp((temp - 8) / 18, 0.25, 1.7);
		const et = clamp(etDemand * clamp(soil / 45, 0.22, 1), 0.25, 6.2);
		soil = clamp(soil - et, 26, 96); // 26% 为犁底层毛细补水下限

		// —— 策略调控决策（基于当日观测）——
		const dev: DeviceState = { ...EMPTY_DEVICES };
		if (p.deviceMode === 'FIXED') {
			dev.IRRIGATION = d % 3 === 0;
			dev.VENTILATION = temp > 27.0;
			dev.SUPPLEMENTAL_LIGHT = dayLen < 10.6;
			dev.SHADE = temp > 30.5;
			dev.CO2_SUPPLY = dayLen < 11.0 && prevLai > 0.8;
		} else if (p.deviceMode === 'RULE') {
			dev.IRRIGATION = soil < 58;
			dev.VENTILATION = temp > 27.5 || rh > 84;
			dev.SUPPLEMENTAL_LIGHT = solar < 0.55;
			dev.SHADE = temp > 31;
			dev.CO2_SUPPLY = solar > 0.45 && prevLai > 0.6 && !dev.VENTILATION;
		} else if (p.deviceMode === 'AGENT') {
			dev.IRRIGATION = soil < 61.5;
			dev.VENTILATION = temp > 26.0 || rh > 78.5;
			dev.SUPPLEMENTAL_LIGHT = solar < 0.6;
			dev.SHADE = temp > 29.5;
			dev.CO2_SUPPLY = solar > 0.32 && prevLai > 0.5 && !dev.VENTILATION;
		}

		// —— 执行效果 ——
		let waterM3 = 0;
		if (dev.IRRIGATION) {
			waterM3 = p.waterPerEventM3;
			soil = clamp(soil + (p.deviceMode === 'AGENT' ? 13 : 18), 20, 96);
		}
		if (dev.VENTILATION) {
			temp -= (temp - tOut) * 0.45;
			rh = lerp(rh, rhOut, 0.45); // 通风使棚内湿度向室外湿度靠拢
			co2 = clamp(co2 + (415 - co2) * 0.38, 380, 1500);
		}
		if (dev.SHADE) {
			temp -= 3.0;
			rh += 3;
		}
		if (dev.SUPPLEMENTAL_LIGHT) co2 -= 55;
		if (dev.CO2_SUPPLY) co2 = clamp(co2 + 240, 380, 1500);

		soil = Math.min(soil, 76); // 田间持水量：超出部分渗漏
		temp = clamp(temp, 4, 42);
		rh = clamp(rh, 30, 97);
		co2 = clamp(co2, 360, 1500);

		// —— 加温（各策略最低温度设定不同，能耗计入 energyKWh / costYuan）——
		let heatKWh = 0;
		if (temp < p.heatSetpointC) {
			heatKWh = (p.heatSetpointC - temp) * HEAT_KWH_PER_C;
			temp = p.heatSetpointC;
		}

		// —— 积温 ——
		const gddDay = Math.max(0, temp - 10);
		gdd += gddDay;

		// —— 病害（逻辑斯蒂侵染累积；保护性措施降低侵染速率）——
		const wet = rh > 85 ? 1 : rh > 78 ? 0.45 : 0;
		const dry = clamp(1 - rh / 85, 0, 1);
		const protect = 1 - p.protection * 0.86;
		const rates: Array<[DiseaseCode, number]> = [
			['BOTRYTIS', 0.2 * bell(temp, 19, 5.5) * (0.25 + wet) * protect],
			['LATE_BLIGHT', 0.17 * bell(temp, 22, 6.5) * (0.2 + wet * 1.15) * protect],
			['POWDERY_MILDEW', 0.14 * bell(temp, 24, 6.0) * (0.3 + dry * 0.95) * protect],
			['LEAF_MOLD', 0.16 * bell(temp, 23, 5.0) * (0.25 + wet) * protect],
		];
		for (const [code, r] of rates) {
			const s = severity[code];
			severity[code] = clamp(s + r * s * (1 - s / 0.94), 0, 0.98);
		}
		const avgSeverity = (severity.BOTRYTIS + severity.LATE_BLIGHT + severity.POWDERY_MILDEW + severity.LEAF_MOLD) / 4;
		severitySum += avgSeverity;

		// —— 虫口密度（逻辑斯蒂增长 - 按植保水平成比例的控制）——
		const pestGrow = 0.052 * clamp((temp - 12) / 12, -0.6, 1.3);
		pest = clamp(pest + pestGrow * pest * (1 - pest / 190) - 0.055 * p.protection * pest + 0.15 * rnd(), 0, 320);

		// —— 养分因子 ——
		const nutrientFactor = clamp(
			0.99 - 0.3 * Math.max(0, (56 - soil) / 56) - 0.14 * Math.max(0, (soil - 86) / 14) - 0.07 * (gdd / 1500),
			0.32,
			1.0
		);
		const waterStress = clamp((36 - soil) / 16, 0, 1);
		if (waterStress > 0.4) agg.waterStressDays += 1;

		// —— 生长 ——
		const f2 = gompertz(gdd, 860, 210); // 生殖生长占比因子
		const veg = 2.4 + (p.vegMaxG - 2.4) * gompertz(gdd, 620, 250);
		const trussesStanding = Math.min(p.trussMax, Math.max(0, Math.floor((gdd - 470) / 80)));
		const heatStress = clamp(1 - Math.max(0, temp - 31) / 9, 0.25, 1);
		const fruitSetRate =
			trussesStanding <= 0
				? 0
				: clamp(0.95 * heatStress * (1 - 0.55 * avgSeverity) * (0.55 + 0.45 * nutrientFactor) * p.setScale, 0, 0.97);
		const fruitCount = trussesStanding <= 0 ? 0 : Math.round(trussesStanding * 5.2 * fruitSetRate);
		if (fruitCount > 0 && firstFruitDay < 0) firstFruitDay = d;
		const fruitAge = firstFruitDay > 0 ? d - firstFruitDay : 0;
		const singleFruitWeightG = fruitCount > 0 ? round(p.fruitWeightMaxG * gompertz(fruitAge, 34, 11.5), 2) : 0;
		const mature = singleFruitWeightG > p.fruitWeightMaxG * 0.62;

		let lai = 0.06 + (p.laiMax - 0.06) * gompertz(gdd, 620, 250);
		lai *= 1 - 0.3 * avgSeverity; // 病叶脱落
		lai *= 1 - 0.38 * waterStress; // 干旱导致叶片萎蔫脱落
		lai *= 1 - 0.22 * Math.max(0, (gdd - 1050) / 500); // 后期衰老
		lai = Math.max(0.03, lai);

		const plantHeightCm = round(5.2 + (p.heightMaxCm - 5.2) * gompertz(gdd, 620, 250), 1);

		const wFruit = round(fruitCount * singleFruitWeightG * FRUIT_DRY_RATIO, 2);
		const rootFrac = lerp(1 / 3, 0.262, f2);
		const leafFracOfRest = lerp(0.625, 0.6, f2);
		const wRoot = round(veg * rootFrac, 2);
		const rest = Math.max(0, veg - wRoot);
		const wLeaf = round(rest * leafFracOfRest, 2);
		const wStem = round(rest - wLeaf, 2);
		const wTotal = round(wLeaf + wStem + wRoot + wFruit, 2);

		// —— 生育期（与后端一致的五个枚举值）——
		const maturityFrac = p.fruitWeightMaxG > 0 ? singleFruitWeightG / p.fruitWeightMaxG : 0;
		const stage: StageCode =
			fruitCount <= 0
				? gdd < 200
					? 'SEEDLING'
					: 'FLOWERING'
				: maturityFrac > 0.62
				? 'MATURITY'
				: maturityFrac > 0.3
				? 'FRUIT_GROWTH'
				: 'FRUIT_SET';

		// —— 风险等级 ——
		const maxSeverity = Math.max(severity.BOTRYTIS, severity.LATE_BLIGHT, severity.POWDERY_MILDEW, severity.LEAF_MOLD);
		const riskScore =
			maxSeverity * 0.6 + clamp((temp - 31) / 6, 0, 1) * 0.2 + clamp((42 - soil) / 22, 0, 1) * 0.2 + clamp(pest / 400, 0, 1) * 0.12;
		const riskLevel: RiskLevel = riskScore < 0.2 ? 'LOW' : riskScore < 0.38 ? 'MEDIUM' : riskScore < 0.6 ? 'HIGH' : 'CRITICAL';

		// —— 能耗与成本（口径：水、电、加温与随冠层变化的可变投入；不含人工与折旧）——
		const energyKWh = round(
			heatKWh +
				(dev.SUPPLEMENTAL_LIGHT ? 6 * 7.5 : 0) +
				(dev.VENTILATION ? 1.8 : 0) +
				(dev.IRRIGATION ? 1.1 : 0) +
				(dev.CO2_SUPPLY ? 0.9 : 0),
			2
		);
		const inputCostYuan = PLANT_COUNT * 0.0035 * (prevLai / 3) * (1 + 0.5 * f2);
		const establishCostYuan = d <= 30 ? ESTABLISH_COST_TOTAL_YUAN / 30 : 0;
		const costYuan = round(
			BASE_DAILY_COST_YUAN +
				MANAGEMENT_COST_YUAN_PER_DAY +
				establishCostYuan +
				inputCostYuan +
				waterM3 * WATER_PRICE_YUAN_PER_M3 +
				energyKWh * ENERGY_PRICE_YUAN_PER_KWH,
			2
		);
		const harvestKg = mature ? PLANT_COUNT * fruitCount * (singleFruitWeightG / 1000) * DAILY_HARVEST_FRACTION : 0;
		const revenueTodayYuan = harvestKg * TOMATO_PRICE_YUAN_PER_KG;

		// —— 经济字段与后端一致：costYuan / revenueYuan / profitYuan 全部是**累计值** ——
		cumCost += costYuan;
		cumRevenue += revenueTodayYuan;
		const cumCostYuan = round(cumCost, 2);
		const cumRevenueYuan = round(cumRevenue, 2);
		const cumProfitYuan = round(cumRevenue - cumCost, 2);
		agg.harvestKg += harvestKg;

		// —— 约束违反统计 ——
		// 高温时长按「日最高气温」估算：晴好天气午后峰值高于日均值，幅度随太阳辐射增大
		const tMax = temp + 6.5 * solar;
		if (soil < 30 || tMax > 34 || maxSeverity > 0.75) agg.constraintViolations += 1;
		if (tMax > 30) agg.highTemperatureMinutes += Math.min(360, (tMax - 30) * 13);
		agg.waterUsedM3 += waterM3;
		agg.energyKWh += energyKWh;
		prevLai = lai;

		points.push({
			day: d,
			simulatedAt: formatSimulatedAt(ms),
			gdd: round(gdd, 1),
			lai: round(lai, 3),
			plantHeightCm,
			wLeaf,
			wStem,
			wRoot,
			wFruit,
			wTotal,
			fruitSetRate: round(fruitSetRate, 4),
			fruitCount,
			singleFruitWeightG,
			mature,
			stage,
			temperatureC: round(temp, 1),
			airHumidityPct: round(rh, 1),
			co2Ppm: round(co2, 1),
			lightPpfd: round(ppfd, 1),
			soilMoisturePct: round(soil, 1),
			riskLevel,
			// 与后端一致：严重度为 0~100 的百分比（内部模型按 0~1 计算，此处换算）
			severity: {
				BOTRYTIS: round(severity.BOTRYTIS * 100, 2),
				LATE_BLIGHT: round(severity.LATE_BLIGHT * 100, 2),
				POWDERY_MILDEW: round(severity.POWDERY_MILDEW * 100, 2),
				LEAF_MOLD: round(severity.LEAF_MOLD * 100, 2),
			},
			pestPopulation: round(pest, 2),
			nutrientFactor: round(nutrientFactor, 3),
			devices: { ...dev },
			waterUsedM3: round(waterM3, 3),
			energyKWh,
			costYuan: cumCostYuan,
			revenueYuan: cumRevenueYuan,
			profitYuan: cumProfitYuan,
		});
	}

	agg.costYuan = round(cumCost, 2);
	agg.revenueYuan = round(cumRevenue, 2);
	agg.profitYuan = round(cumRevenue - cumCost, 2);
	agg.waterUsedM3 = round(agg.waterUsedM3, 3);
	agg.energyKWh = round(agg.energyKWh, 2);
	agg.harvestKg = round(agg.harvestKg, 1);
	agg.highTemperatureMinutes = Math.round(agg.highTemperatureMinutes);
	const last = points[points.length - 1];
	agg.fruitSetRate = last ? last.fruitSetRate : 0;
	agg.wFruit = last ? last.wFruit : 0;
	agg.fruitCount = last ? last.fruitCount : 0;
	agg.avgSeverity = round(severitySum / Math.max(1, days), 4);

	return { points, agg };
};

export interface DemoDataset {
	batchId: string;
	strategies: StrategyCode[];
	series: Record<string, EvalDayPoint[]>;
	outcomes: Record<string, EvalOutcome>;
}

/** 生成内置示例数据集（4 套策略 × N 天）；结果做缓存，避免重复推演 */
const demoCache = new Map<string, DemoDataset>();

export const buildDemoDataset = (batchId: string = DEFAULT_BATCH_ID, days: number = DEFAULT_DAYS): DemoDataset => {
	const key = `${batchId}|${days}`;
	const cached = demoCache.get(key);
	if (cached) return cached;
	const series: Record<string, EvalDayPoint[]> = {};
	const outcomes: Record<string, EvalOutcome> = {};
	DEMO_PROFILES.forEach((p, i) => {
		const run = simulateStrategy(p, days, DEFAULT_SEED + i * 7919);
		series[p.code] = run.points;
		outcomes[p.code] = {
			wFruit: run.agg.wFruit,
			fruitSetRate: run.agg.fruitSetRate,
			waterUsedM3: run.agg.waterUsedM3,
			highTemperatureMinutes: run.agg.highTemperatureMinutes,
			constraintViolations: run.agg.constraintViolations,
			costYuan: run.agg.costYuan,
			revenueYuan: run.agg.revenueYuan,
			profitYuan: run.agg.profitYuan,
		};
	});
	const result: DemoDataset = { batchId, strategies: [...STRATEGY_ORDER], series, outcomes };
	demoCache.set(key, result);
	return result;
};

/* -------------------------------------------------------------------------- */
/*                                  接口方法                                   */
/* -------------------------------------------------------------------------- */

/** 判断接口返回的序列是否可用（至少一个策略有数据点） */
export const hasUsableSeries = (resp: EvalSeriesResponse | null | undefined): boolean => {
	if (!resp || !isObj(resp.series)) return false;
	return Object.keys(resp.series).some((k) => Array.isArray(resp.series[k]) && resp.series[k].length > 0);
};

/**
 * GET /api/eval/{batchId}/series
 * 后端不可用 / 返回空时自动回退到内置示例数据（source = 'demo'），永不 reject。
 */
export const fetchEvalSeries = async (batchId: string = DEFAULT_BATCH_ID, days: number = DEFAULT_DAYS): Promise<EvalResult<EvalSeriesResponse>> => {
	const url = `/api/eval/${encodeURIComponent(batchId)}/series`;
	try {
		const res = await http.get(url);
		// 后端包在 Result 信封里，这里取 resp.data.data
		const payload = unwrapEnvelope(res.data, `GET ${url}`) as any;
		if (!isObj(payload)) throw new Error(`GET ${url}：data 不是对象（${typeof payload}）`);
		if (!isObj(payload.series)) {
			throw new Error(`GET ${url}：data 中缺少 series 字段（实际字段：${Object.keys(payload).join(', ') || '空'}）`);
		}
		const strategies: string[] = Array.isArray(payload.strategies) && payload.strategies.length ? payload.strategies.map((s: unknown) => String(s)) : [...STRATEGY_ORDER];
		const normalized: EvalSeriesResponse = {
			batchId: str(payload.batchId, batchId),
			strategies,
			series: normalizeSeriesMap(payload.series, strategies),
		};
		if (!hasUsableSeries(normalized)) throw new Error(`GET ${url}：series 里没有任何数据点`);
		return { data: normalized, source: 'api' };
	} catch (e) {
		return { data: demoAsSeries(batchId, days), source: 'demo', error: errorText(e) };
	}
};

/**
 * POST /api/eval/runs  触发后端整批重算并返回终局指标矩阵。
 * 由于该接口会触发完整推演（耗时较长），页面仅在用户主动点击时调用。
 */
export const runEval = async (payload: CreateRunsPayload): Promise<EvalResult<EvalRunsResponse>> => {
	const body: CreateRunsPayload = {
		batchId: payload.batchId || DEFAULT_BATCH_ID,
		seed: Number.isFinite(payload.seed) ? payload.seed : DEFAULT_SEED,
		days: Number.isFinite(payload.days) ? payload.days : DEFAULT_DAYS,
	};
	try {
		const res = await http.post('/api/eval/runs', body);
		const raw = unwrapEnvelope(res.data) as any;
		if (!isObj(raw)) throw new Error('返回体不是对象');
		const outcomes = normalizeOutcomes(raw.outcomes);
		if (!Object.keys(outcomes).length) throw new Error('接口未返回 outcomes');
		return { data: { batchId: str(raw.batchId, body.batchId), outcomes }, source: 'api' };
	} catch (e) {
		const demo = buildDemoDataset(body.batchId, body.days);
		return { data: { batchId: demo.batchId, outcomes: demo.outcomes }, source: 'demo', error: errorText(e) };
	}
};

/** 仅取示例数据的序列部分 */
const demoAsSeries = (batchId: string, days: number): EvalSeriesResponse => {
	const demo = buildDemoDataset(batchId, days);
	return { batchId: demo.batchId, strategies: demo.strategies, series: demo.series };
};

/** 仅取示例数据的终局指标 */
export const buildDemoOutcomes = (batchId: string = DEFAULT_BATCH_ID, days: number = DEFAULT_DAYS): EvalRunsResponse => {
	const demo = buildDemoDataset(batchId, days);
	return { batchId: demo.batchId, outcomes: demo.outcomes };
};

/* -------------------------------------------------------------------------- */
/*                                派生计算工具                                 */
/* -------------------------------------------------------------------------- */

/** 在浮点日索引上对两天数据做线性插值（设备开关/枚举取最近一天） */
export const interpolateDayPoint = (points: EvalDayPoint[], dayFloat: number): EvalDayPoint | null => {
	if (!points.length) return null;
	const maxDay = points.length;
	const d = clamp(dayFloat, 1, maxDay);
	const i0 = Math.floor(d) - 1;
	const i1 = Math.min(maxDay - 1, i0 + 1);
	const a = points[Math.max(0, Math.min(maxDay - 1, i0))];
	const b = points[i1];
	const t = clamp(d - Math.floor(d), 0, 1);
	if (!a) return b || null;
	if (!b || a === b) return a;

	const mix = (x: number, y: number) => x + (y - x) * t;
	const near = t < 0.5 ? a : b;
	const sev: DiseaseSeverity = { ...EMPTY_SEVERITY };
	for (const code of DISEASE_ORDER) sev[code] = mix(a.severity[code] ?? 0, b.severity[code] ?? 0);

	return {
		...near,
		day: d,
		gdd: mix(a.gdd, b.gdd),
		lai: mix(a.lai, b.lai),
		plantHeightCm: mix(a.plantHeightCm, b.plantHeightCm),
		wLeaf: mix(a.wLeaf, b.wLeaf),
		wStem: mix(a.wStem, b.wStem),
		wRoot: mix(a.wRoot, b.wRoot),
		wFruit: mix(a.wFruit, b.wFruit),
		wTotal: mix(a.wTotal, b.wTotal),
		fruitSetRate: mix(a.fruitSetRate, b.fruitSetRate),
		fruitCount: mix(a.fruitCount, b.fruitCount),
		singleFruitWeightG: mix(a.singleFruitWeightG, b.singleFruitWeightG),
		temperatureC: mix(a.temperatureC, b.temperatureC),
		airHumidityPct: mix(a.airHumidityPct, b.airHumidityPct),
		co2Ppm: mix(a.co2Ppm, b.co2Ppm),
		lightPpfd: mix(a.lightPpfd, b.lightPpfd),
		soilMoisturePct: mix(a.soilMoisturePct, b.soilMoisturePct),
		pestPopulation: mix(a.pestPopulation, b.pestPopulation),
		nutrientFactor: mix(a.nutrientFactor, b.nutrientFactor),
		waterUsedM3: mix(a.waterUsedM3, b.waterUsedM3),
		energyKWh: mix(a.energyKWh, b.energyKWh),
		costYuan: mix(a.costYuan, b.costYuan),
		revenueYuan: mix(a.revenueYuan, b.revenueYuan),
		profitYuan: mix(a.profitYuan, b.profitYuan),
		severity: sev,
		devices: { ...near.devices },
		mature: near.mature,
		stage: near.stage,
		riskLevel: near.riskLevel,
	};
};

/** simulatedAt → 小时（浮点）。解析失败返回 fallback */
export const hourFromSimulatedAt = (simulatedAt: string, fallback = 6): number => {
	const m = /T(\d{2}):(\d{2})/.exec(simulatedAt || '');
	if (!m) return fallback;
	const h = Number(m[1]);
	const mi = Number(m[2]);
	if (!Number.isFinite(h) || !Number.isFinite(mi)) return fallback;
	return clamp(h + mi / 60, 0, 23.999);
};

/** simulatedAt → 一年中的第几天（用于太阳位置） */
export const dayOfYearFromSimulatedAt = (simulatedAt: string, fallback = 264): number => {
	const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(simulatedAt || '');
	if (!m) return fallback;
	const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
	if (!Number.isFinite(ms)) return fallback;
	return dayOfYearUTC(ms);
};

/** 判断所有数据点的时间戳小时是否一致（一致则说明序列是「日快照」） */
export const isFlatDailyTimestamp = (points: EvalDayPoint[]): boolean => {
	if (points.length < 3) return true;
	const h0 = hourFromSimulatedAt(points[0].simulatedAt, -1);
	for (let i = 1; i < points.length; i++) {
		if (Math.abs(hourFromSimulatedAt(points[i].simulatedAt, -1) - h0) > 0.01) return false;
	}
	return true;
};
