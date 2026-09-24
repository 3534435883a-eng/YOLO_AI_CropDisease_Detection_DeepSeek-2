# Greenhouse Digital Twin Stage A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current fixed-orbit demonstration with a believable double-span greenhouse that supports unrestricted indoor/outdoor camera movement and close inspection of its complete equipment layout.

**Architecture:** Keep the Vue 3 + Three.js page and isolate free-flight controls, physical layout metadata, and equipment geometry from the large scene class. The scene class owns rendering and applies explicitly simulated frame state; the page owns user controls and presentation. The 15-minute backend expansion and AI integration are separate follow-up plans after this model is visually accepted.

**Tech Stack:** Vue 3, TypeScript 4.9, Three.js 0.186, Vite, existing Spring Boot data contract unchanged in Stage A.

**Spec:** `docs/superpowers/specs/2026-09-23-greenhouse-digital-twin-redesign.md`

## Global Constraints

- Preserve the existing simulation label and historical evaluation page until a real AgentRun adapter exists; do not present daily `/eval/{batchId}/series` as 15-minute device telemetry.
- Default scene dimensions (26 m × 13 m) and 104 tomato plants are configurable demonstration geometry, not measured site dimensions.
- A camera can reach any indoor/outdoor position and turn in any direction; shell visibility modes and optional presets cannot restrict it.
- PBR, consistent metric scale, independent actuator geometry, meaningful sensors, adaptive render quality, and disposal of listeners/resources are required.
- Do not modify legacy SQL or connect real hardware; do not commit or create a branch without a new explicit request.

## File Map

- `src/views/digitalTwin/freeCamera.ts`: pointer-lock free-flight input, camera motion and orbit/free-flight handoff.
- `src/views/digitalTwin/greenhouseLayout.ts`: dimensions, planting coordinates, named device/sensor mounting points and stable IDs.
- `src/views/digitalTwin/equipment.ts`: meshes for pad/fan/HAF, hydraulic/fertigation head, drip zones, shading, CO₂ and sensor hardware, plus state animation handles.
- `src/views/digitalTwin/scene.ts`: renderer, PBR greenhouse shell, scene lifecycle, interaction raycast, integration with modules.
- `src/views/digitalTwin/index.vue`: explicit simulation source, free-flight HUD and inspection panel; preserve existing evaluation analysis until Stage B replaces its scene feed.

---

### Task 1: Unrestricted Free-Flight Camera

**Files:** Create `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/freeCamera.ts`; modify `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/scene.ts` and `index.vue`.

**Interfaces:** `FreeCameraControls(camera, canvas, onExit)` exposes `enabled`, `update(deltaSeconds)`, `activate()`, `deactivate()` and `dispose()`. Scene exposes `setNavigationMode('orbit' | 'fly')` and `getNavigationMode()`.

- [ ] Add free-flight controls that read W/A/S/D, Q/E and Shift, apply frame-time-scaled camera-local movement, rotate from pointer movement, ignore events while text input is focused, and clear held keys on blur/visibility loss.
- [ ] Replace the hard polar and orbit distance limits; keep OrbitControls for optional inspection, transferring orientation/position without a teleport when switching mode.
- [ ] Make any preset stop auto-rotation immediately; a preset jump remains optional and never constrains subsequent movement.
- [ ] Expose fly/orbit toggle plus on-screen key guide and Esc exit state in the twin page.
- [ ] Run `npm run build` in the Vue directory; manually test outside → inside → roof → device close-up, then blur/re-enter to ensure no stuck motion.

### Task 2: Coherent Double-Span Shell and PBR

**Files:** Create `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/greenhouseLayout.ts`; modify `scene.ts` and `plants.ts` only where geometry alignment requires it.

**Interfaces:** Export `GREENHOUSE_LAYOUT` with length, width, two bay centers, center gutter, rows, corridors and key mounting coordinates. Scene exposes `setShellMode('solid' | 'translucent' | 'cutaway')`.

- [ ] Replace the single width-spanning arch with two separate half-width arches and central gutter, duplicate structural ribs at the configured X positions, retain end-door access and align the four planting beds with the inner walkways.
- [ ] Give film, galvanized frame, floor, pipe and soil physically distinct materials and appropriate shadow/transparency behavior; keep dimensions in one layout module.
- [ ] Group covers/walls/end panels into named layers, so solid/translucent/cutaway affects only shell appearance, not the camera or inner hardware.
- [ ] Verify top, end, side, and walk-in views do not show floating supports, missing roof seams or opaque film blocking close equipment inspection; run a Vue build.

### Task 3: Full Equipment and Sensor Layout

**Files:** Create `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/equipment.ts`; modify `scene.ts`.

**Interfaces:** `buildEquipment(scene, layout)` returns a registry of `{ deviceCode, zone, object, label, description, status }` plus `updateEquipment(frame, deltaSeconds)` and `disposeEquipment()`. Each raycastable mesh carries its stable device code in `userData`.

- [ ] Place irrigation head/filter/pump/valves at the service end, main/branch lines and drip laterals next to each bed, with root-zone probes and flow meter.
- [ ] Build individually readable grow-light rows, roof vents, shade tracks, HAF fans, CO₂ tank/valve/distribution, wet-pad intake, water loop and opposing exhaust fan bank; distinguish circulation from extraction.
- [ ] Mount representative outdoor and canopy temperature/humidity, CO₂ and PPFD probes away from localized heat and air inlets; expose simulated units and device provenance.
- [ ] Give each actuator a visible off/on or continuous animation channel and test independent combinations, rather than one room-wide decorative effect.

### Task 4: Inspectable Simulation UI and Quality Review

**Files:** Modify `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/index.vue`, `scene.ts`, and `docs/tomato-greenhouse-agent.md` if user-facing behavior changes.

**Interfaces:** `scene.onInspect((entry) => void)` sends clicked equipment/sensor metadata; `scene.setQuality('high' | 'medium' | 'low')` changes render cost, not equipment availability.

- [ ] Raycast named device/sensor meshes; display readable device name, zone, state/value, units and `SIMULATED` source. Keep a visible difference between local demonstration state and a persisted AgentRun snapshot.
- [ ] Keep quality/performance readout and add a manual quality selector; verify disposal of control listeners, textures, materials, composer and animation on route unmount.
- [ ] Build the frontend and inspect actual browser renders at day/night and shell solid/translucent/cutaway, inside/outside, with at least one close-up for each equipment system.
- [ ] Record remaining modeling compromises for Stage B without misrepresenting demo animation as back-end-authoritative telemetry.

## Stage B/C Handoff

After visual acceptance, plan B extends `/agent/runs` to independently simulated devices and exposes 15-minute snapshots; plan C connects AI evidence/explanation to those saved IDs. Neither stage should be silently folded into the daily evaluation chart.
