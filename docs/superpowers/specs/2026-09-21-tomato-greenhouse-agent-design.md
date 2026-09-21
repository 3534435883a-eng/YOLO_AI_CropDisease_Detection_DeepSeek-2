# Tomato Greenhouse Agent Design

## Goal

Build a single-greenhouse tomato simulation and decision system that turns
structured scenario inputs into reproducible environment snapshots, device
actions, resource consumption, audit records, and an AI-readable explanation.
The system is an explicitly labelled simulation, not a live farm controller.

## Boundaries

- The existing `cropdisease` schema remains compatible. Legacy `greenhouse`,
  `storage`, `purchase`, `imgrecords`, `videorecords`, and `camerarecords`
  records are read-only inputs to the new flow.
- The initial scenario uses the most recent `8号温室` tomato record, with
  flowering/fruit-set as the default growth stage.
- The first release controls five virtual devices: irrigation, ventilation,
  supplemental light, shade curtain, and CO2 supply. It has no physical MQTT,
  serial, emergency-contact, fire-suppression, or pesticide execution.
- A visual-detection record is evidence only until labels and confidences are
  calibrated. It can increase a review risk but cannot trigger chemical action.

## Decision Model

Each simulation tick represents 15 virtual minutes. A run stores its baseline,
seed, model version, and parameter provenance. The model calculates indoor
temperature, air humidity, soil moisture, CO2, light, VPD, environment risk,
and disease-environment pressure.

The rule engine evaluates a fixed candidate set: hold state, irrigate,
ventilate, shade, add light, enrich CO2, and safe combinations. It selects a
valid action set lexicographically by hard-risk reduction, disease-pressure
reduction, water/energy usage, then action count. Constraints prevent CO2
enrichment while ventilation is active and prevent auto-control from
overriding a manually locked, offline, or faulty device.

The baseline path and automatic path use the same scenario, seed, and timeline.
Their risk, resource, and out-of-range duration are compared at every step.
An LLM may explain saved rule results, but it does not choose actions.

## Persistence

New InnoDB tables store run state, snapshots, devices, policy decisions,
device actions, virtual resources and immutable ledgers, normalized vision
events, alerts, and audit entries. A simulation run owns its virtual resource
stock, so it never decrements legacy equipment inventory. A single transaction
writes a tick, actions, resource consumption, and audit data. Unique command
identifiers make automatic retries idempotent.

## User Experience

`智能体指挥中心` is the main operating view. It starts, pauses, steps,
accelerates, resets, and replays the run; shows the automatic-versus-baseline
comparison; exposes devices and manual locks; and shows reasons, alerts, and
resource flow. A Pinia store polls one run summary, so the home page, detailed
environment page, greenhouse page, recognition history, storage/purchase views,
data dashboard, and AI chat all show the same run rather than separate mock
values.

## Acceptance Criteria

- A given baseline, seed, and model version yields the same snapshots and
  automatic actions across repeated runs.
- Device locks, faults, resource shortages, and conflict constraints create
  blocked actions and alerts without changing actual device state or balances.
- The UI always identifies simulation data and its source type.
- Legacy endpoints remain available, and a frontend production build succeeds.
