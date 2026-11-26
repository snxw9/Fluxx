<a id="rec-aud2"></a>

# AUD2: waveform during playback, pause / resume and backward-scrub video seek, gain, multi-layer

Branch `aud1`, continued from `361742a`. Not merged. Device: A16 (SM-A165F, Android 16, 4 GB), **debug (debuggable) build**.

**Benchmark files** (read in place on the removable card, `Download/Seal`, never copied; found through `StorageManager` volumes):
- **Worst case:** `Calc - Is There Someone Else？.mkv`, found by prefix. 52,833,831 bytes; VP9 1920x1440 20 fps; AAC 44.1 kHz; 122.8 s.
- **Typical clip:** the H.264 AMV edit, found by the substring `a-X8h8cgzOI` (its odd characters are never typed). 30,209,431 bytes; H.264 1920x1080 30 fps; AAC; 60.1 s.
- **Documents:** in memory only. `READ_MEDIA_VIDEO` was already granted, so no `pm grant` was run.

## Part A: measured before any fix

**Test-only hooks.** None change behaviour; each is null in the app.
- `VideoDecoder.testEvents`: open, frameAt with its seek target / frames decoded / duration, cancelled, close.
- `PreviewFrames.testPlayback` / `testClock`: playback create / close, track play, every clock read.
- `WaveformProbe`: the audio row's draw-phase range and its placement, and the timeline content's placement.
- The AUD1 frame observer, unchanged.

The classes are `Aud2WaveformTest`, `Aud2ResumeTest`, `Aud2ScrubTest`, `Aud2GainTest` and `Aud2LayersTest`, plus the shared `Aud2Bench`.

### A1. Waveform during playback (VP9, from 0 / 61 s / 90 s, collapsed and with the audio row open)

| Measure | At rest | Playing | Scrubbing |
|---|---|---|---|
| (b) where the row draws vs (d) the scroll offset, same frame | 0 | **0 px** (row edge minus content edge 0 px; draw-phase range centre minus laid-out time 0.0 ms) | 0 |
| Time under the line minus the playhead | 0 (61380.7 vs 61382.0 ms: sub-pixel) | – | – |
| Time under the line minus the preview frame on screen | 0 | median **0 to −50 ms** (one video frame, run-dependent) | +29 ms (the timeline leads the preview while the finger moves) |
| Time under the line minus what is HEARD | – | median **−33 to −85 ms** | – |
| Beat reaches the line minus beat heard (steady runs) | – | median +54 to +125 ms | – |
| Beat at the line minus heard, first seconds after Play at 61 s / 90 s | – | **+1.0 to +2.9 s** (the preview stalls: see A2) | – |

**Cause.**
- **The waveform row is not stale.** It draws at the scroll offset of its own frame. The suspected stale draw-phase read does not exist.
- **What Snxw sees has two parts:**
  1. **A stall.** At mid-file positions the preview video stalls 0.6–1.3 s per cycle after Play / Resume (the decoder seek spiral below). The timeline follows the preview's posted position, so it stalls and then jumps too: beats reach the line up to 2.9 s late.
  2. **A steady lag.** During normal playback the timeline shows the START time of the presented video frame, posted to the main thread and applied one UI frame later. That is 33–85 ms behind the sound.
- **Why it looks like "drift with scroll speed":** a fixed time lag becomes a pixel offset proportional to the scroll speed (here 1 px = 2.96 ms, so ~11–29 px).

### A2. Pause / Resume (both files; 2 tries per case)

| File, case | Resume → first frame | First frame that moves | Largest jump after 150 ms | Skipped | First sound | Decoder at Pause | Decoder at Resume |
|---|---|---|---|---|---|---|---|
| VP9, Play at 0 | 58–92 ms | 210–324 ms | 50–150 ms | 0–3 | 147–258 ms | – | none, or 1 flush to sync 0 (123 ms) |
| VP9, paused at ~3.5 s | 114–151 ms | 223–321 ms | 100 ms | 1 | 171–248 ms | none | **1 flush to sync 0, 1.22–1.24 s** (a cancelled first attempt 1.06 s) |
| VP9, paused mid (~64 s) | 52–53 ms | 188–228 ms | **600–750 ms (12–15 frames)** | 12–15 | 113–158 ms | **1 flush to sync 62.35 s, 0.71–0.79 s** | **2 flushes to 62.35 s, 0.55–1.0 s each** |
| VP9, paused near the end (~118 s) | 47–58 ms | 209–238 ms | **450–500 ms** | 10–13 | 137–162 ms | 1 flush, 0.38–0.53 s | 1 flush, 0.40–0.51 s |
| VP9, after Play ran to the end | 146–152 ms | – | 150 ms | 3 | 209–241 ms | – | flush to 0 (cheap, 94 ms) |
| VP9, trimmed (in 61 s at timeline 0), Play at 0 | 38–168 ms | 175–326 ms | **1450–1500 ms (29–30 frames)** | 35–36 | 115–255 ms | – | flush to 57.2 s, **1.45–1.49 s** |
| VP9, trimmed, paused at source ~64 s | 53–61 ms | 213–226 ms | **850 ms (17 frames)** | 17 | 144–160 ms | 1 flush, 0.78–0.80 s | 2 flushes, 0.82–1.10 s |
| H.264, Play at 0 | 55–62 ms | 218–238 ms | 133–200 ms | 6–8 | 136–149 ms | – | none / 1 cheap |
| H.264, paused at ~4–8 s | 41–52 ms | 214–222 ms | 67 ms / **1100 ms** | 3 / 71 | 133–152 ms | 1 flush, 0.57–0.82 s | 0 / **3 flushes to sync 1.3 s, 0.85–1.07 s** |
| H.264, paused mid (~34–36 s) | 50–62 ms | 222–233 ms | 33–200 ms | 2–10 | 150–153 ms | 0–1 flush, 0.19 s | 0–1 flush, 0.17 s |
| H.264, paused near the end (~57.5 s) | 51 ms | 184 ms | **767 ms (23 frames)** | 24 | 126 ms | 1 flush, 0.74 s | **2 flushes to 54.77 s, 0.74–0.92 s** |
| H.264, after Play ran to the end | 128–165 ms | – | 100–133 ms | 4–5 | 192–235 ms | – | cheap flush to 0 |
| H.264, trimmed (in 30 s), Play at 0 | 60–65 ms | 235–258 ms | 33 ms | 2 | 156–170 ms | – | none |
| H.264, trimmed, paused at ~33.6 s | 36–48 ms | 167–226 ms | 133–200 ms | 7–8 | 101–148 ms | 1 cheap flush (0.07–0.09 s) | 1 cheap flush (0.11–0.17 s) |

- **Clock at Resume (every case):** the position sat at the start until the first valid AudioTimestamp (140–291 ms), then jumped **+80 ms** (the fallback `head − getLatency` is clamped to the start for the first 341 ms). Early timestamps also stepped back by up to one frame; the largest backward steps were −69 and −428 ms.
- **The video decoder was never re-created** at Pause or Resume (opens 0, closes 0).
- **One invalid case:** H.264 "near the end, try 2" started from 0 (the previous case left the controller at the end). It is excluded.

**Cause (lag then skip).**
1. **Pause.** `pause()` re-renders the position the UI last received, which is often ONE FRAME BEHIND the decoder's last request (a race between the worker's last render and the main-thread position). `VideoDecoder.frameAt` treats any backward request as a seek: it flushes, seeks to the previous sync frame and decodes forward. That costs 0.4–0.8 s on VP9 and up to 0.8 s on the long H.264 GOPs.
2. **Resume.** The clock is not monotonic for the first ~300 ms (+80 ms jump, then an occasional one-frame step back). The step back flushes again. During that flush the audio clock runs on, so the next request is more than 0.5 s ahead. The forward-jump rule then flushes and seeks back to the SAME sync frame and decodes even further. It repeats 2–3 times (VP9 mid: 62.35 s each time), so the preview falls behind by 0.6–1.5 s and then drops 12–36 frames to catch up.
3. **Why not at source 0.** The previous sync frame is 0–1 frames back, so the same flushes cost about 0.1 s. It follows the SOURCE position: it is the distance to the previous sync frame.

### A3. Backward scrub (typical clip) and keyframe spacing
- **Keyframe spacing:**
  - **VP9:** 2455 frames, 38 sync frames; interval p50 2.95 s, p95 5.2 s, max 5.3 s.
  - **H.264:** 1800 frames, 329 sync frames; interval p50 33 ms (runs of all-intra frames at cuts), p95 0.8 s, max 3.67 s.
- **H.264 scrubs** (2 tries; first new frame after the first move / longest gap without a new frame):

  | Case | Forward | Backward, collapsed | Backward, 3 twirl-downs |
  |---|---|---|---|
  | Control (no playback), middle | 100 / 193–271 ms | **335–344 / 599–627 ms** | 184–362 / 535–697 ms |
  | After Pause at the start | 226–236 ms | **280–2135 / 1170–2135 ms** | 55–569 / 2310–3090 ms |
  | After Pause at the middle | 103–109 ms | **229–1671 / 1111–1671 ms** | 911–2233 ms |
  | After Pause near the end | 100–438 ms | **32–1769 / 1732–1769 ms** | 2064–2082 ms |
  | After playback ran to the end | (at the end) | **2572–2676 ms** | 64–2748 / 2493–2748 ms |
  | After a tap seek | 100–101 ms | **1055–1143 ms** | 557–1113 ms |

- **Decoder:** 1–15 flush + seeks and 7–19 cancelled decodes per backward scrub.
- **Cause (as AUD1 B7):** every backward request flushes and seeks to the previous sync. Each scrub step cancels the decode in flight, and the next step then flushes again, because its target lies before the cancelled one.

### A4. Gain
- **Crash:**
  - **Stack:** `IllegalArgumentException: Failed requirement` at `CompositionLayer.<init>:363` (`require(audioGain in 0f..1f)`) ← `copy` ← `EditorReducer.reduce` (SetAudio, line 288) ← `EditorViewModel.dispatch:177`.
  - **When:** for 150 % and 200 % (the slider maximum), stopped and playing; 100 % does not throw. In the app this is uncaught on the main thread. The test caught it on the main thread, so the crash buffer stayed empty.
  - **Root cause:** the compact inspector's gain slider (`ElementInspectorSheet.CompactAudioSectionContent`) has `valueRange = 0f..2f`, and its direct entry clamps to 0..2. The model allows only 0..1.
  - **The mix itself already saturates** (`coerceIn(-1, 1)` before 16-bit), and the headroom divides by the summed gain, so there is no wrap.
- **One slider step** (the app's `onAudioChange`):
  - A full `SetAudio` dispatch, i.e. one undo entry PER STEP (60 entries for a 60-step drag), plus `PreviewController.update(.., play = false)`.
  - That bumps the request generation: playback is CLOSED at the first step (it stops), and the frame is re-rendered at every step (60 renders, no decode).
  - Main-thread time per step: 1.5–5.0 ms (p50), p95 4.3–5.2 ms.
  - `AudioMixer.signature` is no longer used anywhere (dead since AUD1), so it does not cause this.
  - **UI frame pacing of the slider drag is NOT measured:** opening the inspector from the test failed ("Failed to inject touch input"), so the slider was not on screen.
- **Resume after a gain change while playing:** first frame 63–83 ms, first sound 137–173 ms, then a **400–750 ms jump** (the A2 resume spiral).
- **Plain playback UI frames** (the reference): p50 24–30, p95 31–54 ms.

### A5. Multi-layer (typical clip, separate decoders through distinct URI spellings; 20 s from the middle)

| Layers | Preview interval p50 / p95 / max | Frames in 20 s (target 600) | Skipped | A/V offset median | Underruns | Decoders | Graphics / PSS during |
|---|---|---|---|---|---|---|---|
| 1 | 32.6 / 45.6 / 121 ms | 623 | 19 | +0.5 ms | 0 | 1, no reopen | 84 / 195 MB |
| 2 | 46.7 / 71.5 / 169 ms | 450 | 195 | −25.6 ms | 0 | 2 | 143 / 249 MB |
| 3 | 75.4 / 123 / 171 ms | 271 | 338 | −64 ms | 0 | 3 | 183 / 280 MB |
| 4 | 246 / 302 / 327 ms | 91 | 507 | −254 ms | 0 | 4 (frameAt p50 45 ms) | 233 / 323 MB |
| 6 | 1511 / 2426 / 2599 ms | **11** | – | – | 0 | **72 opened + 72 closed in 20 s** | 184 / 269 MB |
| VP9 + 2 H.264 | 74 / 100 / 132 ms | 293 | 341 | – | 0 | 3 | 276 / 366 MB |
| 3 layers on ONE URI, in-points 0 / 10 / 20 s | 599 / 984 / 1433 ms | 29 | 528 | – | 0 | 1 shared: **89 flush + seeks** | 94 / 183 MB |

- **Memory:**
  - **Baseline** (1 layer, paused): Java 13.0, native 13.5, graphics 108.6, total PSS 217.3, RSS 285.5 MB.
  - **Per extra 1080p decoder:** about +50 MB graphics and +25–45 MB PSS.
- **Codec failures:** no codec allocation failure and no `CodecException`.
- **Beyond the pool:** the renderer's pool (4 slots, 96 MB) evicts one layer's decoder to open another's on every frame. All layers then freeze together at about 0.5 fps (6 layers). There is no crash, audio is uninterrupted, and nothing goes black.
- **Shared source:** the same source on several layers with different in-points shares one decoder (the pool is keyed by URI) and flushes on every layer request.
- **Highest smooth count: 1 layer** (1080p30 H.264). Two layers play at ~21 fps (a third of the frames dropped); 3 or more are not smooth.
- **Limiting cause:** the layers are decoded one after another on the preview thread, each `frameAt` waiting for its own output (1 layer 6.6 ms p50, 4 layers 45 ms p50 each). It is not codec allocation.
- **UI frame pacing** in this class: FrameMetrics delivered no frames (not measured).

## Part B: fixes (each measured against Part A)

### B1. The timeline follows the heard clock while playing (`3d7a517`)
- **What changed:**
  - `PreviewController.playbackPositionAt(ns)` publishes the HEARD clock: the worker's last clock read, extrapolated; null unless the track runs.
  - While playing, `TimelineView` scrolls to the heard time one display frame ahead (`TimelineFollow.LEAD_NS` = 16 ms) with a raw scroll delta, never `scrollTo`, so it is not a scroll "in progress" and the scrub path never reads it as a drag.
  - When playback stops the view settles back on the playhead's frame x.
  - It still updates once per posted position (no extra frame loop, so the UI can go idle and the tests' idle waits still work). The waveform row is unchanged: it was already exact.
- **After (A1, same file and cases):**

  | Measure | Before | After B1 |
  |---|---|---|
  | Time under the line minus heard, at layout (median, 6 runs) | **−33 to −85 ms** | **+6 to +12 ms** (the 16 ms display lead: about 0 on the glass) |
  | Minus the preview frame on screen (median) | 0 to −50 ms | +28 to +47 ms (the timeline now leads the start of the shown 50 ms frame, as the sound does) |
  | (b) − (d), same frame | 0 | 0 |
  | At rest | exact | exact |
  | Scrubbing | timeline +29 ms ahead of the preview | unchanged (+29 to +30 ms) |

- **Target (within one video frame of the at-rest position, no drift with scroll speed):** met. The remaining offset is a constant 6–12 ms, independent of speed.
- **The start-of-playback stall** (beats up to 2.9 s late) is the decoder spiral, fixed in B2.

### B2. Video seek path: warm pause / resume, no re-seek spiral, cached backward steps, bounded scrub decode
- **What changed:**
  - **`VideoDecoder`:**
    - Keeps the last `DecoderPlan.cacheFrames` decoded frames: a 16 MB budget, 3 to 6 frames, so 5 at 1080p and 4 at 1440p.
    - The frames are held in a `FrameCache` by runs. A hit is EXACT only when the two frames around the target are consecutive in one decode run, so a cached answer is always the frame a straight decode would give.
    - `ImageReader` holds `cacheFrames + 2` images (was 4).
  - **`DecoderPlan.decide`:**
    - A request it can prove from the cache is served from memory.
    - A forward request seeks only if it is more than 0.5 s ahead AND its sync frame lies PAST what is decoded; otherwise it decodes on. This ends the spiral back to the same sync frame.
    - A request after a cancelled one continues from where that one stopped (retarget: the latest target wins, no flush).
    - Only a target before what is decoded and not cached seeks back.
  - **Scrubbing:**
    - `PreviewController` marks seeks that arrive within 250 ms of each other as a scrub (`ScrubPolicy`). The renderer then asks the decoders for a BOUNDED decode: at most 4 frames, or the nearest of a cached frame and the sync frame (one decode).
    - If any shown frame was approximate, the exact frame is rendered once no new request came for 120 ms.
  - **Clock (`AvClock` / `StreamingPlayback`):**
    - Never steps back: a backward step re-seeked the decoder.
    - The pre-timestamp fallback is the output path behind the head (`getLatency − buffer`, 85 ms here; not the whole latency).
    - A forward correction (the first timestamp, +80 ms) is SLEWED in at up to 1.5x real time, not jumped.
- **Decoder life cycle:** the decoder was never re-created at Pause or Resume, before or after (opens 0, closes 0). It stays warm.
- **Commits:** `c766d02` (B2), `84bcd9f` (B2.1).
  - **B2.1:** the B2 cache made every second request cost two decodes (an exact hit on the look-ahead frame returned without decoding the next one). Preview pacing went from p95 65 to 85 ms; fixed so a hit on the newest frame decodes the look-ahead.

**After B2: resume** (`Aud2ResumeTest`, final code, 2 tries per case):

| File, case | Largest jump after 150 ms, before → after | Skipped, before → after | Resume → first frame, after | First sound, after |
|---|---|---|---|---|
| VP9, Play at 0 | 50–150 → 50 ms | 0–3 → 0 | 60–88 ms | 145–278 ms |
| VP9, paused ~3.5 s | 100 → 50 ms | 1 → 0 | 58–59 ms | 147–149 ms |
| VP9, paused mid (~64 s) | **600–750 → 50 ms** | **12–15 → 0** | 41–49 ms | 134–145 ms |
| VP9, paused near the end | **450–500 → 50 ms** | **10–13 → 0** | 36–64 ms | 83–172 ms |
| VP9, after Play to the end (restart at 0) | 150 → 50 ms | 3 → 0 | **152–184 ms** | – |
| VP9, trimmed in 61 s, Play at 0 | **1450–1500 → 50 ms** | **35–36 → 0** | 63–136 ms | – |
| VP9, trimmed, paused ~64 s | **850 → 50 ms** | **17 → 0** | 50–51 ms | 144–153 ms |
| H.264, Play at 0 | 133–200 → 33–67 ms | 6–8 → 0–1 | 44–61 ms | 108–149 ms |
| H.264, paused ~3–4 s | 67 / 1100 → 33 ms | 3 / 71 → 0 | 54–57 ms | 125–150 ms |
| H.264, paused mid | 33–200 → 33–67 ms | 2–10 → 0–1 | 52–62 ms | 152–153 ms |
| H.264, paused near the end | **767 → 33 ms** | **24 → 0** | 44–49 ms | 106–152 ms |
| H.264, after Play to the end | 100–133 → 33 ms | 4–5 → 0 | **194–204 ms** | – |
| H.264, trimmed in 30 s | 33–200 → 33–67 ms | 2–8 → 0–1 | 54–58 ms | – |

- **The clock at Resume:**
  - It leaves the start with the first timestamp (121–330 ms).
  - The correction is now **+2.5 to +6 ms** (was +80 ms).
  - The largest step error is 7–19 ms; there is no backward step.
- **Pause:** no flush (the frame one back is a cache hit).

**After B2: backward scrub** (2 tries; first new frame after the first move / longest gap):

| Case | H.264 collapsed, before → after | H.264 3 twirl-downs, after | VP9 collapsed, after (AUD1: 1.8–3.8 s) |
|---|---|---|---|
| Control, middle | 335–344 → **216–235** / 599–627 → 340–350 ms | 195–252 / 286–412 ms | **113–117** / 194–339 ms |
| After Pause at the start | 280–2135 → **98–112** / 1170–2135 → 465–824 ms | 66–104 / 262–718 ms | 95–106 / 302–314 ms |
| After Pause at the middle | 229–1671 → **59–87** / 1111–1671 → 270–295 ms | 87–102 / 440–452 ms | 63–77 / 252–301 ms |
| After Pause near the end | 32–1769 → **88–91** / 1732–1769 → 266–287 ms | 89–93 / 249–299 ms | 80–85 / 263–298 ms |
| After playback ran to the end | 2572–2676 → **87–88** / 145–147 ms | 85–102 / 154–177 ms | 65–84 / 135–147 ms |
| After a tap seek | 1055–1143 → **86–93** / 503–534 ms | 79–91 / 548–575 ms | 82–86 / 252–285 ms |

- **Forward scrubs are unchanged** (VP9 forward 78–113 ms, gaps 148–219 ms).
- **What the frames while the finger moves are:** the nearest cached or sync frame. The exact frame follows 120 ms after the last move: "caught up after the last move" is 0–150 ms everywhere (was up to 0.96 s).
- **Sync-frame spacing effect:** VP9 has long GOPs (p50 2.95 s), but during a scrub it now shows cached / sync frames, so it is as fast as H.264. The long GOP only costs the exact frame after release (bounded by the GOP).

### B3. Gain (`e895029`)
- **What changed:**
  - **Crash, at the root cause:** the compact inspector slider (`valueRange 0f..2f`) and its typed entry (clamped to 0..2) now use the model's range, `AudioGains.RANGE` = 0..1, through `AudioGains.clamp`. The model and schema are unchanged (v13).
  - **Why nothing louder is lost:** under the existing headroom (it divides by the peak summed gain), a single layer above 100 % played at 100 % anyway.
  - **Saturation:** the mix saturates through `AudioGains.saturate` (it already clamped; now one tested function).
  - **One gesture:** a slider drag is one gesture (`GainEdits.begin / preview / commit`; `previewGesture` with `Batch(SetAudio)`), so one undo step. A typed gain or mute is one edit.
  - **Live gain:** `PreviewController.previewGains` takes a gain-only change (`AudioGains.onlyGainsChanged`) LIVE:
    - The track volume (`AudioTrack.setVolume`) carries the overall gain, applied at once.
    - Each layer's weight relative to it goes to the mixer, read per 20 ms block (`BlockMixer.setGains`, volatile).
    - No new mix, no feeding-thread restart, no re-gate, no seek, no re-render.
  - **Edits that change what is mixed** (mute, a gain crossing 0, timing, visibility, duration) keep the AUD1 path.
- **After:**

  | Measure | Before | After |
  |---|---|---|
  | 150 % / 200 % / slider maximum, stopped and playing | **crash** (IllegalArgumentException) | no exception; held at 100 % (the slider maximum is 100 %) |
  | Undo entries per 60-step drag | 60 | **1** |
  | Playback during a drag | stopped at the first step | **keeps playing**: 0 playbacks created or closed, 61 live updates |
  | Main-thread time per step (p50 / p95) | 1.5–4.6 / 4.3–5.2 ms | 2.3–2.8 / 3.5–5.8 ms |
  | Step → gain applied (track volume set) | – | p50 2.8, p95 5.7, max 8.0 ms |
  | Step → heard (one audible layer) | – | **≈ 90 ms** (3 ms + the 85 ms output path, getLatency − buffer; not measured acoustically) |
  | Per-layer balance with several layers | – | after the ~286 ms (p50) already queued |
  | Resume after a gain change | 400–750 ms jump | 50–100 ms (≤ 2 frames) |

- **Crash buffer:** empty.
- **Not measured:** slider-drag UI pacing. The inspector could not be opened from the test ("Failed to inject touch input", twice).

### B4. Multi-layer (`6370cc7`)
- **What changed:**
  - **Per-frame assignment:** decoders are assigned per frame from the TOP layer down (`DecoderKeys.assign`), and a decoder the current frame uses is never evicted.
  - **The cap:** at most `DecoderKeys.MAX_DECODERS` = 4 decoders (`MAX_DECODERS_LOW_RAM` = 2). The lowest layers beyond the cap are left out of that frame (not drawn); the rest plays and audio is untouched.
  - **Shared sources:** the same source on several layers at different source times gets one decoder per time (`uri#k`). Layers within 50 ms share one.
  - **Why not "hold the last frame":** an `Image`'s HardwareBuffer must not be used after its decoder closes, and a parked decoder per extra layer costs ~50 MB.
- **After (typical clip, 20 s from the middle):**

  | Layers | Interval p50 / p95, before → after | Frames in 20 s, before → after | A/V median, after | Decoders, after | Graphics / PSS during, after |
  |---|---|---|---|---|---|
  | 1 | 32.6 / 45.6 → 30.3 / 55.6 ms | 623 → 686 (skipped 19 → 8) | +3.9 ms | 1 | 94 / 221 MB |
  | 2 | 46.7 / 71.5 → 56.4 / 109 ms | 450 → 334 | −30.5 ms | 2 | 150 / 261 MB |
  | 3 | 75.4 / 123 → 73.4 / 140 ms | 271 → 264 | −96 ms | 3 | 196 / 309 MB |
  | 4 | 246 / 302 → 244 / 319 ms | 91 → 88 | −238 ms | 4 | 252 / 355 MB |
  | 6 | **1511 / 2426 → 250 / 316 ms** | **11 → 94** | −247 ms | **72 opens + 72 closes → 0**; 4 open, 2 lowest left out | 260 / 360 MB |
  | VP9 + 2 H.264 | 74 / 100 → 81 / 128 ms | 293 → 258 | – | 3 | 281 / 375 MB |
  | 3 layers, ONE source, in-points 0 / 10 / 20 s | **599 / 984 → 77 / 154 ms** | **29 → 260** | – | 1 shared (89 flushes) → 3 keys, 0 flushes | 203 / 290 MB |

- **Highest smooth count: 1 layer** (1080p30 H.264). Two play at ~17–21 fps; 4 or more at ~4 fps.
- **Beyond the cap:** no crash, 0 underruns, playback continues, the top 4 play and the rest are left out. No codec allocation failure in any run.
- **Two layers were slower this run** (p50 56 vs 47 ms). B4 does not change that path apart from the decoder cache's extra ImageReader buffers, so this is noise or that memory. **Not settled.**
- **Limiting cause (layer rendering, not changed):** layers are decoded one after another on the preview thread, with a GPU flush before each video layer, so per-layer `frameAt` latency adds up (12 ms each at 2–3 layers, 45 ms each at 4). Per-decoder parallel look-ahead would be the next step.

## Targets

| Target | Result |
|---|---|
| Waveform within one video frame of the at-rest position during playback, no drift with scroll speed | **Met.** (b)−(d) = 0. The line is +6 to +12 ms vs heard (constant: the 16 ms display lead). The start stall is gone: beats +11 to +26 ms after heard (was up to +2.9 s). |
| Resume: first frame ~100 ms | **Met** at 0 / mid / end, both files: 36–88 ms (one try 136 ms). **Missed** after Play to the end: 152–204 ms (a real seek to 0). |
| Resume: after 150 ms no jump > 2 frames | **Met** in every case, both files. |
| Backward scrub, typical clip: first frame ~150 ms | **Met** in 5 of 6 cases (59–112 ms). The control case is 195–252 ms. |
| Backward scrub, typical clip: no stall > ~250 ms | **Not met:** longest gaps 145–824 ms. Each backward step past the cache still flushes, seeks and decodes a first frame, and the steps cancel each other. |
| Backward scrub, VP9 (best effort) | First frame 63–117 ms, gaps 135–339 ms (was 1.8–3.8 s). |
| Gain: no crash across the slider range, stopped and playing | **Met.** |
| Gain: a step heard within ~100 ms, no re-prepare | **Met for one audible layer** (≈90 ms, estimated). With several layers the balance follows after ~0.3 s. |
| Gain: slider-drag UI pacing p95 ≤ plain playback | **Not measured.** |
| Gain: resume after a gain change within the resume target | **Met.** |
| Layers | 1 smooth; beyond the cap no crash, 0 underruns, no thrash. |
| AUD1: edits ≤ 0.3 s | **Met:** 0.09–0.16 s. |
| AUD1: beat median ±20 ms | **Met:** +0.4 / +15.5 / +5.1 ms. |
| AUD1: 0 underruns | **Met.** |
| AUD1: cold first sound ~1.2 s | **Not comparable:** 0.39 s with the PCM cache already warm (it could not be cleared: `pm clear` and app-data deletion are not allowed). |
| AUD1: preview pacing | Restored by B2.1: interval p95 64–69 ms. **One of three runs had one ~1 s stall (23 frames skipped)**, cause not identified. |

## Deviations
- **`Aud2WaveformTest` before any fix ran 4 times,** to correct test defects:
  - The at-rest read had no draw to read.
  - The state sampling distorted the timing.
  - A draw-phase probe under-reported the frame rate; a layout probe replaced it, and it also under-reports (onGloballyPositioned misses layer-only scroll moves). The offsets are reported from both.
- **`Aud2GainTest` before any fix ran twice** (the second run tried to open the inspector).
- **After the fixes:** `Aud2ResumeTest` ran twice after B2 (the slew was added between). `Aud1BenchTest` ran once fully, then its playback method again after B2.1.
- **Lost run:** the host killed one background run (low memory on the computer). The interrupted scrub class was run again.
- **Scope:**
  - **B1:** `TimelineView`'s playback scroll sync, plus `PreviewController.playbackPositionAt`. The waveform row needed no change.
  - **B3:** the inspector's gain slider range and callbacks (`ElementInspectorSheet`, `EditorScreen`, new `GainEdits`), plus `PreviewController.previewGains`. The gesture protocol, `EditorAction` and the schema are unchanged; existing actions are composed in `Batch`.
  - **B4:** the renderer's decoder pool (keys and eviction), not layer rendering.
- **Commits per part:** B2, B3 and B4 were built and measured together and committed separately (hunks split per part). `Aud2Test` (all the B2–B4 JVM tests) is in the B4 commit.
- **B4 "holds its last frame":** not done. Layers beyond the cap are left out of the frame.
- **The gain slider's maximum is now 100 %:** above 100 % is no longer offered. Under the existing headroom it was not louder.

## Not verified
- Real ears and eyes: the gain "heard" time is estimated from getLatency − buffer.
- Other phones' decoders, other codecs (HEVC, AV1) and containers; thermal state.
- The display pipeline after the swapchain.
- A non-debuggable build.
- Slider-drag UI pacing.
- The multi-layer live gain balance by ear.

## Tests
- **JVM:** **697 tests, one full run, 58 s, 0 failures**, including `InspectorIconFamilyTest` and the AUD1 tests.
  - **New:** `Aud2Test`, 14 tests:
    - the frame cache (bound, eviction, run exactness) and the cache memory bound;
    - warm pause / resume;
    - forward seek only past the decoder, and retarget after a cancel;
    - the clock's monotonic rule, fallback and slew;
    - the scrub policy;
    - saturation and the gain range;
    - the live-gain split and the gain-only test;
    - decoder keys and cap;
    - the timeline follow / waveform x mapping.
- **Device (A16), one class per invocation:**

  | Class | Result | Wall |
  |---|---|---|
  | `Aud2WaveformTest` before (4 runs) | ran | 205 / 150 / 157 / 150 s |
  | `Aud2ResumeTest` before | 2 / 2 | 363 s |
  | `Aud2ScrubTest#a3KeyframeSpacing` | 1 / 1 | 12 s |
  | `Aud2ScrubTest#a3ScrubTypicalCollapsed` before / after | 1 / 1 each | 233 / 227 s |
  | `Aud2ScrubTest#a3ScrubTypicalThreeTwirlDowns` before / after | 1 / 1 each | 257 / 234 s |
  | `Aud2ScrubTest#a3ScrubVp9Collapsed` after | 1 / 1 | 214 s |
  | `Aud2GainTest` before (2 runs) / after | 1 / 1 each | 74 / 72 / 80 s |
  | `Aud2LayersTest` before / after | 1 / 1 each | 254 / 220 s |
  | `Aud2WaveformTest` after B1 / final | 1 / 1 each | 131 / 132 s |
  | `Aud2ResumeTest` after B2 / after the slew | 2 / 2 each | 313 / 317 s |
  | `Aud1BenchTest` (regression) / its playback method after B2.1 | 2 / 2, 1 / 1 | 189 / 186 s |

## Device log
- **Keep-awake:** stay_on 0 → 15 → 0 (restored), and the same in the second session. screen_off_timeout 600000 throughout.
- **Charging:** NOT charging for Part A, B1 and the first B2 runs. On AC power from the resume run after the slew onward.
- **Screen:** awake and unlocked before every class; none skipped.
- **Crash buffer:** cleared before the first run and before each gain run; empty at every check and at the end.
- **Interruptions:** one host-side background run was killed (low memory on the computer). The phone finished that instrumentation unattended; its output was lost and the class was re-run. No `pm grant`, no uninstall, no app-data changes, no projects touched.

## Phone test list (AUD2)
1. Play a long video from the middle, Pause, Resume. There should be no lag and no skip. Repeat near the end and right after the start.
2. Trim a clip so it starts mid-file, move it to the timeline start, Play, Pause, Resume. Same.
3. Scrub backward on the H.264 clip and on the VP9 file. Frames should follow the finger (an approximate frame while moving, the exact one just after you stop).
4. Expand the layer's twirl-down and play. The waveform should move with what you hear, at any zoom.
5. Drag the gain slider while playing. The sound should follow, playback should not stop, and one undo should restore it. The slider stops at 100 %; there is no crash.
6. Put the same clip on 2, 3 and 4 layers (also with different in-points), then 5+ different clips. No crash, no audio dropout; beyond 4 sources the lowest layers are left out.
