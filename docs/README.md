# Fluxx documentation

Start with the [roadmap](architecture/FLUXX_ROADMAP.md) for active scope. The [Phase 2 status matrix](checkpoints/PHASE_2_STATUS_AND_BACKEND.md) distinguishes implemented controls from placeholders. Feature references below own their detailed behavior and verification evidence.

**Current state (2026-11-22):** Phase 2 is active. **The text phase is complete**; the graph editor is in progress, with G1 (curve backend + Ease view, schema v13) implemented in source and awaiting phone acceptance ([record](checkpoints/GRAPH_G1.md)). Every text plan, spec and stage record is consolidated in the [text record](checkpoints/TEXT.md): E1–E3, F1/F2, T1–T4 and the Edit Text UI were user-tested; FIX and EXPLODE are committed; T5 (Arabic/Hebrew RTL) is stopped pending a cross-style shaping decision; TEXT FIX 9 and PERF 10/11 were device-assisted A16 passes; F3 (the Animate tab) and F3.1–F3.3 are merged to master (`c8109d9`, `67df5f3`) with **phone acceptance of F3.1–F3.3 done**; F3.4 (the Character Tracking cap and the docs consolidation) is merged from the single `text` branch. Open items carried forward are listed at the top of the text record. Phase 3 effects/masks are not started.

*Historical (2026-10-01):* E1b/E1c accepted; E1d lazy 2048 R8 rendering and E1e debug visual/lifecycle/resource harness implemented in source, unverified at the time. E2/E3 then followed the [approved array-overflow design](checkpoints/TEXT.md#e2-implementation-and-approved-array-overflow).

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
| [Graph editor G1.1](checkpoints/GRAPH_G1_1.md) | Graph page layout rework (header row, rails, touchpad + values bar, XY pad), the playhead investigation, verification and the A16 test list |
| [Graph editor G1](checkpoints/GRAPH_G1.md) | Easing model (schema v13: Hold, Custom bezier, Bounce, Elastic), evaluator and overshoot clamps, `SetKeyEasing`, the Graph page's Ease view, verification and the A16 test list |
| [Markers](checkpoints/MARKERS.md) | Scope, creation/deletion, navigation, persistence and hit routing |
| [Media browser](checkpoints/MEDIA_BROWSER.md) | MediaStore browsing, selection, import and read-only audio |
| [Autosave](checkpoints/AUTOSAVE.md) | Save queue, lifecycle, atomicity and recovery limits |
| [Export audit](audits/EXPORT_AUDIT.md) | Historical failures, ownership lessons and export regression checks |

## Text references

| Document | Owns |
| --- | --- |
| [Text record](checkpoints/TEXT.md) | The whole text phase (complete): status, what was built, open items carried forward, and every plan, spec and stage record in build order — E plan and E1–E3/T1–T4 checkpoint, F1, the F1–F3 animation plan, the T1–T5 spec, FIX/EXPLODE/T5, the variable-instance fix, the Edit Text UI proposal, plan and overhaul (TEXT UI 5–8), TEXT FIX 9, PERF 10/11, and F3–F3.4 |

[Design assets](assets/) contain UI references. Vendored dependency documentation/licenses belong to their upstream packages, not this product index.

## Maintenance rules

- Update the existing feature owner for a correction or follow-up. Do not create a new revision/handoff file for the same feature.
- Keep the roadmap and status matrix concise; link to details rather than copying their contracts or checklists.
- Distinguish implemented source, actual build/test results, user-confirmed phone behavior and planned work. A smoke test is not full feature acceptance.
- Preserve meaningful failure evidence and outstanding checks when consolidating; remove superseded current-state claims.
- Keep generated logs, PNG dumps, downloaded archives and build outputs in ignored result/build directories. Retain checked-in test references, source provenance and required licenses.
- Follow [workspace rules](../AGENTS.md): builds or device execution need explicit user authorization.
