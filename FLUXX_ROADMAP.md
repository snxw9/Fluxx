# Fluxx Roadmap: v0.1 → Full Vision

> Companion to `FLUXX_SDD.md`, which describes the complete target architecture. This document defines what actually gets built first, in what order, and why.

## Why a separate v0.1

The full SDD scope — dual Graph Editor, 15 effects, masks/mattes/blend modes, 9 layer types, 4K export, resumable chunked rendering, all on a from-scratch Vulkan/C++/Oboe engine — is normally a multi-year build for a small team of specialists. Cutting it down for v0.1 doesn't lower the ambition; it's the same destination with a route that surfaces problems in the Vulkan/JNI/audio-sync core in week three instead of month eight, when there's nothing else built yet to lose.

## v0.1 Scope

**In:**
- Single composition, no nesting
- 4 layer types: Video, Image, Solid, Text
- Transform animation only (position, scale, rotation, opacity) via Bezier keyframes + one easing preset
- 2 effects: Curves (cheap, LUT-based) + Blur (GPU-heavy, proves the ping-pong FBO stack)
- Hard-edge stencil masks only (no feathering)
- Normal blend mode only
- 1080p export, single-shot (not chunked/resumable)
- Timeline: layers, drag-to-trim, scrub, zoom/pan

**Deferred to v0.2+:** everything else in `FLUXX_SDD.md` — remaining effects, feathered mattes, track mattes, other blend modes, nested comps, dual Graph Editor, motion paths, text range selectors, 4K export, resumable chunked export, WAL crash recovery.

## Defaults (change any of these — none are locked in)

- **Jetpack Compose (Android-only).** Chosen because Compose Multiplatform's Android target uses the identical Compose compiler and runtime, resulting in no performance differences on Android. Since any future iOS release is planned as a separate native rebuild (utilizing Metal, Core Audio, and AVFoundation/VideoToolbox) rather than a shared-UI port, there is no code-sharing justification for carrying Kotlin Multiplatform project structure overhead.
- **Min SDK 29 (Android 10), target latest (35/36).** API 29 is the floor for the guaranteed Vulkan 1.1 baseline on 64-bit devices. Devices on 29–34 lose the `mediaProcessing` foreground service type (added in API 35) and fall back to best-effort background export.
- **First two effects: Curves + Blur** — one proves the cheap LUT path, one proves the expensive FBO ping-pong path. Which effect comes third onward in v0.2 is genuinely an open call.

## Phased Build Order

### Phase 0 — Walking Skeleton
No user-facing features. Goal: confidence the hard technical bets actually work on real hardware before anything gets built on top of them.
- [x] Vulkan instance/device/swapchain init, rendering a solid color quad at target fps on a real mid-tier test device
- [x] JNI round trip (Kotlin → C++ → Kotlin) with no crashes
- [x] One MediaCodec-decoded frame bound zero-copy as a Vulkan texture via AHardwareBuffer — the riskiest claim in the SDD, prove it early
- [x] A `mediaProcessing`-typed foreground service running a fake export that survives the app being backgrounded

**Done when:** all four run reliably on both a ~4GB budget device and a flagship. (Phase 0 Complete)

#### Phase 0 retrospective
What actually made this phase hard, for continuity into future work on this codebase:
- The single most recurring bug class was releasing a buffer/resource across a producer-consumer boundary (Kotlin decode thread → native staging → GPU render) before the actual consumer was confirmed done — this showed up at least three separate times in different forms (ImageReader release timing, a generation-counter race, an unacknowledged-drop deadlock). Treat any new cross-boundary handoff with the same suspicion by default.
- Never let two independent threads issue Vulkan calls on the same device/queue without explicit coordination — established as a hard rule early in Phase 0, keep enforcing it as more rendering code gets added.
- A feature isn't actually done until there's a way to trigger and verify it end-to-end — the export service was fully correct but had no UI hook to test it, caught only after implementation was reported complete.
- Plan review isn't the same as implementation verification — Step 2's swapchain/render pass/command buffers passed three rounds of plan review while still being stub functions underneath; this wasn't caught until Step 3 needed them to actually work. Confirm "seen it happen on the device," not just "implemented and logs say so," before treating something as done.

### Phase 1, Step A (Complete)

Apply position/scale/rotation/opacity to the video layer, controllable live from the UI. Builds directly on the existing Step 3 rendering — no changes to the decode/staging pipeline.

Requirements:
- A minimal Layer data class in Kotlin (positionX/Y, scaleX/Y, rotationDegrees, opacity, plus the asset URI) — doesn't need to persist yet, just exist as the source of truth the UI writes to and the renderer reads from
- Pass the current transform as a 2D transform matrix via a Vulkan push constant to the video pipeline's vertex shader — apply it to the full-screen triangle's procedural positions (gl_VertexIndex-based), don't switch to a vertex buffer for this
- Opacity: enable alpha blending on the video pipeline (currently opaque), multiply the sampled color's alpha by the opacity value in the fragment shader — with only one layer against the black clear color, this will visibly fade toward black, which correctly confirms the mechanism even before there's a second layer to see through to
- Compose UI: sliders for position X, position Y, scale, rotation, and opacity, laid over the existing playback view, writing directly to the Layer data class on change — no drag/touch gesture handling yet, sliders are enough to prove the pipeline
- Push the updated transform to native on each slider change via JNI — this isn't a hot per-frame path from Kotlin's side, the native side still applies it every frame via the push constant

Definition of done: moving each slider visibly and immediately affects the video's position, scale, rotation, and transparency on screen, with no regression to playback stability, frame rate, or the crash-free behavior already confirmed across codecs and aspect ratios.
- **Verified aspect-fit test matrix:** Confirmed that cold-starting and swapping 1:1, 16:9, 9:16, and 4:3 videos all correctly "contain" (aspect-fit) within the fixed 9:16 comp bounds without stretching. This produces the mathematically correct top/bottom letterboxing or left/right pillarboxing for each aspect ratio, and updates seamlessly via `onFormatReady` without requiring a manual UI nudge.

#### Phase 1 Step A retrospective
The recurring bug pattern in this phase was duplicate/stale sources of truth silently drifting apart — hardcoded comp dimensions duplicating C++ constants, a stale hardcoded aspect ratio left over from an earlier fix attempt, two separate matrix-initialization call sites, and a command-buffer array sized against a since-emptied framebuffer list.
**Standing check for future plan reviews:** Grep for duplicate sources of truth whenever a value's location or ownership changes during a refactor.

### Phase 1 — One Layer, One Frame
- Single image/video layer with transform (position/scale/rotation/opacity)
- Compose preview shell
- Minimal `.fluxx` save/load for one layer
- Export that single composited layer to MP4

**Done when:** you can import a video, nudge it around the frame, and export a file that plays back correctly.

### Phase 2 — Timeline + Multiple Layers
- Multiple layers with z-order
- Timeline UI: drag, trim, scrub, playhead
- Text layer type
- Bezier keyframes on transform properties, one easing preset

**Done when:** a simple multi-layer, multi-keyframe composition previews and exports correctly.

### Phase 3 — First Real Effects + Masks
- Ping-pong FBO effect stack with Curves + Blur
- Hard-edge stencil masks

**Done when:** both effects apply correctly, stack together, and a mask correctly clips a layer.

### Phase 4+ — Everything Else
Sequence the remaining `FLUXX_SDD.md` scope from here: dual Graph Editor, remaining effects, feathered mattes, track mattes, other blend modes, nested comps, motion paths, text range selectors, 4K export, resumable chunked export, WAL crash recovery. Roughly in that order of dependency — but revisit priority once v0.1 is in hand and it's clearer what actually matters for using the tool.

## Pre-Coding Checklist

- [ ] Repo scaffold per SDD Section 12
- [ ] Effects renamed away from Adobe's naming (already reflected in the updated SDD)
- [ ] Test device matrix: at least one real ~4GB device + one flagship
- [ ] Decide on crash reporting/telemetry now — cheap to add at v0.1, painful to retrofit into a hand-tuned zero-allocation hot path later
- [ ] Git LFS for `/libs` if precompiled binaries grow large
