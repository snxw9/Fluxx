# Phase 2 Status, Backend Architecture & UI Control Matrix

**Status:** Phase 2 A-D are implemented in source; E1b/E1c CPU text is implemented. Android debug Kotlin and both-ABI native debug/release compilation passed during E1b/E1c. The user confirmed A16 E1a render/playback/export smoke. Specific feature acceptance and JVM/device tests remain pending unless individually recorded. "Functional" below means implemented, not exhaustive phone verification.

---

## 1. Phase 2 Scope & Architectural Boundary

Phase 2 transitions Fluxx from a single-layer proof-of-concept to a multi-layer motion graphics compositor. It is divided into five sequential steps:

- **Step A:** Multi-layer composition contract, FlatBuffers v4 schema, and persistence. *(Implemented in source)*
- **Step B:** Shared multi-layer Vulkan rendering and audio mixing parity. *(Implemented in source, pending on-device acceptance)*
- **Step C:** Mobile editing UI (shell, timeline, inspector, media browser, autosave). *(Implemented in source, pending on-device acceptance)*
- **Step D:** Keyframe animation on transform properties (Bezier interpolation + 1 easing preset). *(Implemented in source, pending on-device acceptance)*
  - [Approved Option B architecture, regression coverage and acceptance checklist](KEYFRAME_ANIMATION.md).
- **Step E:** E1a A16 render/playback/export smoke confirmed by user. E1b/E1c CPU shaping, static font bundle and cached SDF generation implemented; six host shaping fixtures pass. Android native/debug Kotlin compilation passes; device typography tests pending. No Vulkan text or editor integration. See [TEXT_LAYER.md](TEXT_LAYER.md). E1d requires the user's outstanding 16 KB runtime and golden-export comparison.

---

## 2. Backend Engine Architecture

### Bounded Working Sets (No Artificial Layer Ceilings)
Fluxx imposes **no arbitrary ceiling** on project layers or export layers (resident resource bounds are not project-layer quotas; large-project performance still requires measurement). Resident hardware resources are strictly bounded:
- **Video Decoders:** LRU pool by URI (2 slots on low-RAM devices, 4 otherwise). Open failures trigger LRU eviction and retry.
- **Raster Cache:** LRU image cache (16 MiB on low-RAM, 32 MiB otherwise) capped at 1920px longest edge.
- **GPU Layer Resources:** Up to 4 resident layer entries batch-composited to the offscreen target in draw order.
- **Persistent Readback:** One mapped host RGBA buffer reused for all export frames.

### Visual Compositing Pipeline
- **FramePlan:** Prepares visible layers ordered by `(zOrder, id)` once per immutable project revision. Evaluated via `[startUs, endUs)`.
- **CompositionRenderer:** Offscreen Vulkan compositor shared between preview and general export.
- **LayerGeometry:** Calculates normalized position, aspect-fit contain scaling, and user rotation relative to a stored reference canvas. Canvas resizing adjusts clipping/projection without warping existing layer coordinates.
- **Image/Solid Pipeline:** Image decoding respects EXIF orientation. Solids expand a 1-pixel RGBA texture to canvas dimensions.

### Dual Export Pipelines (`CompositionExporter`)
1. **Single-Video Fast Path:** Media3 Transformer 1.11.1 pipeline for a visible, unmuted, unity-gain, full-source single video with no animated transform, a centred anchor, default composition dimensions, matching reference canvas and resolved duration. Retains source frame timing and zero-copy GPU encoding.
2. **General Layered Path:** Vulkan headless rendering to a reusable host RGBA buffer, BT.709 YUV conversion, MediaCodec H.264 (12 Mbps), and AAC stereo (192 kbps) muxed into an MP4 published to `Movies/Fluxx`.

### Audio Mixing Pipeline
- **AudioMixer:** Merges active, unmuted video tracks into a disk-backed float stereo mix.
- **Clock Master:** `AudioTrack` playback position drives preview timing; visual frames skip or throttle to maintain audio sync.
- **Resampling:** Linear PCM resampling to 48 kHz stereo with conservative peak headroom scaling.

---

## 3. UI Control & Feature Status Matrix

The shared colour picker and responsive inspector grid are implemented in source; Galaxy A16 measurement, builds and phone acceptance remain pending. See [Global Colour Picker](GLOBAL_COLOUR_PICKER.md).

This matrix maps every editor control to its underlying engine state, distinguishing active controls from inert placeholders:

| Control / Feature | Live State | Required Before Enabling |
| :--- | :--- | :--- |
| **View / Preview Menu** | Implemented in source in toolbar slot 7: resolution badge, layer controls, grid, camera placeholders, Zoom In/Out/100%/Fit | Seven 48dp-wide × 40dp-tall controls with 20dp vectors; clipping and neighboring-icon thumb accuracy await Galaxy A16 review. |
| **Preview Resolution / Expansion** | Full/Half/Third/Quarter target scaling; original presentation aspect retained; expansion moved to viewport corner | Native target recreation waits for GPU idle. Headless export and reference canvases are isolated. Phone verification pending; [checkpoint](TIMELINE_LAYOUT.md#preview-quality-and-presentation). |
| **Preview Layer Controls / Grid** | Bounding outline, eight selection indicators, passive anchor toggle with active-edit override, composition grid | Compose-only overlays; source geometry and transformed/zoomed alignment await phone verification. Motion Paths, Control Points, X-Ray and Camera remain disabled. |
| **Contextual Previous / Next** | Scoped markers / focused animated keys → selected-layer bounds → global bounds, with direction-specific fallthrough | Marker tier reachable from any frame, including between markers; focused properties retain keyframe priority. Prepared tests and phone verification pending. |
| **Timeline Interactions** | Implemented in source: 28dp rows/no extra row gap (2dp between clip bodies), 26dp clips, transparent unblurred eyes, always-visible 14×8dp grips with 48dp-wide row touch targets, 100ms sibling reflow, absolute-pointer drop targeting with release refresh, inspector viewport restore | Phone acceptance pending; see [timeline checkpoint](TIMELINE_LAYOUT.md#timeline-gestures-and-inspector-viewport). |
| **Timeline Layout & Duration** | 32dp frame ruler → 8dp gap → tracks → 8dp inspector separator; shared geometry in both preview modes; solid-colour clips; new empty projects derive duration from layers | [Layout checkpoint](TIMELINE_LAYOUT.md); tests and device checks pending. |
| **Flip Horizontal / Vertical** | Functional in source for selected visual layers; negate evaluated Scale, auto-key animated Scale, preserve spatial pivot | Renderer source uses `VK_CULL_MODE_NONE`; actual flipped preview/export and tests await user verification. |
| **Fit to Composition Width / Height** | Functional in source for Video/Image; uniform absolute positive scale via shared `LayerGeometry` aspect fit | Source dimensions/rotation/SAR required; zero reference dimensions fall back to composition. Tests and phone verification pending. |
| **Stretch to Composition Area** | Functional in source for Video/Image; independent positive X/Y scales, preserving position/rotation/pivot | Uses the same fit geometry; full aligned bounds assume no user rotation and a centred layer. Phone verification pending. |
| **Media Info** | Functional in source for Video/Image; shared browser/dialog metadata content | `MediaRepository.details`; handles loading and unavailable metadata. Phone verification pending. |
| **Auto Orient** | Visible, disabled & inert in the selected-layer menu | Per-frame Position curve tangent evaluation over composition time. |
| **Extract Audio** | Visible, disabled & inert in Audio inspector only | Audio-only layer model (`LayerType.AUDIO`), timeline audio tracks and audio mixer layer pipeline. |
| **Convert to Outline / Autotrace** | Visible, disabled & inert in selected-layer menu | Bitmap vectorization/tracing engine and vector shape layer model. |
| **Position / Scale / Rotation** | **Functional** (2D dot-grid, dial, horizontal rulers) | Fully supported in 2D engine and persistence. |
| **Anchor Point Mode** | Functional in source (3×3 presets, percentage entry, centre hold-drag, static Position auto-compensation) | v6 spatial pivot persistence and shared matrix integration implemented; Feature tests and phone verification pending. Position keyframes are preserved; anchor animation is not implemented. |
| **Z-Position & XYZ Rotation** | Hidden / Planned | 3D transform math, camera projection, and engine evaluation. |
| **3D Utility Rail Icon** | Visible, disabled & inert | 3D transform math, camera projection, and engine evaluation. |
| **Composition & Layer Markers** | **Functional in source** (ruler/layer flags, playhead tint, Global Colour Picker, split partitioning, marker navigation) | [Marker checkpoint](MARKERS.md); tests, allocation profiling and phone review pending. |
| **Keyframe Diamonds** | **Functional** (Inspector rail toggle/add/remove, timeline markers, magnetic snap) | Integrated into FlatBuffers schema, unified Kotlin Bezier evaluator, and UI. |
| **Graph Editor Icons** | Visible, disabled & inert | Full SDD dual Value/Speed Graph Editor and curve backend. |
| **Solid Layers & Colour Picker** | **Functional** (SV box, hue strip, sliders, hex, alpha, draggable eyedropper, custom palettes) | Fully integrated into renderer, FlatBuffers schema, and UI. |
| **Palette System** | Implemented in source (create/switch/rename/delete/edit palettes, save swatches, AtomicFile JSON); tests pending | App-level data independent of project FlatBuffers. |
| **Palette Import (External)** | Not implemented | External .ase/.gpl swatch import is deferred. |
| **Contextual Edit (Edit Solid)** | **Functional** (Opens Global Colour Picker) | Integrated via shared `FluxxColorPicker`. |
| **Contextual Edit (Edit Footage)** | Visible, disabled & inert (non-tappable) | Footage editing capabilities (speed ramping, retiming, source replace). |
| **Blending & Opacity Tab** | **Functional** (Opacity slider, reset; blend mode row is inert) | Additional blend modes require per-mode shader / linear blend math. |
| **Effects Tab** | Visible, disabled & inert (non-tappable) | Phase 3: Curves + Gaussian Blur ping-pong FBO pipeline (SDD §4, §9). |
| **Layer Styles Tab** | Visible, disabled & inert (non-tappable) | Layer style model, drop shadow, and glow rendering. |
| **Track Mattes / Masks Tab** | Visible, disabled & inert (non-tappable) | Phase 3: Hard-edge stencil masks. Relationship-based track mattes (SDD §8). |
| **Motion Blur Cell** | Visible, disabled & inert (non-tappable) | Composition-level shutter angle/phase and layer-level blur engine. |
| **Shape Layer Icon** | Visible, disabled & inert | Vector shape model, path tessellation, and shape renderer. |
| **Native text shaping** | E1b CPU source implemented; six host hb-shape parity fixtures pass | Android instrumented execution pending; no renderer submission yet |
| **CPU SDF / atlas packing** | E1c dumps, overlap probe and retained bitmap cache verified on host | GPU atlas/pipeline remain E1d; measured 338 / 1,547 glyphs for combined corpus |
| **Bundled text faces** | Static Inter / Noto Serif / JetBrains Mono, pinned hashes and OFL notices | Native fallback implemented; editor picker remains E3 |
| **Text Layer Icon** | Visible, disabled (`LayerType.TEXT` is placeholder) | **Phase 2 Step E:** Text data model, renderer integration and editor remain planned. |
| **Adjustment Layer Icon** | Visible, disabled & inert | Adjustment layer model and render-pass effect accumulation. |
| **Camera Layer Icon** | Visible, disabled & inert | Camera model, 3D viewport projection, and scene hierarchy. |
| **Null Layer Icon** | Visible, disabled & inert | Non-rendering transform parenting and spatial hierarchy. |
| **Layer Duplicate / Copy / Paste** | **Functional in source** (Duplicate directly above source, hold copy, toolbar paste at top/playhead) | Duplicate uses atomic Add/Reorder batch with a fresh ID and one undo entry; adjacent placement phone verification pending. |
| **Select All Layers** | Visible, disabled & inert | Multi-selection architecture decision across gestures and commands. |
| **Timing Tab** | **Functional** (Start/Duration for all; Source In for video) | Source-to-composition time warp model before true speed remapping. |
| **Audio Sub-Pill** | **Functional** (Read-only browsing of Songs/Albums/Artists) | Audio-only layer model (`LayerType.AUDIO`) and renderer integration. |
| **Autosave** | **Functional** (Lifecycle triggers, `ProjectSaveQueue`, atomic writes) | Serializes immutable snapshots on `onPause`/`onStop`. |

---

## 4. State & Gesture Protocol

The editing UI adheres to an immutable unidirectional state flow:
- `EditorViewModel.state` is the committed source of truth.
- `transientState` holds live gesture previews (e.g. active dragging/scrubbing).
- `displayedState` delivers `transientState ?: state` to the UI and preview renderer.
- **Gesture Lifecycle:**
  1. `beginGesture()`: Pauses playback and captures base layer state.
  2. `previewGesture(action)`: Dispatches absolute transforms/trims against base state.
  3. `commitGesture()`: Commits final snapshot into history and disk save queue.
  4. `cancelGesture()`: Reverts transient changes cleanly on pointer cancellation.
