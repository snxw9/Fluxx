<a id="rec-tl3"></a>

# TL3: the layer twirl-down

Branch `tlp`, after TLP.3 (A1 `d2b7157`, A2 `342d0f1`, A3 `d592890`). The audit (B0) below was written before any TL3 code.

## B0. Audit

### 1. Timeline layout
- **Structure (`TimelineView`):**
  - One `horizontalScroll` Row holds `[half-viewport spacer | content column | half-viewport spacer]`. The content column is the ruler, an 8dp gap, then the track viewport.
  - The track viewport is a `LazyColumn` (`verticalScrollState`) with one item per layer (key = layer id), front to back.
  - Every row (`TimelineClipRow`) is the full composition width and 28dp (`TRACK_ROW_HEIGHT_DP`). The clip body is offset by `startUs × pxPerUs`.
  - Vertical virtualisation is the LazyColumn's own: only visible rows are composed. Horizontally there is none: a row composes its clip body, its thumbnails and every key and marker of the layer.
- **D-button and reorder grip (`TimelineSideControls`):** a separate stationary overlay. For each `listState.layoutInfo.visibleItemsInfo` entry it places the D-button and grip at the item's offset (or the reorder's `placedTops`). The grip column is also the vertical scroll handle and the long-press reorder gesture. The row pitch is assumed to be 28dp (`rowPx`).
- **Hit-testing (`TimelineHits`):**
  - `clipBox` gives every clip a tap / hold-drag target of at least 48dp.
  - `spans` splits the 48dp key targets of neighbouring editable keys at their midpoints.
  - Layer markers draw last, in an exact top zone (`ExactTimelineTargets` removes the minimum touch-target padding).
- **Inspector open:**
  - The `BoxWithConstraints` around the tracks switches from `fillMaxSize` to `height(TimelineTracksBottom)`, which is the ruler, the gap and ONE row. So the viewport is one row tall.
  - A `LaunchedEffect(selectedLayerId, inspectorVisible, order)` saves the scroll position once per inspector session and calls `scrollToItem(index)` on the selected layer, which puts it at the top. It restores the saved position when the inspector closes.
  - The sheet is an overlay (`AnimatedVisibility` slide + fade) of constant height. `TimelineInspectorAnchorHeight` reserves the ruler, the gap, one row and 8dp above it, so the preview never moves.
  - The bottom content padding `maxHeight − TimelineTracksBottom` lets even the last layer reach the top.

### 2. Layer types and keyframeable properties
`LayerType` is VIDEO, IMAGE, SOLID or TEXT. There are no shape or audio-only layers.

| Type | Keyframeable (keys stored) |
|---|---|
| VIDEO, IMAGE, SOLID | Position (2-D), Scale (2-D), Rotation, Opacity (`animTransform`) |
| TEXT | The four above, plus the text properties: Source Text (string keys), Font size, Fill colour (colour keys), Stroke colour (colour keys), Stroke width, Stroke over fill (hold), Tracking, Leading, Baseline shift, Ligatures (hold). Plus every animator field: the animator's Enabled and Grouping tracks, each override property (scalar, 2-D, or colour for Fill / Stroke colour), and each range selector's fields (scalar or hold) and Seed (integer keys). |

- **Not keyframeable:** the solid's colour (`solidColorArgb`, static, `SetColor`), audio volume (`audioGain`, static) and mute (`muted`, static).

### 3. Audio
- **Which layers have audio:** only VIDEO layers (`FramePlan`: `gain = audioGain × headroom` when VIDEO and not muted), and only when the media has an audio track (probed when mixing). No audio-only layer type exists.
- **`AudioMixer` time mapping:** `source = sourceInUs + (t − startUs)` for t in `[startUs, min(endUs, duration))`.
  - Speed is 1×; there is no speed, loop or time-remap field in the model.
  - Gain is static `audioGain` (and the headroom); `muted` silences the layer; hidden (eye-off) layers are not mixed.
- **Decoding:** a private `MediaExtractor` + `MediaCodec` loop inside `AudioMixer.mixLayer`, writing 48 kHz stereo floats to a disk scratch file. There is no reusable decoder utility and no waveform or peak pipeline anywhere. TL3 adds a separate peak decoder that follows the same pattern; `AudioMixer` is not touched.

### 4. Key-set actions and easing
- `SetKeyframe` / `SetKeyframe2D` / `SetColourKeyframe` and the text property path (`TextPropertyActions`) all store through `rekey1D` / `rekey2D` / `rekeyColour`. When the passed easing preset EQUALS the existing key's preset, they `copy(value = …)` and keep the key's full curve (custom bezier parameters included).
- The animator `Key SET` (`TextAnimatorActions.editKey`) uses `rekey1D` with the edit's easing; `TextAnimatorEdits.set` defaults it to the key's existing easing.
- **Result:** writing a value with the key's own easing preserves its easing, so B3's value editing can proceed (not a blocker). GRAPH G2's value graph already relies on this.

### 5. Colour keys
- **Colour-keyed properties:** text Fill colour and Stroke colour (`AnimatableColour` / `ColourKeyframe`), and animator Fill / Stroke colour overrides (also `AnimatableColour`).
- **Interpolation (`interpolateColour`):**
  - Linear-light, premultiplied alpha. Alpha is interpolated linearly; each channel is `lin(a)·αa·(1−t) + lin(b)·αb·t`, then un-premultiplied and re-encoded to sRGB 8-bit.
  - It is LINEAR in the eased progress `t` (not hue-based). So splitting a segment's easing exactly reproduces every colour, apart from the new key's 8-bit storage.
- **Easing:**
  - Linear and Easy Ease use their fast path (Easy Ease is a 1024-entry LUT of the `.33,0,.67,1` bezier).
  - Custom / Bounce / Elastic use `KeyframeEvaluator.ease(…)` clamped to [0, 1] (colour never overshoots).
  - Hold steps.

### 6. Scroll cost baseline (before TL3)
- **Horizontal scroll:** `TimelineView` reads `horizontalScrollState.value` in composition (`scrollOffsetPx` for every row, and the ruler's visible range). So TimelineView recomposes every scroll frame, and so does every visible row whose parameters include fresh lambdas, which is all of them.
- **Vertical scroll:** `TimelineSideControls` reads `listState.layoutInfo` in composition, so it recomposes every scroll frame and places one D-button and grip per visible row. The LazyColumn lays out the visible rows.
- **Keys:** composed per key, each with two `pointerInput`s, for every key of a visible layer (not only those on screen).
- **TL3's rule:** the new rows add no per-frame composition. Their content is drawn in `Canvas` / `drawBehind`, reading the scroll offset and animation progress only in the draw phase. Paths, gradients and waveform geometry are cached and keyed on their inputs. Key hit targets in twirl rows are one `pointerInput` per row (a hit-test over the sorted key xs), not one composable per key.

## Design
- **Items:** the timeline is one LazyColumn of `TwirlItem`s: each layer, followed (when it is expanded) by its rows.
  - Keys: layer ids for layers (the reorder and the side controls keep working), `tw|<layer>|<target>` strings for rows.
  - `TwirlRows.offsets` / `indexAt` give the cumulative-y offset table, for binary-search lookups. The LazyColumn's own measuring keeps layout, scrolling and hit-testing O(visible rows).
- **UI state, never in undo history or saved:**
  - `EditorViewModel.expandedLayers`, pruned with the layers and cleared on Load.
  - `KeySelectionSet`: tracks of (layer, target, local times).
  - The ONE twirl popover (`TwirlPopover`, in TimelineView).
- **Pure, JVM-tested logic:** `TwirlRows`, `TwirlExpansion`, `KeySelectionSet` / `KeySelectionRules` / `KeyGestureRules` / `MultiKeyEdits`, `ColourBar` / `EasingSplit` / `ColourKeyAdd`, and `PeakPyramid` / `PeakBuilder` / `PeakCachePolicy` / `WaveformMapping`.
- **Edits:** existing actions only, composed in `Batch`: `SetKeyframe(2D)`, `SetColourKeyframe`, animator `Key SET` / `MOVE` / `REMOVE`, `MoveKeyframe`, `RemoveKeyframe`, `SetKeyEasing`. No new actions; schema v13.

## Parts

| Part | Status | Commit |
|---|---|---|
| B0. Audit | Done (above) | `78109cf` |
| B1. Expand / collapse, rows, D name column | Done | `78109cf` |
| B2. One shared transition with the sheet | Done | `f200ce2` |
| B3. Keys in twirl rows, value popover, multi-track ops | Done | `e758991` |
| B4. Colour bar visualiser | Done | `a4b28cc` |
| B5. Audio waveform row | Done (layer-bar waveform skipped: no audio-only layers) | `bf3ad59` |

### B1
- **The arrow (D-button):**
  - It toggles the layer.
  - In selection mode, on a selected layer, it sets every selected layer to the inverse of the tapped layer's state (`DEffect.EXPAND`).
  - On an unselected layer in selection mode it still adds that layer (TLP T1).
  - The chevron turns `>` → `v` (graphicsLayer rotation).
- **On expand:** the layer scrolls (animated) to the top. The viewport is not resized, and the layers below are pushed down.
- **Rows:** one 28dp row per keyed property, in inspector order: Position, Scale, Rotation, Opacity; then for text: Source Text, Font size, Fill colour, Stroke colour, Stroke width, Stroke over fill, Tracking, Leading, Baseline shift, Ligatures; then each animator (On, Grouping, its properties, its selectors' fields).
  - The last key deleted: the row leaves (animated).
  - Nothing keyed: one dim "No animated properties" row.
  - Audio: B5.
- **D growth:**
  - **Width:** 56 → **104dp** (`TwirlStyle.D_EXPANDED_DP`), animated in the layout phase.
  - **Name column:** below the D, one name cell per row: the property's inspector icon (14dp) and its name at 10sp, ellipsised.
  - **Font scale:** the width is fixed at 104dp at every font scale; the 10sp text scales and ellipsises. The width was not measured on the device.
  - **Fill:** the D's neutral `surfaceVariant`, or `primary` / `onPrimary` when the layer is selected, with the D's fade toward the timeline.
  - **Shape:** the bracket is flat on the left edge; the last cell rounds toward the timeline.
- **Faded area:** `surfaceVariant` × **`TwirlStyle.AREA_ALPHA = 0.22`** (ONE constant), with a 10dp soft bottom edge. It spans the full viewport width and is drawn in the draw phase only.
- **Name tap:** selects every key of that property and enters key mode. Tapping again with all of them selected deselects them; none left exits.
- **Name long-press:** opens that property's inspector page (collapsing the twirl-downs).
  - Transform (in its Position / Scale / Rotation mode) or Opacity open directly.
  - Text properties and animator fields open Edit Text on its last tab (deviation: not the exact field).
- **Playhead:** the line runs through the expanded rows.
- **Shared mapping:** the rows share the timeline's scroll, zoom and time → x mapping (`time × pxPerUs`).
- **Deviations:**
  - Selector Seed tracks (integer keys) are not listed.
  - Layer reordering (the grip) is paused while any twirl-down is open, because the reorder maths assumes one 28dp row per layer.

### B2
- **One spec:** `TL3Motion` (**280ms, FastOutSlowIn**) is used by everything that moves, and each starts from the same state change in the same frame:
  - the sheet's slide;
  - the timeline viewport (now full height and clipped to one row while the sheet is open, the clip read in the draw phase);
  - the rows appearing, leaving and pushing down (animateItem);
  - the D's width and the chevron.
- **Arrow with the sheet open:** the sheet closes while the layer expands, with no scroll restore.
- **Layer tap / name long-press:** every twirl-down collapses while the sheet opens. Opening the inspector always collapses them; expansion is not kept.
- **Deviation:** these are separate animations on one shared spec, not one progress value (the prompt allowed either).

### B3
- **Gestures:** `KeyGestureRules` and one detector (`detectKeyGestures`) replace the TLP.1 / T5 double-tap rules everywhere. `KeyTapRules` and its tests were removed.
  - **Twirl rows, outside key mode:** a tap opens the value popover at once; a hold released without moving selects the key and enters key mode; a hold and drag moves the key alone, inside its clip.
  - **Twirl rows, in key mode:** a tap toggles a key; a hold release opens the popover (on an unselected key, for that key alone); a hold and drag on a selected key moves the selection (every track and layer, whole frames, clamped so no track's earliest key goes below local 0).
  - **Bar keys with the sheet open:** a tap does nothing outside key mode; a hold release selects; in key mode a tap toggles. A hold release on a selected key does nothing; on an UNSELECTED key it adds the key (unspecified case: chosen).
  - **A drag that moved zero frames** counts as a hold release.
- **Value popover (twirl rows only):**
  - One at a time, non-modal, anchored at the key, in `PropertyDisplay` units with the typed-entry field: one field per axis (X and Y for 2-D).
  - Writes go through the existing key-set actions with each key's own easing (so the curve is kept), at the key's time (no seek), in one Batch.
  - **Bulk:** the same value goes to every selected key of the same property (T5 paste kind) on any layer, per axis, with a "Changes N keyframes on M layers" note.
  - **Colour keys:** the animator colour popover (`FluxxColourPicker` COMPACT, modal, as on the Animate page), previewed through the gesture protocol.
  - **No popover:** Source Text, or hold / choice fields (Stroke over fill, Ligatures, selector modes, Enabled / Grouping). Deviation: they have no typed number.
- **Invariants (`KeySelectionRules`):**
  - Opening the inspector on X keeps X's keys.
  - A property page narrows the selection to its track.
  - Collapsing a twirl-down drops that layer's keys unless it is the inspected layer.
  - Closing the sheet with no twirl-down open ends key mode.
  - Otherwise the selection survives.
  - A layer-body tap in key mode with twirl-downs open (and no sheet) opens the inspector and keeps that layer's keys. With the sheet open, the T5 rule (exit key mode) is unchanged.
- **Key bar placement:**
  - Sheet open: the header strip (TLP.2) and now also the landing page's name row (same height).
  - No sheet: a bottom overlay (`key-bottom-bar`, the layer bar's rules: 48dp, extra bottom scroll padding, no resize).
  - The overlay slides on `TL3Motion` while the sheet's header bar slides in with the sheet.
- **Key top bar:** "N keyframes selected · M layers".
- **Multi-track operations:**
  - Easing / More apply to every continuous selected track.
  - Stagger / Sequence work per track, each anchored on its own first selected key.
  - Odd / Even count within each track.
  - Select all covers the displayed rows (the page's track, or the expanded layers' rows).
  - Trash deletes every selected key.
  - The drag snaps with every moving key as a candidate (composition time) and shows the readouts.
  - Each is one undo.
- **Copy / paste:** unchanged, in the property ⋮ menu.
- **Deviation:** twirl-row drags have no edge auto-scroll.

### B4
- **Colour rows:** a 16dp gradient bar between consecutive keys, from stops sampled by `AnimatableColour.evaluate` (the evaluator's linear-light premultiplied interpolation and the segment's easing).
  - **Stops:** adaptive, one per 6px, 2–64 per segment.
  - **Shapes:** Hold is a hard step; alpha < 1 draws over a faint checkerboard; nothing is drawn outside the keys or the clip.
  - **Cache:** `ColourBar.Cache`, keyed by (the two keys: value, time and curve; the stop count), holding the brush (drawn scaled from a 0..1 gradient). Stale entries are dropped after an edit. The bar is never rebuilt per scroll frame.
- **Tap on the bar between keys:** opens the ONE twirl popover: a swatch, `#AARRGGBB`, and `KEY_ADD` (new icon, in the monochrome family). The playhead does not move.
- **"Add keyframe here":**
  - It adds the sampled colour at the tapped frame and splits the easing exactly (de Casteljau at x = progress, halves normalised): left half on the left key, right half on the new key.
  - Linear → Linear + Linear; Hold → Hold + Hold; Easy Ease is split as its `.33,0,.67,1` bezier.
  - One Batch (key set + two `SetKeyEasing`), one undo.
  - **Measured max difference (JVM, ~1ms sampling): Linear 1/255, Easy Ease 1/255, Custom 1/255, Hold 0/255** (the 1 is the new key's 8-bit storage).
  - Bounce / Elastic segments: disabled, with "Not available for Bounce / Elastic segments".
  - **Not exact:** custom curves that overshoot y in [0, 1] are not exactly reproducible, because the evaluator clamps the colour progress per segment. Untested here.

### B5
- **`AudioPeakProvider`** (`media/`, outside the render path):
  - It decodes the first audio track off the main thread, in mono, into min / max peaks (256-sample buckets, ×4 per level).
  - **Cache:** under `cacheDir/tl3-peaks`, keyed by (uri SHA-1, size, lastModified, format version), LRU at 64MB.
  - **When it runs:** only for a shown row of a visible (eye-on) layer. It is cancelled when the row leaves mid-decode.
- **Audio row:** 56dp, first in the twirl-down; its name cell is the audio icon and "Audio". The waveform:
  - is aligned to the clip through the mixer's mapping (start, in-point, 1×; the model has no speed or loops, so nothing is left out);
  - covers the visible range only, at a fixed scale;
  - is dimmed when the layer is muted or at volume 0;
  - shows a static placeholder while the peaks compute.
- **No audio track:** a source found without one drops its row.
- **Layer-bar waveform:** skipped. No audio-only layer type exists yet.

## Performance
- **No per-frame composition from TL3:**
  - The rows draw keys, bars and waveforms in `drawBehind`, reading the horizontal scroll (`VisibleRange`) and the list layout only in the draw phase.
  - Keys are binary-searched to the visible range; each row has one `pointerInput`, not one composable per key.
  - Reused `Path`s and cached brushes; no per-frame allocation in the key, bar or waveform loops.
  - The D width and chevron animate in the layout / graphics-layer phases.
- **Unchanged baseline:** TimelineView itself still recomposes per horizontal scroll frame (`scrollOffsetPx`). That was the case before TL3 and was not changed.
- **New cost:** with the sheet open, the timeline is now full height under the sheet (clipped), so a few more layer rows are composed there.

## Tests
- **JVM: 667 tests, one full run, 43 s wall.**
  - 666 passed. `PreparedTextAnimationTest.hostPreparedEvaluationAllocatesZeroAfterWarmup` (a host-JVM allocation counter, untouched here) failed once with 224 bytes.
  - Re-run alone 3× (11 s, 9 s, 9 s): passed each time. **Flaky.**
  - **New:** `KeyCurvePopoverTest` 2, `DragSnapTest` +3, `Tl3TwirlTest` 4, `Tl3KeysTest` 5, `Tl3ColourBarTest` 4, `Tl3AudioPeaksTest` 3.
  - **Updated:** `TlpSelectionTest` (the arrow expands), `TlpKeyTapTest` (KeyGestureRules replace the double-tap rules).
  - `InspectorIconFamilyTest` is green with `KEY_ADD`.
- **Device (A16):** NOT RUN. `adb devices` listed nothing: the phone (wireless debugging) was not connected.
  - The keep-awake settings were not read or changed (nothing to restore).
  - Pending, each once, one class per invocation: `Tl3UiTest` (new, 7 tests: expand / D growth / rows, sheet transitions, twirl gestures with one popover and key mode surviving the transition, bulk edit, name tap, colour bar add, audio row from a generated 1 s WAV fixture in the app cache) and the regressions `TlpUiTest`, `TlpOneUiTest`, `TlpTwoUiTest`.
  - All compile.

## Not verified
- Scroll smoothness and the transition animation's feel (280ms).
- Real-finger gestures (tap vs hold vs drag thresholds) and haptics.
- TalkBack on the arrow, name cells, rows and popovers.
- The faded area's strength on the phone (`AREA_ALPHA` 0.22).
- The D's 104dp name column at large font scales.
- The colour bar on the phone (banding, checkerboard).
- The waveform on real media (AAC / video sources, long files, decode time, cache growth).
- Edit Text long-press landing (opens Edit Text, not the exact field).
- All device classes above.

## Phone test list (TL3)
1. Layer with Position and Opacity keys → tap the D's arrow:
   - The chevron turns down and the D widens into a name column over two rows (Position, Opacity) on a soft tinted area.
   - The layer scrolls to the top; layers below are pushed down. Scale (no keys) has no row.
2. With the inspector sheet open, tap another layer's arrow: the sheet slides down while that layer expands, at one speed. Tap a layer's clip: every twirl-down collapses while the sheet slides up.
3. In a row, tap a key: its value popover opens (one field, or X / Y); type a value: the key changes, the playhead does not move, one undo. Tap another key: the popover moves there (only one).
4. Hold a key and release: key mode, with the bottom key bar. Tap other keys: they toggle at once (no delay). Hold and drag a selected key: every selected key moves, snapping, with the readouts.
5. Select Position keys on two expanded layers, plus an Opacity key, then hold-release a selected Position key: "Changes 2 keyframes on 2 layers". Type X: both Position keys change X; Y and Opacity stay.
6. Key mode on, tap the layer: the sheet opens with the key bar in the landing name row; the keys stay selected. Open Transform: the selection narrows to Position.
7. Tap a property name in the D: all its keys are selected; tap again: deselected. Long-press it: its inspector page opens.
8. Text layer with Fill colour keys → expand: a gradient bar between the keys. Tap the bar between keys: a swatch, a hex and the add icon. Add: a key appears and the bar looks the same. On a Bounce segment the add is greyed with a note.
9. Video with sound → expand: an Audio row first, a static placeholder, then the waveform aligned to the clip. Trim the in-point: it shifts. Mute: it dims. Eye off: nothing is computed.
10. Scroll the timeline quickly with several layers expanded: it stays smooth.
