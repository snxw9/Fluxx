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
