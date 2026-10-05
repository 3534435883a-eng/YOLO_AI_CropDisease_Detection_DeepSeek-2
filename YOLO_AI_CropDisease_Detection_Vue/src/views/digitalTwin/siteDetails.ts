import * as THREE from 'three';
import { GREENHOUSE_LAYOUT, plantPositionAt } from './greenhouseLayout';

export const buildSiteDetails = (scene: THREE.Scene, concrete: THREE.Material, frame: THREE.Material) => {
	const group = new THREE.Group();
	group.name = 'greenhouse-site-and-crop-support';
	scene.add(group);

	const rubber = new THREE.MeshStandardMaterial({ color: 0x232c2a, roughness: 0.82, metalness: 0.06 });
	const galvanized = new THREE.MeshStandardMaterial({ color: 0xb7bcb5, roughness: 0.39, metalness: 0.82 });
	const painted = new THREE.MeshStandardMaterial({ color: 0x48636a, roughness: 0.48, metalness: 0.55 });
	const waterBlue = new THREE.MeshStandardMaterial({ color: 0x316581, roughness: 0.5, metalness: 0.18 });
	const plastic = new THREE.MeshStandardMaterial({ color: 0xd0d8ce, roughness: 0.58, metalness: 0.04 });
	const wood = new THREE.MeshStandardMaterial({ color: 0x897259, roughness: 0.88, metalness: 0 });
	const amber = new THREE.MeshStandardMaterial({ color: 0xe9ae43, roughness: 0.5, metalness: 0.12 });

	const box = (width: number, height: number, depth: number, x: number, y: number, z: number, material: THREE.Material) => {
		const mesh = new THREE.Mesh(new THREE.BoxGeometry(width, height, depth), material);
		mesh.position.set(x, y, z);
		mesh.castShadow = height > 0.1;
		mesh.receiveShadow = true;
		group.add(mesh);
		return mesh;
	};

	const line = (from: [number, number, number], to: [number, number, number], radius: number, material: THREE.Material) => {
		const start = new THREE.Vector3(...from);
		const end = new THREE.Vector3(...to);
		const direction = end.clone().sub(start);
		const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, direction.length(), 8), material);
		mesh.position.copy(start).addScaledVector(direction, 0.5);
		mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), direction.normalize());
		mesh.castShadow = radius > 0.024;
		group.add(mesh);
		return mesh;
	};

	box(8.6, 0.09, 2.8, GREENHOUSE_LAYOUT.serviceEndX + 2.0, 0.025, 0, concrete);
	box(6.8, 0.12, 4.4, GREENHOUSE_LAYOUT.serviceEndX + 2.8, 0.04, 4.1, concrete);
	box(GREENHOUSE_LAYOUT.length - 10.5, 0.07, 1.18, 0, 0.025, 16.5, concrete);
	box(GREENHOUSE_LAYOUT.length - 10.5, 0.07, 0.8, 0, 0.025, -16.5, concrete);

	const serviceX = GREENHOUSE_LAYOUT.serviceEndX - 1.2;
	const reservoir = new THREE.Mesh(new THREE.CylinderGeometry(0.84, 0.89, 1.9, 32), waterBlue);
	reservoir.position.set(serviceX, 1.07, 4.05);
	reservoir.castShadow = true;
	reservoir.receiveShadow = true;
	group.add(reservoir);
	for (const height of [0.34, 1.02, 1.7]) {
		const band = new THREE.Mesh(new THREE.TorusGeometry(0.86, 0.027, 8, 40), galvanized);
		band.rotation.x = Math.PI / 2;
		band.position.set(serviceX, height, 4.05);
		group.add(band);
	}
	box(0.4, 0.11, 0.4, serviceX, 2.06, 4.05, rubber);
	line([serviceX + 0.85, 0.52, 4.05], [serviceX + 2.5, 0.52, 4.05], 0.058, waterBlue);
	line([serviceX + 2.5, 0.52, 4.05], [serviceX + 2.5, 0.52, 5.25], 0.058, waterBlue);
	line([serviceX + 2.5, 0.52, 5.25], [GREENHOUSE_LAYOUT.serviceEndX, 0.52, 5.25], 0.058, waterBlue);
	box(0.96, 1.72, 0.58, serviceX + 3.1, 0.93, 2.65, painted);
	box(0.72, 1.37, 0.035, serviceX + 3.1, 0.98, 2.94, plastic);
	box(0.45, 0.24, 0.041, serviceX + 3.1, 1.39, 2.97, rubber);
	for (let index = 0; index < 3; index++) {
		const lamp = new THREE.Mesh(new THREE.SphereGeometry(0.045, 10, 8), index === 0 ? amber : waterBlue);
		lamp.position.set(serviceX + 2.93 + index * 0.16, 0.82, 2.975);
		group.add(lamp);
	}
	line([serviceX + 3.1, 1.75, 2.65], [serviceX + 4.2, 3.45, 2.65], 0.022, rubber);
	line([serviceX + 4.2, 3.45, 2.65], [GREENHOUSE_LAYOUT.length / 2 - 1.5, 3.45, 2.65], 0.022, rubber);
	box(GREENHOUSE_LAYOUT.length - 6, 0.08, 0.28, 0, 3.55, 0, galvanized);
	for (const x of [-10, -5, 0, 5, 10]) {
		line([x, 3.55, 0], [x, GREENHOUSE_LAYOUT.eaveHeight - 0.46, 0], 0.021, frame);
	}

	const layout = GREENHOUSE_LAYOUT;
	const lineHeight = 3.25;
	for (const bedZ of layout.bedCenters) {
		line([-layout.bedLength / 2, lineHeight, bedZ], [layout.bedLength / 2, lineHeight, bedZ], 0.023, galvanized);
		for (const endX of [-layout.bedLength / 2 + 0.1, layout.bedLength / 2 - 0.1]) {
			line([endX, 0.32, bedZ], [endX, lineHeight, bedZ], 0.035, frame);
		}
	}
	const twineGeometry = new THREE.CylinderGeometry(0.0045, 0.0045, 2.88, 5);
	const twines = new THREE.InstancedMesh(twineGeometry, plastic, layout.bedCenters.length * layout.plantsPerBed);
	const matrix = new THREE.Matrix4();
	let twineIndex = 0;
	for (let plotIndex = 0; plotIndex < layout.bedCenters.length; plotIndex++) {
		for (let plant = 0; plant < layout.plantsPerBed; plant++) {
			const point = plantPositionAt(plotIndex, plant);
			matrix.makeTranslation(point.x, 1.81, point.z);
			twines.setMatrixAt(twineIndex++, matrix);
		}
	}
	twines.instanceMatrix.needsUpdate = true;
	group.add(twines);

	for (const endX of [-layout.bedLength / 2 + 0.5, layout.bedLength / 2 - 0.5]) {
		for (const bedZ of layout.bedCenters) {
			box(0.43, 0.04, 0.31, endX, 0.38, bedZ, wood);
			box(0.06, 0.52, 0.06, endX, 0.63, bedZ, painted);
			box(0.27, 0.14, 0.025, endX, 0.84, bedZ + 0.045, plastic);
		}
	}

	for (const x of [-18.4, -17.8, -17.2]) {
		box(0.5, 0.55, 0.65, x, 0.34, -5.15, wood);
		box(0.48, 0.06, 0.64, x, 0.64, -5.15, painted);
	}
	line([GREENHOUSE_LAYOUT.serviceEndX - 2.4, 0.16, 6], [GREENHOUSE_LAYOUT.serviceEndX - 2.4, 2.6, 6], 0.025, galvanized);
	box(0.32, 0.2, 0.32, GREENHOUSE_LAYOUT.serviceEndX - 2.4, 0.11, 6, concrete);
	return group;
};
