# TEXT F3.2: AE parity fixes, seed flicker, export/playback, Animate polish

Branch `text-f3-2`, built on `text-f3-1` (`24cb2fd`). One consolidated pass, organised by the sections of the brief.

Status:
- Implemented in source.
- The JVM suite passes, and the debug, release and instrumentation Kotlin sources compile.
- **A16:** the Animate layout tests pass on the A16 (7 of 7), after a scroll fix the first run exposed; adjacent inspector and render suites pass too. See §12.
- Phone acceptance is pending your run of §13.

Hard rules kept:
- No changes to PreviewController, the batch/draw contract, the document schema or worker preparation.
- No new EditorAction types.
- Evaluation changed only in §1 (Ramp coverage), §2 (seed) and §3 (tracking alignment).

Two minimal boundary changes are stated where they occur:
- **§2:** the selector engine's own seed list, inside `PreparedSelectorEngine`. Worker preparation is untouched.
- **§4:** the gesture preview whitelist. The begin/preview/commit/cancel protocol is unchanged.

## 1. Selector coverage parity (Ramp Up / Ramp Down)

`PreparedSelectorEngine.coverage` is the Range branch.

**Before:** every non-Square shape had zero influence outside Start..End.

**Now (After Effects):**
- **Ramp Up:** rises 0→1 across the range. Every character at or past End is fully selected (v = 1).
- **Ramp Down:** every character before Start is fully selected. It falls 1→0 across the range and is 0 past End.
- **Square, Triangle, Round, Smooth:** unchanged.

Where the extension applies:
- It is applied after Offset, Units and Based On are resolved, so Offset animates it.
- Amount and Min/Max amount scale it like the ramp itself.
- Smoothness (Square only), Ease High/Low, Randomize Order, mode and the selector combine rules apply on top.
- A zero-width range (Start = End) stays empty for non-ramps and becomes a step for ramps.

Ease High/Low:
- They were already accepted in −100..100 by validation and the ease table.
- Ease High 25 / Ease Low 100 changes the curve, which stays monotonic (tested).

Tests (`SelectorParityF32Test`, engine level, 30 fps, frame by frame):
- **Fade up, Ramp Up:**
  - first frame (Offset −100): every weight is 1;
  - last frame: every weight is 0;
  - per-character weights fall monotonically;
  - characters release in reading order;
  - quarter and middle frames are partial.
- **Ramp Down:** the mirror (0 at the first frame, 1 at the last, rising monotonically).
- **Right-to-left** differs only in the Position vector, so the weights are identical (asserted).
- **Eased vs linear:** the eased curve differs and stays monotonic, with the same end states.
- **Units, Offset, Based On (Words), Amount and Subtract mode** all hold on the extended coverage.
- **Non-ramp shapes** are still 0 outside the range.

### Goldens re-recorded (`PreparedTextAnimationGoldenTest`)

Every F2 golden stack contains a Ramp Up (s = 1) and a Ramp Down (s = 2) selector, so all four `GOLDEN` hashes changed:

| Stack | Old (TEXT FIX 9) | New | Why |
|---|---|---|---|
| plain | `c308f9b7…` | `bd69ccc5…` | Ramp extension; then the §3 tracking alignment (centred point text) |
| rich runs | `2c87f9bd…` | `644eadc0…` | Ramp extension; then §3 tracking alignment |
| wrapped paragraphs | `e1ed5d2c…` | `9e08d0fe…` | Ramp extension only (the paragraph path already aligned tracking) |
| wiggly/advanced | `1a028c14…` | `a36cdd93…` | Ramp extension, plus the seed fixture change below |

Details:
- **Seed fixture.** The advanced stack's second seed key moved from `0x76543210+i` to `0x12345678+40+i`. Interpolated, the old span would have been about 1.6 billion seeds, far over the cap. On the old engine this fixture change alone gives `2d392f52…`.
- **Intermediate step.** After §1 and §2, before §3, the plain and rich hashes were `ab1a726b…` and `d1f1f71d…`. Only §3 moved them again; the wrapped and advanced hashes did not move.
- **Guard for "no non-ramp change".** The new `NON_RAMP` test uses the same four stacks with Square/Triangle/Round/Smooth only and constant-seed keys. It was recorded on the F3.1 engine (`24cb2fd`) **before** any change, and it is unchanged after §1–§3:
  - `1153ee31…`, `0af1af37…`, `0d9282f6…`, `b7542135…`
  - It is left-aligned so the §3 rule is a no-op there. I verified that the left-aligned and centred variants hash identically on the old tracking formula: these stacks are single-line, so the old formula never shifted them.
- **`sharedTrackMemoIsBitIdenticalAcrossLayers`** is unchanged and passes.

## 2. Random Seed interpolation (flicker)

**Rounding.** Between keys k0 and k1 the seed is `k0 + Math.round((k1 − k0) · f)`, where f is the linear fraction:
- nearest integer, ties toward +∞;
- before the first key / after the last key, the end seed holds;
- a static (unkeyed) seed never moves;
- a segment whose signed difference overflows a Long holds k0.

This applies to Range and Wiggly selectors alike, since both read `seedAt`.

**Cap.**
- Distinct reachable integer seeds per selector: **min(256, what fits the 32 MiB permutation pool for that selector)**. One seed costs 4 × the sum of the four member counts, plus wrapped-line ranks when those are prepared.
- Typical layers: for 720 glyphs the pool cap is 2,912, so 256 applies. The pool only bites past about 8,000 characters.
- A 0→200 ramp reaches 201 seeds.
- The 4,096 seed-keyframe cap is unchanged.
- `TextAnimatorEdits.MAX_SEEDS` is now 256.

**Boundary change (minimal).**
- `PreparedSelectorEngine` now prepares every reachable seed (`TextSeedRange.prepared`), sorted, and finds a seed with `binarySearch`.
- The permutation pool and worker preparation are unchanged.

**Edit time.**
- A seed edit (typed, dice or reset) whose result would exceed 256 is refused.
- The refusal shows the inline line and the WARNING glyph on the Seed tile and in its popover, in the Character pre-check style (`TextSeedRange.problem`).
- Note: with keys present, the dice button's random 64-bit seed is almost always refused, because it is far from the other key.

**Loading an over-cap document** never throws:
- The engine keeps the keyed seeds first, fills the rest up to the cap, and evaluates with the nearest prepared seed (`seedsClamped`).
- The preview publishes the warning once in the layer error map, so the inspector header shows it.
- Export renders clamped. It can render, so it does not fail.

**Cost.**
- Permutations are cached per distinct seed. 600 flicker frames on 720 glyphs allocate **0 bytes** (asserted).
- JVM host, 0→200 on 720 glyphs: preparation 5–11 ms, and 75–475 µs per frame across runs (noisy, JIT-dependent).
- These are not device numbers. Device frame time is unmeasured.

**Tests.**
- Interpolation and rounding values.
- Reachable counts for 0→25, 0→150, 0→200 and overlapping segments.
- Edit-time problem at 256 vs 255.
- Over-cap clamping with the exact end key.
- The flicker recipe (Opacity 0%, Start 0→100%, Randomize, Seed 0→25 / 150 / 200, Smoothness 0, Square):
  - all hidden at the start and visible at the end;
  - the selected set differs between consecutive frames whenever the integer seed changes;
  - at least a third of the frames re-hide a revealed character.
- A static seed keeps a stable order: the selected set only shrinks.
- `WigglySelectorTest` now asserts interpolation, where it used to assert Hold.

## 3. Tracking animator follows alignment

**Static tracking rule.** Static edits compensate the layer position so the point at the alignment fraction stays fixed (`LayerGeometry.compensateTextPosition`, with the box anchored by `textMatrixInto`).

**Bug.** In point text, `positionClusters` re-aligned each line against `newMax`, the widest line *including* animated tracking. The layer box (logical bounds) uses the static width, so animated tracking grew the text from the left edge. Paragraph text already subtracted `lineTotals · align`.

**Fix (evaluation offset only).**
- Point-text lines align inside the static box: `offset = pen + lineShift + ((baseMax − advances[l]) − (rawMax − rawAdvances[l]))·align`.
- Every line's alignment point therefore stays at `baseMax·align`, using the animated, selector-weighted total at that time.
- Line anchor stays an extra shift on top.

**Tests (`PreparedTextAnimationTest`):**
- all three alignments × line anchor 0/50/100 (left grows right, centre ±total/2, right grows left);
- a symmetric centred spread for ± tracking, with the ink centre fixed;
- a keyed amount through a Range selector at 50% Amount stays aligned at every time.

**Checked and not the same bug:**
- **Line spacing:** it grows downward from line 0, exactly like static leading.
- **Grouping alignment:** it is centred on the group bounds.

## 4. Crash: dragging keyframes in the Animate tab

**Root cause.** A16 crash buffer, read-only `adb logcat -b crash -d`, saved to `build/crash-logs/text-f3-2-crash.txt`. Two crashes, at 17:47:23 and 17:47:59:

```
java.lang.IllegalArgumentException: Unsupported transient edit
    at com.fluxx.android.editor.EditorViewModel.previewGesture(EditorViewModel.kt:170)
    at ...EditorScreen... onPreviewGesture
    at com.fluxx.android.ui.timeline.TimelineViewKt$TimelineClipRow...(TimelineView.kt:964)   ← keyframe drag
```

- With an Animate field focused, the timeline keyframe drag previews `EditorAction.EditTextAnimator(AnimatorEdit.Key MOVE)`. That path was added in F3.
- `previewGesture`'s transient whitelist never included `EditTextAnimator`, so its `require` threw on the main thread on the first drag step.

**Fix.**
- `EditTextAnimator` joins the transient whitelist. It is an existing action, so the protocol is unchanged.
- Preview and commit never throw:
  - an unsupported or refused preview keeps the last valid preview;
  - a failed commit leaves the document unchanged;
  - both publish `EditorViewModel.gestureError`, which the editor shows as a toast.
- A drag stays one undo step.

**Test (`AnimatorKeyDragTest`).** It drags keys through the real gesture protocol on:
- an animator scalar property (Opacity);
- a vector property (Position);
- a selector field (Offset);
- seed keys;
- the animator enabled Hold flag.

For each it asserts the preview, the commit, one undo step and no undo left. A refused move (a deleted selector) and an unsupported action neither throw nor change the document.

## 5. Export and playback after export

All four root causes were confirmed in source:
1. `ExportService.state` is process-global and its terminal state was never reset.
2. The done view of `ExportDialog` had only Open and Close.
3. `EditorScreen` keyed its effect on `output`, so a stale URI reopened the done dialog on every project open.
4. `onExportStart` calls `stopPreview()`, and only `onResume` (with a null preview) rebuilt it, so playback stayed dead.

Fix (the export pipeline is unchanged):
- **State.** `ExportStateStore`, a plain JVM-testable store, now owns the state. Every terminal state (saved, failed, cancelled) gets a fresh non-zero `completion` id.
- **Reset.** `ExportService.reset()` runs when the dialog is dismissed and when a project is opened (`openProject`). It never resets a running export.
- **Done dialog.** It opens only when `ExportRules.showDone` sees a completion id the screen has not seen (`rememberSaveable`).
- **Export again.** The done view adds "Export again"; Open exported video and Close stay.
- **Preview.** `MainActivity` collects the export state and calls `refreshPreview()` when an export ends (`ExportRules.rebuildPreview`). This also covers a conflated short run.

Tests (`ExportStateStoreTest`):
- done → dismiss/reset → reopen shows no dialog;
- Export again gets a new completion;
- a saved, failed or cancelled end rebuilds the preview, and a reset does not;
- running is never reset.

Saved outputs are exercised through the primitive rule, because `android.net.Uri` cannot be built on the JVM.

## 6. Non-modal slider popovers

`AnchoredPopover(modal = false)` is used by the Animate slider and vector popovers and by the Character tab's numeric popovers.

**Mechanism.**
- The popup window is not focusable, so touches outside it reach the timeline, the transport and the rail.
- It turns focusable only while its value field types (`LocalPopoverTyping`). The field requests focus two frames later and asks the IME to show.

**Dismissal.**
- **Preview tap:** `CompositionPreview` observes taps on the Initial pass, without consuming them, and sends `PopoverDismissal.request()`.
- **Another tile:** the page swaps its single popover key.
- **Back:** `BackHandler`.
- Timeline, transport and rail touches never dismiss (`PopoverDismissal.dismisses` is tested).

**While open.**
- The rail diamond and Prev/Next do not touch popover state.
- The diamond state follows the focused field live.
- The value re-reads at the new time while not dragging.
- Edits use the current time.

**Tested:**
- JVM: the dismiss rule.
- Instrumented (not yet run): diamond keys Position with its popover still open, another tile replaces it, and a preview request closes it.

## 7. Caps, size, axis labels

### Animate caps (slider = soft range; typed = hard, and the thumb pins at the end)

| Field | Slider | Typed / hard | Before |
|---|---|---|---|
| Position, Anchor point (px) | −500…500 | **±10,000** | ±100,000 |
| Scale (%) | **0…400** | **±2,000** | ruler range, ±50,000 |
| Rotation (°) | −360…360 | **±3,600** (10 turns, turns read-out kept) | ±36,000 |
| Tracking (1/1000 em) | −100…100 | **±1,000** | −500…500 typed, ±1,000 hard |
| Blur (px) | 0…50 | **0…250** | 0…100 |
| Skew (°) | −85…85 | ±85 (unchanged) | |
| Opacity family, Line anchor (%) | 0…100 | 0…100 (unchanged) | |

All other rows of the F3.1 limits table are unchanged. The F3.1 checkpoint's table is superseded by this one for these rows.

**Engine ranges.** Animator properties validate −MAX..MAX, so **no engine range changed** and there is no schema change. A test evaluates every new cap at once: all records stay finite and no NaN appears.

**Blur at 250.** `writeRecords` clamps per-glyph softness to the SDF budget: 10% of the glyph size (7.5% with faux bold), minus half the stroke width.
- 250 therefore can never produce an artefact, and the cost is unchanged (a per-glyph shader parameter).
- On a 100 px glyph it renders as 10 px (asserted).
- On a 1,000 px layer the visible blur still stops at 100 px. Values above 100 only show where the relative size takes a glyph past 1,000 px.
- I did not verify the visual result at 250 on the device.

**Scope.** These caps apply to the Animate slider popovers and to its pad/dial/ruler sub-page. The Transform tab has no slider popovers for these properties, and its dial, ruler and position pad keep their own limits: Rotation ±36,000, Scale ±50,000, Position ±10 × composition. Tell me if you want them aligned too.

### Size

`SliderPopoverMetrics`, applied globally:
- 4dp track (M3 default 16dp);
- 4 × 22dp thumb (44dp);
- 240dp minimum width (280dp).

The row stays 48dp because it is the touch target, so the row height itself is not tighter.

### Axis labels

Two-axis rows lead with an `AXIS_X` / `AXIS_Y` glyph (new monochrome `InspectorIcons`). This covers Position, Anchor point, Grouping alignment and unlinked Scale.

## 8. Value entry keyboard

**Keyboard types.**
- The Compose UI here is **1.12.1**, which has `KeyboardType.DecimalSigned` and `NumberSigned` (checked in the jar), so there is no version bump.
- Every typed value field defaults to `DecimalSigned`: `InspectorValueField`, the Transform `CompactValueBox` and the Transform numeric dialog.
- Two fields keep their keyboard:
  - **Rotation dial box:** keeps the Text keyboard, because it accepts the turns notation `2x30`.
  - **Seed:** keeps `Number`. Seeds are unsigned, so the sign key has no use.

**Parsing (`ValueParsing.number`)** never throws. Malformed input returns null and the field keeps its last value. It accepts:
- a Unicode minus or dash;
- a leading "+";
- a sign after the digits (`50-`, the Samsung "." / "−" key order);
- comma decimals, or a thousands comma when a decimal point is present;
- spaces and unit suffixes.

It rejects:
- a lone sign or a lone separator;
- a sign inside the digits;
- two decimal points;
- letters, NaN and Infinity.

This is tested. Samsung keyboard behaviour itself is unverified on the device.

## 9. Animate tab layout

- **Empty state:** a full-width "+ Animator" bar at the top (hollow tile, ADD glyph and "Animator"). The faded glyph is centred and smaller (40% of min(body, width); it was 60%).
- **With animators:**
  - The glyph stays fixed in the page centre, does not scroll, and never moves with the list (tested).
  - Collapsed bars are opaque, as before.
  - An expanded animator's rows are one opaque block in the sheet colour (`Surface`). The expanded bar's lower half joins the block, so the glyph shows only in the half-gap margins between bars.
- **"+ Animator" placement:** it now follows the last animator at full width and scrolls with the list. After an add, the list scrolls to the new animator's bar (without that, the move of the add row dragged the scroll to the end).
- **Smaller "+" for property and selector:** a 28dp drawn tile with a 16dp glyph. The touch target is ≥ 48dp wide × one row pitch tall (40dp at 268, 48dp at 328, like every Animate tile).
- **Property tiles:**
  - One collapsible **Property bar**: chevron, count, trash and small +. The tiles below it are in **insertion order**, with no category headers.
  - The add-property popover keeps its Transform / Colour / Text / Advanced categories.
- **Deleting:**
  - **Property tile:** swipe it **sideways** with the bars' swipe component (`SwipeToDelete`), past 40% of the tile and at least 40dp. Removing one property is one undo step, and an accessibility Delete action is provided.
    - Why sideways, not swipe up: the tile row does not scroll horizontally, while the Animate body scrolls vertically, so a vertical swipe is the conflicting direction.
  - **Property bar trash:** removes all of that animator's properties in one action (one undo step). It is hidden when the animator has no properties.
  - **Selector bars:** delete by swipe, as before.
- **The hold-then-delete property menu is removed.** Reset value is still on the rail.
- **Kept from F3.1:** bar structure, eye, duplicate and grip reorder.

## 10. Prev/Next with the layer selected and nothing focused

Only tier 1 of `ContextualNavigation` changed:
- **(a)** A focused field with keyframes uses only its own keyframes.
- **(b)** Otherwise (nothing focused, inspector closed, a static focus or a stale field) tier 1 is every keyframe of the selected layer (`aggregateKeyTimes`): transform and text properties, animator properties, selector fields, seed keys and the animator enabled flag.

The fall-through tiers and the nothing-selected rule are unchanged.

`ContextualNavigationTest` was rewritten:
- an unfocused walk over keys on two properties;
- a text layer with keys on transform, text, animator, selector, seed and enabled tracks, walked in order both ways;
- focused-without-keys falling to layer keys;
- keyed focus ignoring other properties;
- fall-through on a keyless layer.

`TextAnimatorEditsTest`'s stale-field case now expects the layer key.

AE parity §16, Timeline Layout and Markers were updated.

## 11. Files

- **Engine:**
  - `TextSelectorEngine.kt`: Ramp coverage, seed set and lookup.
  - `TextAnimators.kt`: `seedAt` and `TextSeedRange`.
  - `PreparedTextAnimation.kt`: tracking alignment and `seedWarning`.
  - `CompositionRenderer.kt`: seed warning for the preview.
- **Editor:**
  - `EditorViewModel.kt`: transient whitelist and `gestureError`.
  - `ContextualNavigation.kt`: tier 1.
  - `TextAnimatorEdits.kt`: `MAX_SEEDS`.
- **Export:**
  - `ExportStateStore.kt` (new);
  - `ExportService.kt`;
  - `MainActivity.kt`;
  - `EditorScreen.kt`: dialog key, reset, error toast;
  - `EditorDialogs.kt`: Export again.
- **UI:**
  - `InspectorPopover.kt`: non-modal popovers, slider metrics, axis glyph, typing focus.
  - `CompositionPreview.kt`: dismiss on tap.
  - `PropertyLimits.kt`: caps and `ValueParsing`.
  - `ElementInspectorSheet.kt`: keyboards and parsing.
  - `InspectorIcons.kt`: `AXIS_X` / `AXIS_Y`.
  - `TextAnimatePage.kt`, `TextAnimateState.kt`: layout, swipe, trash, seed pre-check.
  - `TextInspectorPanel.kt`: non-modal popovers.

## 12. Verification

- **JVM:** 465 tests, 0 failures. 435 at F3.1.
  - New suites: `SelectorParityF32Test` (13), `AnimatorKeyDragTest` (2), `ExportStateStoreTest` (3), `TextF32UiStateTest` (7).
  - Rewritten: `ContextualNavigationTest` (12).
  - Updated: the golden, `PreparedTextAnimationTest`, `WigglySelectorTest`, `TextAnimateStateTest` and `TextAnimatorEditsTest`.
- **Compile:** `compileDebugKotlin`, `compileReleaseKotlin` and `compileDebugAndroidTestKotlin` all pass.
- **A16 instrumented (SM-A165F), run on request after the first commit.** Debug and test APKs were installed with `adb install -r`. Nothing was uninstalled, no data was cleared, no UI was scripted on the app and no projects were touched.
  - `TextAnimateLayoutTest`: **7 of 7 pass** (fit at 268/328 × 1/1.3; bar icons and both control styles; Character offset/value; the new non-modal popover, X/Y glyph, swipe and trash test). It covers:
    - add bar on top and full width;
    - glyph centred, smaller and fixed;
    - Property bar, trash and toggle;
    - insertion order;
    - add bar after the Grouping row.
  - The first run failed 2 of 7 and exposed a real bug: adding the first animator moved the add bar from the top to the bottom, and the LazyColumn kept its scroll anchored to that row's key, so the list jumped to the end and the new animator was off-screen. Fixed: the page scrolls to the new animator's bar after adding (`revealAnimator`). Then 7 of 7.
  - Regression: `TextInspectorLayoutTest` 3 of 3 (Character tab popovers are now non-modal) and `AnimatorPropertyRenderTest` 2 of 2 (all 25 properties through the preview and export renderers on the new engine).
  - Logs: `build/f32-animate-layout.txt` and the `build/f32-*.txt` files.
  - Not run: the long warm-up / F2 device suites (excluded by the brief).
- **Device:** besides the test runs, only the read-only crash buffer was read. No settings were changed.
## 13. A16 manual test list

1. **Fade up (bottom up):** Animate → Position + Opacity, Position Y 100, Opacity 0%. Offset −100% → 100% over 1 s. Advanced: Ramp Up, Ease High 25, Ease Low 100. The text is hidden at the start and rises letter by letter.
2. **Fade up (right to left):** the same with Position X 100, Y 0.
3. **Ramp Down:** repeat 1 and 2 with Ramp Down (the mirror behaviour).
4. **Flicker:** Opacity 0%, Range Selector Start 0% → 100% over 1 s, Randomize Order on, Seed 0 → 25, then 0 → 150 and 0 → 200, Smoothness 0. The letters flicker in. Then type a seed key 0 → 300: it is refused with the inline line.
5. **Key while open:** with a slider popover open, tap the rail diamond and scrub. The popover stays open and the diamond updates.
6. **Tracking:** a Tracking animator on centred text spreads from the centre. Right-aligned text grows left.
7. **Drag keys:** drag keyframes on the timeline with an Animate field focused (a property, Offset, Seed, the eye). No crash, and one undo step.
8. **Scrub while open:** open a slider popover and scrub the timeline. The popover stays open and the value follows. Tapping the preview closes it, and so do Back and another tile.
9. **Signed entry:** type −50 into a value field with the number keyboard (also try `50-` and the Samsung "." / "−" key).
10. **Axis glyphs:** Position and Anchor sliders show X and Y. So does unlinked Scale.
11. **Layout:** on an empty Animate tab the bar is on top and the glyph centred. Add animators: the + bar moves to the bottom, the glyph stays behind, and expanded blocks are opaque.
12. **Deleting:** swipe a property tile or a selector bar sideways to delete it. The Property bar trash deletes all tiles at once. Undo restores each in one step.
13. **Export:** export, dismiss and reopen the project: no dialog. Export again works, and playback works right after the export ends (saved, cancelled or failed).
14. **Layer-wide Prev/Next:** with a layer selected and nothing focused, Prev/Next hops between every keyframe of the layer, then the markers, in/out and bounds.
