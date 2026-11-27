<a id="rec-mp1"></a>

# MP1: media performance, pass 1 (measure, then safe wins)

Branch `aud1`, continued from `48b57a5`. Not merged. Device: A16 (SM-A165F, MediaTek, Android 16, 4 GB).

**Benchmark files** (read in place on the removable card, `Download/Seal`, never copied or written):
- **Worst case:** `Calc - Is There Someone Else？.mkv`, found by prefix. VP9 1920x1440 20 fps, 122.8 s.
- **Typical clip:** the H.264 AMV edit, found by the substring `a-X8h8cgzOI`. 1920x1080 30 fps, 60.1 s.
- **Long source (new):** `Project 3 [F85C683].mp4`, found by the prefix `Project 3` in `Download/Seal` (the first place looked). 5.02 GB, HEVC 1920x1080 30 fps, AAC, 3018.4 s.
- **Documents:** in memory only. No user project was created, opened or changed.

**Power state (important for reading the numbers).** Part A ran on the charger. The phone came off the charger at about 10:04 and Battery Saver came on. That was found at about 11:15; Snxw plugged it in and turned Battery Saver off at about 11:17. The runs in between are **not used** for any number below, except where marked. Every "after" number comes from runs after 11:17.

## Part A: measured and audited before any fix (`eb83345`)

**Test-only probes** (null in the app, no behaviour change):
- the decoder's codec name on open, and the pts of every frame it returns (`shown=`);
- `MediaProbes`: the PCM cache runs, peaks and thumbnails;
- `SourcePcm.decoding / coveredChunks / allocatedBytes` and `SourcePcmCache.release` (the runaway guard).

**Classes:** `Mp1ExportTest`, `Mp1ScrubTest`, `Mp1ClipsTest`, `Mp1LongSourceTest`, `Mp1CullTest`, `Mp1BackgroundTest`, with `Mp1Bench`. Added in Part B: `Mp1CullGoldenTest`, `Mp1StartupTest`.

### A0. Export and the decoder cap
- **Code path:** `CompositionExporter` renders every frame with an offscreen `CompositionRenderer`, the same class as the preview. So it used the preview's pool, its 4-decoder cap (`DecoderKeys.MAX_DECODERS`) and AUD2 B4's rule (beyond the cap the lowest layers are left out of that frame).
- **Test:** 6 overlapping layers of the typical clip, each with its own URI spelling (6 decoders) and in-points 7 s apart, tiled 3x2 at 1/3 scale over a magenta solid. 2 s exported, the file decoded, every tile centre checked on 13 sampled frames. A sanity check confirms magenta shows in the gaps.
- **Before: FAIL.** The 2 lowest layers were missing on every sampled frame (26 of 78 tiles). The export renderer reported 2 layers left out per frame. The export took 57.5 s.
- **Fixed in this pass** (`4cc6e9f`): export gives every layer a decoder. When all pooled decoders are still needed by the frame, it closes the one whose next use is farthest away (`DecoderKeys.exportVictim`, Belady), after a flush, and reopens it when that layer comes round. That costs about N − S reopens per frame. The preview keeps AUD2 B4's rule.
- **After: PASS.** 0 of 78 tiles missing; 0 layers left out per frame. The export now takes **108.6 s** for 2 s of 6 layers (it reopens decoders: slower, as allowed).

### A1. Non-debuggable measurement (the `benchmark` build type)
- **Setup:** `initWith(release)`, not debuggable, R8 minify + resource shrinking, the debug signing key, `applicationIdSuffix ".bench"` (no uninstall needed). Instrumentation targets it with `-PfluxxTestBuildType=benchmark`; debug stays the default and `gradle.properties` is unchanged. It ships what release ships (the release source set, no debug fixtures).
- **What it took to run the in-process tests** (each found on the phone):
  - The media benchmarks moved to `src/androidTestMedia`. Other instrumented classes use debug-only text proof helpers and do not compile against a release-like build.
  - **R8 broke the test runner three times**, all in library code the tests link against inside the app APK:
    - `androidx.tracing.Trace` removed (`NoClassDefFoundError`);
    - `kotlinx JobSupport.toString` made final under `allowoptimization` (`LinkageError`: kotlinx-coroutines-test subclasses it);
    - `androidx.collection.mutableIntObjectMapOf` removed (`NoSuchMethodError`).
  - So `benchmark-rules.pro` keeps AndroidX, Kotlin, kotlinx and the app's own package (no obfuscation).
  - **Consequence:** the variant measures the **non-debuggable runtime with release resources**, not R8's optimisation of library code. A real release should be at least as fast. Release's rules are unchanged.
- **Permission:** a new package has no `READ_MEDIA_VIDEO`, so every media class skipped. With Snxw's approval: `pm grant com.fluxx.android.bench android.permission.READ_MEDIA_VIDEO` (benchmark package only).
- **R8 regression check:** all four classes pass on the variant. MediaCodec, the Vulkan JNI and the reflection-based probes work.
- **Results (Part A code; debug = AUD2's final numbers on the same code and phone):** see the table in *Before / after*, rows A1.

### A2. Audits (read-only)

**A2.1 GPU copy path: zero-copy into the compositor.**
- MediaCodec renders into an `ImageReader` surface (`ImageFormat.PRIVATE`, `USAGE_GPU_SAMPLED_IMAGE`, `cacheFrames + 2` images).
- `Image.hardwareBuffer` goes to `RenderBridge.layer` and then `VulkanRenderer::setFrameLayer`. There a new buffer is imported as a `VkImage` with the Android external format (dedicated allocation). It is sampled through an immutable `VkSamplerYcbcrConversion`, so YUV to RGB happens in the sampler inside the video fragment shader. There is no RGB intermediate texture and no CPU copy.
- **Full-frame copies per video layer per frame: 0 before sampling.**
- **Memory traffic per layer per frame (1080p):**
  - the YUV read, about 3.1 MB (1.5 B/px, more with codec padding);
  - the blended write to the RGBA8 composition target (the covered pixels read and written, up to 2 × 8.3 MB full-frame).
- **The per-video-layer flush:** `CompositionRenderer` calls `flush()` before every video `frameAt`. So each video layer gets its own render pass, and each pass after the first LOADs and STOREs the whole target: about +16.6 MB per extra video layer at export size, less at preview size. This is MP2's "per-layer GPU flush".
- **Per frame, not per layer:**
  - the preview does one scaled `vkCmdBlitImage` to the swapchain;
  - export does one `vkCmdCopyImageToBuffer` (8.3 MB), then RGBA to YUV420 on the CPU into the encoder's input image.
- **CPU per layer per frame:** every decoded frame is a new `AHardwareBuffer`, and the native layer state keeps only the last one. So the `VkImage`, memory import, view and descriptor are re-created every frame. That is an import, not a copy.

**A2.2 What was already skipped (before B3)**

| Case | Evaluated | Draw | Decode |
|---|---|---|---|
| Hidden (eye off) | no (`FramePlan` drops it; text not prepared) | skipped | skipped; decoder / texture released after 3 s |
| Outside its clip (time) | no | skipped | skipped; the decoder stays open until slot pressure evicts it |
| Opacity 0 | yes, every frame | skipped | skipped (not in the decoder assignment) |
| Outside the frame | yes | **drawn** (the GPU clips it) | **decoded** |
| Zero area (scale 0) | yes | **drawn** (degenerate quad) | **decoded** |
| Covered by an opaque layer | yes | drawn | decoded |

The text tier pass evaluates every active text layer's animation each frame, whatever its opacity or position. Export uses the same renderer and the same rules.

**A2.3 Decoder pool at a clip hand-over**
- **The pool:** keyed `uri` / `uri#k` (one per distinct source time). At most 4 decoders (2 on low-RAM devices), a 96 MB estimate budget (w × h × 8 per decoder) and the codec's advertised instance count.
- **Same source on both sides of the cut:** the same decoder continues. Contiguous ranges decode on. A non-contiguous in-point is a `DecoderPlan` SEEK (flush, previous sync, decode forward), unless it lies less than 0.5 s ahead or inside what is already decoded.
- **Different source:** the next clip's decoder is opened synchronously on the preview thread, on the first frame that clip is active (extractor open, codec create / start, seek, first decode). Nothing is pre-opened.
- **The previous decoder is not released at the cut:** the 3 s delayed release covers only hidden layers (and sources no longer in the project). It stays until slot pressure evicts it, least recently used and never one the current frame uses.

**A2.4 Decode scope (before B2)**
- **PCM cache (`SourcePcm`):**
  - It starts on demand at Play (the first 0.5 s gates playback).
  - Then it goes on to `firstGap(0)` until the WHOLE source is decoded, on a daemon thread at `NORM_PRIORITY − 1`, never paused for playback.
  - The file is created sparse at the full logical size (frames × 4 B). The 256 MB LRU compared `file.length()`, the logical size.
  - **50 minutes:** about 580 MB logical, and all 50 minutes of AAC decoded in the background. That alone is over the cap, so every `get()` evicted all other sources' caches.
  - **Measured:** 161–416 s decoded within 20 s of Play, 31–80 MB written, still running.
- **Peaks (`AudioPeakProvider`):**
  - The whole first audio track, on `Dispatchers.IO` at default priority, when a twirl-down audio row is shown.
  - **50 minutes:** about 6 MB in memory, the whole AAC track decoded. **Measured:** not finished after 40 s (39 s of CPU), aborted.
- **Timeline thumbnail:**
  - One 160 px `ContentResolver.loadThumbnail` per SOURCE: MediaProvider picks the frame and caches it on the system's disk. The app keeps an 8 MB in-memory LRU.
  - `Dispatchers.IO`, 3 at a time, default priority, not paused during playback.
  - It ignores the used range. It never decodes the whole source.
  - **For a `file://` URI it fails** (`FileNotFoundException`, no thumbnail). This mattered later (B2.1).

**A2.5 Codecs** (`MediaCodecList.findDecoderForFormat`):

| File | Video | Audio |
|---|---|---|
| VP9 worst case | `c2.mtk.vp9.decoder` (hardware) | `c2.android.aac.decoder` (software) |
| H.264 typical | `c2.mtk.avc.decoder` (hardware) | `c2.android.aac.decoder` (software) |
| HEVC long source | `c2.mtk.hevc.decoder` (hardware) | `c2.android.aac.decoder` (software) |

### A3. Baselines (before any fix, on the charger)

**Backward-scrub display rule.** The AUD2 scrub: 20 moves 16 ms apart. Three cases per file, backward twice and forward once. Every frame the decoder returned is compared with its target (`shown` vs `t`).

| File, case | Frames shown later than the target (backward, 2 tries) | By how much | First frame | Longest gap |
|---|---|---|---|---|
| VP9, control at the middle | 5 / 18 | p50 450, max 1200 ms | 76–115 ms | 197–347 ms |
| VP9, after Pause at the middle | 9 / 18 | p50 400, max 1200 ms | 88–105 ms | 175–312 ms |
| VP9, after a tap seek | 11 / 8 | p50 100, max 400 ms | 98–110 ms | 299–311 ms |
| H.264, control | 3 / 5 | p50 133, max 200 ms | 182–534 ms | 348–1719 ms |
| H.264, after Pause | 6 / 5 | p50 100, max 200 ms | 237–622 ms | 412–622 ms |
| H.264, after a tap seek | 6 / 6 | p50 67, max 233 ms | 87–94 ms | 502–511 ms |

Forward scrubs: 0 frames ahead in every case.

**30 clips end to end** (30 × 2 s, 60 s, played from 0 to the end; window ±300 ms around each of the 29 cuts):

| Variant | Worst cut interval | Cuts with a frame > 100 ms late | Late frames (> 2 intervals) at cuts | Decoder at cuts | A/V median per cut |
|---|---|---|---|---|---|
| (i) contiguous ranges, one file | 55 ms | 0 / 29 | 0 | 0 opens, 0 seeks | +1 to +15 ms |
| (ii) non-contiguous in-points | 618 ms | **18 / 29** | 30 | 29 seeks (1 per cut) | +1 to +19 ms |
| (iii) alternating H.264 / VP9 | 1321 ms (the first VP9 open) | **27 / 29** | 34 | 1 open, 31 seeks | +1 to +13 ms |

- **Pre-opening (MP2) is needed** for (ii) and (iii): each non-contiguous cut seeks the shared decoder, and every switch of source flushes and seeks.
- **Underruns: 1 per run,** always at the end: the composition ends and the track drains. No run had an underrun inside the playback.

**Long source** (`Project 3`, 5 s trimmed at 0:00 / 25:00 / 49:00, each at timeline 0; battery 36.7 C, charging):
- **Layout:** the moov (seek index) is at the END of the file (offset 5.02 GB, 1.0 MB). Extractor open (test-side) is still fast, 88–119 ms; the seek to sync 12–35 ms. The decoder is `c2.mtk.hevc.decoder` (hardware).

| Position | First frame after load | Play → first frame | First sound | PCM after 20 s | Background decode at 20 s | Process CPU in 20 s |
|---|---|---|---|---|---|---|
| 0:00 | 23 ms | 116 ms | 1018 ms | 161 s decoded, 31 MB | **still running** (aborted) | 23.2 s |
| 25:00 | 197 ms | 72 ms | 715 ms | 327 s, 63 MB | **still running** (aborted) | 23.5 s |
| 49:00 | 123 ms | 91 ms | 593 ms | 416 s, 80 MB | **still running** (aborted) | 22.0 s |

- **Peaks:** the whole first audio track; not finished at the 40 s limit (39 s of CPU), aborted.
- **Thumbnail:** none (`file://`: `FileNotFoundException`).

**Culling baseline** (4 video layers of the typical clip, separate decoders, 10 s from the middle; the windows' lengths were not checked in this run, see Deviations):

| Case | Preview interval p50 / p95 | Render p50 | Decoder requests (frameAt p50) |
|---|---|---|---|
| 4 visible, 2x2 | 260 / 352 ms | 241 ms | 166 (47 ms) |
| 4 fully outside the frame | 42 / 125 ms | 37 ms | **984** (4.8 ms): decoded and drawn although invisible |
| 1 still + 1 that leaves for 1 s and re-enters | 56 / 74 ms | 49 ms | 398 |

Re-entry: the worst interval around the entry was 72 ms; 2 frames were later than 2 intervals (66.7 ms).

**Background work vs playback** (typical clip, 15 s from the middle):
- **Preview p95:** 43.9 ms warm, 43.4 ms with a cold PCM + peak decode running (−1 %).
- **UI p95:** 49.6 / 35.2 ms.
- **The cold decode ran at full speed during playback:** 123 s of PCM and the whole peak track in 15 s. On this phone it did not hurt playback pacing.

## Part B: fixes (measured after each, against Part A)

### B1. Backward scrub never shows a frame ahead of the finger (`80a621b`)
- **The floor rule (`ScrubFloor`, pure and JVM tested; the decoder runs exactly it).** While scrubbing, the frame shown is the nearest decoded frame AT OR BEFORE the target:
  1. the cache's exact floor;
  2. otherwise, when the decoder stands before the target in its GOP (or less than 0.5 s before it), a bounded forward decode (at most 4 frames) that stops once a frame passes the target; that frame is cached, not shown;
  3. otherwise the nearer of the cached floor and the sync frame at or before the target: the cache if it is at or after that sync frame, else a seek and the same bounded decode.
- The exact frame after the 120 ms settle is unchanged.
- **After** (final code, on the charger): **0 frames later than the target**, in every case, backward and forward, both files.

| File | First frame, backward (before → after) | Longest gap, backward (before → after) | Exact frame after release |
|---|---|---|---|
| VP9 | 76–115 → 73–133 ms | 175–347 → 289–393 ms | 25–73 ms |
| H.264 | 87–622 → 72–267 ms | 348–1719 → 382–590 ms | 0–53 ms |

- **The cost:** on VP9 the longest gaps grow by about 50–100 ms. A later cached frame no longer counts as an answer, so a step that has only later frames in the cache decodes from the sync frame instead.

### B2. Decode only the used range (`85fcc88`, `2899516`)
- **Used range:** each clip's in-point to out-point plus 10 s each side, clamped and merged per source. Clips far apart in one long file stay separate windows. No whole-source background decode of any kind is left.
- **PCM cache:**
  - It decodes what a reader needs: PLAYBACK priority, the first 0.5 s at a start, then 3 s ahead of the play position.
  - Otherwise only the used range, at background priority (B5).
  - The cap counts **allocated** bytes: the files are sparse.
  - Background filling stops before one source alone reaches the cap (partial coverage).
- **Peaks:** the clip's used range only. A widened trim decodes just the missing part (seeded from the cached pyramid). The file format is v4 (the covered ranges and their buckets only).
- **Timeline thumbnail (video):** a frame from inside the used range (the in-point plus up to 1 s, nearest sync). It waits while playing or scrubbing, decodes at background priority, and is cached in memory and on disk (16 MB LRU).
- **Found on the phone and fixed:**
  1. **A restart loop.** A run stopped at the scope's last frame left that chunk unfinished, and the worker restarted there forever (seen as 50+ restarts in 20 s). Runs now finish the last chunk.
  2. **A slower first sound** at mid and end positions. Setting the scope started the worker at the margin before the in-point, and Play's demand restarted it (a second open of the 5 GB file). The scope no longer starts the worker; playback starts the fill after its start is decoded.
  3. **The thumbnail strip** (B2.1). File-URI clips now get a thumbnail, and the old strip (one `Image` composable per 48 dp tile, hundreds on a long clip) was re-placed on every playback scroll step. The timeline never went idle (Compose idle timeouts in `Aud1BenchTest`, `Aud2ResumeTest`, `Aud2GainTest`; bisected to this) and UI frames doubled. It is now one canvas with the same tiles. Content-URI clips already paid that cost before MP1.
- **Extractor open and the moov at the end:** 87–119 ms on this 5 GB file. Nothing to fix.
- **After (long source, final scope code, on the charger):**

| Position | First frame after load | Play → first frame | First sound (before → after) | PCM after 20 s (before → after) | Background decode at 20 s | Process CPU in 20 s |
|---|---|---|---|---|---|---|
| 0:00 | 22 ms | 687 ms (see below) | 1018 → 1310 ms | 161 s / 31 MB → **16 s / 3.1 MB** | **stopped** (scope done) | 23.2 → 8.3 s |
| 25:00 | 201 ms | 97 ms | 715 → 671 ms | 327 s / 63 MB → **+26 s / +5.0 MB** | stopped | 23.5 → 12.6 s |
| 49:00 | 123 ms | 87 ms | 593 → 789 ms | 416 s / 80 MB → **+26 s / +5.0 MB** | stopped | 22.0 → 10.5 s |

- **Peaks:** the window 48:50–49:15 was ready in **5.1 s** (4.0 s of CPU); before, it was not finished after 40 s. 5.9 MB in memory: level 0 is indexed from the source start.
- **Thumbnail:** 160x90 from inside the clip. The clip row had already loaded it (same key), so the test call was a 6 ms cache hit and the decode cost was not isolated.
- **The 0:00 row:** this run's Play → first frame (687 ms) and first sound (1310 ms) are the slowest of three after-runs (the earlier ones: 232 / 260 ms and 1056 / 1091 ms). Within the 1 s / 1.5 s targets, but more than A3's 1018 ms; the cause was not isolated.

### B3. Safe culling (`079c90e`)
- **What is culled:** opacity 0; zero area (unless something adds area, e.g. a stroke); padded bounds fully outside the frame.
- **The bounds** come from the renderer's own draw matrices (`LayerGeometry.matrix` / `textMatrixInto`). Touching the frame edge is not culled.
- **Text** pads by its stroke, 1 em, the baseline shift and the SDF edge. Text with animators is never culled on position.
- **`CullPadding`** is the extension point for future effects and layer styles. **`RenderDependencies`** is the empty hook for mattes, effect references and precomps: a layer in it is never culled.
- **Evaluation never skips.**
- **Video:** a culled layer is not drawn. It keeps decoding (warm) while it becomes visible within 0.5 s, sampled ahead along its animation. Otherwise it is not decoded, and its decoder goes through the existing 3 s delayed release.
- **Parenting does not exist in the model,** so there is no hidden-parent case to test.
- **Differential golden test (`Mp1CullGoldenTest`): PASS, 103 frames byte-identical** OFF vs ON (the renderer's own YUV readback). Scenes:
  - scale 0 (both axes, one axis); fully outside; opacity 0;
  - animating in from outside (7 frames around the entry);
  - touching the edge, partly outside; rotation 90°;
  - text with a stroke (outside / partly in); text with an animator, layer outside;
  - stacked with and without culled layers;
  - a video culled for 1 s, then re-entering (every frame for 2 s).
  - Culling really happened: for example 17 video decodes skipped and 15 warm decodes in the re-entry scene.
- **The saving** (4 layers fully outside, final code, on the charger): render p50 36.8 → **9.4 ms**; decoder requests 984 → **0**; preview interval p50 42 → 33.4 ms (the full 30 fps). The 4 decoders were released after the delay (4 closes).
- **Re-entry** (1 still + 1 returning layer): the worst interval around the entry is **74 ms** (1 frame over 2 intervals). That is the 2-layer steady pacing (p95 74.5 ms, p99 98 ms), not a re-entry stall: the returning layer had been decoding warm for 0.5 s.

### B4. Release readiness: baseline profile (see Commits)
- **Generation needs a disallowed command.** The Macrobenchmark `BaselineProfileRule` collects and compiles profiles through `cmd package compile` / profile dumps. So `app/src/main/baseline-prof.txt` is **written by hand**: wildcard `HSPL` rules for the app's hot packages.
  - startup and the shell (`MainActivity`, `ProjectManager`, theme, navigation, home);
  - opening the editor (editor, model, schema);
  - the editor screen, timeline, preview and common UI; the inspector post-startup;
  - preview, play / pause and scrub (render, engine, media); fonts post-startup.
- **What ships:** the build merges it with the libraries' profiles into `assets/dexopt/baseline.prof`: 10.2 KB without the app rules, 12.4 KB with them. ProfileInstaller 1.4.0 is already on the classpath through Compose.
- **Measured on the benchmark variant, with vs without** (final code, one run each, on the charger):

| | Without the app profile | With it |
|---|---|---|
| ART state after `adb install -r` | `status=verify` `reason=install` | `status=verify` `reason=install` (also after a run) |
| Process start → test start | 1561 ms | 1203 ms |
| Editor host → first preview frame | 1299 ms | 1236 ms |
| First scrub: first frame after the first move | 224 ms | 193 ms |
| First Play: first frame / first sound | 166 / 358 ms | 243 / 430 ms |

- **Reading:** both installs stay at `verify`. `adb install -r` does not compile from the embedded profile, and background dexopt (idle and charging) did not run during the session. So the differences above are run-to-run noise, not the profile. Its effect is **not measured**. It applies on a Play Store install, or after the device's background dexopt.
- The debug variant is unaffected (debuggable builds ignore baseline profiles).

### B5. Media work priority scheduler (`0d0fa62`)
- **`MediaWorkPolicy` (pure, JVM tested):** priorities PLAYBACK < SCRUB < BACKGROUND. Background runs only when nothing is playing, no scrub is active and 300 ms have passed since. Jobs outside the used range are cancelled.
- **`MediaWork`:** process-wide state, fed by the preview. Background jobs call `checkpoint()` and run at `THREAD_PRIORITY_BACKGROUND`.
- **Users (B2's jobs):** the PCM fill beyond what a reader needs, peaks, thumbnails.
- **After** (typical clip; cold PCM + peaks for a never-seen source):
  - preview p95 warm **43.0** vs with background **43.2 ms (+1 %)**; target ≤ 10 %;
  - UI p95 61.0 vs 40.3 ms;
  - the cold decode advanced 13 s, nearly all in the 1.5 s settle before Play; it then paused for the 15 s of playback, and the peaks waited. Before B5, 123 s were decoded during the playback.
- **Cold first sound** (the typical clip under a never-seen URI, empty cache): **487 ms**. AUD1 recorded 1.18 s.

## Commits (branch `aud1`, on top of `48b57a5`)
- `eb83345` A: probes, MP1 classes, the benchmark build type, the media tests moved to `src/androidTestMedia` (before any fix).
- `8732645` A1: benchmark keep rules found on the phone.
- `4cc6e9f` A0: export never leaves a layer out.
- `80a621b` B1: the scrub floor rule.
- `0d0fa62` B5: the media-work scheduler (before B2, which uses it).
- `85fcc88` B2: decode only the used range (PCM cache, peaks, thumbnails).
- `079c90e` B3: safe culling, the differential golden test, `Mp1Test`.
- `2899516` B2.1: the scope start, the thumbnail job, the one-canvas strip (found on the phone).
- `aa95081` B4: the baseline profile and `Mp1StartupTest`.
- This record: `MP1.md`, `TLP.md`.

## Targets

| Target | Result |
|---|---|
| Export: all 6 layers on every sampled frame | **Met after the fix:** 0 of 78 tiles missing (before: 26, the 2 lowest layers). Slower: 108.6 s for 2 s. |
| Backward scrub: zero frames later than the target | **Met:** 0 in every case, both files, both directions (before: up to 11 per scrub, up to 1.2 s ahead). |
| Backward scrub: first frame about 150 ms or better | **Met on VP9** (73–133 ms) and on H.264 after a tap seek (72–89 ms). **Not met on H.264** control (169–235 ms; AUD2 216–235) and after Pause (229–267 ms; AUD2 59–87). AUD2's fast after-Pause answers were the LATER cached frames this pass removes. After playback the cache holds the frames around the pause point, so the first backward step has no cached frame at or before it and seeks to the previous sync frame (H.264 GOPs up to 3.7 s). |
| Backward scrub: stall gaps reported | VP9 289–393 ms (before 175–347); H.264 382–590 ms (before 348–1719). |
| 30 clips: no boundary frame later than 100 ms | **Not met** (not in this pass's scope). Contiguous ranges 0/29 cuts. Non-contiguous in-points 18/29 → 24/29; alternating sources 27/29 → 28/29 (worst 618 / 1321 ms → 869 / 1470 ms, one run each). **Limiting cause:** every non-contiguous cut flushes and seeks the shared decoder, and every source switch opens (once) or seeks. Pre-opening the next decoder (MP2) is needed. |
| Long source: first frame within about 1 s | **Met:** 22–201 ms after load; 87–687 ms after Play. |
| Long source: first sound within about 1.5 s | **Met:** 671–1310 ms (before 593–1018 ms; see B2 for the 0:00 case). |
| Long source: audio cache for the used range plus margin only (about 5 MB) | **Met:** 3.1 MB at 0:00 (16 s), then +5.0 MB per position (26 s). Before: 31–80 MB and still growing. |
| Long source: no whole-file background decode | **Met:** the worker stops when the window is covered (stopped at 20 s in all 3 positions). Before: still decoding the whole file at 20 s. |
| Long source: thumbnails and peaks scoped to the used range | **Met:** peaks 48:50–49:15 in 5.1 s (before: the whole track, not done at 40 s); the thumbnail is a frame inside the clip. |
| Long source: extractor open time and codec reported | 87–119 ms (moov at the end of the 5 GB file); `c2.mtk.hevc.decoder` (hardware). |
| Culling: pixel-identical differential test | **Met:** 103 frames byte-identical. |
| Culling: no stall at re-entry (no frame later than 2 frames) | **Not met by 7 ms:** the worst interval around the entry is 74 ms, against 66.7 ms. It equals the steady 2-layer pacing (p95 74.5 ms); the returning layer was decoding warm. Before: 72 ms, 2 frames over. |
| Culling: saving reported | 4 layers outside the frame: render p50 36.8 → 9.4 ms; decoder requests 984 → 0 per window; the full 30 fps. |
| Release variant: debug vs benchmark for each metric; the baseline profile's effect | Reported (A1, B4). **The profile's effect is not measurable** here: `adb install -r` leaves the package at `verify` (no profile-guided compile). |
| Scheduler: playback p95 with background work within 10 % of warm | **Met:** +1 % (43.2 vs 43.0 ms). |
| Scheduler: cold first sound about 1.2 s or better | **Met:** 487 ms (typical clip, empty cache); 671–1310 ms on the 5 GB HEVC file. |
| No regressions: resume | **Met with two outliers:** first frame 36–75 ms (restart after the end 131–270 ms; AUD2 152–204). Every case jumps ≤ 2 frames after 150 ms except H.264 "after Play ran to the end" try 1 (167 ms, 10 frames skipped) and H.264 "trimmed" try 1 (100 ms, 2 skipped). |
| No regressions: beat offset within ±20 ms | **Met:** medians +1.4 / +17.3 / −0.1 ms. |
| No regressions: 0 underruns | **Met:** AUD1 0 / 0 / 0. MP1 classes that play to the composition's end record 1: the track draining at the end (before and after). |
| No regressions: gain live | **Met:** no crash at 150 / 200 %, 61 live updates, playback continues, one undo. |

## Before / after

**A1: debug vs the non-debuggable benchmark variant** (the same Part A code; debug = AUD2's final numbers; benchmark = this pass, on the charger, Battery Saver off, 12:07–12:20):

| Metric | Debug | Benchmark |
|---|---|---|
| First sound, cold PCM cache (fresh package) | 1.18 s (AUD1) | 0.63 s (10:07; Battery Saver state unknown then) |
| First Play (warm) / after a move edit / after a gain edit | 0.39 s / 0.09–0.16 s | 0.57 / 0.09 / 0.14 s |
| 1 layer: frames in 20 s, interval p50 / p95 | 686, 30.3 / 55.6 ms | 660, 32.7 / 45.2 ms |
| 2 layers | 334, 56.4 / 109 ms | 480, 39.4 / 71.8 ms |
| 3 layers | 264, 73.4 / 140 ms | 219, 99.3 / 121 ms |
| 4 layers | 88, 244 / 319 ms | **182, 98.7 / 220 ms** |
| 6 layers | 94, 250 / 316 ms | **144, 142 / 241 ms** |
| VP9 + 2 H.264 | 258, 81 / 128 ms | 257, 81.4 / 114 ms |
| 3 layers on one source, in-points 0 / 10 / 20 s | 260, 77 / 154 ms | 231, 97 / 129 ms |
| Resume: first frame (restart after the end) | 36–88 ms (152–204) | 38–87 ms (124–196) |
| Resume: largest jump after 150 ms | ≤ 2 frames | ≤ 2 frames (33–67 ms) |
| Backward scrub, H.264 collapsed: first frame / longest gap | 59–235 / 145–824 ms | 66–207 / 118–566 ms |
| Underruns | 0 | 0 |

**Reading:** non-debuggable helps most where the preview thread is CPU-bound (4 and 6 layers: about 2x the frames, 2 layers +44 %). One and three layers are within run-to-run noise. A/V and resume are unchanged.

**MP1 parts** (debug; "after" = final code, on the charger):

| Metric | File | Before (A3) | After | Part |
|---|---|---|---|---|
| 6-layer export: tiles missing | H.264 ×6 | 26 / 78 | **0 / 78** | A0 `4cc6e9f` |
| Backward scrub: frames later than the target | VP9 | 5–11 per scrub, up to 1.2 s | **0** | B1 `80a621b` |
| | H.264 | 3–6 per scrub, up to 233 ms | **0** | B1 |
| Backward scrub: first frame | VP9 / H.264 | 76–115 / 87–622 ms | 73–133 / 72–267 ms | B1 |
| Backward scrub: longest gap | VP9 / H.264 | 175–347 / 348–1719 ms | 289–393 / 382–590 ms | B1 |
| Long source: PCM after 20 s (per position) | HEVC 5 GB | 161–416 s, 31–80 MB, still decoding | 16–26 s, 3.1–5.0 MB, stopped | B2 `85fcc88` `2899516` |
| Long source: process CPU in 20 s | | 22.0–23.5 s | 8.3–12.6 s | B2 |
| Long source: peaks | | whole track, not done at 40 s | 25 s window, 5.1 s | B2 |
| Long source: thumbnail | | none (file URI) | a frame inside the clip | B2 |
| Long source: first frame / first sound | | 23–197 / 593–1018 ms | 22–201 / 671–1310 ms | B2 |
| 4 layers outside the frame: render p50 / decoder requests | H.264 ×4 | 36.8 ms / 984 | **9.4 ms / 0** | B3 `079c90e` |
| Culling: OFF vs ON pixels | mixed scenes | – | 103 / 103 frames identical | B3 |
| Re-entry: worst interval | H.264 ×2 | 72 ms | 74 ms | B3 |
| Preview p95, warm vs with cold background work | H.264 | 43.9 vs 43.4 ms | 43.0 vs 43.2 ms | B5 `0d0fa62` |
| Cold background PCM during 15 s of playback | VP9 (cold) | 123 s decoded | paused (13 s, before Play) | B5 |
| Cold first sound, empty cache | H.264 | 1.18 s (AUD1) | 0.49 s | B2 / B5 |
| 30 clips: cuts > 100 ms late (i / ii / iii) | H.264, VP9 | 0 / 18 / 27 of 29 | 0 / 24 / 28 of 29 | measured only |
| UI frames p95 during playback (warm) | H.264 | 49.6 ms | 61.0 ms | (B2.1 strip fix: 124 → 61 ms) |

## Deviations
- **One adb command outside the allowed list,** approved by Snxw in the session: `pm grant com.fluxx.android.bench android.permission.READ_MEDIA_VIDEO`. The benchmark package is new, so it lacked the permission and every media class skipped.
- **The benchmark variant keeps AndroidX, Kotlin, kotlinx and the app's own classes** (R8 broke the in-process test runner three ways, see A1). It measures the non-debuggable runtime, not R8's optimisation of library code. `lintVitalAnalyzeBenchmark` crashed once (an internal lint failure, while sources were being edited during the build); measurement builds were made with `-x lintVital*`. Release's lint and rules are unchanged.
- **The media benchmark classes moved to `src/androidTestMedia`** (`git mv`). In debug they are compiled into androidTest with every other class, as before.
- **Commits vs measurement:** B2 and B5 share functions (the PCM worker's priority), so B5's scheduler is committed first (`0d0fa62`) and B2 uses it. The parts were built and measured together, as in AUD2. Mp1Test (every part's JVM tests) is in the B3 commit.
- **The power state invalidated one block of runs** (see Device log). These were re-run after Snxw restored the charger and turned Battery Saver off: the MP1 after-classes, the regression classes, the A1 benchmark classes and B4.
- **Re-runs, and why:**
  - `Mp1LongSourceTest`, 4 runs: before; after B2; after the chunk-end fix; after the power fix (each with a fresh URI spelling).
  - `Mp1ScrubTest`, `Mp1CullTest`, `Mp1BackgroundTest`, 3–4 runs each: after B, after fixes, after the power fix, after the strip fix.
  - The AUD regression classes: under Battery Saver, then twice after the power fix (both failed, Compose idle), then after the strip fix (pass).
  - `Aud1BenchTest#a1PrepareCosts` 2 extra: a re-run (still failing), and the bisect with the old thumbnail call (pass).
  - The A1 benchmark classes: 4 attempts that never started a test (R8 three times, then the permission), one full run on battery, then the run used here.
- **Measurement notes:**
  - The culling windows: in the "4 outside" case the pause after 10 s took effect only at 30 s (a Compose idle wait), so frame counts there cover 30 s. That is why the comparison uses per-frame intervals and render times.
  - Underruns of 1 in classes that play to the composition's end are the track draining at the end (before and after alike).
- **Thumbnails behave differently in the app:**
  - a timeline video clip shows a frame from inside its trim (it was the system's choice of frame);
  - file-URI clips now get one;
  - the strip is drawn by one canvas (same tiles).
- **The peak cache format** is v4: old peak files rebuild once; `Tl3AudioPeaksTest`'s version assertion 3 → 4.
- **Pre-opening the next decoder** was measured only, as instructed. It is needed (A3), and belongs to MP2.

## Not verified
- **Real eyes and ears:** what the A/V offsets, the scrub feel and the thumbnail strip look and sound like.
- **Other phones and codecs:** only the A16 (MediaTek: `c2.mtk.*` VP9 / AVC / HEVC, `c2.android.aac`). No AV1, no 4K, no 60 fps sources.
- **Thermal state** over a long session: the battery read 35–38 C; thermal status 1 (light) once, on battery.
- **The benchmark variant against a real release signing,** with R8 optimising the libraries; and the baseline profile's effect (needs a Play install or background dexopt).
- **The 0:00 long-source first sound** (1.31 s in the final run vs 1.02 s before). Within target, cause not isolated (one run).
- **Content-URI sources** (picker imports) for the B2 thumbnail path: only file URIs were benchmarked.
- **Culling during a real user session:** a layer animated out and back while scrubbing (the lookahead is forward-only).

## Tests
- **JVM:** **708 tests, 0 failures, 0 errors** (`testDebugUnitTest`; full run 69 s), including `InspectorIconFamilyTest` (6), `Aud1Test` (7), `Aud2Test` (14), `Tl3AudioPeaksTest` (4).
  - **New: `Mp1Test`, 11 tests:**
    - the floor rule: a random walk over 60 synthetic GOP layouts (shown ≤ target, exact means the stream's floor);
    - cache floor / exact floor;
    - the used-range window, merge and extension;
    - PCM scope targets, run limits and the chunk-end case;
    - allocated-bytes LRU for a source larger than the cap;
    - peaks v4 (partial coverage, round trip, widening);
    - the culling predicates (bounds from the renderer's matrix, edge, rotation, padding, zero area, opacity, unprovable text, dependencies);
    - the 0.5 s warm lookahead;
    - the scheduler (priorities, pause / resume after 300 ms, cancel, urgency);
    - export decoder rotation.
  - Targeted runs while iterating (`--tests`); **one full run on the final code** (after the last code change; a later invocation was up to date).
- **Device (A16), one class per invocation:**

| Class (label) | Result | Wall | Power |
|---|---|---|---|
| `Mp1ExportTest` before | **FAIL** (26 tiles missing) | 76 s | charger |
| `Mp1ScrubTest` before | 2 / 2 | 167 s | charger |
| `Mp1ClipsTest` before | 1 / 1 | 215 s | charger |
| `Mp1LongSourceTest` before | 1 / 1 | 121 s | charger |
| `Mp1CullTest` before | 1 / 1 | 97 s | charger |
| `Mp1BackgroundTest` before | 1 / 1 | 75 s | charger |
| Benchmark: `Aud1BenchTest#a1PrepareCosts` / `Aud2LayersTest` / `Aud2ResumeTest` / `Aud2ScrubTest#a3ScrubTypicalCollapsed` (first full run) | all pass | 37 / 263 / 291 / 205 s | battery |
| `Mp1ExportTest` after A0 | 1 / 1 | 140 s | battery, saver |
| `Mp1CullGoldenTest` | **1 / 1 (103 frames identical)** | 44 s | battery, saver |
| `Mp1LongSourceTest` after B2 / after the chunk-end fix | 1 / 1 each | 152 / 90 s | battery, saver |
| `Mp1ScrubTest` after B / after fixes | 1 / 2 each (VP9: Compose idle) | 424 / 212 s | battery, saver |
| `Mp1CullTest` / `Mp1BackgroundTest` / `Mp1ClipsTest` after B | 1 / 1 each | 82 / 107 / 212 s | battery, saver |
| Regression under Battery Saver: `Aud2ResumeTest` / `Aud1BenchTest` / `Aud2GainTest` | 0 / 2, 2 / 2, 0 / 1 | 95 / 302 / 47 s | battery, saver |
| `Mp1StartupTest` (benchmark, without / with the profile; pre-power-fix builds) | 1 / 1 each | 22 / 20 s | battery, saver |
| `Mp1LongSourceTest` final | 1 / 1 | 91 s | charger |
| `Mp1ScrubTest` after the power fix | 2 / 2 | 185 s | charger |
| `Mp1CullTest` / `Mp1BackgroundTest` / `Mp1ClipsTest` / `Mp1ExportTest` final | 1 / 1 each | 85 / 139 / 212 / 129 s | charger |
| `Aud2ResumeTest` / `Aud2GainTest` / `Aud1BenchTest` (old strip) | 0 / 2, 0 / 1, 0 / 2 (Compose idle) | 109 / 45 / 138 s | charger |
| `Aud1BenchTest#a1PrepareCosts` re-run / bisect (old thumbnail call) | 0 / 1 / **1 / 1** | 69 / 61 s | charger |
| **Final (strip fix):** `Aud1BenchTest` / `Aud2ResumeTest` / `Aud2GainTest` / `Mp1BackgroundTest` / `Mp1ScrubTest` | **2 / 2, 2 / 2, 1 / 1, 1 / 1, 2 / 2** | 225 / 345 / 78 / 88 / 134 s | charger |
| **Benchmark final:** `Aud1BenchTest#a1PrepareCosts` / `Aud2LayersTest` / `Aud2ResumeTest` / `Aud2ScrubTest#a3ScrubTypicalCollapsed` | **all pass** | 45 / 229 / 291 / 206 s | charger |
| **Benchmark B4:** `Mp1StartupTest` without / with the profile | 1 / 1 each | 25 / 20 s | charger |

## Device log
- **Connection:** wireless adb. Screen awake and unlocked before every class: none skipped for screen state.
- **Keep-awake:**
  - **before:** `stay_on_while_plugged_in` 0, `screen_off_timeout` 600000;
  - **during:** `svc power stayon true` (15), timeout 600000;
  - **after:** restored to 0 / 600000 at the end of the session.
- **Charging and power:**
  - on the charger (AC) from the start until about 10:04;
  - then on battery, discharging, with **Battery Saver on** (`settings global low_power = 1`), found at about 11:15;
  - Snxw plugged in and turned it off at about 11:17 (`low_power = 0`, checked at the start and end of every later chain).
  - Battery 79 % → 70 % on battery; 70 → 75 % after.
- **Battery temperature before heavy classes:** 35.0–37.8 C, never above the 42 C limit; no wait or skip was needed. Thermal status 1 (light) once, on battery.
- **Crash buffer:**
  - cleared at the start (it held older vendor library lines);
  - one foreign crash during the session (`com.microsoft.office.officehubrow:remote`, unrelated);
  - Fluxx crashes: only the benchmark test runner's 4 starts that failed on R8-removed classes (`NoClassDefFoundError` androidx.tracing; then a `LinkageError` and a `NoSuchMethodError` inside the test). None in the app's own code.
- **Runaway protection:**
  - **before B2,** each long-source position ended with the PCM worker still decoding the whole file: aborted at the 20 s limit (3×); peaks still decoding at 40 s: aborted (twice).
  - **after B2,** no background decode was running at any limit. Each class ended with `SourcePcmCache.release` and the peak job cancelled.
- **Interruptions:** none from the host. One benchmark build failed on a Kotlin daemon connection error and was retried.
- **Not done:** no uninstall, no `pm clear`, no `cmd package compile`, no app-data deletion, no project touched, no scripted taps outside the instrumentation, no wake / unlock. One `pm grant`: see Deviations.

## Phone test list (MP1, for Snxw)
1. **30 clips end to end:** put 30 two-second clips of one video on the timeline end to end, first with contiguous ranges, then with scattered in-points, then alternating two videos. Play it all. Contiguous should be smooth. Scattered and alternating cuts may show a visible hitch: pre-opening is MP2.
2. **The 50-minute file:** trim it to 5 s at the start, around 25:00 and around 49:00, each at the timeline start, and play each. You should get a picture within about 0.3 s and sound within about 1.3 s. The phone should stay cool and quiet afterwards (no background decoding of the whole file). Expand the layer: the waveform should appear within a few seconds.
3. **Backward scrub** on the H.264 clip and on the VP9 file. While the finger moves, the picture should never jump ahead of the finger. The exact frame should follow just after you stop.
4. **Export 6 layers** of different clips tiled on screen. Every layer should be in the exported file (it takes longer than before).
5. **A layer moved outside the frame and back during playback** (position keys). Playback should speed up while it is outside, and it should come back without a stutter.
6. **Timeline thumbnails:** a trimmed clip should show a frame from inside its trim, including clips imported as files.
