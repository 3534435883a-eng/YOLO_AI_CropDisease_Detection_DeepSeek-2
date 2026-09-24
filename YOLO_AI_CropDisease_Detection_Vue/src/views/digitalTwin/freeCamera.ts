import * as THREE from 'three';

const MOVEMENT_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE', 'ShiftLeft', 'ShiftRight']);

export class FreeCameraControls {
	private readonly keys = new Set<string>();
	private readonly angles = new THREE.Euler(0, 0, 0, 'YXZ');
	private readonly motion = new THREE.Vector3();
	private yaw = 0;
	private pitch = 0;
	private wasLocked = false;
	private dragging = false;
	enabled = false;

	constructor(
		private readonly camera: THREE.PerspectiveCamera,
		private readonly canvas: HTMLCanvasElement,
		private readonly onExit: () => void
	) {
		window.addEventListener('keydown', this.onKeyDown);
		window.addEventListener('keyup', this.onKeyUp);
		window.addEventListener('blur', this.clearKeys);
		document.addEventListener('visibilitychange', this.onVisibility);
		document.addEventListener('pointerlockchange', this.onPointerLockChange);
		document.addEventListener('mousemove', this.onMouseMove);
		canvas.addEventListener('pointerdown', this.onPointerDown);
		window.addEventListener('pointerup', this.onPointerUp);
		canvas.addEventListener('click', this.onCanvasClick);
	}

	activate() {
		this.enabled = true;
		this.keys.clear();
		this.syncOrientation();
		this.canvas.focus();
		this.requestLock();
	}

	syncOrientation() {
		this.angles.setFromQuaternion(this.camera.quaternion, 'YXZ');
		this.yaw = this.angles.y;
		this.pitch = this.angles.x;
	}

	deactivate() {
		this.enabled = false;
		this.keys.clear();
		this.dragging = false;
		this.wasLocked = false;
		if (document.pointerLockElement === this.canvas) document.exitPointerLock();
	}

	update(deltaSeconds: number) {
		if (!this.enabled || !this.hasFocus() || document.hidden || !this.keys.size) return;
		const forward = Number(this.keys.has('KeyW')) - Number(this.keys.has('KeyS'));
		const sideways = Number(this.keys.has('KeyD')) - Number(this.keys.has('KeyA'));
		const vertical = Number(this.keys.has('KeyE')) - Number(this.keys.has('KeyQ'));
		this.motion.set(
			Math.sin(this.yaw) * forward + Math.cos(this.yaw) * sideways,
			vertical,
			-Math.cos(this.yaw) * forward + Math.sin(this.yaw) * sideways
		);
		if (this.motion.lengthSq() === 0) return;
		const speed = this.keys.has('ShiftLeft') || this.keys.has('ShiftRight') ? 14 : 5;
		this.camera.position.addScaledVector(this.motion.normalize(), speed * Math.min(deltaSeconds, 0.05));
	}

	dispose() {
		this.deactivate();
		window.removeEventListener('keydown', this.onKeyDown);
		window.removeEventListener('keyup', this.onKeyUp);
		window.removeEventListener('blur', this.clearKeys);
		document.removeEventListener('visibilitychange', this.onVisibility);
		document.removeEventListener('pointerlockchange', this.onPointerLockChange);
		document.removeEventListener('mousemove', this.onMouseMove);
		this.canvas.removeEventListener('pointerdown', this.onPointerDown);
		window.removeEventListener('pointerup', this.onPointerUp);
		this.canvas.removeEventListener('click', this.onCanvasClick);
	}

	private hasFocus() {
		return document.pointerLockElement === this.canvas || document.activeElement === this.canvas;
	}

	private requestLock() {
		if (this.enabled && document.pointerLockElement !== this.canvas) {
			try {
				const attempt = this.canvas.requestPointerLock?.();
				if (attempt && typeof attempt.catch === 'function') void attempt.catch(() => {});
			} catch {
				this.canvas.focus();
			}
		}
	}

	private onCanvasClick = () => this.requestLock();
	private onPointerDown = (event: PointerEvent) => {
		if (!this.enabled || event.button !== 0) return;
		this.canvas.focus();
		this.dragging = true;
	};
	private onPointerUp = () => { this.dragging = false; };
	private clearKeys = () => {
		this.keys.clear();
		this.dragging = false;
	};
	private onVisibility = () => {
		if (document.hidden) this.clearKeys();
	};
	private onKeyDown = (event: KeyboardEvent) => {
		if (!this.enabled || !this.hasFocus() || !MOVEMENT_KEYS.has(event.code)) return;
		if (event.repeat) return;
		event.preventDefault();
		this.keys.add(event.code);
	};
	private onKeyUp = (event: KeyboardEvent) => {
		if (!this.keys.delete(event.code)) return;
		event.preventDefault();
	};
	private onPointerLockChange = () => {
		const locked = document.pointerLockElement === this.canvas;
		this.clearKeys();
		if (this.enabled && this.wasLocked && !locked) this.onExit();
		this.wasLocked = locked;
	};
	private onMouseMove = (event: MouseEvent) => {
		if (!this.enabled || (document.pointerLockElement !== this.canvas && !this.dragging)) return;
		this.yaw -= event.movementX * 0.002;
		this.pitch = THREE.MathUtils.clamp(this.pitch - event.movementY * 0.002, -Math.PI / 2 + 0.01, Math.PI / 2 - 0.01);
		this.camera.quaternion.setFromEuler(this.angles.set(this.pitch, this.yaw, 0, 'YXZ'));
	};
}
