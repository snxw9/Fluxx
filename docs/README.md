# Fluxx documentation

Start with the [roadmap](architecture/FLUXX_ROADMAP.md) for active scope. The [Phase 2 status matrix](checkpoints/PHASE_2_STATUS_AND_BACKEND.md) distinguishes implemented controls from placeholders. Feature references below own their detailed behavior and verification evidence.

**Current state (2026-11-22):** Phase 2 is active. Text E1–E3, F1/F2 and T1–T4 are user-tested on the A16; FIX and EXPLODE are committed; T5 (Arabic/Hebrew RTL) is stopped pending a cross-style shaping decision ([report](checkpoints/TEXT_FIX_EXPLODE_T5.md)). The Edit Text UI rework through TEXT UI 8 is implemented in source, device-unverified ([overhaul record](checkpoints/TEXT_UI_OVERHAUL.md)). TEXT FIX 9 and TEXT PERF 10/11 were device-assisted on the A16 and record measured results and open items ([PERF 11 still open](checkpoints/TEXT_PERF_11.md#still-open)). F3 animator UI and Phase 3 effects/masks are not started. Separate 16 KB/golden checks remain outstanding.

*Historical (2026-10-01):* E1b/E1c accepted; E1d lazy 2048 R8 rendering and E1e debug visual/lifecycle/resource harness implemented in source, unverified at the time. E2/E3 then followed the [approved array-overflow design](checkpoints/TEXT_LAYER.md#e2-implementation-and-approved-array-overflow).

## Architecture and scope

| Document | Owns |
| --- | --- |
| [Roadmap](architecture/FLUXX_ROADMAP.md) | Active phase, build order and gates |
| [SDD](architecture/FLUXX_SDD.md) | Full target architecture; not a shipped-feature inventory |
| [AE parity vision](architecture/AE_PARITY_VISION.md) | Long-term behavior, deferred features and mobile design principles |
| [Phase 2 status/backend](checkpoints/PHASE_2_STATUS_AND_BACKEND.md) | Current engine boundaries, control matrix and gesture protocol |

## Feature references

| Document | Owns |
| --- | --- |
| [Home and projects](checkpoints/HOME_AND_PROJECTS.md) | Navigation, project cards, creation and presets |
| [Timeline and preview](checkpoints/TIMELINE_LAYOUT.md) | Ruler/track/inspector geometry, duration/timecodes, gestures/reordering, preview quality/zoom, toolbar and resize evidence |
| [Element inspector](checkpoints/ELEMENT_INSPECTOR.md) | Inspector navigation, transforms/anchor, layer actions, trim/extend/split |
| [Global colour picker](checkpoints/GLOBAL_COLOUR_PICKER.md) | Shared picker, palettes and eyedropper ownership |
| [Keyframe animation](checkpoints/KEYFRAME_ANIMATION.md) | Transform evaluator, tracks, editing and interpolation contracts |
| [Markers](checkpoints/MARKERS.md) | Scope, creation/deletion, navigation, persistence and hit routing |
| [Media browser](checkpoints/MEDIA_BROWSER.md) | MediaStore browsing, selection, import and read-only audio |
| [Autosave](checkpoints/AUTOSAVE.md) | Save queue, lifecycle, atomicity and recovery limits |
| [Export audit](audits/EXPORT_AUDIT.md) | Historical failures, ownership lessons and export regression checks |

## Text references

Listed in build order. The text checkpoint is the main owner; the others record one stage or pass each.

| Document | Owns |
| --- | --- |
| [Text checkpoint](checkpoints/TEXT_LAYER.md) | E1–E3 and T1–T4 implementation, limits, build/parity/PNG results and user-run lists |
| [Text implementation plan](checkpoints/TEXT_LAYER_IMPLEMENTATION_PLAN.md) | E1 gates and E2/E3 property/schema/editor decisions |
| [F1 property completion](checkpoints/TEXT_F1_IMPLEMENTATION.md) | Tracking/leading/baseline/stroke properties, v10, spread spike and F1 acceptance |
| [Text animation F1–F3 plan](checkpoints/TEXT_ANIMATION_F1_F3_PLAN.md) | F2 selector/animator engine, v11, F2 evidence and the F3 animator UI outline |
| [Text completion spec](architecture/TEXT_COMPLETION_SPEC.md) | Verbatim T1–T5 specification |
| [FIX / EXPLODE / T5](checkpoints/TEXT_FIX_EXPLODE_T5.md) | Case/script/selection fixes, Explode Text, and the T5 stop evidence |
| [Variable-instance fix](checkpoints/TEXT_VARIABLE_INSTANCE_FIX.md) | Named-instance style arrows correction |
| [Text UI improvements proposal](checkpoints/TEXT_UI_IMPROVEMENTS_PROPOSAL.md) | Original eight-item UI proposal (superseded by the overhaul) |
| [Edit Text UI plan](architecture/TEXT_UI_OVERHAUL_PLAN.md) | Approved Edit Text surface and final old-to-new control inventory |
| [Edit Text UI overhaul](checkpoints/TEXT_UI_OVERHAUL.md) | Overhaul commits, TEXT UI 5–8 changes, limits, deviations and A16 lists |
| [TEXT FIX 9](checkpoints/TEXT_FIX_9.md) | Font import regression, overlap-safe SDF, sharp tiers and device suites |
| [TEXT PERF 10](checkpoints/TEXT_PERF_10.md) | Single-layer text frame-time profile and fixes |
| [TEXT PERF 11](checkpoints/TEXT_PERF_11.md) | Multi-layer text cost, resume/rotation fixes and imported-font latency |

[Design assets](assets/) contain UI references. Vendored dependency documentation/licenses belong to their upstream packages, not this product index.

## Maintenance rules

- Update the existing feature owner for a correction or follow-up. Do not create a new revision/handoff file for the same feature.
- Keep the roadmap and status matrix concise; link to details rather than copying their contracts or checklists.
- Distinguish implemented source, actual build/test results, user-confirmed phone behavior and planned work. A smoke test is not full feature acceptance.
- Preserve meaningful failure evidence and outstanding checks when consolidating; remove superseded current-state claims.
- Keep generated logs, PNG dumps, downloaded archives and build outputs in ignored result/build directories. Retain checked-in test references, source provenance and required licenses.
- Follow [workspace rules](../AGENTS.md): builds or device execution need explicit user authorization.
