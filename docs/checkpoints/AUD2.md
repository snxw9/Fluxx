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
