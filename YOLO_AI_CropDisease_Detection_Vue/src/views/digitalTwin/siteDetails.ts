import * as THREE from 'three';
import { GREENHOUSE_LAYOUT } from './greenhouseLayout';

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

	box(8.6, 0.09, 2.8, -17.3, 0.025, 0, concrete);
	box(6.8, 0.12, 4.4, -16.6, 0.04, 4.1, concrete);
	box(29.5, 0.07, 1.18, 0, 0.025, 7.7, concrete);
	box(29.5, 0.07, 0.8, 0, 0.025, -7.5, concrete);

	const reservoir = new THREE.Mesh(new THREE.CylinderGeometry(0.84, 0.89, 1.9, 32), waterBlue);
	reservoir.position.set(-17.2, 1.07, 4.05);
	reservoir.castShadow = true;
	reservoir.receiveShadow = true;
	group.add(reservoir);
	for (const height of [0.34, 1.02, 1.7]) {
		const band = new THREE.Mesh(new THREE.TorusGeometry(0.86, 0.027, 8, 40), galvanized);
		band.rotation.x = Math.PI / 2;
		band.position.set(-17.2, height, 4.05);
		group.add(band);
	}
	box(0.4, 0.11, 0.4, -17.2, 2.06, 4.05, rubber);
	line([-16.35, 0.52, 4.05], [-14.5, 0.52, 4.05], 0.058, waterBlue);
	line([-14.5, 0.52, 4.05], [-14.5, 0.52, 5.25], 0.058, waterBlue);
	line([-14.5, 0.52, 5.25], [-11.7, 0.52, 5.25], 0.058, waterBlue);
	box(0.96, 1.72, 0.58, -14.1, 0.93, 2.65, painted);
	box(0.72, 1.37, 0.035, -14.1, 0.98, 2.94, plastic);
	box(0.45, 0.24, 0.041, -14.1, 1.39, 2.97, rubber);
	for (let index = 0; index < 3; index++) {
		const lamp = new THREE.Mesh(new THREE.SphereGeometry(0.045, 10, 8), index === 0 ? amber : waterBlue);
		lamp.position.set(-14.27 + index * 0.16, 0.82, 2.975);
		group.add(lamp);
	}
	line([-14.1, 1.75, 2.65], [-13.0, 3.45, 2.65], 0.022, rubber);
	line([-13.0, 3.45, 2.65], [12.5, 3.45, 2.65], 0.022, rubber);
	box(24.8, 0.08, 0.28, 0, 3.55, 0, galvanized);
	for (const x of [-10, -5, 0, 5, 10]) {
		line([x, 3.55, 0], [x, 4.04, 0], 0.021, frame);
	}

	const layout = GREENHOUSE_LAYOUT;
	const lineHeight = 3.25;
	for (const bedZ of layout.bedCenters) {
		line([-11, lineHeight, bedZ], [11, lineHeight, bedZ], 0.023, galvanized);
		for (const endX of [-10.9, 10.9]) {
			line([endX, 0.32, bedZ], [endX, lineHeight, bedZ], 0.035, frame);
		}
	}
	const twineGeometry = new THREE.CylinderGeometry(0.0045, 0.0045, 2.88, 5);
	const twines = new THREE.InstancedMesh(twineGeometry, plastic, layout.bedCenters.length * layout.plantsPerBed);
	const matrix = new THREE.Matrix4();
	let twineIndex = 0;
	for (const bedZ of layout.bedCenters) {
		for (let plant = 0; plant < layout.plantsPerBed; plant++) {
			const x = -layout.bedLength / 2 + (plant + 0.5) * (layout.bedLength / layout.plantsPerBed);
			matrix.makeTranslation(x, 1.81, bedZ);
			twines.setMatrixAt(twineIndex++, matrix);
		}
	}
	twines.instanceMatrix.needsUpdate = true;
	group.add(twines);

	for (const endX of [-10.5, 10.5]) {
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
	line([-16, 0.16, 6], [-16, 2.6, 6], 0.025, galvanized);
	box(0.32, 0.2, 0.32, -16, 0.11, 6, concrete);
	return group;
};
