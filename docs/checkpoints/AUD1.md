<a id="rec-aud1"></a>

# AUD1: audio playback and A/V sync

Branch `aud1`, created from `tlp` at `1768112`. Not merged. Device: A16 (Android 16), **debug (debuggable) build**.

**Benchmark file:** `Calc - Is There Someone Else？.mkv`. It sits on the removable card at `/storage/3532-3739/Download/Seal/` (52,833,831 bytes). It is 122.8 s long: VP9 1920x1440 at 20 fps, with AAC-LC audio at 44.1 kHz stereo. The first decoded pts is 0 and there is no encoder-delay key.

- **Read in place:** the tests found it by prefix through `StorageManager` volumes (listing `/storage` itself is not allowed under scoped storage). It was never copied.
- **Permission:** `READ_MEDIA_VIDEO` was already granted (user set), so no `pm grant` was needed.
- **Documents:** in memory only.

**Status:**
- **A (baseline):** done.
- **B1–B6:** done.
- **B7 (scrub after pause / playback):** stopped. The cause is in the preview seek path (out of scope), and is reported below with numbers and options.
- **C:** done (no measurable gain).
- **D:** not needed: the video keeps up.

| Part | Commit |
|---|---|
| A baseline (test-only frame hook + Aud1BenchTest / Aud1ScrubTest) | `d35185a` |
| B1–B6 streaming playback, PCM cache, bulk, priming trim, heard clock, audio thread | `1d94318` |
| C layer rows read the playhead in the draw phase | `2124220` |
| Records | this commit |

## Findings: is the lag audio, video or clock?
**The audio was late. It was the clock.**
- **The clock used the AudioTrack head.** That head runs **65 ms (p50; p95 84) ahead of what is heard**: the AudioTimestamp against the head; `getLatency` reported 213 ms with the 128 ms buffer.
- **Measured against the decoded audio, heard beats came +70 to +80 ms after their video frame** (median; p95 89–108 ms).
- **Priming on top:** the AAC priming was not trimmed either. 2048 samples is 46 ms at 44.1 kHz. Proved on fixtures: before the trim, playback and waveform sat +38–50 ms after the file's true times.
- **So against the file's own timing, audio was about 115–125 ms late.**
- **The video kept pace:** VP9 1440p rendered at p50 29 ms / p95 43 ms per frame against a 50 ms frame budget, with 0–2 skipped frames per 20 s once audio ran. The video is not the problem.

## Before / after (the benchmark file, debug build)

| Metric | Before (`d35185a`) | After (`1d94318` / `2124220`) | Target |
|---|---|---|---|
| Cold Play → first sound heard | 59.9 s ("Preparing audio" 59.7 s) | **1.18 s** (status not needed after the fix) | ~1 s: **missed by 0.18 s** |
| Repeat Play | 0.19 s | 0.24 s | ≤ 0.3 s: met |
| Play after a move edit | 28.4 s | **0.12 s** | ≤ 0.3 s: met |
| Play after a gain edit | 17.6 s | **0.09 s** | ≤ 0.3 s: met |
| Decode-only (test decoder) | 29.7 s | 24.4 s (now paid once per source, in the background) | – |
| Scratch / cache | 53.5–77.1 MB float scratch per mix | 23.6 MB 16-bit PCM cache per source | – |
| Output latency (getLatency / buffer) | 213 ms / 6144 frames (128 ms) | 341 ms / 12288 frames (256 ms), compensated by the clock | – |
| Beat offset, heard minus presented, from 0 | median +69.7, p95 89.1 ms | **median +2.5 / −7.1, p95 29.8 / 28.4 ms** | ±20 / ±35: met |
| Beat offset, from the middle | median +79.6, p95 107.9 ms | **median +15.6 / +15.0, p95 30.0 / 32.9 ms** | met |
| Beat offset, 3 twirl-downs expanded | median +71.2, p95 84.0 ms | **median +2.8 / −6.5, p95 28.4 / 18.1 ms** | met |
| Underruns in 20 s (incl. 3 expanded) | 0 | 0 | 0: met |
| Preview frame interval (from the middle) | p50 43.9 / p95 94.0 / p99 103 ms | p50 49.5–50.1 / p95 64.9–65.6 / p99 70–75 ms | steadier (20 fps = 50 ms) |
| VP9 render per frame (from the middle) | p50 32.3 / p95 43.8 ms | p50 35–36 / p95 44–45 ms | unchanged |
| UI frames during playback, collapsed, from the middle (FrameMetrics) | p50 45.7 / p95 61.5 ms | p50 39.0 / p95 52.7 (after B), 46.7 / 66.3 (after C) | no measurable C gain |
| Tap seek to the presented frame | 0.76–1.71 s | 0.80–1.85 s | out of scope (video seek path) |
| Waveform vs playback | within a bucket, both +38–50 ms late | within a bucket, both **within ±5 ms of the true times** | met |

- **Two runs per cell:** where a cell lists two values (for example 2.5 / −7.1), they are the B run and the C run.
- **The 341 ms latency** comes from the deliberately larger buffer (2x the platform minimum, B6). It never shows: the clock subtracts what is not yet heard.

## What changed per fix
- **B1 streaming:**
  - No whole-file mix before Play. A feeding thread mixes 20 ms blocks straight from the source caches and starts when 0.5 s is decoded at the start position.
  - The video waits on the gate as before.
  - A seek into an undecoded region restarts the decoder there.
  - "Preparing audio" only after 0.3 s, cleared the moment audio is ready.
  - **Cold first sound: 1.18 s.** The remainder is codec start-up, 0.5 s of decode from a cold extractor on the SD card, the first-frame gate and the track start.
- **B2 cache:**
  - One file per source: 48 kHz stereo 16-bit, under `cacheDir/aud1-pcm`. The key is the uri SHA-1, size, lastModified and version 1.
  - 1 s chunk coverage is persisted; the LRU cap is 256 MB; stale versions and the old mixer's leftover `preview_audio_*.pcm` temp files are deleted.
  - Edits (timing, gain, visibility, mute, duration) only re-mix, which is why they now take 0.09–0.12 s.
- **B3 bulk:**
  - Whole decoder buffers go into primitive arrays (`asShortBuffer` / `asFloatBuffer`), with an array-level stereo fold and reused buffers.
  - The stereo linear resampler runs only when the rate differs (this file: 44.1 to 48 kHz, once, at decode).
  - Export's `AudioMixer.mix` now uses the same cache and `BlockMixer`; fixture export mixes took 0.41–0.81 s against 0.51–0.92 s before.
  - **Deviation:** the fold, resample and mix still loop over samples on primitive arrays (that cannot be avoided in Kotlin without native code); what is gone is the per-sample `ByteBuffer` work and the per-frame allocation.
- **B4 priming:**
  - `AudioTiming.primingSamples`: the format's encoder-delay key; else nothing when the container already shifts the priming before 0; else 2048 for AAC.
  - The peaks apply the identical offset (peak format v3).
  - **Proof (TL3.1 fixtures):** waveform and playback are within ±5 ms of the true burst times (before: +38–50 ms), and match each other within one bucket.
  - **Not provable on the benchmark file:** it has no metadata, and an audio beat cannot say where the picture intended it; the AAC 2048 is the standard LC priming.
- **B5 clock:**
  - `AvClock`: the AudioTimestamp extrapolated to now (what is heard), else the head minus `getLatency`.
  - Never ahead of the head, never before the start.
  - The residual median is within ±16 ms. That is within half a video frame (25 ms at 20 fps): the remaining spread is frame quantisation, not a constant offset, so no fudge is added.
- **B6 thread:**
  - `THREAD_PRIORITY_URGENT_AUDIO` with blocking writes.
  - The buffer is 2x the platform minimum (256 ms here): 0 underruns with the UI busy (3 twirl-downs, playing).
  - **Trade-off:** a larger buffer means more latency to compensate (exact through the timestamp) and slower stop. A smaller one was not tried, since 0 underruns was already met.

## B7 / A6: scrub after pause or playback (STOPPED: preview seek path)
- **Data** (`Aud1ScrubTest`, 2 tries per case; first new frame after the first move; longest gap):

  | Case | Forward | Backward |
  |---|---|---|
  | Control: no playback, middle | 99–161 ms, gap ≤ 280 ms | **2586–3762 ms** |
  | After Pause at the start | 81–105 ms | **1939–2319 ms** |
  | After Pause at the middle | 75–106 ms | **85–2945 ms**, gap up to 2979 ms |
  | After Pause near the end | 81–101 ms | **1800–2086 ms** |
  | After playback ran to the end | 94–137 ms | **2003–2295 ms** |
  | After a tap seek | 93–116 ms | **1157–1428 ms**, caught up after 1.4–1.6 s |
  | 3 twirl-downs expanded | as above | as above |

- **Cause:**
  - `VideoDecoder.frameAt` (core-engine) flushes the codec and seeks to the previous sync frame on EVERY backward request (and on forward jumps > 0.5 s), then decodes forward to the target. That is 0.8–1.7 s per frame on this VP9 file.
  - Every scrub step supersedes the one in flight (the generation-cancel), so no backward frame finishes until the finger stops.
  - Playback and the audio path are not involved: the no-playback control is just as bad, and forward scrubs are fine everywhere.
- **Not changed (out of scope).** Options for a separate pass:
  1. While scrubbing, show the nearest SYNC frame (fast) and decode the exact frame on release.
  2. Keep a small cache of recently decoded frames / a GOP-reverse decode, so backward steps inside the same GOP are served from memory.
  3. Do not restart a backward decode that is still heading to a target inside the same GOP; retarget it (latest target wins).
  4. A low-resolution proxy for scrubbing.

## Part C
- The layer rows read the playhead where it is drawn (at-playhead key fill), as derived state (the text label) and at drag time (snapping). No visible change.
- **No measurable UI gain:** the editor screen itself still recomposes on every playhead update (it reads the playhead from editor state at its top), and that is the per-frame cost.
- **Option:** read the playback position through a separate state that only the timeline, ruler and readout observe, instead of the editor state the whole screen reads (an editor-state change, outside this pass).

## Part D
Not needed: the 1440p VP9 decode keeps pace (p95 43–45 ms per frame at 20 fps; 0–2 skipped frames per 20 s). Frame pacing already follows the audio clock and drops late frames.

## Tests
- **JVM: 683 tests, one full run, 48 s, 0 failures.**
  - **New:** `Aud1Test` 7 (priming maths, peak/mix offset equality, cache key / LRU, chunk coverage, the A/V clock with fallback, the streaming window, the bulk fold / resampler).
  - **Updated:** `Tl3AudioPeaksTest` (peak format v3).
  - `PlaybackGateTest` green; `InspectorIconFamilyTest` green.
- **Device (A16):**

  | Class | Result | Wall |
  |---|---|---|
  | `Aud1BenchTest`, before (both methods) | ran | 330 s |
  | `Aud1BenchTest`, after B1–B6 | ran | 199 s |
  | `Aud1BenchTest` playback method, after C | ran | 178 s |
  | `Aud1ScrubTest`, before | ran | 351 s |
  | `Tl31MeasureTest#c…` (waveform vs playback, priming) | pass | 33 s |
  | `Tl31UiTest` | 10 / 10 | 64 s |
  | `Tl3UiTest` | 7 / 7 | 43 s |
  | `TlpOneUiTest` | 4 / 4 | 37 s |
  | `TlpTwoUiTest` | 4 / 4 | 23 s |

  - The first two `Aud1BenchTest` invocations SKIPPED (the file lookup listed `/storage`, which scoped storage refuses); the lookup now asks `StorageManager`.
  - `E1aGoldenExportTest` did not run: it needs a manual `e1aLabel` argument (r26 / r28). Export is covered by the mixer fixture test.
  - `Aud1ScrubTest` was not re-run after the fixes: the cause is outside the changed code.
- **Not run:** a non-debuggable (benchmark variant) build. Possible without an uninstall (an `applicationIdSuffix` variant with the debug key), but not done in this pass.

## Device log
- **Keep-awake:** stay_on 0 → 15 → 0; screen_off_timeout 600000 → 600000 → 600000. Restored.
- **Charging:** the phone was NOT charging for these runs, so stay-on did not apply; no run hit the screen timeout.
- **Screen:** awake and unlocked before every class; none skipped.
- **Crash buffer:** cleared before the first run; empty at the end.
- **Interruptions:** none.

## Deviations
- B1–B6 share one commit and one after-measurement (they form one engine).
- **The cold-start target (~1 s) is missed: 1.18 s.**
- B7 stopped: out of scope.
- C: no measurable gain.
- Per-sample loops remain on primitive arrays (B3).
- No non-debuggable run.

## Not verified
- Real ears.
- The display's own latency (the frame hook is "handed to the swapchain", not photons; no software can measure the glass).
- TalkBack (unaffected).
- Other sources: HE-AAC, multi-channel, Opus, files whose container already trims priming.
- The decoded-PCM cache under the 256 MB LRU with many sources.
- Playback while a source is still decoding far ahead (seek inside an undecoded region during playback restarts there; only seen in tests).

## Phone test list (AUD1)
1. Open a project with the benchmark video and press Play from the start: sound within about a second, no long "Preparing audio".
2. Play from the middle: same.
3. Listen to the beats against the picture: they should land together (no audio lag).
4. Pause, then scrub forward: immediate. Scrub backward: still slow (the known video seek issue, reported, not fixed here).
5. Let it play to the end, then scrub: same as 4.
6. Edit while stopped (move the layer, change its volume), then Play: sound at once.
7. Play again after stopping: at once.
8. Expand the layer's twirl-down: the waveform lines up with what you hear.
