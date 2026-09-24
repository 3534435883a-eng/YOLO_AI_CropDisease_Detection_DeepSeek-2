export const GREENHOUSE_LAYOUT = {
	length: 26,
	width: 13,
	eaveHeight: 4,
	archRise: 1.7,
	bayCenters: [-3.25, 3.25] as const,
	bedLength: 21,
	bedWidth: 1.7,
	bedCenters: [-4.4, -2.25, 2.25, 4.4] as const,
	plantsPerBed: 26,
	centerAisleWidth: 2.5,
	serviceEndX: -11.8,
	wetPadEndX: -13,
	exhaustEndX: 13,
} as const;

export const roofHeightAt = (across: number) => {
	const layout = GREENHOUSE_LAYOUT;
	const bayWidth = layout.width / 2;
	const bayCenter = across < 0 ? layout.bayCenters[0] : layout.bayCenters[1];
	const position = (across - bayCenter) / bayWidth + 0.5;
	const arch = Math.max(0, Math.sin(Math.PI * Math.max(0, Math.min(1, position))));
	return layout.eaveHeight + layout.archRise * Math.pow(arch, 0.86);
};
