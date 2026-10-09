# Connected Greenhouse Implementation Plan

> **For agentic workers:** Use subagent-driven development for the independent model, task service, and shared frontend areas; the primary agent integrates the server clock and greenhouse UI. Track steps with checkboxes. Higher-priority session instructions prohibit adding/running tests without a user request; use static review and production compilation here.

**Goal:** Complete a continuous agricultural decision workflow in the existing greenhouse interface while preserving the project name and independent Q&A.

**Architecture:** Reuse the M3 live run as the shared computational context. Keep agronomy response, persistent farm tasks, the server clock, and the frontend subscription store as focused units. Connect evidence, assistant output, action receipts and reports using task/run/request ids.

**Tech Stack:** Java 8-compatible Spring Boot, Jackson, Vue 3, TypeScript, Pinia, Element Plus, ECharts and existing Three.js scene.

**Spec:** `docs/superpowers/specs/2026-10-08-connected-greenhouse-design.md`.

## Global Constraints

- Project name and existing visual direction remain unchanged.
- Keep ordinary Q&A independent; only greenhouse-linked requests include simulationRunId/taskId.
- Historical replay, estimated agronomy state, simulated event/action and human observation retain separate source labels.
- No video production or GitHub upload in this task.
- Initial implementation used compilation only. The subsequent user request "验证流程，完善参数" authorizes focused tests, workflow verification and parameter improvements under this approved design.
- Preserve existing action idempotency, scenario version checks, fault constraints and prediction/observation separation.
- No new hosted service or third-party library.

## Task 1: Agronomy response in the existing scenario

**Files:** Create `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/m3/scenario/ScenarioAgronomyModel.java`; modify `ScenarioSession.java` and `agent/crop/TomatoCropGrowthModel.java`.

**Interfaces:** Scenario snapshots add `agronomy`, `shadowAgronomy`, `resources`, and `effects`. Existing environment/devices/decision fields remain compatible. Each agronomy snapshot includes soilMoistureVwcPct, waterStressFactor, waterUsedL, drainageL, evapotranspirationL, wetExposureMinutes, diseaseConditions and parameter/source notes.

- [x] Implement deterministic root-zone water balance with units explicit in constants and snapshot:

```java
double addedL = irrigationLitresPerMinute * duty * minutes;
double availableL = rootVolumeLitres * theta + addedL;
double drainedL = Math.max(0, availableL - rootVolumeLitres * fieldCapacity);
double nextL = Math.max(0, availableL - drainedL - evapotranspirationL);
double nextTheta = nextL / rootVolumeLitres;
```

- [x] Advance treated and untreated agronomy from the same initial conditions, using the same weather/time. Do not update these estimates with unrelated historical root observations.
- [x] Feed root water stress into the crop response and expose cumulative disease-condition risk separately from visual diagnosis. Add circulation mixing/wetness proxy with explicit assumption, and resource accounting from the same actuator duty/time.
- [x] Add action effect descriptions with state changes, potential side effects and review signals; preserve unknown/blocked actions and do not assert automatic disease cure.
- [x] Check units, clipping, finite values, step scaling, branch equality with no intervention, and that M3 forecast metrics are not overwritten by scenario effects.

## Task 2: Server clock and resumable runs

**Files:** Modify `agent/m3/M3LiveService.java`, `M3LiveController.java`; create `agent/m3/M3RunJournal.java`.

**Interfaces:** Add `POST /m3/live/{id}/playback` accepting `{playing:boolean,stepCount:1|12|48}`. Snapshot adds `playback:{playing,stepCount,waitingForAi,restored}`. `current`, `step` and scenario endpoints remain compatible.

- [x] Add a single scheduled executor owned by the service, ticking active runs once per wall second; serialize mutation on the run and reuse existing advancement/AI guard.
- [x] Explicit pause sets playing=false; frontend unmount only unsubscribes. Manual step while auto-playing returns a clear state error.
- [x] Persist a complete atomic checkpoint of consumed frames, filters, risk exposure, crop, scenario actions and explicit random state. Rebuild with frozen parameters and restore the checkpoint without calling AI, verify input hash/version, and restore into paused state; unresolved AI returns interrupted status.
- [x] Make writes atomic and reject invalid UUIDs/path traversal. Release finished executors on service shutdown and retain archived run files.
- [x] Surface restore/persistence failures without silently creating a different run.

## Task 3: Persistent farm task and report

**Files:** Create `agent/task/FarmTaskService.java`, `FarmTaskController.java`; modify `agent/dto/AgriPlanRequest.java`, `agent/plan/PlanDeductionService.java`, and the chat request/orchestrator integration as discovered in the current tree.

**Interfaces:**

```text
POST /agent/tasks                    -> FarmTask
GET /agent/tasks/{id}                -> FarmTask
PATCH /agent/tasks/{id}              -> FarmTask
POST /agent/tasks/{id}/evidence      -> FarmTask
POST /agent/tasks/{id}/turns         -> FarmTask
POST /agent/tasks/{id}/observations  -> FarmTask
POST /agent/tasks/{id}/actions       -> FarmTask
PATCH /agent/tasks/{id}/actions/{key}-> FarmTask
GET /agent/tasks/{id}/report         -> text/markdown attachment
```

`FarmTask` includes id/title/crop/question/simulationRunId/createdAt/updatedAt/evidence/turns/actions/observations. Evidence retains type, label, source, imageUrl and candidate details; actions use ids/status/reviewCondition and distinguish HUMAN from SIMULATION. Link plan requests with taskId/simulationRunId and build context from saved facts plus current M3 snapshot.

- [x] Implement bounded file persistence using UUID paths and atomic replacement in the existing project data directory.
- [x] Save user inputs, assistant turns, sources and explicit action completion; empty/unavailable run links return a recoverable message rather than facts from the legacy engine.
- [x] Link chat and plan inputs to saved task facts; isolate greenhouse sessions per run/task. Keep general Q&A free of implicit current-run context.
- [x] Produce a task report from stored evidence/turns/manual actions/current scenario receipts, feedback and references; include source/assumption boundary and no invented effectiveness percentage.
- [x] Review endpoint envelopes, path validation, response shapes, task-id length and evidence payload bounds.

## Task 4: Shared frontend subscriptions and cross-module context

**Files:** Create `src/stores/greenhouse.ts`, `src/api/agent/tasks.ts`, `src/components/GreenhouseContext.vue` under `YOLO_AI_CropDisease_Detection_Vue`; modify `src/api/m3/live.ts`, `src/api/agent/chat.ts`, `src/api/agent/plan.ts`, `src/views/modelCalibration/M3LiveWorkbench.vue`, `src/views/agentChat/index.vue`, `src/views/agentSimulation/index.vue`, `src/views/homePage/index.vue`, `src/views/detailsEnv/index.vue`, `src/views/imgPredict/index.vue`, `src/views/referenceLibrary/index.vue`, `src/views/videoPredict/index.vue`, and `src/layout/navBars/index.vue`.

**Interfaces:** `useGreenhouseStore()` exposes run/task/busy/error, `attachRun(id)`, `acceptRun(run,replace?)`, `start(year)`, `setPlayback(playing,stepCount?)`, `subscribe()` returning unsubscribe, `ensureTask(question?)`, `refreshTask()`, `addEvidence(input)`, `addTurn(input)`, `addAction(input)`, `setActionStatus(id,status)`, and `linkedQuery()` returning `{mode:'m3',liveRun,taskId}`.

- [x] Use one store-managed poll per active run, reject stale id/cursor/version responses, merge frames/events without duplication, and persist only active ids in browser storage.
- [x] Convert M3LiveWorkbench to server playback controls; remove client stepping timer and pause-on-unmount.
- [x] Home/environment/navigation/resource summaries read the selected M3 context; ordinary chat has its own stable scope. Bind greenhouse session ids to task/run and pass ids to plans.
- [x] Image-to-assistant transfers original image/candidate metadata into a saved task; attach the task to routes. Restore plan input from task evidence and persist human action completion.
- [x] Correct stale M3 library text/default route and provide a finite default video detection threshold.
- [x] Add concise shared context strip with crop/time/source and links carrying the same task/run ids.

## Task 5: Greenhouse workflow and visible effects

**Files:** Create `src/views/digitalTwin/components/FarmTaskWorkbench.vue`, `AgronomyEffects.vue`; modify `src/views/digitalTwin/index.vue`, `components/ScenarioPanel.vue`, `components/AutonomousShowcase.vue`, and Three.js scene state updates only where needed.

**Interfaces:** Components receive the current shared run and task; emit accepted snapshots, focus device, and open linked planning/reference panels. `AgronomyEffects` reads agronomy/shadowAgronomy/resources/effects supplied by Task 1.

- [x] Preserve the central scene; add a collapsible task/inputs pane and assistant/plan pane with simple action/result/review wording.
- [x] Provide text input, image evidence navigation, CSV parsing using existing plan API, linked conversation and report download in the same workbench.
- [x] Display root-zone cross section, water budget, climate/condition-risk comparison and action costs. Use text/icons with color; show estimated/simulated labels and model clock.
- [x] Persist assistant answers and manual review actions through Task 3; virtual action status comes from the scenario receipt. Show loading, interrupted AI, unresolved risk and restore error states.
- [x] Scene device effects follow current scenario duties; do not run a second heavy 3D scene. Keep camera and rendering loop independent of model polling.
- [x] Retain the existing automatic takeover and calibration presentation and expose the task workbench during takeover.

## Task 6: Compilation, integration and delivery

**Files:** Project completion note `docs/research/2026-10-08-connected-greenhouse-delivery.md`; update implementation and progress checkboxes.

- [x] Review all interfaces together and fix unresolved imports, stale calls, task/report source mismatches and duplicated advancement.
- [x] Run Vue production build (`npm run build`) with the installed Node runtime; run Java compilation with tests skipped (`mvn -DskipTests compile` or the project's existing javac build helper). Build is compilation, not a functional test run.
- [x] Use existing startup/deployment helpers to publish compiled local assets and restart only this application's services if necessary; preserve desktop shortcuts and existing data.
- [x] Document implemented mechanisms, parameter assumptions, current source labels and remaining validation limits. Do not claim unmeasured performance or agronomic success.
- [x] Show the updated project in the browser if the local service is available. Deliver changed areas and build results without producing video.
