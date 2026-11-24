# Element Inspector & Layer Actions

> **TEXT UI 8 (2026-11-18):** section-page controls no longer fill the height. They are fixed `SectionLayout` constants that do not depend on the sheet height:
>
> - pad 128;
> - dial 144;
> - ruler 28 + 4 + 48;
> - value boxes 40;
> - slider rows 20 + 48;
> - mode tiles 40.
>
> They grow only in text rows with font scale. Each control group is centred vertically. See the Edit Text overhaul record in `TEXT.md`, section TEXT UI 8.

> **TEXT UI 7 (2026-11-18):** section pages now have one header row (← | tabs; no name/duplicate/delete, which stay on the landing grid), controls are sized from `SectionLayout`, tiles show keyframe badges and Edit Solid uses the FULL shared colour picker. See the Edit Text overhaul record in `TEXT.md`, section TEXT UI 7.

**Edit Text → Animate (TEXT F3.1, 2026-11-22):** implemented in source; A16 instrumented layout and render tests pass; phone acceptance done (F3.1–F3.3, user-confirmed 2026-11-22). Same 268/328dp sheet and the same fixed rail. **The Animate body scrolls vertically** (the one deviation from the no-scroll rule). The page has:

- a centred add-animator control over a persistent faded Animate glyph;
- one collapsible bar per animator (grip, chevron, name, duplicate, eye), with only one expanded at a time. Inside are:
  - **Property** [+] with Transform / Colour / Text / Advanced group bars of icon tiles;
  - **Selector** [+] with Range/Wiggly selector bars. Each holds its primary tiles plus a collapsed Advanced group.
  - **Grouping**: the Anchor point grouping tile.
- Grip long-press-drag reorders with the timeline hierarchy rule (one undo). Swiping a bar deletes it (undo restores). Long-pressing an animator bar offers Rename.
- Position, Rotation and Scale use slider popovers by default, or the Transform tab's pad, dial and rulers via rail ⋮ "Controls: …" (an app preference).

The focused field drives the rail diamond, tile badges, timeline focus and Prev/Next. [Layout figures, hidden fields, limits and A16 list](TEXT.md#rec-text-f3-1); F3 history in [TEXT F3](TEXT.md#rec-text-f3).

**Edit Text rework (2026-11-18):** Implemented in source, device-unverified. Edit Text is a fixed, non-scrolling sheet: every common control sits on the Character (4 rows) or Paragraph (3 rows) face; the only overlays are Case / Justify / Box width choice rows, exact numeric entry, Fonts and the colour picker, all inside the sheet, which is docked below the preview, so none hides the edited layer. While Edit Text is open the sheet uses one fixed height (344dp at font scale 1; labelled 3-column reflow at larger scales) and ignores Preview Expansion. **Back:** the editor's top-left arrow dispatches system Back while Edit Text is open; Back first leaves the innermost transient state (Explode dialog, numeric/source/colour draft, confirmation, base-alignment row, choice row, Fonts) and otherwise returns directly to this inspector's landing grid, clearing any text selection. The check mark keeps its Done behaviour. The header ⋮ holds exactly Explode Text, Reset {tab} style, Manual kerning and Relative size; the status line and Hold label are gone (the rail diamond carries key state). [Final inventory](TEXT.md#11-rework-2026-11-18-final-surface-and-inventory), [gate and A16 user-run list](TEXT.md#rework-commit-2-menu-back-status-and-icons).

**Explode Text** (header ⋮): its compact dialog contains the five split modes, Skip spaces, Keep original, Group/Each pivot, a freeze/drop summary, Prepare/Confirm and explicit confirmation above 200 pieces. It uses existing pages and undo transactions; no sub-screen or frozen layout schema. [Current gate, limits and exact user-run list](TEXT.md#rec-text-fix-explode-t5).

Earlier Edit Text stages (E3, T1–T4 pages, FIX preview selection) are recorded in the [text checkpoint](TEXT.md#rec-text-layer) and [FIX/EXPLODE/T5](TEXT.md#rec-text-fix-explode-t5); the rework above is the current surface.

**Status:** Implemented in source. Android debug Kotlin compilation passed during E1b/E1c; the feature-specific JVM/phone checks below remain pending. This document owns inspector navigation, layer actions and trim/extend/split.

### Anchor acceptance — 2026-09-24

Source implementation and generated FlatBuffers bindings are complete. `AnchorPointTest.kt` covers centre parity, pivot invariance, compensated corners across source rotation/SAR/odd reference dimensions and resizing, legacy v1–v5 decoding, v6 round-trip, protected curves, gesture undo/cancel, copy/paste and opacity-preserving reset. Tests have not been run.

- [ ] Verify active Position tap-again toggle, bullseye icon, percentage entry and all nine presets.
- [ ] Hold-drag centre: continuous preview, one undo entry, cancellation restores both pivot and Position.
- [ ] Confirm no sheet scrolling/overflow at both preview sizes on Galaxy A16.
- [ ] Check unrotated and 90°/270° source video, non-square pixels and odd dimensions for compensation drift; include media with decoder crop metadata.
- [ ] Check source-local pivot position and rotation/scale behaviour at corners, then resize the composition and save/reopen.
- [ ] Keyframed Position: advisory appears, keyframe diamond is disabled, curves remain intact and the expected visual jump occurs.
- [ ] Reset Anchor centres with static compensation; Reset All centres the pivot and resets Position/Scale/Rotation while retaining opacity animation.
- [ ] Export a single video with a non-centre anchor and compare against preview.

---

## 1. Structure & Navigation

The inspector reports the focused Position/Rotation/Scale/Opacity property to the timeline. Landing, Timing, Audio, Color and dismissal clear focus. Opening a property page hides the trim row; Back returns to the landing categories.

- **Audio panel:** Extract Audio is a visible, disabled, non-tappable placeholder alongside mute/gain. Its caption says extraction is not available yet. Backend prerequisites are `LayerType.AUDIO`, timeline audio tracks and audio mixer integration. Extract Audio is not in the toolbar Layer menu.

- **Trim/Extend/Split Quick Actions:** A landing-only 36dp row beneath the title offers Move In/Extend In before the clip, Trim In/Split/Trim Out inside, and Extend Out/Move Out after it. Split is hidden at exact endpoints. Actions, anchor rules and verification are documented in [Trim, Extend and Split](ELEMENT_INSPECTOR.md#7-trim-extend-and-split). These controls remain landing-page header icons and are intentionally **not** demoted into a navigable landing-state tab.

- **Landing State (8-Cell Grid):** Opening the inspector on a layer displays an 8-cell grid arranged 4-4 at both preview sizes. Expanded preview is now the default:
  - **Row 1:** Timing / Mattes & Masks / Layer Styles / Blending & Opacity
  - **Row 2:** Transform / Contextual Edit (Edit Solid or Edit Footage) / Motion Blur / Effects

  All 8 cells are visible across Video, Image, and Solid layers, providing a consistent layout. Functional versus inert behavior is context-sensitive per layer type:

  | Cell | Video | Image | Solid | Behavior / State |
  | :--- | :---: | :---: | :---: | :--- |
  | **Transform** | ✅ Functional | ✅ Functional | ✅ Functional | 2D position dot-grid, rotation dial, scale rulers. |
  | **Timing** | ✅ Functional | ✅ Functional | ✅ Functional | Start and Duration sliders for all; Video also exposes Source In. |
  | **Blending & Opacity** | ✅ Functional | ✅ Functional | ✅ Functional | Opacity slider + reset; inert "Blend Mode: Normal" placeholder row. |
  | **Contextual Edit** | ⏸ Inert | ⏸ Inert | ✅ Functional | **Solid:** "Edit Solid" opens the Global Colour Picker.<br>**Video / Image:** "Edit Footage" is visible, non-tappable placeholder. |
  | **Effects** | ⏸ Inert | ⏸ Inert | ⏸ Inert | Visible, non-tappable placeholder (Phase 3 target: Curves + Blur). |
  | **Layer Styles** | ⏸ Inert | ⏸ Inert | ⏸ Inert | Visible, non-tappable placeholder. |
  | **Track Mattes / Masks** | ⏸ Inert | ⏸ Inert | ⏸ Inert | Visible, non-tappable placeholder (Phase 3 target: Stencil masks). |
  | **Motion Blur** | ⏸ Inert | ⏸ Inert | ⏸ Inert | Visible, non-tappable placeholder. |

  *"Visible, non-tappable placeholder"* means the cell is rendered with `TextDisabled` color, `clickable(enabled = false)`, and `semantics { disabled() }`—no navigation occurs on tap and no empty "coming soon" screen is opened. This exactly matches the established placeholder pattern from `AddContentSheet.kt`.

- **Header:** Contains the layer title, duplicate, hold-to-copy, delete, an in-sheet Back button on property pages, and a video-only **Audio** shortcut toggle that opens gain and mute sliders.
- **Quick-Navigation Bar:** Selecting a functional category reveals persistent tabs to quickly switch between active sections (Transform, Timing, Blending & Opacity, Contextual Edit for Solids) without returning to the landing view. Inert placeholder categories do not appear in the tab bar to prevent false affordances.
- **Shared Utility Rail:** Every applicable tab receives the same left-side utility rail containing:
  1. Keyframe diamond button (toggle/add/remove keyframe)
  2. Graph editor icon: **enabled (GRAPH G1)** when the rail's focused property is continuous and has two or more keys; opens the Graph page (Ease view) in the same sheet, and tapping it again returns. Visible and disabled otherwise (including discrete properties). See [GRAPH G1](GRAPH_G1.md#5-ui-the-graph-page-ease-view).
  3. **3D icon** (visible, disabled & inert; positioned below Graph, above Overflow)
  4. Overflow dots icon (Reset options menu)
- **Sheet Placement & Alignment:**
  - When the inspector opens, the timeline programmatically scrolls to align the selected layer with the first timeline row.
  - Both clip rows and layer-control headers use matching trailing padding so the bottom-most layer can also scroll to the top row.
  - User vertical scrolling on the timeline dismisses the inspector sheet; programmatic alignment does not dismiss it.
  - The shared workspace budget reserves 328dp for collapsed preview or 268dp for expanded preview (`InspectorSheetBudget`; TEXT UI 5 briefly raised these to 344/308, TEXT UI 6 restored the originals and fits Edit Text with smaller tiles), scaled for fonts exactly as before TEXT UI 5. Every page, Edit Text included, uses the same height. Opening the inspector does not resize preview; [timeline geometry](TIMELINE_LAYOUT.md#vertical-regions) owns these measurements. The keyboard overlays the editor (no `imePadding`); only the focused popover/inline field lifts above it.

---

## 2. Transform Controls

Transform mode provides a right rail to toggle between **Position**, **Rotation**, and **Scale**:

### Position
- **Grid Pad:** Square dot-grid interactive pad (capped at 200dp).
- **Exact Coordinates:** Editable X/Y fields in composition pixels (center is `0, 0`).
- **Reference Geometry:** Coordinates respect the layer's reference canvas dimensions (`LayerGeometry`), ensuring values remain stable across canvas aspect ratio changes.
- **Anchor Point Mode (source implemented, 2026-09-24):** Tap the already-active Position rail icon to toggle Anchor Point mode; Rotation/Scale and reopening Transform return to Position. This follows the plan's concrete tap-again handler/checklist (not a timed double-tap recognizer).
  - A compact 3×3 preset grid replaces the dot-grid pad. X/Y are editable percentages of source-local bounds; centre is 50%/50%. Tap centre to snap; hold and drag centre for free placement. No scrolling is added.
  - Position now locates the spatial anchor in composition space; rotation and scale pivot there. A monochrome preview target appears only while Anchor mode is active on a visible, active selected layer.
  - Static Position auto-compensates through the same `LayerGeometry.matrix` coefficients, including source rotation/pixel aspect and exact reference dimensions. The reusable matrix buffer is separate from the renderer's per-frame buffer. Compensation holds appearance at the current playhead; animated Scale/Rotation may change appearance at other times.
  - Animated Position skips compensation, preserves its curves, and shows “Position is keyframed — auto-compensation skipped”. The keyframe diamond is disabled in Anchor mode; spatial anchors themselves are not animated.
  - Source metadata loads on IO; static anchor edits wait until geometry is available. Image dimensions come from the same raster decode path as rendering. Video orientation/pixel aspect are parsed by shared `VideoGeometry.from` in both metadata and decoder paths. Unusual decoder crop/output-format differences still need phone verification.
  - Presets/numeric edits are single actions. Hold-drag uses begin/preview/commit/cancel from a fixed gesture baseline and produces one undo entry. Cancellation restores the original anchor and Position.
  - v6 adds only spatial `anchorX/anchorY`; the existing temporal `keyframeAnchorUs` is unchanged. v1–v5 documents default to centre. Non-centre anchors use the layered export path.

### Rotation
- **Dial:** Circular rotation dial with a circumference handle (capped at 184dp, fitting available vertical space).
- **Turns Notation:** Center field displays and accepts full turns plus remainder degrees:
  - Formats: `2x45` or `2×45` = 765°; `-1x315` = -45°; plain total degrees (`765`) also supported.
  - Remainder values accept `0–360`. Commits once on Done, Enter, or focus loss; invalid input is discarded.
- **Continuous Tracking:** Crossing angle boundaries accumulates full turns instead of clamping or wrapping.

### Scale
- **Horizontal Rulers:** Horizontal drag ruler with minor ticks every 10% and labeled major checkpoints every 50% (100%, 150%, 200%, etc.). Vertical-only drag motion is ignored.
- **Linked vs. Unlinked:**
  - **Linked:** Single combined ruler scaling width and height proportionally.
  - **Unlinked:** Two equal-height rulers for independent X and Y scaling.

### Reset Actions
- **Reset Current Mode:** Resets only the active mode (Position to center, Rotation to 0°, or Scale to 1× aspect-fit).
- **Reset Anchor Point:** In Anchor mode, the same menu item resets to 50%/50%, compensating only static Position. **Reset all transforms** resets the spatial anchor, Position, Scale and Rotation atomically; opacity and its keyframes remain unchanged.
- **Reset All Transforms:** Located in the overflow menu; resets position, scale, and rotation simultaneously while **preserving opacity**.

---

## 3. Blending & Opacity and Contextual Edit

### Blending & Opacity (formerly Opacity)
- **Blend Mode Row:** Inert placeholder row showing "Blend Mode: Normal" (styled with `TextDisabled` and non-clickable), reserving the visual slot for future blend-mode selection (see [AE_PARITY_VISION.md](../architecture/AE_PARITY_VISION.md) §26).
- **Opacity Slider:** Dedicated horizontal slider with fine scrub (0–100%).
- **Utility Rail:** Shared rail with keyframe diamond, Graph icon (enabled for animated Opacity with two or more keys, GRAPH G1), disabled 3D icon, and overflow menu with **Reset opacity** (resets to 100%).
- **Value Box:** Direct numeric entry with percentage display.

### Contextual Edit (Edit Solid / Edit Footage)
- **Edit Solid (Solids):**
  - Powered by the shared **Global Colour Picker** (`FluxxColourPicker`, FULL variant), providing:
    - Saturation-value box (X = saturation, Y = value) with a vertical hue strip; the separate V slider is deleted
    - Editable hex/numeric input (`#RRGGBB` / `#AARRGGBB`) with strict validation
    - Alpha slider (0–100%) with checkerboard transparency preview
    - RGB numeric sliders (0–255)
    - Eyedropper sampling tool (native Vulkan offscreen readback from preview canvas)
    - Persisted shared palettes: one 22dp horizontally scrollable swatch row, browser (create/switch/rename/delete/edit) and save-colour icon; 0-24 swatches, AtomicFile JSON app storage
  - Side-by-side layout uses the actual available height without scrolling. Galaxy A16 width/height acceptance is pending; see [Global Colour Picker](GLOBAL_COLOUR_PICKER.md).
  - Live preview immediately updates solid layer RGBA texture in the Vulkan compositor.
  - Color changes commit through `EditorAction.SetColor(id, argb)`.
- **Edit Footage (Video / Image):**
  - Visible, non-tappable placeholder cell reserving the slot for future speed ramping, retiming, and media replacement workflows.

---

## 4. Layer Duplication, Copy & Paste

### Contextual Layer menu — 2026-09-25

The existing sixth toolbar icon owns these actions; no duplicate menu or toolbar icon is introduced. Implemented in source, with compilation, tests and phone acceptance pending.

- With no selection: Paste layer (clipboard-dependent) and Select all layers (disabled).
- With a selected Video/Image: Flip Horizontal, Flip Vertical, Fit to Composition Width, Fit to Composition Height, Stretch to Composition Area and Media Info, followed by disabled Auto Orient and Convert to Outline / Autotrace, then a divider and the global actions.
- With a Solid: Flip H/V and the two placeholders; Fit/Stretch/Media Info are omitted.
- Flip negates the evaluated scale axis around the existing spatial anchor. Fit/Stretch set **absolute positive** scales, deliberately removing previous flip signs; they preserve position, rotation and anchor. Fit is based on source aspect-fit geometry, including source rotation/pixel aspect and reference-canvas fallback. These commands do not reset a user rotation or centre an off-centre layer.
- Animated Scale inserts/updates an anchor-relative keyframe at the playhead, preserving an existing key's easing. Static Scale updates both transform and static scale values. Each command is one undo entry.
- Fit metadata is read on the IO dispatcher. Failed reads leave the edit unchanged; stale requests are discarded after project/selection/playhead changes or during another gesture.
- Media Info shares the browser metadata content and formatting through `MediaRepository.details`; images imported outside MediaStore resolve their dimensions as well. Video includes source duration/frame rate when available.
- Auto Orient awaits per-frame Position curve tangent evaluation. Convert to Outline / Autotrace awaits bitmap tracing and vector shape layers.

### Duplication and clipboard

- **Duplicate:** Tap the duplicate icon to clone the selected layer at its original start time with a fresh ID, immediately above its source in the hierarchy. Creation and placement form one undo entry. Tied z-orders retain their relative order. Updated 2026-09-26; phone verification pending.
- **Copy:** Long-press (hold) the duplicate icon to capture an immutable layer snapshot into `EditorViewModel.layerClipboard` (confirmed via Toast).
  - Copy captures committed state, legacy reference canvas dimensions, and resets any active gesture.
  - Clipboard survives Activity recreation with the ViewModel; cleared on project Load; not written to disk or undo stack.
- **Paste:**
  - Accessible via the 6th toolbar icon (Layer menu).
  - Enabled only when the in-memory clipboard holds a layer.
  - Inserts layer at the current **frame-snapped playhead** with topmost z-order and a fresh layer ID.
  - Preserves source trim, duration, transform, opacity, audio gain/mute, visibility, color, and reference canvas.
  - Re-allocates a fresh asset ID if the source asset was relinked, preventing asset collision.
  - Reuses `EditorAction.Add` with selection focus, duration expansion, and undo registration.
- **Select All Layers:** Visible in the 6th-icon menu, currently disabled and inert (editor maintains single `selectedLayerId`).

---

## 5. Deferred Controls & Placeholders

The following controls are rendered as inert/disabled placeholders pending future phases (see [AE_PARITY_VISION.md](../architecture/AE_PARITY_VISION.md) §58 for the full three-tier roadmap):
- **3D Utility Rail Icon:** Rendered below Graph and above Overflow in the utility rail; visible, disabled & inert pending the 3D scene engine and camera projection.
- **Effects Tab:** Visible, non-tappable placeholder cell in the 8-cell grid (Phase 3 target: Curves + Gaussian Blur).
- **Layer Styles Tab:** Visible, non-tappable placeholder cell in the 8-cell grid.
- **Track Mattes / Masks Tab:** Visible, non-tappable placeholder cell in the 8-cell grid (Phase 3 target: hard-edge stencil masks).
- **Motion Blur Cell:** Visible, non-tappable placeholder cell in the 8-cell grid (engine integration pending).
- **Edit Footage Cell:** Visible, non-tappable placeholder cell for Video and Image layers.
- **Blend Mode Row:** Inert "Blend Mode: Normal" row in the Blending & Opacity tab.
- **Z-Position & XYZ Rotation:** Planned for 3D engine support.
- **Keyframe Diamonds:** Now enabled by Phase 2 Step D; see [keyframe implementation](KEYFRAME_ANIMATION.md). Property focus also controls timeline marker prominence and interaction.
- **Graph Editor Icons:** Visible in every rail with a diamond (Transform, Blending & Opacity, Edit Text pages, Animate). GRAPH G1: enabled for a continuous focused property with two or more keys; opens the **Graph page** (`InspectorSection.GRAPH`). GRAPH G1.1 layout: the shared header row (back arrow, Value | Speed | Ease with Value and Speed disabled, the segment label right-aligned); a Graph-only left rail (diamond, Graph, Invert, Tools popover with Snap / Mirror / Apply to all, ⋮ with Copy / Paste cubic-bezier and Reset); the centre item, one rounded box holding the touchpad (handles with direct and relative drag; Bounce/Elastic as an XY pad) over the values bar (x1 y1 x2 y2, or Bounces/Height, Oscillations/Damping; tap to type); and a right rail list of the type tiles and 30 built-in presets. Non-modal; the back arrow, the Graph icon or Back returns to the page it came from with its state. GRAPH G2L: the right rail became a type strip inside the centre box plus a pinned rail (up to 15 curves; long-press then drag to pin, reorder, replace or unpin); a floating library icon opens the curve library over the strip and rail (touchpad and values bar keep their bounds; Back closes it first), with save, import/export, and a rename/delete menu on user curves; ⋮ gained "Show playhead dot"; the header names the curve. [G1](GRAPH_G1.md), [G1.1](GRAPH_G1_1.md), [G2L](GRAPH_G2L.md).

---

## 6. Phone Verification Checklist

The landing-page navigation, 8-cell grid, trim icons, and scrubbing/hierarchy gestures are implemented in source and await phone verification.

- [ ] **8-Cell Grid Landing Page:**
  - Confirm 8 cells render in a 4-4 arrangement in the requested order at both preview sizes on Video, Image, and Solid layers.
  - Verify placeholder cells (Effects, Layer Styles, Track Mattes/Masks, Motion Blur, Edit Footage) are visible in disabled styling and do NOT navigate or crash on tap.
  - Verify "Trim Controls" remain persistent header icons and are not in the grid.
- [ ] **Blending & Opacity Tab:**
  - Verify renamed tab label in grid and quick-nav bar.
  - Verify "Blend Mode: Normal" row is visible and inert.
  - Verify opacity slider scrubs smoothly and reset opacity restores 100%.
- [ ] **Contextual Edit (Edit Solid):**
  - Verify tapping "Edit Solid" opens the Global Colour Picker.
  - Verify side-by-side layout fits within sheet height without introducing scrolling.
  - Verify SV/hue, RGB, hex and alpha updates; eyedropper drags show a live sample and commit once on release. Confirm palettes persist after relaunch.
- [ ] **Shared Utility Rail:**
  - Verify 3D icon is present below Graph and above Overflow.
  - Verify 3D icon is styled as disabled and inert in Transform and Blending & Opacity.
- [ ] **Position / Rotation / Scale:**
  - Drag dot-grid pad; enter exact positive, negative, and fractional pixel coordinates; verify the pixel fields show half the composition width/height at its center.
  - Scrub dial across multiple rotations; test notation entry (`2x45`, `-1x315`, `720`); verify turn accumulation.
  - Drag horizontal ruler left/right; verify vertical drag does not change scale; toggle linked/unlinked mode.
  - Test "Reset active mode" vs. "Reset all transforms"; ensure opacity is preserved.
- [ ] **Sheet Interaction & Alignment:**
  - Tap layer to open inspector; confirm timeline auto-scrolls selected layer to row 1; scroll timeline vertically to dismiss.
  - Verify the 328dp collapsed-preview / 268dp expanded-preview inspector budget, font scaling and small-window clamping; opening the sheet must not resize preview.
- [ ] **Duplicate / Copy / Paste:**
  - Tap duplicate on a top, middle and bottom layer -> verify the clone is immediately above its source at the original start time; one undo removes it and restores the previous hierarchy.
  - Hold duplicate -> verify Toast confirmation.
  - Move playhead to new time -> open 6th toolbar icon -> tap Paste -> verify layer appears at playhead with new ID.

### Context menu phone acceptance

- [ ] Check no-selection, Video, Image and Solid menus; verify both placeholders and Select all remain inert.
- [ ] Flip both axes at centre/corner anchors; confirm video remains visible and undo/redo restores the edit.
- [ ] Enable Scale animation, flip and Fit/Stretch at and between keys; confirm key insertion/replacement and unchanged other channels.
- [ ] Fit Width/Height and Stretch landscape footage into a portrait composition; repeat with 90°/270° video, non-square pixels and resized compositions. Fit/Stretch must clear old negative signs.
- [ ] Open Media Info for videos, MediaStore images and document-imported images; verify name, dimensions/aspect and video duration/frame rate. Check missing-media handling.
- [ ] Open Audio: Extract Audio is visible and non-tappable; verify it is absent from the Layer menu.

`FlipAndFitTest` prepares static/animated flips, undo/redo, signed temporal anchors, fit/stretch matrix bounds, pivot invariance, source quarter-turn/SAR, positive scale replacement and zero-reference guards. Tests have not been run.

## 7. Trim, extend and split

### Seven actions

The 36dp quick-action row sits below the title on the inspector landing page. Compact bordered, icon-only controls change with the selected layer and playhead. Opening a property page hides the row; the header Back button returns to the landing page. See [timeline gestures](TIMELINE_LAYOUT.md#timeline-gestures-and-inspector-viewport).

| Playhead | Actions | Contract |
| --- | --- | --- |
| Before clip | Move In, Extend In | Move preserves duration/source offset and translates animation. Extend reveals earlier source up to its available handle. |
| Inside clip | Trim In, Split, Trim Out | Trims preserve the other endpoint and animation's composition timing. Split selects the new right segment. |
| Exactly at an endpoint | Trim In, Trim Out | Split is hidden. Trimming to the opposite endpoint may collapse the clip to zero duration. |
| After clip | Extend Out, Move Out | Extend reveals later source up to its available duration; Move preserves duration and ends at the playhead. |

`ClipQuickActions` owns context selection and clamp arithmetic; Compose dispatches its action once. At a source/composition limit, the action is disabled and does not create undo history. Unknown clip durations keep duration-dependent operations disabled until resolved; the five-second fallback is presentation only. This avoids fabricating a video source interval. Move In can still translate unresolved timing.

Video Extend In clamps to `max(0, start - sourceIn)`. Images and solids extend to composition zero without changing source offsets. Extend Out clamps to the known video source end, otherwise composition duration (or the representable timeline if unknown), and never shortens an existing clip. The quick actions use the exact playhead timestamp; timeline drag snapping retains the composition frame grid.

### Animation origin and corrections to the supplied pseudocode

- `CompositionLayer.keyframeAnchorUs` is nullable for legacy documents; its resolved value is the anchor or the current clip start. Trim/Extend materialize the OLD resolved anchor before changing the in-point. Merely retaining null would still shift the animation.
- Move shifts an explicit anchor by the clip-start delta. A null anchor can remain null because it follows the moved clip start equivalently. Timing-panel start changes and duplicate/copy/paste use the same translation rule. A source-in-only edit changes source mapping, not animation timing.
- Split creates both segments in one reducer transition. Left retains the resolved anchor and all keys. Right anchors at the split point `p` and subtracts **`p - oldResolvedAnchor`** from every key, not `p - oldClipStart`; the latter is incorrect after an in-point trim.
- Rebased key timestamps are signed. Keys before the split remain negative and preserve the original segment's value and easing curve. Clamping/removing them or inserting a new Easy Ease segment would change the curve. Both segments retain the original static transform, appearance, audio, media and reference-canvas properties.
- Split inserts the right segment next to the left in render order and normalizes z-order indices. This preserves ordering against unrelated layers even when the original zOrder values tie.
- Timeline markers convert anchor-relative key time to clip-relative display/snap coordinates. Inspector diamonds, numeric edits and gestures write against the resolved anchor. MoveKeyframe accepts negative anchor-relative times; the timeline still bounds visible drags to the clip.
- The unified Kotlin renderer still fills its reused six-float output buffer. No JNI or second evaluator was introduced.

### Persistence

Signed key times and persistent animation origins were introduced in **version 5**; the current writer uses v13 (GRAPH G1 easing). Versions 1-4 still load, with absent anchors resolving to clip start.

The additive Layer fields are `keyframe_anchor_us:long=-1` and `has_keyframe_anchor:bool=false`. The presence bit is necessary: moving a trimmed clip earlier can create a legitimate negative anchor, including exactly -1. Null remains null on round-trip; explicit -1 survives as an explicit value. Decoding also accepts a non-sentinel anchor without the presence flag.

The schema and generated Kotlin binding stay synchronized. Older app versions reject v5 using their existing version check; forward loading in those versions is not promised.

### Collapse, history and safety

- Zero-duration layers remain in the document for non-destructive recovery but are filtered out of both timeline lists. The existing half-open active interval makes them absent from preview/export frame evaluation.
- Undo/Redo now stores document plus selection in each existing history entry. A split is one step; Undo restores the original layer selection and Redo selects the right segment. Playhead position remains navigation state.
- Invalid boundary splits and no-op trims do not add history. Reducer validation completes before history is mutated. Checked integer arithmetic protects key rebasing and anchor translation.
- The supplied manual instruction “past clip end, tap Trim In” conflicts with the context matrix: past the end the row contains Extend Out/Move Out. Test collapse by tapping Trim In **at** the out-point, or Trim Out **at** the in-point.

### Verification prepared, not executed

`TrimAndSplitTest` covers all seven actions, source/composition clamps, exact endpoint collapse, signed anchor persistence (including -1), original-curve equality across split timestamps, split after prior trim and repeated split, move/paste offsets, negative key editing, Image/Solid behavior, unresolved timing, render-order ties, no-op history, and atomic undo/redo with selection.

Continuity assertions compare each resulting segment to the original curve at the **same timestamp**. Values 1ms before and after a cut need not be equal for a moving property; the original plan's cross-timestamp comparison is not a reliable continuity test.

Phone acceptance:

1. Check the 2/3/2 context configurations, hidden Split at endpoints, icon accessibility labels and disabled source limits.
2. Trim/extend an animated video and confirm keys stay at their composition times; move it and confirm keys move with it.
3. Split between keys after a prior trim; scrub across the cut and compare preview/export to the unsplit original.
4. Duplicate and paste a trimmed or split layer later; save/reopen and verify animation timing, source mapping and negative keys.
5. Collapse from each exact endpoint, confirm timeline/preview disappearance, then Undo and Redo.
6. Check all inspector pages fit without scrolling in both preview sizes; the 36dp action row appears only on the landing page.


## E3 Text inspector — historical baseline (current surface: Edit Text rework at the top)

Text enables Contextual Edit as Edit Text. Its controls stay fixed; only the source field scrolls. A compact header and workspace-level IME padding keep the source field, Done/Cancel and letterboxed preview visible while typing; timeline/actions restore their prior layout after dismissal. Font and alignment are static controls. Size, Fill and Hold-only Source Text use the existing keyframe focus/navigation/easing framework. Fill opens the existing Global Colour Picker and eyedropper.

A typing session captures the frame-snapped playhead and original editor state. Native metrics run on a dedicated worker, revisions reject stale results, and source/layout/allowed static Position compensation publish in one gesture preview. Done, focus loss and navigation await the latest draft and commit one existing gesture; Cancel restores the snapshot. Style changes await typing before applying their own gesture. No PreviewController API or gesture protocol changed.

Text gets shared-metric natural-pixel bounds, handles, hit testing and anchor compensation. Fit Width/Height and Stretch preserve font size and use Scale; empty text disables fit and retains its anchor target. Media Info remains media-only. Timeline labels evaluate the held string. Add Content creates an assetless five-second text layer at the snapped playhead. See the exact [E3 device checklist](TEXT.md#e3-editor-integration--implemented-in-source-device-unverified).

### F2 implementation status (2026-10-04)

F1 is user-accepted; its performance harness, pre-encode parity and lifecycle checks were deferred at this checkpoint and have since run on the A16 (TEXT FIX 9, TEXT PERF 10/11). F2a and F2b are implemented in source, device-unverified. F3 and UI improvements remain deferred. No PreviewController API or gesture-protocol changes, no new animator UI and no agent device runs.

V11 adds default-on hold-keyed Ligatures: effective on only when layer tracking evaluates to zero. Animator tracking does not split ligatures. Both source/font/mode layouts, shared membership maps and seeded permutations for every source alternative are prepared ahead of playback. V10 loading is retained permanently. Per-animator grouping, averaged cluster influence, lookup-table easing, signed spatial influence with clamped colour influence, round SDF joins and bounded SDF softness are documented deviations/semantics. Paint order stays per glyph; no additional paint scope or two-pass draw.

Detailed stage evidence and the exact user-run list are maintained in [the existing text animation plan](TEXT.md#rec-text-animation-plan). No animator UI is added; data/edit actions exist for later F3 diamonds/controls.

The data model exposes addressed `(layerId, animatorId, selectorId?, propertyId)` edit/key actions, all scalar/vector/colour tracks, hold-keyed enum/boolean fields, per-animator grouping and the hold-keyed layer Ligatures property. Add/remove/reorder are single undo transactions. Auto-key, freeze/reset, navigation and timeline key aggregation include animator tracks; duplicate/clipboard preserve seeds/order/values with fresh animator/selector IDs; trim/extend/split retain checked signed rebasing. These are backend contracts only: no Add Animator picker, selector controls, new diamonds or handles are exposed in this pass. Existing selection/fit/typing geometry consumers now request the evaluated ligature layout; their gesture protocol and controls are unchanged.
