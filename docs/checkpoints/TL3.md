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
