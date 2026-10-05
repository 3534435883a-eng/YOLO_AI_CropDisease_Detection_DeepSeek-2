import * as THREE from 'three';
import type { EquipmentEntry } from './equipment';
import { GREENHOUSE_LAYOUT as layout } from './greenhouseLayout';

type Route = { entry: EquipmentEntry; start: THREE.Vector3; end: THREE.Vector3; direction: THREE.Vector3; side: THREE.Vector3; mix: number };
type Layer = { routes: Route[]; lines: THREE.LineSegments; positions: Float32Array };
const MAX_ARROWS = 8;
const writePoint = (array: Float32Array, index: number, point: THREE.Vector3) => {
	array[index] = point.x; array[index + 1] = point.y; array[index + 2] = point.z;
};

/** Direction diagrams, not velocity measurements or fluid simulation. Three batched draws. */
export class OperatingFlows {
	private layers: Layer[] = [];
	private enabled = true;
	private elapsed = 0;
	private arrows = MAX_ARROWS;
	private point = new THREE.Vector3();
	private tail = new THREE.Vector3();
	private tip = new THREE.Vector3();
	constructor(private scene: THREE.Scene, entries: EquipmentEntry[]) {
		const air: Route[] = [], water: Route[] = [], heat: Route[] = [];
		const add = (routes: Route[], entry: EquipmentEntry, from: THREE.Vector3, to: THREE.Vector3) => {
			const direction = to.clone().sub(from).normalize();
			const side = new THREE.Vector3().crossVectors(direction, new THREE.Vector3(0, 1, 0));
			if (side.lengthSq() < 0.01) side.set(1, 0, 0);
			routes.push({ entry, start: from, end: to, direction, side: side.normalize(), mix: 0 });
		};
		for (const entry of entries) {
			const origin = entry.object.getWorldPosition(new THREE.Vector3());
			if (entry.code.startsWith('HAF_')) {
				add(air, entry, origin.clone().add(new THREE.Vector3(-3, 0, 0)), origin.clone().add(new THREE.Vector3(3, 0, 0)));
			} else if (entry.code.startsWith('EXHAUST_')) {
				add(air, entry, new THREE.Vector3(layout.wetPadEndX + 1, origin.y, origin.z), new THREE.Vector3(layout.exhaustEndX + 2, origin.y, origin.z));
			} else if (entry.code === 'IRRIGATION') {
				for (const z of layout.bedCenters) {
					const join = new THREE.Vector3(layout.serviceEndX + 0.15, 0.48, z);
					add(water, entry, new THREE.Vector3(layout.serviceEndX + 0.15, 0.48, 5.25), join.clone());
					add(water, entry, join, new THREE.Vector3(-layout.bedLength / 2, 0.37, z));
					add(water, entry, new THREE.Vector3(-layout.bedLength / 2, 0.37, z), new THREE.Vector3(layout.bedLength / 2, 0.37, z));
				}
			} else if (entry.code === 'HEATING') {
				for (const offset of [-0.4, 0, 0.4]) {
					add(heat, entry, origin.clone().add(new THREE.Vector3(offset, 1, 0)), origin.clone().add(new THREE.Vector3(offset, 3.9, 0)));
				}
			}
		}
		for (const [routes, color] of [[air, 0x9bcbb2], [water, 0x82bce4], [heat, 0xefb36b]] as const) {
			const positions = new Float32Array(routes.length * MAX_ARROWS * 18);
			const geometry = new THREE.BufferGeometry();
			geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3).setUsage(THREE.DynamicDrawUsage));
			const lines = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.8, depthWrite: false }));
			lines.frustumCulled = false;
			lines.visible = false;
			scene.add(lines);
			this.layers.push({ routes, lines, positions });
		}
	}
	setEnabled(enabled: boolean) { this.enabled = enabled; }
	setQuality(quality: 'high' | 'medium' | 'low') { this.arrows = quality === 'high' ? 8 : quality === 'medium' ? 5 : 3; }
	update(dt: number) {
		this.elapsed += dt;
		for (const layer of this.layers) {
			let index = 0, visible = false;
			for (const route of layer.routes) {
				route.mix += ((this.enabled && route.entry.active ? 1 : 0) - route.mix) * (1 - Math.exp(-dt * 4));
				visible ||= route.mix > 0.01;
				for (let i = 0; i < MAX_ARROWS; i++) {
					const phase = (i / this.arrows + this.elapsed * 0.17) % 1;
					this.point.lerpVectors(route.start, route.end, phase);
					const size = i < this.arrows && route.mix > 0.01 ? 0.24 * route.mix : 0;
					this.tail.copy(this.point).addScaledVector(route.direction, -size);
					this.tip.copy(this.tail).addScaledVector(route.side, size * 0.65);
					writePoint(layer.positions, index, this.point);
					writePoint(layer.positions, index + 3, this.tip);
					writePoint(layer.positions, index + 6, this.point);
					writePoint(layer.positions, index + 9, this.tail);
					this.tip.copy(this.tail).addScaledVector(route.side, -size * 0.65);
					writePoint(layer.positions, index + 12, this.point);
					writePoint(layer.positions, index + 15, this.tip);
					index += 18;
				}
			}
			layer.lines.visible = visible;
			(layer.lines.geometry.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true;
		}
	}
	dispose() {
		for (const layer of this.layers) {
			this.scene.remove(layer.lines);
			layer.lines.geometry.dispose();
			(layer.lines.material as THREE.Material).dispose();
		}
	}
}
