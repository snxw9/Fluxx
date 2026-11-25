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
