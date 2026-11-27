<a id="rec-aud3"></a>

# AUD3: choppy audio on HE-AAC clips (and the timeline merge)

Branch `timeline`. **AUD3 was merged into `master` at merge commit `5fbadd9`** (2026-11-27; `timeline` kept). Device: A16 (SM-A165F, Android 16), debug package `com.fluxx.android.debug`. Raw results are in `docs/checkpoints/aud3/`.

## Part A: the merge (git only)

| Step | Result |
|---|---|
| Before | `master` `2377a83`, `graph` `365937e`, `text` `861644a`, `tlp` `1768112`, `aud1` `bb8e112` (local and origin); working tree clean |
| Ancestry | `tlp` is an ancestor of `aud1`. `master` is an ancestor of `aud1`, so there were no conflicts. |
| Rename | `aud1` became `timeline`, was pushed (`origin/timeline` at `bb8e112`), and then `origin/aud1` was deleted |
| Merge | `git merge --no-ff timeline` on `master`: **merge commit `eec4878`** ("Merge timeline: TLP, TL3, AUD1, AUD2, MP1, MP1.1, MP1.2"), pushed |
| `tlp` | deleted with `git branch -d` (accepted: fully merged) and on origin |
| JVM on `master` | **721 tests, 0 failures, 0 errors** (39 s wall, no `clean`) |
| Records | "records: timeline merged" `f57a611` on `master` (TLP.md status); `timeline` fast-forwarded to `f57a611` |
| Branches after | `master` `f57a611`, `timeline` `f57a611` (then AUD3 commits), `graph` `365937e`, `text` `861644a`; `tlp` and `aud1` gone |

## Part B: AUD3

### What was known (MP1.2, `mp1_2/audio_probe.txt`, AUD1)

- **The six clips Snxw hears as choppy** (17, 20, 21, 22, 27, 28) are exactly the Instagram "… - Video by <name>.mkv" files with AAC declared at 24 kHz, which the decoder outputs at 48 kHz.
- **Already ruled out:**
  - decode speed: 13–25× real time;
  - format-change order: the change arrives before the first buffer;
  - gaps in the samples.
- **Audio seeks in these MKV files land at 0–0.13 s.**
- **The MP1.2 cache-vs-decode check was inconclusive** (its Opus control failed too).
- **The audio path (AUD1):**
  - the per-source cache is 48 kHz stereo 16-bit, with 1 s chunk coverage;
  - the stereo linear resampler runs only when the decoder's output rate is not 48 kHz (a pass-through otherwise);
  - AAC priming is 2048 samples at the output rate unless the container gives an encoder delay;
  - a 20 ms-block feeding thread mixes from the caches, and an undecoded block is mixed as silence, not an underrun.

### B1. Evidence (`Aud3Test`, one invocation, 987 s wall; Saver 0, AC, 37.3–39.3 °C, screen on and unlocked: comparable)

Files: the six choppy clips; controls Project 1 clips 1 and 5 (AAC-LC 44.1 kHz), 15 (Opus) and 23 / 26 (HE-AAC declared at 22.05 kHz); and from Download/Seal, two MP4 HE-AAC v2 files (22.05 kHz mono) and one more 24 kHz "Video by" MKV. Project 1 was read through `ProjectManager.loadProject` only: size 9256, mtime 1795802998935 before and after, unchanged. Its file had last changed at 19:09:58 because Snxw opened it in the app after MP1.2, not because of a test.

**A1: declared versus decoded** (all `c2.android.aac.decoder`, except Opus):

| File | Declared | AudioSpecificConfig | Output | Samples per buffer | First in / first out | App priming |
|---|---|---|---|---|---|---|
| clips 1, 5 (MKV) | 44100 Hz, 2 ch, profile 2 | `1210`: AAC-LC | 44100 / 2 | 1024 | 0 / 0 | 2048 = 46.4 ms |
| clip 15 (MKV, Opus) | 48000 / 2 | OpusHead | 48000 / 2 | 960 | 0.007 / 0.007 | 0 |
| clips 23, 26 (MKV) | 22050 / 2, profile 5 | `2b920800`: SBR explicit | **44100** / 2 | 2048 | 0 / 0 | 2048 = 46.4 ms |
| **clips 17, 20, 21, 22, 27, 28 (MKV)**, Seal "3 - Video by xtosy" | 24000 / 2, profile 5 | `2b118800`: SBR explicit | **48000** / 2 | 2048 | 0 / 0 | 2048 = 42.7 ms |
| two Seal MP4s (HE-AAC v2) | 22050 / 1, profile 29 | `eb8a0800`: PS explicit | 44100 / 2 | 2048 | −0.161 / 0.025 | encoder-delay 3553 = 80.6 ms |

The decoder's output rate is the one the app uses (priming at 42.7 ms, not 85.3).

**A2: the app's PCM against a straight-through reference decode.**
- **The method:** a MediaCodec decode with no cache or seek, through the app's own fold and resampler to 48 kHz. It is aligned by normalised cross-correlation (global, then a local lag every second), level-normalised by least squares, and compared as error energy per 20 ms block.
- **The caches measured:** a fresh test cache filled from 0, a fresh cache filled reader-first at 3 s then 0, and the app's existing cache.

| File | Gain | Per-second RMS error (median / max) | Bad 20 ms blocks | At a 1 s chunk join | Local lag per second |
|---|---|---|---|---|---|
| clip 1 (control) | 1.000 | **0.000 / 0.000** | 0 / 1359 | – | 0 throughout |
| clip 5 (control) | 1.000 | 0.000 / 0.000 | 0 / 1042 | – | 0 |
| clip 15 Opus (control) | 1.000 | 0.000 / 0.000 | 0 / 1443 | – | 0 |
| clip 23 (22.05 kHz HE-AAC) | 1.000 | 0.000 / 0.000 | 0 / 642 | – | 0 |
| clip 26 (22.05 kHz HE-AAC) | 1.000 | 0.000 / 0.000 | 0 / 1403 | – | 0 |
| **clip 17** | 0.723 | **0.965 / 1.165** | **397 / 397** | 15 | 0 |
| **clip 20** | 0.827 | 0.690 / 1.099 | 1137 / 1443 | 45 | jumps of ±16 (7 changes) |
| **clip 21** | 0.912 | 0.503 / 0.702 | 773 / 1164 | 31 | −16 / −32 / −17 |
| **clip 22** | 0.772 | 0.832 / 1.035 | 1175 / 1448 | 46 | ±15–16 (12 changes) |
| **clip 27** | 0.808 | 0.697 / 0.925 | 959 / 1198 | 38 | 16, 1, 2 |
| **clip 28** | 0.788 | 0.739 / 1.116 | 927 / 1142 | 36 | 16, 1 |
| Seal 24 kHz MKV | 0.953 | 0.327 / 0.364 | 337 / 795 | 12 | −16 |
| Seal MP4 HE-AAC v2 (×2) | 0.995 | 0.083–0.100 / 0.445 | 5–31 | 1–4 | 0; 4 silent blocks at 0.00 s |

- **The method is proven:** every clean control, including the two 22.05 kHz HE-AAC MKVs, compares exactly (0.000).
- **The 24 kHz HE-AAC files are wrong throughout,** not at chunk joins. Join blocks are 2/50 of all blocks, and that is their share of the bad ones. The error is the same in all three caches (fresh, reader-first, the app's own), so it is not the run or restart pattern.
- **The local alignment moves in steps of 16 frames,** which is a third of a millisecond at 48 kHz.

**A3: playback from a cold cache** (the same files under a distinct `file://` spelling, so the app built new cache entries; 10 s from 0, and 5 s after a seek to 5 s):
- the mixer played **0** blocks as silence for every file, choppy and control alike;
- track underruns were 0–1 for both, with first sound 264–1269 ms after Play;
- so the choppiness does not line up with feeding or underruns.

**Why:** the timestamps of these MKV files are in whole milliseconds. The probe in `mp1_2/audio_probe.txt` gives the output pts as 0.000, 0.042, 0.085, 0.128, 0.170, 0.213 s, steps of 42 and 43 ms for buffers of 2048 frames (42.667 ms).
- **On the resampler path** (any output rate other than 48 kHz) the samples are written contiguously: the resampler follows on from its last frame unless the timestamp jumps by more than 100 ms.
- **On the pass-through path** (output already 48 kHz) each buffer was written at its own timestamp. A 2048-frame buffer then lands 2016 or 2064 frames after the previous one, a 32-frame overlap or a 16-frame gap of stale data every 43 ms, throughout the file.
- **Why only these files:** Opus (960-sample frames, exact 20 ms timestamps) and MP4 (exact timestamps) are not affected. Only a 48 kHz output with millisecond-rounded timestamps is: here, HE-AAC declared at 24 kHz in MKV. AAC at 48 kHz in MKV would be affected too.

### Suspects (written before the fix)

| # | Suspect | Verdict | Evidence |
|---|---|---|---|
| S1 | The declared 24 kHz rate is used where the 48 kHz output rate should be (resampler, cache, priming, peaks, mixer) | **Refuted** | SourcePcm takes the rate from the output format (the change comes before the first buffer). The priming is 42.7 ms (at 48 kHz), matching the measured offset of 43.0 ms. The cache and mixer are 48 kHz by design. |
| S2 | SBR frame size (2048 against 1024) or priming off by 2×, an error at every chunk or block join | **Refuted as stated** | The 2048-sample buffers are handled, and the priming is right. Errors are not concentrated at joins. But the per-buffer placement (S5) is where the frame size matters. |
| S3 | Chunk or coverage bookkeeping mixes source-rate and output-rate samples | **Refuted** | There are no holes, coverage is complete, and fresh, reader-first and app caches are identical. |
| S4 | The feeding thread underruns on these files (cold re-decode from 0 s) | **Refuted** | No block was mixed as silence, and underruns were 0–1, the same as the controls. |
| S5 | Other | **Confirmed: 48 kHz pass-through placed each decoded buffer at its millisecond-rounded container timestamp**, so buffers overlap or leave gaps every 43 ms | The local lag jumps by 16 frames, error is present throughout, and the same file family resampled (22.05 kHz) is exact. |
| (open) | MP4 HE-AAC v2 with an edit list (first pts −0.161 s, encoder-delay 3553) | Not by ear; a separate small offset | The measured offset is 102.4 ms against the app's 80.6 ms priming, giving 4 silent blocks at the very start and 8–10 % residual error. Not reported by Snxw; left for its own pass. |

### B2. The fix (`2ba9b52`)

- **48 kHz decoder output is written contiguously,** like the resampling path, in `Resampler.run`'s pass-through (`AudioEngine.kt`). A buffer that follows the previous one within 100 ms is written straight after it; a larger jump (a seek inside a run, an edit list) is honoured.
  - Sources with exact timestamps (Opus, MP4, any 44.1 kHz source) are placed exactly as before.
  - The run start is still placed by its timestamp and priming (unchanged).
- **The PCM cache version is now 2** (`PcmCachePolicy.VERSION`). The app's existing rule deletes the stale v1 files, so every source's cache rebuilds on its next play. The `APP-CACHE` rows after the fix show "not fully covered" for that reason.
- **Peaks are unchanged** (`AudioPeakProvider` still places by timestamp). Its per-buffer error is at most 1 ms, it does not accumulate, and a waveform bucket is far coarser, so the waveform and playback still agree to within about 1 ms.
- **JVM** (`Aud3BookkeepingTest`, pure, 5 tests):
  - millisecond-stamped 2048-frame HE-AAC buffers at 48 kHz are written back to back, with no gap, overlap or drift, from the primed first frame;
  - the old per-stamp rule really does step by values other than 2048, so the test fails on the old code;
  - a jump over 100 ms still resets;
  - exact 20 ms Opus stamps are placed exactly as before;
  - v1 cache names are stale.

**Before / after** (`Aud3Test`, the same files, the same method; the after-run took 770 s wall, Saver 0, AC, 37.4–38.3 °C, comparable):

| File | Before: error median / max, bad blocks, lag | After: error median / max, bad blocks, lag |
|---|---|---|
| clip 17 | 0.965 / 1.165, 397 of 397, gain 0.72 | **0.000 / 0.000, 0**, lag 2048 frames (= the 42.7 ms priming), steady |
| clip 20 | 0.690 / 1.099, 1137 of 1443, ±16 jumps | **0.000 / 0.000, 0**, steady |
| clip 21 | 0.503 / 0.702, 773 of 1164 | **0.000 / 0.005, 0**, steady |
| clip 22 | 0.832 / 1.035, 1175 of 1448, 12 jumps | **0.000 / 0.000, 0**, steady |
| clip 27 | 0.697 / 0.925, 959 of 1198 | **0.000 / 0.000, 0** |
| clip 28 | 0.739 / 1.116, 927 of 1142 | **0.000 / 0.000, 0** |
| Seal 24 kHz MKV | 0.327 / 0.364, 337 of 795 | **0.000 / 0.000, 0** |
| controls 1, 5, 15, 23, 26 | 0.000 | 0.000 (unchanged) |
| Seal MP4 HE-AAC v2 (×2) | 0.083–0.100, 4 silent blocks at 0 s | unchanged (the open offset below) |

- **Playback after:** every file had 0 blocks mixed as silence, underruns 0–1 (the same as before and as the controls), and first sound 292–892 ms after Play.
- **What Snxw should hear:** clips 17, 20, 21, 22, 27 and 28 play smoothly, like the other clips, from the first play. The first play of every source rebuilds its cache once, the same cost as a new import.

### Still open

- **MP4 HE-AAC v2 with an edit list** (first pts −0.161 s, encoder-delay 3553): the cache sits about 22 ms off the reference (102.4 ms measured against 80.6 ms of priming), with 4 silent 20 ms blocks at the very start and 8–10 % residual error, which is likely resampling phase. It was not reported by ear and was not fixed.
- **AAC at 48 kHz in MKV** (AAC-LC with 1024-frame buffers of 21.333 ms) would have had the same fault. None was in the test set; the fix covers it by the same rule.

## Records

- **Power state per class:** both `Aud3Test` runs had Saver 0, AC, 37.3–39.3 °C, screen on and unlocked. None is NOT COMPARABLE. The JVM suites ran on the PC.
- **Wall-clock times:**
  - JVM on `master` after the merge: 39 s (721, 0 failures);
  - `Aud3Test` before: 987 s; after: 770 s;
  - JVM full suite at the end: 45 s (726, 0 failures).
  - No infrastructure failures, and no re-runs.
- **Project 1:** size 9256, mtime 2026-11-27 19:09:58.935 before and after (adb stat, `aud3/project1_stat_*.txt`; in-process checks in every method). The file had changed at 19:09:58, before this pass, because Snxw opened it in the app; nothing in this pass wrote it.
- **Test files:**
  - the fresh test caches were written to `cacheDir/aud3-test/` and deleted by the test;
  - the cold-start playback (A3) used distinct `file://` spellings of the same files, so the app built its own new cache entries for them (its normal LRU-capped cache);
  - with the version bump, the app deleted its own v1 caches on the next start.
- **Phone state:**
  - found: Saver 1, stay-on 0, timeout 600000;
  - for the session: Saver 0, stay-on 15, timeout 600000;
  - **restored:** Saver 1, stay-on 0, timeout 600000.
  - The crash buffer was empty and was not cleared (nothing to save).
  - Installed with `install -r` only. No uninstall, `pm clear`, compile, data deletion or scripted taps.
- **Not verified:**
  - **by ear on Snxw's phone**: he is the final check for clips 17, 20, 21, 22, 27 and 28, and for 23 and 26 (measured exact before and after, never confirmed by ear);
  - other devices (other AAC decoders);
  - other audio codecs (AC-3, Vorbis, FLAC, PCM in MKV: none in the test set);
  - AAC at 48 kHz in MKV;
  - the MP4 HE-AAC v2 offset above.

## B3. The MP4 HE-AAC files (Snxw: "check those 2 mp4 files")

**The files:**
- Download/Seal: '#liloandstitch #livingroom #girlfriend …' and '(Scap edit) ib@melis #aura …'. Both are MP4 with HE-AAC v2 (object type 29, PS) declared at 22.05 kHz mono, decoded to 44.1 kHz stereo.
- Project 1 clips 14, 19 and 24 turned out to be MP4 AAC files built the same way, with an edit list.

**The evidence** (`Aud3Test#a4Mp4EditList`, 92 s wall; Saver 0, AC, 37.5 °C; comparable):
- **Method:** a "true timeline" decode puts every decoder output buffer at its own pts, contiguously, at 48 kHz, with no priming trim. It is cross-correlated against a fresh app cache and compared after a 9-tap low-pass (insensitive to sub-sample resampling phase).
- **The MKV control** (clip 1, AAC-LC, pts from 0, no encoder delay) shows the intended 46.4 ms AAC-LC priming trim (AUD1's proven rule), with error 0.000 at that offset.

| File | First input pts | encoder-delay | App priming (before) | App vs true timeline (before) |
|---|---|---|---|---|
| Seal MP4 ×2 | −0.1611 s (= 3553 samples at 22.05 kHz) | 3553 | 80.6 ms | sound **80.6 ms early**; error at that offset 0.037–0.065 |
| clip 19 | −0.1611 s (3553) | 3553 | 80.6 ms | **80.6 ms early** (0.029) |
| clips 14, 24 | −0.1147 s (= 2529) | 2529 | 57.3 ms | **57.3 ms early** (0.012–0.013) |

- **The cause:** the MP4 edit list both stamps the priming samples with negative times and reports the same count as "encoder-delay". `AudioTiming.primingSamples` checked the encoder delay before the "container already shifted it" rule, so these files were trimmed twice.
- **What the earlier symptoms really were:**
  - the 8–10 % "residual error" in B1 was sub-sample resampling phase in that comparison (about 0.01–0.07 after the low-pass), not damage;
  - the "4 silent blocks at 0 s" were the doubled offset.
- **Nothing is lost at the start:** the decoder does not drop the negative-stamped output (first output at −0.022 s).
- **What it sounded like:** these clips' audio (and their waveform, which uses the same rule) ran 57–81 ms ahead of the picture. That is audible on lip sync or a beat cut, but not as choppiness.

**The fix** (`AUD3 B2`):
- `primingSamples` now returns 0 first when the first pts is negative, then the encoder delay, then the AAC default.
- The PCM cache version is now 3, so those caches rebuild.
- JVM: `anMp4EditListIsNotTrimmedTwice`. The AUD1 rule tests still pass: an encoder delay without a shift is still trimmed, and AAC with nothing said keeps 2048.

**After** (`a4-after`, 108 s wall): the two Seal MP4s and clips 14, 19 and 24 have app priming 0 and **lag 0 against the true timeline, error 0.000**. The MKV control is unchanged (46.4 ms trim, 0.000).

**JVM full suite after B3:** 727 tests, 0 failures (44 s). This is a second full run, because B3 was a new fix after the end-of-part run.

**Phone, after B3:** Saver 1, stay-on 0, timeout 600000 restored. Project 1 is unchanged (size 9256, mtime 19:09:58.935).

**Not verified:** by ear (clips 14, 19 and 24 and the two Seal MP4s should now sit in sync with their picture); other MP4 edit-list variants (a positive media-time offset, partial shifts).
