# TEXT F3.1: Animate tab rework, crash fix, limits, global Prev/Next

Branch `text-f3-1`, built on master after TEXT F3 (`7993799`). It addresses the first A16 test of the Animate tab:
- the layout was hard to use;
- adding Character offset or Character value crashed the app;
- some options were duplicates;
- Prev/Next lost context.

The engine is unchanged. There are no changes to:
- PreviewController, the gesture protocol or the batch/draw contract;
- the document schema;
- animator evaluation or the native renderer;
- the EditorAction contract. No action types were added.

Two preparation boundaries were hardened (§6), and every hidden option stays in the backend.

Status: implemented and verified in source (§10). Phone acceptance is pending your A16 run (§11).

## 1. Range drag strip removed

- `RangeHandleStrip` is deleted, along with the Index-units strip-scale estimate (`AnimateUnits`), which only existed for it. Nothing in the Animate tab is a drag strip.
- Start, End and Offset are ordinary tiles. Each opens the standard slider popover with typed entry, a keyframe badge and focus, like every other field.
- The Transform tab's pad, dial and rulers are untouched.

## 2. Position / Rotation / Scale: both control styles

- **Sliders (default):** anchored slider popovers with typed values and `PropertyLimits` caps.
  - Position: X and Y sliders, ±500px on the slider, ±100,000px typed.
  - Scale uses the Transform tab's link semantics (`ScaleRulerMath.linkedY`):
    - Linked shows one Scale slider and keeps the X:Y ratio.
    - Unlinked shows Scale X and Scale Y.
    - The link toggle is in the popover row.
  - Rotation: **the slider and the typed value are plain degrees.** The popover adds the dial's turns notation (`n x remainder`, the same `formatRotation`) as a read-out. Turns cannot be typed in the shared slider popover; its parser takes numbers only.
- **Pad, dial, rulers:** the existing in-sheet sub-page (the Transform tab's `PositionTrackpad`, `RotationDialController` and `ScaleController`), with ← and system Back.
- **Switch:** the rail ⋮ shows `Controls: Sliders → Pad, dial, rulers` (or the reverse), only while Position, Rotation or Scale is focused.
- **Persistence:** an app preference, not project state and not schema (`AnimateControlsPreference`, SharedPreferences `inspector_preferences` / `animate_transform_controls`). It survives restart.

## 3. Selector tiles

These were verified against `TextRangeSelector`, `SelectorField` and `TextSelectorEngine` (`rangeCoverage`, `wigglyCoverage`).

| | Primary (always visible) | Advanced (collapsed by default, transient) |
|---|---|---|
| Range | Start, End, Offset, Shape | Units, Based on, Mode, Amount, Smoothness (**only while Shape = Square**), Ease high, Ease low, Randomize order, Seed |
| Wiggly | Max amount, Min amount, Wiggles/second, Correlation | Based on, Mode, Temporal phase, Spatial phase, Lock dimensions, Seed |

- **Smoothness** is read only in the Square branch of `rangeCoverage`. When the Shape leaves Square the tile disappears, and the stored value is untouched (tested).
- Each selector's on/off is the eye on its bar.

### Hidden backend fields (UI only; backend, defaults and schema untouched)

| Field | Hidden on | Why | Engine |
|---|---|---|---|
| Min amount, Max amount | Range | Redundant with Amount: coverage = Amount × (Min/Max blended by the shape). After Effects' Range selector has only Amount. | Still applied |
| Amount | Wiggly | It only scales Min/Max. After Effects' Wiggly has none. | Still applied |
| Enabled | both | On the bar (eye), not a tile | Still applied |
| Kind | both | Chosen when the selector is added | Still applied |

**Existing documents with non-default hidden values** keep rendering exactly as before, because the engine honours them. The only hint is one **WARNING tile** at the end of that selector's Advanced group. It appears only while a hidden field differs from its default or is keyed. Its popover lists the values ("Not shown for this selector, still applied: Min amount 20%") and has a Reset that restores them to defaults in one undo entry (`AnimateHidden`).

## 4. Structure: one collapsible bar per animator

```
            [+]                       ← add animator (centred; vertically centred when empty)
 [grip] [v] Animator 1   [dup] [eye]
    Property                    [+]   ← add-property popover (§5)
      [v] Transform  2                ← group header, collapsible; tiles below
          [Pos][Opac]
      [v] Colour / Text / Advanced    ← only groups that hold properties
    Selector                    [+]   ← Range / Wiggly popover
      [grip] [v] Range Selector 1  [dup] [eye]
          [Start][End][Offset][Shape]
          [>] Advanced
      [grip] [>] Wiggly Selector 2 [dup] [eye]
    Grouping                    [Grp] ← Anchor point grouping (choice popover)
 [grip] [>] Animator 2   [dup] [eye]
```

- **Bars:** grip, animated chevron, name, duplicate, eye.
  - The eye is the existing keyframeable Hold `enabled` flag. Off keeps every key and value. Tapping the eye focuses that field, so the rail diamond keys it, and a key badge shows on the eye.
  - Collapsed, only the bar shows. Expand and collapse animate (`animateItem` fade/placement plus chevron rotation).
- **One animator is expanded at a time.** Selectors, property groups and Advanced groups expand independently.
- **Transient state:** all of this is `AnimateBars`, saved with the panel, never in the document and never undone. Focus is held separately, so collapsing never loses it.
- **Reorder:** long-press-drag on the grip, for animators and for selectors. It follows the timeline hierarchy rule:
  - the target is the absolute pointer position against sibling slot centres captured at drag start, never animated positions;
  - the dragged row and its whole block follow the finger, and siblings animate with `animateItem`;
  - edges auto-scroll;
  - previews go through the text draft, so release is **one undo entry** and cancel restores.
  - The Move earlier/later menu entries are removed.
- **Delete:** swipe a bar sideways past 40% of its width. Undo restores, with no confirmation. A Delete accessibility action is on the bar. No menu has a Delete entry.
- **Long-press an animator bar:** the menu has Rename only. Selector bars have no menu.
- **Duplicate:** one tap on the bar icon, using the existing `duplicateAnimator`/`duplicateSelector`. It is one Batch (one undo), with fresh ids, the **same seeds**, the keys, properties and grouping.
- **Grouping alignment** stays an ordinary property in the Text group.
- New animators still start with one Range selector (0–100%). The new animator and that selector open expanded.
- **Persistent glyph:** the faded Animate glyph sits behind the page at all times. It is monochrome `TextPrimary` at 5% alpha with no semantics, takes no touches and moves no layout. Tiles and bars draw their own `SurfaceHigh` surfaces on top, so their contrast is unchanged. Row labels sit over at most a 5% tint.

### Layout figures (360dp window, content 292dp = 6 cells of 48.67dp; every row is one pitch)

| Sheet | Body | Tile / pitch | Rows visible | Touch targets |
|---|---|---|---|---|
| 268, scale 1 | 212 | 36 / 40 | 5 | 40 |
| 328, scale 1 | 272 | 44 / 48 | 5 | 48 |
| 268, scale 1.3 | 200 | 36 / 40 | 5 | 40 |
| 328, scale 1.3 | 260 | 44 / 48 | 5 | 48 |

- An expanded animator with two properties and its Range selector open is 10 rows. That is 400dp at 268 and 480dp at 328, so the body scrolls.
- The `1/2` pager tile is gone. Tiles lay out in rows of the same cells.
- Bar controls (grip, duplicate, eye) are full-pitch touch targets.

### Deviation

**The Animate body scrolls vertically.** This is the one deviation from the earlier no-scroll rule. The sheet height never changes (268/328), and the rail on the left stays fixed (tested). Nothing else scrolls.

## 5. Add-property popover, regrouped

Order:
1. **Transform:** Anchor point, Position, Scale, Skew, Skew axis, Rotation, Opacity.
2. **Colour:** Fill colour, Stroke colour, Fill opacity, Stroke opacity, Stroke width.
   - A collapsed **Colour shift** sub-group: Fill/Stroke hue, saturation, brightness. These are relative shifts of the existing colour, not duplicates of the colour properties.
3. **Text:** Tracking, Line anchor, Line spacing, Grouping alignment, Blur.
4. A collapsed **Advanced** group: Character offset, Character value.

Added properties show as selected.

**Animator Blur** is per-glyph edge softness: an SDF shader parameter (`writeRecords` encodes it next to the stroke distance), cheap, and not a Gaussian layer blur. It is independent of the future Effects-tab blur.

## 6. Character offset / value crash

### Root cause (A16 crash buffer, read-only `adb logcat -b crash -d`; full log in `build/crash-logs/text-f3-1-crash.txt`)

Three identical crashes at 11:14:00, 11:14:15 and 11:16:00:

```
FATAL EXCEPTION: main   Process: com.fluxx.android.debug
java.lang.IllegalArgumentException: Character animation needs worker preparation; retry preparation or narrow the animated range
    at com.fluxx.android.model.PreparedTextAnimation.<init>(PreparedTextAnimation.kt:95)
    at com.fluxx.android.model.TextLayoutMetrics.prepareAnimation$app(TextLayout.kt:69)
    at com.fluxx.android.model.TextLayoutMetrics.animation(TextLayout.kt:73)
    at com.fluxx.android.model.TextLayoutMetrics.animatedBounds(TextLayout.kt:76)
    at com.fluxx.android.model.TextLayoutMetrics.bounds(TextLayout.kt:32)
    at com.fluxx.android.ui.editor.EditorScreenKt.EditorScreen(EditorScreen.kt:123)
    at androidx.compose.runtime.RecomposeScopeImpl.compose(...)
```

- `EditorScreen` computes `textCanFit` on the main thread from `selectedTextMetrics`, a `produceState` keyed on the layer text.
- In the recomposition right after the property is added, `produceState` still holds the **previous** metrics, which have no character catalogue. They are handed the **new** text.
- `PreparedTextAnimation` then hits `requireNotNull(characterCatalogue)` and throws out of composition.
- The other 23 properties need no catalogue. That is why only these two crashed.
- The other suspects were checked:
  - `codepoints` (4,096) and `TextNominalMetrics.index` ("codepoint was not prepared") are reachable in the same way from a stale catalogue after a value drag. The test reproduces this.
  - First-preparation cost was not the cause.

### Fix

- **UI boundary (`TextLayoutMetrics`):** bounds, geometry, logical bounds and the stroke-clamp label never throw for Character animation. Without a matching catalogue (stale metrics, or a missing codepoint at evaluation) the layer is measured without those overrides (`TextCharacterPreparation.withoutCharacters`). This affects measurement only; the renderer is unchanged.
- **Renderer preparation boundary (`CompositionRenderer.prepareTextProject`):** a catalogue failure from the codepoint or catalogue caps no longer fails the frame.
  - **Interactive previews** draw the layer without its Character animation and publish the message (`TextLayerErrors.characters`). The inspector header shows it.
  - **Export** still fails explicitly, so a file is never silently degraded.
- **Error UI:** the offending Character offset/value tile shows the WARNING glyph with state "Error: …". Its popover has a short warning line (`TextCharacterPreparation.problem`).
- **Pre-check:** before a Character offset/value value is committed (typed, slider drag, reset, or adding the property), the page computes the reachable-codepoint count and fonts × codepoints for the layer's distinct characters and fonts. It refuses the change with the same inline message when either would exceed **4,096 codepoints** or **16,384 glyphs**.
- **Regression tests:**
  - `AnimatorPropertyCoverageTest` (JVM) adds all 25 properties through the real reducer and prepares/evaluates each on the UI path (no catalogue), the worker path (with catalogue) and a stale-catalogue path.
  - **With the fix reverted it fails with exactly the device exception, and the stale-catalogue case also fails ("codepoint was not prepared"). With the fix it passes.**
  - `AnimatorPropertyRenderTest` (A16) renders all 25 through the production preview and export renderers. It also checks that over-cap Character animation degrades in preview and fails in export.

## 7. Limits

- **Skew:** `TextSelectorEngine.affine` clamps the coverage-weighted skew to ±85°, so the typed cap is now **±85** (the slider is ±85).
  - Trade-off: at partial coverage, a typed 170° previously reached 85°. Now the cap applies before weighting.
- **Blur:** `PreparedTextAnimation.writeRecords` clamps the per-glyph blur to the SDF budget: **10% of the glyph's font size** (7.5% with faux bold), minus half the stroke width. At the 1,000px maximum layer size that is 100px, so the **typed cap is now 100px** (it was 200). The slider stays 0–50.
  - A run with relative size above 100% can exceed this budget. This is rare, and the cap follows the layer maximum.
- **Character offset/value:** slider ranges are unchanged. The pre-check (§6) replaces a cap, because the real limit depends on the text.

### Limits table (asserted by `TextAnimateStateTest.limitsTable`; typed = hard)

| Field | Slider | Typed / hard |
|---|---|---|
| Anchor point, Position (px) | −500…500 | ±100,000 |
| Scale (%) | ruler range | ±50,000 |
| Skew (°) | −85…85 | **±85** |
| Skew axis (°) | −180…180 | ±3,600 |
| Rotation (°) | −360…360 | ±36,000 (turns read-out) |
| Opacity, Fill/Stroke opacity, Line anchor (%) | 0…100 | 0…100 |
| Stroke width (px) | −50…50 | ±200 |
| Fill/Stroke hue (°) | −180…180 | ±3,600 |
| Fill/Stroke saturation, brightness (%) | −100…100 | ±100 |
| Tracking (1/1000 em) | −100…100 | −500…500 typed, ±1,000 hard |
| Line spacing (px) | −100…100 | ±2,000 |
| Grouping alignment (%) | −100…100 | ±1,000 |
| Blur (px) | 0…50 | **0…100** |
| Character offset | −100…100 | ±1,114,111 + pre-check |
| Character value | 32…591 | 0…1,114,111 + pre-check |
| Fill / Stroke colour | colour picker | — |
| Start, End (%) | 0…100 | ±100,000 |
| Offset (%) | −100…100 | ±100,000 |
| Amount, Min/Max amount, Ease high/low (%) | −100…100 | ±100 |
| Smoothness, Correlation (%) | 0…100 | 0…100 |
| Wiggles/second | 0…10 | 0…1,000 |
| Temporal / Spatial phase (°) | −360…360 | ±36,000 |

**Structural caps:**
- 16 animators per layer;
- 8 selectors per animator;
- one of each property per animator;
- name 128 characters;
- 64 distinct seeds per selector;
- 4,096 reachable codepoints;
- 16,384 catalogue glyphs.

## 8. Contextual Previous / Next: one global rule

`ContextualNavigation` was rewritten; `EditorViewModel.navigateBoundary` is unchanged and delegates to it. The rule is defined in [AE parity §16](../architecture/AE_PARITY_VISION.md#16-contextual-previous--next-navigation), and [Timeline Layout](TIMELINE_LAYOUT.md#contextual-previous--next) and [Markers](MARKERS.md) were updated to match. There is no `PREVIEW_ACTION_BAR.md`; Timeline Layout holds that section.

Per direction, the first tier with a stop strictly beyond the playhead frame wins:
1. focused keys, only while keyed;
2. markers inside the layer span (layer and composition);
3. layer in/out;
4. markers outside the span;
5. timeline bounds.

With nothing selected: composition markers, then bounds.

**Deliberate behaviour changes:**
- a focused property no longer blocks markers;
- a selected layer now reaches composition markers.

The guards are unchanged.

## New icons (`InspectorIcons`, same stroke family)

| Icon | Shape |
|---|---|
| `EYE` | Almond and pupil, matching the timeline eye |
| `EYE_OFF` | Almond with a slash |
| `GRIP` | Three bars |
| `EXPANDED` | Down chevron, rotated to point right when collapsed |

Reused icons: `DUPLICATE`, `DELETE` (swipe reveal), `WARNING` (character error, hidden values).

## Follow-ups (also in the roadmap)

- On-preview highlight of the characters a selector covers. The strip that implied coverage is gone.
- Eyedropper in the animator colour popover. Still open.
- Known gaps against After Effects:
  - per-character 3D rotation;
  - an "All Transform Properties" shortcut;
  - the Character Range option;
  - the Expression selector.

## 10. Verification

- **Compile:** Kotlin debug and release, plus instrumentation sources: pass.
- **JVM:** 435 tests, 0 failures.
  - New or rewritten:
    - `ContextualNavigationTest` (10, every tier and edge);
    - `AnimatorPropertyCoverageTest` (4: all 25, stale catalogue, pre-check caps);
    - `TextAnimateStateTest` (14). It covers fit and scroll at 268/328 × 1/1.3, bar state with one expanded animator and focus kept, Range/Wiggly tiles, Smoothness only for Square, hidden fields absent in every shape state, the hidden-value hint and reset, the popover groups, the limits table and caps, grip reorder as one undo, swipe delete and undo, duplicate (seeds, keys, grouping), the eye keeping keys, and preference persistence.
  - `MarkersTest` was updated for the new rule: from frame 0 / 100 a selected layer now reaches composition markers 30 / 90.
- **Host:** native sources are unchanged.
  - `text-proof selftest`: PASS.
  - `shaping_parity.py`: exit 0.
  - `verify_inputs.py`: 1,447 checked, passed.
- **Instrumented (A16, SM-A165F; debug and test APKs installed with `adb install -r`; nothing uninstalled, no data cleared, no scripted UI on the app, no projects touched):**
  - `TextAnimateLayoutTest` (rewritten): 6 of 6.
    - Fits at 268/328 × 1/1.3 with an animator expanded: Property groups, Range expanded with Advanced open, then Wiggly with Advanced open, then the Grouping row.
    - Every tile scrolled into view sits inside the body at a full 40/48dp target. The body scrolls and the rail does not move. Hidden fields are absent.
    - The bar's duplicate and eye work, and both control styles work.
    - Character offset/value add without a crash.
    - The controls preference uses an in-memory store in tests, so no app file is written.
  - `AnimatorPropertyRenderTest` (new): 2 of 2.
  - `TextInspectorLayoutTest`: 3 of 3.
  - **App suite** (`a16-f3-1-suite`, `notClass` F2/perf as in PERF 11 and F3): 47 run, 40 passed, 7 failed. All 7 failures are environmental, the same set as before:
    - `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
    - `TextF1AcceptanceTest#fiveHundredGlyphs…` fails at its final validation assertion.
    - `E1aGoldenExportTest` is missing its local asset.
  - The stay-awake setting was set to 7 for the suite and restored to 0.
  - A first full run that included F2/perf was stopped with `am force-stop com.fluxx.android.debug.test`. That stops processes only; no data was cleared.

## 11. A16 test list

Debug build, a text layer selected, Edit Text → Animate.

1. **Add an animator.** Tap the centred +. It opens expanded. Collapse it with the chevron and see only the bar; expand it again and see the Property, Selector and Grouping rows.
2. **Duplicate** with the bar icon. Animator 1 copy appears below, identical. Undo removes it in one step.
3. **Sliders.** Property + → Transform → Position and Opacity.
   - Tap Position: X/Y sliders, live while dragging, one undo per drag.
   - Type a value.
   - Set Opacity to 0.
4. **Control style.** Focus Position, then rail ⋮ → `Controls: Sliders → Pad, dial, rulers`. Tapping Position now opens the pad, and ← returns. Check Rotation (dial) and Scale (rulers), then switch back. Kill and reopen the app: the choice is kept.
5. **Reveal.** On Range Selector 1:
   - Start 0, End 100.
   - Offset: rail diamond at 0s = −100, move the playhead, Offset = 100 (key).
   - Shape: Ramp up.
   - Advanced: Ease high 25, Ease low 100.
   - Play: characters reveal left to right.
   - Check Smoothness is visible only with Shape = Square.
6. **Eye** off/on on the animator and on the selector. Keys and values are kept. Key the eye with the rail.
7. **Reorder.** Long-press a grip and drag two animators, then two selectors. Siblings slide, the dragged row follows the finger, and Undo restores in one step.
8. **Swipe delete.** Swipe a selector bar and an animator bar sideways. Undo restores each.
9. **Wiggly** selector with Position: check Max/Min amount, Wiggles/s, Correlation, and the Advanced phases, lock and seed.
10. **Character offset and value** (Property + → Advanced). No crash. Type Offset 5000 on short text: it is refused with the inline line. On very varied text the tile shows the warning, and the preview keeps drawing without the character animation.
11. **Prev/Next walk** on a layer with markers inside and outside its span, and composition markers inside and outside. Walk it:
    - with a keyed focused property (keys first, then inside markers, in/out, outside markers, bounds);
    - with an un-keyed focused property (same as unfocused);
    - with nothing selected.
    - Repeat on the Character, Paragraph and Animate pages and with the inspector closed. The behaviour must be identical.
12. **Rotate** the phone and use **split screen** with an animator expanded. The expansion, focus and scroll survive, and nothing clips.
