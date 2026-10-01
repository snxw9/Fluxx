# Phase 2 Step D: transform keyframes

Status: implemented/reviewed in source, September 16, 2026. No build, JVM test execution, instrumented test, screenshot, or phone verification was performed in this pass.

## Approved architecture: Option B

- One Kotlin `KeyframeEvaluator` handles position XY, scale XY, continuous rotation and opacity. Easy Ease uses P1=(0.33,0), P2=(0.67,1), a private 1024-entry float LUT and linear interpolation. LUT initialization uses Newton iteration with a bisection fallback, outside `CompositionRenderer.render()`.
- Each renderer owns a reusable six-float evaluation buffer. `CompositionLayer.evaluatedTransformInto()` fills it; the existing scalar `LayerGeometry.matrix()` overload consumes the values. Animation evaluation creates no Pair, Transform, list or array per layer/frame. This is a claim about this evaluation path, not a measured allocation guarantee for the entire existing playback/UI pipeline.
- Inspector display uses the same evaluator through an allocating UI convenience method. Preview and general export both use `CompositionRenderer`; animated single videos are excluded from the static Media3 fast path. Device preview/export parity remains to be verified.
- FlatBuffers stores the four optional animation tracks on Layer. There is no LayerAnimationSync table, animation JNI API, native animation cache, second evaluator or device-only parity test.

## State and persistence contracts

- `layer.transform` remains authoritative for every unanimated channel. The existing SetTransform reducer is unchanged. Track static slots are persistence/fallback values, not a second source for live static edits.
- The layer buffer evaluator overlays only animated channels. Static position/scale/opacity edits therefore remain visible when another channel is animated.
- Keys now use signed anchor-relative microseconds (`playheadUs - resolvedKeyframeAnchorUs`). Trim/Extend preserve the resolved origin; Move and Paste translate it. Split rebases all keys against the right segment's new origin, retaining negative keys and outgoing easing. See [Trim, Extend and Split](ELEMENT_INSPECTOR.md#7-trim-extend-and-split), which supersedes the original Step D clip-start-relative convention.
- Reducer insertion/replacement and movement keep timestamps sorted and unique. Moving onto an existing timestamp replaces the destination key on affected properties; a combined timeline diamond moves all properties at that timestamp together. Decoder input is sorted and duplicate times use the last serialized entry.
- Easing belongs to the departing key. Wire values explicitly map Linear=0 and Easy Ease=1, independently of Kotlin enum order. Existing-key numeric/gesture edits retain its easing.
- Empty tracks use a static fallback; removing the final key freezes its value. Disabling animation freezes its evaluated value and clears its keys. Reset restores the selected property's default and removes its animation.
- Legacy v1-v4 files remain readable; absent tracks are static and absent anchors resolve to clip start. Signed anchors and keys were introduced in v5; the current writer uses v8, and older readers reject unsupported versions rather than silently discard anchor semantics. Legacy transform slots still hold the evaluated value at the clip in-point; track static slots retain the static base.
- Rotation remains unbounded, including negative turns. Opacity evaluation clamps to [0,1]. Position stays normalized to the reference canvas; inspector fields convert it to composition pixels. Scale remains a multiplicative XY pair.

## Editing and timeline

- Inspector diamonds enable/add/remove keys for the current transform sub-mode or opacity. Filled means a key at the exact local playhead; outline means animated between keys; dim outline means static. Graph icons remain disabled.
- Inspector controls show evaluated values during scrubbing/playback. `EditorViewModel.editTransform()` routes animated edits into keyframe actions, and static edits into unchanged SetTransform actions.
- Drags begin once, preview against their immutable gesture base, and commit one undo step. Cancellation discards previews. A Batch action groups multi-property edits and reset-all into one undo step. Reset-all excludes opacity and its keys.
- Active-property diamonds on the selected layer are 10dp with 48dp-wide by 28dp-high row-bounded targets over 26dp clip pills. Other diamonds shrink to 6dp at 32% opacity and have no pointer handlers. Hold an active marker to drag it; ordinary horizontal swipes scrub. See [interaction revision](TIMELINE_LAYOUT.md#timeline-gestures-and-inspector-viewport).
- Marker pointer identities stay stable during transient movement. Magnetic snapping chooses the nearest playhead, clip edge, adjacent key or scoped marker within 12dp; otherwise it frame-snaps. Moving keys exclude their old timestamp from adjacent targets. Keys outside the clip range remain stored but hidden.

## Regression coverage (written, not executed)

`KeyframeAnimationTest` covers LUT values against an independent double-precision inverse across 1,021 samples, boundary and single-key behavior, XY evaluation, multi-turn rotation, mixed static/animated values, easing round-trip, v1-v4 legacy decoding, sorted insertion, removal/movement, enable/disable, cancellation/undo/redo, duplicate/paste, reset isolation, non-destructive trimming, and magnetic snapping.

## Android Studio / phone acceptance

1. Run the JVM tests, including KeyframeAnimationTest and LayeredEditingTest.
2. Animate each property. Scrub/play and check both inspector values and layer pixels. Change a static channel while another animates; save/reopen and check both.
3. Animate 0 to 720 degrees and 0 to -720 degrees. Confirm continuous turns and editable turns/remainder text.
4. Drag a control repeatedly, cancel once, then commit once. One Undo must remove the entire committed drag. Reset-all must leave opacity animation untouched.
5. Drag timeline keys across other keys and near clip edges/playhead at several zoom levels. Confirm no interrupted drag, correct snapping, and one-step Undo, including merged timestamps.
6. Duplicate, copy/paste later, trim shorter, and re-extend. Check local timing and restored hidden keys.
7. Export an animated single video and compare its movement/opacity to preview. Check the static single-video path still works.
8. Check inspector fit and timeline touch targets with expanded/collapsed preview, including dense neighboring keys.
