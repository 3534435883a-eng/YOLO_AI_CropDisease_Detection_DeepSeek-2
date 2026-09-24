# Blender greenhouse rebuild implementation plan

**Goal:** Replace the visibly simplistic greenhouse frame and equipment with editable, meter-scale Blender assets while preserving the existing free camera and simulation data flow.

**Architecture:** Generate two modular GLBs from one Blender source scene. The structure asset replaces the legacy frame while the equipment asset replaces the legacy inspection meshes using stable `deviceCode` groups and named moving parts. Existing roof film, dynamic tomato canopy, simulation effects, and local Three.js fallback remain until their own replacements pass visual review.

**Tech stack:** Blender 5.2.2 LTS on E:, glTF/GLB, Three.js 0.186, Vue 3, Vite.

**Spec:** `docs/superpowers/specs/2026-09-23-greenhouse-digital-twin-redesign.md`

## Global constraints

- Keep the original scene archive at `E:\agent 农业\_greenhouse_model_archive\greenhouse-threejs-before-blender-2026-09-24.zip` untouched.
- Install tools and store authored assets on E:.
- Treat 26 x 13 m and four tomato beds as simulated design assumptions, not surveyed measurements or certified construction drawings.
- Preserve unrestricted fly navigation, device inspection, 15-minute agent state playback, and separate daily evaluation mode.
- Never silently substitute invented readings for live measurements.

## Tasks

1. Verify Blender and E:-local addon setup. Record Blender version and addon load result.
2. Author the greenhouse Blender scene: structural frame, gutters, doors, crop support, service zones, irrigation, LEDs, shade drive, ventilation, fans, CO2, cooling pads, and sensors. Keep component names and pivots stable.
3. Export structure and equipment GLBs and validate node names, bounds, material count, triangle count, and code coverage.
4. Load assets asynchronously in Three.js. Keep procedural geometry visible until both assets load successfully; bind imported device groups and moving parts to the existing deterministic state updates.
5. Run frontend build and inspect desktop/mobile screenshots from exterior, aisle, roof, and near-equipment viewpoints. Repair visual/interaction failures before delivery.

## Acceptance

- All device codes in the current equipment registry have a corresponding selectable asset group.
- Fan rotors, roof panels, wet surfaces, LEDs, and status lights respond to the same frame state as their inspection metadata.
- The camera can still move freely between any interior and exterior points, without a mandatory preset.
- GLB load failures leave a functional legacy scene and report the failure in the console.
- The authored `.blend`, exported GLBs, and visual review images are retained on E:.
