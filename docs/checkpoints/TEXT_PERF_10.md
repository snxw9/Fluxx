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
