# TEXT F3.3: popover exit, Prev/Next fall-through, sheet swipe, small polish

Branch `text-f3-3`, built on `master` (`5ad9364`). One consolidated pass, organised by the sections of the brief.

Status:
- Implemented in source.
- The JVM suite passes (478 tests, 0 failures).
- **A16:** the Animate, inspector-swipe and inspector layout tests pass, as does the render suite (§9).
- Phone acceptance is pending your run of §10.

Hard rules kept:
- No changes to PreviewController, the gesture protocol, the EditorAction contract, the batch/draw contract, the document schema or preparation.
- No evaluation change: §5 found that box text already spreads from the alignment point and only adds tests.
- Popovers stay non-modal. No new visible buttons, no −/+ steppers, no Done/✓. Sheet heights are unchanged (268dp / 328dp).

## 1. Leaving a slider popover (still non-modal)

The slider popovers stay non-modal (F3.2): the timeline, the transport (play, Prev/Next) and the rail diamond keep working while one is open and do not close it. New ways to close one:

| Way to close | How |
|---|---|
| **Same tile again** | A toggle (`PopoverToggle.next`) on every Animate slider, vector, colour, choice and seed tile, and on the Character / Paragraph tiles (`openPopover`). The rail's "Manual spacing" item still only opens. |
| **Empty sheet-body space** | `Modifier.dismissPopoversOnEmptyTap()` on `text-inspector-body`: an invisible layer that watches the body's taps after its children. A tap that no tile, bar or list consumed requests dismissal (`PopoverDismissal.request`). Scrolls and drags never count. The rail, timeline and transport sit outside this box. |
| **Swipe the popover down** | The popover follows a downward swipe and closes past 48dp; a shorter drag springs back. A swipe that starts on a slider track stays a slider gesture, because an M3 slider moves its value on press (`PopoverSwipeGuard`). The axis glyph, value box and padding all start the swipe. |
| **Back** | Closes the popover first. Its `BackHandler` is registered after the sheet's and the editor's, so it wins; the sheet stays open. |

The existing triggers still work: a preview tap, another tile, a collapsible bar, Back. Closing never loses the value: slider drags commit on release and typed values on Done or focus loss. The toggle keeps the field focused, so the rail and Prev/Next still follow it.

## 2. Tracking slider range

`PropertyLimits.ANIM_TRACKING`: the slider now spans ±1000, equal to its hard limit, with detents every 100. Typed values stay capped at ±1000.

All other caps are unchanged, including the Character tab's Tracking (slider ±100, typed ±500, hard ±1000). I read "the Tracking slider" as the Animate one; say if you want the Character slider widened too. That would mean raising its typed cap to ±1000.

## 3. "+ Animator" bar visibility

`AnimateRows.build` drops the add bar while any animator bar is expanded. It returns at the bottom once every bar is collapsed. In the empty state it is still the top (only) row.

Adding an animator expands the new one, so the bar hides at once, and F3.2's scroll-to-new-animator (`revealAnimator`) still runs. The list keeps its anchor on expand and collapse: the bar you tap is always at or below the first visible item. The A16 test asserts that the tapped bar does not move in either direction.

## 4. Prev/Next: the new fall-through step

**Cause.** In F3.2 tier 1 was "the focused field's keys, or (only if it had none at all) every key of the layer". A focused field that had keys, but none in the direction of travel, skipped the layer's other keys and fell straight to markers and the out point. That is the reported case: Random Seed keyed behind the playhead, then Next jumped to the layer end instead of the Start / Tracking keys ahead.

**Fix (`ContextualNavigation.tiers`).** The layer-wide keys are now their own tier after the focused field.

Final tier list, per direction (the first tier with a strictly earlier / later stop wins; exhausted tiers fall through):
1. The focused property / animator field's keys.
2. **New:** every keyframe time of the selected layer (`layerKeys`: transform, text, animator properties, selector fields, seed keys, enabled).
3. Markers inside the layer span.
4. Layer in / out.
5. Markers outside the span.
6. Timeline bounds.

With nothing focused, an unkeyed focus or the inspector closed, tier 1 is empty and tier 2 gives the F3.2 behaviour. With nothing selected it is unchanged: composition markers, then the bounds.

**Engine-level reproduction (`ContextualNavigationTest`):**
- (a) Seed keyed behind the playhead. Next walks Start 1.5 s → animator Tracking 1.8 s → text Tracking 2.2 s → Start 2.5 s → out point. With the seed keyed ahead, Previous walks back through the same keys. The seed key itself is still the first stop when it lies in the direction of travel.
- (b) An unkeyed field focused while another property is keyed: the jump lands on that key. This runs for each focus kind, both directions, inspector open and closed:
  - Transform (Position, Opacity);
  - Text (Size);
  - Animate animator property (Opacity);
  - Animate selector fields (End, Offset);
  - nothing focused.
- A keyed Text-page focus (Tracking) and a keyed animator property (Tracking) hop their own key first, then the rest of the layer.

No case still skipped keys after the change. The focus plumbing (`TextFocus.owner` returns null on the Animate tab; animator focus clears when the tab or page changes) passes the right field on every page, so the tier structure was the only cause.

## 5. Tracking on box (paragraph) text

This already worked; no fix was needed. The paragraph branch of `PreparedTextAnimation.positionClusters` subtracts each line's animated tracking total times that line's own alignment factor (`paragraph.align[l]`). It is the same rule F3.2 gave point text.

New tests (`TextF33TrackingBoxTest`) on a box that wraps to several lines:
- Centred: each line's visible glyphs grow symmetrically about the line centre, for positive and negative tracking. A trailing space at a wrap stays outside the line.
- Left grows right; right grows left.
- Animated tracking moves the visible glyphs exactly as static tracking of the same amount does (at widths where static tracking does not re-wrap).
- Animated tracking never re-wraps.

## 6. Swipe down from the top of the sheet on every page

**Cause.** The gesture lived in each header row (`SectionHeader`, the landing handle), not in the sheet. Edit Text draws its own header (Character / Paragraph / Animate, and the Fonts sub-page), so it never had one.

**Page audit before F3.3:**

| Page | Had it? |
|---|---|
| Landing grid (handle) | yes |
| Transform, Timing, Blending & Opacity, Audio | yes |
| Edit Solid | yes (uses `SectionHeader`) |
| **Edit Text: Character, Paragraph, Animate, the Fonts sub-page, the Animate Pad sub-page** | **no** |

**Fix.** One sheet-level behaviour, `Modifier.inspectorSwipeDown` on the sheet (`InspectorSwipe.kt`), replaces both per-header detectors. It starts only inside the top band (the 8dp frame plus one header row, which grows with the font scale). So it never fights:
- the Animate list's vertical scroll;
- sliders and tiles in the body;
- text editing: it is off while the source text is being typed, when the top of the sheet is the text field and the keyboard is up.

It watches on the Initial pass and consumes only a downward vertical drag past the touch slop, so a swipe across a tab does not switch the page. The threshold is unchanged (48dp).

`InspectorSwipe.enabled` is an exhaustive `when` over every `InspectorSection`, so a new page must be listed. `TextF33UiStateTest` checks every page. The A16 test `InspectorSwipeLayoutTest` swipes on the real sheet: landing, Transform, Timing, Blending & Opacity, Edit Text (Character, Paragraph, Animate), Edit Solid and Audio, at three points across the header. It checks that each swipe closes, a body swipe does not, and the page tab stays selected.

## 7. Adding a marker must not close the inspector

**Cause.** The playhead marker tap creates a marker and then selects it through `onMarkerSelect`, the same path as tapping a marker. That handler called `dismissInspector()` and cleared property focus. The reason dated from before F3.1: it stopped the focus report reclaiming navigation from the marker. Since F3.1, Prev/Next no longer depends on marker selection, so that reason was obsolete.

**Fix (the cause, not a special case).** `onMarkerSelect` no longer touches the inspector. It seeks, selects the marker, and keeps the layer selection, page and focus. Only a marker owned by a different layer moves the selection there (`MarkerEditing.selectionAfterMarker`). A composition marker keeps the current selection.

Checked for the same side effect:
- **Marker edit and delete** (the playhead tap on an existing marker, and the marker popover) go through `onMarkerAction` and never dismissed the inspector.
- **Markers have no move action.**
- **Ruler and bar marker taps** used the same `onMarkerSelect` and are fixed with it.

The F3.3 state test runs the real playhead rule. It adds the marker, selects it, edits it and deletes it, and checks the selection stays on the layer throughout. `MARKERS.md` is updated.

## 8. Files

- `editor/ContextualNavigation.kt`: the new tier 2; doc comment.
- `ui/inspector/InspectorSwipe.kt` (new): the sheet swipe rule and modifier.
- `ui/inspector/ElementInspectorSheet.kt`: the sheet-level swipe; per-header detectors removed.
- `ui/inspector/InspectorPopover.kt`:
  - `PopoverDismissal` rule extended;
  - `PopoverToggle`, `dismissPopoversOnEmptyTap`;
  - the popover swipe with `PopoverSwipeGuard` / `blocksPopoverSwipe`.
- `ui/inspector/TextAnimatePage.kt`: the tile toggle.
- `ui/inspector/TextInspectorPanel.kt`: the tile toggle (`openPopover(toggle)`); the body's empty-tap dismiss.
- `ui/inspector/TextAnimateState.kt`: add bar visibility.
- `ui/inspector/PropertyLimits.kt`: the `ANIM_TRACKING` slider range.
- `ui/timeline/MarkerPopover.kt`: `MarkerEditing.selectionAfterMarker`.
- `ui/editor/EditorScreen.kt`: `onMarkerSelect` no longer dismisses the inspector.
- **Tests:**
  - New: `TextF33UiStateTest` (8), `TextF33TrackingBoxTest` (3), `InspectorSwipeLayoutTest` (androidTest, 2).
  - Updated: `ContextualNavigationTest` (14), `TextAnimateStateTest`, `TextF32UiStateTest` (the dismiss rule), `TextAnimateLayoutTest` (add bar, popover exits).
- **Docs:** this file, the roadmap, `README.md`, AE parity §16, `TIMELINE_LAYOUT.md`, `MARKERS.md`.

## 9. Verification

- **JVM:** 478 tests, 0 failures.
- **A16 instrumented** (Galaxy A16, installed with `adb install -r`, run with `am instrument`):

| Suite | Result |
|---|---|
| `TextAnimateLayoutTest` | 7 of 7 (now includes the add bar hide/return without a jump, and the same-tile, empty-space, swipe and short-drag popover checks) |
| `InspectorSwipeLayoutTest` | 2 of 2 (the first run failed on a test fixture: a video layer needs an asset reference; fixed with a URI-less reference) |
| `TextInspectorLayoutTest` | 3 of 3 |
| `AnimatorPropertyRenderTest` | 2 of 2 |

- `adb logcat -b crash -d` (read-only): no new app crashes. The only Fluxx entries are the 17:47 "Unsupported transient edit" crashes F3.2 fixed.
- **Not verified on the device by script:** Back closing the popover before the sheet (covered by `BackHandler` order and the state rule; the instrumented harness has no system Back) and the Fonts sub-page swipe (the same header row as the Edit Text tabs, which are tested). §10 items 1 and 7 cover them.

## 10. A16 manual test list

1. Open a slider popover. Close it by: tapping the same tile, tapping empty sheet space, swiping it down, Back. Timeline, play, Prev/Next and the diamond keep working and do not close it.
2. Tracking slider reaches ±1000.
3. Expand an animator bar: "+ Animator" is hidden; collapse all: it returns.
4. Key Random Seed at 0, move the playhead after it, Next: jumps to the next Start/Tracking key, not the layer end.
5. Unkeyed property focused, another property keyed: Prev/Next jumps to that key.
6. Animated tracking on wrapped centred box text spreads from the centre.
7. Swipe down from the top of the sheet works on every page, including Edit Text.
8. With a layer in focus, add a marker: the inspector stays open on the same page.
