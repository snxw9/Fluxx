# Composition and Layer Markers

Implemented in source for Phase 2 C/D, September 29, 2026. Android debug Kotlin compilation subsequently passed during E1b/E1c; marker-specific JVM tests, allocation profiling and phone acceptance remain pending. No build, packaging, installation, or device launch was performed for this change.

## Document and editor contract

- FlatBuffers v7 appends marker vectors to Layer and Composition. The checked-in Kotlin table bindings and codec are updated together. Versions 1–6 load empty marker lists. Unknown future project versions are still rejected; additive schema fields do not imply that older Fluxx releases accept v7 files.
- Marker IDs are positive and unique within their owning scope. Layer copies retain marker IDs; selected layer plus marker ID identifies a selection. Descriptions are single-line and limited to 128 UTF-16 code units. New composition markers default to red (`FFF44336`); layer markers default to orange (`FFFF9800`). Solid-layer creation chooses dark ink on light solids or amber on dark solids. Existing/custom colours are retained; flags and description labels have contrasting outlines/text.
- Markers are sorted by signed stored time. Composition times are nonnegative; layer times are relative to `resolvedKeyframeAnchorUs`. Frame uniqueness is checked in **composition time**, including off-grid anchors. Same-frame additions replace attributes while retaining the existing ID. Changing composition frame rate coalesces newly coincident markers deterministically, keeping the earliest stored marker, in the settings action's undo entry.
- Trim preserves all markers, including those outside the visible clip. Move shifts the anchor with the layer. Split partitions at the split time: strictly earlier markers stay left; markers at/after the split move right and rebase to the right anchor. Duplicate/copy/paste retain marker content and relative timing.
- Add, edit, delete, split, and layer duplication are discrete undoable document changes. Selection is transient and creates no history entry. Undo/redo clear marker selection instead of restoring stale references.

## Geometry and input routing

Updated geometry: see [Timeline Layout](TIMELINE_LAYOUT.md). The ruler and playhead target are now 32dp high; an 8dp gap puts the first row at y=40–68dp, followed by an 8dp inspector separator. Marker/keyframe partitioning inside each 28dp row is unchanged.

| Region | Geometry | Routing |
| --- | --- | --- |
| Stationary playhead target | 48dp wide, y=0–32dp | Tap adds/removes the scoped marker; hold configures |
| Ruler | 32dp high | Nearest composition flag within 18dp horizontally selects/seeks; empty taps seek and clear selection |
| First row | y=40–68dp, after an 8dp spacer | No playhead target overlap |
| Composition glyph | 9×12dp inverted cue flag | Lower ruler band at y=16–28dp; dark 0.5dp outline |
| Layer glyph | 8×7dp downward pennant | Top of row, distinct from centred keyframe diamond |
| Layer marker target | Up to 48dp wide; top 12dp with a focused property | Wins top-zone taps; underlying keyframe retains y=12–28dp; dense targets divide at horizontal midpoints |
| Layer marker without editable keys | 48×28dp | Whole row target |

The ruler, playhead, and layer marker targets override Compose's minimum touch-target expansion to zero, preserving their exact vertical partitions. See the [Compose ViewConfiguration contract](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ViewConfiguration). Keyframe diamonds remain at their existing y=14dp centre. The visual time chip uses 10sp text with a 12sp line height at y=1dp, above the ticks; enlarged-font geometry still needs device review.

Layer selection determines creation scope. An out-of-range playhead (or snapped frame outside the half-open clip interval) produces “Playhead outside selected layer” and creates nothing. Holding on an existing retained marker can still edit/delete it outside trimmed bounds. Negative composition-time markers are preserved but cannot be selected or sought, even when quantization would put them on frame zero. Tapping the playhead on an existing scoped marker removes it in one undo step; tapping an already-parked ruler/layer flag does the same. Configuration stages colour and text locally until Add/Save; the top-right X, outside tap, or Back dismisses without saving. There is no Cancel button. Descriptions render beside flags, eliding before the next marker/clip edge. Existing markers can be deleted from the dialog.

## Colour picker and lookup

The live picker formula was read from `FluxxColorPicker.kt`: `minOf(140.dp, height, (maxWidth - 26.dp - 10.dp - 135.dp).coerceAtLeast(40.dp))`.

The custom Dialog disables platform default width. Its outer width is capped at 360dp, with 12dp margins and 12dp content padding on each side. At the 360dp floor, the Surface is 336dp and the picker receives 312dp. The formula gives a 140dp SV box and a 136dp right column at the explicit 208dp picker height. This is a source geometry check, not an on-device measurement. Keyboard visibility, landscape, enlarged fonts, and narrower windows remain acceptance checks.

Binary search compares frame indices and returns the existing Marker object, avoiding a boxed nullable colour. The selected layer wins over composition colour when both match. Line, time text, and chip outline restore to white off-marker. `FrameRate.nearestFrame` uses exact Long arithmetic when multiplication/addition fit, retaining BigInteger as an extreme-value fallback. Tests check result identity and arithmetic parity; they do not prove zero allocations at runtime.

## Navigation and lifecycle

Marker navigation is available from any frame when no inspector property is focused (or the inspector is closed). Explicit marker selection and parking on a marker are no longer prerequisites, per the subsequent user correction. A selected layer uses its own markers; no selected layer uses composition markers. Retained layer markers outside a trimmed clip remain navigation candidates when their composition time is nonnegative.

Prev/Next excludes the entire current frame, then falls through independently in each direction: scoped markers, focused animated property, selected-layer bounds, global layer/composition bounds. Starting between markers, before the first, or after the last also searches the scoped marker tier. Seeking off the selected marker clears its ID, including fallthrough to a non-marker boundary.

Selection also clears on property focus, clip selection/drag, layer change/deselection, empty timeline/ruler taps, playback start, owner/marker deletion, load, and undo/redo. Marker taps dismiss the inspector and clear property focus so its focus-reporting SideEffect cannot immediately reclaim navigation. Reopening a property restores keyframe editing. Repeated reports of unchanged inspector focus do not dispatch selection actions or cancel gestures.

Keyframe dragging now considers composition and current-layer marker times as magnetic targets. Playhead scrubbing remains direct scroll synchronization. A dedicated scrub-snapping system with detents, hysteresis, and guides is deferred.

## Prepared tests and manual acceptance

Navigation follow-up: user testing established that navigation worked after selecting/parking on markers but skipped them from other frames. The earlier activation gate caused this behaviour. It is now removed; inspector property focus still gives keyframes priority. The current-snapshot transport entry point remains in use. Source changes are complete; phone confirmation is pending.

`parkedMarkersChainThroughTransportWithoutExplicitSelectionInBothScopes` exercises the production editor navigation entry point with both scopes in one project, no marker selection, exact and same-frame-offset playheads, 30 and 30000/1001 frame rates, and a layer anchor distinct from its in-point. Reused callbacks exercise consecutive navigation and scope changes without UI recomposition. Regression coverage now also starts between markers, before the first marker, and after the last, in both scopes. These new tests are prepared, not executed.

- [ ] With multiple composition markers and no layer selected, park on a middle marker without explicit selection; verify both directions land on other composition markers and retain marker colour.
- [ ] Select the marked layer and repeat; verify only its markers are visited, including repeated button presses before/after inspector dismissal.

`MarkersTest.kt` covers sorting/frame uniqueness, off-grid anchors, split partition/rebase, move/trim/copy/paste/undo, fractional-frame lookup and colour priority, navigation fallthrough, reducer selection lifecycle, edit history, v7 round-trip and v1–6 missing vectors, invalid records, frame-rate changes, exact frame arithmetic, and keyframe marker snapping. Tests are prepared but unrun. UI lifecycle wiring requires the following manual checks.

- [ ] Build and run JVM tests in Android Studio.
- [ ] Park a focused first-row keyframe directly under the playhead; tap and long-press drag it without creating a marker.
- [ ] Tap the playhead with/without layer selection; verify scope, same-frame tap-to-remove, undo restoration, and out-of-range feedback.
- [ ] Hold the playhead to add/edit colour and description; verify X/outside dismissal, Delete, undo, and redo.
- [ ] On the 360dp profile, measure the full 140dp SV box; check hex input, sliders, buttons, keyboard, and enlarged text for clipping.
- [ ] At a coincident keyframe and marker, top-zone taps select the marker; focus the property again and verify lower-zone keyframe tap/drag.
- [ ] Tap ruler flags within the 32dp zone; empty ruler and clip taps clear selection as specified.
- [ ] At 30fps and 30000/1001fps, verify frame-quantized tint, selected-layer colour priority, and white restoration off-frame.
- [ ] Chain Prev/Next through markers, then through layer/global boundaries; repeat with a focused property and trimmed hidden markers.
- [ ] Verify selection clears on scrub, property focus, clip drag, playback, deletion, project load, and undo/redo.
- [ ] Split before/on/after markers, including trimmed-out markers; check no duplication and one-step undo.
- [ ] Duplicate/copy/paste, trim, and move layers; verify content-relative marker positions and keyframe snapping.
- [ ] Save/reopen v7 and open existing v1–6 projects; verify colours, descriptions, and signed layer times.
- [ ] Profile lookup allocations during scrubbing; identity assertions alone are not allocation measurements.

Deferred: marker dragging, duration/range markers, MP4 chapter metadata, CSV/EDL/XML import/export, marker multi-selection/batch operations, and magnetic playhead scrubbing.
