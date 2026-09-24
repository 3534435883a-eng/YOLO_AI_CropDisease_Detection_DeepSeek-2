import * as THREE from 'three';
import { GREENHOUSE_LAYOUT, roofHeightAt } from './greenhouseLayout';

export type EquipmentFrame = {
	irrigation: boolean;
	ventilation: boolean;
	supplementalLight: boolean;
	shade: boolean;
	co2: boolean;
	circulationFan: boolean;
	exhaustFan: boolean;
	coolingPad: boolean;
	roofVent: boolean;
	temperatureC: number;
	airHumidityPct: number;
	co2Ppm: number;
	lightPpfd: number;
	soilMoisturePct: number;
	sensorReadings?: Record<string, number>;
};

export type EquipmentEntry = {
	code: string;
	name: string;
	zone: string;
	kind: 'actuator' | 'sensor';
	description: string;
	object: THREE.Object3D;
	active: boolean;
	value?: number;
	unit?: string;
};

const steel = new THREE.MeshStandardMaterial({ color: 0x8d9da1, metalness: 0.77, roughness: 0.34 });
const darkSteel = new THREE.MeshStandardMaterial({ color: 0x293e40, metalness: 0.61, roughness: 0.43 });
const pipeBlue = new THREE.MeshStandardMaterial({ color: 0x27769b, metalness: 0.18, roughness: 0.43 });
const pipeGreen = new THREE.MeshStandardMaterial({ color: 0x538072, metalness: 0.28, roughness: 0.43 });
const padBrown = new THREE.MeshStandardMaterial({ color: 0x9a8c69, metalness: 0.02, roughness: 0.95 });
const glass = new THREE.MeshPhysicalMaterial({ color: 0xdde8db, metalness: 0.04, roughness: 0.22, transparent: true, opacity: 0.82 });

const box = (parent: THREE.Object3D, size: [number, number, number], position: [number, number, number], material: THREE.Material) => {
	const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), material);
	mesh.position.set(...position);
	mesh.castShadow = true;
	mesh.receiveShadow = true;
	parent.add(mesh);
	return mesh;
};

const cylinder = (parent: THREE.Object3D, radius: number, height: number, position: [number, number, number], material: THREE.Material) => {
	const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, height, 16), material);
	mesh.position.set(...position);
	mesh.castShadow = true;
	parent.add(mesh);
	return mesh;
};

const pipe = (parent: THREE.Object3D, start: THREE.Vector3, end: THREE.Vector3, radius: number, material: THREE.Material) => {
	const delta = end.clone().sub(start);
	const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, delta.length(), 8), material);
	mesh.position.copy(start).addScaledVector(delta, 0.5);
	mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), delta.normalize());
	mesh.castShadow = true;
	parent.add(mesh);
	return mesh;
};

const createRotor = (parent: THREE.Object3D, position: [number, number, number], radius: number) => {
	const frame = new THREE.Mesh(new THREE.TorusGeometry(radius, 0.055, 8, 32).rotateY(Math.PI / 2), darkSteel);
	frame.position.set(...position);
	parent.add(frame);
	const rotor = new THREE.Group();
	rotor.position.set(...position);
	for (let blade = 0; blade < 5; blade++) {
		const arm = new THREE.Group();
		arm.rotation.x = (blade * Math.PI * 2) / 5;
		const wing = box(arm, [0.035, radius * 0.83, radius * 0.29], [0, radius * 0.43, 0], steel);
		wing.rotation.y = 0.35;
		rotor.add(arm);
	}
	cylinder(rotor, radius * 0.18, 0.12, [0, 0, 0], darkSteel).rotation.z = Math.PI / 2;
	parent.add(rotor);
	return rotor;
};

export const buildEquipment = (scene: THREE.Scene) => {
	const entries: EquipmentEntry[] = [];
	const signalMaterials = new Map<string, THREE.MeshStandardMaterial>();
	const rotors: Array<{ rotor: THREE.Object3D; code: string }> = [];
	const roofPanels: THREE.Object3D[] = [];
	const wetSurfaces: THREE.MeshStandardMaterial[] = [];
	const ledSurfaces: THREE.MeshStandardMaterial[] = [];
	const roots = new THREE.Group();
	roots.name = 'agricultural-equipment';
	scene.add(roots);

	const register = (code: string, name: string, zone: string, kind: EquipmentEntry['kind'], description: string, position: [number, number, number]) => {
		const object = new THREE.Group();
		object.name = name;
		object.position.set(...position);
		object.userData.deviceCode = code;
		roots.add(object);
		const entry: EquipmentEntry = { code, name, zone, kind, description, object, active: false };
		entries.push(entry);
		return object;
	};

	const signal = (parent: THREE.Object3D, code: string, position: [number, number, number]) => {
		const material = new THREE.MeshStandardMaterial({ color: 0x6a7471, emissive: 0x155b43, emissiveIntensity: 0.18, roughness: 0.28 });
		const indicator = new THREE.Mesh(new THREE.SphereGeometry(0.055, 10, 8), material);
		indicator.position.set(...position);
		parent.add(indicator);
		signalMaterials.set(code, material);
	};

	const service = register('IRRIGATION', '过滤泵阀与滴灌主管', '西端设备区', 'actuator', '水源经过过滤、加压与分区阀进入四条种植床的滴灌带。', [-11.7, 0, 5.25]);
	box(service, [1.65, 0.12, 1.05], [0, 0.11, 0], darkSteel);
	cylinder(service, 0.31, 1.15, [-0.47, 0.76, 0], steel);
	cylinder(service, 0.15, 0.75, [0.18, 0.54, -0.17], pipeBlue);
	box(service, [0.45, 0.4, 0.42], [0.44, 0.46, 0.2], steel);
	pipe(service, new THREE.Vector3(-0.47, 1.26, 0), new THREE.Vector3(0.63, 1.26, 0), 0.055, pipeBlue);
	for (let valve = 0; valve < 4; valve++) {
		const z = -0.36 + valve * 0.23;
		pipe(service, new THREE.Vector3(0.62, 1.26, z), new THREE.Vector3(0.74, 0.92, z), 0.028, pipeBlue);
		box(service, [0.16, 0.1, 0.1], [0.74, 0.98, z], darkSteel);
	}
	signal(service, 'IRRIGATION', [0.44, 0.72, 0.42]);
	for (const bedZ of GREENHOUSE_LAYOUT.bedCenters) {
		pipe(roots, new THREE.Vector3(-11.55, 0.42, 5.25), new THREE.Vector3(-11.55, 0.42, bedZ), 0.035, pipeBlue);
		pipe(roots, new THREE.Vector3(-11.55, 0.42, bedZ), new THREE.Vector3(-10.5, 0.31, bedZ), 0.023, pipeBlue);
	}

	const tank = register('CO2_SUPPLY', 'CO₂ 气源与减压阀', '西端供气区', 'actuator', '气瓶经减压阀接入棚内分配管；强排风期间禁止补气。', [-11.75, 0, -5.55]);
	cylinder(tank, 0.3, 1.48, [0, 0.79, 0], new THREE.MeshStandardMaterial({ color: 0x476456, metalness: 0.47, roughness: 0.42 }));
	cylinder(tank, 0.13, 0.22, [0, 1.62, 0], steel);
	box(tank, [0.31, 0.18, 0.24], [0.35, 1.53, 0], darkSteel);
	pipe(tank, new THREE.Vector3(0.35, 1.53, 0), new THREE.Vector3(1.2, 2.0, 0), 0.024, pipeGreen);
	signal(tank, 'CO2_SUPPLY', [0.36, 1.56, 0.14]);
	pipe(roots, new THREE.Vector3(-10.55, 2, -5.55), new THREE.Vector3(-10.55, 2, 5.4), 0.024, pipeGreen);
	for (const bedZ of GREENHOUSE_LAYOUT.bedCenters) {
		pipe(roots, new THREE.Vector3(-10.55, 2, bedZ), new THREE.Vector3(10.55, 2, bedZ), 0.019, pipeGreen);
	}

	for (const bayCenter of GREENHOUSE_LAYOUT.bayCenters) {
		const code = `WET_PAD_${bayCenter < 0 ? 'N' : 'S'}`;
		const pad = register(code, '蒸发湿帘进风墙', bayCenter < 0 ? '北跨西端' : '南跨西端', 'actuator', '循环水润湿蜂窝湿帘，需与对端排风配合产生穿堂气流。', [-12.94, 2.2, bayCenter]);
		box(pad, [0.18, 2.6, 2.25], [0, 0, 0], darkSteel);
		const wetMaterial = padBrown.clone();
		wetSurfaces.push(wetMaterial);
		box(pad, [0.205, 2.36, 2.04], [-0.025, 0, 0], wetMaterial);
		for (let slat = 0; slat < 27; slat++) {
			const rib = box(pad, [0.025, 2.33, 0.023], [-0.141, 0, -0.98 + slat * 0.075], steel);
			rib.castShadow = false;
		}
		pipe(pad, new THREE.Vector3(-0.2, 1.33, -1.08), new THREE.Vector3(-0.2, 1.33, 1.08), 0.044, pipeBlue);
		box(pad, [0.44, 0.27, 2.3], [-0.15, -1.43, 0], pipeBlue);
		signal(pad, code, [-0.24, 1.45, 1.03]);

		const exhaustCode = `EXHAUST_${bayCenter < 0 ? 'N' : 'S'}`;
		const exhaust = register(exhaustCode, '强制排风机', bayCenter < 0 ? '北跨东端' : '南跨东端', 'actuator', '从湿帘对端抽排空气，与屋顶通风窗和 CO₂ 补气分开控制。', [12.93, 2.45, bayCenter]);
		for (const side of [-1, 1]) {
			box(exhaust, [0.38, 0.09, 1.78], [0, side * 0.85, 0], darkSteel);
			box(exhaust, [0.38, 1.7, 0.09], [0, 0, side * 0.85], darkSteel);
		}
		const shroud = new THREE.Mesh(new THREE.CylinderGeometry(0.75, 0.75, 0.29, 24, 1, true).rotateZ(Math.PI / 2), steel);
		shroud.position.x = -0.12;
		exhaust.add(shroud);
		rotors.push({ rotor: createRotor(exhaust, [-0.27, 0, 0], 0.69), code: exhaustCode });
		signal(exhaust, exhaustCode, [-0.26, 0.7, 0.72]);
	}

	const shade = register('SHADE', '内遮阳幕卷轴与驱动', '双跨中央天沟下方', 'actuator', '中央驱动沿两跨导轨同步展开遮阳幕，减少冠层直射。', [0, GREENHOUSE_LAYOUT.eaveHeight - 0.24, 0]);
	box(shade, [0.58, 0.36, 0.42], [0, 0, 0], darkSteel);
	cylinder(shade, 0.12, 0.9, [0.45, 0, 0], steel).rotation.z = Math.PI / 2;
	signal(shade, 'SHADE', [0.02, 0.1, 0.23]);

	const lamps = register('SUPPLEMENTAL_LIGHT', '冠层补光灯及配电', '四条种植床上方', 'actuator', '沿种植床冠层悬挂灯列；模拟开关改变灯体发光与照度。', [0, 3.25, -4.4]);
	box(lamps, [0.84, 0.16, 0.34], [0, 0, 0], steel);
	box(lamps, [0.74, 0.045, 0.25], [0, -0.11, 0], glass);
	pipe(lamps, new THREE.Vector3(0, 0.1, 0), new THREE.Vector3(0, 0.62, 0), 0.013, darkSteel);
	signal(lamps, 'SUPPLEMENTAL_LIGHT', [0.35, 0.04, 0.18]);

	const sideVent = register('SIDE_VENT', '侧窗卷膜驱动', '双侧檐口', 'actuator', '侧部卷膜调节自然进风；与屋面窗及强排风独立。', [0, 3.66, -6.48]);
	box(sideVent, [0.54, 0.32, 0.3], [0, 0, 0], darkSteel);
	cylinder(sideVent, 0.08, 1.1, [0.72, 0, 0], steel).rotation.z = Math.PI / 2;
	signal(sideVent, 'SIDE_VENT', [0, 0.1, 0.17]);

	for (const bayCenter of GREENHOUSE_LAYOUT.bayCenters) {
		for (let index = 0; index < 3; index++) {
			const code = `HAF_${bayCenter < 0 ? 'N' : 'S'}_${index + 1}`;
			const x = -7 + index * 7;
			const side = bayCenter < 0 ? 1 : -1;
			const fan = register(code, 'HAF 环流风机', `${bayCenter < 0 ? '北' : '南'}跨 ${index + 1} 区`, 'actuator', '棚内循环混合空气，不等同于对外强制排风。', [x, 3.15, bayCenter + side * 1.35]);
			cylinder(fan, 0.08, 0.6, [0, 0.28, 0], steel);
			rotors.push({ rotor: createRotor(fan, [-0.12, 0, 0], 0.32), code });
			signal(fan, code, [0.02, 0.42, 0]);
		}
	}

	for (const bayCenter of GREENHOUSE_LAYOUT.bayCenters) {
		const code = `ROOF_VENT_${bayCenter < 0 ? 'N' : 'S'}`;
		const vent = register(code, '屋面通风窗', bayCenter < 0 ? '北跨拱顶' : '南跨拱顶', 'actuator', '独立于端墙强排风的自然通风执行器。', [0, roofHeightAt(bayCenter) + 0.04, bayCenter]);
		box(vent, [5.4, 0.12, 1.08], [0, 0, 0], darkSteel);
		const panel = new THREE.Group();
		panel.position.z = -0.48;
		box(panel, [5.15, 0.04, 0.94], [0, 0.08, 0.47], glass);
		box(panel, [5.2, 0.045, 0.035], [0, 0.09, 0.93], steel);
		vent.add(panel);
		roofPanels.push(panel);
		signal(vent, code, [2.5, 0.12, 0.45]);
	}

	const makeSensor = (code: string, name: string, zone: string, description: string, position: [number, number, number], unit: string) => {
		const sensor = register(code, name, zone, 'sensor', description, position);
		const material = new THREE.MeshStandardMaterial({ color: 0xf0eee6, metalness: 0.15, roughness: 0.48 });
		box(sensor, [0.18, 0.28, 0.13], [0, 0, 0], material);
		box(sensor, [0.12, 0.045, 0.018], [0, 0.06, 0.073], darkSteel);
		pipe(sensor, new THREE.Vector3(0, 0.16, 0), new THREE.Vector3(0, 0.72, 0), 0.012, steel);
		signal(sensor, code, [0.055, -0.075, 0.078]);
		entries[entries.length - 1].unit = unit;
	};
	makeSensor('SENSOR_OUTDOOR', '室外气象探头', '西端室外', '棚外参考温度，独立于棚内冠层测点。', [-16, 2.6, 6], '℃');
	makeSensor('SENSOR_AIR_N', '北跨冠层温湿度', '北跨冠层', '避开补光灯与进风口的冠层代表测点。', [-3, 2.2, -3.25], '℃');
	makeSensor('SENSOR_AIR_S', '南跨冠层温湿度', '南跨冠层', '与北跨分区布置的温湿度测点。', [4, 2.2, 3.25], '%');
	makeSensor('SENSOR_CO2', '冠层 CO₂ 探头', '中央冠层', '位于作物高度附近，远离气源出口。', [0, 2.1, -2.2], 'ppm');
	makeSensor('SENSOR_LIGHT', 'PPFD 光量子传感器', '南跨冠层', '测量冠层上的光合有效光子通量密度。', [-3, 2.55, 3.35], 'μmol/m²/s');
	for (const [index, bedZ] of GREENHOUSE_LAYOUT.bedCenters.entries()) {
		makeSensor(`SENSOR_ROOT_${index + 1}`, '根区含水率探头', `种植床 ${index + 1}`, '探头插入根区，代表该床的模拟土壤含水率。', [5.8, 0.47, bedZ], '%');
	}
	makeSensor('SENSOR_FLOW', '灌溉流量计', '西端滴灌主管', '过滤泵阀之后的主管流量模拟测点。', [-10.75, 0.95, 5.25], 'L/min');

	const update = (frame: EquipmentFrame, deltaSeconds: number) => {
		for (const entry of entries) {
			let active = false;
			if (entry.code === 'IRRIGATION') active = frame.irrigation;
			else if (entry.code === 'SHADE') active = frame.shade;
			else if (entry.code === 'SUPPLEMENTAL_LIGHT') active = frame.supplementalLight;
			else if (entry.code === 'SIDE_VENT') active = frame.ventilation;
			else if (entry.code === 'CO2_SUPPLY') active = frame.co2 && !frame.ventilation && !frame.roofVent && !frame.exhaustFan;
			else if (entry.code.startsWith('WET_PAD_')) active = frame.coolingPad && frame.exhaustFan;
			else if (entry.code.startsWith('EXHAUST_')) active = frame.exhaustFan;
			else if (entry.code.startsWith('HAF_')) active = frame.circulationFan;
			else if (entry.code.startsWith('ROOF_VENT_')) active = frame.roofVent;
			else if (entry.kind === 'sensor') active = true;
			entry.active = active;
			const signalMaterial = signalMaterials.get(entry.code);
			if (signalMaterial) {
				signalMaterial.color.setHex(active ? 0x8bf1ac : 0x69736e);
				signalMaterial.emissiveIntensity = active ? 1.4 : 0.08;
			}
			if (frame.sensorReadings && Object.prototype.hasOwnProperty.call(frame.sensorReadings, entry.code)) entry.value = frame.sensorReadings[entry.code];
			else if (entry.code === 'SENSOR_OUTDOOR') entry.value = frame.temperatureC - 2.5;
			else if (entry.code === 'SENSOR_AIR_N') entry.value = frame.temperatureC;
			else if (entry.code === 'SENSOR_AIR_S') entry.value = frame.airHumidityPct;
			else if (entry.code === 'SENSOR_CO2') entry.value = frame.co2Ppm;
			else if (entry.code === 'SENSOR_LIGHT') entry.value = frame.lightPpfd;
			else if (entry.code.startsWith('SENSOR_ROOT_')) entry.value = frame.soilMoisturePct;
			else if (entry.code === 'SENSOR_FLOW') entry.value = frame.irrigation ? 4 : 0;
		}
		for (const { rotor, code } of rotors) {
			if (entries.find((entry) => entry.code === code)?.active) rotor.rotation.x += deltaSeconds * 18;
		}
		for (const panel of roofPanels) panel.rotation.x += ((frame.roofVent ? -0.68 : 0) - panel.rotation.x) * Math.min(1, deltaSeconds * 3);
		for (const material of wetSurfaces) material.color.setHex(frame.coolingPad && frame.exhaustFan ? 0x6d8077 : 0x9a8c69);
		for (const material of ledSurfaces) material.emissiveIntensity = frame.supplementalLight ? 2.2 : 0.04;
	};

	const replaceVisuals = (imported: THREE.Group) => {
		const importedDevices = new Map<string, THREE.Object3D>();
		imported.traverse((node) => {
			if (typeof node.userData.deviceCode === 'string') importedDevices.set(node.userData.deviceCode, node);
		});
		const missing = entries.filter((entry) => !importedDevices.has(entry.code)).map((entry) => entry.code);
		if (missing.length) throw new Error(`Blender equipment is missing device codes: ${missing.join(', ')}`);

		roots.add(imported);
		for (const entry of entries) {
			entry.object.visible = false;
			entry.object = importedDevices.get(entry.code)!;
		}
		imported.traverse((node) => {
			if (node.name.startsWith('ROTOR__')) rotors.push({ rotor: node, code: node.name.slice(7) });
			if (node.name.startsWith('ROOF_PANEL__')) roofPanels.push(node);
			if (!(node instanceof THREE.Mesh)) return;
			if (node.name.startsWith('SIGNAL__')) {
				const material = (node.material as THREE.MeshStandardMaterial).clone();
				node.material = material;
				signalMaterials.set(node.name.slice(8), material);
			} else if (node.name.startsWith('WET_SURFACE__')) {
				const material = (node.material as THREE.MeshStandardMaterial).clone();
				node.material = material;
				wetSurfaces.push(material);
			} else if (node.name.startsWith('LED_EMITTER')) {
				const material = (node.material as THREE.MeshStandardMaterial).clone();
				material.emissive.setHex(0xffd8ab);
				node.material = material;
				ledSurfaces.push(material);
			}
		});
	};

	return { entries, roots, update, replaceVisuals };
};
