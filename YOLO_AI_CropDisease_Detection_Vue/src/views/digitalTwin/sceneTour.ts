import * as THREE from 'three';
import { GREENHOUSE_LAYOUT as layout } from './greenhouseLayout';

export type TourState = { status: 'idle' | 'playing' | 'paused' | 'finished'; title: string; index: number; total: number };
const STOPS = [
	{ title: '全棚概览', position: [43, 28, 43], target: [0, 1.7, 0] },
	{ title: '供水与过滤泵阀', position: [layout.serviceEndX + 3, 2.4, 8], target: [layout.serviceEndX, 0.9, 5.25] },
	{ title: '番茄冠层与小区', position: [8.4, 2.3, 0.75], target: [0.5, 1.55, 0.75] },
	{ title: '冠层环境感知', position: [2.5, 3, -0.3], target: [0, 2.1, -2.2] },
	{ title: '通风与排风设备', position: [layout.exhaustEndX - 4, 3.2, layout.bayCenters[1] + 2.5], target: [layout.exhaustEndX, 2.45, layout.bayCenters[1]] },
] as const;

/** Tour only moves the camera; no device commands or data playback. */
export class SceneTour {
	private status: TourState['status'] = 'idle';
	private index = 0;
	private elapsed = 0;
	private from = new THREE.Vector3();
	private fromTarget = new THREE.Vector3();
	private to = new THREE.Vector3();
	private toTarget = new THREE.Vector3();
	private travelSeconds = 3.5;
	constructor(private camera: THREE.Camera, private target: THREE.Vector3) {}
	getState(): TourState { return { status: this.status, title: STOPS[this.index].title, index: this.index + 1, total: STOPS.length }; }
	start() { this.index = 0; this.status = 'playing'; this.enterStop(); }
	pause() { if (this.status === 'playing') this.status = 'paused'; }
	resume() { if (this.status === 'paused') this.status = 'playing'; }
	stop() { this.status = 'idle'; }
	private enterStop() {
		this.elapsed = 0;
		this.from.copy(this.camera.position);
		this.fromTarget.copy(this.target);
		const stop = STOPS[this.index];
		this.to.set(stop.position[0], stop.position[1], stop.position[2]);
		this.toTarget.set(stop.target[0], stop.target[1], stop.target[2]);
		this.travelSeconds = Math.max(2.4, Math.min(6, this.from.distanceTo(this.to) / 9));
	}
	update(dt: number) {
		if (this.status !== 'playing') return;
		this.elapsed += dt;
		const t = Math.min(1, this.elapsed / this.travelSeconds);
		const eased = t * t * (3 - 2 * t);
		this.camera.position.lerpVectors(this.from, this.to, eased);
		this.target.lerpVectors(this.fromTarget, this.toTarget, eased);
		if (this.elapsed >= this.travelSeconds + 3) {
			if (this.index === STOPS.length - 1) this.status = 'finished';
			else { this.index++; this.enterStop(); }
		}
	}
}
