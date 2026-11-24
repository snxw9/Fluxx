<a id="rec-graph-g2"></a>

# GRAPH G2: Value graph + G2L carry-over fixes

Branch `graph-g2`, created from `graph-g2l` at `9c227aa` (`graph-g2l` is not merged into `master`). Not merged.

Status:
- Implemented in source.
- JVM: **542 tests, 0 failures** (`:app:testDebugUnitTest`), including `GraphG2Test` (11, new) and the extended `InspectorLimitsTest` (A4).
- **A16 (SM-A165F), instrumented, targeted:**

  | Test | Result |
  |---|---|
  | `GraphValueTest` (7, new) | Pass |
  | `GraphLibraryTest` (8: 5 updated for the grid, 3 new) | Pass |
  | `GraphPageLayoutTest` (5) | Pass |
  | `GraphPlayheadTest` (6, 1 new: the Value view on the real editor + preview controller) | Pass |
  | `GraphCurveRenderTest`, `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest`, `TextAnimateLayoutTest` | Pass (13) |
  | `adb logcat -b crash -d` | Empty |

  The four Graph suites (25 tests) passed on two consecutive runs. Warm-up and F2 suites were not run.
- Phone acceptance: not done (§9).

Hard rules kept:
- No change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract, the schema (still v13), preparation, the evaluator or `SetKeyEasing` semantics.
- **The transient-allowed set was not changed:** every action key dragging uses was already in it (§B4).
- `InspectorUtilityRail` and `RailBudget` untouched; the Graph left rail keeps its five items. Icons from the family only. No −/+ steppers, no Done/✓. Popovers non-modal (the typed-entry popover keeps its existing behaviour). Sheet heights 268 / 328dp; the sheet, touchpad and values bar keep identical bounds across Ease / Value, library open / closed and the popovers (`valueTabOpensWithKeysAndKeepsEveryBound`).
- Graph and library use never changes `playheadUs`, the clip position on the timeline or the selection, except a double tap on a key, which seeks (§B5).

Two shared-component changes outside the Graph files, both small: `TypedEntry` (the A4 fix) and an `autoFocus` parameter on `InspectorValueField` (default `true`, so other pages are unchanged).

---

# Part A: G2L carry-over

## A1. Library grid

- The **Types** section is gone (types live only in the strip, which is not selectable while the library is open). Sections: Pinned, Presets, My curves.
- A 2-column `LazyVerticalGrid`. Cells are about 39.5 × 41.2dp: a 24dp-high thumbnail over a 7sp name of up to two lines (`GraphLayout.GRID_*`). Captions span both columns.
- **Rows fully visible (A16, 360dp wide, font scale 1):** 4 rows at 268dp (8 curves; the 4th row ends at 205.2 of 207.6dp), 5 rows at 328dp. JVM geometry: 4.06 and 5.5.
- Drag in the grid: long-press then move; the drop slot comes from the pointer's x **and** y over the pinned cells (`GridDrop`). The rules are unchanged: insert; when full, replace the cell under the pointer and keep the others' order; drag out to unpin; auto-scroll near the edges; one store write per drop.
- Grid fixes found while testing:
  - a cell that scrolls out of view now drops its bounds, so stale bounds can't misplace a drop;
  - the drop handler reads the current library state through `rememberUpdatedState` (it was a memoized reference handed to a long-lived pointer coroutine, the G1.1 pattern);
  - a full Pinned section that runs past the bottom of the viewport still counts as Pinned.
- The grid's scroll state lives in the page session (`GraphSession.libraryGrid`). It survives closing and reopening the library and resets when the Graph page closes.

## A2. Library icon fade

`GraphLibraryButton` now draws one radial scrim, the same composable with the same parameters open and closed. It is centred on the 40dp icon and fades to transparent 6dp beyond it (`GraphLayout.SCRIM_EXTENT`; the scrim box is 46 × 46dp). There is no solid block any more. On the A16 at 268 and 328dp, the scrim's bounds equal the icon's bounds plus 6dp on the left and top in both states, and every library cell 12dp or more from the icon lies outside the scrim (`gridSectionsRowsAndTheIconFade`). Pixels were not sampled.

## A3. xy popover

**Correction to the G2L report:** the G2L xy popover was not read-only. It held values-bar tiles, each opening a second, nested `value-entry` popover, and those were hit by the A4 bug.

The xy popover now holds **typed fields directly** (`InspectorValueField`): x1 y1 x2 y2 for bezier curves, Bounces/Height or Oscillations/Damping for Bounce/Elastic.
- Same clamps as the values bar (`EaseLimits.typed`). Commit on IME Done or focus loss. Invalid input is discarded; empty keeps the old value.
- Each commit re-keys its field so it can be edited again. The curve is read back from the store at commit time, never captured.
- Only the stored curve changes, never the graph. Thumbnails update everywhere through the store. Writes are atomic.
- The first field takes focus (the new `autoFocus` parameter). Pencil and trash are unchanged.
- Tested in `xyPopoverEditsAndPersistsTheStoredCurve`.

## A4. The flaky tap (cause and fix)

**Cause (a product bug, not test timing).** `TypedEntry.keyboard` treated "the keyboard was seen, then hidden" as Back, and cancelled the entry. A typed field opened right after another entry closed saw the **previous** entry's keyboard still sliding down: "seen", then 0, so it cancelled itself straight away. The popover opened and closed at once, and anything typed was lost.

This explains the user's report about editing saved curves (xy popover), the G2L note about the values-bar tile after a typed entry, and the library tile taps right after a popover closed. Shown on the A16 with temporary logging: the second entry's cancel came from `TypedEntry.keyboard` via the IME-inset effect, not from any tap.

**Fix.** Back-cancel is armed only by a rising IME edge, or by the IME staying visible for 150ms (`TypedEntry.ime` / `settled`). A falling IME never arms it, and Back still cancels once the field's own keyboard is up.

Regression tests:
- `InspectorLimitsTest.typedEntryCommitsOrCancelsExactlyOnce` (JVM): the falling-IME sequence that cancelled before the fix now commits.
- `GraphLibraryTest.aSecondTypedEntryRightAfterTheFirstStaysOpenAndCommits` (A16): values bar, then the same tile again, then the xy popover. Before the fix, the A16 diagnostic run of this sequence cancelled the reopened entry; the test was written after the fix.

Because `InspectorValueField` is shared, the fix applies to every typed entry in the inspector.

---

# Part B: the Value graph

## B1. What it is

The Value tab is live. It shows the property's value over time across all of its keys, through the property's own evaluator. Keys are draggable points, and the selected segment's easing handles can be edited on the curve. Ease stays the per-segment editor; Speed stays disabled (G3).

The layout is unchanged: same centre box, strip, pinned rail, library and left rail. Only the canvas and the values bar change. The touchpad's bounds are identical in Ease and Value.

- Eligibility (`GraphValues.available`): every property that enables the Graph icon, **except colour** (text Fill/Stroke colour and animator colour fields). For colour, the Value tab is disabled and Ease works.
- Text-layer tracks and animator fields (for example, Range Selector Offset) work through `GraphTarget`. Tested in JVM; on the A16 only the transform Rotation and Position paths are driven.
- The page still opens in the **Ease** view; Value is one tap in the header.

## B2. Canvas (`ValueCanvas`, `GraphValueCanvas.kt`)

- X is local time; Y is the value in display units (Opacity and Scale × 100 %, Rotation °, Position px).
- The viewport lives in the page session. It resets when the page or property changes.
- The curve is sampled with the property's evaluator (`GraphValues.sampler`, the same `KeyframeEvaluator` path the renderer uses) at one point per 2px.
  - It is built in `drawWithCache` and rebuilt only when the scene, viewport or size changes.
  - Outside the keys the evaluator holds the end values, so the curve extends flat.
- Keys are drawn as points; the selected key gets a ring, and the edited segment's two keys are highlighted. The hit radius is 24dp.
- Grid: 1-2-5 time ticks (frames under one second, else seconds) and value ticks, at most 4 labels per axis at 8sp. Labels are hidden when the touchpad is under 120dp in either direction.
- One playhead line, plus the dot on the edited channel's curve when "Show playhead dot" is on.
- Fit: the keys' time range and the sampled extent of every channel, overshoot included, with a margin. A drag freezes the viewport and only grows it to keep the dragged point in view; it never refits mid-drag.

## B3. Selected-segment handles

- For bezier types: P1 = (t0 + x1·dt, v0 + y1·dv) and P2 = (t0 + x2·dt, v0 + y2·dv), each joined to its key by a line (`ValueHandles`).
- A drag uses `EaseMath.moveHandle`, with the same clamps, Snap and Mirror as Ease. It is one `SetKeyEasing` through begin/preview/commit/cancel, and a named type becomes Custom. Direct drag only.
- **Flat segments** (|dv| < 1e-4 display units): the handles sit on the value line and only move in time; y is kept.
- Bounce/Elastic draw no handles (they use the Ease XY pad); Hold draws its step.
- **Hit testing:** the nearest target wins, and a key wins a tie. With Easy Ease the P2 handle sits about 16dp from its key, and when handles were tested first it stole taps meant for the key (found by the A16 tests).

## B4. Key dragging: existing actions reused

A key drag (or a typed Time/Value) is one `EditorAction.Batch`, which gives one undo:

| Target | Time | Value |
|---|---|---|
| Transform Rotation / Opacity, text tracks | `MoveKeyframe` | `SetKeyframe` |
| Transform Position / Scale | `MoveKeyframe` | `SetKeyframe2D` |
| Animator property / selector field | `EditTextAnimator(Key MOVE)` | `EditTextAnimator(Key SET)` |

- All of these were already in the view model's transient-allowed set, so nothing was added.
- Values are written with the key's own `easing`, so `rekey1D/2D` keep its curve (tested for transform, text size and selector offset).
- **Time** snaps to the composition frame grid (through the keyframe anchor) and stays at least one frame from each neighbour (`GraphValues.clampTime`). It stays inside the clip, the range the timeline's key drag uses; a key already outside the clip keeps its own time as a bound.
- **Value** is clamped to the inspector's hard limits for the property (`GraphValues.axes`), plus the model clamp for animator fields. With Snap on it snaps to the visible grid step.
- Live preview goes through `onGesturePreview`. The drag reads every input through `rememberUpdatedState` holders (the G1.1 rule).
- The drag never touches the playhead, the clip position or the selection (`GraphPlayheadTest.valueGraphEditsKeepThePlayheadAndADoubleTapSeeksOnce`, on the real editor and preview controller).
- On 2-D properties, only the chosen channel changes.

## B5. Selection and seeking

- Tapping a key selects it. The selected segment is that key's departing segment (for the last key, the previous one).
- Double-tapping a key dispatches `Seek(anchor + key time, snap = true)` through the sheet's `onAction`: the same dispatch + `previewController.update` path Prev/Next use, and the one graph gesture that moves the playhead. The A16 test records exactly one `Seek`, and no echo afterwards on the real controller.
- A selection is stored with the playhead at the moment it was made (`KeySelection`) and counts only while the playhead has not moved. Any playhead move (Prev/Next, seek, the double-tap, scrubbing, playback) clears it, and the segment goes back to `GraphModel.segmentIndex`. Tapping empty canvas clears it too.
- Ease and Value share the selected segment, and so do the header label and the rail tools.

## B6. Values bar (Value view)

- **Time** (composition seconds, 2 decimals) and **Value** (display units) for the selected key, each typed in the existing `value-entry` popover.
  - Time snaps to a frame and is clamped to the neighbours on commit.
  - Value is clamped to the property's limits.
  - Invalid input is discarded; each commit is one undo.
- **With no key selected:** the tiles show the segment's start key and are inert (disabled). I chose this over "–" so the bar still reads as values.
- **2-D properties:** both channels are drawn, the edited one solid and the other dimmed. A small **X | Y** tile picks the channel the Value tile and key drags edit. Easing stays shared by both channels.

## B7. Navigation

- One-finger drag on empty canvas pans.
- Pinch zooms, axis-aware by finger separation: mostly horizontal zooms time, mostly vertical zooms value, otherwise both. The data point under the fingers stays fixed.
- Limits:
  - time: at least 2 frames and at most 20× the fit;
  - value: at least 1/1000 of the fit and at most 20× it;
  - pan: the window's centre stays inside the fitted window.
- A double tap on empty canvas fits, as does entering the view. A double tap on a key seeks (B5).
- In Ease, an empty-canvas drag keeps its relative-handle meaning; in Value it pans.

## B8. Header, rail and library

- Value is enabled (except for colour); Speed stays disabled.
- The header label follows the selected segment ("Key n to n+1 · duration"), with the curve name on the second line.
- Invert, Apply to all, Copy/Paste cubic-bezier, Reset, Snap and Mirror act on the selected segment. The ⋮ menu keeps "Show playhead dot".
- Strip, pinned rail and library taps, and library save, use the selected segment's curve (`libraryAndRailApplyToTheSelectedSegment`).

## Measured (A16)

| | 268dp | 328dp |
|---|---|---|
| Touchpad (360dp wide), Ease = Value | 206.9 × 166.8 | 206.9 × 226.8 |
| Library grid rows fully visible | 4 (8 curves) | 5 (10 curves) |
| Library cell | ~39.5 × 41.2 | same |
| Icon scrim | 46 × 46 (icon 40 + 6) | same |

## 9. Phone acceptance (pending)

1. Value tab on a Rotation with three keys: the curve, keys and playhead line are visible; labels are readable at both heights.
2. Tap a key: it highlights and the header shows its segment. Double-tap it: the playhead jumps there once. Tap empty canvas to clear.
3. Drag a key in time and value: frame steps, no crossing a neighbour. One Undo restores it, and the playhead never moves.
4. Select key 1 and drag its handles: the curve changes, Ease shows the same curve, one Undo.
5. Pinch horizontally, vertically and diagonally; pan; double-tap empty to fit.
6. Position: X | Y toggle; drag a key on Y only.
7. A text animator's Range Selector Offset: the Value tab works. A text Fill colour: the Value tab is disabled.
8. Library: the grid, no Types, scroll and reopen (the position is kept), drag to Pinned in the grid, the soft fade around the icon.
9. xy popover on a saved curve: type all four values one after another without waiting. Each commits, and the thumbnail updates.
10. Type a value in the bar, press Done, and immediately tap another tile: the entry opens and commits (A4).

## 10. Deviations, not verified

- **Not verified on the device:**
  - text-layer tracks and animator fields in the Value view (JVM only);
  - font scale 1.3 (geometry only);
  - real-finger pinch feel;
  - TalkBack (out of scope).
- The timeline's scroll offset isn't read directly. `GraphPlayheadTest` asserts the clip's on-screen x is unchanged, which is how G1.1 checked it.
- **Default view on opening stays Ease,** not Value (less disruptive for existing users and tests).
- **Hit testing:** the nearest target wins (B3), a change from "handles first" in the Ease view's style.
- **Shared code:** the `TypedEntry` fix and `InspectorValueField.autoFocus` touch every inspector page's typed entries (the existing layout suites pass).
- **Rows visible** are counted from unclipped row bottoms; Compose's `boundsInRoot` clips to the viewport, and a first count that used it over-reported (5 instead of 4).

## 11. Questions for G3

- Speed graph: show speed in property units per second (After Effects) or normalised, and should 2-D properties show the combined spatial speed or per channel?
- "Smooth through key": should it be a toggle on the selected key (shared tangents, like After Effects' Continuous Bezier), and is a schema bump for per-key in/out influence acceptable?
- Should the Value view become the default view when a property has three or more keys?
- Separate X/Y easing (out of scope here): G3 or later?
