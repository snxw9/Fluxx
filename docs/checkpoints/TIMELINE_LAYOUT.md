# Timeline, preview and editor workspace

Implemented in source, consolidating the September 2026 workspace follow-ups. Android debug Kotlin/native compilation later passed during E1b/E1c, and the user confirmed A16 E1a render/playback/export smoke. The detailed gesture, resize and UI checks below remain pending unless individually confirmed. Current geometry supersedes the earlier 22dp ruler / 54dp inspector anchor.

## Vertical regions

| Region | Height | Timeline-relative position |
| --- | --- | --- |
| Ruler and time indicator | 32dp | 0–32dp |
| Ruler-to-track spacer | 8dp | 32–40dp |
| First/selected track | 28dp (26dp clip) | 40–68dp |
| Inspector separator | 8dp | 68–76dp |
| Inspector | Remaining reserved sheet height | Starts at 76dp |

The spacer is a sibling layout region, not padding inside the clip. While the inspector is open, the track viewport and playhead are clipped at 68dp so neither later rows nor the line bleed into the separator. Selection scrolls the active row into that viewport; closing restores the earlier vertical list position. Eye controls, reorder grips, placement measurements, and drag origins share the 40dp track origin.

The marker target occupies the ruler's 32dp height and ends 8dp before the first row. Its exact touch bounds still disable vertical touch expansion. First-row keyframes keep their complete 28dp row; marker/keyframe top/bottom routing within the row is unchanged.

## Preview expansion and compact controls

Both preview modes reserve `40dp action bar + 76dp timeline anchor`. Expanded preview uses a 268dp inspector budget; collapsed preview uses 328dp (scaled for fonts). This gives the preview 12dp more space at normal font scale and moves the entire action-bar/timeline region down by that amount, without adding padding inside tracks. The expand button stays in the preview corner. Inspector visibility does not alter preview height, and all reserved heights clamp to the available workspace on small windows.

The action bar is 40dp tall, with 20dp vectors and 48dp-wide controls. The View menu uses the same height and a smaller ellipsis. Compared with the previous 48dp bar and 54dp anchor, the net additional vertical cost is 14dp. The clip itself stays 26dp high. Empty automatic projects disable playback.

## Ruler and project extent

Ticks now rise from a dedicated baseline in the lower ruler band. The time chip and marker descriptions use the upper band. Ticks have no duration-number labels. Major/minor strides are integer frame counts, chosen for readable pixel spacing; all x coordinates come from `FrameRate.frameTimeUs(frame)`. At 30000/1001fps, frame 30 is at 1,001,000µs, not at an invented one-second tick. The time chip uses the existing non-drop-frame minutes:seconds:frames convention, plus the saved display origin. Tick drawing starts at the visible frame range and does not iterate from zero for every viewport.

There is no artificial 60-second extent or 100dp minimum timeline width. New projects are empty at time zero. Creation no longer offers a Seconds field and always chooses automatic duration. `Composition.durationUs == null` persists that mode using the existing codec representation; cached `resolvedDurationUs` derives the maximum layer end, or zero for no layers. Import/add, move, trim, deletion, undo/redo, and reload preserve this mode. Markers do not create media duration.

Existing explicitly sized projects retain their stored duration. Composition settings show Start and End timecodes (`MM:SS:FF`) and an “End follows layers” option. Start defaults to zero and offsets displayed timecodes only; layer/marker positions, playback, and export remain composition-relative. End initially reflects the current resolved timeline end plus the display origin. Editing End sets a fixed duration of End minus Start; leaving automatic end enabled preserves content-driven duration. The origin is persisted in additive FlatBuffers v8; v1–7 files default to zero. One Framerate field accepts integer/decimal values through 120 fps, with standard fractional aliases such as 29.97 preserving 30000/1001 internally. Playback, export eligibility, encoder/mux duration, audio mixing/cache identity, and project metadata use the resolved length. Unresolved media still needs resolution/relinking before export.

## Solid clip colour

Solid clip bodies use their stored ARGB colour, composited over the timeline background for transparency. Labels and selection outlines choose black or white for contrast; a faint outline keeps black/white solids identifiable. Changing the solid colour updates its timeline appearance.

## Prepared checks

`TimelineDurationAndRulerTest` covers zero-length creation, add/move/trim/delete/undo duration changes, automatic-mode persistence, legacy explicit durations, and tick stride/frame-grid correctness at 24, 25, 30, 60 and 30000/1001fps. Existing navigation and timeline geometry tests were updated for zero-length empty projects and the 76dp inspector anchor. Tests are prepared, not executed.

- [ ] Build and run JVM tests in Android Studio.
- [ ] Create/open an empty project: no invented duration, no scrolling through blank seconds, zero timecode, disabled Play.
- [ ] Import a short clip, then longer/overlapping clips; move, trim, delete the last layer, undo/redo and save/reopen. Check timeline, playback end, metadata and export length agree.
- [ ] Inspect ticks at integer and fractional rates near frame/second boundaries. Check ticks have no duration labels and the time chip/marker descriptions remain legible.
- [ ] Toggle preview expansion with the inspector open and closed. Verify active-row alignment and both gaps; preview must stay stable when only opening/closing the inspector.
- [ ] Select and reorder first/middle/last layers. Verify eye/grip alignment and restored scroll position.
- [ ] Tap and drag a first-row keyframe directly beneath the playhead; verify no marker interception. Check ruler marker creation/editing and same-row keyframe/marker routing.
- [ ] On 360dp, check all seven compact action targets, menu opening, enlarged fonts, landscape, and the preview expansion control.
- [ ] Check black, white, saturated, and translucent solids: body hue, text, selected outline and colour edits.

## Pinch zoom and settings follow-up

Two fingers over the timeline zoom its scale between 12 and 2400 dp/second around the fixed playhead. The two-pointer gesture consumes input before ruler/clip handlers, retains ownership until both fingers lift, and suppresses scroll-to-time feedback while pinching. Ordinary one-finger scrolling and long-press clip editing remain separate. Numeric pixel feedback from programmatic playhead positioning is ignored within one pixel. Phone gesture verification is pending.

`CompositionTimecodeTest` prepares coverage for parsing/formatting, fractional and 120fps clocks, new creation rates, display-origin persistence, undo/redo, unchanged content timing, and v1–7 origin defaults. No tests/builds were run.

- [ ] Pinch in/out over the ruler, clips, and an empty timeline; verify the playhead time stays fixed and no marker is accidentally toggled.
- [ ] Check single-finger scrub, clip/keyframe drag, inspector docking and preview expansion after zoom.
- [ ] Edit Start, save/reopen and undo/redo: labels shift, content stays in place. Check automatic End follows imported/moved layers; manual End sets the fixed duration.
- [ ] Create projects at 12/14/18/20/120fps; verify settings, tick spacing and project reopen.

## Timeline gestures and inspector viewport

- **Density:** Rows remain 28dp with no additional row gap (28dp pitch), retaining the centred 26dp clip body and 2dp between clip bodies. The September 29 layout adds distinct ruler/track/inspector spacing and moves the derived inspector anchor to 76dp; see [Timeline Layout](TIMELINE_LAYOUT.md).
- **Phone feedback (2026-09-26):** User confirmed reordering works and reported the other cluster/context-menu changes satisfactory, but requested tighter spacing and reported fast drags dropping short. Follow-up source changes use absolute finger position against current logical viewport slots, refresh the destination on release, and use a 100ms sibling placement animation. Destination calculation does not read animated sibling positions. These follow-up changes await phone verification.
- **Inspector viewport:** Capture the first visible index and offset once before alignment. Changing selection while the inspector is open retains that original snapshot. Back/close/outside dismissal restores it (clamped if layers were removed). Vertical drag dismissal records an explicit skip flag so a completed drag cannot trigger a late snap-back; hierarchy dragging also skips restoration.

- A normal horizontal swipe over a clip or marker scrubs immediately through the enclosing timeline scroll container. Moving a clip or keyframe requires a long press followed by a drag.
- Clip movement accumulates horizontal displacement from the gesture's original timing, includes edge auto-scroll, snaps to frames, and uses the existing begin/preview/commit/cancel protocol. Selecting for a drag closes the inspector so it does not obscure the working area.
- Keyframe markers are 10dp only for the selected layer's active property. Other markers shrink to 6dp and dim to 32% opacity. Inactive and deselected markers have no tap/drag handlers; their clip can still be selected or moved. Active markers retain 48dp-wide, 28dp-high hit areas within their row and move only their owning property.
- The right-edge hierarchy grip is always visible: a transparent 14×8dp vector inside a 48dp-wide, 28dp-high touch region. It dims to monochrome secondary text at rest and highlights while held. A stationary gesture gutter retains pointer ownership while rows reorder or leave the viewport; normal swipes still scroll.
- Long-press hierarchy drags preview absolute `ReorderLayer` actions against the original order. Non-dragged rows use `Modifier.animateItem()`; the dragged row bypasses placement animation and translates to its continuous viewport position. Translation compensates for actual list placement and edge scrolling rather than assuming a fixed slot offset. Side controls follow animated clip placement. Release commits one undo entry; cancellation restores the original project.
- Eye toggles are transparent and unblurred, centred in a 36×28dp row region. No background fill, backdrop recording, `GraphicsLayer` replay or GPU blur pass remains. The 26dp clip body and monochrome eye vector remain crisp.
- Empty timelines have navigable time space. Seek is no longer clamped to composition duration, and paused/empty preview callbacks preserve the requested cursor position. Playback/export duration remains the composition's duration. Timeline space grows ahead of the cursor; moving a clip still expands composition duration through the existing reducer.

## Timeline gesture verification

### Fast reorder crash follow-up — 2026-09-26

User reports a crash during rapid right-handle reordering. No crash trace is available; the precise device exception is unconfirmed. Source review identified an unsafe late-preview path that threw when its gesture had already ended. Ended previews now no-op, timeline callbacks carry editor gesture IDs, and callbacks for old sessions cannot preview, commit or cancel a newer session. The reorder edge-scroll job is cancelled explicitly before release/cancel/disposal, checks cancellation after suspending, and the gutter's ordinary scroll handler is disabled during a held reorder.

Regression tests prepare cancellation/late-preview, immediate restart, old-session isolation, duplicate placement and one-step undo scenarios. No builds/tests were run. Phone acceptance: rapidly reverse the right-hand drag, release near both scroll edges, immediately re-grab, and interrupt/cancel; confirm no crash, correct destination and one undo per drop. If a crash persists, capture the Logcat exception/native trace to identify the remaining cause.

`TimelineInteractionTest` covers free seeking, movement independent of playhead, hierarchy ordering with tied z-orders, cancellation, single-entry undo/redo and invalid reorder targets. Added coverage checks continuous rendered displacement across slot transitions, edge-scroll compensation, bounds clamping and the current 76dp inspector anchor. Fast multi-row jumps, viewport shifts, immediate release and single-step undo also have prepared regression coverage. These tests have not been executed.

Phone acceptance:

1. Check the landing trim glyphs and close spacing. Open every page, verify the trim row disappears, then use Back to return.
2. Swipe directly across animated clips and diamonds without holding: the cursor must scrub smoothly. Test empty projects and space beyond the last clip too.
3. Open each transform sub-mode and Opacity; verify only that property's markers are prominent. Return to landing, close the sheet, then deselect: all markers must dim/shrink and stop intercepting taps/drags.
4. Hold a clip away from an active diamond and drag left/right, including near viewport edges. Verify cumulative movement, frame snapping, release and one-step undo.
5. Hold an active diamond and drag across other keys; verify the gesture remains attached and only that property changes.
6. Verify clips remain visible beneath the compact eye surface. Hold beside a row at the fixed right edge, move it up/down and across a vertical viewport edge, and verify preview stacking, release, undo/redo and cancellation.
7. Repeat at both preview sizes with 8+ layers. Confirm compact rows, unblurred eyes, always-visible grips and reliable 48dp-wide keyframe/handle targets; inspect trim/split actions for regressions.
8. Watch the dragged row follow the finger continuously while siblings and their side controls glide. Reverse direction, cross several rows quickly, drag beyond top/bottom and exercise edge auto-scroll; release, undo/redo and interrupt/cancel.
9. Scroll to layers 7–8, open the inspector, and dismiss via Back, close or empty space: the pre-open index and offset must return without changing hierarchy. Change selected layers within the same inspector session and repeat.
10. Open again and manually scroll vertically: the inspector must dismiss and preserve the dragged viewport even if the finger lifts immediately. Repeat with a hierarchy drag and after removing layers.

## Preview quality and presentation

- Slot 7 is View / Preview, containing Layer Controls, Preview Resolution, Grid Overlay, Camera and Zoom. Its badge reflects Full, Half, Third or Quarter. No eighth toolbar button is added.
- Quality is editor-session state. `PreviewController.setPreviewResolution` pauses playback, publishes the new quality and invalidates the paused frame. The worker and paused eyedropper use the same quality.
- `CompositionRenderer.renderPreview` accepts quality only on a surface-backed renderer. It caches the even, minimum-2px target dimensions until quality or composition dimensions change. This avoids per-frame dimension-pair allocation.
- Scaled dimensions reach only offscreen target allocation through `RenderBridge.begin`. Every layer matrix still receives the document's full composition dimensions and the layer's reference canvas. No `SetComposition`, layer edit, undo entry or persistence change occurs.
- Native presentation now receives the original composition dimensions separately from target dimensions. This prevents small aspect-ratio changes from independent even rounding (for example 1081×1921). Normalized pixel sampling uses the resulting presentation rectangle and actual target dimensions.
- Even dimensions are a preview policy, not a claim that Vulkan RGBA framebuffers require chroma alignment. Full preview also rounds odd dimensions to even; headless `render` always uses the original document dimensions.
- Target recreation runs on the owning renderer worker and waits for device idle before freeing GPU resources. Pausing playback alone is not a GPU completion guarantee.
- Export continues to call `CompositionRenderer.render` on its own headless instance. It cannot call `renderPreview`; all preview state is outside the project/export contract.

## View controls and zoom

- Expansion is a 48×48dp control in the viewport's bottom-right corner with a 32dp dark surface and 18dp expand/collapse vector. The old divider action is removed; the divider is decorative. Expansion is hidden during eyedropper interaction.
- The seven toolbar controls are now 48dp wide × 40dp tall with 20dp vectors and 8dp horizontal padding. At 360dp: 336dp controls + 16dp padding leave 8dp for six gaps (~1.33dp each). Insets are applied by the editor scaffold. See [Timeline Layout](TIMELINE_LAYOUT.md) for the shared preview/inspector budget; phone touch accuracy remains pending.
- Bounding boxes and eight selection handle indicators derive from the selected layer's actual `LayerGeometry.matrix`, including source rotation/SAR, evaluated animation, flips and spatial anchors. These indicators do not add canvas transform gestures. Missing media geometry suppresses bounds/handles rather than guessing a rectangle.
- Passive anchor visibility follows `showAnchorPoints`. Active Anchor Edit temporarily overrides a disabled preference, without changing it. Eyedropper mode hides composition overlays.
- Grid is a Compose-only 8×8 grid with dashed third divisions; it never enters Vulkan output or export.
- Motion Paths, Control Points, X-Ray Mode and Camera entries (Active Camera, Top, Front) are visible disabled placeholders.
- Fit computes the current viewport-to-composition pixel ratio. 100% means one composition pixel per physical viewport pixel, and differs from Fit. Zoom In/Out update the scale; Fit and 100% reset pan. Pan state and inverse coordinate mapping are present; freeform **preview** pinch/pan gestures remain deferred. Timeline pinch zoom is implemented separately below.
- A centred Compose transform scales the full-workspace surface and composition overlays together without changing the surface's layout size. Surface clipping is also set explicitly. The eyedropper inversely maps zoom/pan before native sampling. SurfaceView zoom, cropping and overlay registration require device review.

## Contextual Previous / Next

The current contract is owned by [Markers](MARKERS.md#navigation-and-lifecycle): scoped markers are reachable from any frame unless an open inspector has property focus. Focused animated keys, selected-layer bounds and global bounds follow with independent directional fallthrough. The selected/parked-marker prerequisite was removed after user feedback. Key timestamps use the temporal anchor; invalid negative/overflowing composition times are excluded. Next never seeks backwards from beyond the final bound.

## Preview and toolbar verification

`ContextualNavigationTest` covers focused-property priority, isolated fallthrough, inspector/static-property gating, global boundaries, signed anchors, negative keys and overflow. `PreviewSettingsTest` covers quality dimensions, Fit/100% behavior, zoom/pan inverse mapping and temporary anchor suppression override. Tests have not run.

- [ ] Galaxy A16 360dp: all seven icons fit with no clipping; **each icon reliably registers as itself, not its neighbor under a real thumb**. Test left/right edge icons and both preview heights.
- [ ] Switch Full → Half → Third → Quarter while paused, playing and repeatedly in quick succession. Playback pauses on a quality change; the same frame redraws without GPU failure or tearing.
- [ ] Test 1081×1921 and portrait/landscape/square compositions: presentation bounds and shape proportions remain stable at every quality.
- [ ] Confirm quality/view toggles create no undo entry and do not alter composition dimensions or layer reference canvases; save/reopen the project to check values.
- [ ] Export with Quarter selected and inspect output metadata: native composition dimensions, no grid/bounds/anchor overlays, no preview zoom.
- [ ] Expand/collapse from the viewport corner. Confirm no duplicate toolbar/divider expansion action and hide the button while eyedropping.
- [ ] Toggle bounds/handles independently on solid, rotated video, flipped/scaled images and animated layers. Check alignment at Fit and 100%, including non-centre anchors and decoder crop metadata.
- [ ] Turn Anchor Points off, enter Anchor Edit, then leave it: active indicator appears only during editing; preference remains off.
- [ ] Check Grid, menu checkmarks, resolution badge and inert placeholders. Check menus with enlarged text.
- [ ] Zoom in/out, 100% and Fit while paused/playing at both preview sizes. Pixels must stay inside the workspace and overlays must remain aligned. Confirm eyedropper samples the touched pixel at each zoom and quality; letterbox samples are rejected.
- [ ] Navigate with no selection, a selected layer, and each focused animated property. At the first/last key, fall through to layer bounds, then global bounds. Repeat on split/trimmed layers with signed key times and beyond composition end.

## Composition resize and surface ownership

Current source uses a full-workspace SurfaceView, a composition overlay/mask, and native centred aspect-fit presentation. Composition dimensions change the offscreen canvas and projection, not the SurfaceView's shape. Preview quality changes only target resolution. Shared `LayerGeometry` uses the layer's stored reference canvas for natural fit and offsets; resizing the composition changes crop/projection while preserving source proportions, transforms, timing and reference dimensions. Solids retain reference dimensions. New layers capture their reference canvas; legacy layers capture the pre-edit canvas on the first resize. The additive v4 reference fields round-trip through the current v8 codec and the same geometry feeds preview/export.

`PreviewController` publishes an immutable SurfaceSize and acknowledges exactly the dimensions it passed to native resize. Changes arriving during resize remain pending. Requested layout dimensions versus actual swapchain extent are used to compensate compositor buffer stretching. Out-of-date acquire/present gets one bounded immediate retry. Native target and surface recreation remain renderer-worker operations; resource release waits for GPU completion.

### Resize audit evidence retained

| Finding | Resolution / verification limit |
| --- | --- |
| Surface-size acknowledgement could record dimensions never applied | Immutable captured size fixes the source race; user reported this alone did not fix flattening |
| Recomputing fit from the live canvas changed existing footage on resize | Stored reference canvas added; user confirmed edge stretching improved but aspect-switch flattening remained |
| Composition-sized SurfaceView could independently reshape the buffer | Surface moved to full-workspace layout; final phone acceptance was not recorded |
| Actual swapchain extent can lag requested UI size | Presentation compensates for display mapping; arithmetic checks covered 16 layout/aspect combinations within half a buffer pixel, not a device proof |

The 2026-09-11/27 audit was source/arithmetic review. Later A16 render/playback/export smoke is not exhaustive resize acceptance. Retest 9:16 -> 16:9 -> square -> custom compositions, paused and playing, with rotated/SAR footage, rapid changes, expand/collapse, save/reopen and export. Unclipped footage must retain its aspect ratio and circles remain round. Composition-only edits should not emit surface-resize logs unless the workspace size changed; use requested canvas/target/surface/actual extent logs to investigate any failure.
