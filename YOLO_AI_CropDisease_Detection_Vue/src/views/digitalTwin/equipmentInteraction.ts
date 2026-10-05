import * as THREE from 'three';
import type { EquipmentEntry } from './equipment';

export type HoverLabel = { name: string; zone: string; x: number; y: number };

/** Selection markers have their own material; equipment materials remain intact. */
export class EquipmentInteraction {
	private selected: EquipmentEntry | null = null;
	private hovered: EquipmentEntry | null = null;
	private selectedBox = new THREE.Box3();
	private hoverBox = new THREE.Box3();
	private selectedMarker = new THREE.Box3Helper(this.selectedBox, 0xe3ba6b);
	private hoverMarker = new THREE.Box3Helper(this.hoverBox, 0x88b8a3);
	private raycaster = new THREE.Raycaster();
	private pointer = new THREE.Vector2();
	private down = new THREE.Vector2();
	private anchor = new THREE.Vector3();
	private inside = false;
	private pressed = false;
	private dragged = false;
	private timer = 0;
	private enabled = true;
	constructor(
		private canvas: HTMLCanvasElement,
		private camera: THREE.Camera,
		private scene: THREE.Scene,
		private equipment: { entries: EquipmentEntry[]; roots: THREE.Group },
		private onSelect: (entry: EquipmentEntry | null) => void,
	) {
		for (const marker of [this.selectedMarker, this.hoverMarker]) {
			marker.visible = false;
			marker.renderOrder = 20;
			const material = marker.material as THREE.LineBasicMaterial;
			material.transparent = true;
			material.opacity = 0.85;
			material.depthTest = false;
			material.depthWrite = false;
			scene.add(marker);
		}
		canvas.addEventListener('pointerdown', this.onDown);
		canvas.addEventListener('pointermove', this.onMove);
		canvas.addEventListener('pointerleave', this.onLeave);
		canvas.addEventListener('click', this.onClick);
		window.addEventListener('pointerup', this.onUp);
	}
	select(code: string | null) {
		this.selected = this.equipment.entries.find(entry => entry.code === code) || null;
		this.updateMarker(this.selected, this.selectedBox, this.selectedMarker);
		this.onSelect(this.selected);
	}
	private updateMarker(entry: EquipmentEntry | null, box: THREE.Box3, marker: THREE.Box3Helper) {
		marker.visible = !!entry;
		if (entry) {
			box.setFromObject(entry.object).expandByScalar(0.09);
			marker.visible = !box.isEmpty();
		}
	}
	private pick(center = false) {
		this.raycaster.setFromCamera(center ? new THREE.Vector2(0, 0) : this.pointer, this.camera);
		for (const hit of this.raycaster.intersectObject(this.equipment.roots, true)) {
			let node: THREE.Object3D | null = hit.object;
			while (node && !node.userData.deviceCode) node = node.parent;
			const entry = this.equipment.entries.find(item => item.code === node?.userData.deviceCode);
			if (entry) return entry;
		}
		return null;
	}
	update(dt: number, orbit: boolean) {
		this.enabled = orbit;
		this.timer += dt;
		if (this.timer < 0.08) return;
		this.timer = 0;
		this.hovered = orbit && this.inside && !this.pressed ? this.pick() : null;
		this.updateMarker(this.hovered === this.selected ? null : this.hovered, this.hoverBox, this.hoverMarker);
		this.updateMarker(this.selected, this.selectedBox, this.selectedMarker);
		this.canvas.style.cursor = this.hovered ? 'pointer' : '';
	}
	getHoverLabel(width: number, height: number): HoverLabel | null {
		if (!this.hovered || !this.enabled) return null;
		this.hovered.object.getWorldPosition(this.anchor).project(this.camera);
		if (this.anchor.z < -1 || this.anchor.z > 1 || Math.abs(this.anchor.x) > 1 || Math.abs(this.anchor.y) > 1) return null;
		return {
			name: this.hovered.name, zone: this.hovered.zone,
			x: Math.max(125, Math.min(width - 125, (this.anchor.x * 0.5 + 0.5) * width)),
			y: Math.max(120, Math.min(height - 80, (-this.anchor.y * 0.5 + 0.5) * height - 25)),
		};
	}
	private onDown = (event: PointerEvent) => {
		this.down.set(event.clientX, event.clientY);
		this.pressed = true;
		this.dragged = false;
	};
	private onMove = (event: PointerEvent) => {
		this.inside = true;
		const rect = this.canvas.getBoundingClientRect();
		this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
		if (this.pressed && Math.hypot(event.clientX - this.down.x, event.clientY - this.down.y) > 5) this.dragged = true;
	};
	private onUp = () => { this.pressed = false; };
	private onLeave = () => {
		this.inside = false;
		this.hovered = null;
		this.hoverMarker.visible = false;
		this.canvas.style.cursor = '';
	};
	private onClick = (event: MouseEvent) => {
		if (this.dragged || event.button !== 0) return;
		const rect = this.canvas.getBoundingClientRect();
		this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
		this.select(this.pick(document.pointerLockElement === this.canvas)?.code || null);
	};
	dispose() {
		this.canvas.removeEventListener('pointerdown', this.onDown);
		this.canvas.removeEventListener('pointermove', this.onMove);
		this.canvas.removeEventListener('pointerleave', this.onLeave);
		this.canvas.removeEventListener('click', this.onClick);
		window.removeEventListener('pointerup', this.onUp);
		this.canvas.style.cursor = '';
		for (const marker of [this.selectedMarker, this.hoverMarker]) {
			this.scene.remove(marker);
			marker.geometry.dispose();
			(marker.material as THREE.Material).dispose();
		}
	}
}
