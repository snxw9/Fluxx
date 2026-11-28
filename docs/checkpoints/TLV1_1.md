<a id="rec-tlv1-1"></a>

# TLV1.1: key-drag seek, thumbnail end, waveform speed and timing

Branch `tlv` (from tip `4b36a8b`). **Not merged into `master`.** Raw device output is in `docs/checkpoints/tlv1_1/`.

## A1. Key drag past the edge

**Regression or design?** The original TL3.1 design, not a TLV1 regression. `keyFinish` on `master` (`d3eba92`) and on `timeline` (`ba8bab7`) commits or cancels and then eases back to the unchanged playhead with `animateScrollTo`, with no seek (R23: "eases back, no seek"). TLV1 kept that behaviour, with `animateTo` on the time-based scroll state.

**Built (Snxw's decision):**
- `KeyDragLanding` (pure, `editor`, JVM tested):
  - `Tracker` records whether the edge scroll actually MOVED the view during the drag: an edge step that consumed 0 px (the scroll at 0 or at the end) does not count. It is reset when a drag begins (`beginEdit`).
  - `seekTo(committed, autoScrolled, keyTime)` returns the key's time only for a committed drag that auto-scrolled.
  - `movedTime` finds the dragged key's new time in a group move (the moved selection keeps each track's order).
- `TimelineView.keyFinish(commit, keyCompositionUs)`:
  - commits (or cancels) exactly as before: one gesture, one undo;
  - if `seekTo` gives a time, it calls the normal `onSeek(time, snap = true)` (the reducer frame-snaps it) after the commit, then eases the view to that time rather than back to the old playhead;
  - otherwise it eases back to the playhead, with no seek, as before.
- **Twirl rows** (`TwirlKeys`): `TwirlGestures.commitKeyDrag(time)`, where time = layer key anchor + the dragged key's new local time (`MultiKeyEdits.moveTogether`'s moved selection).
- **Layer bar** (`TimelineClipRow`): `onKeyCommit(time)`, where time = clip start + the dragged key's new bar time (`groupMap[dragged]` or `movingTo`).
- **Multi-key drags** seek to the key under the finger, not to the earliest key. **Cancelled drags** never seek.
- No new action. The seek is the existing `EditorAction.Seek`. Selection and scroll state stay out of the undo history.

**Tests:**
- `KeyDragLandingTest` (JVM, 4 tests): auto-scrolled versus not; cancel; a step at the scroll limit does not count; a lone key's composition time; a group drag lands on the dragged key.
- `Tlv11UiTest` (device, 4 tests: twirl row and layer bar):
  - a drag held in the right edge zone lands the key past the finger-only distance; the playhead equals the key's new time; the view's centre is that time within half a px; one undo restores the keys; the seek is not in the undo history;
  - a 30–40 dp drag that never reaches the edge moves the key, leaves the playhead unchanged and eases back.
- `Tl31UiTest.b1DraggingAKeyToTheEdgeScrollsTheTimeline` asserted "no seek" (the old design). It now asserts the seek to the moved key.

## A2. Thumbnail at the clip end

**Cause (two):**
1. **The tile count was floored** (`tiles = (fullWidth / 48 dp).toInt()`). The partial last tile was never drawn, so every clip whose width is not a whole number of tiles ended with up to 47 dp of bare clip background at its real end. This is what Snxw saw: the 2.3 s test clip at 120 dp/s is 776.25 px, so 5 tiles were drawn and 101 px (36 dp) was left bare.
2. **The tile grid used `hiddenLeadPx`, which TLV1 caps at 4 window widths** (it is also the label's offset). For a clip whose real start lies more than about 8640 px before the window, the grid was shifted by the capped amount, so the tile at the real end was cut at an arbitrary x and the grid jumped at each origin step.
   - For a clip starting at 0 on the A16 the shift is a whole number of tiles (the 540 px origin steps and the 135 px pad are multiples of 135 px), so it was invisible there. Other start times showed it.
- Not the cause: the window clip (the box is the clip's part in the window plus 48 dp; window cuts stay square) and the corner clip (rounded only at real ends, as designed).

**Fix:** `ThumbnailTiles.layout` (pure) lists the tiles that meet the box, from the clip's real start (uncapped lead, in Double) to its real end. The last tile is partial and centre-cropped to its width, and nothing is drawn past the real end. The first tile starts at or before the box, on the grid of the real start. The canvas draws that list. Real ends keep their rounded corners; window cuts stay square.

**Tests:**
- `ThumbnailTilesTest` (JVM, 7): a partial last tile at the real end; a whole number of tiles adds no sliver; a clip shorter than a tile; the start before the window keeps the real start's grid; a window cut stops at the box; 3 h before the window the grid stays exact; empty inputs.
- `Tlv11TilesTest` (device, the typical H.264 clip, tiles as DRAWN, reported from the draw phase through `TimelineProbe.tiles`):

| Case | Box | Tiles drawn | First tile | Last tile |
|---|---|---|---|---|
| 2.3 s clip, end inside the window | 776 px | **6** (TLV1: 5) | index 0 at x 0 | index 5, **101.25 px** wide, ends at 776.25 |
| 60 s clip at its start (end cut by the window) | 1755 px | 13 | index 0 at x 0 | full, reaches the window cut |
| 60 s clip at its end (start 18,765 px before the window) | 1509 px | 12 | index 139 at x 0 | index 150, **23.96 px**, ends at the box end |
| same at 77 dp/s | 1264 px | 10 | index 87 | index 96, **49.13 px**, ends at the box end |

## C1. Waveform speed, measured first (`Tlv1WaveformTest`, "before" build `04760a9` + probes only)

Method: the row is expanded at the clip's middle (default zoom 120 dp/s). Times run from the expand to the first draw with any waveform column, and to the draw where every clip column of the laid-out window (2160 px) has data. The Compose test clock is pumped at 16 ms.
- **COLD:** this source's peak files and PCM cache files deleted through the caches' own file names (never app data).
- **WARM peaks:** stored peaks only.
- **WARM PCM:** the used range decoded into the PCM cache first, as playback's background fill does.
- **Both:** both of the above.
- Files are read in place through MediaStore content URIs and the real add path. Project 1 clips are copied into an in-memory document.

| Clip | Used | Cold | Warm peaks | Warm PCM | Both |
|---|---|---|---|---|---|
| typical H.264 | 60 s | 12,196 | 134 | 12,899 | 110 |
| VP9 | 122.8 s | 27,220 | 112 | 22,042 | 81 |
| HEVC, 10 min used (20:00–30:00) | 600 s | 110,659 | 79 | 118,900 | 100 |
| HEVC, whole | 3018 s | **541,321** | 102 | skipped (see below) | – |
| P1 clip 17 HE-AAC 24k MKV | 8.7 s | 1,255 | 69 | 1,093 | 95 |
| P1 clip 14 MP4 edit list | 22.4 s | 3,526 | 79 | 3,054 | 91 |

All times are ms; in every case "first pixels" = "complete window". The 50-minute range does not fit the PCM cache's cap (256 MB = 1398 s), so its PCM modes were skipped.

**Where the time went (probe lines in `tlv1_1/c-before-Tlv1WaveformTest.txt`):**
- **Scheduling** was 15–30 ms (request to start). **Extractor open** was 13–127 ms.
- **Decode of the whole used range from the source** took the rest. Of that, the per-sample peak loop was about 30 % (computeMs 3,377 of 11,953 on the 60 s clip; 164 s of 541 s on the 50-minute file).
- **All or nothing:** the state stayed `Loading` until the whole range was done, so the first pixels waited for it. The time was proportional to length (~5× real time at background priority).
- **The PCM cache was never read:** "warm PCM" was as slow as cold.
- **Priority:** the work was BACKGROUND (`MediaWork.background`) even for a visible row. Nothing was playing during the test, so it was never paused here.

## C2. Fix and after

Built (`AudioPeakProvider`, `PeakChunks`, `TwirlAudioRow`):
- **Chunks:** the used range plus its margin, in 2 s source chunks.
  - Shown chunks first: from the one under the playhead, rightward (one decode, no seek), then the nearest left. Then outward from the focus.
  - The row reports its shown source range (`focus`) as the window moves.
- **Progressive:** partial peaks are published as chunks land. Shown chunks publish at once; the rest at most every 1.5 s. `Peaks.Ready.complete` stays false until the range is done.
  - The row draws only built columns; not-yet-built runs show the placeholder band.
- **Source:** a chunk the PCM cache already holds is read from it (with the same asset length playback opens it with). Otherwise it is decoded: one decoder, continuing while chunks are adjacent, otherwise a seek with 100 ms of pre-roll.
- **Priority:** shown chunks are foreground (default thread priority, never paused). The rest is background as before: background priority, `MediaWork.checkpoint`, paused while playing or scrubbing.
- **Cancel:** a collapse, or the row leaving, cancels the job. What was built is kept in memory and stored (partial coverage), so a re-expand shows it at once and builds only the rest.
- **Scope:** nothing outside in-point to out-point plus 10 s is read or decoded (input stops 200 ms past the wanted range, as before). The PCM cache is only read: no `setScope`, `fill` or `ensure` from the peaks.
- **Format:** peaks version 5, on the 48 kHz PCM timeline. Older `.peaks` files are deleted on first use.
- **Resolution:** level 0 is 256 frames (5.33 ms) as before, and coarser levels are rebuilt per publish. No frame-level work is done up front: only the shown chunks run first, whatever the zoom.

**After (final build `7a72ec5`), ms:**

| Clip | Used | Cold first / complete window | Warm peaks | Warm PCM first / complete | Both |
|---|---|---|---|---|---|
| typical H.264 | 60 s | **635** / 1,601 | 102 | 110 / 110 | 107 |
| VP9 | 122.8 s | **949** / 2,658 | 85 | 153 / 280 | 85 |
| HEVC, 10 min used | 600 s | **705** / 2,311 | 66 | 200 / 351 | 77 |
| HEVC, whole | 3018 s | **756** / 2,330 | 80 | – | – |
| P1 clip 17 | 8.7 s | **624** / 1,231 | 79 | 141 / 201 | 75 |
| P1 clip 14 | 22.4 s | **423** / 1,017 | 81 | 85 / 85 | 67 |

- **Targets met:**
  - first pixels warm under 300 ms (66–200 ms);
  - cold under 1.5 s (423–949 ms), with no growth with length: the 50-minute file starts in 0.76 s, against 541 s before.
- **Time to complete the visible window** (2.7 s at most) scales with the window, not with the clip. The rest of the used range fills in afterwards at background priority.
- **What the cold time is:** almost all of it is the first 2 s chunk's decode. This phone's AAC decode runs at about 5× real time (5 s chunks took 0.9–1.4 s). Extractor open is 13–90 ms.
- **Cancel** (`b6`, 50-minute file, cold): collapsing after the first pixels stopped the work **182 ms** later. 12 s were kept (stored: an 18 KB file), and expanding again drew them in **81 ms**.
- **First "after" run** (`c-after-Tlv1WaveformTest.txt`, 5 s chunks starting at the window's left edge): cold 0.6–1.6 s. The typical clip missed at 1,618 ms, so the chunks were cut to 2 s and start at the playhead, and the class was run once more (`c-after2-…`, the table above).

## C3. Waveform timing

Method:
- Onsets (a 5 ms rise to at least 3× the loudest of the 40 ms before it) in the PCM cache's 48 kHz samples, i.e. what plays. Each onset's true time is the first sample at half the local peak.
- In the peaks: the nearest bucket within 150 ms that reaches the same level after 20 ms below it (its centre).
- "Drawn": the first pixel column that shows the onset (a column reads buckets from its left time to one px later), its centre against the true time.
- Up to 120 s per file. Offsets are signed: + = waveform later than the sound.
- "Before" = the v4 peaks the before build stored, read in the after run with the same method.

| File | Onsets | Before: source median / worst | Before: drawn at 120 dp/s | After (decoded = from PCM): source median / worst | After: drawn at 120 dp/s / at 2400 dp/s |
|---|---|---|---|---|---|
| P1 clip 1 AAC-LC MKV | 2 | +2.6 / +2.6 ms | −1.8 ms | −0.06 / −0.75 ms | −4.2 ms (−1.4 px, −0.08 frame) / −2.8 ms |
| P1 clip 15 Opus | 1 | +0.06 | −4.1 | +0.06 | −4.1 / −2.7 |
| P1 clip 17 HE-AAC 24k MKV | 1 | −2.7 | −6.8 | +2.7 | −1.5 / −0.07 |
| P1 clip 14 MP4 edit list | 11 | **+21.0 / +23.3** | **+16.7 (+5.6 px, 0.33 frame at 20 fps)** | −0.65 / −2.5 | −4.8 (−1.6 px, −0.10 frame) / −3.4 |
| Seal MP4 HE-AAC v2, edit list | 5 | **+22.3 / +24.7** | **+17.9 (+6.0 px, 0.54 frame at 30 fps)** | −0.15 / −2.6 | −4.3 (−1.5 px, −0.13 frame) / −2.9 |
| typical H.264 | 0 | – | – | – | – |

**Verdict:**
- **By the rule, the waveform was accurate:** the worst median was +22.3 ms, under 1 frame (0.54 frame at 30 fps). So no C3-only fix was required.
- **It was a real, systematic offset on HE-AAC v2 MP4s with an edit list.** The peaks placed each decoded buffer at its own timestamp, while the PCM cache (playback and export) writes a buffer that follows within 100 ms straight after the previous one (AUD3). On these files the two placements differ by about 21 ms.
  - This matches AUD3's open note: playback of these files is ~22 ms off a reference decode.
  - The waveform now follows what Fluxx plays. Whether playback itself is right for those files is still AUD3's open item, not changed here (the PCM write side is out of scope).
- **C2 needed the same placement anyway** (otherwise chunks read from the PCM cache and decoded chunks would disagree by 21 ms). So decoding now runs the cache's own `AudioFold` and `Resampler`. After: decoded and PCM-read peaks are identical and within ±2.7 ms of the sound in source time on every file.
- **The rest is the draw rule:** a column shows an onset up to one bucket (5.33 ms) plus one px before it, so the waveform reads about 3–5 ms early on screen.
- `PeakPlacementTest` (JVM, 3 tests) covers the bookkeeping:
  - the ~21 ms off-stamp buffer is placed contiguously;
  - millisecond stamps at 48 kHz are written back to back;
  - a jump over 100 ms is honoured.
- **Limit:** the onset detector found few clean onsets on music (0 on the typical clip, 1–2 on three files; 20 in total across the files). The edit-list result rests on 16 onsets, all within 3 ms of each other.

**What Snxw can expect by eye:** the waveform is within about 0.15 frame of the sound at 30 fps on every file measured. It reads 1–2 px early at 120 dp/s and about 20 px early at frame zoom (2400 dp/s), which is 3–5 ms. Before, the edit-list MP4s read 6 px (half a frame) late.

## Records

- **Commits:** `04760a9` TLV1.1 A; `7a72ec5` TLV1.1 C; then this records commit. Part B: none.
- **Contracts unchanged:**
  - the gesture protocol, `EditorAction` (the seek is the existing `Seek`), the batch/draw contract, the evaluator, `SetKeyEasing`, preparation, and schema v13;
  - no renderer, decoder, mixer playback path, export, model or `AvClock` change.
  - The PCM cache is only read: `SourcePcmCache.get` (as playback opens it), `covers` and `read`. `Resampler` and `AudioFold` are reused, unchanged.
- **Device runs** (`tlv1_1/device_runs.txt`; one class per invocation; all on AC, Saver 0, screen on and unlocked, 36.0–37.2 °C; all comparable):

| Class | Build | Wall | Result |
|---|---|---|---|
| `Tlv11UiTest` | A | 39 s | OK 4 |
| `Tl31UiTest` | A | 71 s | OK 10 |
| `Tlv11TilesTest` | A | 16 s | OK 1 |
| `Tlv1WaveformTest` before | A + probes | 1157 s | OK 7 |
| `Tlv1WaveformTest` after v1 | C, 5 s chunks | 358 s | OK 8 |
| `Tlv1WaveformTest` after (final) | C | 361 s | OK 8 |
| `Tl3UiTest` | C | 46 s | OK 7 |
| `Tl31UiTest` | C | 65 s | OK 10 (re-run because C changed the waveform row it covers) |

- **No failures, no flaky tests, no infrastructure failures.**
- **JVM full suite once at the end:** 760 tests, 0 failures, 62 s.
- **Project 1 (read-only):**
  - read through `ProjectManager.loadProject` only, never saved;
  - `stat` 9256 bytes, mtime 2026-11-28 00:53:37.987 before and after (adb), and unchanged in-process in both waveform phases.
- **Phone state:**
  - found: Saver 1, stay-on 0, timeout 600000;
  - for the session: Saver 0, stay-on true, timeout 600000;
  - **restored:** Saver 1, stay-on 0, timeout 600000.
  - The crash buffer at the start and end held only the same 7 vendor info lines, with no Fluxx line.
- **`tlv` is NOT merged into `master`.**
- **Not verified:**
  - a real finger on a key drag past the edge (the UI tests use Compose injected input);
  - thumbnails by eye;
  - the waveform's look and feel by eye, including the placeholder band while it fills in;
  - waveform work while playing (the background chunks pause; the shown ones do not);
  - other devices;
  - onset timing on music-heavy files (the detector found too few onsets there).

## Phone checklist for Snxw (`tlv` build)

1. Hold-drag a key (layer bar and twirl row) into an edge until the timeline scrolls, then release: the playhead jumps to the key. One undo moves the key back.
2. A short key drag that never reaches an edge: the playhead stays put.
3. Clip ends: the last thumbnail tile fills to the rounded end, with no bare strip. Check a clip that starts far off screen too.
4. Expand a long clip's audio row (try the 50-minute file): the waveform appears under the playhead in about a second and fills outward.
5. Collapse it while it is filling, then expand again: what was there comes back at once.
6. An MP4 with an edit list (Project 1 clip 14): the waveform's hits line up with what you hear.
