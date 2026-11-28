<a id="rec-tlv1"></a>

# TLV1: timeline virtualisation (the horizontal axis as time)

Branch `tlv`, cut from `master` at `d3eba92`. **Not merged into `master`.** Snxw merges after phone testing. Raw results are in `docs/checkpoints/tlv1/`.

## Part A: AUD3 merged (git only)

| Step | Result |
|---|---|
| Before | `master` `f57a611`, `timeline` `ba8bab7` (local and origin); working tree clean |
| Ancestry | `master` is an ancestor of `timeline`, so there were no conflicts |
| Merge | `git merge --no-ff timeline` on `master`: **merge commit `5fbadd9`** ("Merge timeline: AUD3 (HE-AAC placement, MP4 edit-list priming), PCM cache v3"), pushed |
| JVM on `master` | **727 tests, 0 failures** (55 s, `--rerun` of the test task: the first call was up to date at 7 s because the tree equals `timeline`'s) |
| Records | "records: AUD3 merged" `d3eba92` on `master` (TLP.md and AUD3.md status), pushed |
| Branches | `master` `d3eba92`, `timeline` `ba8bab7` (kept), `graph`, `text` untouched, `tlv` new from `d3eba92` |

## B0. Inventory (before any change)

The timeline's horizontal content was ONE scrolled node, a `Row` inside `horizontalScroll`:
- a half-viewport spacer, then a `Column` (the ruler, then the `LazyColumn` of rows), then a half-viewport spacer;
- every child computed x as `time × pxPerUs` from time 0;
- the width was `durationUs × pxPerUs + viewport`;
- MP1.1 kept that under 200,000 px with a zoom ceiling tied to the length.

**What the old code does on the A16 (density 2.8125, 1080 px):**
- the short project (60 s) reaches 1178 dp/s, against 2400;
- Project 1 (1078.7 s) is capped at 65.6 dp/s;
- the 3-hour composition is capped at 6.5 dp/s (0.6 px per frame).

### Migration map (every place that reads or writes the horizontal scroll, the content width, the zoom, or converts time and x)

| # | File, function | What it does (before) |
|---|---|---|
| M1 | `TimelineView.TimelineView`, `chosenDpPerSecond` / `pxPerSecond` / `pxPerUs` | The chosen zoom, clamped by `TimelineZoom.clamp(dp, layoutSpan, density, windowPx)` (the MP1.1 ceiling); `pxPerUs` for every child |
| M2 | `TimelineView`, `scale` (`TimelineScale`) | TL1 frame x: `round(frame × pxPerFrame)`, absolute content px (Long) |
| M3 | `TimelineView`, `horizontalScrollState = rememberScrollState()` | The pixel scroll (Int content px under the playhead) |
| M4 | `TimelineView`, `beginEdit` / `finishScroll` (`editScrollOrigin`) | An edit's edge scroll lands the playhead on a whole frame (`snappedTime(scroll)`) |
| M5 | `TimelineView`, `LaunchedEffect(edgeScroll)` | Edge auto-scroll: `scrollBy(edgeScroll)` every 16 ms |
| M6 | `TimelineView`, the pinch `pointerInput` | `chosenDpPerSecond = clamp(zoomNow × zoom, …)`; the anchor is the playhead (the follow effect re-centres it) |
| M7 | `TimelineView`, `centerPx` / `centerXPx` / `centerXDp` | The playhead column and the spacers' width |
| M8 | `TimelineView`, `scrollOffsetReader` | `{ horizontalScrollState.value }` for clip, key and twirl drags (content-coordinate drags under auto-scroll) |
| M9 | `TimelineView`, `keyEdgeFn` (`EdgeScroll.speed`) | The ramped edge speed for key drags (px per frame) |
| M10 | `TimelineView`, `keyFinish` | After a key drag: `animateScrollTo(timeX(playhead))`, easing back, not a scrub |
| M11 | `TimelineView`, `visibleRange` | `VisibleRange { scroll − centre .. scroll + centre }` (content px) for the twirl rows' draw phase |
| M12 | `TimelineView`, `contentWidthPx` / `contentWidthDp` | `durationUs × pxPerUs`: the giant node's width |
| M13 | `TimelineView`, follow `LaunchedEffect(playheadUs, pxPerUs, …)` | Playing: `dispatchRawDelta(TimelineFollow.scrollFor(heard) − scroll)` (never a scrub). Else `scrollTo(timeX(playhead))` |
| M14 | `TimelineView`, scrub `snapshotFlow { scroll, inProgress }` | While the user scrolls: `onSeek(snappedTime(scroll))` once per frame crossed |
| M15 | `TimelineView`, `.horizontalScroll(…)` + `Row(width = content + 2 × centre)` + the two `Spacer`s | The container, drag, fling and bounds `[0, durationUs × pxPerUs]` |
| M16 | `TimelineView`, `WaveformProbe.scroll` | Test probe: the content's left edge in root px |
| M17 | `TimelineView`, the ruler's `visibleUs` | `(scroll ± centre) / pxPerUs` |
| M18 | `TimelineView`, the clip row's `onMoveEdge` | Clip-drag edge scroll, ±16 px per frame within 48 dp of an edge |
| M19 | `TimelineRuler` (whole function) | Tap: `offset.x / pxPerUs` to seek / nearest marker. Draw: baseline and ticks at `scale.frameX`, drag highlights, markers and labels at `time × pxPerUs`. Popover anchor at `marker × pxPerUs`. Semantics `TimelineZoomKey = pxPerUs` (used by UI tests) |
| M20 | `TimelineClipRow`, `previewMove` | Clip drag: `(movePixels + scroll − scrollOrigin) / pxPerUs`, snap zone `SnapZones.of(pxPerUs / density)` |
| M21 | `TimelineClipRow`, clip box | `offset(start × pxPerUs)`, `width(duration × pxPerUs)`: one node per clip as wide as the clip |
| M22 | `TimelineClipRow`, thumbnail canvas | `widthPx / 48` tiles from the clip's start |
| M23 | `TimelineClipRow`, hit box | `TimelineHits.clipBox(startDp, widthDp)` (48 dp minimum) |
| M24 | `TimelineClipRow`, key container | `offset(startDp).width(widthDp)`; quiet keys drawn at `t × pxPerUs`; editable key targets at `t × pxPerUs` (dp), split by `TimelineHits.spans` |
| M25 | `TimelineClipRow`, key drag | `kfTime + dx / pxPerUs`, snap zone, `EdgeScroll.contentDx(lastDx, scrollAtDown, scroll)` under auto-scroll |
| M26 | `TimelineClipRow`, layer markers | Flags, targets and labels at `time × pxPerUs` (dp), split at midpoints; popover anchor |
| M27 | `StationaryTimelinePlayhead` | The fixed column (`columnPx`); not on the scrolled axis (unchanged) |
| M28 | `Twirl.TwirlTrackRow` | Draws keys in the visible range: `firstVisible(times, anchor, pxPerUs, range.start)`, `x = comp × pxPerUs` |
| M29 | `Twirl.firstVisible` / `nearestKey` | Binary search and hit test in content px (`comp × pxPerUs`) |
| M30 | `Twirl.TwirlAudioRow` | Waveform: `clipL / clipR = start / end × pxPerUs`, per pixel `x / pxPerUs` to source time; `WaveformProbe.draw` |
| M31 | `Twirl.TimelineFollow` | `scrollFor(heard) = round(heard × pxPerUs)`; `screenX(t, scroll) = line + t × pxPerUs − scroll` |
| M32 | `Twirl.VisibleRange` | The visible content px (Float) |
| M33 | `TwirlKeys.TwirlKeyRow` | Key drag `dx / pxPerUs`, snap zone, `contentDx(…, scroll)`; down: `nearestKey(…, p.x)`, bar tap `p.x / pxPerUs`; popover anchors at `time × pxPerUs` |
| M34 | `TwirlKeys.drawColourBar` | Segments at `(anchor + key) × pxPerUs`, clipped to the visible content px |
| M35 | `TimelineRulerScale.TimelineZoom` | `MIN 0.5`, `MAX 2400`, the MP1.1 `MAX_CONTENT_PX` / `ceiling` / `floor` / `clamp(…, length)` / `contentPx` |
| M36 | `EdgeScroll.contentDx` | `screenDx + (scrollNow − scrollAtDown)` in Int content px |
| M37 | `TimelineSection.kt` (whole file) | **Dead code** (only its own `@Preview` uses it): an older timeline with its own `horizontalScroll` and `time × pxPerUs` |
| M38 | UI tests (`TimeAuditTest`, `Tl3UiTest`, `Tl31UiTest`, `TlpTwoUiTest`, `TlpUiTest`) | Read `TimelineZoomKey` and tap or hold at `t × pxPerUs` in row-local coordinates |
| M39 | `Aud2Bench` (`WaveformProbe.scroll` / `draw` / `placed`) | Benchmarks of the waveform row and the follow |

Not on the horizontal axis (unchanged):
- `TimelineSideControls` (its `startEdgeScroll` is the vertical reorder);
- the vertical `LazyListState`, `TwirlArea`, the inspector viewport capture and restore (vertical, `preInspectorViewport`);
- `DragSnap` (already in time);
- `SnapZones` (takes zoom, not position);
- `MarkerPopover`, `DButton`.

### Regression checklist (before)

| # | Behaviour | Covered by (before) |
|---|---|---|
| R1 | Drag-scroll the timeline (finger) and scrub to whole frames | `TimeAuditTest` fps24 / 25 / 2997 / 30 / 60 (scrub lands on frames), `TL1Test` |
| R2 | Fling feel and stop; no overscroll stretch at 00:00 | NO TEST (finger feel); `Tl31Test` documents no overscroll |
| R3 | Pinch zoom, anchored at the playhead | NO TEST |
| R4 | Playback follow: raw delta, never a scrub, 16 ms lead | `Aud2Test` (`TimelineFollow`), `Aud2ScrubTest` / `Aud2ResumeTest` (media, device) |
| R5 | Ruler tap seeks; a marker within 48 dp is selected instead | `TimelineZoomFloorTest` (targets), `MarkersTest`; NO UI TEST of the ruler tap |
| R6 | Clip move by hold-drag, with snapping to the playhead, clip ends, markers and ticks | `TlpTwoUiTest.aLayerDragSnapsToThePlayhead…` / `…AnotherClipsEnd`, `DragSnap` in `TlpKeyTest` |
| R7 | Group move of a layer selection | `TlpMultiLayerTest` (JVM) |
| R8 | Edge auto-scroll while dragging a clip | NO TEST |
| R9 | Edge auto-scroll while dragging a key | `Tl31UiTest.b1DraggingAKeyToTheEdgeScrollsTheTimeline` |
| R10 | Key gestures on the layer bar (tap, hold, hold-drag, key mode) | `TlpKeyTest` (JVM), `TlpOneUiTest` |
| R11 | Twirl-down rows: keys, value popover, key mode | `Tl3UiTest` (several), `Tl31UiTest.a1…` |
| R12 | Colour bar and its popover | `Tl3UiTest.theColourBarShowsAndAddingAKeyKeepsItsLook` |
| R13 | Audio waveform row | `Tl3UiTest.aLayerWithAudioGetsTheAudioRowFirstAndItsPeaks`, `Tl31UiTest.b6…`, `Aud2WaveformTest` (media) |
| R14 | Thumbnails on video clips | NO TEST (visual) |
| R15 | Layer markers and composition markers (tap, popover anchor) | `MarkerPopoverTest`, `MarkersTest` (JVM); NO UI TEST of the anchor x |
| R16 | Select-all asks layers or keyframes; Curves popover | `Tl31UiTest.b4…`, `Tl31UiTest.b5…` |
| R17 | D-button zones and selection mode | `TlpUiTest` (3) |
| R18 | Layer reorder (vertical) | `TlpUiTest` / `TlpTwoUiTest` (layout), JVM reorder tests |
| R19 | Stagger and Sequence | `Mp11Test`, `TlpMultiLayerTest` |
| R20 | Inspector open / close restores the (vertical) viewport | `Tl3UiTest.theArrowWithTheSheetOpenClosesIt…` (partly) |
| R21 | Centred-playhead ends: time 0 and the end reach the centre | `TL1Test` (frame x); NO UI TEST |
| R22 | Graph time tile follows the scrub; playback pause rests on the frame on screen | `TimeAuditTest.graphTimeTileFollowsTheScrub`, `…playbackPauseRestsOnTheFrameOnScreen` |
| R23 | Key drag eases back to the playhead after release (no seek) | `Tl31UiTest.b1…` (partly) |
| R24 | The highest zoom on long compositions | `Mp11Test` (the MP1.1 ceiling), `TimelineZoomFloorTest` |

The NO TEST items get tests in B4 where a test can see them (R3, R5, R8, R15, R21). R2 and R14 remain for the finger and the eye (phone checklist).

## B1–B5: what changed

| Commit | Step |
|---|---|
| `33c862f` | B0 / TLV1 A: the inventory above, the test-only `TimelineProbe`, `Tlv1Test` |
| `86b87ac` | B1: `TimelineViewport` (pure, `TimelineViewportTest`, 8 tests) |
| `4a59c4c` | B2 + B3: the time-based scroll state and the windowed timeline (one commit: the pixel scroll container and the full-width children could not be separated without a broken intermediate build) |
| `6471604` | C (B4): `TimelineAxisTest` (5 tests) for the checklist items that had no test |
| `23a5d68` | D (B5): stress and measurement; two fixes found on the phone (below) |

**The design as built:**
- **The scroll state is the time under the playhead** (`TimelineScrollState.centreUs`, Long µs, with the sub-µs rest kept). It is a `ScrollableState`, used through `Modifier.scrollable` with the old `horizontalScroll`'s own direction (`ScrollableDefaults.reverseDirection`) and the platform fling, with no overscroll.
  - Drag, fling, edge auto-scroll and the key-drag return are scrolls in progress.
  - Playback follow and the settle on the playhead are plain state writes (`jumpTo`), so they are never a scrub.
- **Only a window is laid out:** the viewport plus half a viewport each side (2160 px on the A16).
  - Its origin is a TL1 whole-pixel content x that moves in half-viewport steps (`TimelineViewport.originFor`). In between, the window only translates, in the draw phase (`graphicsLayer`).
  - Every horizontal child maps time to its own x through `TimelineAxis`: `x = t × pxPerUs − origin`, in Double, a small number at any length.
- **The zoom is 0.5–2400 dp/s at every length** (`TimelineZoom.clamp`). MP1.1's `MAX_CONTENT_PX`, `ceiling`, length-based `floor` / `clamp` and `contentPx` are gone.
- **The scroll range is 0 to the longest of the composition and the latest layer end.**

**Migration map, converted:**

| # | Conversion |
|---|---|
| M1 | Zoom without a length |
| M2 | `TimelineScale` kept; frame x minus origin for ticks |
| M3 | → `TimelineScrollState` |
| M4 | The origin is a centre time; snapped from the content px |
| M5 | `scrollBy` on the state: the same px per frame at the same zoom |
| M6 | Pinch: the time under the playhead is unchanged (the state is that time) |
| M7 | The centre column kept; spacers gone |
| M8 | Reader returns Double content px |
| M9 | Unchanged |
| M10 | `animateTo(playhead)` |
| M11, M32 | `VisibleRange` removed (rows draw the window) |
| M12, M15 | Container and spacers → `scrollable` + one window-wide `Column` translated in the draw phase; semantics `HorizontalScrollAxisRange` kept |
| M13 | `jumpTo(TimelineFollow.centreFor(heard))` |
| M14 | Scrub from the centre time |
| M16 | `WaveformProbe.scroll` reported from the draw phase |
| M17, M19 | The ruler draws the window range at window x, taps through `axis.timeAt`, markers and popover at `axis.x` |
| M20, M25 | Drags in Double content px |
| M21 | The clip box is the clip's part in the window (plus 48 dp), with corners only at real ends |
| M22 | Thumbnail tiles from the clip's real start, only those in the box |
| M23 | The hit box is built only while visible or dragged |
| M24 | Keys at window x; only keys in the window plus a target (or being dragged) are drawn or composed |
| M26 | Markers at window x, only near the window |
| M27 | Unchanged |
| M28–M30, M33, M34 | Through `TimelineAxis`, drawing the window |
| M31 | `centreFor` / `screenX(t, centre)`: relative |
| M35 | Ceiling removed |
| M36 | `contentDx` in Double |
| M37 | `TimelineSection.kt` deleted |
| M38 | UI tests: touches placed in the visible bounds (below) |
| M39 | Probes kept with the same meaning |

**Found and fixed on the phone (B5):**
1. **The twirl key gesture handler kept the FIRST `TimelineAxis`.** It is keyed on row and zoom, and the axis changes once the real viewport width arrives, so taps and holds missed their keys. It now reads the latest axis (`rememberUpdatedState`).
2. **Test-only touch placement.** A row node is now a window wider than the screen and starts off screen, so its clipped `boundsInRoot` (which `performTouchInput` measures from) no longer begins at its local 0. `Tl3UiTest` / `Tl31UiTest` now place touches from the scroll semantics, as `TimeAuditTest` does. Real fingers were never affected.
   - With 1 and 2, the two classes went from 3 and 4 failures (they passed on the before build) to 7 / 7 and 10 / 10.
3. **The JVM tests caught a 2 px precision loss** in `TimelineAxis.timeAt` at 3 hours: a `Float` was added to a `Long` and the result stayed a `Float`. It is now Double.

## Before / after (A16, `Tlv1Test`)

**Method.** The Compose test rule runs the UI on a virtual frame clock: a sleeping test thread runs no frames. The first "before" runs therefore measured a frozen UI (0 frames; discarded). The class now pumps one frame per 16 ms of real time while it measures. Frame times include that pump, so they are comparable before against after, not absolute app numbers. Before = the `d3eba92` app with the same test APK.

| | Short (30 × 2 s, 60 s) before → after | Project 1 (1078.7 s) before → after | 3 h (180 × 60 s) before → after |
|---|---|---|---|
| Default zoom used | 120 → 120 dp/s | **65.6** (ceiling) → 120 | **6.5** (ceiling) → 120 |
| **Highest zoom reached** | 1178.8 → **2400 dp/s** (110.5 → 225 px/frame) | 65.6 → **2400** (9.2 → 337.5 px/frame at 20 fps) | 6.5 → **2400** (0.61 → 225 px/frame) |
| **Widest timeline node** | 21,330 → **2160 px** | 200,000 → **2160** | 200,000 → **2160** |
| Layout nodes on screen | 238 → 180 (190 at top zoom) | 238 → 180 | 238 → 180 |
| Follow 5 s: UI frame p50 / p95 / p99 | 65.3 / 106.6 / 119.1 → 64.7 / 108.8 / 127.5 ms | 45.3 / 64.6 / 66.7 → 38.2 / **51.4** / 63.9 (zooms differ) | 38.2 / 57.7 / 61.6 → 33.4 / **47.9** / 58.1 (zooms differ) |
| Follow 5 s: main thread busy, worst message | 4596 ms, 127 ms → 4274 ms, 117 ms | 3879, 89 → 3160, 53 | 4032, 82 → 3378, 89 |
| Follow 5 s: timeline / clip-row recompositions | 55 / 594 → 59 / 638 | 66 / 715 → 80 / 869 | 88 / 946 → 98 / 1067 |
| Fling 8000 px/s: UI p50 / p95 / p99 | 51.0 / 65.5 / 69.2 → 49.6 / 70.6 / 83.5 ms | 37.9 / 49.4 / 56.6 → 35.7 / 60.2 / 68.5 (before moved 18.2 s at 65.6 dp/s, after 9.9 s at 120) | 38.8 / 47.5 / 48.7 → 35.6 / 43.9 / 45.1 |
| Fling: main thread busy, worst | 3426, 70 → 3384, 64 | 2637, 51 → 2563, 65 | 2400, 47 → 2235, 41 |

- **The targets:**
  - **Node count independent of length: met.** It is 177–190 at 60 s, 30 min, 50 min and 3 h, at any zoom.
  - **The highest zoom at any length equals the short-project maximum: met** (2400 dp/s everywhere, including 50 min and 3 h).
  - **No node wider than the window: met** (2160 px, asserted).
- **Timeline UI p95 "not worse":**
  - **Follow:** equal on the short project (106.6 → 108.8, within run-to-run noise) and better on Project 1 and 3 h, but those ran at different zooms (the old build was capped).
  - **Fling:** on the short project at the same zoom, p95 went 65.5 → 70.6 ms, slightly worse. Project 1's fling is not comparable (different zoom and distance). This is reported, not hidden.
  - The rows recompose about once per frame before and after alike: the timeline recomposes on every playhead change. That cost predates TLV1 and is not changed here.
- **B5 stress (after only):**
  - **3 h at the top zoom:** 225 px per frame, with the start, middle (5400 s) and end (10800 s) reached exactly; 180–185 nodes, widest 2160 px.
  - **The 50-minute HEVC** (`Project 3 [F85C683].mp4`, imported through the real add path from its MediaStore content URI): composition 3018.3 s, top zoom 2400 dp/s (225 px per frame) = the short-project maximum. 92 nodes, widest 2160.
  - **Sequence 30 × 60 s** (the typical clip, content URIs, the browser's Sequence): 1800 s; top zoom 2400 dp/s; at 0, 900 and 1800 s, 177–182 nodes, widest 2160.
  - **Fling across 3 h** (4 dp/s, flings of 30,000 px/s): 60 flings in 33.2 s reached 10,783.1 of 10,800 s. **0 of 831 drawn frames had a window short of the screen** (no blank edge). UI p50 33.0, p95 43.9, p99 55.4, max 110.2 ms.
    - The test's "reached the end" assertion failed by 17 s (its 60-fling budget). That is a test limit, not a defect; it was not re-run (test economy).

## Regression checklist (after)

| # | Behaviour | Result |
|---|---|---|
| R1 | Drag-scroll, scrub to whole frames | **pass**: `TimeAuditTest` 7 / 7 (fps 24 / 25 / 29.97 with timecode / 30 / 60: playhead on a frame, line within 0.5 px of its tick, ruler column scan), `TimelineAxisTest` |
| R2 | Fling feel and stop | Same `scrollable` and platform fling as `horizontalScroll`; `TimelineAxisTest` (bounds, no consumption past the ends). **Feel: phone checklist** |
| R3 | Pinch at the playhead | **pass**: `TimelineViewportTest`, `TimelineAxisTest` (the time under the playhead is unchanged). Finger: phone checklist |
| R4 | Playback follow, never a scrub | **pass**: `TimelineAxisTest` (`jumpTo` is not "in progress"), `Aud2Test`; `Tlv1Test` follow ran, 4.3–4.9 s in 5 s |
| R5 | Ruler tap seek, marker targets | **pass**: `TimelineAxisTest` (tap → time within 0.5 px at 3 h), `TimelineZoomFloorTest` |
| R6 | Clip move with snapping | **pass**: `TlpTwoUiTest` 4 / 4 (snaps to the playhead and to another clip's end, readouts) |
| R7 | Group move | **pass**: `TlpMultiLayerTest` (JVM) |
| R8 | Edge auto-scroll, clip drag | **pass** (time conversion): `TimelineAxisTest`. Finger: phone checklist |
| R9 | Edge auto-scroll, key drag | **pass**: `Tl31UiTest.b1DraggingAKeyToTheEdgeScrollsTheTimeline` |
| R10 | Layer-bar key gestures | **pass**: `TlpOneUiTest` 4 / 4, `TlpKeyTest` |
| R11 | Twirl rows, value popover, key mode | **pass**: `Tl3UiTest` 7 / 7, `Tl31UiTest` 10 / 10 (after the B5 fixes) |
| R12 | Colour bar and popover | **pass**: `Tl3UiTest.theColourBarShowsAndAddingAKeyKeepsItsLook` |
| R13 | Audio waveform row | **pass**: `Tl3UiTest.aLayerWithAudioGetsTheAudioRowFirstAndItsPeaks`, `Tl31UiTest.b6…` |
| R14 | Thumbnails | Visual: phone checklist (tiles now start at the clip's real start inside the window) |
| R15 | Markers, popover anchor | **pass**: `TimelineAxisTest` (window x 3 h in at frame zoom), `MarkerPopoverTest` / `MarkersTest` |
| R16 | Select-all, Curves popover | **pass**: `Tl31UiTest.b4…`, `.b5…` |
| R17 | D-button zones, selection mode | **pass**: `TlpUiTest` 3 / 3 |
| R18 | Layer reorder (vertical) | **pass**: `TlpUiTest` / `TlpTwoUiTest` (vertical, unchanged) |
| R19 | Stagger, Sequence | **pass**: `Mp11Test`, `TlpMultiLayerTest`; B5 Sequence 30 × 60 s |
| R20 | Inspector restore (vertical) | **pass**: `Tl3UiTest.theArrowWithTheSheetOpen…` (vertical, unchanged) |
| R21 | Both ends reach the centre | **pass**: `TimelineViewportTest`, `TimelineAxisTest`; B5 3 h start / end exact |
| R22 | Graph time tile, pause on the frame | **pass**: `TimeAuditTest.graphTimeTileFollowsTheScrub`, `…playbackPauseRestsOnTheFrameOnScreen`; `GraphPlayheadTest` 8 / 8 |
| R23 | Key drag eases back (no seek) | **pass**: `Tl31UiTest.b1…` ("no seek") |
| R24 | Highest zoom on long compositions | **pass**: `Mp11Test` (new rules), `TimelineViewportTest`, B5 |

- **Trim handles:** the live timeline has none. Trimming is the clip quick actions (at the playhead, no position), and the only drag handles were in the dead `TimelineSection` (deleted).

## Records

- **Unchanged:** the gesture protocol, the `EditorAction` contract, the batch/draw contract, the evaluator, `SetKeyEasing`, preparation, and the schema (v13).
  - Nothing under the renderer, decoders, audio, model, export or `PreviewController` changed. Production code changed only in `ui/timeline` and in `editor` (`TimelineViewport` new, `TimelineZoom`, `EdgeScroll.contentDx`).
  - The one test under `render/` (`Aud2Test`) changed only for `TimelineFollow`'s time API.
  - Scroll and zoom state stay UI state.
  - `git diff --stat d3eba92..23a5d68` (code): 17 files, +924 / −911. Main: `ui/timeline` (TimelineView, Twirl, TwirlKeys, TimelineScroll new, TimelineProbe new, TimelineSection deleted) and `editor` (TimelineViewport new, TimelineRulerScale, EdgeScroll). Tests: `editor` (Mp11Test, TimelineViewportTest new, Tl31Test), `render` (Aud2Test), `ui/timeline` (TimelineAxisTest new), androidTest (`Tl3UiTest`, `Tl31UiTest`), androidTestMedia (`Tlv1Test` new).
- **JVM:**
  - on `master` after the AUD3 merge: 727 tests, 0 failures, 55 s;
  - at the end of Part B: **740 tests, 0 failures, 51 s**.
- **Device classes (one per invocation), all with Saver 0, on AC, screen on and unlocked, at 35.8–37.7 °C** (`docs/checkpoints/tlv1/device_runs.txt`):
  - **`Tlv1Test` before** (frozen clock; invalid): 81 s, then 176 s, then 174 s. The first had another app in front (WhatsApp; the test window had no focus); the next two ran on the frozen virtual clock. All discarded. Valid before: **89 s**.
  - **`Tlv1Test` after:** 2025 s (frozen clock; invalid), then **176 s** (valid: 7 run, 1 failure, the fling budget above).
  - **`TimeAuditTest`** 143 s OK 7; **`TlpUiTest`** 22 s OK 3; **`TlpOneUiTest`** 37 s OK 4; **`TlpTwoUiTest`** 20 s OK 4; **`GraphPlayheadTest`** 94 s OK 8.
  - **`Tl3UiTest`:** 31 s 3 failures, then 33 s 3 failures, then **OK 7**. **`Tl31UiTest`:** 49 s 4 failures, then 53 s 4 failures, then **OK 10**.
  - **Baseline on the before build:** `Tl3UiTest` OK 7, `Tl31UiTest` OK 10.
  - A diagnostic class (one run, then deleted) located the touch-placement cause.
- **Infrastructure:** the phone dropped off wireless debugging once (1 infrastructure failure; reconnected by Snxw). Its transport id changed several times, so the device was addressed by transport id, not by its name with spaces.
- **Project 1:** read through `ProjectManager.loadProject` only; size 9256, mtime 2026-11-27 20:56:51.123 before and after (adb stat and in-process checks; Snxw had opened it in the app at 20:56, before this pass).
- **Phone state:**
  - found: Saver 1, stay-on 0, timeout 600000;
  - for the session: Saver 0, stay-on 15, timeout 600000;
  - **restored:** Saver 1, stay-on 0, timeout 600000.
  - The crash buffer at the end held only vendor info lines (`libpq_sec`, `meow_lib`, a system process), with no Fluxx crash.
- **`tlv` was merged into `master` on 2026-11-28 at merge commit `8ad3dea`** (recorded in TLP.md).
- **Not verified:**
  - a real finger: fling feel and stop, pinch, edge auto-scroll speed, thumbnails, jitter while playing (the phone checklist);
  - other devices, densities and RTL layouts (`reverseDirection` follows the layout direction as before; not run);
  - accessibility scroll actions (the `HorizontalScrollAxisRange` semantics is kept; actions are not);
  - the waveform benchmarks (`Aud2WaveformTest`; their probes keep their meaning, not run);
  - export (untouched).

## Phone checklist for Snxw (`tlv` build)

1. **Slow drag:** the timeline follows the finger exactly; scrubbing lands on whole frames.
2. **Fast fling:** the same feel and stopping as before; no stretch at 00:00 or at the end.
3. **Pinch zoom:** it stays anchored at the playhead; zoom in to frame level and out to the whole composition.
4. **Edge auto-scroll:** hold-drag a clip, then a key (layer bar and twirl row), to the left and right edges. It scrolls at the same speed as before and snaps as before.
5. **Snapping:** a clip drag snaps to the playhead, clip edges, markers and ticks (with the haptic tick).
6. **Playback follow:** play a long clip. The timeline moves smoothly under the playhead, with no jitter or jumps every half screen.
7. **The 50-minute clip:** zoom in fully. It reaches frame level (wide frames on the ruler) anywhere along the clip.
8. **A 3-hour composition (or Project 1):** frame-level zoom at the start, middle and end; flinging across it never shows a blank edge.
9. **Sequence of 30 clips:** clip boxes, thumbnails and labels look as before; clips longer than the screen have square corners only where they run off the window.
10. **Ruler tap:** it seeks to the tapped frame; a marker near the tap is selected instead.
11. **Inspector:** open and close it; the layer list returns to where it was.
12. **Select-all and the Curves popover:** they open and act as before.
13. **Twirl rows:** keys, the value popover, key mode, the colour bar and the waveform, at the start of a long composition and hours in.
