<a id="rec-graph-g1-1"></a>

# GRAPH G1.1: Graph page layout rework + playhead investigation

Branch `graph-g1`, on top of the G1 commit `ced9499`. Not merged (the user merges `graph-g1` once after G1.1).

Status:
- Implemented in source.
- JVM: **524 tests, 0 failures** (`:app:testDebugUnitTest`).
- **A16 (SM-A165F), instrumented, targeted:**

  | Test | Result |
  |---|---|
  | `GraphPageLayoutTest` (5) | Pass |
  | `GraphPlayheadTest` (5) | Pass (`nextOntoAKeyframeThenDrag` fails without the fix) |
  | `GraphCurveRenderTest` | Pass |
  | `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest`, `TextAnimateLayoutTest` | Pass (13 with `GraphCurveRenderTest`) |
  | `adb logcat -b crash -d` | Empty |

  Warm-up and F2 suites not run.
- Phone acceptance: not done (§9).

Hard rules kept:
- No change to `PreviewController` (read only), the gesture protocol, the batch/draw contract, preparation, the schema, the evaluator or the semantics of `SetKeyEasing`. UI, layout and wiring only.
- Other pages are unchanged. `InspectorUtilityRail` is not touched. In `ElementInspectorSheet` only the Graph branch of the header row and its content padding changed. The existing layout suites pass.
- Icon-first and monochrome; one new glyph (`GRAPH_TOOLS`) in the family, `InspectorIconFamilyTest` passes. No −/+ steppers, no Done/✓. Sheet heights unchanged (268dp / 328dp). Popovers non-modal.

## 1. Sheet chrome and header

**Cause (as the brief found).** `ElementInspectorSheet` skipped the header row for `GRAPH` and set its content top padding to 0, and the Graph page drew its own 36dp header beside the rail. So the Graph rail started at the top of the sheet.

**Fix.**
- The sheet now draws `GraphPageHeader` in the same full-width header row as every section page, at `InspectorChromeBudget.header(fontScale)`.
- The content keeps the normal `CONTENT_TOP` gap; only Edit Text keeps its own 0.

Header contents:
- Left: a back arrow, the same handler as the Graph icon (`graphHost.close`).
- Centre: Value | Speed | Ease. Value and Speed are visible, disabled and inert; Ease is selected.
- Right: the segment label, two lines, right-aligned and ellipsised ("Key 1 to 2 · 1.00s" over "start → end").

On the A16, at 360 and 384dp × 268 and 328dp, the Graph header's top and height equal the Transform header's, and the Graph rail's top equals the Transform rail's (`layoutFitsBothHeightsAndTheRailSitsWhereTransformsDoes`). The sheet swipe-down still closes from the header; the `InspectorSwipe` exhaustive rule and its tests are unchanged.

## 2. Left rail (Graph page only)

A Graph-only composable (`GraphLeftRail`), so `InspectorUtilityRail` and `RailBudget` keep their behaviour for every other page. Top to bottom:

1. **Keyframe diamond** for the edited property at the playhead.
2. **Graph icon**, selected; returns to the previous page.
3. **Invert**, enabled by the existing `EaseMath.invert` rule; one action.
4. **Tools** (new `GRAPH_TOOLS` glyph). It opens a non-modal `AnchoredPopover` with the G1 toggle fix: decide the toggle first, request dismissal only when opening. The popover holds:
   - Snap (toggle)
   - Mirror handles (toggle)
   - Apply to all keys (momentary, one undo; the popover stays open)

   Snap and Mirror show a selected state in the popover, and the Tools icon stays highlighted while either is on. Both are disabled for non-bezier types.
5. **⋮** (last): Copy cubic-bezier (bezier types only), Paste cubic-bezier, Reset curve to Easy Ease. "Apply this curve to all keys" left the ⋮ (it is in Tools).

**Fit:** `GraphLayout.leftTile(content)` gives five tiles with 2dp gaps, 40dp when they fit and never below 36dp. At 268dp × font scale 1 the content is 208dp, exactly five 40dp tiles plus four gaps. JVM tested at 360/384/411dp × 268/328 × 1/1.3.

## 3. Centre: touchpad + values bar as one item

- One rounded container (SurfaceHigh, the 0.5dp border, 8dp corners), the touchpad over a hairline over the values bar, one shared width.
- **Touchpad:** the full centre width and the height left by the bar (40dp × font scale), capped at 2:1; extra width becomes margin. A16 measured: ≥ 150dp high at 268, ≥ 210dp at 328, ≥ 200dp wide at 360dp. JVM: ≥ 120dp high at font scale 1.3.
- **Non-square plot:** `EasePlot` already maps x and y separately. The drawing, the 24dp hit radius, the relative drag (plotWidth and plotHeight separately) and the 0.1 grid work for any aspect.
- **Y range:** the default tightens from [−0.25, 1.25] to [−0.15, 1.15]. It still grows to fit overshoot, handles and Bounce/Elastic extremes, and still freezes-then-grows during a drag.
- **Loupe removed:** `EaseLoupe`, its state and the 3× crop are gone. Kept: direct drag (48dp target), relative drag (tap a handle, then drag on empty canvas at 0.35×), snap, mirror, the dashed ghost, the playhead dot and line, and the cached curve layer.

**Values bar.** One bar divided by hairlines.

| Curve | Tiles |
|---|---|
| Bezier types (Linear, Easy Ease, Ease In / Out, Custom) | x1, y1, x2, y2 (2 decimals; the named types show their bezier values) |
| Bounce | Bounces (integer), Height (2 decimals) |
| Elastic | Oscillations (1 decimal), Damping (2 decimals) |
| Hold | One disabled "Hold" tile |

- **What I matched from the Animate tiles** (`TextAnimatePage` → `InspectorTile` with an inset fill): the SurfaceHigh fill (the container's), the 8dp corners (the container's), the 8sp TextSecondary corner caption, and the Surface fill while active. The value is centred in `labelMedium` monospace TextPrimary.
- **Typing:** a tile opens the existing `AnchoredPopover("value-entry")` + `InspectorValueField`. It commits on IME Done or focus loss and discards invalid input; one undo per entry. Typing a bezier value converts the curve to Custom. Clamps: x1, x2 to [0, 1], y1, y2 to [−2, 3], Bounces 1–8 (rounded), Height 0.1–0.9, Oscillations 1–10, Damping 0.1–1.
- **Live values:** the bar reads the draft curve, so it updates while a handle is dragged (A16 asserted mid-drag).
- **Deleted:** the numeric popover (`EaseNumericRows`, the numeric tile, the slider rows). `EaseLimits.rows` is the single source of ranges and labels for the bar and the pad.

**Bounce / Elastic on the touchpad (`EasePad`).** The canvas is an XY pad with one handle (a dot with a faint crosshair):

| Type | x maps to | y maps to |
|---|---|---|
| Bounce | count 1–8, snapping to whole bounces | height 0.1–0.9 |
| Elastic | oscillations 1–10 | damping 0.1–1 |

Pad coordinates are 0–1 across the plot rectangle, 0 at the bottom. Direct drag on the dot, or a relative drag from empty canvas (the handle is always selected). Same gesture contract, one undo per drag, `SetKeyEasing` only. The JVM tests cover the mapping both ways, clamping and the integer snap.

## 4. Right rail

- One vertical `LazyColumn`, 40dp wide, the full content height. It scrolls only vertically and does not auto-scroll.
- Order: Hold, Linear, Easy Ease, Ease In, Ease Out, Bounce, Elastic, a hairline, then the presets. No Custom tile.
- Taps apply live, one undo. Highlight: type tiles by type, the bezier tiles and presets by curve match (ε 0.005). A Custom curve that matches nothing highlights nothing.
- The CSS `ease-in` / `ease-out` presets duplicated the Ease In / Ease Out tiles and are removed: **30 presets**. A JVM test checks that no preset equals another or a type tile.
- Bounce and Elastic start from G1's defaults.

## 5. Removed

- The 8 + 7 tile grid
- The numeric tile and popover
- The bottom preset strip
- The loupe
- The old `GraphLayout` grid arithmetic and its test
- The `EASE_NUMERIC` and `EASE_COPY` glyphs (no surface uses them now)

## 6. The playhead jump: root cause and fix

**Found and fixed** (after the first G1.1 commit, with a debug trace on the user's A16).

**How it was found.**
- My harness drove the real `EditorScreen` with a real `PreviewController` but never reproduced it, because it always moved the playhead *before* opening the Graph page.
- A temporary debug-only trace in `EditorViewModel` logged every playhead change with its call stack while the user reproduced it on their own project:
  - Next moved the playhead 0.628s → **1.000s**, onto a keyframe;
  - the next graph drag moved it back to **0.628s**, through `PreviewController.position` → `reportPosition`.
- So the preview controller had been told to show an old time.

**Cause.** `GraphPage` passed its drag handlers to the canvas as callable references to local functions (`::begin`, `::preview`, `::commit`, `::cancelDrag`). Compose memoizes those, so the canvas kept the handlers from the composition in which the page was opened. Those handlers carried that composition's callbacks, including `EditorScreen`'s `onTransformCommit`, which captured `playheadUs` at that time, plus the segment's key time and layer id. On release, the stale commit called `previewController.update(project, <playhead when the page opened>)`. The controller rendered that time and reported it back, and `reportPosition` moved the playhead there.

It only showed when the playhead was moved *after* opening the Graph page: with Prev/Next onto a keyframe or marker, as the user does to pick a segment. That is why "on a key or marker" looked like the trigger. The trace also caught the same echo on smaller moves.

**Fix (call sites only; `PreviewController` untouched).**
- `GraphPage`: every input the handlers use (`onAction`, `onBegin`, `onPreview`, `onCommit`, `onCancel`, the layer id, the target, the segment's key time) goes through an always-current `rememberUpdatedState` holder. A memoized handler can no longer act on an old composition, which also stops a drag after Prev/Next from editing the previous segment.
- `EditorScreen`: `onTransformCommit`, `onTransformCancel` and `onGesturePreview` read `editor.state.playheadUs` / `editor.displayedState.playheadUs` when they run, instead of a value captured at composition. For the Transform and Opacity pages this sends the same value as before.

**Tests.**
- A16 `GraphPlayheadTest.nextOntoAKeyframeThenDrag`: real `EditorScreen` + `PreviewController`; open the Graph page at 0.2s, Next onto the key at 1.0s, drag a handle. It **fails without the fix** (1,000,000 → 200,000µs, checked by stashing the fix) and passes with it.
- `GraphPlayheadTest` also covers: the playhead on a keyframe, on a composition and a layer marker, the hold-then-release drag, the left handle to the left edge, and the Animate Offset target.
- `GraphPageLayoutTest` asserts the playhead after a drag, a preset tap and a typed value.
- JVM `GraphG11Test.graphEditingNeverMovesThePlayhead` covers the `EditorViewModel` sequence. The cause is Compose lambda memoization, which a JVM test cannot reproduce, so this one passes with or without the fix.
- The debug trace is removed.

**User-confirmed on the A16 (2026-11-24):** with the fix the playhead no longer jumps; a drag that starts during playback pauses playback (the intended behaviour) and does not jump. The harness could not hold playback across steps, so that case is confirmed by hand only.

## 7. Files

New: `GraphG11Test` (JVM), `GraphPlayheadTest` (androidTest), this file. The playhead fix also changes `ui/editor/EditorScreen.kt` (three callbacks).

Changed:
- `ui/inspector/GraphPage.kt`: rewritten (geometry, header, left rail, centre item, values bar, XY pad, right rail; the loupe, grid, numeric popover and strip removed)
- `ui/inspector/ElementInspectorSheet.kt`: the Graph header row and padding
- `ui/inspector/InspectorIcons.kt`: `GRAPH_TOOLS` added; `EASE_NUMERIC`, `EASE_COPY` removed
- `editor/EaseTools.kt`: two duplicate presets removed
- Tests: `GraphG1UiStateTest` (the old geometry test removed, the icon list and plot range updated) and `GraphPageLayoutTest` (rewritten)
- Docs: `GRAPH_G1.md` (the stale "not committed" line; a pointer to this pass), `ELEMENT_INSPECTOR.md`, `AE_PARITY_VISION.md`, `FLUXX_ROADMAP.md`, `PHASE_2_STATUS_AND_BACKEND.md`, `README.md`, `docs/README.md`

**JVM count:** 518 (G1) − 1 (the G1 tile-grid geometry test) + 7 (`GraphG11Test`) = 524.

## 8. Not verified

- Phone acceptance.
- Font scale 1.3 on the device (JVM geometry only).
- TalkBack.
- Clipboard on the device.
- How the right rail's scrolling feels by hand.

## 9. A16 manual test list

1. Rotation with three keys → Transform → Rotation → Graph. The header row matches Transform's (back arrow, Value | Speed | Ease, "Key n to n+1 · …s" right-aligned); the left rail starts where Transform's does.
2. Left rail: diamond, Graph (selected), Invert, Tools, ⋮. Tools: Snap and Mirror toggle (and stay highlighted on the Tools icon once closed); Apply to all keys keeps the popover open. ⋮: Copy, Paste, Reset.
3. Drag P1 and P2 directly and relatively (tap a handle, drag on empty canvas). The bar's x1…y2 follow live; one Undo per drag. Drag the left handle to the far left: the timecode stays.
4. Tap a bar tile and type a value; Done or tapping away commits; invalid text does nothing; a typed Ease In value turns Custom.
5. Right rail: scroll the types and presets; the matching one highlights; Bounce and Elastic turn the canvas into a pad. Drag the dot: more bounces to the right, higher up; the bar follows.
6. Hold shows the step and a disabled "Hold" bar.
7. Both preview sizes: nothing clipped; the centre item is one rounded box.
8. Back arrow, the Graph icon and system Back all return to the page you came from; swipe down from the header closes the sheet.

## 10. Deviations

- The playhead fix is at the call sites (`GraphPage`, `EditorScreen` wiring); `PreviewController` is unchanged (§6).
- `EASE_NUMERIC` and `EASE_COPY` were removed from the icon family because nothing draws them now (Copy is a ⋮ text item).

## 11. Questions for G2

- Should the dot move, or stay put, while a handle is dragged? Option: hide the dot during a drag and keep only the vertical playhead line, so the curve visibly moves under a fixed playhead.
- Right rail at 268dp: 7 type tiles do not all fit without scrolling. Should Hold/Linear move into the ⋮, or is scrolling fine?
- User presets (G4): a "save current curve" tile at the top of the right rail, or in the ⋮?
