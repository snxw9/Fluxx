<a id="rec-tlp"></a>

# TLP: timeline productivity (selection, the D-button, the eye as a real off switch, multi-layer and key actions)

Local branch `tlp`, created from `master` at `2377a83` (after the graph merge). **Not pushed, not merged.**

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
