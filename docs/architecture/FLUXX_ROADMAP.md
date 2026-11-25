# Fluxx Roadmap: v0.1 → Full Vision

**Current status (2026-11-22):** Phase 2 remains active. **The text phase is complete** and the current big item is the [graph editor](#graph-editor-in-progress-g1-in-source): G1 (curve backend + Ease view) is implemented in source, phone acceptance pending. Every text plan, spec and stage record is now one document, the [text record](../checkpoints/TEXT.md). In brief: E1–E3, F1, T1–T4 and the Edit Text UI were user-tested; FIX/EXPLODE are committed; T5 is stopped (below); TEXT FIX 9 and PERF 10/11 were device-assisted A16 passes (`4c85cb2`). F3 (the Animate tab) and its F3.1–F3.3 follow-ups are merged to master (F3.1/F3.2 in `c8109d9`, F3.3 in `67df5f3`), with A16 instrumented tests passing and **phone acceptance of F3.1–F3.3 done** (user-confirmed). F3.4 (the Character Tracking cap and these docs fixes) is on the single `text` branch, merged to master. Open decisions: T5 cross-style shaping ownership; a non-debuggable measurement build; animator threading or a batch/draw contract change for 30 animated layers under 16.6 ms p95. Stage-by-stage evidence is in the text record.

**Current T5 stop (2026-10-09):** FIX `85b9de4` and EXPLODE `59a54a0` passed their compile/JVM/host gates and are implemented in source, device-unverified. T5 is dropped under the approved droppable-stage rule. The user's indivisible cursive-word decision resolves the historical emergency-wrap finding, but a new host proof shows mandatory Arabic lam-alef is lost across separate style items even with full source context and optional ligatures off. Cross-style shaping requires a further style-ownership decision beyond the approved wrapping change. No partial RTL code or production fonts remain; stored RTL still receives the existing rendering error. The diagnostic and this report remain uncommitted after the two feature commits. [Evidence, limits and exact manual tests](../checkpoints/TEXT.md#rec-text-fix-explode-t5). Earlier checkpoint statements below retain their historical context.

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

Fluxx is currently in **Phase 2: Timeline + Multiple Layers**. F1/F2 text are user-accepted. Their deferred device suites have since run on the A16 in [TEXT FIX 9](../checkpoints/TEXT.md#9d-deferred-device-suites-results-fixes-findings) and TEXT PERF 10/11. Results and the remaining environmental failures (Vulkan validation layer, golden source asset) are recorded there. The F3 animator UI is implemented and merged, reworked by F3.1 and polished by F3.2–F3.4, with phone acceptance of F3.1–F3.3 done; the text phase is complete ([text record](../checkpoints/TEXT.md)). See the [existing text animation plan](../checkpoints/TEXT.md#f2b-implementation-and-verification).
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
- **Current focus:** see the current status at the top of this document. Historical E1–E3 evidence (150/150 JVM run, lazy 2048 R8 texture-array atlas, E3 text creation and editing) is in the [text checkpoint](../checkpoints/TEXT.md#rec-text-layer); separate golden/16 KB evidence remains outstanding.

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
  - E1a A16 smoke and E1b/E1c acceptance are recorded in [the text layer record](../checkpoints/TEXT.md#rec-text-layer). E1d/E1e/E2/E3 are implemented in source and compiled, with 150 passing JVM tests. Texture-array overflow, v9 and editor wiring are complete in source; Separate 16 KB/golden evidence remains outstanding; E1–E3 device behavior is user-confirmed.
  - User-confirmed E1–E3 device behavior is distinct from separate golden/16 KB verification. Text data model, typography editor, font layout, and Vulkan glyph/quad rendering are implemented in source. [E2/E3 animation contract](../checkpoints/TEXT.md#31-universal-text-property-animation): size/fill and hold-keyed Source Text reuse the property framework; remaining text animation is future work. E1–E3 device behavior is user-confirmed; separate golden/16 KB evidence remains tracked in the checkpoint.

- [x] **Step F1: Text property completion**
  - Approved with amendments: tracking, additive leading, baseline shift, centred SDF stroke colour/width and hold-keyed paint order through existing animation/gesture infrastructure; additive v10 persistence.
  - Implemented in source with compile/JVM and host spread evidence; the user accepts F1 device checks except performance/parity/lifecycle, which remain unrun and deferred. [Implementation and exact user-run list](../checkpoints/TEXT.md#rec-text-f1).
- [x] **Step F2: Text animator engine (F2a then F2b)**
  - Additive v11 codec/model, selector engine and editing actions; per-glyph prepared rendering and dual ligature layouts implemented in source. V10 loading is permanent. Compile/JVM/host results and exact device list are in [the existing animation plan](../checkpoints/TEXT.md#f2b-implementation-and-verification). Device 60fps/zero-allocation/parity/lifecycle remain unverified; no limits have been reduced.
- [x] **Step T1–T4: Text completion** ([spec](../checkpoints/TEXT.md#rec-text-completion-spec), [evidence](../checkpoints/TEXT.md#rec-text-layer))
  - Rich styled text (v12), font import/library and static variations, paragraph/box text, wiggly selector and all 25 animator property families. User-tested on the phone; FIX and EXPLODE follow-ups are committed ([report](../checkpoints/TEXT.md#rec-text-fix-explode-t5)).
  - Commits: T1a `5772f10`, T1b `df056c4`, T1c `57c4251`, T2a `391aacf`, T2b `e4e6def`, T3a `f05e402`, T3b `0da4590`, T4a `adc922b`, FIX `85b9de4`, EXPLODE `59a54a0`.
- [ ] **Step T5: Arabic/Hebrew direction** — stopped under the droppable-stage rule; needs a cross-style shaping ownership decision. Stored RTL is still rejected for rendering.
- [x] **Edit Text UI overhaul, TEXT UI 5–8** — fixed non-scrolling Character/Paragraph faces, in-sheet overlays, shared colour picker family, marker popover and fixed section sizing. A16-reviewed (TEXT UI 6 layout accepted) and exercised through the F3.x phone acceptance ([plan](../checkpoints/TEXT.md#rec-text-ui-plan), [record](../checkpoints/TEXT.md#rec-text-ui-overhaul)).
- [x] **TEXT FIX 9 and TEXT PERF 10/11** — import regression, overlap-safe SDF and sharp tiers, with device confirmation per [FIX 9](../checkpoints/TEXT.md#rec-text-fix-9). Measured A16 frame-time fixes, one GPU submit per frame for text, and the resume/rotation fixes ([PERF 10](../checkpoints/TEXT.md#rec-text-perf-10), [PERF 11](../checkpoints/TEXT.md#rec-text-perf-11)). Open items are listed in PERF 11.
- [x] **Step F3: Animator UI** (branch `text-f3`, merged)
  - The Edit Text Animate tab on the F2/T4 backend: animator strip, Properties | Selectors, all 25 properties, Range (handle strip) and Wiggly selectors, keyframes through the existing rail/badges/timeline. No schema, preparation, evaluation or protocol change and no new action types. Presets are not in this pass. Implemented in source; superseded by F3.1–F3.3, whose phone acceptance is done. [Checkpoint, inventory and A16 list](../checkpoints/TEXT.md#rec-text-f3).
- [x] **Step F3.1: Animate rework** (branch `text-f3-1`, merged with F3.2 in `c8109d9`)
  - From the first A16 test:
    - one collapsible bar per animator (Property / Selector / Grouping rows), with a scrolling body (the one no-scroll deviation);
    - grip reorder and swipe delete;
    - slider or pad/dial/ruler transform controls (app preference);
    - Range/Wiggly tiles with Advanced groups, and redundant fields hidden in the UI only.
  - The Character offset/value crash is fixed at the metrics and renderer-preparation boundaries, with a cap pre-check.
  - Skew is capped at ±85°, Blur at 100px.
  - Prev/Next follows one global rule (focused keys → markers inside the span → in/out → markers outside → bounds; layer-wide keys added in F3.2 and made their own tier in F3.3).
  - Status: A16 instrumented tests pass; phone acceptance done. [Record](../checkpoints/TEXT.md#rec-text-f3-1).
- [x] **Step F3.2: AE parity fixes, seed flicker, export/playback, Animate polish** (branch `text-f3-2`, merged in `c8109d9`)
  - Ramp Up / Ramp Down selector coverage extends past End / before Start (AE). Random Seed interpolates and rounds per frame (flicker), 256 seeds per selector. Animated tracking spreads from the alignment point. These are deliberate evaluation changes; the goldens were re-recorded and a non-ramp guard golden is unchanged.
  - Fixed: the timeline keyframe-drag crash for animator fields, and the stale export dialog plus dead playback after an export.
  - Non-modal slider popovers (scrub, key and play while open), Animate caps, compact sliders with X/Y glyphs, signed number keyboards with lenient parsing, the Animate layout pass (add bar, opaque blocks, Property bar trash, swipe-delete tiles), and Prev/Next tier 1 across every key of the selected layer.
  - Status: JVM suite passes; A16 Animate layout tests pass (7 of 7); phone acceptance done. [Record](../checkpoints/TEXT.md#rec-text-f3-2).
- [x] **Step F3.3: popover exits, Prev/Next fall-through, sheet swipe, small polish** (branch `text-f3-3`, merged in `67df5f3`)
  - Slider popovers stay non-modal and now also close on the same tile, empty sheet-body space, a swipe down, and Back (first).
  - Prev/Next gains a tier: the focused field's keys, then every other key of the selected layer, then markers inside the span, in/out, markers outside, bounds. A keyed Random Seed behind the playhead no longer jumps to the layer end.
  - Swipe down from the top of the sheet is one sheet-level behaviour on every page (Edit Text had none). Adding or tapping a marker keeps the inspector open on the same page and focus.
  - Animate Tracking slider spans ±1000; "+ Animator" hides while a bar is expanded. Box-text tracking already spread from the alignment point (tests added).
  - Status: JVM suite passes (478); A16 Animate, inspector-swipe and inspector layout tests pass; phone acceptance done. [Record](../checkpoints/TEXT.md#rec-text-f3-3).
- [x] **Step F3.4: Character Tracking cap, docs consolidation** (branch `text`)
  - The Character Tracking slider spans its hard limit (±1000, detents every 100, typed ±1000), like the Animate one; Manual spacing keeps its ranges. Static tracking at ±1000 renders finite at unchanged cost.
  - All text docs consolidated into one [text record](../checkpoints/TEXT.md); the text branches replaced by one `text` branch. The text phase is complete.
  - Status: JVM suite passes (481). [Record](../checkpoints/TEXT.md#rec-text-f3-4).

**Done when:** a simple multi-layer, multi-keyframe composition previews and exports correctly.

### Graph editor (in progress: G1, G1.1, G2L, G2, G2.1, TL1 and G3 in source)

The graph editor (value and speed curves for keyframes, reached from the rail's Graph icon) is the current big item. It is planned in these passes:
- [x] **G1: curve backend + Ease view** — schema v13 easing (Hold, Linear, Easy Ease, Custom bezier with overshoot, Bounce, Elastic), `SetKeyEasing`, overshoot-safe consumers, and the Graph page's Ease view with presets, handles, numeric entry and `cubic-bezier()` clipboard. Implemented in source on `graph-g1`; JVM 518/518 and the targeted A16 layout/render tests pass; phone acceptance pending ([record](../checkpoints/GRAPH_G1.md)).
- [x] **G1.1: Graph page layout rework** — shared header row, Graph-only left rail with a Tools popover, touchpad + values bar as one item, Bounce/Elastic XY pad, right rail of types and presets; loupe, tile grid, numeric popover and preset strip removed. The reported playhead jump was a stale Graph drag callback that re-sent the playhead from when the page opened (after Prev/Next); fixed at the call sites and guarded by an A16 test that fails without the fix. JVM 524/524, A16 Graph and layout suites pass; phone acceptance pending ([record](../checkpoints/GRAPH_G1_1.md)).
- [x] **G2L: Ease view layout rework + curve library** — type strip inside the centre box, a pinned rail (up to 15, drag to pin/reorder/replace/unpin), a library that morphs over the strip + rail without resizing the touchpad, user curves (save, rename, delete, 60 max, app-level store), `.fluxxcurves` import/export via SAF, and a "Show playhead dot" toggle. The user-preset part of the old G4 moved here. No schema change, no new action. JVM 531/531; A16 Graph, layout and render suites pass; phone acceptance pending ([record](../checkpoints/GRAPH_G2L.md)).
- [x] **G2: Value graph** — the property's value over time from the real evaluator; key selection (tap) and seek (double tap); key dragging in time and value with the existing `MoveKeyframe` / `SetKeyframe(2D)` / animator Key MOVE+SET actions (one undo, frame snap, neighbour clamp); the selected segment's handles on the curve; pan, axis-aware pinch zoom and fit; Time / Value / X|Y values bar. Colour has no Value graph. Plus G2L carry-over: 2-column library grid without Types, the icon fade, an editable xy popover, and the typed-entry self-cancel fix (A4). No schema change, no new action. JVM 542/542; A16 Graph, layout and render suites pass; phone acceptance pending ([record](../checkpoints/GRAPH_G2.md)).
- [x] **G2.1: acceptance fixes**:
  - a shared hex parser (3, 6 and 8 digits);
  - Value graph keys are not draggable;
  - nothing draws through the values bar;
  - the Graph uses the inspector pages' units (shared `PropertyDisplay`);
  - a live Value tile and an inert Time tile;
  - the time audit, with Graph times now in the transport's timecode;
  - a compact independent xy popover;
  - library and rail drags lift, follow and reflow, with 3.75× auto-scroll;
  - Snap on by default and persisted;
  - magnetic Transform detents (Rotation 5°, Opacity and Scale 25%).

  No schema change, no new action. A16 suites pass, with two flaky tests reported; phone acceptance pending ([record](../checkpoints/GRAPH_G2_1.md)).
- [x] **TL1: timeline**:
  - scrubs land on whole frames;
  - the playhead readouts floor (the frame on screen) and pause rests on it;
  - a tiered ruler (second / half-second / frame) that adapts to zoom;
  - one frame → x function for ticks and playhead (0.00 px offset);
  - a live Graph Time tile.

  No schema change, no new action. JVM 571/571; A16 TimeAuditTest passes; phone acceptance pending ([record](../checkpoints/TL1.md)).
- [x] **G3: Speed graph**:
  - speed over time in the page's units/s, one combined curve for Position and Scale;
  - speed / influence handles;
  - a Speed / Influence values bar;
  - Smooth through key (Speed view, one undo);
  - the page opens on the last-used tab.

  No schema change, no new action. JVM 581/581; A16 `GraphSpeedTest` passes; phone acceptance pending ([record](../checkpoints/GRAPH_G3.md)).
- [x] **G3.1**:
  - the G3 verification finished;
  - the rail auto-scroll hardened;
  - ruler tiers always three at the default zoom (major 12, mid 5, minor 3dp);
  - the Speed view is segment-framed and stable, with no pan and an Ease-style relative drag;
  - Speed handles cannot cross;
  - magnetic speed / influence landmarks.

  JVM 587/587; A16 sweep green ([record](../checkpoints/GRAPH_G3_1.md)).
- [ ] **G4: convert Bounce/Elastic to keys** and extra curve libraries. Next.
- Later: share-sheet export of curves, separate X/Y easing and dimensions (needs schema v14) and multi-select.

### Timeline productivity (parked)

Still parked after the text phase. These are user ideas recorded for planning; none is built.

- **Select all and multi-select for layers.** This is the foundation for the rest; "Select all" is still a disabled placeholder.
- **Duplicate count.** Hold the duplicate button on a layer to choose how many duplicates to make, instead of tapping once per copy.
- **Stagger** the selected layers by a chosen number of seconds.
- **Arrange and sequence layers:** layer arrangement, and placing layers end to end, with or without overlap.
- **Keyframe stagger / sequence:** the same stagger and sequence operations for keyframes.

### Parked / follow-ups

- **Parked (GRAPH G2.1 / TL1 / G3 / G3.1):** Position detents (needs layer geometry), drop-frame timecode, the Interface size setting, separate X/Y easing (needs schema v14), a "Make continuous" action, and Smooth through key in the Value and Ease views. The live Time tile shipped in TL1.
- **Interface size setting** (from GRAPH G2L): an app-level size for inspector tiles and thumbnails; several Graph widths (strip, rail, library) now follow fixed tile sizes.
- **Landscape editor layout.** Rotation now keeps the project open, but the landscape layout is cramped and the preview distorts.
- **T5 right-to-left text.** Stopped; needs the cross-style shaping ownership decision.
- **Shapes and masks from text.**
- **3D text extrusion.**
- **Imported-font latency.** Still not instant; the worker's first rasterisation is unchanged (TEXT PERF 11).
- **Eyedropper in the animator colour popover** (TEXT F3 follow-up, still open after F3.1): wire the existing eyedropper to Fill/Stroke colour animator properties.
- **On-preview selector coverage highlight** (TEXT F3.1 follow-up): show which characters a selector covers on the preview, now that the Range strip is gone.
- **Animator gaps against After Effects** (TEXT F3.1): per-character 3D rotation, an "All Transform Properties" shortcut, the Character Range option and the Expression selector.
- **Release-like benchmark build type**, for real performance numbers.
- **Animator evaluation on a second core.**
- **Per-cluster modifiers in the vertex shader.**
- **Double-buffered batch resources.**
- **Environmental test gaps:** GPU validation-layer tests and the missing E1a export source asset.

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
