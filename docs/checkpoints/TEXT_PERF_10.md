# TEXT PERF 10: text frame-time investigation and fixes

Branch `text-perf-10` (from master `9fd4499`). Device: Galaxy A16 (SM-A165F, MediaTek mt6789 / Helio G99: 2× Cortex-A76 at 2.2 GHz and 6× Cortex-A55 at 2.0 GHz, Mali-G57 MC2), Android 16. The build is the debug APK, the same one the 9d suites run. It is JIT-only (`dexopt status=verify`, debuggable).

## 10a. Profile (before any fix)

### How it was measured

- **Stage timers.** These are diagnostic only: they never change output and do not allocate.
  - Kotlin: `CompositionRenderer.frameStages` covers frame, tiers, evaluate, prepareText, textLayer (mesh) and finish, using `System.nanoTime` around each stage.
  - Native: `VulkanRenderer::frameStages` is exposed as `TextDebugBridge.frameStages` in debug builds only. It covers the work-fence wait, batch record, batch submit+wait, present-fence wait, acquire, present record, present submit and the post-present idle wait.
  - GPU time comes from Vulkan timestamp queries around the text render pass and the present blit.
  - Perfetto/atrace sections: `fluxx.frame`, `fluxx.tiers`, `fluxx.prepareText`, `fluxx.textMesh`, `fluxx.batchRecord`, `fluxx.batchSubmitWait`, `fluxx.acquire`, `fluxx.queuePresent`, `fluxx.presentIdleWait`.
- **Harness.** `TextPerfProfileTest` runs the TEXT FIX 9d F2 fixtures, built by the same `TextF2AcceptanceTest.fixture`, in three modes:
  - **static:** the same time every frame;
  - **scrub:** deterministic random seeks;
  - **playback:** consecutive 60 fps frames.

  Each mode runs 120 warm-up frames and 300 measured frames (4096 glyphs: 10 + 40). The harness writes `files/text/<label>/perf10-<case>-<mode>.json`.
- **Commands.** The phone was kept awake with `svc power stayon true` (the user approved this).

  ```
  adb shell am instrument -w -r -e class com.fluxx.android.render.TextPerfProfileTest#representativeStages,com.fluxx.android.render.TextPerfProfileTest#maximumStages \
    -e textRunLabel a16-perf10-before com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
  adb shell simpleperf record --app com.fluxx.android.debug -g --duration 12 -f 2000 -o /data/local/tmp/p10/rep-playback.data
  adb shell "cat /data/local/tmp/p10/perfetto.txt | perfetto --txt -c - -o /data/misc/perfetto-traces/perf10-rep-playback.pftrace"
  ```

  The Perfetto config records sched, cpu/gpu frequency, cpu idle, thermal, atrace gfx/view/dalvik/power and the app's sections.
- **Clocks and thermal.**
  - A shell sampler logged `scaling_cur_freq` once a second. The big cores stayed at **2.1–2.2 GHz** and the little cores at 2.0 GHz under load, so there was no throttling.
  - `PowerManager.currentThermalStatus` was **0 (none)** before and after every mode.
  - The GPU frequency nodes (`/proc/gpufreqv2`, `/sys/kernel/ged`) are not readable from the shell. GPU time is therefore measured with timestamps, and the Perfetto traces carry the `gpu_frequency` events.
- **Artefacts.**
  - Traces: `perf10-rep-playback.pftrace` and `perf10-max-playback.pftrace`, kept outside the repo. Open them in ui.perfetto.dev.
  - Profiles: simpleperf `rep-playback.data` and `max-playback.data`.

### Per-frame breakdown, before (ms, p50 / p95)

| Stage | 720 static | 720 scrub | 720 playback | 4096 static | 4096 playback |
|---|---|---|---|---|---|
| **Frame (render thread, wall)** | **16.50 / 19.59** | **85.37 / 91.52** | **80.72 / 86.72** | **29.81 / 32.63** | **897.69 / 948.16** |
| Tiers + layout evaluation (CPU) | 1.13 / 4.85 | 72.23 / – | 66.58 / 74.58 | 7.25 / – | 875.57 / 924.72 |
| ↳ animator/selector evaluation (CPU) | 0.00 / 0.01 | 70.93 / – | **65.39 / 73.30** | 0.01 / – | **870.05 / 918.91** |
| Glyph preparation (`prepareText` reuse, CPU) | 0.04 / 0.07 | – | 0.06 / 0.07 | – | 0.07 / 0.08 |
| `textLayer` JNI + native mesh build (CPU) | 1.48 / 2.96 | 1.79 / – | 1.67 / 1.82 | 11.95 / – | 10.57 / 11.39 |
| `finish` (all waits below, wall) | 13.46 / 15.42 | 11.44 / – | 12.28 / 15.42 | 10.67 / – | 10.94 / 12.19 |
| ↳ batch command record (CPU) | 0.10 / 0.17 | – | 0.11 / 0.16 | – | 0.37 / 0.55 |
| ↳ batch submit + fence wait (wall) | 4.08 / 5.09 | 6.48 / – | 6.54 / 9.36 | 4.57 / – | 4.70 / 5.63 |
| ↳ acquire (wall) | 0.14 / 0.20 | 0.14 / – | 0.15 / 0.25 | 0.14 / – | 0.16 / 0.24 |
| ↳ present record + submit (CPU) | 0.69 / 0.99 | – | 1.17 / 1.87 | – | 1.34 / 1.81 |
| ↳ post-present `vkQueueWaitIdle` (wall) | 8.02 / 10.39 | 3.34 / – | 3.75 / 4.98 | 3.69 / – | 3.64 / 4.14 |
| **GPU: text batch** (timestamps) | 3.03 / 3.41 | 3.56 / – | 3.21 / 5.77 | 1.22 / – | 1.19 / 1.65 |
| **GPU: present blit** (timestamps) | 7.61 / 9.61 | 3.36 / – | 3.65 / 4.34 | 3.57 / – | 3.51 / 3.85 |

(– = not extracted; the full p50/p95/max/mean for every stage is in the JSON reports.)

Atlas upload is 0 in steady state (`atlasUploadsDelta` 0; `upload()` only copies the per-batch vertex/index buffers). Layout/shaping is 0 in steady state (the prepared layouts are reused; no shaping in the window).

### Verdict: CPU-bound in Kotlin animator evaluation

- **720 glyphs, playback:** evaluation is **65 of 81 ms (81%)**. GPU work is about 6.9 ms (text 3.2 + present 3.7), but every frame waits for it serially (submit → fence, present → `vkQueueWaitIdle`), so about 11–12 ms of each frame is spent waiting on the GPU.
- **Static 720-glyph frame (no evaluation):** 16.5 ms p50, and 13.5 ms of that is waiting. The present blit costs 7.6 ms GPU when the GPU clocks down on an idle-ish frame.
- **4096 glyphs:** evaluation is **870 of 898 ms (97%)**. Mesh build adds 10.6 ms and the GPU waits add 10.9 ms.
- **GPU fill rate is not the problem.** The text pass is 1.2–3.6 ms for these small glyphs (8–72 px).

### What the CPU time is (simpleperf, render thread, 720 playback, cpu-cycles)

- **About 55–60% is the ART interpreter** (`ExecuteSwitchImplCpp` 21%, `DoCall` 16%, `GetFieldInfo` 3%, plus allocator time for shadow frames).
  - **`PreparedTextAnimation.evaluate` is never JIT-compiled.** It is 5,123 dex code units with 115 registers, about 8× the next-largest hot method (`dexdump`: `PreparedSelectorEngine.coverage` 851, `renderTarget` 1,357).
  - `Jit::MaybeDoOnStackReplacement` takes 3–5% by itself: OSR is attempted on loop back-edges and never succeeds.
- **About 15–20% is libcore math and buffer intrinsics routed through the generic JNI trampoline** in the debuggable JIT (`artQuickGenericJniTrampoline`, `GetShorty`, `BuildGenericJniFrameVisitor`):
  - `Math.pow` from `interpolateColour` (sRGB↔linear): 12.7% inclusive;
  - `DirectByteBuffer.putFloat` → `Memory.pokeInt`: 3.9%;
  - `sin`/`cos`/`tan` in `SelectorMath.affine`: 4%;
  - `Math.abs` → `doubleToRawLongBits`;
  - `coerceIn` (2–6% self, not inlined).
- **The idle prewarm competes for the CPU.** `Fluxx-text-idle` (`TextIdlePrewarm.foreground`) used **48–66% of all app CPU samples** during playback, about one full core, rasterising bundled faces while frames render.
- **4096-glyph playback** has the same profile (interpreter 35%+, JNI trampolines 13%, `pow` 3.4%, `coerceIn` 3.6%).
- **Not hot:** JNI crossings per glyph (one `textLayer` call per layer), re-layout/re-shaping, tier selection (≤ 1.2 ms at 720), atlas upload, draws (one indexed draw per layer), and command recording (≤ 0.4 ms).

### Instrumentation added in 10a

- `vulkan_renderer.{h,cpp}`: stage accumulators, a 4-entry timestamp query pool (created only where the graphics queue reports `timestampValidBits`), ATrace sections, and `frameStages(out)`.
- `jni_bridge.cpp` and the debug source set `TextDebugBridge.kt`: `frameStages(session, LongArray)`, with `STAGES = 12`.
- `CompositionRenderer.kt`: `stageNs`, `frameStages(out)`, `STAGE_*` constants, and `android.os.Trace` sections on frame and tiers.
- `TextPerfProfileTest` (androidTest): the harness above.
- `PreparedTextAnimationGoldenTest` (JVM): SHA-256 of every evaluation output for four F2 animator stacks at 11 times, **recorded before any fix**. It is the bit-identity gate for 10b.

## 10b. Fixes, in order of measured impact

Every fix keeps the output bit-identical. Proof:
- `PreparedTextAnimationGoldenTest` still produces the four hashes recorded from TEXT FIX 9. It also asserts that the record-based tier inputs equal the old buffer scan at every sampled time.
- `SrgbCodecTest` compares the codec against the `pow` formulas: all channel pairs, 2.5 M random inputs, and the 129 doubles around each of the 255 byte boundaries.

1. **`PreparedTextAnimation.evaluate` split into stage methods** (`evaluateSelectors`, `evaluateAnimator`, `evaluateLines`, `positionClusters`, `applyAnimator`, `writeRecords`, `evaluateBounds`, `characterBounds`).
   - The statements are the same, in the same order. Cross-stage locals became fields with the same names.
   - The 5,123-unit method was never JIT-compiled; every stage now compiles.
   - The 96-byte modifier records are staged in a `FloatArray` and copied in one bulk `FloatBuffer.put`. Before, each float was its own `DirectByteBuffer.putFloat` → `Memory.pokeInt` JNI call, 22 per cluster.
   - The SDF tier inputs (`maxGlyphScale`, `maxModifierDistanceEm`) read the same floats from the staging array instead of `getFloat` on the direct buffer.
2. **`SrgbCodec`**: exact table forms of the sRGB transfer functions, used by `interpolateColour` and `TextAnimatorColour.blend`.
   - Decoding uses a 256-entry table of `linear(i/255.0)`.
   - Encoding brackets the value between the analytic byte thresholds and falls back to the formula within 1e-9 of a threshold. It finds the bracket with a 1,024-bucket lookup plus a short forward scan.
   - Before, these were six `pow` calls (through JNI here) per colour interpolation.
3. **Remaining JNI-routed and out-of-line helpers in per-glyph loops.**
   - `SelectorMath.clamp`/`magnitude`/`least`/`greatest` are inline and equal `coerceIn`/`abs`/`Math.min`/`Math.max`, including signed zeros and NaN pass-through. Kotlin's versions are out-of-line calls, and min/max/abs call `doubleToRawLongBits` (JNI here) on zeros.
   - `f - f == 0f` is an exact `isFinite()`, which otherwise goes `Math.abs` → `floatToRawIntBits`.
   - `AffineTrig` memoises sin/cos/tan on exact radian inputs. The axis is constant per animator; rotation and skew repeat whenever weights repeat.
   - Property ordinals are read once per animator; `coerceIn` with possibly empty ranges stays exact, including its exception.
4. **No `vkQueueWaitIdle` after present.**
   - That call held the render thread for the whole present blit: 3.6–8 ms per frame.
   - GPU order already covers every later use:
     - the offscreen render pass has an external dependency on `TRANSFER` reads (the blit);
     - `render()` waits `mInFlightFence` before reusing the present command buffers;
     - readback, resize and teardown wait on their own fence or `vkDeviceWaitIdle`.
   - **The per-batch fence wait (`submitWork`) is unchanged**, because resource lifetime depends on it (decoder images and mesh storage are reused after a flush).
5. **Wiggly noise and character values without JNI** (commit `296908c`, found by the 10d F2 run, where the per-character fixture was still 66 ms p50).
   - simpleperf on `characterRepresentativeStages` playback: `WigglySelectorMath.noise` was **46%** of the render thread, mostly `Math.floor` through the generic JNI trampoline. It made 4 floor calls per noise sample and 2 samples per value.
   - `SelectorMath.floor` is an exact inline `Math.floor`: signed zero, infinities and integral values ≥ 2^52 pass through unchanged, and NaN stays NaN.
   - The SplitMix axis hashes (x, x+1, y, y+1, dimension) are computed once per value and shared by the four corners and both samples. That is 17 `mix` calls per value instead of 40, and XOR plus wrapping addition keep the result exact.
   - `TextCharacterPreparation.valid` makes one `Character.getType` call (JNI) instead of two and checks finiteness without `Math.abs`.
   - **Proof:**
     - `SelectorFloorTest`: 6 M random doubles across every exponent, plus edge values; bit-equal to `Math.floor`. NaN input only has to give NaN, because HotSpot's own `Math.floor` returns different NaN payloads in interpreted and compiled code.
     - `WigglyReferenceTest`: 300 k random parameter sets plus non-finite inputs, bit-equal to a verbatim copy of the TEXT FIX 9 noise; `valid()` equals the old expression for every codepoint and 200 k random values.
     - The golden hashes, whose advanced stack includes Wiggly selectors and character offset/value, are unchanged.
   - **A16 result:** character-representative playback went from frame **68.2 / 70.4** to **29.7 / 31.7** ms (p50 / p95), and evaluation from **58.6 → 21.5** ms.

### Candidates checked and not changed (the profile shows they are not costs)

| Candidate | Result |
|---|---|
| Re-layout / re-shaping when nothing changed | 0 shapes in steady state; prepared layouts are reused (`shapesDelta` 0). |
| Per-frame glyph-key hashing or tier selection for unchanged layers | `prepareText` reuse is 0.04–0.07 ms; tier selection is under 0.5 ms at 720 glyphs. |
| Uploading unchanged atlas data | `atlasUploadsDelta` 0 in steady state. `upload()` copies only the frame's vertex/index buffers. |
| One draw per glyph | One indexed draw per layer. |
| Per-glyph JNI calls | One `textLayer` call per layer, with modifiers in one direct buffer. |
| Redundant mesh rebuilds | One mesh build per layer per frame (1.7–2.0 ms at 720, 10–11 ms at 4096). |
| SDF fragment cost and oversized quads | The GPU text pass is 1.0–3.4 ms; quads are the glyph box plus the tier spread. |
| Sync points | Fixed the post-present idle wait (above). The batch fence wait is the batch contract. |

### Cache invalidation rules

This pass adds four caches. Each one is keyed on everything that affects its output.

| Cache | Key | Invalidated by | Test |
|---|---|---|---|
| `SrgbCodec` tables | The formulas themselves (constant). | Never: the tables equal the formula for every input, with the formula used near thresholds. | `SrgbCodecTest` (exhaustive per input). |
| `AffineTrig` | Exact radian input bits, including the sign of zero. NaN never matches. | Any change in the input bits recomputes. | `PreparedTextAnimationGoldenTest`: animated rotation, skew and axis across 11 times and four stacks. |
| Staged records (`records`) | Rewritten in full by every `evaluate(time)`. The time memo (`evaluatedTime`) is unchanged from TEXT FIX 9. | Every evaluation. | The golden test hashes the buffer after every evaluation, including a repeated time and backward seeks. |
| External-tier settle (`externalSettled_`) | (layout, tier, epoch). | Epoch bump on atlas repack, on eviction of an external CPU tier glyph, and on deferred worker replies. `forget()` erases the node. | `ImportedTierAcceptanceTest` (device), with external requests and supplies re-verified in the 10d suites. |

## 10c. Idle prewarm and native allocations

- **`TextIdlePrewarm.foreground` pauses while frames render.**
  - `CompositionRenderer.renderTarget` marks `TextIdlePrewarm.activity` (a `FrameActivityGate`, one volatile store, no allocation) on every frame: preview playback, scrubbing and export.
  - The prewarm steps through `IdleWarmLoop`, which returns `PAUSED` while a frame arrived within the last 750 ms. It resumes from the same cursor when the renderer is quiet.
  - **A16 result:** `Fluxx-text-idle` went from **48–66% of all app CPU samples** during playback (before) to **0.2%** (after, simpleperf).
  - `IdleWarmLoopTest`:
    - the rasterisation counter does not move across 600 simulated 60 fps frames, nor across irregular scrubbing gaps (30–400 ms);
    - it resumes on the same cursor after the quiet window;
    - a source check confirms every rendered frame marks the gate.
- **Rich and wrapped native allocations.** A temporary per-frame backtrace diagnostic was used here and removed before commit.
  - **598 of 600 frames had 0 native allocations** (rich and wrapped representative, a full 0–10 s sweep).
  - Frame 542, where the rich runs (run scale up to 1.22) cross into the 96 ppem tier, had 3,071 allocations. The atlas generation went from 1 to 2 and 240 tier glyphs were collected and packed: `std::map` nodes plus `std::string` font ids in the glyph keys.
  - Frame 543 had 2 allocations: 9d's external-tier settle map was cleared on repack and re-inserted one node per layout.
  - **Fix:**
    - settle entries are invalidated by an epoch instead of `clear()`;
    - the node is created on the tier-change frame (`setTier`).
  - **After: 599 of 600 frames allocate nothing.** The one frame that does is the tier switch itself (see Findings).

## Before / after (A16, debug APK, ms p50 / p95)

| Stage | 720 playback before | 720 playback after | 4096 playback before | 4096 playback after |
|---|---|---|---|---|
| **Frame** | **80.72 / 86.72** | **17.84 / 24.38** | **897.69 / 948.16** | **132.01 / 136.21** |
| Animator/selector evaluation | 65.39 / 73.30 | 9.89 / 12.09 | 870.05 / 918.91 | 113.12 / 116.74 |
| Tiers + evaluation | 66.58 / 74.58 | 10.31 / 12.61 | 875.57 / 924.72 | 114.69 / 118.21 |
| prepareText reuse | 0.06 / 0.07 | 0.06 / 0.07 | 0.07 / 0.08 | 0.06 / 0.11 |
| textLayer + native mesh | 1.67 / 1.82 | 1.89 / 2.43 | 10.57 / 11.39 | 11.00 / 11.21 |
| finish (waits) | 12.28 / 15.42 | 6.30 / 9.52 | 10.94 / 12.19 | 6.13 / 7.33 |
| ↳ batch record | 0.11 / 0.16 | 0.11 / 0.16 | 0.37 / 0.55 | 0.40 / 0.46 |
| ↳ batch submit + fence | 6.54 / 9.36 | 4.70 / 7.75 | 4.70 / 5.63 | 3.96 / 4.78 |
| ↳ acquire | 0.15 / 0.25 | 0.13 / 0.32 | 0.16 / 0.24 | 0.14 / 0.18 |
| ↳ present record + submit | 1.17 / 1.87 | 0.86 / 1.44 | 1.34 / 1.81 | 0.98 / 1.30 |
| ↳ post-present idle wait | 3.75 / 4.98 | **0 (removed)** | 3.64 / 4.14 | **0 (removed)** |
| GPU text batch | 3.21 / 5.77 | 2.77 / 4.43 | 1.19 / 1.65 | 1.02 / 1.44 |
| GPU present blit | 3.65 / 4.34 | 3.84 / 15.50 (1) | 3.51 / 3.85 | 3.30 / 3.83 |

(1) The GPU present span starts at `TOP_OF_PIPE`, before the blit's wait on the swapchain-acquire semaphore. It therefore includes display pacing (vsync) when a frame waits for a free image; it is not shader time.

Other modes (after):

| Case | Frame p50 / p95 | Evaluation p50 |
|---|---|---|
| 720 static | 16.58 / 17.88 (vsync-bound: the batch waits behind the paced present) | 0.01 |
| 720 scrub | 20.79 / 26.84 (before: 85.37 / 91.52) | 10.44 |
| 720 rich playback | 16.58 / 20.84 | 9.24 |
| 720 wrapped playback | 16.54 / 21.54 | 9.24 |
| 720 character playback | 29.73 / 31.71 (before fix 5: 68.17 / 70.43) | 21.45 (before fix 5: 58.58) |
| 4096 static | 15.99 / 18.99 (before: 29.81 / 32.63) | 0 (mesh 10.16) |
| 4096 scrub | 126.11 / 134.37 (before: 889.60 / 896.53) | 108.86 |

Run-to-run variation is about ±1 ms at p50 and ±3 ms at p95; another 10b run measured 720 playback at 16.86 / 18.98. The render thread ran on the A76 cores for 99.5% of its samples (simpleperf `--cpu 6-7`). Thermal status was 0 in every mode.

### Targets

- **720 glyphs, p95 < 16.6 ms: not met on the debug APK.**
  - Measured: **p50 16.5–17.8 ms, p95 19–24 ms** (rich and wrapped p95 20.8 / 21.5). Before: p50 80.7, p95 86.7.
  - The remaining floor is about 12 ms of CPU plus about 4.7 ms of synchronous GPU batch wait:
    - about 10 ms of animator evaluation (8 animators × 4 selectors × 13 properties over 720 characters);
    - 1.9 ms of mesh build;
    - about 0.5 ms of other CPU work;
    - the batch fence wait, which includes queueing behind the previous frame's paced present.
  - Within evaluation, the largest remaining items are:
    - selector coverage, ease tables and `combine` (about 17% of the thread);
    - affine trigonometry via JNI (about 11%: the per-glyph weights differ, so the memo misses);
    - colour interpolation (about 7%).
- **4096 glyphs: 60 fps is not reachable** with the current design. Reachable now: **p50 126–132 ms, p95 134–136 ms**, versus 898 / 948 before. The floor has three parts:
  1. Evaluation is about 109–113 ms: 16 animators × 8 selectors over 4,096 characters is about 524 k selector-character evaluations plus 65 k affine compositions per frame on one core.
  2. The native mesh rebuild is about 10–11 ms. Even a static 4,096-glyph frame costs 16.0 / 19.0 ms.
  3. The synchronous batch wait is about 4 ms.

### Next steps (not done here)

1. **Measure a non-debuggable build.**
   - The suites run on the debuggable APK, where libcore math (`sin`/`cos`/`tan`/`hypot`) and Kotlin parameter checks go through the generic JNI path and the JIT does not intrinsify them.
   - A `benchmark` build type (non-debuggable, debug-signed, with the debug-only harness) would give the number users actually see. It is a build-system change, so it was not made in this pass.
2. **Overlap GPU batch completion with the next frame's CPU work** by double-buffering batch resources.
   - This changes when Kotlin may reuse resources after `flush`/`finish`, which is the batch/draw contract.
   - Reported, not changed: it would save about 3–5 ms per frame.
3. **Evaluate animators on the second A76 core.** Selector coverage is independent per animator.
4. **Move per-cluster modifier application to the vertex shader**, so the 4,096-glyph mesh is static and the per-frame 10 ms rebuild goes away.
5. **Make tier-switch frames allocation-light** by interning font ids in `TextRenderer::Key` and using flat containers. A tier switch currently costs about 3 k allocations on that one frame.

## 10d. Suites and numbers

Every device run used the debug APK and `am instrument` directly, with no uninstall and no data clearing. APKs were installed with `adb install -r`.

### App instrumented suite (`a16-perf10-suite`, 10c APK): 33 run, 26 passed, 7 failed

```
adb shell am instrument -w -r -e notClass com.fluxx.android.render.TextF2AcceptanceTest,com.fluxx.android.render.TextPerfProfileTest \
  -e textRunLabel a16-perf10-suite -e e1aLabel r28p10 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- **Passed:**
  - LayerTransformation 3, FontImportAcceptance 4, ImportedTierAcceptance 3, PaletteRepository 5, TextExplode 4;
  - F1 parity, TextFix 2, PaletteBrowser 1, TextInspectorLayout 3.
- **Failed, all environmental (not in this pass):**
  - `TextGpuProofTest` and the four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
  - F1 `fiveHundredGlyphs…` fails **only** on its final validation assertion. Everything else in its window is clean:
    - frame **16.38 / 18.63** ms p50 / p95 at 660 glyphs (TEXT FIX 9d: 33–37 ms p50);
    - native allocation Δ 0 and Kotlin Δ 0;
    - rasterisations, uploads, shapes and generation Δ 0. The 9d "prepared counter 1 changed" from the idle prewarm is gone.
  - `E1aGoldenExportTest` is missing its local source asset.

### Core-engine instrumented: `TextCpuProofTest` 2 / 2 passed (runner `com.fluxx.android.engine.test/androidx.test.runner.AndroidJUnitRunner`)

### F2 (`TextF2AcceptanceTest`, `a16-perf10-f2`, 10c APK): 20 run, 8 passed, 12 failed

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest -e textRunLabel a16-perf10-f2 \
  com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p10/f2.txt 2>&1 &"
```

The run took 5,031 s (84 min). Thermal status stayed 0.

- **Passed (8):** all seven pre-encode pixel parity tests (`randomAndReverse…` for plain, rich, wrapped, imported variable, character and advanced modifier, plus `characterRandom…`), `exportVisualPropertyFixtures` and `preparedTransportPreservesRichMetrics`.
- **Failed (12):** every performance test.
  - All 10 that complete fail on **p99 > 16.7 ms** and on **Vulkan validation** (the layer is absent).
  - The two maximum per-character and modifier tests hit the **15-minute `TimeoutException`** (see below).

| Fixture (pass 0 cold / pass 1 after 180 s thermal load) | glyphs | frame p50 ms | frame p95 ms | frame p99 ms | native `new` Δ | TEXT FIX 9d p50 |
|---|---|---|---|---|---|---|
| f2 representative | 660 | 19.0 / 22.0 | 25.7 / 31.4 | 28.1 / 32.4 | 0 / 0 | 33–37 |
| t1 rich representative | 720 | 17.1 / 18.5 | 24.8 / 24.0 | 27.4 / 25.6 | 1277 / 624 | 34 |
| t2 imported variable representative | 700 | 17.7 / 17.6 | 24.3 / 24.2 | 26.6 / 26.3 | 85407 / 4334 | 27.5 / 28.7 |
| t3 wrapped representative | 720 | 16.2 / 16.3 | 19.2 / 19.4 | 20.6 / 23.7 | 1277 / 624 | 35.6 / 34.8 |
| t4 characters representative | 720 | 66.5 / 66.3 | 72.9 / 68.6 | 1590 / 70.0 | 38269 / 1884 | 81.9 / 82.0 |
| t4 modifiers representative | 720 | 59.7 / 59.7 | 63.3 / 61.7 | 66.6 / 63.0 | 1277 / 624 | 94.0 / 93.9 |
| f2 maximum | 4096 | 124.6 / 125.0 | 135.1 / 129.0 | 143.9 / 133.9 | 0 / 0 | 330–341 |
| t1 rich maximum | 4096 | 125.5 / 125.4 | 129.7 / 128.9 | 132.4 / 131.1 | 3612 / 2063 | 438.9 / 437.6 |
| t2 imported variable maximum | 4096 | 124.7 / 124.5 | 128.5 / 128.3 | 132.6 / 130.2 | 65587 / 24582 | 434.4 / 433.9 |
| t3 wrapped maximum | 4096 | 129.9 / 129.8 | 133.5 / 133.6 | 138.0 / 136.1 | 3612 / 2063 | 330–341 |

- **Mesh build p95:** 1.9–2.7 ms at 660–720 glyphs and 10.2–10.8 ms at 4096, the same as 9d.
- **Kotlin allocation Δ:** 0 everywhere, except imported variable pass 0 (13 representative, 3 maximum). This is the known asynchronous imported-tier fetch, which also counts as 1 worker IPC.
- **Prepared counters 2/5** (atlas upload and generation) change in pass 0 of the maximum, character and imported fixtures. That is the asynchronous sharp-tier arrival inside the cold window, unchanged from 9d.
- **Native allocations in the rich, wrapped and modifier windows are unchanged from 9d** (1277 / 624 representative, 3612 / 2063 maximum).
  - The 10c per-frame diagnostic attributes them to tier-switch frames. 10c removed only the 2-allocation settle re-insert. Every other frame in the window allocates nothing.
  - F2 asserts on the window total, so it still fails. See Still open.
- **The character and modifier fixtures were the outliers.** Profiling the character fixture led to fix 5 (Wiggly and character values without JNI). The rerun is below.

### F2 rerun of the affected tests after fix 5 (`a16-perf10-f2b`, APK at `296908c`)

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest#characterRepresentativeColdAndThermal,\
…#advancedModifierRepresentativeColdAndThermal,…#characterRandomAndReversePreEncodePixelsMatch,\
…#advancedModifierRandomAndReversePreEncodePixelsMatch,…#characterMaximumColdAndThermal,…#advancedModifierMaximumColdAndThermal \
  -e textRunLabel a16-perf10-f2b com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p10/f2b.txt 2>&1 &"
```

`Tests run: 6, Failures: 4`. The run took 2,137 s.

- **Passed:** `characterRandomAndReversePreEncodePixelsMatch` and `advancedModifierRandomAndReversePreEncodePixelsMatch`. Pixels are unchanged by fix 5.
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal` now finish** within the unchanged 15-minute limit:
  - **13 min 59 s** for the character test (in 9d and the 10c run, both timed out at 15 min);
  - **11 min 57 s** for the modifier test.

  Both still fail on p99, validation, native allocations and the cold-pass counters.

| Fixture (pass 0 / pass 1) | glyphs | frame p50 ms | frame p95 ms | frame p99 ms | native `new` Δ | 10c run p50 | TEXT FIX 9d p50 |
|---|---|---|---|---|---|---|---|
| t4 characters representative | 720 | 30.7 / 30.5 | 33.5 / 32.4 | 1556 / 33.3 | 38269 / 1884 | 66.5 / 66.3 | 81.9 / 82.0 |
| t4 modifiers representative | 720 | 25.6 / 25.5 | 27.8 / 27.9 | 29.0 / 29.2 | 1277 / 624 | 59.7 / 59.7 | 94.0 / 93.9 |
| t4 characters maximum | 4096 | 399.2 / 398.5 | 406.2 / 403.6 | 1923 / 406.4 | 31635 / 34566 | timeout | timeout |
| t4 modifiers maximum | 4096 | 341.4 / 340.7 | 348.8 / 346.3 | 364.2 / 354.0 | 3612 / 2063 | timeout | timeout |

- **Characters maximum** also changes prepared counters 2/5 in pass 1, and counter 1 (rasterisations) in pass 0.
  - With 4,096 glyphs whose codepoints change every frame, the per-character catalogue keeps generating and uploading glyphs during the window.
  - The pass 0 p99 of about 1.9 s, and the representative 1.56 s, is the cold first catalogue preparation, which lands inside the window.
  - Both are as in 9d.

### JVM

`:app:testDebugUnitTest`: **390 tests, 0 failures**.
- That is 357 before TEXT FIX 9d, plus 20 from 9d, the golden test (1), `SrgbCodecTest` (3), `IdleWarmLoopTest` (4), `SelectorFloorTest` (2) and `WigglyReferenceTest` (3).
- `core-engine` has no JVM tests.

## Deviations

1. **Perfetto traces were captured but not analysed with `trace_processor`.** The session's permission policy blocked downloading the Windows `trace_processor_shell`.
   - The per-stage numbers come from the in-app stage timers and GPU timestamp queries.
   - The traces are available for ui.perfetto.dev: `perf10-rep-playback.pftrace` and `perf10-max-playback.pftrace`, kept outside the repo.
   - GPU frequency is in their `gpu_frequency` events. The sysfs GPU clock nodes are not readable from the shell.
2. **All numbers are from the debug (debuggable, JIT-only) APK**, which is what the suites and Android Studio installs run. A release-like measurement needs a new build type (see Next steps).
3. **The 720-glyph p95 target is not met** (19–24 ms against 16.6 ms). Numbers and the floor are reported above, and no limit was changed.
4. **The batch fence wait was kept** (batch/draw contract). Only the post-present `vkQueueWaitIdle` was removed. This is GPU synchronisation inside `finishFrame`; the Kotlin-visible flush/finish semantics are unchanged.
5. **`TextF2AcceptanceTest.fixture` is now `internal`** (was `private`), so the profiling harness builds the identical fixtures.

## Still open

- **Vulkan validation-layer tests** (the layer cannot be installed on the phone in this setup):
  - `TextGpuProofTest`;
  - the 4 `TextGpuAcceptanceTest` tests;
  - the final validation assertion in F1 and in every F2 performance test.
- **`E1aGoldenExportTest`:** the source video asset (`app/src/androidTest/assets/e1a/source.mp4`, git-ignored) is missing.
- **720-glyph p95 under 16.6 ms**, and **60 fps at 4,096 glyphs** (Next steps above).
- **Tier-switch frames allocate.** They allocate natively (about 3 k on the switch frame, from collect and pack) and spike in CPU time. These are not steady-state frames, but the F2 harness counts them whenever the size animation crosses a tier inside its window, so the F2 rich, wrapped and modifier windows still total 624–3,612, unchanged from 9d.
- **Per-character catalogue churn.** The character fixtures still allocate natively after warm-up (1,884 per window at 720 glyphs and 34,566 at 4,096 after warm-up) and keep uploading at 4,096 glyphs, because the animated codepoints keep generating catalogue glyphs. This is design work in the T4 catalogue, not in this pass.
- **The host JVM allocation proof is intermittent.** `PreparedTextAnimationTest.hostPreparedEvaluationAllocatesZeroAfterWarmup` reported 352 bytes in 2 of about 6 full runs while the exhaustive `SrgbCodecTest` was keeping the host JIT busy. It passed every isolated rerun, and the device ART counters are 0. It looks like a HotSpot deoptimisation inside the measured window.

## A16 manual test list (debug build, installed with `adb install -r`)

1. **Playback, 700-ish glyphs.**
   - Make a 1080×1920 project with one text layer of about 30 lines (for example `AV office gy 0123456789` repeated) at 40–70 px.
   - Add 2–4 animators, each with a Range selector (Offset keyframed from -100 to 100) changing Position, Rotation, Scale, Opacity and Fill colour.
   - Play it. It should look smooth with no stutter, and the Fill colour transition should look the same as before (no banding or colour shifts).
2. **Scrubbing.** Drag the playhead quickly back and forth across the animation for 20 seconds.
   - The text follows your finger without lag spikes.
   - Releasing the playhead leaves a sharp, settled frame.
3. **Static frame.** Leave the playhead still on a frame mid-animation for 10 seconds.
   - Nothing flickers.
   - Glyphs look identical to TEXT FIX 9: same sharpness, no holes in Playfair, same stroke width.
4. **Large text and tier switch.** Animate the text size from 30 to 300 px over 3 seconds and play it.
   - The text becomes sharper as it grows, with at most a brief one-frame hitch at the moment it sharpens.
   - No blur remains after it settles.
5. **Imported fonts.** Repeat steps 1 and 4 with an imported variable font (Playfair or Source Serif).
   - Same smoothness.
   - The large sizes are sharp after a moment.
6. **Big paragraph.** Paste about 4,000 characters into one layer with 8+ animators.
   - Playback will be slow (about 7–8 fps is the measured floor on this phone; see the doc).
   - It must not freeze, crash or show an error, and scrubbing must stay responsive.
7. **Idle prewarm.** After opening a project, keep the app idle for 5 seconds, then open the Fonts page.
   - Bundled font previews appear immediately.
   - During playback (step 1) the phone should run cooler than it did on TEXT FIX 9, because the background prewarm no longer runs while frames play.
8. **Eyedropper and export.** Pause on a frame, use the colour eyedropper on the text fill, then export a 3-second clip.
   - The eyedropper colour matches the text (the frame is still read correctly after the present-wait change).
   - The exported video matches the preview frame for frame.
9. **Backgrounding.**
   - Press Home during playback, come back, and play again: the preview recovers.
   - Rotate the phone or resize the window (split screen) while paused: the preview redraws correctly.
