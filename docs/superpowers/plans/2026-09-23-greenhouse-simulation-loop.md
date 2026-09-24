# Greenhouse Simulation Loop Implementation Plan

**Goal:** Persist 15-minute environment, actuator, and resource states for a replayable 3D twin without mixing daily evaluation data.

**Architecture:** Policy proposes actions; the service validates dependencies, health, locks, and stock in a transaction. Historical reads reconstruct device states from saved actions, not current device rows. Vue explicitly selects AgentRun or daily evaluation.

**Spec:** `docs/superpowers/specs/2026-09-23-greenhouse-digital-twin-redesign.md`

## Tasks

- [x] Extend device codes, policy, physical effects, and consumption for HAF, exhaust, roof vents and evaporative pads.
- [x] Enforce real-state interlocks and add deterministic tests for blocked and permitted actions.
- [x] Expose per-step snapshots with saved device states and resource consumption; test replay and reset semantics.
- [x] Wire 3D playback and controls to AgentRun snapshots, preserving daily evaluation and source labels.
- [x] Run focused tests and build; document parameters and remaining limitations. Live database/API and browser validation remain unverified because the backend is not running.
