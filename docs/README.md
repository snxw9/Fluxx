# Fluxx documentation

Start with the [roadmap](architecture/FLUXX_ROADMAP.md) for active scope. The [Phase 2 status matrix](checkpoints/PHASE_2_STATUS_AND_BACKEND.md) distinguishes implemented controls from placeholders. Feature references below own their detailed behavior and verification evidence.

Current text work: E1b/E1c accepted; E1d lazy 2048 R8 rendering and E1e debug visual/lifecycle/resource harness implemented in source, unverified. No builds/tests were run for this pass. E2/E3 are unimplemented pending the [production overflow design decision](checkpoints/TEXT_LAYER.md#stop-before-e2--production-overflow-design-decision). Earlier A16 smoke and CPU compilation evidence does not verify new GPU text. Outstanding 16 KB/golden checks remain.

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
| [Text checkpoint](checkpoints/TEXT_LAYER.md) | Implemented E1 work, build/parity/PNG results and pending acceptance |
| [Text implementation plan](checkpoints/TEXT_LAYER_IMPLEMENTATION_PLAN.md) | Remaining E1 gates and E2/E3 property/schema/editor decisions |
| [Export audit](audits/EXPORT_AUDIT.md) | Historical failures, ownership lessons and export regression checks |

[Design assets](assets/) contain UI references. Vendored dependency documentation/licenses belong to their upstream packages, not this product index.

## Maintenance rules

- Update the existing feature owner for a correction or follow-up. Do not create a new revision/handoff file for the same feature.
- Keep the roadmap and status matrix concise; link to details rather than copying their contracts or checklists.
- Distinguish implemented source, actual build/test results, user-confirmed phone behavior and planned work. A smoke test is not full feature acceptance.
- Preserve meaningful failure evidence and outstanding checks when consolidating; remove superseded current-state claims.
- Keep generated logs, PNG dumps, downloaded archives and build outputs in ignored result/build directories. Retain checked-in test references, source provenance and required licenses.
- Follow [workspace rules](../AGENTS.md): builds or device execution need explicit user authorization.
