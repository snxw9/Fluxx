# Fluxx: Android Motion Graphics Compositor
## Software Design Document (SDD) — Full Vision

> **Scope note:** This document describes Fluxx's complete target architecture and feature set — the north star. It is **not** the v0.1 build scope. For what actually ships first, in what order, and why, see `FLUXX_ROADMAP.md`. Nothing in v0.1 should paint the engine into a corner that blocks anything described here later.

### Current implementation note - 2026-09-11

The target architecture below is not a description of completed engine features. Phase 2 A-C backend details and outstanding hardware validation are recorded in [PHASE_2_STATUS_AND_BACKEND.md](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md). Current layered preview/export share a batched offscreen Vulkan compositor; general export transfers RGBA to a reusable host buffer for MediaCodec input, while the working full-source single-video Media3 path remains available. Audio uses a disk-backed sequential mix and AudioTrack preview. The future scene-linear color engine, frame/proxy caches, Oboe-driven composition audio and resumable export described below are not established by this implementation.

**Scaling requirement:** never impose an arbitrary maximum number of project or export layers. Bound expensive resident resources, virtualize UI work and schedule additional work through those resources. Actual format, allocation and storage failures must be reported, and real-time guarantees must be based on measurements. See [PHASE_2_STATUS_AND_BACKEND.md](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md) for the live UI placeholder matrix.

### Revision notes (v1 → v2)
- Reframed Section 2 as the full feature scope, not "minimum" — see roadmap for what v0.1 actually contains
- Renamed four effects that mirrored Adobe's own product naming too closely (Section 2)
- Vulkan-only architecture for v1; OpenGL ES fallback deferred (Section 3)
- Clarified FlatBuffers vs. Direct ByteBuffer responsibilities in the JNI bridge (Section 3)
- L1 cache changed from a fixed constant to a runtime-detected budget (Section 4)
- Added an OEM scoped-storage testing note (Section 4)
- Noted the fixed Masks→Effects pipeline order as a deliberate, documented simplification (Section 5)
- Reconstructed the transform, coordinate-mapping, and Bezier formulas missing from the source doc (Sections 5–7) — flagged inline, verify conventions before implementing
- Added a precision note on linear-space compositing for high-accumulation effects like Glow (Section 9)
- Rewrote background export around Android's actual `mediaProcessing` foreground service type, its time budget, and the Play Console declaration requirement (Section 10)
- Fixed "forbidden from using `new`" to the language-agnostic rule it's actually describing (Section 11)
- Added a Git LFS note for `/libs` (Section 12)
- Replaced Compose Multiplatform with plain Jetpack Compose for the UI layer (Section 3)

---

## 1. Vision & Core Philosophy
Create the first truly professional motion graphics compositor for Android. Designed exclusively for touch devices, Fluxx replicates a desktop-class compositing workflow without cloning the desktop UI.
- **Professional First:** Every feature solves a real motion graphics problem.
- **Motion Graphics First:** A compositor before it is a video editor. The engine prioritizes frame generation over clip trimming.
- **Non-Destructive:** Source files are never modified; projects are strictly instruction sets.
- **Offline-First & Distraction-Free:** No internet required. No advertisements. No AI-generated content (only utility AI, such as rotoscoping). No plugin ecosystem.
- **Performance is a Feature:** Optimization is foundational. The engine targets mid-tier mobile hardware for 60fps real-time playback without thermal throttling.
- **Backward Compatibility:** Projects created today must seamlessly open in future versions.

## 2. Full Feature Scope
The complete Fluxx feature set, across all versions, encompasses the following core pillars. **This is the full vision, not the v0.1 release** — see `FLUXX_ROADMAP.md` for what ships first.
- **Project Management:** Unlimited nested compositions, dedicated asset manager, and a resumable render queue.
- **Timeline Interactions:** Unlimited layers, parenting, solo/hide/lock toggles, layer labels, and motion blur switches. Touch-optimized gestures (zoom, pan, drag-to-trim).
- **Animation System:** Bezier interpolation, dual Graph Editor (Value and Speed), motion paths, and custom easing curves.
- **Layer Types:** Video, Image, Audio, Shape, Text, Solid, Null, Adjustment, and Pre-composition.
  > **Precomp sizing rule (confirmed against After Effects behavior):** When precomposing, the new precomp's stored width/height is determined at precompose-time, not inherited dynamically from wherever it's nested later.
  > - Precomposing a single layer → new precomp's resolution matches that layer's native pixel dimensions (e.g. the video's decoded resolution, or the image's pixel size).
  > - Precomposing multiple layers → new precomp's resolution matches the original parent composition's resolution.
  > 
  > Once created, a precomp is just another composition — it gets its own offscreen render target at its own fixed resolution (per the offscreen-target architecture used for the top-level comp), and functions as an ordinary layer (with its own aspect) when nested inside a parent comp. Nesting a precomp into a differently-shaped parent comp never resizes or reshapes the precomp itself.
- **Built-in Effects:** Gaussian Blur, Glow, Curves, Levels, Tint, Fill, Hue/Saturation, Exposure, Sharpen, Chaos Displace, Ripple Distort, Mosaic, Posterize, Tile Repeater, Procedural Noise.
  *(Renamed from the original Turbulent Displace, Wave Warp, CC RepeTile, and Fractal Noise — those mirror Adobe's own effect naming closely enough to be worth avoiding in a from-scratch build, especially "CC RepeTile," which keeps Adobe/Cycore's own branding prefix. Functionally identical; rename further to taste.)*
- **Professional Features:** Masks, Track Mattes, Blend Modes, Time Stretch/Remap, Adjustment Layers, Hardware-Accelerated playback, and 4K Export (device-capability dependent).

## 3. Technology Stack & Architecture Boundaries
To guarantee real-time performance and prevent Android's Garbage Collector from causing frame drops, Fluxx strictly separates the User Interface from the Render Engine.

| Environment        | Technology                                                                           | Responsibility                                                   |
|--------------------|--------------------------------------------------------------------------------------|------------------------------------------------------------------|
| UI & State Layer   | Kotlin & Jetpack Compose (Android-only)                                              | Project state, timeline UI, touch gestures, and file I/O.        |
| Core Render Engine | C/C++ (Android NDK)                                                                  | Pixel compositing, Bezier interpolation, and effect shaders.     |
| Graphics Pipeline  | Vulkan only (v0.1+)                                                                  | Hardware-accelerated drawing and matrix transformations.         |
| Audio Engine       | Oboe (C++)                                                                           | Ultra-low latency, perfectly synchronized audio playback.        |
| JNI Bridge         | FlatBuffers (structured/infrequent) + Direct ByteBuffer / AHardwareBuffer (hot path) | High-speed numerical instruction passing between Kotlin and C++. |

**UI framework note:** Compose Multiplatform's Android target is functionally identical to Jetpack Compose (sharing the same compiler and runtime, resulting in identical performance). Plain Jetpack Compose is used instead purely to avoid carrying unnecessary Kotlin Multiplatform tooling for code-sharing capabilities the project doesn't require. Any future iOS build is planned as a separate native rebuild (utilizing Metal, Core Audio, and AVFoundation/VideoToolbox), rather than a shared-UI port.

**Graphics pipeline note:** an OpenGL ES/ANGLE fallback is deferred past v1. All 64-bit Android devices on Android 10+ guarantee Vulkan 1.1, and roughly 85% of active Android devices support Vulkan today — a second render backend isn't worth the v1 engineering cost for a performance-focused tool likely to attract newer devices anyway. Revisit if compatibility telemetry says otherwise.

**JNI bridge note:** FlatBuffers and Direct ByteBuffer are not interchangeable — they solve different problems. Use FlatBuffers where a schema and cross-language type safety matter more than raw speed (project load, effect parameter changes, undo/redo commands). Use a direct/shared ByteBuffer or AHardwareBuffer for anything touched every frame during real-time playback (pixel data, transform matrices), where serialization overhead would cost frames.

**Development Environment:** Android Studio serves as the master build environment, utilized for its mandatory JNI debugging, LLDB support, and native memory profilers. Visual Studio Code acts as the auxiliary editor—the `/app/src/main/cpp` directory is treated as an isolated workspace for writing raw C++ math and engine logic without the overhead of the Android toolchain. An agentic tool (Antigravity, Gemini CLI) can handle scaffolding and logic generation across both environments, but doesn't replace Android Studio for JNI/LLDB debugging or native memory profiling — use plan-before-execute review specifically for anything touching Vulkan or the JNI bridge, where bugs are easy to introduce and hard to catch after the fact.

## 4. Data Schema & File System
Projects are non-destructive and highly modular. Media is securely referenced via Android's scoped storage APIs.
- **File Format (.fluxx):** Project files are currently raw FlatBuffers binaries (.fluxx) — no ZIP container is used yet. The ZIP wrapper (bundling referenced assets/fonts) is deferred until later phases require embedding files like custom fonts or thumbnails alongside the instruction data. This is a deliberate deviation from the original JSON-in-ZIP design to optimize for zero-copy load and direct native bridge compatibility using FlatBuffers.
- **Asset Referencing:** The Kotlin layer acquires persistable `content://` URIs upon import. It extracts raw integer file descriptors (fd) and passes them to the C++ engine to bypass native C++ I/O permission restrictions.
  *Note: persistable URI grants behave inconsistently across OEM skins — some Samsung/Xiaomi builds revoke or mishandle grants in ways that don't reproduce on an emulator. Build a "relink this file" recovery flow into the UI from the start, and budget real device-lab testing time here specifically.*
- **Crash Recovery:** A memory-mapped Write-Ahead Log (WAL) records the user's latest interactions. If the operating system terminates the app in the background, the log instantly reapplies the unsaved command queue upon reboot.
- **Tiered Caching System:**
  - **L1 (RAM):** Dynamic limit based on detected device memory class via `ActivityManager.getMemoryClass()` / `isLowRamDevice()`, not a fixed constant. Calibrated so a ~4GB-class device (e.g., the base Galaxy A16 variant) gets a conservative ~1.5GB ceiling; 6GB/8GB-class devices scale up for a smoother scrub cache. Uses zero-copy AHardwareBuffer and an LRU algorithm for immediate ±30 frame playback.
  - **L2 (Disk):** Internal cache. Background workers silently generate MJPEG proxies for fast timeline scrubbing of high-resolution video.
  - **L3 (Source):** External storage. Asynchronous reading for final rendering and 100% zoom previews.

## 5. Mathematical Rendering Pipeline
The engine executes frame generation through a strict, immutable order of operations:

`Source → Time → Masks → Effects → Transform → Blend → Motion Blur → Composite`

*This fixed order is a deliberate simplification versus tools like After Effects, where masks and effects can interleave in a user-defined stack (an effect can target a specific mask; a mask can sit between two effects). A single global order is significantly simpler to implement — worth keeping as a documented decision so it isn't "corrected" later by assumption.*

- **Zero-Copy Decoding:** MediaCodec writes decoded frames directly to an AHardwareBuffer. Vulkan binds this buffer as a texture, allowing the GPU to process video pixels without CPU overhead.
- **Ping-Pong Effect Stacking:** Multiple effects are processed sequentially using two offscreen Framebuffer Objects (FBOs). The GPU bounces the texture between Texture A and Texture B, strictly capping memory consumption regardless of effect count.
- **Transformation Math (DAG):** Layer parenting utilizes local 3×3 matrices. The directed acyclic graph ensures child layers inherit parent transformations mathematically:

  `M_world(child) = M_world(parent) × M_local(child)`

  Composed up the parent chain, so a layer's final on-screen transform is the product of its own local matrix and every ancestor's world matrix above it. *(Reconstructed — the source document had a placeholder here; verify against your intended matrix convention, row- vs. column-major, before implementing.)*
- **Premultiplied Alpha:** The engine exclusively processes premultiplied alpha colors to reduce heavy division operations during blending to simple addition.

## 6. Timeline Interaction Engine
Translating blunt touch inputs into frame-accurate, professional edits.
- **Coordinate Translation:** Screen pixels (X_screen) are mapped to exact timeline frames (F_index) using the current temporal zoom scale (W_scale, pixels per frame) and horizontal scroll offset (O_scroll, in frames):

  `F_index = O_scroll + (X_screen / W_scale)`

  *(Reconstructed — adjust if zoom scale is defined as frames-per-pixel instead.)*
- **Magnetic Snapping:** Dragged elements (playheads, keyframes, layer boundaries) snap to high-priority targets when within a dynamically calculated physical threshold (ΔF_snap), converting a fixed pixel radius into frame-space at the current zoom level:

  `ΔF_snap = snap_radius_px / W_scale`

  *(Reconstructed — tune `snap_radius_px` per interaction type; keyframes and layer boundaries likely want different radii.)*
- **Touch Target Expansion (Fitts's Law):** Tiny visual nodes (like 6dp Bezier handles) are wrapped in invisible 48dp interaction boxes to guarantee tap reliability.
- **Unidirectional Data Flow (UDF):** The Compose UI never modifies state directly. Gestures emit actions to a ViewModel, which mutates an immutable state. Granular state hoisting guarantees that moving a single layer does not recompose the entire timeline.

## 7. Animation & Graph Editor Core

**Phase 2 Step D decision (Option B, September 16, 2026):** Transform animation is evaluated in Kotlin on the existing preview/export worker, alongside `LayerGeometry`. One LUT evaluator fills a renderer-owned six-float buffer; FlatBuffers persists tracks but no animation JNI sync or native evaluator is used. This is the approved current implementation boundary, superseding the aspirational C++ interpolation responsibility in section 3 for this step. See [keyframe checkpoint](../checkpoints/KEYFRAME_ANIMATION.md) for contracts and pending verification.
- **Sparse Execution:** Animatable properties contain an `isAnimated` boolean. If false, the engine reads a static float, bypassing interpolation math entirely to save CPU cycles.
- **Bezier Mathematics:** Temporal motion computes via the standard cubic Bezier parametric form, for control points P0–P3 and t ∈ [0,1]:

  `B(t) = (1-t)³P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³P3`

  *(Reconstructed — standard form, included since the source document had a placeholder here.)*
- **Root-Finding Optimization:** Translating abstract time (X) to absolute value (Y) utilizes a hardware-optimized Newton-Raphson root-finding algorithm, falling back to a Bisection search for extreme handles. Built-in presets map directly to memory Look-Up Tables (LUTs) for zero-math execution.
- **Spatial Auto-Orient:** Motion paths utilize Catmull-Rom Splines, forcing layers to move in smooth, curved trajectories through 2D space without breaking keyframe coordinates.

## 8. Masking, Shapes, and Text
- **Stencil Buffer Masking:** Hard-edged masks bypass textures entirely. The GPU writes mask paths as binary values to the Stencil Buffer; the fragment shader discards outside pixels at zero hardware cost.
- **Feathered Alpha Mattes:** Masks with feathering render to an offscreen grayscale 8-bit texture, apply a fast Dual Kawase blur, and multiply during the final composite.
- **Signed Distance Fields (SDFs):** Basic shape primitives are drawn as single quads. Fragment shaders utilize SDF math to generate infinite-resolution strokes and drop shadows without generating complex geometry.
- **Dynamic Triangulation:** Pen Tool paths invoke a CPU-level Ear Clipping algorithm to tessellate Bezier curves into physical GPU triangles in real-time.
- **Text Engine Pipeline:** FreeType and HarfBuzz generate a dynamic Glyph Texture Atlas. Text animators (Range Selectors) apply independent transformation matrices to each individual character quad.

## 9. Effect Optimizations
Desktop algorithms are heavily refactored for ARM architectures.
- **Dual Kawase Blur:** Replaces standard Gaussian Blur algorithms via highly efficient downsample/upsample passes.
- **1D LUT Color Processing:** Effects like Curves and Tint bypass per-pixel mathematical calculations. The CPU generates a tiny 256×1 pixel LUT upon parameter change; the shader directly maps original pixels to the new LUT.
- **Linear Color Space:** Blurs, Additive Blends, and Glows execute in Linear color space via `VK_FORMAT_R8G8B8A8_SRGB`, giving free hardware sRGB↔linear conversion at the point of sampling/writing.
  *Precision note: this is 8 bits, which is fine for most effects but can band on ones that accumulate a lot of light — Glow especially, or repeated Add-blend passes. Worth a spike test before committing; if banding shows up, a 16-bit float intermediate buffer for just that effect is a cheap, contained fix.*

## 10. Background Export & Render Queue
- **Foreground Service Type:** Rendering triggers an Android ForegroundService declared with `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (Android 15+/API 35+), paired with `PowerManager.PARTIAL_WAKE_LOCK`, to keep the CPU spinning and prevent the Low Memory Killer from terminating the export when the device is minimized or locked.
- **OS Time Budget:** The `mediaProcessing` type is capped at roughly 6 hours out of every rolling 24, shared across all of the app's mediaProcessing services. Implement `Service.onTimeout()` and call `stopSelf()` within the grace period, or the OS raises an ANR. Below Android 15 this type doesn't exist — fall back to an untyped/best-effort foreground service on those OS versions and expect less OS cooperation there.
- **Play Console Declaration:** Apps targeting Android 14+ must declare foreground service types and use case on the Play Console app content page, including a demonstration video, before release. Budget time for this in the release checklist, not at submission time.
- **Hardware Encoding:** Vulkan paints directly into a MediaCodec surface. A secondary thread runs MediaMuxer to synchronize video packets with Oboe PCM audio, writing the final H.264/HEVC stream.
- **Strict Memory Throttling:** The engine hard-caps "Frames in Flight" (e.g., maximum 3). The C++ thread automatically blocks if the encoder buffers fill up, aggressively preventing Out of Memory (OOM) crashes on 4K renders.
- **Resumable Chunking:** Video renders in discrete segments (e.g., 300-frame blocks). Upon an OS crash, power failure, *or a `mediaProcessing` timeout*, the engine skips completed chunks, resumes rendering, and seamlessly concatenates the segments — treat the OS timeout as another checkpoint trigger, not just crashes.

## 11. Memory Management & Object Pooling
- **Zero-Allocation Playback:** Once the playhead initiates playback, neither the Kotlin UI nor the C++ engine constructs new objects on the hot path.
  *(The original phrasing referenced a `new` keyword — Kotlin doesn't have one; object construction there is just `ClassName()`. The actual rule is language-agnostic: no allocation during playback, on either side of the JNI bridge.)*
- **Flyweight Pattern:** Transformation matrices, float arrays, and instruction wrappers are pre-allocated during project load. The engine strictly mutates and recycles these objects, locking the memory footprint in place.
- **Audio Synchronization:** The video render loop checks the Oboe thread's `currentAudioTimestamp` immediately prior to drawing. Visual frames are aggressively dropped to maintain strict synchronization with the audio clock.

## 12. Version Control & Workspace Structure
The project utilizes a dual-build system managed through Android Studio, with a strict local exclusions strategy to maintain a clean Git repository.
- **Repository Layout:**
```
/fluxx-android
├── /app (Kotlin Compose UI & Project Data)
├── /core-engine (C++ NDK Source, Vulkan, Oboe)
├── /libs (Pre-compiled static binaries)
├── .gitignore
└── build.gradle.kts
```
- **.gitignore Strictness:** All C++ compilation outputs (`.cxx`, `.so`, `.o`), CMake caches, and Gradle build folders are aggressively blocked from version control.
- **Heavy Asset Isolation:** A designated `/local_testing_media` folder is established for 4K video, WAV files, and fonts. This folder is ignored by Git, ensuring the repository remains lightweight and push/pull times remain instantaneous.
- **Git LFS:** Consider Git LFS for `/libs` if the precompiled static binaries grow large — same rationale as isolating heavy media assets, keeps clone times fast.
