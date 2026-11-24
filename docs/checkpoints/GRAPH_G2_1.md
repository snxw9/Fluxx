<a id="rec-graph-g2-1"></a>

# GRAPH G2.1: acceptance fixes (hex colour, Value graph, units, time audit, library drag, snapping)

Branch `graph-g2-1`, created from `graph-g2` at `99b8a49`. `graph-g2` is not merged into `master`, so this branch also carries G2 and G2L. Not merged.

The items come from the user's A16 acceptance of G2 ([GRAPH_G2.md](GRAPH_G2.md)).

**Status:** implemented in source.
- **JVM:** one full run of 564 tests had 1 failure (`InlineValueFieldTest`, a real regression from §2, fixed). That suite and `GraphG21Test` were then re-run green. The suite now has 565 tests. The full suite was not run a second time, per §1.
- **A16 (SM-A165F, 1080×2340, 450 dpi = 2.8125):** every new and changed instrumented test passes. Two tests are flaky in long suite runs (§10).
- **Crash buffer:** `adb logcat -b crash -d` is empty.
- **Phone acceptance:** pending (§12).

## Hard rules kept

- **Frozen contracts:** no change to `PreviewController`, the gesture protocol, `EditorAction` (no new action), the batch/draw contract, the schema (still v13) or preparation, the evaluator, or `SetKeyEasing`.
- **Persisted settings:** the curve library file gained an optional `graphSnap` key. It stays at version 1, and a G2 file without the key reads as on.
- **Sheet height:** constant at 268 / 328dp. Every Graph test asserts the sheet's bounds.
- **Playhead and selection:** Graph use never changes the playhead, timeline scroll or selection, except a key double tap (Seek). Library and rail drags never touch the document. `GraphPlayheadTest` checks this on the real editor.
- **Icons:** monochrome, one family (`InspectorIconFamilyTest` passes). No steppers and no Done buttons.

## 1. Test-running economy

| Run | What | Wall time | Result |
|---|---|---|---|
| JVM, targeted (iteration) | G2.1, hex, G2, colour suites | 124 s, then 48 s | 1 failure, then green |
| JVM, full suite (once) | all 564 tests + androidTest compile | 166 s | 1 failure (`InlineValueFieldTest`) |
| JVM, failing suite re-run | `InlineValueFieldTest`, `HexEntryTest`, `ColourPickerFamilyTest` | 111 s | green |
| A16 run 1 | `ColourHexEntryTest`, `PaletteBrowserTest`, `TransformDetentsTest` | 29 s | 4/5; the scale test was re-run alone (11 s) and passed |
| A16 sweep (once) | `Graph*` ×5, `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest`, `TextAnimateLayoutTest` (46 tests) | 300 s | 42/46 (see below) |
| A16 failing tests + new audit | 4 failed + `TimeAuditTest` | 55 s, 31 s, 15 s, 17 s | fixed one by one (below) |
| A16 final drag suites | `GraphLibraryTest`, `GraphPlayheadTest`, `TransformDetentsTest` (22 tests) | 181 s | 20/22: the 2 flaky tests, both green when re-run |

- **Why the last run:** `GraphLibrary.kt` changed after the sweep (the `moved` state, settled positions, scroll anchoring). That code runs in every drag, so the drag suites ran once more because of the code change, not for confidence.
- **Gradle:** no `clean`; incremental builds and the build cache stayed on.
- **Installs:** every install was `install -r`. Nothing was uninstalled and no data was cleared.

Sweep failures and fixes:
1. **`GraphValueTest`, typed edits (test sequence).** After an undo, the selected key's time no longer existed. The typed value then correctly went to the playhead, so the test now re-selects the key.
2. **Library and rail lift tests (a real bug).** `CurveDragState.moved` was a plain var, so crossing the slop never recomposed and the lifted tile never appeared. It's now snapshot state.
3. **xy y2 check (test bug).** It measured against the test's 268dp host view instead of the window.
4. **Library reflow jitter (a real bug, found by the rerun).** The slot flipped 2↔3 every frame. It was computed from animated bounds: a cell sliding aside is still under the finger, so the slot flipped back. Slots now come from the lazy layout's settled item offsets (`layoutInfo.visibleItemsInfo`).
5. **Rail slot running on (a real bug).** `LazyColumn` anchors its scroll to the first visible item's key. Moving that item in the draft scrolled the rail under a still finger, and the slot ran to 9. The scroll is now held by index (`requestScrollToItem`) when the slot changes, in the grid too.

## 2. Hex colour entry

- **One parser:** `FluxxColor.parseHex` / `FluxxColor.parseHexEntry` (packed ARGB, keeps the current alpha byte exactly).
- **Callers:** `ColourValues.hex` (the shared picker: Edit Text Fill/Stroke, markers, swatch editor, Edit Solid, Add content → Solid, animator colours) and `TextColourEdit.hex`. Palette files keep their own strict 8-digit check.
- **Accepted:** any case, an optional `#` and surrounding whitespace.
  - `RRGGBB` keeps the alpha.
  - `AARRGGBB` sets colour and alpha.
  - `RGB` doubles each digit.
- **Rejected:** everything else (`0000`, 5 or 7 digits, `0x…`, `##…`, empty). It's discarded as before.

The user's symptom: `0000` is 4 digits, which is invalid by the brief's rule, so it reverts. `000000` now works on a colour at any alpha.

- **Deviation:** a 3-digit value applies when the field is left (Done, blur or Back), not live. Live 3-digit apply made typing `00FF00` flash `#0000FF` at the third keystroke; the full suite caught it. 6- and 8-digit values still apply live. Either way, one undo.
- **Tests:** `HexEntryTest` (table, all three call paths), `InlineValueFieldTest.threeDigitHexAppliesOnLeaveAsOneUndo`, and A16 `ColourHexEntryTest`:
  - `000000` on 50% red gives `0x80000000`;
  - `#fff` gives `0x80FFFFFF`;
  - `40Ff0000` works;
  - `0000` reverts with no commit.

## 3. Value graph: keys are not draggable

- **Behaviour:** a key is tap-to-select and double-tap-to-seek. A one-finger drag that starts on a key pans. The selected segment's handles still drag.
- **Removed:** the key-drag callbacks (`onKey*`), the hit branch and the page's `keyBegin/Preview/Commit/Cancel`.
- **Kept:** `GraphValues.keyAction` / `clampTime`, which the tiles use.
- **Converted tests:**
  - `GraphValueTest.aKeyDragPansHandleDragAndTypedValuesAreOneUndoEach`: a drag on a key leaves the keys and dispatches nothing. Typed time works as a timecode or as frames, with frame snap and neighbour clamp. Typed value is one undo each. The playhead is unchanged.
  - `GraphG21Test.typedKeyEditsSnapClampAndAreOneUndo`.

## 4. Nothing draws through the values bar

- **Confirmed causes:** the Value canvas wasn't clipped, and the values-bar tiles were transparent when idle.
- **Fixes:**
  - The canvas is `clipToBounds()`, which covers its curve, playhead and marker layers.
  - The Value and Ease bars and their idle tiles fill with `SurfaceHigh`; the active tile keeps `Surface`.
- **A16 test:** `GraphValueTest.nothingDrawsThroughTheValuesBar` zooms the value axis four times. The canvas and bar bounds are unchanged, and a pixel sample of the bar's bottom two rows (more than 100 px) is fully opaque. Every pixel is the bar fill, a hairline or the selected-channel fill, never curve ink.

## 5. Units audit (every property with a Value graph)

**Cause, confirmed:**
- `GraphValues.axes` showed Position as the stored value with factor 1, labelled "px". Stored Position is centred and normalised to half the reference size.
- Every tile used `%.2f`.
- The header (`GraphModel.values`) formatted raw stored numbers.

**Fix:**
- `PropertyDisplay` / `PropertyUnits` (new, pure) holds each stored → shown conversion (shown = offset + stored × factor) and the page's own formatter. The Transform and Opacity pages now call it in place of their inline maths. The arithmetic is identical, which `sharedHelperMatchesThePagesInlineArithmetic` checks bit for bit.
- `ValueAxis` wraps it, so these all use the page's units, origin and formatting:
  - the axis labels, ticks, grid and Snap grid;
  - typed entry (the page's `limit.typed`);
  - the viewport fit;
  - the live value and the header labels (`GraphValues.labels`).
- **Stored clamps:** the limit values are unchanged. Position's ±10× composition caps are now converted into stored units; G2 applied pixel caps to normalised values, so it was effectively unclamped.

| Property | Inspector page shows | Graph showed (G2) | Graph shows (G2.1) | Verdict |
|---|---|---|---|---|
| Position X / Y | composition px, top-left origin, `%.1f` (`639.0`, `915.5`) | stored ×1, `%.2f` + "px" (`0.18px`, `-0.05`) | `639.0px`, `915.5px` | **wrong → fixed** |
| Scale X / Y | ruler read-out (`150%`, `25%`, `1.5%`) | ×100, `%.2f` (`150.00`) | `150%` (the read-out) | format → fixed |
| Rotation | turns notation `1x45.0°` | `405.00` | `1x45.0°` | format → fixed |
| Opacity | ×100, `%.1f` (`50.0%`) | `50.00` | `50.0%` | decimals → fixed |
| Font size, Tracking, Leading, Baseline shift, Stroke width | `PropertyLimits.format` (`72`, `150`, `-12.5`, `2.5`) | `%.2f` (`72.00`) | `PropertyLimits.format` | decimals → fixed |
| Animator properties (Position X/Y, Rotation, …) | `PropertyLimits.format` with the field's limit | `%.2f` | the same | decimals → fixed |
| Selector fields (Offset, …) | `PropertyLimits.format` | `%.2f` | the same | decimals → fixed |
| Header start/end labels | — | raw stored, `n()` | the page's units | fixed |

- **Live value:** it reads exactly as the page does:
  - Transform / Opacity: `evaluatedTransform(playhead)` at the raw playhead.
  - Text: `sizeAt` / `strokeWidthAt` / `evaluate` at the frame-snapped playhead.
  - Animate: `TextAnimatorEdits.value` / `vector`.
- **Y direction:** the Transform page's Position Y grows **downward** on screen (top-left origin). The Graph's value axis grows **upward**, so a larger Y, which is lower on screen, plots higher. Stated here and not flipped.
- **Tests:** `unitsCrossCheckTable` (15 rows; the user's key gives `639.0` / `915.5`), `liveValueIsThePagesValueAtThePlayhead`, and A16 `positionHasTwoChannelsAndTheToggleEditsOne` (typed `333` px is stored as `(333 − 960) / 960`; the tile shows `333.0px`).
- **A16 gap:** the A16 suites don't open the user's project. Their Position fixture isn't the user's key; the JVM table covers that exact case.

## 6. Live values; the Time tile is inert

- **Value tile, no key selected:** shows the live value at the playhead, in the page's text, and follows scrubbing and playing. It's always enabled.
- **Typing with no key selected** sets the value at the playhead (`GraphValues.atPlayheadAction`):
  - Transform: `SetKeyframe(2D)` at `GraphModel.local`, keeping an existing key's easing, else Easy Ease, exactly as `EditorViewModel.editTransform` does.
  - Animator fields: the Animate page's `TextAnimatorEdits.set`.
  - Text-layer tracks: `SetKeyframe`. This is a deviation: the Edit Text page goes through its payload draft, which the Graph can't host. The resulting key is the same.
- **Time tile, no key selected:** inert, showing "–". With a key selected it shows and edits that key's time.
- **Tests:** `typedValueWithNoKeySetsTheValueAtThePlayheadWithOneUndo` (JVM), A16 `liveValueTileAndInertTimeTile`, and `GraphPlayheadTest.liveValueTypedValueAndLibraryDragsKeepThePlayhead`.

### 6a. Time display audit

**Formulas:**
- **(a) Transport and the timeline's playhead pill:** both call `TimeFormat.formatTimecode(startTimecodeUs + playheadUs)`, which is `CompositionTimecode.format`. That's MM:SS:FF at the nominal fps, rounded to the **nearest** frame.
- **(b) Ruler:** unlabelled ticks at `frameTimeUs(f)` (exact frame times, rounded down), drawn in the scrolled content after a half-viewport spacer.
- **(c) Playhead:** fixed at the viewport centre. The scroll is `round(playhead × pxPerUs)`.
- **(d) Graph (G2):** the Time tile showed `(anchor + local) / 1e6` in seconds to 2 decimals. The axis showed "Nf" frame counts or "%.1fs". Neither used the start timecode.

A16, `TimeAuditTest`, fixture composition, 120dp/s zoom (frame = 11.25 px):

| Frame (30 fps) | Playhead µs | (a) Pill / transport | (d) Graph G2.1 | G2 Time tile | G2 axis | Tick x − playhead x |
|---|---|---|---|---|---|---|
| 0 | 0 | 00:00:00 | 00:00:00 | 0.00 | 0f | −0.50 px |
| 1 | 33 333 | 00:00:01 | 00:00:01 | 0.03 | 1f | −0.25 px |
| 15 | 500 000 | 00:00:15 | 00:00:15 | 0.50 | 15f | −0.75 px |
| 29 | 966 666 | 00:00:29 | 00:00:29 | 0.97 | 29f | −0.25 px |
| 30 | 1 000 000 | 00:01:00 | 00:01:00 | 1.00 | 30f | −1.00 px |
| 45 | 1 500 000 | 00:01:15 | 00:01:15 | 1.50 | 45f | −0.25 px |
| 89 | 2 966 666 | 00:02:29 | 00:02:29 | 2.97 | 89f | −0.25 px |

- **29.97 fps with a 1-hour start timecode:** the playhead pill and the G2.1 Graph show the same values, `59:56:12` … `59:59:11`. The G2 Graph showed `0.00` … `2.97`. Tick offsets were −0.24 to −0.92 px.
- **Between frames:** ¾ of the way from frame 15 to 16 shows `00:00:16`, the nearest frame.

Discrepancy and cause:

| Display pair | Disagreement | Cause | Action |
|---|---|---|---|
| Graph (d) vs transport (a) | always: seconds vs MM:SS:FF, frame counts vs MM:SS:FF, no start timecode | the Graph had its own formatters | **Fixed** (contained formatter bug): `GraphTime.label` = `CompositionTimecode.format(start + t)`, used by the Time tile (display and typing) and the time-axis labels. Regression test `graphTimesUseTheTransportTimecode`: it fails without the fix, because the old text was `1.50`. |
| Playhead x vs ruler tick | ≤ 1 px at frames (the line is 1dp = 2.8 px) | spacer and pixel rounding | none; consistent |
| Transport vs playhead between frames | the timecode shows the nearest frame while the line sits up to ½ frame (5.6 px here) from that tick | a scrub stops anywhere, and the timecode rounds to the nearest frame | not changed. A floor display, or snapping scrubs to frames, is a timeline decision (`TIMELINE_LAYOUT.md`), not a contained bug. Question for G3. |
| 29.97 with a start timecode | the start shows as `59:56:12` | non-drop-frame timecode at the nominal 30 fps, with a 1-hour start that isn't on the frame grid (107,892.1 frames) | not changed (consistent everywhere). Typed timecodes round-trip within a fraction of a frame and are then frame-snapped. Question for G3 (drop-frame). |

- **Not verified:** the transport bar wasn't found on screen in this harness; only the playhead pill matched. The transport uses the same function with the same arguments (`PreviewSection.kt`), so it's verified by code only.
- **Time tile:** stays inert with no key selected, per the brief.

## 7. xy popover: compact, independent fields

**Cause (confirmed in code):**
- G2's xy popover auto-focused field 0 and re-keyed a field after every commit (`key(i, rounds[i])`).
- Committing field 0 recreated it, and its `LaunchedEffect` requested focus again and called `keyboard.show()`.
- Moving from field 0 to another field committed field 0 on focus loss, so the recreated field 0 stole focus back and reopened the keyboard. Every later commit re-keyed again, so the keyboard kept coming back until the fields were left alone.
- **Cut-off:** four `InspectorValueField`s in a 1 × 4 row, each at least 56dp plus unit and padding (about 300dp), inside a popover capped at 344dp and clamped by 8dp window margins. The fourth field fell outside.

**Fix (`GraphLibrary.kt`):**
- **Layout:** a 2 × 2 grid (bezier), or 1 × 2 (Bounce and Elastic). Each tile is 48 × 44dp with a corner label and a centred value; I made them 48dp wide instead of the brief's ≈40dp so `-1.50` fits.
- **Focus:** no auto-focus and no re-keying. A field opens the keyboard only when tapped.
- **Commit:** Done, a tap elsewhere in the popover, Back hiding the keyboard, or closing the popover commits **that field only** (`XyEdit.commit`: clamped; invalid discarded; empty keeps the value). The keyboard then closes.
- **Moving between fields:** moves focus, keeping the keyboard up.
- **Back detection:** `ImeArm` uses the A4 rule, so a falling keyboard from another field is never read as Back.
- **Pencil and trash:** unchanged.

**A16 (`xyFieldsAreIndependentAndStayAboveTheKeyboard`, at 268dp and 328dp):**
- No focus and no keyboard on open.
- Editing only x1 and tapping away changes only x1 and closes the keyboard.
- Moving x2 → y1 commits x2 with the keyboard still up (1008 px).
- Typing all four with Done each: after 600 ms, the keyboard is still hidden each time.
- y2 lies at 468–513 px against a keyboard top of 1332 px, inside the window (0–2340).

## 8. Library and rail drag: lift, follow, reflow

- **Model (pure, `GraphDrag.kt`):**
  - `PinDrafts.draft`: the display-only draft order, with the gap, `evicted` when full, and `unpinning`.
  - `PinDrafts.commit`: the one write, through the existing `pinAt` / `unpin` (JVM-tested to equal the draft).
  - `candidate` / `candidateIndexed`: the slot from the pointer among the laid-out cells.
  - `stable`: 8dp hysteresis.
- **Display:**
  - After the long press moves past the slop, the tile is drawn in an overlay at exactly the pointer: scale 1.08, 8dp shadow, alpha .92, or .5 when the drop would unpin.
  - Its cells become empty gaps.
  - The other cells slide aside with `animateItem` (180 ms).
  - When the set is full, the cell that would return to the library is dimmed (.35) with an outline, and its state reads "Returns to the library".
  - On drop, the tile animates into its cell (150 ms).
- **Haptics:** a light haptic on lift (existing) and on slot change (`TextHandleMove`).
- **Store:** no writes while dragging; one write per drop (`CurveLibraryStore.writes`, a new test counter).
- **Auto-scroll:**
  - A 56dp edge zone; speed ramps quadratically from 150 dp/s at the inner edge to **1000 dp/s** at the edge.
  - G2 was a fixed 12 px per 16 ms (750 px/s ≈ 267 dp/s on the A16) in a 32dp zone, so the new maximum is **3.75×**.
  - It keeps scrolling while the finger rests in the zone, and each frame refreshes the slot.
  - Measured: with the finger 10dp from the bottom, a lifted pinned curve reached "My curves" in **45 frames (720 ms)**.
- **Tests:**
  - JVM: insert, reorder, replace-when-full, unpin and section closing; drop slots with hysteresis and with cells scrolled out of view; the speed ramp.
  - A16:
    - `liftFollowReflowAndOneWritePerDrop`: the overlay follows within 1 px over three moves, cell 4 slides aside, there are no writes while dragging and one on drop, and the playhead is unchanged.
    - `autoScrollReachesAFarSectionAndUnpins`.
    - `railReorderLiftsFollowsAndWritesOnce`.
    - `longPressMenuOnUserCurvesOnlyAndDragToPin` and `fullSetReplaceKeepsTheOthersInOrder` (G2) still pass.

## 9. Snapping

- **Graph Snap:** on by default and persisted app-level in the curve library file (`graphSnap`, next to "Show playhead dot"). Mirror is unchanged (off, per page). Since keys no longer drag, Snap applies to the handles.
- **Magnetic detents:**
  - **What existed:** nothing in the code locked to a value. The "mechanism the Character Tracking slider uses" (TEXT F3.4, `PropertyLimit.detentStep` / `detent`) is a **haptic bucket**: a tick when the value crosses a multiple, with no lock.
  - **What G2.1 adds:** `Magnet` (`Detents.kt`) adds the lock and reuses the same tick when it locks. The output follows the finger. Inside a detent's zone it locks; past the zone it releases. Outside zones the output is the raw value, so nothing is skipped.
  - **Where:** applied to the Transform Rotation dial (5°), the Opacity slider (25%) and the Scale ruler (25%; uniform, Width and Height; during inertia too). Position has none (parked).
  - **Unchanged:** typed values never pass through a magnet, and the same actions dispatch with the snapped value.
- **Zone rule:** the zone is `min(8dp, 35% of the detent spacing)`, so zones never merge (35% < 50%).

| Control | Travel (A16) | Detent spacing | Zone |
|---|---|---|---|
| Rotation dial | 144dp dial, handle radius 60dp | 5° = **5.24dp** of arc | **1.83dp = 1.75°** (35%) |
| Opacity slider | 211.6dp | 25% = 52.9dp | 8dp = 3.78% |
| Scale ruler (96dp per doubling) | log | 100→125%: 30.9dp; 400→425%: 8.4dp; 1000→1025%: 3.4dp | 8dp; 2.9dp; 1.2dp |

**Rotation is finer than a finger resolves.** 5° detents are 5.24dp apart on the arc, so the zone is capped at 1.75° (1.83dp). On the phone the lock will feel like a light stepping near the handle's circle, and it works better with the finger further from the centre. I kept the 5° spacing; Q2 asks whether to change it.

**A16 (`TransformDetentsTest`):** every preview was either exactly on a detent or outside every zone.
- Rotation: 88 previews, 65 on a detent, 23 following the finger, including values between zones.
- Opacity: 126 / 37 / 89.
- Scale: 240 / 145 / 95.
- Typed `7.3°`, `52.3%` and `123.4%` stay exact.

JVM: `magnetLocksInsideTheZoneReleasesOutsideAndNeverSkips`, `zoneRuleKeepsZonesApart` and `scaleDetentsFollowTheLogRuler`. Snap: `graphSnapIsOnByDefaultAndPersisted` and A16 `toolsPopoverOverflowItemsAndSwipeDown` (on by default, toggles, persisted).

## 10. Tests

- **JVM:**
  - New: `GraphG21Test` (19), `HexEntryTest` (3), and `InlineValueFieldTest` +1.
  - Updated: `GraphG2Test` (Position clamps in stored units) and `FluxxColorTest` (3 digits now valid).
- **A16, new suites:** `ColourHexEntryTest`, `TransformDetentsTest` (3) and `TimeAuditTest` (2).
- **A16, new tests in existing suites:**
  - `GraphValueTest` (+2: live tile, bar opacity; 1 converted);
  - `GraphLibraryTest` (+4: xy grid, lift/follow/reflow, auto-scroll, rail);
  - `GraphPlayheadTest` (+1; the key-drag step now asserts a pan);
  - `GraphPageLayoutTest` (Snap default on).
- **Flaky (reported with evidence):**
  - `GraphLibraryTest.railReorderLiftsFollowsAndWritesOnce` failed in 2 of 6 runs after the scroll-anchor fix. Both failures came from full-suite runs, immediately after `longPressMenuOnUserCurvesOnlyAndDragToPin`, and both moved the curve to index 7. It passed alone four times and in a 3-test run with that predecessor. Cause unconfirmed; the code paths are those in §1, fixes 4–5.
  - `GraphLibraryTest.aSecondTypedEntryRightAfterTheFirstStaysOpenAndCommits` (the G2 A4 regression test; its code is untouched here) failed once in a 22-test run ("the second entry stays open": 0 popups). It passed in the sweep, twice alone, and in the 3-test run. Possibly IME state left over from the preceding test; unconfirmed.
- **Sweep:** `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest`, `TextAnimateLayoutTest`, `GraphCurveRenderTest` and `GraphPageLayoutTest` passed in the sweep, and `PaletteBrowserTest` passed in run 1.

## 11. Not verified

- Haptic feel; real-finger drag feel (lift, reflow, landing); whether the rotation lock is usable with a thumb.
- The transport bar on screen in the audit harness (verified by code only).
- The user's own project (no project was opened).
- TalkBack.
- The flaky tests' causes.

## 12. Phone test list

1. **Hex:** in any colour picker at 50% alpha, type `000000`: black, alpha still 50%. `#fff` gives white on leaving the field. `0000` reverts.
2. **Value graph, keys:** dragging a key pans; tap selects; double tap seeks. Handles still drag.
3. **Zoom:** zoom in hard; the curve never shows through the Time / Value bar.
4. **Units:** open your project's Position key. The Value tile shows the Transform page's numbers (e.g. `639.0px` / `915.5px`). Check Rotation (`1x45.0°`), Opacity (`50.0%`) and Scale (`150%`) the same way.
5. **No key selected:** the Value tile follows the playhead while scrubbing and playing; typing sets the value at the playhead (one undo); Time shows "–".
6. **Time:** a selected key's Time tile reads like the transport timecode; type `00:01:15` or a frame count.
7. **xy popover (a saved curve):** 2 × 2 tiles. Edit one and tap away: only it changes and the keyboard closes. Done never brings the keyboard back. y2 is above the keyboard.
8. **Library drag:** drag a curve into Pinned; it lifts, follows and the cells move aside. Drag a pinned one out; it dims and unpins. With 15 pinned, the replaced cell dims. Auto-scroll near the edges. Reorder in the rail.
9. **Snap:** on by default and remembered after restarting the app.
10. **Detents:** Rotation locks every 5°, Opacity and Scale every 25%, each releasing as you keep moving. Typed values stay exact.

## 13. Deviations

- **§2:** 3-digit hex applies when the field is left, not live (it flashed otherwise).
- **§5 Position clamps:** expressed in stored units. Same pixel limits; G2 was effectively unclamped.
- **§5 tile units:** the Value tile shows the unit (e.g. `639.0px`); the Transform box shows none.
- **§5 header:** Rotation reads in turns notation (`1x45.0°`).
- **§6:** for text-layer tracks, typing at the playhead uses `SetKeyframe` rather than the Edit Text payload path.
- **§6a time axis:** tick labels are timecodes, which are longer than "Nf".
- **§6a typed time:** accepts a timecode or a bare frame count.
- **§7:** xy tiles are 48dp wide (the brief said ≈40dp).
- **§8 test hook:** `CurveLibraryStore` gained a `writes` counter.
- **§8 scroll anchoring:** while a drag changes the slot, the lists hold their scroll by index.
- **§9:** Snap applies to handles only (keys don't drag).

## 14. Questions for G3

1. Should scrubs snap the playhead to frames, or should the timecode floor rather than round, so the playhead line always sits on the tick the timecode names? (§6a)
2. Rotation detents: keep 5° (a 1.75° zone, finer than a finger), or use 15° on the dial and keep 5° for something else, such as a modifier?
3. Drop-frame timecode for 29.97 / 59.94?
4. Turn the Time tile live now that the Graph uses the transport's formatter?
5. Speed graph: value units per second in the page's units (e.g. px/s, °/s)?
