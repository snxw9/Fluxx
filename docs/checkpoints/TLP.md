<a id="rec-tlp"></a>

# TLP: timeline productivity (selection, the D-button, the eye as a real off switch, multi-layer and key actions)

Branch `tlp`, created from `master` at `2377a83` (after the graph merge). **Pushed to `origin/tlp`; not merged into `master`.**

**Status:**
- **T0–T6:** done. T6's coverage highlight was skipped at first and then built in TLP.1 Part 6 (the selector coverage box).
- **TLP.1:** done, except Part 3 (key bar placement), which was blocked and is replaced by TLP.2 Part 3.
- **TLP.2:** in progress (see the TLP.2 section at the end).
- **Tests and phone acceptance:** the latest results are in each section's Tests list. Phone acceptance is pending for TLP, TLP.1 and TLP.2 (phone test lists at the end of each section).

## T0. Selection architecture (written before the code)

### Audit: every use of `selectedLayerId` today

| Where | What it does | In selection mode |
|---|---|---|
| `EditorState.selectedLayerId` / `selectedLayer` | The one selected layer, document-adjacent editor state. | Mirrors the **primary** (the last selected), so every single-layer consumer below keeps working on one layer. |
| `EditorReducer` (`Select`, `Add`, `Split`, `ExplodeText`, `AddCompositionMarker`, `AddLayerMarker`, `Remove`, `Seek`, marker deletes) | Sets or clears the selection as a side effect; `Seek` reads the selected layer's markers. | Unchanged. Selection-mode edits only use existing actions, so these rules still apply to the primary. |
| `EditorViewModel` history (`HistoryEntry(project, selectedLayerId)`) | Undo / redo restore the single selection with the document. | Unchanged. The **selection set is not in history**: it lives beside the document as UI state. After undo / redo / any edit it is pruned to layers that still exist. If none remain, selection mode exits. |
| `EditorViewModel.copiedLayer` (the layer clipboard) | One layer, a session clipboard (not document state, not undoable). | Becomes a **list** in timeline order (T4). Paste keeps relative stacking and timing and is one undo. |
| `EditorScreen` inspector (`inspectorSheetVisible`, `isInspectorOpen`, `dismissInspector`) | A timeline tap opens the inspector; preview taps never do. | Entering selection mode **closes** the inspector. In the mode it stays closed: timeline taps add and remove layers instead of opening it. On exit it stays closed. |
| `EditorScreen` fit / eyedropper / Explode / text editing | Guard on `selectedLayerId == layer.id`. | Unreachable (the inspector is closed). |
| `EditorTopBar(selectedLayerId)` | Layer name vs project name, back = deselect. | Replaced by the **selection top bar** while in the mode (T3). Its Back exits the mode, never the project. |
| `CompositionPreview(selectedLayerId)` (on-preview selector, move, text hit-test) | Tap cycles through the layers under the finger; drag moves a text layer. | **Preview selection is inert in the mode.** Taps do not change the set or the primary, and the move gesture is off, so the preview cannot fork the set. The outline overlay shows the primary. |
| `TimelineView` / `TimelineClipRow(isSelected)` | Row border, editable keyframes (with the inspector open), scroll-to-row on open. | The row is **not** highlighted (the D-button shows selection). Keys are display only (the inspector is closed). |
| `TimelineSideControls(selectedId)` | Eye tint. | Replaced by the D-button (T1). |
| `MarkerEditing` / playhead marker tap | Adds or removes a marker on the selected layer, else on the composition. | Acts on the **primary**, unchanged. A marker tap on another layer's flag only seeks and selects the marker; it never changes the set. |
| `ContextualNavigation` (Prev / Next) | The selected layer's boundaries and markers, with keys only when the inspector is open. | The **primary's** boundaries and markers (the inspector is closed, so no keys). |
| Keyframe markers (`LayerKeyMarkers`) | Editable only for the selected row with the inspector open on a focused property. | Display only for every row. |

### Design

- `LayerSelection` (pure, JVM tested, `editor/LayerSelection.kt`) is an **ordered** list of distinct layer ids in **selection order**; `primary` = the last one.
  - Operations: `add` (moves to the end; becomes primary), `remove`, `toggle`, `selectAll` (timeline order, top first), `odd` / `even` (1-based positions counted from the top over all layers in timeline order; replaces the set), `invert`, and `prune(existing ids)`.
- **Selection mode is a separate state** (`EditorViewModel.layerSelection: LayerSelection?`; null = not in the mode). It is not part of `EditorState`, so it is never in undo history and never saved.
  - Outside the mode, nothing changes: single selection, inspector rules and taps behave as before.
- **Entering** (long-press a D-button, or Select all from the 6th icon): close the inspector, `Select(id)` (not recorded in history: `Select` never is), set = [id].
- **Exiting** (Back on the top bar, deselecting the last layer, a tap on empty timeline space): set = null. The single selection stays the old primary but the inspector stays closed.
  - An empty-space tap is one whose only target is the track background: no layer, clip, key, marker or playhead under it. It dispatches nothing, so the playhead, timeline scroll and document are untouched.
  - The ruler keeps its own meaning (seek), as does the playhead (markers).
- Every multi-layer edit (visibility, move together, stagger, sequence, trim / move set, delete, duplicate, paste) is **one `Batch` of existing actions** (one undo).

### Real blockers

- **Moving many layers in one existing gesture:** *not a blocker.*
  - The protocol's preview takes any `EditorAction`, `Batch` included (`EditorViewModel.transientAllowed` already allows `Batch` and `Move`).
  - So a hold-drag on a selected layer previews `Batch(Move(id, start + Δ) for each selected)` from the gesture's base and commits once. Same begin / preview / commit / cancel calls, no protocol change.
  - Δ is clamped so the earliest selected clip stays at ≥ 0, and each start is frame-snapped through `TimelineEdits.move`. Frame-aligned clips therefore keep their exact relative offsets.
- **The D-button's 48dp touch target:** rows are 28dp tall and the row height must not change. The button is 56dp wide (48dp+) and one row tall. A 48dp-tall target would overlap the neighbouring rows' buttons. *Reported, not worked around.*
- **The arrow** is drawn and disabled (TL3). Its callback is wired as bulk expand and does nothing this run.

## T1. The D-button

- **What it is:** the row's existing eye became the D-button (`ui/timeline/DButton.kt`). It is not an extra column.
  - **Shape:** one-sided rounded (flat on the screen edge, a 14dp radius toward the timeline), one row tall (28dp), 56dp wide.
  - **Zones:** the eye on the left (`EYE` / `EYE_OFF`), the new `ARROW` chevron on the right. `InspectorIconFamilyTest` passes.
  - **Fade:** the rounded edge fades with the Graph library icon's scrim stops (0.9 → 0.82 → 0, ending `GraphLayout.SCRIM_EXTENT` past the icons), so the layer shows underneath.
  - **Colour:** neutral (`surfaceVariant` / `onSurfaceVariant`) when not selected, `primary` / `onPrimary` when selected. All from `MaterialTheme.colorScheme`; no hard-coded colour. The row itself is not highlighted in selection mode.
- **Rules (`DButtonRules`, JVM tested):**
  - **Outside the mode:** a tap on the eye toggles that layer (existing action). The arrow is disabled. A long-press (with haptic) enters selection mode with that layer.
  - **In the mode:**
    - an unselected layer: any tap on its D-button, or a tap on its body, adds it;
    - a selected layer, eye: sets every selected layer to the inverse of the tapped layer's state (one `Batch`, one undo);
    - a selected layer, arrow: disabled (wired as bulk expand for TL3);
    - a selected layer, body tap or long-press on its D-button: removes it;
    - a tap on empty track space deselects all and exits (no `Seek`, no scroll, no document change);
    - deselecting the last layer exits.
- **Accessibility:** content descriptions for the eye ("Hide / Show …", "Hide / Show the selected layers", "Add … to the selection") and the arrow ("Expand … (not available yet)", disabled). The D-button carries a selected state ("Selected" / "Not selected") and a long-click action.
- **48dp:** 56dp across; one 28dp row tall (the row height is fixed, see T0 blockers).

## T2. The eye as a real off switch

### Audit (before the change)

| Path | Hidden layers before TLP |
|---|---|
| Draw list (`FramePlan`, used by preview and export) | **Excluded** (`layers.filter { it.visible }`), so no draw, no effect pass and no keyframe evaluation for media / solid / text draws. |
| `FrameEvaluator` | Excluded. |
| Text preparation (`CompositionRenderer.prepareTextProject`) | **Not excluded.** Every text layer was shaped and got native layouts, glyph uploads and character catalogues. The per-frame passes skipped hidden layers (`renderPreparedText`, `applyTextTiers` → base tier, no animator evaluation). |
| Decoders / raster textures | LRU caches. A hidden layer's decoder / texture stayed until evicted by others. No prefetch exists: `frameAt` is only called for draw-list layers, so a hidden video's decoding stops on the next frame. |
| Export (`CompositionSupport.check`, `FramePlan`, `AudioMixer`, the single-video fast path) | Excluded (the fast path requires `visible`). |
| Audio (`AudioMixer`, `AudioMixPolicy` headroom) | Excluded. |
| Preview hit-testing / on-preview selector / overlay | Excluded (`layer.visible && active`). |
| Parenting | None: layers cannot be parented, so the "hidden parent still evaluates" rule does not apply. |

### Change

- **Preparation (the one allowed exception):** `prepareTextProject` prepares visible text layers only (`RenderLayers.preparedText`). A hidden text layer's layouts are released like any unwanted layout. Turning it back on re-prepares it, as any project change does.
- **Delayed release:** `DelayedRelease` (`render/HiddenRelease.kt`, 3 s, clock injectable through `CompositionRenderer(releaseClock = …)`).
  - It closes decoders and raster textures that no visible layer has needed for 3 s. A quick off / on keeps them.
  - It runs between frames (nothing staged; the previous frame's GPU work has completed).
  - A source shared with a visible layer stays wanted. Released resources are re-acquired on demand by the next frame that needs them.
  - **Limit:** the release happens on the next rendered frame after the 3 s, not on a timer. A paused, untouched preview keeps the resource until something renders. `PreviewController` is frozen, so no timer was added there.
- **Hidden layers stay** in the timeline, undo and the saved file, and can be selected and edited (JVM).
- Tests: `HiddenLayerTest`. **Device memory was not measured** (not verified).

## T3. Layer selection bars and move together

- **Top bar** (`SelectionTopBar`, `ui/editor/SelectionBars.kt`) replaces the editor top bar in selection mode. It is accent-filled (`primary` / `onPrimary`), 48dp.
  - **Back:** exits selection mode, never the project. System Back also exits the mode first.
  - **Title:** "N layers selected".
  - **Group:** a non-modal popover with Precompose, Group and mask and Group and exclude, all disabled placeholders.
  - **Select all:** timeline order.
  - **Odd / Even:** a popover with Select Odd (positions 1, 3, 5 …) and Select Even (2, 4, 6 …), counted from the top over all layers. It replaces the selection.
  - **Trash:** deletes the selected layers in one `Batch`, one undo. The single-layer delete has no confirmation, so neither does this.
- **Bottom bar** (`SelectionBottomBar`) is an overlay above the timeline, aligned to the bottom, with the top bar's height and width.
  - **Layout:** it does not resize the timeline. The timeline gets the same extra bottom scroll padding (`TimelineView(extraBottomPadding)`).
  - **Contents:** the primary layer's contextual trim / move set at the playhead (`ClipQuickActions.available`), then Stagger and Sequence (enabled in T4).
  - **Trim / move:** applies to every selected layer where that action is valid in its own context (one `Batch`). A brief "Skipped N layers" toast names the rest.
- **Inspector:** closes on entering selection mode, and timeline taps no longer open it while in the mode. Clip options are ignored in the mode. On exit it stays closed.
- **Move together** (`MultiLayerEdits.moveTogether`): a hold-drag on a selected layer previews `Batch(Move …)` for every selected layer.
  - **Gesture:** the same begin / preview / commit / cancel calls, previews built from the drag's base project. One undo. **No gesture-protocol change.**
  - **Offsets:** the delta is whole frames, clamped so the earliest selected clip stays at ≥ 0. Frame-aligned starts keep exact relative offsets.
  - **Unselected layer:** a hold-drag on one in the mode moves that layer alone and does not change the selection.
- Icons added to the family: `GROUP`, `ODD_EVEN`, `STAGGER`, `SEQUENCE`. Tests: `TlpMultiLayerTest` (JVM).

## T4. Multi-layer actions

- **Stagger** (bottom bar popover): a typed whole number of frames N (no steppers; it applies on IME Done or focus loss, and invalid input is discarded) and a Reverse toggle.
  - **Order:** selection order. Layer 1 stays; layer k starts N frames after layer k−1's start.
  - **Edit:** whole clips move (duration and in-point kept) with existing `Move`s, one `Batch`.
- **Sequence** (popover): end to end in selection order (or reversed). Each layer starts at the previous one's end plus a typed gap in frames (negative = overlap, default 0), never before 0.
  - An unresolved clip duration counts as zero. Same actions, one `Batch`.
- **6th toolbar icon (context-aware)** (`EditorActionBar` → `LayerMenuButton`):
  - **No selection:** Select all, Paste.
  - **One layer (outside the mode):** the existing items, then Paste and Select all (Select all is no longer a disabled placeholder).
  - **Selection mode:** Duplicate; Duplicate ×N (a typed count 1–99 inside the menu); Copy; Paste; Select all; Invert selection.
  - **Select all from outside the mode** enters it.
- **Clipboard:** `layerClipboard` holds a list (timeline order).
  - **Paste** puts the earliest copied layer at the frame-snapped playhead. The others keep their timing offsets, and all keep their relative stacking above the existing layers (the existing paste rule: a fresh id tied on the top z-order).
  - **Duplicate ×N** places each copy directly above its source (the existing duplicate rule).
  - **Undo:** paste, duplicate and ×N are one `Batch` each. Each step is computed on the state after the previous ones, so the batch replays exactly.
- Tests: `TlpMultiLayerTest` (JVM): the stagger / sequence maths, duplicate ×N and multi-layer copy / paste.

## T5. Keyframe selection, bars, copy / paste

- **Scope:** one property's keys, the active property (or animator field) on the open property page, on the selected layer (`KeyframeSelection`, `editor/KeySelection.kt`). UI state only: never in undo history, pruned after every change.
  - Key selection mode is separate from layer selection. It ends when the page, property or layer changes, or the inspector closes.
- **On the timeline:**
  - **Double tap:** selects an editable key. In key selection mode, taps (and double taps) toggle keys and do not seek.
  - **Outside the mode:** a single tap still seeks. The key's tap detector now also listens for a double tap, so a single tap seeks after the double-tap timeout instead of at once (*not verified on a finger*).
  - **Empty tap:** a tap off the keys (clip body or empty track space) deselects all keys and exits, with no seek, no scroll and no selection change.
  - **Drawing:** selected keys get an accent outline. Deselecting the last key exits.
- **Top bar** (accent): Back, "N keyframes selected", Select all, Odd / Even (time order within the property), Trash (one undo).
- **Bottom bar** (same overlay rules as T3):
  - **Easing:** Hold, Linear, Easy Ease, and More (Ease In, Ease Out, Bounce, Elastic, then the pinned Graph curves). Each sets every selected key's departing segment with one `SetKeyEasing` (the selected key times) in one `Batch`. Disabled on hold-only tracks.
  - **Stagger** (typed N ≥ 1) and **Sequence** (one frame apart).
  - **Layout:** with the inspector open the bar overlays the inspector's bottom 48dp. *Layout to check on the phone.*
- **Sequence / Stagger (keys)** (`KeyEdits.pack`):
  - **Placement:** the selected keys in time order, N frames apart (N = 1 is Sequence), anchored on the first selected key, which stays. Unselected keys are not in the plan, and the selection follows its keys.
  - **Actions:** frame grid only. Existing `MoveKeyframe` (transform / text tracks) and the animator's `Key MOVE`, one `Batch`.
  - **Collision (exactly what `MoveKeyframe` does):** it drops any key already at the target time. A moved key that lands on an UNSELECTED key's frame replaces it, and that unselected key is deleted. Example (JVM): selected 0, 10, 20 staggered by 20 → the key moved to 40 replaces the unselected 40.
  - **Ordering:** left-moving keys first (earliest first), then right-moving keys (latest first), so selected keys never collide mid-batch.
  - **X/Y keys** move whole (both axes).
- **Copy keys / Paste keys** (6th icon whenever a property page is open):
  - **Copy:** stores the selected keys' values, curves and exact relative timing.
  - **Paste:** puts the first copied key at the playhead in the target layer's own local time, keeping exact relative µs. The playhead may be outside the clip. Existing keys at those times are replaced (the set-key actions' rule). Text and animator times are frame-snapped, as their actions do.
  - **Targets:** the same property kind only (otherwise disabled). Colour and Source Text keys are not copyable (no numeric value channels).
  - **Undo:** one `Batch`.
- Tests: `TlpKeyTest` (JVM).

## T6. Small leftovers

- **Eyedropper in the animator colour popover: done.**
  - **Record** (roadmap, TEXT F3 follow-up): "wire the existing eyedropper to Fill/Stroke colour animator properties".
  - **Behaviour, matching the Edit Text colour picker:** the popover closes; the same preview sampler (`sampleColour`, the `onTextSampleColour` path in `EditorScreen`) runs; the release writes one animator colour edit (`TextAnimatorEdits.set`, one undo). A release outside the composition or Back changes nothing.
- **On-preview selector coverage highlight: SKIPPED (ambiguous record).** The record is one line: "show which characters a selector covers on the preview, now that the Range strip is gone". Questions for you:
  1. **When:** while the Animate tab is open, only while a selector (or its Range field) is focused, or always?
  2. **Partial coverage:** a selector covers characters by an amount (0–100%, with Shape / Smoothness / Ease). Should the highlight show that amount (opacity or fill level), or only covered / not covered?
  3. **Style:** a per-character box, an underline, or a tint over the glyphs? Monochrome like the rest of the UI?
  4. **Several selectors** (Add / Subtract / Intersect modes): the combined coverage, or the focused selector's alone?
- Excluded as asked: the landscape layout and the Interface size setting.

## Tests

- **JVM** (Gradle 9.7.1): **621 / 621**, one full run, 46 s wall. That is the 600 of Stage 1 plus 21 new: `TlpSelectionTest` 5, `TlpMultiLayerTest` 6, `HiddenLayerTest` 4, `TlpKeyTest` 6.
- **A16, `TlpUiTest`** (new; the real `EditorScreen` with a fixture composition):
  - **Run 1 (screen off):** 48 s wall, 3 / 3 timed out in setup (`host()` waiting for the preview surface). Environmental; no TLP assertion ran.
  - **Run 2 (screen on):** 19 s wall, 2 / 3 passed. `selectionModeEntryTapRulesAndExit` failed at the width check: bottom bar 376.2dp vs top bar 384dp.
  - **Cause:** `SelectionBottomBar` applied its `testTag` after `padding(horizontal = 4.dp)`, so the tagged node was the padded inner area (384 − 2 × 11 px). The bar itself already drew full width. Fix: the tag moved before the padding (as in the top bar).
  - **Re-run of the failed method only:** passed, 14 s wall. No flaky items.
- **What `TlpUiTest` covers:**
  - D-button geometry (56 × 28dp on the left edge), states and fade stops; outside the mode the eye toggles one layer.
  - Long-press entry; add by D-button and by body; bulk eye (one undo); remove by body and by long-press; the empty-space exit without a seek; selection not in history.
  - The bottom bar never moves or resizes the timeline, and matches the top bar's width with 48dp height.
  - Odd / Even / Select all, and Back.
- **Crash buffer:** empty (read with `-d`).

## Not verified

- TLP device behaviour beyond what `TlpUiTest` covers (T4 stagger / sequence UI, T5 key bars and copy / paste, T6 eyedropper) was not run on the phone.
- Haptics, TalkBack, and real-finger feel: D-button long-press, double tap on keys, the single-tap seek delay that the double-tap detector adds.
- Device memory and decoder / texture release timing for hidden layers.
- The key bottom bar overlaying the inspector's bottom 48dp (layout to check on the phone).
- The animator-colour eyedropper on the phone.

## Phone test list

1. Long-press a layer's D-button: selection mode, the accent bars appear, and the inspector closes. Tap other D-buttons and bodies to add; tap a selected body to remove; tap empty track space to exit (the playhead must not move).
2. With several selected, tap a selected eye: all hide or show together; one Undo restores.
3. Hold-drag a selected clip: all selected clips move together by whole frames; one Undo.
4. Bottom bar: Trim / Move with mixed layers ("Skipped N layers"); Stagger 5 and Sequence (gap 0, 10, −15; Reverse).
5. Top bar: Odd / Even, Select all, Trash (one Undo), Back.
6. 6th icon: no selection (Select all, Paste); selection mode (Duplicate, Duplicate ×3, Copy, Paste keeps timing and stacking, Invert).
7. Hide a video layer: playback stays smooth; toggle it quickly off and on (no stutter); export without it.
8. Property page open: double-tap keys to select, tap to toggle, Select all / Odd / Even / Trash, Hold / Linear / Easy Ease / More, Stagger 4, Sequence; Copy keys, move the playhead, Paste keys.
9. Animate tab → a Fill colour animator → colour popover → eyedropper.

---

# TLP.1: fixes from phone testing, key hold-drag, selector coverage box

Branch `tlp`, continued from `83cb803`. One commit per part, then the device test and this record.

**Status:** Parts 1, 2, 4, 5 and 6 done. **Part 3 stopped on a blocker** (below); the key bottom bar is unchanged.

| Part | Status | Commit |
|---|---|---|
| 1. Layer Stagger / Sequence Reverse defaults | Done | `a9f17e7` |
| 2. Key tap rules (timeline) | Done | `9946dde` |
| 3. Key bottom bar placement | **Blocked, not changed** | — |
| 4. Copy / Paste keys in the property ⋮ | Done | `325826c` |
| 5. Hold-drag moves selected keys together | Done | `9d1b51d` |
| 6. Selector coverage box | Done (one deviation: the colour) | `941fc14` |

## Part 1: Stagger / Sequence defaults
- `MultiLayerEdits.STAGGER_REVERSE_DEFAULT = true`, `SEQUENCE_REVERSE_DEFAULT = false`; the `stagger` / `sequence` default arguments use them.
- Each popover remembers its Reverse inside its own content, which leaves composition when the popover closes, so every open starts from the default. Nothing is persisted, and the two popovers no longer share one toggle.
- Key Stagger / Sequence: unchanged (no Reverse).
- `TlpMultiLayerTest`: the two forward-order stagger calls now pass `reverse = false`; a new test asserts the defaults.

## Part 2: Key tap rules
- `KeyTapRules` (pure): outside key selection mode a tap does **nothing** and a double tap **selects** (enters the mode); in the mode a tap or a double tap **toggles**. No rule seeks. The T5 seek on a single tap is gone.
- The tap is still consumed by the key's own detector, so it never reaches the clip.
- Kept: in the mode a single tap still waits out the double-tap window before toggling, because a double tap must toggle once, not twice. Outside the mode the wait has no visible effect (the tap does nothing).
- Editable keys carry the test tag `timeline-key-<layer>-<local time>`.

## Part 3: Key bottom bar placement: BLOCKED
With the inspector open, the space between the preview and the sheet is the action bar (40dp), then the timeline viewport: ruler 32dp, gap 8dp, **the single visible track row 28dp**, gap 8dp (`TimelineInspectorAnchorHeight`). A 48dp bar sitting directly above the sheet covers the bottom 4dp of the ruler, the gap, **the whole key row** and the gap below it.
- The keys being selected would be hidden and untappable, and so would hold-drag (Part 5) and the empty-space tap that exits the mode.
- Extra bottom scroll padding cannot help: with the inspector open the timeline viewport is exactly one row tall (`TimelineTracksBottom`), so there is nowhere to scroll the row to.

Nothing was changed: the key bar is still where T5 put it (under the sheet). Options for Snxw:
- **(a)** The bar replaces the 40dp editor action bar while key selection is active. This is the only strip that is free; the bar would be 40dp, or 48dp overlapping 8dp of the preview's bottom edge.
- **(b)** The bar overlays the top 48dp of the inspector sheet (the sheet height unchanged), covering the page header.
- **(c)** The sheet keeps its height but the timeline anchor gains 48dp (a layout change to the preview / inspector budget).

## Part 4: Copy keys / Paste keys
- **Where the property menu lives (audit):** every animated property page already uses the shared rail `InspectorUtilityRail`. Its ⋮ is the "Property options" menu (`inspector-rail-menu`), which also holds Reset and the easing rows.
  - It is used by Transform (Position, Scale, Rotation), Opacity, and the text tabs, including Animate (animator fields).
  - The Graph page has its own rail and ⋮ (`GraphLeftRail`), which is not touched; Graph keeps Copy / Paste graph.
- **New:**
  - `KeyClipboardHost` (a composition local, provided by EditorScreen) adds **Copy keys** / **Paste keys** to that ⋮, for the rail's own property (its `graphTarget`).
  - Copy takes the key selection when it is on that layer and property, otherwise **every key** of the property (`KeyEdits.copyScope`). It is disabled for colour and text source, and when there are no keys.
  - Paste is enabled for a same-kind clipboard, with or without a key selection. T5 semantics are unchanged (one `Batch`, one undo).
  - The text tab rail passes no property while a partial text selection is the scope (existing rule), so the two items are hidden there.
- **Removed:** the 6th icon's `keyMenu` (`key-menu-copy` / `key-menu-paste`). With a property page open the 6th icon shows its normal one-layer menu again; the layer-mode and no-selection menus are unchanged.

## Part 5: Hold-drag moves the selected keys
- `KeyEdits.moveTogether`:
  - The raw drag (local µs) is turned into whole frames at the earliest selected key, and every selected key moves that many frames on the grid (relative offsets kept in frames).
  - The earliest key is clamped to local time ≥ 0.
  - The result is one `Batch` of the existing `MoveKeyframe` / animator `Key MOVE`. All moves go the same way: earliest first when moving left, latest first when moving right, so selected keys never collide mid-batch. 2-D keys move whole.
  - Grid, not a constant µs shift: at 30 fps a constant shift leaves keys 1µs off the frame they land on, so a key would sit beside an unselected key instead of replacing it. A JVM test caught this.
- **Replacement (reported):** a moved key that lands on an UNSELECTED key's frame replaces it; this is `MoveKeyframe`'s existing rule.
- **Timeline gesture:**
  - A long-press drag on a selected key (in key mode) carries the whole selection. It is previewed from the drag's base project through the existing `previewGesture`, and committed once (one undo).
  - A zero-frame drag previews an empty `Batch` (no change, no undo entry).
  - An unselected key moves alone as before (with its snapping) and does not change the selection.
  - The selection follows its keys: the row hands the old → new map to EditorScreen just before the commit, and EditorScreen re-selects after `commitGesture` (whose sync prunes the moved-away times).
- The group drag does not snap to the playhead or markers (it moves in whole frames only); the single-key drag keeps its snapping.

## Part 6: Selector coverage box
- **Coverage audit:**
  - The per-character coverage is computed in `PreparedSelectorEngine.coverage()` (private, cached per selector) and combined across selectors by mode in `evaluate()`.
  - `PreparedTextAnimation` owns the engines and the `SelectorMembership`, which `evaluate()` updates for wrapped lines.
  - Nothing exposed one selector's own coverage. The evaluator contract did not need to change: the selector engine is reusable as is.
- **What was added (read only):**
  - `PreparedTextAnimation.selectorCoverage(animator, selectorId, time)` builds a separate `PreparedSelectorEngine` for that ONE selector, with its mode forced to Add, over the evaluated membership, and returns its per-cluster coverage.
  - `clusterBoxes()` returns a copy of the evaluated per-cluster visual boxes.
  - Both are pure additions to the file (14 lines). The renderer never calls them; preparation, evaluation and the evaluator are unchanged. A JVM test checks that reading coverage leaves the renderer's modifier records identical.
- **Box rule** (`SelectorCoverageBox`, pure):
  - A cluster counts at |coverage| ≥ `THRESHOLD` (0.05).
  - The box is the union of the cluster boxes from the first to the last covered cluster, so non-contiguous coverage gives one box.
  - Nothing covered gives no box, and the preview shows the layer box.
  - `TextLayoutMetrics.selectorBox` returns it in the same layout space as `bounds()`.
- **Preview:**
  - `PreviewLayerOverlay` gets the focused Animate-tab address. EditorScreen passes `focusedAnimatorTrack` when it has a `selectorId` (the selector or any of its Range fields), which is only reported on the Animate tab with the inspector open.
  - The box is computed off the UI thread; the last box is kept while the next one computes (no flicker while scrubbing) and drawn only for the current layer and selector.
  - It replaces the layer outline, with the same stroke (2.5dp black under 1dp colour) and the same matrix. The resize handles stay on the layer box.
  - Hidden or inactive layers show nothing, and gestures are unchanged.
  - The overlay exposes `stateDescription` "Selector box" / "Layer box" (tag `preview-layer-overlay`).
- **Deviation, the colour:** the theme's `colorScheme.error` is off-white (`TextPrimary`): the scheme is monochrome. A box from that token would match the white layer box, and making the token red would recolour every error text in the app. So the red is ONE named token, `SelectorCoverageColour` (`0xFFFF453A`), in `ui/theme/Color.kt`, like the existing `MissingMediaWarning` exception. If `error` becomes red later, that one line can point at it.

## Tests (TLP.1)
- **JVM:** **637 / 637**, one full run, 44 s wall. That is the 621 of TLP plus 16 new:

  | Class | New tests |
  |---|---|
  | `TlpMultiLayerTest` | 1 |
  | `TlpKeyTapTest` | 2 |
  | `TlpKeyMenuTest` | 2 |
  | `TlpKeyGroupMoveTest` | 4 |
  | `SelectorCoverageBoxTest` | 7 |

- **Device keep-awake:** `stay_on_while_plugged_in` read as **0**, set with `svc power stayon true` (it read 15 after), and **restored to 0** after the device runs (confirmed). The phone was on wireless debugging and not plugged in (`mStayOn=false`), so stay-awake had no effect during the runs.
- **Screen checks:** before each run `dumpsys power` showed `mWakefulness=Awake` and `dumpsys window` showed `isKeyguardShowing=false`. No class was skipped.
- **Crash buffer:** cleared once before the first run (allowed this pass); empty after all runs.
- **A16, `TlpOneUiTest`** (new; the real `EditorScreen` and the real preview overlay with fixture compositions; Compose test input only):
  - **Run 1:** 49 s, 2 / 5 passed (Stagger / Sequence defaults; selector box on focus / blur).
    - The 3 key tests failed in setup: the test tapped the clip but never opened the Transform page, so no property was focused and no key was editable. Fixed in the test (it now opens Transform, as the Graph tests do).
  - **Re-run of the 3 failed methods:** 29 s, 2 / 3 passed (hold-drag; ⋮ Copy / Paste keys).
    - `keyTaps…` failed again on a single key tap in key mode. A key's single tap fires after the double-tap timeout, which only passes on the Compose test clock (`Thread.sleep` does not advance it). The same tap passed in the hold-drag test because the next touch resolved it.
    - A repeat of the one method (11 s) failed the same way, so it is **not flaky**: a test timing bug. Fixed in the test (`mainClock.advanceTimeBy` after a key tap).
  - **Re-run of that method:** passed, 13 s. **All 5 methods have passed.** No product code changed between the runs.
- **A16, `TlpUiTest`** (regression for the selection bars, which Part 1 touched): 3 / 3, 28 s.
- **Not written:** the Part 3 device test (the part is blocked).
- **Flaky:** none.

## Not verified (TLP.1)
- Real-finger feel: double tap on keys, the hold-drag of several keys, the key tap toggle delay in the mode. Haptics; TalkBack (the new ⋮ items, the overlay's state).
- The red box's look on the phone (colour, stroke over text); the box on wrapped / multi-line and character-animated text; wiggly selectors (covered by the same engine, not tested on the device).
- Copy / Paste keys on the text tabs and animator fields from the ⋮ (JVM-tested through `KeyEdits`; the device test covers Transform Position only).
- Part 3 (blocked).

## Phone test list (TLP.1)
1. Select 3 layers → Stagger: Reverse is on; turn it off, close, reopen: on again. Sequence: Reverse off.
2. Property page open, not in key mode: single-tap a key → nothing (no seek, the clip does not react). Double-tap a key → it is selected, the playhead does not move.
3. In key mode: tap keys to add / remove, double tap toggles once; tap empty track → exits, no seek.
4. Select two keys, hold one and drag: both move together by whole frames; drag left past 0: the earliest stops at 0; one Undo puts them back. Drag an unselected key: it moves alone, the selection stays.
5. Property ⋮: Copy keys with no selection copies all keys; move the playhead, Paste keys. The 6th icon no longer lists key items.
6. Text layer → Animate → focus a selector (or its Start / End): the red box wraps the covered characters and follows Start / End / Offset / Shape; focus a property field or leave the tab: the white layer box returns.
