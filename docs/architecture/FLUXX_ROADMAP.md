# Fluxx Roadmap: v0.1 → Full Vision

**Text completion status (2026-10-04):** F1/F2 user-accepted. T1a foundation committed (`5772f10`); T1b styled rendering/Character editing is source-implemented with its compile/JVM gate passed. Authorized continuation permits stage sub-commits. T2 fonts → T3 paragraph/box text → T4 animator gaps → T5 Arabic/Hebrew direction remain ordered work. F1 perf/parity/lifecycle stay deferred; F3 animator UI remains later. [Current evidence](../checkpoints/TEXT_LAYER.md) and the [verbatim specification](TEXT_COMPLETION_SPEC.md) distinguish implementation from device verification.

> Companion to `FLUXX_SDD.md`, which describes the complete target architecture. This document defines what actually gets built first, in what order, and why.

## Why a separate v0.1

The full SDD scope — dual Graph Editor, 15 effects, masks/mattes/blend modes, 9 layer types, 4K export, resumable chunked rendering, all on a from-scratch Vulkan/C++/Oboe engine — is normally a multi-year build for a small team of specialists. Cutting it down for v0.1 doesn't lower the ambition; it's the same destination with a route that surfaces problems in the Vulkan/JNI/audio-sync core in week three instead of month eight, when there's nothing else built yet to lose.

## v0.1 Scope

**In:**
- Single composition, no nesting
- 4 layer types: Video, Image, Solid, Text
- Transform animation (position, scale, rotation, opacity), plus Step E font size/fill colour and hold-keyed Source Text through the same property framework; other text properties become animatable in later passes
- 2 effects: Curves (cheap, LUT-based) + Blur (GPU-heavy, proves the ping-pong FBO stack)
- Hard-edge stencil masks only (no feathering)
- Normal blend mode only
- 1080p export, single-shot (not chunked/resumable)
- Timeline: layers, drag-to-trim, scrub, zoom/pan

**Deferred to v0.2+:** everything else in `FLUXX_SDD.md` — remaining effects, feathered mattes, track mattes, other blend modes, nested comps, dual Graph Editor, motion paths, text range selectors, 4K export, resumable chunked export, WAL crash recovery, baked/stylized layer bounding-box rendering (Stages 2–3).

## Defaults (change any of these — none are locked in)

- **Jetpack Compose (Android-only).** Chosen because Compose Multiplatform's Android target uses the identical Compose compiler and runtime, resulting in no performance differences on Android. Since any future iOS release is planned as a separate native rebuild (utilizing Metal, Core Audio, and AVFoundation/VideoToolbox) rather than a shared-UI port, there is no code-sharing justification for carrying Kotlin Multiplatform project structure overhead.
- **Min SDK 29 (Android 10), target SDK 35, compile SDK 37 (current build settings).** API 29 is the floor for the guaranteed Vulkan 1.1 baseline on 64-bit devices. Devices on 29–34 lose the `mediaProcessing` foreground service type (added in API 35) and fall back to best-effort background export.
- **First two effects: Curves + Blur** — one proves the cheap LUT path, one proves the expensive FBO ping-pong path. Which effect comes third onward in v0.2 is genuinely an open call.

## Current Status & Active Phase

Fluxx is currently in **Phase 2: Timeline + Multiple Layers**. F2a/F2b text animator engine is implemented in source with compile/JVM/host verification; device performance/parity/lifecycle remain unverified. F1 is user-accepted, with its performance/parity/lifecycle checks deferred. F3 animator UI remains future work. See the [existing text animation plan](../checkpoints/TEXT_ANIMATION_F1_F3_PLAN.md#f2b-implementation-and-verification).
- **Steps A, B, and C** (backend composition model, Vulkan multi-layer rendering, audio mixing, and mobile editing UI across shell, inspector, timeline, browser, and autosave) are **implemented in source**.
- Android debug Kotlin and native debug/release compilation passed during E1b/E1c; the user confirmed A16 E1a render/playback/export smoke. Feature-specific phone/JVM acceptance remains where explicitly listed; see the status matrix and text checkpoint.
- Comprehensive feature specifications and verification checklists are consolidated under [`docs/checkpoints/`](../checkpoints/):
  - [**Phase 2 Architecture & UI Matrix**](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md)
  - [**Home, Projects & Creation Sheet**](../checkpoints/HOME_AND_PROJECTS.md)
  - [**Element Inspector & Layer Actions**](../checkpoints/ELEMENT_INSPECTOR.md)
  - [**Media Browser & Content Import**](../checkpoints/MEDIA_BROWSER.md)
  - [**Autosave & Persistence**](../checkpoints/AUTOSAVE.md)
  - [**Composition & Layer Markers**](../checkpoints/MARKERS.md) — implemented in source; tests and phone acceptance pending.
  - [**Timeline Layout & Automatic Duration**](../checkpoints/TIMELINE_LAYOUT.md) — compact separated ruler/tracks/inspector regions, frame-grid ticks, content-driven new projects; source implementation with validation pending.
- **Current focus:** Phase 2 text F2 engine (F2a/F2b implemented in source, device-unverified; F3 deferred). The final user instruction treats F1 performance/parity/lifecycle as unrun and deferred; prior status claims do not establish those checks. The user confirmed E1–E3 text behavior on device. [F1 checkpoint](../checkpoints/TEXT_F1_IMPLEMENTATION.md) records v10 property completion, spread evidence and outstanding A16 tests. E2 uses the approved lazy 2048 R8 texture-array atlas; E3 includes text creation, fixed Edit Text controls, workspace IME handling, shared geometry and one undo entry per typing session. Separate golden/16 KB evidence remains outstanding. The historical E1–E3 JVM run passed 150/150 tests; see the F1 checkpoint for current validation.

---

## Phased Build Order

### Phase 0 — Walking Skeleton (Complete)
No user-facing features. Goal: confidence the hard technical bets actually work on real hardware before anything gets built on top of them.
- [x] Vulkan instance/device/swapchain init, rendering a solid color quad at target fps on a real mid-tier test device
- [x] JNI round trip (Kotlin → C++ → Kotlin) with no crashes
- [x] One MediaCodec-decoded frame bound zero-copy as a Vulkan texture via AHardwareBuffer — the riskiest claim in the SDD, prove it early
- [x] A `mediaProcessing`-typed foreground service running a fake export that survives the app being backgrounded

**Done when:** all four run reliably on both a ~4GB budget device and a flagship. *(Phase 0 Complete — device confirmed)*

#### Phase 0 retrospective
- The single most recurring bug class was releasing a buffer/resource across a producer-consumer boundary before the consumer was confirmed done (ImageReader release timing, generation races). Treat new cross-boundary handoffs with suspicion.
- Never let two independent threads issue Vulkan calls on the same device/queue without explicit coordination.
- A feature isn't actually done until there's a way to trigger and verify it end-to-end. Confirm "seen it happen on the device."

### Phase 1 — One Layer, One Frame (Complete)
- [x] Single video layer with transform (position/scale/rotation/opacity) controllable live from UI sliders
- [x] Aspect-fit contain projection preserving video aspect ratio without stretching
- [x] Minimal `.fluxx` save/load for one layer
- [x] Export that single composited layer to MP4 via Media3 Transformer

**Done when:** you can import a video, nudge it around the frame, and export a file that plays back correctly. *(Phase 1 Complete — verified on device)*

#### Phase 1 retrospective
- Duplicate/stale sources of truth silently drifted apart during early iterations.
- Standing rule: grep for duplicate sources of truth whenever a value's ownership changes during a refactor.

### Phase 2 — Timeline + Multiple Layers (Active Phase)

- [x] **Step A: Multi-Layer Composition Contract & Backend**
  - Immutable `ProjectDocument`, `CompositionLayer`, FlatBuffers v4 schema, `FramePlan` z-sorting, rational frame rates.
  - Zero artificial layer quotas; bounded working sets (video decoder LRU, raster cache, GPU layer batches).
  - Multi-track `AudioMixer` with disk-backed stereo mix.
  - General layered export via `CompositionExporter` + retained single-video Media3 fast path.
  - *Details:* [PHASE_2_STATUS_AND_BACKEND.md](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md)
- [x] **Step B: Multi-Layer Visual & Audio Parity**
  - Vulkan layered offscreen compositor (`CompositionRenderer`) with reference-canvas geometry.
  - *Details:* [PHASE_2_STATUS_AND_BACKEND.md](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md), [Composition resize evidence](../checkpoints/TIMELINE_LAYOUT.md#composition-resize-and-surface-ownership)
- [x] **Step C: Mobile Editing UI & Integration**
  - Shell & Projects: Floating navigation pill, liquid bubble create button, project cards with rendered first-frame thumbnails, A/B/C sorting, and composition creation sheet with presets. (*Details:* [HOME_AND_PROJECTS.md](../checkpoints/HOME_AND_PROJECTS.md))
  - Editor Workspace: Letterboxed Vulkan preview with expand/collapse, toolbar actions, synchronized timeline tracks, clip moving, frame-snapped trimming.
  - Element Inspector: 2D position dot-grid, rotation dial with turn accumulation (`2x45`), horizontal scale rulers, Blending & Opacity, video gain/mute, Global Colour Picker, 8-cell grid landing page, 3D rail icon, duplicate, hold-to-copy, and paste layer. (*Details:* [ELEMENT_INSPECTOR.md](../checkpoints/ELEMENT_INSPECTOR.md), [AE_PARITY_VISION.md](AE_PARITY_VISION.md))
  - Media Browser: Paginated MediaStore folders, full-screen browser with filmstrip drag scrub, batch multi-selection import, read-only audio browsing. (*Details:* [MEDIA_BROWSER.md](../checkpoints/MEDIA_BROWSER.md))
  - Persistence & Autosave: Lifecycle-driven (`onPause`/`onStop`) `ProjectSaveQueue` with atomic file writes. (*Details:* [AUTOSAVE.md](../checkpoints/AUTOSAVE.md))
  - *Status: Implemented in source; awaiting user Android Studio build & phone review.*
- [x] **Step D: Keyframe Animation on Transform Properties**
  - Follow-up: [seven inspector trim/extend/split actions](../checkpoints/ELEMENT_INSPECTOR.md#7-trim-extend-and-split), v5 persistent animation anchors, signed key rebasing and atomic split history. Implemented in source; validation pending.
  - Keyframe data model, actions, FlatBuffers persistence, and Bezier interpolation (Easy Ease preset).
  - Inspector utility rail keyframe diamond buttons (toggle/add/remove/auto-keyframe).
  - Timeline inline diamond markers with 48dp Fitts's Law touch targets, magnetic snapping, and seek-on-tap.
  - Export fast-path guard ensuring animated single videos use layered Vulkan pipeline.
  - *Status: Implemented in source; awaiting user Android Studio build & phone review.*
- [x] **Step E: Text Layer Support**
  - E1a A16 smoke and E1b/E1c acceptance are recorded in [TEXT_LAYER.md](../checkpoints/TEXT_LAYER.md). E1d/E1e/E2/E3 are implemented in source and compiled, with 150 passing JVM tests. Texture-array overflow, v9 and editor wiring are complete in source; Separate 16 KB/golden evidence remains outstanding; E1–E3 device behavior is user-confirmed.
  - User-confirmed E1–E3 device behavior is distinct from separate golden/16 KB verification. Text data model, typography editor, font layout, and Vulkan glyph/quad rendering are implemented in source. [E2/E3 animation contract](../checkpoints/TEXT_LAYER_IMPLEMENTATION_PLAN.md#31-universal-text-property-animation): size/fill and hold-keyed Source Text reuse the property framework; remaining text animation is future work. E1–E3 device behavior is user-confirmed; separate golden/16 KB evidence remains tracked in the checkpoint.

- [x] **Step F1: Text property completion**
  - Approved with amendments: tracking, additive leading, baseline shift, centred SDF stroke colour/width and hold-keyed paint order through existing animation/gesture infrastructure; additive v10 persistence.
  - Implemented in source with compile/JVM and host spread evidence; the user accepts F1 device checks except performance/parity/lifecycle, which remain unrun and deferred. [Implementation and exact user-run list](../checkpoints/TEXT_F1_IMPLEMENTATION.md).
- [x] **Step F2: Text animator engine (F2a then F2b)**
  - Additive v11 codec/model, selector engine and editing actions; per-glyph prepared rendering and dual ligature layouts implemented in source. V10 loading is permanent. Compile/JVM/host results and exact device list are in [the existing animation plan](../checkpoints/TEXT_ANIMATION_F1_F3_PLAN.md#f2b-implementation-and-verification). Device 60fps/zero-allocation/parity/lifecycle remain unverified; no limits have been reduced.
- [ ] **Step F3: Animator UI**
  - Deferred: Add Animator picker, fixed selector pages, diamonds and preview range handles. No new UI in F2.

**Done when:** a simple multi-layer, multi-keyframe composition previews and exports correctly.

### Phase 3 — First Real Effects + Masks
- Ping-pong FBO effect stack with Curves + Blur
- Hard-edge stencil masks
- Layer bounding-box overlay (toggleable preview overlay, bake-to-export)

**Done when:** both effects apply correctly, stack together, and a mask correctly clips a layer.

### Phase 4+ — Everything Else
Sequence the remaining `FLUXX_SDD.md` scope from here: dual Graph Editor, remaining effects, feathered mattes, track mattes, other blend modes, nested comps, motion paths, text range selectors, 4K export, resumable chunked export, WAL crash recovery. Roughly in that order of dependency — but revisit priority once v0.1 is in hand and it's clearer what actually matters for using the tool.

## Pre-Coding Checklist

- [ ] Repo scaffold per SDD Section 12
- [ ] Effects renamed away from Adobe's naming (already reflected in the updated SDD)
- [ ] Test device matrix: at least one real ~4GB device + one flagship
- [ ] Decide on crash reporting/telemetry now — cheap to add at v0.1, painful to retrofit into a hand-tuned zero-allocation hot path later
- [ ] Git LFS for `/libs` if precompiled binaries grow large
