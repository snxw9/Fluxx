# TEXT PERF 11: multi-layer text cost, resume/rotation bugs, imported-font latency

Branch `text-perf-11` (from master `20671a6`). Device: Galaxy A16 (SM-A165F, Helio G99, Mali-G57 MC2), Android 16. All numbers come from the **debug APK** (debuggable, JIT-only), installed with `adb install -r`. Measurements were run without tapping the UI. Device settings changed during the runs (stay-on, rotation, `wm size`) were restored; see [Device settings](#device-settings).

Commits:

| Commit | Content |
|---|---|
| `affdcc2` 11a | Instrumentation only: per-layer counters, multi-layer harness, lifecycle, rotation and imported-font tests. |
| `0b3de93` 11a follow-up | Fair fixture spacing, stacked/imported variants, replay phase. |
| `01d598d` 11b | Merged text batches, shared track memo. |
| `42f4dc4` 11c | Rotation/resize keep the project; playback clock gate; streaming test; tier catalog retention. |
| `53bf4dc` 11c follow-up | The canvas keeps height in short windows. |
| `bc19e5c` 11d | Imported-font caches, keep-warm, imported settle, idle tier and JIT warm-up. |
| `1d2ccb3` 11c follow-up | The swapchain follows the reported surface size (stale extent after rotation). |
| `fcfd386` 11d follow-up | Tier warm-up sent from settled frames only (fixes the F1 allocation regression the suite caught); warm counters; `TierWarmUpTest`. |
| 11e | This document. |

## 11a. Profile (before any fix)

### How it was measured

- **Fixtures** (`TextMultiLayerFixture`). The text is the F2 representative line `AV office gy 0123456789` (22 glyphs).
  - `single30`: **one layer** holding that line 30 times (the PERF 10 baseline).
  - `layers30` / `layers60`: **30 / 60 separate layers**, one line each.
  - Each row sits where the single layer draws its line: the native line height of the font, with the row position keyframed with the same times and easing as the size. Both cases therefore cover the same area.
  - The first run spaced rows for 40 px, so at 300 px the layers overdrew each other and the GPU time was not comparable. It was discarded.
  - Three kinds:
    - `plain`: 40 px, no animators;
    - `animated`: the F2 representative stack (8 animators × 4 selectors × 13 properties, size 8 → 72 px);
    - `size`: font size keyframed **30 → 300 px over 3 s** on every layer.
- **Modes and frames.**
  - The modes are static, scrub (seeded random seeks) and playback (consecutive 60 fps frames).
  - Each mode runs 120 warm-up frames and 300 measured frames. Scrub and playback stay inside the 3 s span for `size`.
- **Counters.** All are diagnostic only.
  - Kotlin stages (PERF 10), plus text layers drawn, mid-frame flushes and tier JNI time.
  - Native stages (PERF 10), plus text layers, native mesh build, re-collect+pack time and count, atlas upload, tier calls, glyph-placement lookups and (from 11b) merged flushes.
  - Per-frame Kotlin (`Debug.getThreadAllocCount`) and native `new` counts.
  - Window deltas of shaping, rasterisation, uploads and generation.
- **Command.**

  ```
  adb shell am instrument -w -r -e class com.fluxx.android.render.TextPerfProfileTest#multiLayerStages \
    -e textRunLabel <label> com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```

  Narrow a run with `-e perfCases layers30-size,…` and `-e perfModes playback`.
- **Profile.** simpleperf: `simpleperf record --app com.fluxx.android.debug -g --duration 10 -f 2000` during `layers30-animated` playback.
- **Thermal.** Status was 0 in every mode.

### Before: frame and batch cost (ms, p50 / p95, playback)

| Case | Frame | Batch submit + fence wait | GPU batch (sum) | Submits per frame | Evaluate | textLayer + mesh |
|---|---|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 | 8.8 / 13.5 | 2.8 | 1 | 1.7 | 3.8 |
| **layers30-plain** | **23.6 / 29.3** | 12.5 / 16.1 | 6.3 | **8** | 2.1 | 4.5 |
| layers60-plain | 50.1 / 56.4 | 28.6 / 33.0 | 11.6 | 15 | 4.0 | 9.7 |
| single30-animated | 16.6 / 23.5 | 4.7 / 7.6 | 2.6 | 1 | 8.9 | 1.7 |
| **layers30-animated** | **45.2 / 58.7** | 19.6 / 23.9 | 8.0 | **8** | **18.6** | 3.5 |
| layers60-animated | 65.6 / 90.6 | 19.6 / 32.2 | 9.7 | 15 | 32.0 | 6.4 |
| single30-size | 16.7 / 18.7 | 8.6 / 13.4 | 6.2 | 1 | 1.7 | 3.9 |
| **layers30-size** | **32.2 / 35.5** | 19.8 / 22.8 | 11.0 | **8** | 2.1 | 4.9 |
| layers60-size | 53.3 / 58.1 | 30.3 / 34.2 | 14.7 | 15 | 4.1 | 9.9 |

Scrub and static show the same pattern. The full tables are under [Before / after](#before--after-a16-debug-apk).

### Per-layer mean cost before (30 layers, playback, ms per layer)

| | plain | animated | size |
|---|---|---|---|
| Batch submit + wait | 0.42 | 0.63 | 0.66 |
| Kotlin `flush()` (mid-frame submits, inside "other") | 0.44 | 0.63 | 0.68 |
| Animator evaluation | 0.06 | **0.65** | 0.07 |
| Tier policy (incl. evaluation) | 0.09 | 0.68 | 0.11 |
| `textLayer` JNI | 0.14 | 0.12 | 0.16 |
| ↳ native mesh build | 0.11 | 0.10 | 0.14 |
| Batch record | 0.03 | 0.02 | 0.03 |
| Batches per layer | 0.27 | 0.27 | 0.27 |

### Verdict: the cost is a submit + fence wait per 4 layers, then per-layer animator track evaluation

1. **Batch flush and fence per 4 layers (all cases).**
   - Kotlin flushes every 4 entries (the four-entry batch/draw contract).
   - Before this pass, each flush recorded a full render pass on the 1080×1920 target (load + store), submitted it and waited for its fence. 30 layers cost **8 round trips per frame** and 60 layers 15.
   - The wait is 12–30 ms of a 24–53 ms frame. The GPU adds about 0.6 ms per extra pass, just for the target load/store: GPU batch time is 6.3 ms for 30 layers against 2.8 ms for the same glyphs in one layer.
   - This is the top cost in every multi-layer case.
2. **Per-layer animator overhead (animated only).**
   - Thirty layers evaluate in 18.6 ms against 8.9–10.9 ms for the same 720 glyphs in one layer.
   - simpleperf on the render thread (30 animated layers): keyframe tracks account for **23%** inclusive (`AnimatableProperty1D.evaluate` → `KeyframeEvaluator.evaluate1D`, with `FrozenList.get` and `last()`), and colour interpolation for another **8.5%**.
   - Each layer evaluates its own ~650 animator, selector and layer tracks per frame. With duplicated layers they are identical.
   - Selector coverage (31%) scales with the total glyph count, not the layer count.
3. **Not costs.** Measured, not assumed:
   - `textLayer` JNI and mesh build: 0.1–0.16 ms per layer.
   - Tier policy: about 0.1 ms per layer.
   - Shaping, rasterisation and atlas uploads in the window: all Δ 0.
   - Kotlin allocations: 0.

### The specific checks

- **Does each text layer trigger its own batch submit and fence wait?** One submit per **4** layers, not per layer, with a fence wait each (above). See 11b for the fix and why it stays within the contract.
- **Does animating font size re-layout or re-shape per frame per layer?** **No.**
  - In the `size` windows, `shapingCpuNs` and `rasterizations` Δ are 0.
  - The mesh scales advances and quads by `size / upem`.
  - Layouts are keyed by (font, shaping document, ligature mode), with no size in the key. The 30 layers with equal text share **one** layout handle.
  - Re-scaling rather than re-shaping is already what happens, and it is exact.
- **Do tier switches cause per-layer repacks, collection or allocation spikes?** No; they are already batched across layers.
  - The tier policy takes the per-handle maximum and makes one `textTiers` JNI call per change frame. Hysteresis is 15%.
  - In 300 playback frames of `layers30-size` (1.7 loops of 30 → 300 px) the counts were:
    - 4 tier calls, 4 re-collects and 4 placement lookups (one shared layout);
    - 0 atlas uploads (the warmed atlas retains every tier);
    - 92 native allocations, all on those 4 frames (the PERF 10 tier-switch finding).
  - Collect+pack costs at most 1.2 ms (p95 0 in playback).
  - Nothing to change here.
- **Per-layer allocations, JNI crossings, lock contention, font/face lookups, GPU resources.**
  - Kotlin allocations Δ 0 in every window.
  - Native allocations: 0, except the tier-switch frames above.
  - JNI per layer: 1 `textLayer` call, plus 1 `flush` per 4 layers.
  - Tier mutex wait: 1–2 µs in total per 300-frame window.
  - No font or face lookups per frame: no shaping or rasterisation, and one shared layout.
  - No per-layer GPU resources: one shared vertex/index buffer and atlas, resized only during preparation.

## 11b. Multi-layer fixes (in order of impact)

### 1. One GPU submit per frame for text layers (`01d598d`)

**Change.** `VulkanRenderer::flushBatch` behaves as follows.

- If the batch Kotlin flushes holds **only text entries**, it now moves them to a pending list instead of recording, submitting and waiting.
- The next real submit records everything pending in draw order, in **one** render pass: pending text first, then the current batch. A real submit happens on:
  - any flush whose batch contains a raster (video, image or solid) entry;
  - `finish`;
  - running out of merged mesh storage.

**Why this stays inside the batch/draw contract.**

- The Kotlin-visible API is unchanged: `textLayer` / `layer` / `flush` / `finish` arguments, the 4-entry limit (native still rejects a fifth entry), and Kotlin still flushing every 4 entries.
- The contract's purpose is that after `flush` returns, nothing Kotlin owns is still in use by the GPU.
  - A **text** entry owns nothing Kotlin can release. Its mesh is built into native storage and its matrix copied when `textLayer` returns; the modifier buffer is read only during that call.
  - Every batch containing a Kotlin-owned resource (a decoder image or `HardwareBuffer`) still submits and **waits for its fence before `flush` returns**, together with all pending text, and so does `finish`.
- The fence wait is therefore kept everywhere it protects a resource. **Please review this reading of the contract**; it is listed under Deviations.

**Storage.**

- Merged batches need mesh room for every text layer since the last submit.
- `TextRenderer::reserveFrame()` runs during preparation, after the previous submit's fence. It reserves last frame's demand, bounded to **16,384 glyphs** (8,192 on low-RAM devices), about 4.5 MB for vertices and indices.
- `meshFits()` submits early beyond that, so correctness never depends on the reserve.
- Steady-state frames neither allocate nor split (native allocation Δ 0).

**Bit-identity.** `TextMergedBatchParityTest` (device, passed) renders every frame twice, with merging on and off (`TextDebugBridge.mergeTextBatches`), and compares full RGBA readbacks. It covers:

- 30 layers plain, animated and size, and 60 layers animated, at 6 times each, including tier switches;
- 30 animated layers with translucent solid layers interleaved, so raster entries force real submits between merged text.

It also asserts **1** submit per frame with merging against **8** without.

### 2. Shared keyframe-track memo (`01d598d`, `TrackMemo`)

**What it does.**

- A track is a pure function of (track, time), so equal tracks now share one slot per prepared project.
- The first layer evaluated at a time computes the value; layers with an equal track read it back.
- Slots are resolved during preparation, so per frame a slot costs one comparison of the last time and allocates nothing.

**Equality and confinement.**

- Equality is the data-class equality of the track: static value, keyframes with their times, values and easing. Floats compare as `Float.equals`, so `-0` and `+0` stay apart.
- That covers everything the value depends on.
- The memo is confined to the renderer's thread. Other users (UI bounds, the warm-up thread) get a private memo.

**Coverage.** Selector tracks (20 per selector), animator `enabled`/`anchorGrouping` and property values (scalar, vector, colour), and the layer's size, tracking, leading, baseline, fill, stroke colour and stroke width.

**Bit-identity.**

- `PreparedTextAnimationGoldenTest` still produces the four PERF 10 hashes.
- The new `sharedTrackMemoIsBitIdenticalAcrossLayers` evaluates three layers per F2 stack on one memo and compares full records, bounds and geometry, byte for byte, with private memos. The three layers are two equal ones and one with a different start track; they are evaluated interleaved, one at a different local time, with repeated and backward times.
- The host zero-allocation test still passes.

**Effect.** `layers30-animated` evaluation fell from 15.0 to 11.7 ms (the single layer takes 9.6 ms), and `layers60-animated` from 27.2 to 18.7 ms.

### Candidates checked and not changed

| Candidate | Evidence |
|---|---|
| Re-shaping or re-layout on size change | `shapingCpuNs` / `rasterizations` Δ 0; layouts are size-independent and shared by equal text. |
| Tier-switch repacks per layer | One collect per tier-change frame for all layers; 0 uploads; hysteresis already 15%. |
| Per-layer JNI or allocations | 1 call per layer, 0.1–0.16 ms; Kotlin Δ 0. |
| Vertex-shader modifiers, animators on the second core, double-buffered batches | Not the top multi-layer cost after fixes 1–2. They remain next steps for the evaluation floor (below); they would change the batch/draw contract or the threading model, so they need your decision. |

## Before / after (A16, debug APK)

Before is the 11a build. After is the final code paths (round-two run on `bc19e5c`'s code). Each cell is frame p50 / p95 in ms. Batch wait is submit + fence. GPU is the summed text batches.

### Playback

| Case | Frame before → after | Evaluate | Batch wait p50 / p95 | GPU | Submits |
|---|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 → **16.6 / 18.1** | 1.7 → 1.7 | 8.8 / 13.5 → 8.7 / 13.4 | 2.8 → 2.8 | 1 → 1 |
| layers30-plain | 23.6 / 29.3 → **16.5 / 18.1** | 2.1 → 2.2 | 12.5 / 16.1 → 5.4 / 7.1 | 6.3 → 3.0 | 8 → 1 |
| layers60-plain | 50.1 / 56.4 → **22.5 / 24.8** | 4.0 → 3.7 | 28.6 / 33.0 → 6.6 / 7.5 | 11.6 → 3.7 | 15 → 1 |
| single30-animated | 16.6 / 23.5 → **19.7 / 26.2** (1) | 8.9 → 9.6 | 4.7 / 7.6 → 5.9 / 8.9 | 2.6 → 3.0 | 1 → 1 |
| layers30-animated | 45.2 / 58.7 → **22.4 / 27.5** | 18.6 → 11.7 | 19.6 / 23.9 → 5.9 / 8.6 | 8.0 → 3.1 | 8 → 1 |
| layers60-animated | 65.6 / 90.6 → **32.9 / 35.6** | 32.0 → 18.7 | 19.6 / 32.2 → 6.8 / 8.3 | 9.7 → 3.8 | 15 → 1 |
| single30-size | 16.7 / 18.7 → **17.0 / 18.6** | 1.7 → 1.7 | 8.6 / 13.4 → 8.1 / 9.3 | 6.2 → 6.5 | 1 → 1 |
| layers30-size | 32.2 / 35.5 → **18.7 / 21.2** | 2.1 → 2.0 | 19.8 / 22.8 → 8.4 / 12.4 | 11.0 → 6.5 | 8 → 1 |
| layers60-size | 53.3 / 58.1 → **29.1 / 31.0** | 4.1 → 4.0 | 30.3 / 34.2 → 10.4 / 11.7 | 14.7 → 6.7 | 15 → 1 |

**Reproducibility.** A third run on the final build (`a16-perf11-final`, playback, frame p50 / p95) gave:

- single30-plain 16.5 / 18.5; layers30-plain 16.5 / 18.1; layers60-plain 23.2 / 26.8;
- single30-animated 18.0 / 26.7; layers30-animated 24.1 / 28.6; layers60-animated 35.4 / 38.6;
- single30-size 17.5 / 19.0; layers30-size 18.9 / 20.8; layers60-size 27.5 / 30.7.

Every case had 1 submit per frame. The two after-runs agree within about ±2 ms at p50 and ±3 ms at p95.

(1) The single layer has no layer-count change. Its "before" run measured evaluation at 8.9 ms, the first 11a run at 10.9 ms and the round-one after run at 10.6 ms. This is run-to-run variation (±1–2 ms p50, ±3 ms p95, as PERF 10 noted), not a regression.

### Scrub

| Case | Frame before → after | Evaluate | Batch wait p50 / p95 | Submits |
|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 → 16.5 / 18.1 | 0.7 → 1.8 | 13.0 / 13.8 → 7.9 / 9.6 | 1 → 1 |
| layers30-plain | 27.0 / 32.9 → **16.6 / 18.2** | 2.4 → 2.4 | 14.0 / 18.4 → 4.5 / 6.9 | 8 → 1 |
| layers60-plain | 45.8 / 54.4 → **22.4 / 25.0** | 4.1 → 3.6 | 25.5 / 31.6 → 6.6 / 7.6 | 15 → 1 |
| single30-animated | 17.0 / 23.9 → 16.5 / 22.0 | 8.0 → 7.5 | 5.6 / 10.1 → 5.1 / 9.7 | 1 → 1 |
| layers30-animated | 49.4 / 60.5 → **23.7 / 28.0** | 20.8 → 12.1 | 20.2 / 26.0 → 6.5 / 9.6 | 8 → 1 |
| layers60-animated | 88.6 / 99.8 → **33.5 / 36.0** | 36.4 → 18.7 | 35.9 / 42.3 → 7.5 / 9.7 | 15 → 1 |
| single30-size | 17.8 / 35.3 → 17.0 / 34.4 | 1.0 → 1.0 | 7.9 / 10.9 → 7.8 / 10.3 | 1 → 1 |
| layers30-size | 34.6 / 41.9 → **19.6 / 21.9** | 2.1 → 2.0 | 20.8 / 27.2 → 8.4 / 9.8 | 8 → 1 |
| layers60-size | 48.4 / 57.0 → **26.1 / 30.7** | 4.0 → 3.8 | 28.0 / 33.2 → 8.9 / 11.4 | 15 → 1 |

### Static

| Case | Frame before → after | Batch wait p50 / p95 | Submits |
|---|---|---|---|
| layers30-plain | 21.0 / 26.5 → **16.6 / 18.0** | 10.5 / 14.3 → 6.8 / 8.4 | 8 → 1 |
| layers60-plain | 37.3 / 42.9 → **21.8 / 23.3** | 19.9 / 24.3 → 6.8 / 9.8 | 15 → 1 |
| layers30-animated | 30.4 / 34.3 → **16.5 / 18.8** | 18.5 / 22.2 → 7.2 / 11.4 | 8 → 1 |
| layers60-animated | 54.7 / 62.1 → **23.9 / 25.5** | 33.8 / 40.0 → 7.9 / 10.9 | 15 → 1 |
| layers30-size | 29.2 / 33.2 → **17.9 / 19.3** | 19.0 / 22.3 → 8.5 / 9.6 | 8 → 1 |
| layers60-size | 49.1 / 54.6 → **26.1 / 27.9** | 30.5 / 34.4 → 10.6 / 12.1 | 15 → 1 |

The single-layer static rows are unchanged (16.5 / 18.0–18.2).

### Per layer after (30 layers, playback, ms per layer)

| | plain | animated | size |
|---|---|---|---|
| Batch submit + wait | 0.19 | 0.19 | 0.28 |
| Kotlin "other" (mid-frame flushes) | 0.03 | 0.01 | 0.02 |
| Evaluation | 0.07 | 0.38 | 0.06 |
| Batches per layer | 0.03 | 0.03 | 0.03 |

### Targets: 30 layers, p95 under 16.6 ms

- **plain and size: not met at p95, but at the single-layer floor.**
  - 30 layers now run like one layer: plain is 16.5 / 18.1 against single 16.6 / 18.1, and size is 18.7 / 21.2 against single 17.0 / 18.6.
  - p50 sits at the 60 Hz pacing (16.5 ms).
  - The p95 of 18–21 ms is the same floor the single 30-line layer has on this phone. The batch fence includes waiting behind the paced present of the previous frame (PERF 10 finding), and present pacing is not text work.
- **Size animation (the reported "lags badly and skips").** 30 layers went from 32.2 / 35.5 to **18.7 / 21.2**, and 60 layers from 53.3 / 58.1 to 29.1 / 31.0. The *skips* also had a separate cause, the streaming test; see 11c Part 2.
- **Animated (F2 stack on every layer): not reachable on the debug APK.**
  - 30 layers run at **22.4 / 27.5**, against 19.7 / 26.2 for the same glyphs in one layer.
  - The floor is evaluation:
    - about 9.6 ms for 720 glyphs (as for one layer);
    - about 2 ms of remaining per-layer evaluation (selector setup per layer);
    - mesh about 2 ms;
    - waits about 6 ms.
  - 60 layers: 32.9 / 35.6 ms, floor about 18.7 ms of evaluation.
  - Going lower needs the PERF 10 next steps (animators on a second core, vertex-shader modifiers) or a non-debuggable build. See Still open.

## 11c. Lifecycle

### Part 2: background and resume during playback

**Where the clock lives.**

- `PcmPlayback`: the AudioTrack playback head. The preview worker (`PreviewController`) reads `positionUs` each frame.
- `PreviewController` itself is unchanged.

**What happens on Home.** Measured, not assumed:

- `MainActivity.onPause` pauses the preview, and its playback and clock are closed.
- `surfaceDestroyed` closes the controller and its renderer. That destroys the Vulkan device, the atlas and, before this pass, the sharp-tier glyph catalog.
- On return a new controller and renderer start at the editor's playhead.

**Reproduction without tapping.**

- The `PreviewLifecycleTest` harness wires a preview exactly as `MainActivity` does.
- `EditorLifecycleTest#homeDuringPlaybackInTheEditor` drives the **real** `MainActivity` and `EditorScreen` with an in-memory 30-layer size-animated document:
  - `PreviewController.play()` (what the Play button calls);
  - a real `input keyevent KEYCODE_HOME`;
  - 4 s in the background;
  - return to the task, then Play;
  - the editor playhead is sampled every 10 ms.

**Results** (11a app, 30 stacked layers):

| | Playhead during the 4 s stop | After return: first update after Play | Playhead jump at that update | Largest jump |
|---|---|---|---|---|
| Play 2 s after return | frozen at 2.166 s once the pause landed (1) | 356 ms | +100 ms | 133 ms |
| **Play immediately after return** | frozen at 2.100 s (1) | **1,277 ms** (1,194 ms stall) | **+733 ms** | 733 ms |
| Immediately, imported variable font | frozen at 2.100 s (1) | 754 ms | +266 ms | 266 ms |

(1) Frames already in flight when Home is pressed still report their positions (≤ 100 ms) before the pause lands; after that the playhead does not move for the whole 4 s.

**Root causes, with evidence.**

1. **The playhead does not advance while the app is stopped.** The pause holds it, and the 4 s gap shows no movement. The *jump* comes after Play.
2. **The clock started before the first frame of a playback was rendered.**
   - After a return, that first frame is cold: Vulkan init, text preparation, tier waits. It took 1.2 s.
   - The AudioTrack head ran during it, so the first presented frame was 733 ms ahead. A short clip ends in one or two such frames ("skips to the end").
3. **Slow frames counted as seeks.**
   - `renderPreview` decided "streaming" (playback) from the end-to-end time between frames (< 100 ms) and at most 3 frames of progress.
   - Any frame over about 96 ms, or playback under 20 fps (30 layers with size animation, a renderer rebuilt after Home, imported fonts), looked like a seek.
   - Each such frame then **blocked for up to 1.5 s** waiting for pending sharp tiers while the audio clock ran on. This is the "lags, stutters and skips" mechanism during playback itself.
4. **The rebuilt renderer started with no sharp tiers.** The tier catalog was held by a weak pointer that the destroyed renderer owned, so every 96/192/384 ppem glyph was rasterised again after each return.

**Fixes.**

- **`PlaybackGate`.** The track is filled immediately but plays only once a frame rendered after `play()` has been presented (`PreviewFrames`, counted in `renderPreview`). There is a 2 s safety hold, so audio never waits forever. Until then the position is the start position.
- **Streaming test.** It now uses the gap between the end of the previous call and the start of this one (< 100 ms), and accepts forward progress up to the elapsed wall time plus 3 frames. Seeks still settle and wait for sharp tiers; playback never blocks.
- **Tier catalog retained across renderers.** Memory stays within its own unpinned budget (16 MiB, 8 MiB on low-RAM devices).
- **Imported tiers** are cached across renderers (11d).

**After** (final code, Play immediately after return, 30 stacked layers): the first update arrives at 684 ms and the **playhead moves +33 ms** (one frame) against +733 ms. The largest jump is 133 ms against 733 ms, and frames run about 50 ms against 90 ms (merged batches). With the imported font: +33 ms against +266 ms.

**Test.** `PlaybackGateTest` (JVM) runs PcmPlayback's clock arithmetic with a fake clock and a fake head. It proves:

- the playhead stays fixed across a 60 s stopped interval;
- it does not jump during a 900 ms cold first frame after the return;
- it advances normally once that frame is presented;
- it is released by the hold if no frame is ever presented;
- paused frames drawn before Play do not open the gate.

**"Skips to the end" itself was not reproduced end to end.** The clip in the repro is 12 s. The measured 733 ms jump from one cold frame, and the up-to-1.5 s blocking per misclassified frame, are the mechanisms. Both are fixed and measured above.

### Part 3: rotation and split screen closed the project

**Reproduction.** `EditorLifecycleTest#rotationResizeAndRecreationKeepTheProjectOpen`:

- the real `MainActivity`, editor opened on an in-memory document (no project file touched);
- `settings put system user_rotation 1`, then `wm size 1080x1500`, then `ActivityScenario.recreate()`, each restored after.

**Before:**

```
rotation: recreated=true destination=Shell document=true; resize: recreated=true destination=Shell document=true;
recreate: destination=Shell document=true
```

The Activity was **recreated**, not crashed or killed. The document survived in `EditorViewModel`, but the app showed Home.

**Cause.**

- `MainActivity` declared no `configChanges`.
- The destination was an Activity field, and `onSaveInstanceState` deliberately saved the editor as `"shell"` ("process restoration belongs to the existing editor lifecycle").

**Fix.**

- `AppNavigation` (a ViewModel) owns the destination and survives recreation together with the unsaved document.
- Saved state now carries `editor` plus the project file path. A **new process** (a fresh ViewModel with saved state) reopens that file from its last autosave. An editor without a file falls back to Home, since there is nothing on disk.
- The manifest handles `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation` in place.
  - For those changes Compose relayouts, and the preview surface gets `surfaceChanged`, then `resize`.
  - The renderer, decoders and tiers survive, and nothing is rebuilt.
  - Other changes (dark mode, locale, font scale) still recreate the Activity and go through `AppNavigation`.
- `AppNavigationTest` (JVM) covers save, restore, process death and round trip.

**After:**

```
rotation: recreated=false destination=Editor(projectFile=null) document=true; resize: recreated=false … document=true;
recreate: destination=Editor(projectFile=null) document=true
```

**Autosave on every path that can destroy the editor.**

- `onPause`, `onStop` and `onSaveInstanceState` each queue a save of the committed document. The queue survives the Activity, and `onStop` precedes destruction and process death.
- Leaving the editor saves first, via the existing `onExitClick` loop. System Back is routed to it by `EditorScreen`'s `BackHandler`.
- Rotation and resize no longer destroy anything.

**What landscape and short windows look like** (screenshots from the test, A16):

- **Before the follow-up (`53bf4dc`).**
  - In landscape the **canvas had 0 height**. The editor reserves its full inspector budget (268/328 dp) below the preview, which is more than a landscape phone window has.
  - Only the top bar, transport and timeline showed. In a 1080×1500 window the canvas was a strip about 50 dp tall.
- **After** (final screenshots).
  - Landscape: the canvas is a letterboxed portrait frame, 212 px high, above the transport and timeline. A 1080×1920 composition is small there (90 px text is about 10 px), but visible and live.
  - 1080×1500 window: the canvas takes the top 40%, and the text is readable.
  - Below 560 dp of window height the canvas keeps at least 45% of the space.
  - **Swapchain follow-up (`1d2ccb3`).** Rotating in place exposed a stale-extent path: the swapchain took `currentExtent`, which right after a resize still reported the old size, so the preview stayed slightly stretched (requested 2240×219, swapchain 2340×212). It now uses the reported surface size, clamped. Every logged resize now matches (2340×212, 2240×219, 980×996, 1080×401).
  - Portrait phone windows (about 780 dp and up) never reach this branch, so the accepted portrait layout is unchanged.
  - The inspector sheet in a short window gets the remaining height, which is cramped for Edit Text. It already has a compact mode below 320 dp.
- **Split screen** was not driven from the shell; window resize (`wm size`) stood in for it. It is in the manual list.
- **Follow-up for real landscape work** (not in this pass):
  - preview and timeline side by side;
  - the inspector as a side sheet;
  - landscape-aware timeline row heights;
  - a minimum touch-target audit in short windows.

## 11d. Imported fonts and first play

### Where the wait goes (A16, ms)

| | static (274 KB TTF) before → after | variable (1.9 MB OTF) before → after |
|---|---|---|
| **Apply: metrics the canvas waits for** (`TextMetricsRepository.measure`) | **385 → 194** | **536 → 383** |
| Warm preparation, total | 405 → 183 | 472 → 383 (2) |
| ↳ send → reply (IPC + worker) | 208 → 166 | 249 → 360 (2) |
| ↳ outside the IPC (seed read/verify, hash, stores) | 197 → 16 | 223 → 23 |
| Bind → connected, worker alive | 3–5 | 2–7 |
| Bind → connected, after 35 s idle | 4.8 → **770** (1) | 7 → 4 |
| Library resolve (file SHA-256) | 2.8 | 22 per call before; memoised after (3) |
| Cold preparation (first glyphs, worker rasterisation) | 800 (425 raster) | 1,985 (1,592 in worker) |
| First frame 40 px, settled | 656 → 526 | 600 → 406 |
| 300 px frame: sharp tiers | gave up at 1.5 s, **13 pending** (stayed blurry) → sharp at 3.2 s, 0 pending | gave up at 1.5 s, **15 pending** → sharp at 4.1 s, 0 pending |
| Bundled font apply, for reference | 126–138 | |

(1) After 35 s idle the isolated process had been killed in that run, so binding a new process took 770 ms. With the old 30 s keep-warm, any pause longer than 30 s risked this. The worker now stays bound for **3 minutes** after its last request.

(2) The variable warm row varied between runs, with the in-worker share up in the after run. The part this pass changed (outside the IPC) fell from 223 to 23 ms. The in-worker time is FreeType variable-instance set-up and shaping in the isolated process, which this pass did not change.

(3) The resolve call in the after test was its first in that process, so it hashed (28 ms). The memo applies to the later calls of an apply, and to every later preparation of that file.

The "cold" rows are not comparable before and after: the SDF disk cache built by the before run persisted, and clearing app data is not allowed here. The worker's first rasterisation of a face is unchanged.

**Changes.**

- **`SdfDiskCache.shared`.** A verified in-memory view per directory.
  - Before, every request listed the cache directory and read, SHA-256-verified and touched **every** cached glyph of the font to build the seeds. After storing, it listed and stat'ed the whole directory again. That was about 200 ms of a warm request.
  - Now records are read and verified once per process, served from memory (16 MiB LRU across fonts) and written to disk atomically as before. The disk cap is tracked from a running total, and an evicted file leaves memory too.
- **Font-file verification memo.** A file verified in this process is trusted while its length and modification time are unchanged.
- **`TierGlyphCache`.** Imported sharp-tier glyphs from the worker are kept per process (16 MiB LRU), so a rebuilt preview (after Home) or a re-applied font does not wait for the worker again.
- **Imported settle.** While an imported-tier fetch is in flight, a settled frame may wait up to 6 s for the sharp glyphs; any new request still cancels it at once. Before, it gave up at 1.5 s and nothing redrew it.
- **Keep-warm.** 30 s → 3 min, still released.
- **Not on the main thread.** None of this runs there; the bind/unbind hop is unchanged.
- **Errors.** They are still reported on the existing paths: `FontImportErrors` on the Fonts page, `TextLayerErrors` and the preview status. A tier failure keeps the lower tier and records `lastError`; failures are never cached.

**Picker and placeholder.**

- The Fonts page lists imported families as text rows in the system font. There are **no per-font preview bitmaps** in the picker today, so there was nothing to cache there, and adding them would be new UI.
- The wait you see after tapping an imported face is the apply path above.
- **Placeholder.** The canvas keeps its previous frame while the new face is prepared, and shows the base tier at once before the sharp tier arrives. A separate fallback-font placeholder (draw in Inter, then swap) was **not** implemented; see Deviations.

### First play

**The measured first-play hitch was the JIT, not tiers.**

- With a fresh renderer, a settled paused frame and 2 s idle, the first playback frames of a single animated 30-line layer were **10, 166, 100, 71, 66, 56, 51, 47** ms, then 18 ms steady.
- The animator evaluation runs interpreted until the debug JIT compiles it.
- The size-animated case had no first-play hitch (the max of its first 30 frames is 18 ms), because sharp tiers are generated off-thread and playback never waits for them.

**Changes.**

- **Evaluation warm-up.**
  - When a preview renderer prepares a project with animated layers, the idle prewarm thread evaluates a **private copy** of up to four distinct animated layers' animations (30 evaluations each).
  - The JIT therefore compiles the evaluation stages before Play.
  - It uses PERF 10's activity gate: it pauses while any frame renders and resumes after 750 ms of quiet. The renderer's own animations and output are untouched.
- **Sharp-tier warm-up.**
  - The tier thread has an idle-priority queue, drained only after 750 ms without a frame and never counted as pending, so a settled frame never waits for it.
  - Each text layer queues the bundled glyphs of every tier its keyframed size reaches. That includes the current tier and the tier above it that hysteresis would switch to.
  - It is sent once per change of target, not per frame. Imported faces are not warmed: their tiers come from the worker on demand and are then cached.

**First-play frames (ms):**

| | First 8 frames before | First 8 frames after | First 30 frames max, before → after | Steady p50 / p95 after |
|---|---|---|---|---|
| single30-animated | 10, **166, 100, 71, 66**, 56, 51, 47 | 16, 17, 20, 19, 16, 16, 16, 16 | **166 → 20** | 15.6 / 19.3 |
| layers30-animated | 30, **75, 67, 61, 63**, 57, 58, 60 | 15, 38, 30, 29, 28, 29, 28, 28 | **75 → 38** | 21.8 / 24.5 |
| single30-size | 11, 8, 9, 13, 13, 14, 15, 14 | 13, 8, 8, 13, 9, 10, 15, 16 | 18 → 19 | 18.7 / 20.5 |
| layers30-size | 40, 36, 37, 37, 35, 38, 36, 35 | 16, 9, 11, 10, 9, 11, 9, 11 | 42 → 23 | 23.9 / 27.4 |

## Cache invalidation rules

| Cache | Key | Invalidated by | Test |
|---|---|---|---|
| Merged text storage (`reserveFrame`) | None; capacity only. | Rewritten every submit; reserve = last frame's demand, bounded. | `TextMergedBatchParityTest` (device): pixels identical, merged and separate. |
| `TrackMemo` slots | Track equality (static value, keyframes: time, value, easing) + last time. | A new memo per prepared project; a different time recomputes; unequal tracks never share. | `PreparedTextAnimationGoldenTest`: unchanged PERF 10 hashes + shared-memo bit-identity. |
| Tier catalog (retained) | Bundled font + glyph + ppem (+ spread) inside FontManager. | Its own unpinned LRU budget (16 / 8 MiB); bundled fonts never change. | Existing tier tests; device suites. |
| `SdfDiskCache` memory view | Generator version + ppem + spread (file prefix), exact font identity (SHA-256 of font, face, instance, coordinates), glyph. | Generator change deletes stale files; corrupt file is a miss and is deleted on the next process's read; disk eviction also leaves memory; 16 MiB memory LRU. | `TextSdfDiskCacheTest` (adapted: corruption caught on the next process read). |
| Font verification memo | Font SHA-256 → (length, mtime) at verification. | Any rewrite (length or mtime change) re-hashes; deleting the font forgets it. | Covered by resolve paths; JVM suite. |
| `TierGlyphCache` | (font identity, glyph, ppem); spread follows from ppem; exact worker reply record. | 16 MiB LRU; failures never cached; library deletion changes the identity set (missing fonts fail before the cache). | `TierGlyphCacheTest`: record round trip, second renderer without worker, partial hits, failures, LRU. |
| Tier warm queue | (font, glyph, ppem) as the ready cache. | Skips keys already retained, ready, failed or queued; cleared with the renderer. | Device check (11e). |

## 11e. Suites and numbers

Every device run used the debug APK, installed with `adb install -r` (no uninstall, no data clearing), and `am instrument` directly.

### JVM

`:app:testDebugUnitTest`: **403 tests, 0 failures**.
- PERF 10 ended at 390.
- New: `AppNavigationTest` (4), `PlaybackGateTest` (4), `TierGlyphCacheTest` (4) and `PreparedTextAnimationGoldenTest.sharedTrackMemoIsBitIdenticalAcrossLayers` (1).
- `TextSdfDiskCacheTest` was adapted (Deviation 7).
- `core-engine` has no JVM tests.

### App instrumented suite (`a16-perf11-suite`, on `bc19e5c`): 38 run, 31 passed, 7 failed

```
adb shell am instrument -w -r -e notClass com.fluxx.android.render.TextF2AcceptanceTest,com.fluxx.android.render.TextPerfProfileTest \n  -e textRunLabel a16-perf11-suite -e e1aLabel r28p11 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- **Passed (31).**
  - New in this pass: `EditorLifecycleTest` (rotation/resize/recreate; Home during playback in the editor), `PreviewLifecycleTest`, `ImportedFontLatencyTest`, `TextMergedBatchParityTest`.
  - Unchanged: LayerTransformation 3, FontImportAcceptance 4, ImportedTierAcceptance 3, PaletteRepository 5, TextExplode 4, F1 parity, TextFix 2, PaletteBrowser 1, TextInspectorLayout 3.
- **Failed, environmental as in PERF 10 (6).**
  - `TextGpuProofTest` and the four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
  - `E1aGoldenExportTest` is missing its local source asset.
- **Failed, regression (1), fixed in `fcfd386`.**
  - F1 `fiveHundredGlyphsAllocateNothing…` counted **42 native allocations** in its window (11d's tier warm-up re-sent requests on a tier boundary). Frame 16.07 / 18.48 ms p50 / p95.
  - Rerun on `fcfd386` (`a16-perf11-verify`): native Δ **0**, Kotlin Δ 0, rasterisations, uploads, shapes and generation Δ 0, frame 17.74 / 24.19 ms. It now fails **only** on its final validation-layer assertion, as in PERF 10. The frame difference between the two runs is run-to-run variation; the rerun started straight after the heavy phase-one tests.
- **Rerun on the final code (`fcfd386`).** `TextMergedBatchParityTest` passed. `EditorLifecycleTest#rotation…` passed, with every swapchain extent matching its requested size.

### Core-engine instrumented: `TextCpuProofTest` 2 / 2 passed (runner `com.fluxx.android.engine.test/androidx.test.runner.AndroidJUnitRunner`)

### F2 (`TextF2AcceptanceTest`, `a16-perf11-f2b`, final code)

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest -e textRunLabel a16-perf11-f2b   com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p11/v-f2.txt 2>&1 &"
```

**20 run, 8 passed, 12 failed: the same split as PERF 10.** Recorded as it stands; not rerun.

- **Passed (8):** all seven pre-encode pixel parity tests (plain, rich, wrapped, imported variable, character, advanced modifier, character random) and `preparedTransportPreservesRichMetrics`. Merged batches, the track memo and the warm-ups change no pixel.
- **Failed (12):** every performance test.
  - All fail on **p99 > 16.7 ms** and on **Vulkan validation** (the layer is absent), as in PERF 10.
  - The two 4,096-glyph character and modifier tests hit the 15-minute `TimeoutException`.
  - `importedVariableMaximum` failed on an internal `check`.

**Frame times in this run were slower than PERF 10's, and that was the phone's state, not this branch.**
- Measured: f2 representative 23.2–26.0 ms p50 (PERF 10 19.0); characters representative 61–63 (30.7); 4,096 glyphs 165–173 (124.6).
- The run lasted about 84 minutes, back to back after the other suites.
- To check, `TextPerfProfileTest` ran the same F2 stacks (representative and characters representative, playback) on this build and on the PERF 10 build (`20671a6`). The runs alternated (this, PERF 10, this, PERF 10), with thermal status 0 and the big/little cores at 2.2/2.0 GHz before every run:

| frame p50 / p95, ms | this build #1 | PERF 10 #1 | this build #2 | PERF 10 #2 |
|---|---|---|---|---|
| f2 representative | 16.51 / 18.05 | 16.63 / 19.30 | 16.70 / 19.46 | 16.55 / 17.98 |
| characters representative | 29.43 / 31.51 | 30.43 / 40.92 | 28.86 / 31.66 | 31.95 / 33.68 |

The builds match, so the slowdown in the F2 run was thermal or clock state from the long run.

**Native allocations in the F2 windows.**
- Second pass: unchanged from PERF 10 (rich, wrapped and modifiers 624; characters 1,884; maximum 2,063).
- First pass: rich, wrapped and modifiers rose from 1,277 to about 4,300. Each window starts with a seek back to frame 0, which is a settled frame, so the idle warm-up request can land in it once.
- That is cold-pass, one-off work and does not recur in the second pass. Steady-state playback windows allocate nothing; see F1 above.

**Characters fixture, cold first frame.** The cold first frame takes about 20 s. It is the same on the PERF 10 build: the test took 42 s in total on PERF 10 and 39 s on this build. This is the per-character catalogue preparation, already present before this pass.

### Tier warm-up (`TierWarmUpTest`, final code)

```
adb shell am instrument -w -r -e class com.fluxx.android.render.TierWarmUpTest -e textRunLabel a16-perf11-warm   com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**Passed.** A layer keyframed 30 → 300 px (bundled mono font, 15 glyphs) opens paused at 30 px.
- **While 30 frames render:** the warm counter stays at 0 (PERF 10 rule).
- **Once quiet:** all **45 glyphs** (three sharp tiers) were generated in **7.2 s** of idle (384 ppem glyphs take more than 100 ms each on the A16).
- **Seek to 300 px:** it then settled in **155 ms with 0 pending**. On the first run, with only 26 of 45 warmed after 3 s, the same seek took 1,652 ms and left 9 pending.

## Deviations

1. **Merged text batches and the batch/draw contract.** I read the contract as "after `flush` returns, nothing Kotlin owns is in use by the GPU". Text-only flushes therefore no longer submit on their own; they join the next real submit, which still waits for its fence. Kotlin-visible arguments, the four-entry limit and every fence wait that protects a Kotlin resource are unchanged. If you read the contract as "every flush submits and waits", this is a contract change: tell me and I will revert to per-flush submits.
2. **No non-debuggable benchmark build.** Every number is from the debug APK. Adding one needs a build type, and since `adb uninstall` is not allowed, installing it would mean either temporarily replacing the debug app or leaving a second app on the phone. I did neither.
3. **"Skips to the end" not reproduced end to end.** The mechanisms were measured and fixed (Part 2): a 733 ms single-frame jump, and up-to-1.5 s waits per misclassified frame.
4. **No fallback-font placeholder for imported faces.** The canvas keeps its previous frame, then the base tier, then the sharp tier.
5. **Split screen not driven from the shell.** `wm size` resize stood in for it. Process death is covered by the JVM test of the restore decision, not on device.
6. **Short-window layout change (`53bf4dc`).** This is a layout rule, not new UI: the canvas keeps 45% of the height in windows under 560 dp. Portrait phone windows are unaffected.
7. **`TextSdfDiskCacheTest` adapted** to the memory view: a file corrupted after a font is in memory is detected by the next process instead of the next request. The record still never reaches the worker and is deleted.
8. **Regressions caught and fixed in this pass.**
   - The 11a stage timer folded `if (!sharp) return`, so 11a's commit alone fails `PerFrameTierPathTest`. 11b restores the literal.
   - 11d's tier warm-up allocated in F1's window; fixed in `fcfd386` (11e).
9. **Commit split.** The 11b–11d commits were split by hunk from one working tree. Only the final tree (`fcfd386`) was built and tested on device; intermediate commits were not built on their own.

## Still open

- **Vulkan validation-layer tests and `E1aGoldenExportTest`** (environmental, as in PERF 10).
- **30 animated layers at p95 under 16.6 ms on the debug APK.** Measured 22.4 / 27.5 ms; the floor is evaluation.
  - Next steps from PERF 10, which need your decision on threading or the batch/draw contract: animators on the second A76 core (evaluation is per layer, so layers parallelise naturally), and per-cluster modifiers in the vertex shader.
  - Also a non-debuggable measurement build.
- **Plain/size p95 of 18–21 ms at 30 layers.** This equals the single-layer floor (paced present plus fence); it is not a text cost.
- **Imported faces: the worker's first rasterisation** (0.4 s static, 1.6 s variable for about 20 glyphs; 3–4 s for 15 glyphs at 384 ppem) is unchanged. Caches remove the repeats.
- **Real landscape and split-screen editor layout** (Part 3 follow-up).
- **Character catalogue cold preparation:** about 20 s for the first frame of the 720-glyph characters fixture, the same on the PERF 10 build.
- **Tier-switch frames still allocate** (92 native allocations per switch window here; PERF 10 finding).

## Device settings

- Recorded before any run: `stay_on_while_plugged_in=0`, `user_rotation=0`, `accelerometer_rotation=0`, no `wm size` override.
- `svc power stayon true` kept the phone awake during the suites.
- The rotation test sets and restores `user_rotation`, `accelerometer_rotation` and `wm size` in `finally`.
- **Final state** (checked at the end): `stay_on_while_plugged_in=0`, `user_rotation=0`, `accelerometer_rotation=0`, `wm size` with no override (physical 1080×2340, density 450), no instrumentation or tracing running.
- One stale F2 run was stopped with `am force-stop com.fluxx.android.debug`, so F2 could run on the fixed build. No data was cleared.

## A16 manual test list (debug build)

1. **30 layers, scrub and playback.**
   - Make a 1080×1920 project with one text layer of `AV office gy 0123456789` at 40 px, then duplicate it to 30 layers and spread them down the canvas.
   - Play: it should be smooth.
   - Drag the playhead quickly for 20 s: the text follows your finger with no stalls.
   - Then add 2–4 animators to one layer, duplicate it to 30, and repeat. Playback should look as smooth as with one layer, and visibly smoother than before.
2. **30 layers with font size animation.** Keyframe the size of every layer from 30 to 300 px over 3 s (duplicate after keyframing), then play a few times.
   - There should be no long freezes or skipped seconds.
   - The text sharpens as it grows, with at most a brief one-frame hitch.
3. **Background and return during playback.**
   - Play the project from step 2, press Home, wait 5 s and return. The playhead is where you left it and the preview is paused.
   - Press Play straight away: it starts from that position. It may take a moment for the first frame, but the playhead must not jump ahead or reach the end on its own.
   - Repeat, waiting 10 s before Play.
4. **Rotation.**
   - With the editor open and unsaved edits (move a layer), rotate the phone to landscape and back.
   - The project stays open, edits and undo stay, and the canvas stays visible in landscape (smaller, above the transport and timeline).
   - Rotate during playback too: playback continues or pauses, but nothing closes.
5. **Split screen.** Open Fluxx in split screen with another app, with the editor open.
   - The project stays open, and the canvas is visible in the half window.
   - Drag the divider: the preview redraws and nothing closes.
   - Return to full screen.
6. **Imported font load.**
   - Import a font if needed. In Edit Text → Fonts, tap an imported family: the canvas should update about half a second faster than before (the bundled faces are still the reference).
   - Tap another imported face, then go back to the first: the second time should be quicker.
   - Wait 2 minutes and apply again: no long first wait.
   - At 250 px or more the text should become sharp within a few seconds, without touching the canvas.
7. **First play.**
   - Open a project with an animated text layer (2–4 animators), wait 2 s and press Play.
   - The first second should be as smooth as the rest; before, it stuttered for about the first 10 frames.
8. **Unchanged behaviour.**
   - The eyedropper on text fill still matches.
   - Export a 3-second clip: it matches the preview.
   - Static frames look identical to TEXT PERF 10.
