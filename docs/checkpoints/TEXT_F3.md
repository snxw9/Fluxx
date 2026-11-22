# TEXT F3: Animate tab (animator UI)

> **Superseded in part by [TEXT F3.1](TEXT_F3_1.md) (branch `text-f3-1`, merged in `c8109d9`).** The animator strip, the Properties | Selectors switch, the paged grid, the Range handle strip, the More popover and the menu Move/Delete entries are replaced. F3.1 adds one collapsible bar per animator with Property / Selector / Grouping rows, a vertically scrolling body, grip reorder and swipe delete. It also adds slider-or-pad transform controls, rebuilt Range/Wiggly tiles with Advanced groups and hidden redundant fields, the Character offset/value crash fix, new Skew/Blur caps and the global Prev/Next rule. The record below stays as the F3 history.

Branch `text-f3`. The Animate tab of Edit Text, built on the existing F2/T4 animator backend. UI, state and actions only. Rendering, animator evaluation and text preparation are unchanged. So are the schema, `PreviewController`, the gesture protocol, the batch/draw contract and the `EditorAction` contract.

Status: implemented in source. Compile, JVM and instrumented results are in [Verification](#verification). Phone behaviour is unverified until you run the [A16 list](#a16-test-list).

## What the page is

Pages row (unchanged): ← | Character | Paragraph | Animate. Explode stays on the Character page.

Animate body, top to bottom:

1. **Animator strip.**
   - One tile per animator: the animator glyph and its number. A disabled animator reads "n off".
   - The selected tile is filled (inverted ink); a hollow add tile sits at the end.
   - The strip scrolls sideways when it overflows (16 animators + add).
   - Tapping the selected tile does nothing.
   - Long press opens an anchored menu: Rename…, Enable/Disable, Duplicate, Move earlier, Move later, Delete.
2. **Switch row.**
   - Properties | Selectors icon tabs, two cells each, with a count caption.
   - Then the animator's own keyframeable fields: **Anchor point grouping** (Characters / Words / Lines / All) and **Animator on**.
3. **Properties tab.**
   - One tile per property: icon, value caption and keyframe badge. Colour properties show a swatch; Character value shows the character itself.
   - A hollow add tile opens an anchored popover with all 25 backend properties in three groups (Transform, Colour, Text). Already-added properties show their selected state; tapping one shows it instead of adding it again.
   - Long press a tile: Reset value, Remove.
   - The grid pages when full: the last cell becomes a `1/2` pager tile. Nothing is ever hidden behind a scroll.
4. **Selectors tab.**
   - A selector strip: Range or Wiggly glyph plus number, up to 8, then the add tile. Add asks Range or Wiggly through two icon tiles in an anchored popover.
   - Long press a selector: Enable/Disable, Duplicate, Move earlier, Move later, Delete.
   - Then the selected selector's controls:
     - **Range:** a handle strip (0–100%, or 0–N units for Index units). Drag the start or end handle, or drag the band for Offset. Below it the primary tiles: Shape, Based on, Mode, Amount, Units. **More** (the overflow glyph) opens a popover with the same tiles for the rest: Randomize order, Seed, Start, End, Offset, Smoothness, Ease high, Ease low, Min amount, Max amount, Selector on.
     - **Wiggly:** no strip. Its whole face fits: Max amount, Min amount, Based on, Mode, Wiggles/second, Correlation, Temporal phase, Spatial phase, Lock dimensions, Seed, Amount, Selector on.
5. **Empty states.**
   - No animators: only the add tile, plus a dimmed animator glyph as an icon-only hint.
   - An animator with no properties or no selectors: only that add tile.

Value editing:

- **Scalars:** the anchored slider popover, the same one the Character tiles use (reset, slider, tap-to-type value, `PropertyLimits` slider/typed/hard caps).
- **Vectors** (Anchor point, Grouping alignment): one popover with an X and a Y slider row.
- **Colours** (Fill, Stroke colour): the COMPACT `FluxxColourPicker` in an anchored popover.
- **Choices** (Shape, Based on, Mode, Grouping): one row of icon buttons in a popover.
- **Toggles** (Units %/#, Randomize order, Lock dimensions, Selector on, Animator on): tap toggles.
- **Seed:** a popover with new random seed, reset to 0 and tap-to-type (unsigned 64-bit).
- **Position, Rotation, Scale:** an in-sheet sub-page with the **Transform tab's own controls**: X/Y value boxes beside the dot-grid pad, the rotation dial, and the log scale rulers with the link toggle. ← or system Back returns to the page.

Every popover closes by tapping outside. There are no Done, ✓ or −/+ buttons.

## Layout figures (360dp window, content 292dp = 6 cells of 48.67dp)

| Sheet | Body | Strip | Switch | Gap | Rows | Slack (centred) | Tile drawn |
|---|---|---|---|---|---|---|---|
| 268, font 1.0 (expanded preview) | 212 | 40 | 40 | 8 | 3 × 40 | 4 | 44.7 × 36 |
| 328, font 1.0 | 272 | 48 | 48 | 8 | 3 × 48 | 24 | 44.7 × 44 |
| 268 × 1.3 = 348.4 | 280.4 | 48 | 48 | 8 | 3 × 48 | 32.4 | 44.7 × 44 |
| 328 × 1.3 = 426.4 | 358.4 | 48 | 48 | 8 | 5 × 48 | 14.4 | 44.7 × 44 |

- Pitch is tile + 4dp gap, so every touch target is a full 40dp (268) or 48dp (328) cell.
- Selectors tab: the selector strip takes one row. Range then has the handle strip (one row) and tile rows; Wiggly has tile rows only.
- At 268/328 font 1.0: Range shows 5 primary tiles plus More; Wiggly shows all 12.
- At 328 × 1.3 the Range face holds all 16 tiles and no More.
- Sub-pages use a 40/48dp header row (←, glyph, "Position · Animator 1", badge). Under it at 268:
  - Position: X/Y boxes (40 + 8 + 40) beside the 128dp pad.
  - Rotation: the 144dp dial.
  - Scale: one or two 80dp rulers (164 unlinked).

The sheet height never changes; nothing scrolls vertically.

## Control inventory

Every field comes from the backend (`TextAnimators.kt`, `SelectorField`, `TextSelectorEngine.coverage`, `PreparedTextAnimation`). `TextAnimateStateTest` asserts that every `AnimatorProperty` appears exactly once and that each selector kind shows exactly the fields its engine reads.

**Animator:**

| Field | Where | Keyframes |
|---|---|---|
| Name | Long press → Rename… (128 chars, trimmed, no NUL) | – |
| Enabled | Switch row "Animator on" tile; long press → Enable/Disable | Hold |
| Anchor grouping (Characters / Words / Lines / All) | Switch row grouping tile → choice popover | Hold |
| Order | Long press → Move earlier / later | – |

**Properties** (add popover group / editor / limit):

| Property | Group | Editor | Limit (slider · typed/hard) |
|---|---|---|---|
| Anchor point | Transform | X/Y sliders | ±500 · ±100000 px |
| Position | Transform | Sub-page: X/Y boxes + pad | ±500 · ±100000 px |
| Scale | Transform | Sub-page: log rulers, link | ruler · ±50000 % |
| Skew | Transform | slider | ±85 · ±3600 ° |
| Skew axis | Transform | slider | ±180 · ±3600 ° |
| Rotation | Transform | Sub-page: dial (turns notation) | ±360 · ±36000 ° |
| Opacity | Transform | slider | 0–100 % (default 100) |
| Fill colour, Stroke colour | Colour | COMPACT picker | – |
| Fill / Stroke opacity | Colour | slider | 0–100 % (default 100) |
| Fill / Stroke hue | Colour | slider | ±180 · ±3600 ° |
| Fill / Stroke saturation, brightness | Colour | slider | ±100 percentage points |
| Stroke width | Colour | slider | ±50 · ±200 px |
| Tracking | Text | slider | ±100 · ±500 (hard ±1000) 1/1000 em |
| Line anchor | Text | slider | 0–100 % |
| Line spacing | Text | slider | ±100 · ±2000 px |
| Grouping alignment | Text | X/Y sliders | ±100 · ±1000 % |
| Blur | Text | slider | 0–50 · 0–200 px |
| Character offset | Text | slider | ±100 · ±1114111 code points |
| Character value | Text | slider (32–591; caption shows the glyph) | 0–1114111 |

**Selectors:**

| Field | Range | Wiggly | Editor / limit | Keyframes |
|---|---|---|---|---|
| Kind | glyph on the strip tile, chosen when added | same | – | – |
| Start, End | handle strip; More → exact | – | 0–100 · ±100000 % (or index) | animated |
| Offset | drag the band; More → exact | – | ±100 · ±100000 | animated |
| Units (% / index) | face toggle | – | toggle | Hold |
| Based on | face choice | face choice | 4 choices | Hold |
| Shape | face choice | – | 6 choices | Hold |
| Smoothness | More | – | 0–100 % | animated |
| Ease high / low | More | – | ±100 % | animated |
| Mode | face choice | face choice | 6 choices | Hold |
| Amount | face | face | ±100 % (default 100) | animated |
| Min / Max amount | More | face | ±100 % | animated |
| Randomize order | More (toggle) | – | toggle | Hold |
| Seed | More | face | popover: random, reset, typed unsigned 64-bit | Hold (seed keys) |
| Wiggles per second | – | face | 0–10 · 0–1000 /s | animated |
| Correlation | – | face | 0–100 % | animated |
| Temporal / Spatial phase | – | face | ±360 · ±36000 ° | animated |
| Lock dimensions | – | face toggle | toggle | Hold |
| Selector on | More (toggle); long press | face toggle; long press | toggle | Hold |

## Keyframes

There is no second keyframe system. The focused field (the last tile touched, the handle being dragged, the open popover's field) owns:

- **The rail diamond.**
  - Static field: enable at the playhead (`Key ENABLE`).
  - On a key: remove it (`Key REMOVE`).
  - Otherwise: add a key with the current value (`Key SET`).
  - Hold fields use the square glyphs.
- **The rail ⋮.**
  - Reset value: the backend default, keyed if animated.
  - Freeze at playhead / Enable animation; Reset animation… (confirmed, then `Key FREEZE`).
  - Linear / Easy Ease on a key; "Hold (discrete)" for Hold fields.
- **Tile badges:** hollow diamond while keyed, filled on a key at the playhead. They come from `TextAnimatorActions.keyTimes`, the same model.
- **Timeline.** Every animator key is drawn on the layer bar, dimmed when inactive (unchanged aggregation). The focused field's keys are full size and editable. Dragging one previews `AnimatorEdit.Key(MOVE)` for that field only, on the existing begin/preview/commit calls.
- **Prev / Next:** follow the focused field (`ContextualNavigation`, new defaulted `animatorTrack` parameter).

Editing a keyed field writes a key at the frame-snapped playhead. Editing a static field changes its static value. Both use the reducer's own arithmetic.

## Actions (no new types)

The existing `EditorAction.EditTextAnimator` + `AnimatorEdit` set covers everything:

- Add / delete / rename / enable: `Put` / `Remove`.
- Reorder: `Reorder`, `ReorderSelector`.
- Selectors: `PutSelector` / `RemoveSelector`.
- Properties: `PutProperty` / `RemoveProperty`.
- Keyframe operations: `Key`.
- Duplicate is `Put` + `Reorder` in one `Batch` (one undo).

`TextAnimatorActions.payload(layer, rate, edit)` was extracted from `apply` (pure refactor; the reducer calls it). The page uses it to build live previews.

- **One undo per edit:** typed values, choices, toggles and structure edits are single actions.
- **One undo per drag:** drags run through the existing `onValueBegin` → `onPayloadPreview` → `onFinish`/`onCancel` text draft, so a drag is one undo entry and a cancel leaves nothing. The preview updates live while dragging.
- **Selection is not history:** animator, selector and focused-field selection is transient (`rememberSaveable`). It survives rotation and switching Character / Paragraph / Animate, and creates no undo entry. Stale ids (after undo or delete) fall back to the first item.

## New icons (`InspectorIcons`)

All new icons share the family rules: 24-unit grid, 1.5 stroke, round caps/joins, rounded closed shapes, hollow. `InspectorIconFamilyTest` checks them automatically (no shared glyphs, no sharp closed polygons).

- **Page:** `ANIMATOR`, `ADD` (named ADD: the test forbids a PLUS stepper), `PROPERTIES`, `SELECTORS`, `RANGE`, `WIGGLY`, `ENABLED`, `GROUPING`, `GROUPING_ALL`.
- **Shapes:** `SHAPE_SQUARE`, `SHAPE_RAMP_UP`, `SHAPE_RAMP_DOWN`, `SHAPE_TRIANGLE`, `SHAPE_ROUND`, `SHAPE_SMOOTH`.
- **Based on:** `BASIS_CHARACTERS`, `BASIS_NON_SPACE`, `BASIS_WORDS`, `BASIS_LINES`.
- **Modes:** region glyphs. Add, Subtract, Intersect and Difference share a hollow outline of two overlapping circles and fill the union, A minus B, the lens, or both crescents. Min and Max share a tall and a short rounded bar and fill the shorter or the taller one. The filled region is a second path in the same stroke, caps and joins (`InspectorIcons.REGIONS`), checked by `InspectorIconFamilyTest`.
- **Selector values:** `UNITS_PERCENT`, `UNITS_INDEX`, `RANGE_START`, `RANGE_END`, `RANGE_OFFSET`, `SMOOTHNESS`, `EASE_HIGH`, `EASE_LOW`, `AMOUNT`, `MIN_AMOUNT`, `MAX_AMOUNT`, `RANDOMIZE`, `SEED`, `WIGGLES`, `CORRELATION`, `TEMPORAL_PHASE`, `SPATIAL_PHASE`, `LOCK`.
- **Properties:** `OPACITY`, `SKEW`, `SKEW_AXIS`, `LINE_ANCHOR`, `BLUR`, `FILL_OPACITY`, `STROKE_OPACITY`, `FILL_HUE`, `FILL_SATURATION`, `FILL_BRIGHTNESS`, the three `STROKE_*` colour variants (base glyph plus a small stroke box), `GROUPING_ALIGNMENT`, `CHARACTER_OFFSET`, `CHARACTER_VALUE`.
- **Reused:** Position, Scale, Rotation, Anchor, Fill, Stroke, Stroke width, Tracking, and Leading (for Line spacing).

## Deviations

1. **Value tiles open the shared slider popover** (the Character tiles' `SliderPopoverContent`) instead of editing inside the tile. `InlineValueField` is integer-only (hex/alpha/RGB), so it can't hold float values or 64-bit seeds. Typed entry in the popover uses `PropertyLimits.parseTyped`, as on the other text tiles. The faded-previous-value placeholder is therefore not shown for animator values.
2. **Position, Rotation and Scale use an in-sheet sub-page, not a popover.** Their Transform controls type through their own popovers, and a popover inside a popover loses focus and closes (the TEXT UI 7 hex finding). The position pad spans one composition width/height around the glyph's own place.
3. **A new animator starts with one Range selector (0–100%),** as in After Effects. With no selectors, coverage is zero and the animator does nothing. It can be deleted.
4. **The Range face keeps five primary tiles** next to the handle strip at the normal sizes; the rest sit behind **More**, a popover of the same tiles. Wiggly fits whole.
5. **Anchor point grouping and Animator on are tiles in the switch row.** The prompt didn't name grouping, but the backend has it (keyframeable Hold). Enable/Disable is also in the long-press menu.
6. **Mode glyphs fill their result region.** This is an exception to "hollow unless state": the filled area shows the combination result. Intersect has the smallest fill, a lens about 7 units wide (about 6dp at the 22dp tile icon). All six were checked only by geometry and tests, not yet by eye on the A16.
7. **New Fill/Stroke colour properties start at the backend reset values** (white / black), not After Effects' red.
8. **The animator colour popover has no eyedropper.** The panel's eyedropper is wired to layer paint.
9. **Index units: the strip's scale is an estimate** from the source text (characters, non-space characters, words, explicit lines; wrapped lines are not known to the UI). Only the strip scale is estimated; stored values are exact.
10. **Delete has no confirmation;** undo restores it.

## Follow-ups

- **Eyedropper in the animator colour popover.** The COMPACT picker supports it; wire it to Fill/Stroke colour animator properties. The current eyedropper path is specific to layer paint (deviation 8).
11. **Shared-component changes, all additive:**
    - `InspectorTile` gained `filled` / `hollow` styles.
    - `PositionTrackpad`, `RotationDialController`, `ScaleController` and `CompactValueBox` changed from `private` to `internal` (no behaviour change).
    - `TimelineView`, `LayerKeyMarkers`, `ContextualNavigation`, `EditorViewModel.navigateBoundary`, `ElementInspectorSheet` and `TextInspectorPanel` gained defaulted animator-focus parameters.
    - Edit Text tabs gained test tags (`text-tab-…`).
12. **Character offset/value previews wait on the backend's catalogue preparation** while dragging (TEXT PERF 11: about 20 s cold for a 720-glyph fixture). This is unchanged backend cost, reported rather than changed.

## Verification

Run by the agent on this branch, separately from your phone tests:

- **Compile:** `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin` passed (`tools/text/results/f3-compile*.log`).
- **JVM:** 421 tests, 0 failures, 0 errors, 0 skipped (`f3-jvm2.log`). New: `TextAnimatorEditsTest` (9), `TextAnimateStateTest` (8) and the mode-icon test in `InspectorIconFamilyTest`:
  - add/remove/reorder/duplicate/rename and the 16/8 caps;
  - static vs keyed edits and frame snapping;
  - rail toggle/freeze/easing;
  - one undo per edit and per drag, and cancel;
  - payload = reducer;
  - timeline MOVE of one field, and Prev/Next;
  - layout at 268/328 × 1/1.3;
  - full inventory, enum mirrors and limits at hard caps;
  - paging, stale selection and saved focus.
- **Host:** native sources are unchanged.
  - `text-proof selftest`: PASS.
  - `shaping_parity.py`: exit 0.
  - `verify_inputs.py`: 1,447 checked, passed.
- **Instrumented (A16, SM-A165F, debug APKs installed with `adb install -r`; nothing uninstalled, no data cleared):**
  - `TextAnimateLayoutTest` (new) passed, 5 of 5:
    - the page fits at 268 and 328 at font scale 1 and 1.3, with tiles at 40/48dp targets;
    - add animator / properties / Wiggly through the real reducer;
    - the Position sub-page and Back.
  - `TextInspectorLayoutTest` (existing) passed, 3 of 3.
  - **App suite** (`a16-f3-suite`, `notClass` F2/perf as in TEXT PERF 11): 44 run, 37 passed, 7 failed. All 7 failures are environmental, the same set as PERF 10/11:
    - `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
    - `TextF1AcceptanceTest#fiveHundredGlyphs…` failed only at its final `validationEnabled` assertion. Its zero-allocation and counter assertions passed.
    - `E1aGoldenExportTest` is missing its local source asset.
  - The stay-awake setting was set for the run and restored to 0. No other device setting changed.

## A16 test list

Debug build, inspector open on a text layer, Edit Text → Animate.

1. **First animator.** Tap the add tile in the empty strip. Then:
   - Properties → add → Position, Opacity and Fill colour.
   - Set Opacity to 0 and Fill to red; open Position and drag the pad. The preview updates live while you drag.
   - Undo after each step removes exactly that step; Redo restores it.
2. **Range handles.**
   - Selectors → drag the end handle from 100% towards 0: characters change one by one, live.
   - Drag the start handle, then drag the band (Offset).
   - Switch Units to # and repeat.
   - Each drag is one undo.
3. **Reveal.**
   - Tap the band (Offset focuses).
   - At 0 s set Offset to −100 and tap the rail diamond; at 2 s set Offset to 0.
   - Play: the reveal runs. The Offset keys are bright on the timeline and the others are dimmed. Prev/Next jump between the Offset keys.
   - Drag an Offset key on the timeline: only Offset moves.
4. **Shapes.** Add a second animator (Scale 200%); in its selector choose Ramp up, then Triangle, then Smooth. Each looks different, and both animators combine.
5. **Wiggly.**
   - Selectors → add → Wiggly, with a Position property set to 40, 40.
   - Play: the glyphs wiggle. Change Wiggles/second, Correlation (0 and 100) and Lock dimensions.
   - Scrub backwards: the same frame looks identical at the same time.
6. **Character offset / value.** Add Character offset 5 (letters shift), then Character value 65 (the caption shows A). Remove both via long press.
7. **Structure.**
   - Long press an animator: Rename, Disable (the strip shows "off" and the preview loses that effect), Enable, Duplicate (the copy follows the source), Move earlier/later, Delete.
   - Do the same for selectors.
   - Undo and redo each.
8. **Persistence.** Save, close and reopen the project: animators, selectors, keys, names and the seed survive.
9. **Export.** Export a 3 s clip and compare it to the preview at 0, 1 and 2 s.
10. **Performance.**
    - A 30-line text layer with 3 animators: play and scrub; it should be smooth.
    - Duplicate an animated layer to 30 layers, then play and scrub (compare with TEXT PERF 11).
11. **Lifecycle.** With the Animate page open (a Position sub-page or popover open too):
    - Rotate to landscape and back.
    - Use split screen and drag the divider.
    - The project stays open, the same animator/selector/tab is selected, and no edit is lost or half applied.
12. **Fit.** Repeat 1–2 with the expanded preview (268) and standard (328), and at font size 1.3:
    - nothing is clipped;
    - every tile is tappable as itself;
    - the strip scrolls sideways at 16 animators.
