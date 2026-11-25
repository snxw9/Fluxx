<a id="rec-tl1"></a>

# TL1: frame-snapped scrubbing, tiered ruler, live Time tile

> **GRAPH G3.1 replaced the tier densities.** The minimums are now major 12dp, mid 5dp and minor 3dp. Minors fall back to every Nth frame, so all three tiers show at the default zoom for every rate, and mids stay visible. See [GRAPH G3.1 §2](GRAPH_G3_1.md#2-ruler-tiers-always-three-tiers-mids-stay-visible).

Branch `graph-g3`, created from `graph-g2-1` at `c5f143d` (`graph-g2-1` is not merged into `master`). Part 1 of the TL1 + G3 run, committed separately. Not merged.

**Status:** implemented in source.
- **JVM:** 571 tests, 0 failures, in one full run.
- **A16 (SM-A165F, 450 dpi):** `TimeAuditTest` passes (7 tests: five rates at three zooms, the Graph Time tile, playback/pause). The sweep of `GraphPlayheadTest` and `GraphValueTest` passed 16/16.
- **Crash buffer:** empty.
- **Phone acceptance:** pending (§7).

**Measured offset (playhead line − its frame's tick):**
- **Before:** the G2.1 audit measured −0.25 to −1.0 px with the playhead on a frame. A scrub could also stop anywhere, up to half a frame (5.6 px at 120dp/s) from the tick its timecode named.
- **After:** **0.00 px** in all 48 checks (5 rates × 3 zooms × 3 scrubs, plus 3 with the Graph open). Every scrub now ends on a frame.

## Hard rules kept

- **Frozen contracts:** no change to `PreviewController` (read only, to confirm the pause path is echo-safe), the gesture protocol, the `EditorAction` contract (existing `Seek` only), the batch/draw contract, the schema (v13) or preparation, or the evaluator.
- **Sheet heights** are untouched.
- **Graph:** never moves the playhead (`GraphPlayheadTest` passes).
- **Frame times:** always from the rational rate. `FrameRate.frameTimeUs(n)` = `n·10⁶·den / num` (exact integer arithmetic, rounded down). Nothing accumulates. At 29.97, 10 h is frame 1,078,921, starting at 35,999,997,366 µs (JVM checked against the exact fraction). "Nominal fps" = `round(num/den)`.

## TL1a. Scrubbing snaps to whole frames

Call sites changed:
1. **The scrub → `Seek` path.** In `TimelineView.kt`, the `LaunchedEffect(horizontalScrollState.value, isScrollInProgress)` that turned the scroll into a Seek now maps the scroll to the **nearest whole frame** (`TimelineScale.snappedTime`). It dispatches `onSeek(t, snap = true)` only when that frame changes, so there's at most one Seek per frame crossed (fewer than before, when every pixel produced a Seek). During the drag the content follows the finger smoothly. On release, the playhead → scroll effect (now also keyed on `isScrollInProgress`) settles the content exactly on that frame's x.
2. **The edit's edge scroll** (`finishScroll`, after a clip drag that auto-scrolled) also lands on a whole frame.
3. **`EditorScreen.onSeek`:** the reducer snapped, but the preview was handed the raw time (`previewController?.seek(timeUs)`). It now gets the kept, snapped time (`editor.state.playheadUs`).
4. **Readouts:** `CompositionTimecode.formatPlayhead` (via `TimeFormat.formatPlayhead`) is used by the transport and the timeline pill.
   - It shows the frame **on screen (floor)**, counted as start frame + composition frame, so playback never reads a frame ahead.
   - `FrameRate.floorFrame(t)` = the last frame whose start is ≤ t. A frame's own start always maps to itself, so at rest floor = nearest.
   - Durations (the transport's "/ end"), dialogs and typed values keep nearest rounding (`CompositionTimecode.format`).
5. **Pause (done at the call site).** `EditorScreen` `onPlayPauseToggle`: after `pause()`, the playhead moves to the frame that was on screen (`frameTimeUs(floorFrame(editor.state.playheadUs))`, read from live state) with `Seek` + `previewController.seek`.
   - **No echo:** every `update()`/`seek()` starts a new controller generation, and position reports from an older generation are dropped (`PreviewController.position` checks the epoch).
   - **A16:** playhead 2,266,666 µs before the pause tap; paused at 2,300,000 µs (frame 69, a whole frame), pill `00:02:09`. Unchanged 600 ms later.

**Unchanged:** Prev/Next, markers, ruler taps (already `frameRate.snap`) and the Graph key double tap (`Seek(snap = true)`) still land on frames.

## TL1b. Tiered ruler ticks

- **One time ↔ x function:** `TimelineScale` (`TimelineRulerScale.kt`).
  - `frameX(f) = round(f × pxPerFrame)`, with pxPerFrame from the rational rate.
  - `timeX(t)` = `frameX` for frame times, else `round(t × pxPerUs)` (playback).
  - The ruler draws every tick at `frameX`, and the playhead's scroll offset is `timeX(playhead)`. JVM: they agree for every frame at 12, 120, 600 and 2400 dp/s, at all eight rates.
- **Whole pixels:**
  - The leading spacer is a whole number of pixels (`centerPx`).
  - Each tick is a `drawRect` with a whole-pixel left edge (`frameX − w/2`) and a whole-pixel width: round(1dp) = 3 px for majors, round(0.75dp) = 2 px for mids and minors.
  - The playhead line is positioned at `centerPx − lineWidth/2` by integer offsets, not centring alignment, so it covers exactly its tick's pixels.
  - Result: no anti-aliased double lines. The A16 column scan found **0 ghost columns** in 15 scans.
- **Tiers** (`RulerTiers`), classified by the **displayed timecode frame** (start frame + composition frame), so majors land where the pill shows `:00`:
  - **Major:** every step boundary (1 s zoomed in), the tallest (12dp = 34 px).
  - **Mid:** the middle of the step, ≈ 60% (20 px).
  - **Minor:** every other frame, ≈ 35% (12 px).
  - The tick colours and alphas are unchanged; there are still no labels.
- **Mid-tick rule:** the mid sits on timecode frame `floor(period / 2)` of each step, the same for every rate. At 1 s: 30 / 29.97 → 15; 25 → 12 (12.5 isn't a frame); 24 / 23.976 → 12; 50 → 25; 60 / 59.94 → 30.
- **Zoom adaptation**, dropping tiers in order:
  - Minors hide when the frame pitch is under **6dp** (the ruler's existing minimum).
  - Mids hide when half a step is under **8dp**.
  - The major step then grows: 1, 2, 5, 10, 30 s, 1, 2, 5, 10, 30 min, 1 h. That keeps majors at least **12dp** apart, with mids at half the step again when they fit.
  - At 30 fps: 240dp/s shows all frames; 120dp/s shows majors and mids; 14dp/s shows 1 s majors only; 10dp/s shows 2 s majors with a 1 s mid; 4dp/s shows 5 s majors with a 2.5 s mid. JVM-checked for all eight rates.
- **A16 screenshots** (30 fps, ruler strip around the playhead):
  - **Zoomed in (×2.2, 24.8 px/frame):** majors at frames 60 and 90, a mid at 75, minors on every frame. The playhead (frame 73, `00:02:13`) sits on its minor.
  - **120dp/s (11.3 px/frame):** majors and mids only, a clean alternation; playhead on frame 64.
  - **Zoomed out (1.3 px/frame):** 1 s majors only, a comb that ends at the end of the timeline.
  - In every state each tick is one solid run of 3 or 2 px. Tick heights are 34 / 20 / 12 px.

## TL1c. Live Time tile on the Graph page

With no key selected, the Value view's Time tile shows the **playhead's timecode**: `GraphTime.playhead`, the transport's formatter, floor mode. It updates while scrubbing and playing, and stays not editable. With a key selected it shows and edits that key's time, as in G2.1. Key times are on the frame grid and use start frame + nearest frame (`GraphTime.label` / `parse`), the same count as the ruler and pill.

**A16 (`graphTimeTileFollowsTheScrub`):** after three scrubs with the Graph page open, the tile showed `00:01:21`, `00:01:22` and `00:01:12`, matching the pill each time, and was not enabled.

## Tests and run times

| Run | Wall time | Result |
|---|---|---|
| JVM `TL1Test` (iteration) | 107 s, then 14 s | 2 test mistakes, then green |
| JVM full suite (once) + installs | 150 s | **571 / 0** |
| A16 `TimeAuditTest` | 84 s, 41 s, 99 s, 85 s, 92 s, 27 s | test-harness fixes only (below), then all 7 green |
| A16 `TimeAuditTest#fps30` (strip screenshots) | ≈ 40 s | green |
| A16 sweep: `GraphPlayheadTest`, `GraphValueTest` | 122 s | 16 / 16 |

- **JVM `TL1Test`:**
  - nominal fps;
  - no drift at 10 h (all eight rates);
  - snap = nearest, readout = floor, and the two agree at rest;
  - tiers against the displayed timecode for eight rates × three start offsets (including an off-grid one);
  - the mid rule;
  - zoom adaptation in order, with the explicit 30 fps sequence;
  - frame x = scroll offset for every frame at four zooms.
- **`GraphG21Test` time tests** still pass.
- **A16 harness fixes** (none of them product bugs):
  - scrub on the ruler row and start clear of the playhead marker's 48dp target (holding there opens the marker popover; the sheet covers the lower timeline);
  - scan most of the viewport, capped at the timeline's last frame;
  - the first and last frames' ticks overhang the ruler by half a tick (expected ink);
  - zoom in further at 60 fps so minors show;
  - inject the pause tap raw (Compose never idles while playing).
- **Strip screenshots** are left in `Android/data/com.fluxx.android.debug/files/tl1-audit/` on the phone: 3 PNGs, nothing else written or deleted.

## Not verified

- The playback readout on screen while playing. Compose never idles during playback, so the test can't read the pill then; floor mode is JVM-checked and is the only formatter the pill uses.
- Real-finger scrubbing and flings.
- 23.976, 50 and 59.94 on the device (JVM only).
- Very long timelines on the device.
- TalkBack.

## Phone test list

1. Scrub slowly from frame 0 → 1 → 2 → … The playhead stops on a tick every time, and the pill names that tick's frame.
2. Fling the timeline. When it stops, the playhead is on a tick.
3. Play, then pause. The pill doesn't jump forward, and the playhead rests on the frame that was showing.
4. Pinch in: all frames show, with three tick heights (seconds tallest, half seconds medium). Pinch out: minors go, then mids, then majors every 2 s, 5 s, … with no solid wall of ticks.
5. A 25 fps composition: the medium tick sits on frame 12.
6. A composition with a start timecode: tall ticks land where the pill reads `:00`.
7. Open the Graph page (Value view) with no key selected: the Time tile follows the pill while scrubbing and playing.

## Deviations

- **The pill and transport floor at all times,** not only while playing. At rest the playhead is always on a frame, so floor equals nearest. One rule, with no mode switch on play/pause.
- **Start-timecode count.** The playhead readout counts start frame (floor of the start time) + composition frame, instead of rounding start + time as one number. This is identical whenever the start is on the frame grid. For an off-grid start (e.g. 1 h at 29.97 = 107,892.1 frames) the pill, the ruler's majors and the Graph all use the same count.
- **Tick widths** are whole pixels (3 / 2 px at this density) rather than fractional 1dp / 0.75dp lines.
- **`TimelineSection.kt`** (an older, unused timeline with its own 100 dp/s zoom) wasn't changed; `EditorScreen` uses `TimelineView`.
- **Test hooks:** `TimelineView` exposes its zoom to tests through a semantics key that accessibility doesn't read, and tags the ruler and the playhead line.
