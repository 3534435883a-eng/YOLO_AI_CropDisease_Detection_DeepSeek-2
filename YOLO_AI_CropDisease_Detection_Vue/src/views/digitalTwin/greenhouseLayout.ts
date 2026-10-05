import { HORTI_M3_PROFILE } from './hortiM3Profile';

const spanCount = 4; // 可视化假定值：论文未报告精确跨数
const spanWidth = HORTI_M3_PROFILE.greenhouse.width / spanCount;
const plotSpacing = 2.5;

export const GREENHOUSE_LAYOUT = {
	length: HORTI_M3_PROFILE.greenhouse.length,
	width: HORTI_M3_PROFILE.greenhouse.width,
	eaveHeight: HORTI_M3_PROFILE.greenhouse.eaveHeight,
	archRise: HORTI_M3_PROFILE.greenhouse.ridgeHeight - HORTI_M3_PROFILE.greenhouse.eaveHeight,
	spanCount,
	spanWidth,
	bayCenters: Array.from({ length: spanCount }, (_, index) => -HORTI_M3_PROFILE.greenhouse.width / 2 + spanWidth * (index + 0.5)),
	bedLength: 18,
	bedWidth: 1,
	bedCenters: Array.from({ length: HORTI_M3_PROFILE.experiment.plotCount }, (_, index) =>
		-(HORTI_M3_PROFILE.experiment.plotCount - 1) * plotSpacing / 2 + index * plotSpacing),
	plantsPerBed: HORTI_M3_PROFILE.experiment.plantsPerPlot,
	plantsPerRidge: HORTI_M3_PROFILE.experiment.plantsPerRidge,
	plantRowsPerPlot: HORTI_M3_PROFILE.experiment.ridgesPerPlot,
	plantSpacing: HORTI_M3_PROFILE.experiment.inRowSpacing,
	rowSpacing: HORTI_M3_PROFILE.experiment.betweenRowSpacing,
	centerAisleWidth: 1.2, // 3D 视图的通道示意值
	serviceEndX: -HORTI_M3_PROFILE.greenhouse.length / 2 + 0.4,
	wetPadEndX: -HORTI_M3_PROFILE.greenhouse.length / 2,
	exhaustEndX: HORTI_M3_PROFILE.greenhouse.length / 2,
} as const;

export const plantPositionAt = (plotIndex: number, plantIndex: number) => {
	const layout = GREENHOUSE_LAYOUT;
	const ridge = Math.floor(plantIndex / layout.plantsPerRidge);
	const plantOnRidge = plantIndex % layout.plantsPerRidge;
	const occupiedLength = (layout.plantsPerRidge - 1) * layout.plantSpacing;
	const endMargin = (layout.bedLength - occupiedLength) / 2;
	return {
		x: -layout.bedLength / 2 + endMargin + plantOnRidge * layout.plantSpacing,
		z: layout.bedCenters[plotIndex] + (ridge === 0 ? -1 : 1) * layout.rowSpacing / 2,
	};
};

export const roofHeightAt = (across: number) => {
	const layout = GREENHOUSE_LAYOUT;
	const bayCenter = layout.bayCenters.reduce((nearest, candidate) =>
		Math.abs(candidate - across) < Math.abs(nearest - across) ? candidate : nearest, layout.bayCenters[0]);
	const position = (across - bayCenter) / layout.spanWidth + 0.5;
	const arch = Math.max(0, Math.sin(Math.PI * Math.max(0, Math.min(1, position))));
	return layout.eaveHeight + layout.archRise * Math.pow(arch, 0.86);
};
