# Tomato Greenhouse Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible tomato-greenhouse simulation, constrained device-control engine, and unified Vue operating experience.

**Architecture:** New InnoDB agent tables isolate simulation state from the legacy crop-disease schema. Spring Boot owns simulation ticks and rule decisions; Vue polls a single run-summary endpoint through Pinia and renders the same state across the command center and existing views.

**Tech Stack:** Java 8, Spring Boot 2.3, MyBatis-Plus, MySQL 8, Vue 3, TypeScript, Pinia, Element Plus, ECharts.

**Spec:** `docs/superpowers/specs/2026-09-21-tomato-greenhouse-agent-design.md`

## Global Constraints

- Never re-import or alter `cropdisease.sql`; the new migration is create-only.
- All new simulation data is marked `SIMULATED`, `MANUAL`, `LEGACY_HISTORY`, or `VISION_SIGNAL`.
- The decision engine is deterministic; DeepSeek only explains persisted results.
- Preserve legacy API contracts and avoid foreign keys to legacy MyISAM or duplicate-name tables.

---

### Task 1: Create Isolated Agent Persistence

**Files:**
- Create: `database/migrations/V20260921_01__agent_simulation.sql`
- Create: `.../entity/agent/*`
- Create: `.../mapper/agent/*`

**Interfaces:**
- Produces `AgentRun`, `AgentEnvironmentSnapshot`, `AgentDevice`,
  `AgentPolicyDecision`, `AgentDeviceAction`, `AgentResourceStock`,
  `AgentResourceLedger`, `AgentVisionEvent`, `AgentAlert`, and `AgentAuditLog`.
- `AgentRun` has `id: Long`, `runCode: String`, `status: String`,
  `stepNo: Integer`, `simulatedAt: LocalDateTime`, `baselineJson: String`.

- [ ] Add create-only InnoDB tables, unique command/tick indexes, and no legacy foreign keys.
- [ ] Add focused MyBatis-Plus entities and mappers using `Long`, `BigDecimal`, and `LocalDateTime`.
- [ ] Verify the SQL contains no `DROP TABLE`, `ALTER TABLE greenhouse`, or legacy foreign key.

### Task 2: Implement Deterministic Simulation and Rules

**Files:**
- Create: `.../service/agent/SimulationState.java`
- Create: `.../service/agent/TomatoSimulationEngine.java`
- Create: `.../service/agent/PolicyEngine.java`
- Create: `.../service/agent/DevicePolicy.java`
- Test: `.../src/test/java/.../service/agent/TomatoSimulationEngineTest.java`

**Interfaces:**
- `SimulationState advance(SimulationState state, Map<String, Boolean> devices)`.
- `PolicyResult decide(SimulationState state, List<AgentDevice> devices, ResourceBalance resources)`.
- `PolicyResult` contains rule decisions, requested device states, blocked reasons, and resource requirements.

- [ ] Write deterministic tests for identical seed inputs, high-temperature dry soil, humid low-light disease pressure, low CO2, and ventilation/CO2 conflict.
- [ ] Calculate VPD, normalized environmental pressure, disease-environment pressure, and candidate scores without random calls.
- [ ] Enforce manual lock, health state, cooldown, minimum run duration, resource availability, and device conflict rules.
- [ ] Run the unit tests with the Maven runtime once configured.

### Task 3: Add Transactional Run Lifecycle APIs

**Files:**
- Create: `.../dto/agent/*`
- Create: `.../service/agent/AgentRunService.java`
- Create: `.../controller/AgentController.java`
- Test: `.../src/test/java/.../controller/AgentControllerTest.java`

**Interfaces:**
- `POST /agent/runs`, `POST /agent/runs/{id}/start`, `pause`, `step`, `reset`, `replay`.
- `GET /agent/runs/active`, `GET /agent/runs/{id}/summary`, `GET /agent/runs/{id}/comparison`.
- `POST /agent/runs/{id}/devices/{code}/manual` and `POST /agent/runs/{id}/vision-events`.

- [ ] Bootstrap a run from the latest legacy tomato greenhouse record and create five virtual devices plus isolated resources.
- [ ] Use a single `@Transactional` tick that persists snapshot, policy decisions, actions, conditional resource deductions, ledgers, alerts, and audit entries.
- [ ] Implement idempotent action command ids and blocked action states for locks, faults, and resource shortages.
- [ ] Return the existing `Result<?>` envelope and preserve legacy controllers unchanged.

### Task 4: Add Vision and AI Context Safeguards

**Files:**
- Modify: `.../service/DeepSeekService.java`
- Create: `.../service/agent/VisionEventService.java`
- Modify: `.../controller/ImgRecordsController.java` only if an explicit export endpoint is needed

**Interfaces:**
- Vision import accepts a legacy image/video/camera record id and produces one normalized `AgentVisionEvent`.
- AI explanation receives persisted run summary, actions, alerts, and source labels; it never receives an action-writing tool.

- [ ] Parse legacy visual fields defensively and retain raw evidence/labels.
- [ ] Mark imported visual events `PENDING_REVIEW`; only elevate review risk, never create pesticide/device actions.
- [ ] Add a deterministic local explanation fallback when DeepSeek is unavailable.

### Task 5: Build the Command-Center Frontend

**Files:**
- Create: `.../src/api/agent/index.ts`
- Create: `.../src/stores/agentRun.ts`
- Create: `.../src/views/agentCenter/index.vue`
- Modify: `.../src/router/route.ts`

**Interfaces:**
- `useAgentRunStore()` exposes `summary`, `comparison`, `loading`, `refresh()`, `startRun()`, `pauseRun()`, `stepRun()`, `resetRun()`, and `setManualDevice()`.
- The route is `/agentCenter` and is available to the existing admin/test roles.

- [ ] Poll the active-run summary while the application is open and stop polling on store cleanup.
- [ ] Render run controls, provenance badges, state trend, baseline comparison, device grid, resource balance, rule reasons, and alerts using stable layouts.
- [ ] Expose manual locks and blocked reasons without hiding automatic-state evidence.
- [ ] Build the frontend and verify no text or control overlaps at desktop and mobile widths.

### Task 6: Link Existing Views to One Run Summary

**Files:**
- Modify: `.../views/homePage/index.vue`
- Modify: `.../views/detailsEnv/index.vue`
- Modify: `.../views/infoGreenhouse/index.vue`
- Modify: `.../views/imgRecord/index.vue`
- Modify: `.../views/videoRecord/index.vue`
- Modify: `.../views/storageManage/index.vue`
- Modify: `.../views/purchaseManage/index.vue`
- Modify: `.../views/dataView/index.vue`

- [ ] Add a compact source-labelled agent summary to each view using the shared store.
- [ ] Replace hard-coded detailed-environment values with active-run values when available, preserving a clear fallback when no run exists.
- [ ] Add visual-event import from recognition history and show simulated-resource balances beside legacy inventory data.
- [ ] Keep the legacy dashboard iframe/content and add a non-invasive current-run overlay.

### Task 7: Verify and Document the Release

**Files:**
- Modify: `README.md` or add `docs/tomato-greenhouse-agent.md`
- Test: backend service/controller tests and frontend production build

- [ ] Document migration order, simulation-source labels, model limitations, and how to run each preset scenario.
- [ ] Verify repeatability, manual lock, device fault, low resource, vision import, pause/replay, and legacy endpoint regression cases.
- [ ] Run Maven tests/package with a configured Maven binary and build Vue with the bundled Node runtime.
- [ ] Inspect `git status`, commit the implementation in logical units, and retain the existing baseline tag.
