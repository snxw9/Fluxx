# Fluxx — Phase 2 Changes & After Effects-Level Architecture Notes

> Text status: E1d, E1e, E2 and E3 are implemented in source and compiled; all new text device behavior remains unverified. Native debug/release compilation passed for arm64-v8a and x86_64, Kotlin debug/release and instrumentation sources compiled, and the final JVM run passed 150/150 tests. No APK was installed or launched. See [stage evidence and exact user-run checklists](../checkpoints/TEXT_LAYER.md). The approved overflow policy uses lazy texture-array growth; range selectors and text animators remain future work.

## Overall Direction

Fluxx is intended to become a full mobile motion-graphics, animation, compositing and editing environment—the mobile equivalent of what After Effects represents on desktop.

After Effects should generally be treated as the functional reference for established motion-graphics concepts. Where Fluxx implements an AE-equivalent feature, behaviour should match AE where appropriate unless a different interaction makes more sense on a touchscreen/mobile device.

The goal is **not to copy AE's desktop UI directly**. Fluxx should retain the depth and flexibility of those systems while designing interactions specifically for mobile.

Some features described below do not exist yet. Where future functionality is mentioned, the immediate requirement is to ensure current architecture does not prevent those systems from being added later.

---

# 1. Solid Controls and Global Colour System

Original motivation: the Solid controls did not fit comfortably. Current shared picker geometry and pending device measurements are owned by [Global Colour Picker](../checkpoints/GLOBAL_COLOUR_PICKER.md).

Redesign them to be considerably more compact while preserving usability and appropriate touch targets.

Add a proper colour picker containing:

* Saturation-value box with vertical hue strip
* RGB sliders
* Eyedropper
* Colour preview
* Hex/numeric colour input where appropriate

The existing RGB sliders can sit beside the SV box and hue strip if the available space permits.

### Palette system — implementation update (2026-09-22)

The shared picker now uses an SV box and hue strip, with no separate V slider. Both call sites share an app-scoped palette library persisted with AtomicFile JSON. Palettes support create/switch/rename/delete and swatch add/edit/remove, capped at 24 colours; renaming commits on focus loss/Done. External .ase/.gpl import is deferred. The inspector defaults to expanded preview and a permanent 4-4 grid. New behavior and tests await user verification; see the colour picker checkpoint.
### Global Colour Picker

This colour-selection behaviour should become the default reusable colour-selection system throughout Fluxx rather than being implemented specifically for Solid layers.

The same component should eventually be invoked by:

* Solid colours
* Text colours
* Shape fills/strokes
* Layer colours/labels
* Precomp colours/labels
* Marker colours
* Effect properties containing colour values
* Gradients
* Lights
* Other future colour properties

Do not create separate colour-picker implementations for individual features.

---

# 2. Layer Inspector Redesign

Add the following sections/tabs to the Layer Inspector:

* Effects
* Layer Styles
* Track Mattes / Masks

Rename:

**Opacity → Blending & Opacity**

Arrange the Layer Inspector landing page as a compact 3×3 grid, or use another arrangement if it fits the available screen dimensions better.

### Proposed layout

Top row:

1. Time Remap
2. Trim Controls
3. Motion Blur

Motion Blur could alternatively become one of the persistent icons at the top of the sheet beside Copy, Delete and Audio.

Middle row:

1. Track Mattes / Masks
2. Layer Styles
3. Blending & Opacity

Bottom row:

1. Transform
2. Contextual Edit tab
3. Effects

---

# 3. Context-Sensitive Layer Inspector

The Layer Inspector should not assume every layer exposes the same controls.

Tabs should appear based on the capabilities of the selected layer.

For example:

### Video

* Transform
* Edit Footage
* Effects
* Blending & Opacity
* Track Mattes / Masks
* Trim
* Time Remap
* Motion Blur
* Audio where applicable

### Image

Similar to Video, but without video/audio-specific functionality.

### Text

Replace Edit Footage with:

**Edit Text**

### Shape

Replace it with:

**Edit Shape**

### Solid

Replace it with:

**Edit Solid**

### Camera

Use:

**Camera Settings**

### Audio

Do not show visual-only sections such as:

* Transform
* Blending & Opacity
* Track Mattes / Masks

### Adjustment / Null / Camera

Do not expose properties that are meaningless for those layer types.

Some of these layer types have not been implemented yet. These are architectural rules for future implementation.

Ideally this should become a **capability-driven system**, where the UI derives available controls from what a layer supports rather than accumulating hardcoded layer-type checks.

---

# 4. Shared Inspector Utility Rail

Every applicable Inspector tab should receive the same left-side utility rail for consistency.

Individual icons within the rail should still be context-sensitive.

Add a **3D icon**:

* Below Graph
* Above Overflow

The 3D icon should only appear where 3D transformation is meaningful.

For example, it should not appear for Audio layers, and since Audio layers do not require Transform controls, the Transform tab itself should not appear.

---

# 5. Position and Anchor Point Mode

**Implementation update — 2026-09-24:** Implemented in source; feature tests and phone acceptance remain pending; later E1b/E1c compile checks passed. Option B is the compact 3×3 preset grid only, with editable X/Y percentages and centre hold-drag; no second trackpad or scrolling. Tapping the active Position icon again toggles modes. Rotation/Scale and reopening Transform return to Position.

Position locates the spatial anchor in composition space. Scale and rotation pivot around it. Static Position compensates at the current playhead to keep layer pixels stationary, using the exact `LayerGeometry.matrix` coefficients (including source rotation, pixel aspect and untruncated reference dimensions). Animated Position skips compensation, preserves curves, and displays a monochrome advisory; the keyframe rail is disabled in Anchor mode. Animated Scale/Rotation can still alter appearance away from the edited playhead.

The source-local anchor is persisted in new v6 `anchorX/anchorY` fields, defaulting to centre for older files. It is independent of the existing temporal `keyframeAnchorUs`. Rotation/SAR parsing is shared between the media repository and decoder. Non-centre pivots bypass the static single-video export shortcut and use the layered renderer. Decoder-specific cropped output still needs phone verification against source metadata.

Reset Anchor Point centres the pivot with static-only Position compensation. Reset All atomically resets the spatial anchor, Position, Scale and Rotation while preserving opacity and its animation. See [Element Inspector](../checkpoints/ELEMENT_INSPECTOR.md) for implementation details and acceptance checks.

When Position mode is active, tapping the Position icon again switches the control surface into:

**Anchor Point Mode**

There must be clear visual feedback indicating whether Position or Anchor Point is currently being manipulated.

### Anchor Point controls

Provide nine quick positions:

* Top-left
* Top
* Top-right
* Left
* Center
* Right
* Bottom-left
* Bottom
* Bottom-right

The center control should also support free positioning.

**Tap center:** center anchor point.

**Hold + drag center:** manually move the anchor point.

When Anchor Point mode is active, the existing contextual menu action:

**Reset Position**

should become:

**Reset Anchor Point**

---

# 6. Selected-Layer Context Menu

Add a hamburger/overflow menu at the top of the Editor when a layer is selected.

Tapping it should expose contextual operations such as:

* Flip Horizontally
* Flip Vertically
* Fit to Composition Width
* Fit to Composition Height
* Stretch to Composition Area
* Auto Orient
* Media Info
* Extract Audio
* Convert to Outline / Autotrace — future feature

Menu contents should depend on:

* Layer type
* Number of selected layers
* Capabilities shared by selected layers

A single Video layer therefore exposes different actions from Text, Camera, Audio or a multi-layer selection.

Do not simply disable irrelevant commands where they can instead be omitted.

---

# 7. Extract Audio

Video layers containing audio should expose:

**Extract Audio**

This separates the video's audio into its own Audio layer while maintaining synchronization with the source video.

The original video's visual content remains unchanged.

Extract Audio could exist inside the Audio Inspector section, but it may make more sense as a contextual operation inside the selected-layer menu because extraction is a one-time media operation rather than a continuously adjustable audio property.

---

# 8. Timeline Density

Reduce the vertical spacing between timeline layers.

Original density request; the current 28dp row pitch and separated ruler/inspector geometry are owned by [Timeline](../checkpoints/TIMELINE_LAYOUT.md).

The goal should be to display more layers simultaneously without making touch interactions uncomfortable.

The pill/container on the left containing the Eye/Visibility icon should:

* Become transparent
* Lose the blur
* Become visually lighter

---

# 9. Timeline Hierarchy/Reordering

Original reorder feedback led to absolute-pointer targeting and animated sibling placement; source status and remaining phone checks are owned by [Timeline](../checkpoints/TIMELINE_LAYOUT.md#timeline-gestures-and-inspector-viewport).

When dragging a layer above or below another layer, surrounding layers should smoothly animate out of the way.

The interaction should continuously communicate where the dragged layer will be inserted.

For example, dragging Layer 3 above Layer 2 should smoothly push Layer 2 downward rather than causing an abrupt hierarchy jump.

---

# 10. Timeline Hierarchy Side Control

The timeline-side hierarchy control should remain visible on the right side of applicable layer rows.

It should be:

* Transparent
* Much smaller
* Contained within the layer's row height
* Always accessible
* Large enough to remain usable as a touch target

---

# 11. Inspector Closing / Timeline Position Bug

This is separate from layer hierarchy.

When opening the Inspector for a layer near the bottom of a timeline containing multiple layers, the timeline may reposition the selected layer upward to keep it visible.

Currently, closing the Inspector leaves that layer visually positioned near the top.

When the Inspector closes, restore the timeline viewport appropriately so the layer returns to its natural visible position.

Its actual hierarchy/order must never change.

---

# 12. Preview Resolution

**Implementation update — 2026-09-27:** The approved seven-button toolbar places resolution inside View / Preview (slot 7), with a quality badge instead of an eighth icon. Preview scaling, expansion relocation, view overlays/zoom and contextual navigation are implemented in source; see [Preview and Action Bar](../checkpoints/TIMELINE_LAYOUT.md#preview-quality-and-presentation). Native compilation subsequently passed during E1b/E1c; detailed phone verification remains pending. Freeform preview pinch/pan, motion paths and camera functionality remain deferred; timeline pinch zoom is implemented. Marker storage and interactions were implemented in source on September 29; acceptance remains pending.

Add a Preview Resolution icon to the Editor Action Bar.

Options:

* Full
* Half
* Third
* Quarter

The icon/indicator should update according to the selected resolution.

Preview resolution changes preview quality only. It does not change composition dimensions.

---

# 13. Preview Expansion

Move the existing Preview Expansion toggle out of the Editor Action Bar.

Place it directly on the Preview:

**Bottom-right corner**

Keep it small and unobtrusive.

---

# 14. View / Preview Menu

Replace the old Preview Expansion location in the Editor Action Bar with a vertical menu icon.

This menu should contain viewport-specific functionality.

Potential options include:

### Layer Controls

Show/hide:

* Bounding boxes
* Selection handles
* Anchor points
* Motion paths
* Control points
* Future X-ray controls

### Grid

Toggle a viewport grid overlay.

This is separate from any future Grid effect.

### Camera

Expose relevant camera/3D preview options when 3D is active.

### Zoom

Provide:

* Zoom In
* Zoom Out
* Fit
* Reset

Future viewport functionality can be added here.

---

# 15. Editor Action Bar Layout

Do not simply add the new icons to the existing bar.

Revisit:

* Icon sizes
* Spacing
* Touch targets
* Available horizontal width
* Overflow
* Visual hierarchy
* Behaviour on smaller devices

Keep the Editor Action Bar compact.

---

# 16. Contextual Previous / Next Navigation

Previous/Next navigation should change behaviour according to editing context.

### Nothing selected

Navigate to:

**Timeline start / end**

### Layer selected

Navigate to:

**Selected layer In / Out**

### Animated property selected

Navigate to:

**Previous / next keyframe**

### Composition markers available

Navigate to:

**Previous / next marker**

### Layer selected + layer markers available

Navigate to:

**Previous / next marker**

Markers take precedence from any playhead position, provided no inspector property is focused (or the inspector is closed). The original selected/parked activation gate was removed after user testing clarified that markers must also be reachable from between frames. A selected layer scopes navigation to its markers; otherwise composition markers apply. Each direction falls through from an exhausted marker tier to layer/global bounds. Seeking to a non-marker boundary clears marker selection.

---

# 17. Markers

**Layout follow-up:** The ruler target is now 32dp tall, the first row begins at 40dp, and the inspector begins at 76dp. See [Timeline Layout](../checkpoints/TIMELINE_LAYOUT.md); these measurements supersede the original 22dp marker geometry below.

**Implementation update — 2026-09-29:** Composition and layer markers are implemented in source, completing source work for the AE-parity “implement now” list. Compilation, automated test execution, allocation profiling, and phone acceptance remain pending. See [Markers](../checkpoints/MARKERS.md).

The mobile implementation uses a strict 48×32dp playhead target confined to the ruler, with zero overlap into the first row at y=40dp. Ruler cue flags and top-edge layer pennants remain distinct from centred keyframe diamonds; a focused property's keyframes retain the lower y=12–28dp touch zone. Hold opens the shared Global Colour Picker in a custom-width dialog: 312dp inner width at the 360dp floor, subject to device verification.

FlatBuffers v7 adds sorted, frame-unique marker vectors. Layer times reuse the temporal keyframe anchor; trim preserves hidden markers, move shifts the anchor, split partitions without duplication and rebases the right segment, and copy/paste retains marker content. Marker IDs are scope-local; selection is transient. Same-frame additions do not stack markers. Tapping the playhead or a flag already under it toggles that marker off; hold opens configuration, dismissed with X/outside tap. Descriptions display next to flags. New composition markers are red, layer markers orange, and solid-layer defaults contrast with the solid colour. Frame lookup prioritizes selected-layer colour, uses binary search, and returns an existing object. Changing frame rate keeps the earliest marker when formerly distinct markers collapse onto one frame.

Selection clears on inspector property focus, layer selection/deselection, clip tap/drag, empty timeline/ruler taps, off-frame seeking, playback start, marker/owner deletion, project load, undo/redo, and navigation fallthrough onto a non-marker boundary. A selected layer outside its visible range produces brief feedback rather than creating a composition marker.

Keyframe magnetic snapping includes composition and selected-layer marker targets. Deferred: marker dragging, marker duration/ranges, MP4 chapters, CSV/EDL/XML marker import/export, marker multi-selection/batch edits, and magnetic playhead scrubbing. Scrub snapping needs a dedicated detent/hysteresis/guide framework; current scroll synchronization remains direct.

Markers should integrate directly with the playhead.

### Composition marker

When no layer is selected:

**Tap playhead → create composition marker at current time**

### Layer marker

When a layer is selected:

**Tap playhead → create marker on selected layer**

### Marker configuration

Holding the playhead before placing a marker should allow configuration such as:

* Colour
* Description

Holding the playhead while positioned on an existing marker should edit that marker.

### Marker appearance

Markers should not permanently recolour the playhead.

When the playhead is exactly on a marker, temporarily change the playhead to that marker's colour.

Moving away restores the normal playhead colour.

---

# 18. Universal Property System

This should become one of Fluxx's foundational architectural systems.

Do not implement animation independently for Transform, Effects, Masks, Text, Shapes, Cameras, etc.

Instead, create a common property model.

Conceptually:

Layer
→ Component / Property Group
→ Property
→ Value / Animation
→ Keyframes / Expression

For example:

Text Layer

* Transform

  * Anchor Point
  * Position
  * Scale
  * Rotation
  * Opacity
* Text

  * Source Text
  * Fill
  * Tracking
* Effects

  * Gaussian Blur

    * Blurriness
    * Direction
* Masks

  * Mask 1

    * Path
    * Feather
    * Opacity
    * Expansion

Every meaningful property should eventually be capable of participating in the same animation system where appropriate.

This includes:

* Transform properties
* Effect parameters
* Colours
* Mask properties
* Shape properties
* Text properties
* Audio properties
* Camera properties
* Light properties
* Time-remapping properties

This prevents Fluxx from accumulating separate keyframe implementations for every subsystem.

---

# 19. Advanced Keyframe System

Build toward AE-level keyframe functionality.

Eventually support:

* Add/remove keyframe
* Previous/next keyframe
* Linear interpolation
* Hold interpolation
* Bezier interpolation
* Temporal easing
* Spatial interpolation
* Keyframe velocity
* Influence
* Multiple keyframe selection
* Copy/paste
* Duplicate
* Move selected keyframes together
* Scale keyframe timing
* Reverse selected keyframes
* Sequence/stagger
* Distribute keyframes

---

# 20. Graph Editor

The Graph system should eventually become a first-class animation environment rather than simply displaying a curve.

Plan for:

* Value Graph
* Speed Graph
* Editable Bezier handles
* Incoming/outgoing influence
* Velocity
* Multiple curves
* Property isolation
* Selected-keyframe editing
* Zoom-to-fit
* Numerical editing

Fluxx's existing trackpad interaction system could potentially be used to provide more precise graph manipulation on mobile.

---

# 21. Expressions

Expressions should be planned as a major future system.

Properties should eventually be capable of being driven procedurally rather than only through keyframes.

Fluxx could support equivalents of concepts such as:

* `time`
* `value`
* `wiggle()`
* `loopIn()`
* `loopOut()`
* `linear()`
* `ease()`
* `clamp()`

Expressions should eventually be able to reference:

* Other properties
* Other layers
* Composition properties
* Time
* Controllers

JavaScript/AE expression compatibility is not necessarily required initially, but the Property architecture should not prevent a more capable expression engine later.

---

# 22. Parenting and Property Relationships

Add a proper relationship system separate from visual timeline hierarchy.

Eventually support:

* Parent & Link
* Parent transforms
* Null parenting
* Layer-to-layer relationships
* Property-to-property linking
* Expression references
* Pick-whip-style linking

A mobile alternative to AE's small Pick Whip could use:

* Drag-to-target
* Searchable "Link to..." sheet
* Property picker

---

# 23. Precompositions

Precompositions are essential for serious projects.

Plan for:

* Pre-compose selected layers
* Nested compositions
* Open precomp
* Return to parent composition
* Composition breadcrumbs
* Replace selection with precomp
* Collapse transformations / continuous rasterization where applicable
* Precomp-aware expressions
* Precomp-aware parenting

Precomps should remain real compositions rather than flattened video renders.

---

# 24. Masks

Masks should eventually support:

* Multiple masks per layer
* Mask Path
* Feather
* Opacity
* Expansion
* Invert
* Add
* Subtract
* Intersect
* Difference
* Animated paths
* Bezier points/handles
* Copy/paste
* Mask tracking

Masks and Track Mattes may share an Inspector entry initially, but should remain distinct systems internally.

---

# 25. Track Mattes

Plan for:

* Alpha Matte
* Alpha Inverted
* Luma Matte
* Luma Inverted

Do not architect Track Mattes around the assumption that the matte must always be the layer immediately above.

Prefer a relationship-based matte system so a layer can explicitly reference another compatible layer as its matte.

---

# 26. Blending and Compositing

Expand Blending & Opacity over time.

Support major blend modes including:

* Normal
* Multiply
* Screen
* Overlay
* Darken
* Lighten
* Color Burn
* Color Dodge
* Soft Light
* Hard Light
* Difference
* Exclusion
* Hue
* Saturation
* Color
* Luminosity

Future advanced compositing can include:

* Preserve transparency
* Advanced blending options
* Per-effect opacity/compositing
* Effect/mask interaction controls

---

# 27. Adjustment Layers

Add Adjustment Layers as a dedicated layer type.

An Adjustment Layer applies its effect stack to the composited visual layers beneath it.

Adjustment Layers should participate in:

* Masks
* Effects
* Transform where applicable
* Timing
* Blending
* Parenting where appropriate

---

# 28. Null Objects

Add Null layers as lightweight non-rendering utility layers.

Their primary purposes include:

* Parenting
* Rigging
* Shared animation controllers
* Expression controllers
* Complex transform relationships

They should not be treated internally as normal rendered footage.

---

# 29. Shape Layer System

The Shape system should eventually support:

### Primitive/path creation

* Rectangle
* Ellipse
* Polygon
* Star
* Pen paths

### Appearance

* Fill
* Stroke
* Gradient Fill
* Gradient Stroke

### Structure

Shape Layers should support multiple groups and nested content rather than representing only one vector object.

### Shape operators

Eventually support systems such as:

* Trim Paths
* Repeater
* Merge Paths
* Offset Paths
* Round Corners
* Twist
* Wiggle Paths
* Zig Zag

Shape Layers should conceptually operate as an editable tree of vector groups and operators.

---

# 30. Advanced Text

**Animation requirement amendment:** every eventual text property uses the Universal Property framework, including discrete hold tracks for categorical values. E2/E3 now includes keyframeable font size, fill colour and hold-only Source Text with inspector diamonds; alignment/font family remain temporarily static. Stroke/tracking/leading must be animatable when introduced. Reserve versioned animator schema envelopes and an optional glyph modifier interface, without implementing animators. See [E2/E3 deviations](../checkpoints/TEXT_LAYER_IMPLEMENTATION_PLAN.md#31-universal-text-property-animation); these are planned changes, not shipped behavior.

**Phase 2 E1b/E1c, 2026-10-01:** native FreeType/HarfBuzz shaping and CPU SDF are implemented and host-tested; Vulkan text and the editor remain planned. Bundled static Regular Inter, Noto Serif and JetBrains Mono faces use stable IDs and OFL licensing; unknown IDs fall back to Inter. The selected raster policy is 48ppem R8 SDF with 8px spread, overlap handling enabled and retained CPU bitmaps for upload-only repacks. See [CPU checkpoint](../checkpoints/TEXT_LAYER.md) for actual capacity and artifact measurements. Text Animators/Range Selectors, text-on-path, auto-wrap, arbitrary font import, stroke and tracking/leading controls remain deferred. E1d is gated on the user's 16 KB environment and golden-project checks.

Text should eventually become a full motion-graphics typography system.

Support:

* Font
* Font style
* Size
* Fill
* Stroke
* Kerning
* Tracking
* Leading
* Baseline shift
* Paragraph alignment
* Text boxes
* Text on path

Eventually implement Text Animators capable of animating properties per character/word/line, including:

* Position
* Scale
* Rotation
* Opacity
* Tracking
* Blur
* Fill
* Stroke

with Range Selectors controlling which characters are affected.

---

# 31. Motion Paths

Animated Position properties should display their spatial trajectory directly on the Preview.

Support:

* Motion path visibility
* Editable points
* Bezier handles
* Spatial interpolation
* Path copying/pasting
* Conversion between compatible paths
* Roving keyframes later

Layer Controls visibility should determine whether these overlays appear.

---

# 32. Advanced Motion Blur

Motion Blur should eventually include composition-level controls equivalent to:

* Shutter Angle
* Shutter Phase

Layers should retain individual Motion Blur enable/disable controls.

The architecture should also allow effects and future simulation systems to produce appropriate motion blur.

---

# 33. 3D Scene System

The 3D button being introduced now should eventually connect to a proper scene system.

Plan for:

* X/Y/Z Position
* Orientation
* X Rotation
* Y Rotation
* Z Rotation
* 3D Scale
* 3D parenting
* Cameras
* Lights
* Perspective
* Depth sorting
* Shadows
* Depth of field

Future expansion could include:

* Imported 3D models
* Materials
* Environment lighting
* More advanced rendering

These do not need to be implemented simply because the 3D UI control is being introduced now.

---

# 34. Cameras

Eventually support:

* Camera layers
* Field of View
* Focal Length
* Point of Interest
* Focus Distance
* Aperture
* Depth of Field
* Camera parenting

Viewport camera controls could include:

* Orbit
* Pan
* Dolly
* Look at Selected
* Frame Selected
* Active Camera
* Custom Views

Touch gestures should be considered rather than reproducing desktop mouse interactions directly.

---

# 35. Effects Architecture

Effects should use an ordered stack.

Conceptually:

Input
→ Effect 1
→ Effect 2
→ Effect 3
→ Output

Users should eventually be able to:

* Add
* Remove
* Reorder
* Disable
* Reset
* Duplicate
* Copy/paste
* Save effect stacks as presets

Every compatible effect parameter should use the Universal Property System so it automatically gains access to animation, expressions, reset, undo and other shared functionality.

---

# 36. Effect Search and Discovery

As the number of effects grows, add:

* Search
* Categories
* Favourites
* Recently used effects
* Presets

Consider eventually adding a global Command/Search interface capable of finding both effects and commands.

For example, searching for:

`blur`

could expose relevant blur effects.

Searching:

`precomp`

could expose Pre-compose.

Searching:

`fit`

could expose Fit to Composition commands.

---

# 37. Presets

Eventually allow users to save reusable:

* Animation presets
* Effect presets
* Effect stacks
* Transform presets
* Text presets
* Shape presets

Presets should be capable of containing animated properties where appropriate.

---

# 38. Advanced Time Controls

Beyond existing trimming and Time Remapping, plan for:

* Time Stretch
* Time Reverse
* Freeze Frame
* Frame Blending
* Optical Flow / advanced interpolation later

Time Remapping itself should use the Universal Property System rather than becoming an isolated animation implementation.

---

# 39. Work Area and Time Ranges

Distinguish:

* Composition duration
* Work Area
* Preview range
* Layer In/Out
* Export range

Export should eventually allow:

* Entire Composition
* Work Area
* Custom Range

---

# 40. Global Snapping System

Create one reusable snapping engine rather than separate snapping logic for individual tools.

Eventually allow snapping to:

* Composition edges
* Composition centre
* Other layer bounds
* Layer centres
* Anchor points
* Playhead
* Layer In/Out
* Keyframes
* Markers
* Grid
* Guides
* Mask points
* Shape vertices

Provide a global snapping toggle and eventually configurable snapping targets.

---

# 41. Guides, Rulers and Safe Areas

The View/Preview menu should eventually support:

* Grid
* Rulers
* Guides
* Guide locking
* Title Safe
* Action Safe
* Transparency Grid
* Snapping

Custom guides could be positioned through direct interaction or numerical controls.

---

# 42. Layer Management Controls

Plan for AE-level layer-management functionality such as:

* Visibility
* Solo
* Lock
* Shy
* Motion Blur
* 3D
* Adjustment
* Layer quality
* Guide Layer
* Collapse Transformations where applicable

Do not attempt to permanently display every switch on a phone-sized timeline.

Use contextual/progressive disclosure to preserve mobile usability.

---

# 43. Timeline Search and Filtering

Large compositions need strong navigation.

Eventually support searching/filtering layers by:

* Name
* Type
* Property
* Effect
* Animated state
* Expression
* Missing media
* 3D status

Quick filters could include:

**Animated · Effects · Text · 3D · Audio**

---

# 44. Layer Labels and Organization

Expand the layer-colour system into proper organizational functionality.

Eventually support:

* Rename
* Duplicate
* Colour labels
* Comments/notes
* Select by label
* Shy layers
* Useful grouping/organization mechanisms

---

# 45. Undo / Redo Architecture

Undo/Redo must be treated as a foundational editor system.

Nearly every editor operation should be reversible:

* Property changes
* Layer changes
* Keyframes
* Trimming
* Reordering
* Masks
* Effects
* Parenting
* Precomposing
* Text edits
* Shape edits

Continuous gestures should preferably collapse into a single logical undo operation.

For example, dragging Position continuously for three seconds should result in one undo action rather than hundreds of individual history entries.

---

# 46. Autosave and Crash Recovery

Fluxx should eventually support:

* Incremental autosave
* Atomic project writes
* Crash recovery
* Recovery snapshots
* Project version history where practical

Complex compositions must not be vulnerable to losing significant work because the app or OS terminates unexpectedly.

---

# 47. Media Management

Eventually support:

* Replace Footage
* Reload Footage
* Media Info
* Missing-media detection
* Relink Media
* Consolidate Project
* Collect Project/Media
* Source management

This becomes especially important on mobile where files may move between app storage, device storage and cloud providers.

---

# 48. Proxy Workflow

Proxy media should eventually be supported separately from Preview Resolution.

A high-resolution source could use a lightweight proxy during editing while retaining the original source for final rendering.

Preview Resolution and Proxies are different concepts:

**Preview Resolution:** changes the resolution at which the composition is rendered for preview.

**Proxy:** substitutes a lighter source asset during editing.

---

# 49. Render Queue

The current export pipeline can remain focused while the core editor develops, but Fluxx should eventually move beyond a single Export button.

Plan for a proper Render Queue supporting:

* Multiple compositions
* Multiple outputs
* Export ranges
* Resolution
* Frame rate
* Codec
* Bitrate/quality
* Alpha-capable output where supported
* Image sequences
* Saved output presets
* Queue management

---

# 50. Alpha Pipeline

Transparency should remain first-class throughout the renderer.

Alpha should behave correctly through:

Layer
→ Effect
→ Mask
→ Blend
→ Precomp
→ Composition
→ Export

Avoid architectural decisions that assume every composition ultimately has an opaque background.

---

# 51. Colour Management

The UI Colour Picker and actual render colour management should remain separate concepts.

Long-term architecture should account for:

* Working colour space
* Source interpretation
* Linear-light operations
* Wide-gamut media
* HDR
* Display transforms
* Export colour metadata

Full colour management does not need to be implemented now, but avoid deeply assuming all content will permanently be 8-bit sRGB.

---

# 52. Adaptive Preview

Consider an optional adaptive preview system for mobile hardware.

If Fluxx cannot maintain the target preview frame rate, it could temporarily reduce expensive preview operations such as:

* Resolution
* Effect quality
* Motion blur
* Shadows
* Certain high-cost calculations

Full quality returns when playback stops or during final rendering.

This should never affect final output.

---

# 53. Composition Performance Profiler

Eventually provide performance diagnostics for complex compositions.

For example, Fluxx could identify expensive:

* Layers
* Effects
* Precomps
* Motion Blur
* 3D rendering
* Masks

This would help users understand why a composition cannot preview in real time.

---

# 54. Essential Properties / Reusable Templates

Allow selected properties inside a Precomp to eventually be exposed to its parent composition.

For example, a lower-third Precomp could expose:

* Name
* Subtitle
* Accent Colour
* Logo
* Animation Speed

The user could modify those values without entering the Precomp.

This can eventually become the basis for reusable Fluxx motion-graphics templates.

---

# 55. Capability-Based Architecture

As Fluxx gains more layer types, avoid scattering checks throughout the UI such as:

`if video`
`if text`
`if audio`
`if camera`

Instead, layers should advertise capabilities.

Conceptually:

### Video

* Visual
* Transformable
* Maskable
* Blendable
* Effects
* Footage
* AudioSource

### Text

* Visual
* Transformable
* Maskable
* Blendable
* Effects
* TextEditable

### Audio

* Audio
* AudioEditable

### Camera

* Camera
* CameraSettings
* Transformable

### Null

* Transformable
* Utility

The Layer Inspector, utility rail, menus and available commands should derive their options from these capabilities.

This will become increasingly important as Fluxx gains Cameras, Lights, Adjustment Layers, Nulls, Precomps, Shapes, Text, Audio, 3D layers, Masks and Track Mattes.

---

# 56. Shared Editor Systems

Wherever possible, avoid implementing the same concept independently for different features.

Fluxx should gradually develop reusable core systems for:

* Properties
* Animation
* Keyframes
* Expressions
* Colour selection
* Snapping
* Selection
* Undo/Redo
* Inspector capabilities
* Contextual actions
* Layer relationships
* Viewport overlays
* Presets
* Rendering
* Caching
* Media references

For example, changing an Effect colour and changing Text colour should invoke the same underlying colour system.

Animating Mask Feather and animating Gaussian Blur should invoke the same underlying property/keyframe system.

Undoing a Shape edit and undoing a Transform change should go through the same history architecture.

---

# 57. Mobile-First Principle

After Effects is the reference for **power and expected behaviour**, not necessarily for UI layout.

Fluxx should preserve the depth of established motion-graphics workflows while improving interactions that do not translate well to touchscreens.

Examples include:

* Trackpad-style precision controls
* Hold + drag secondary interactions
* Context-sensitive Inspector controls
* Progressive disclosure instead of dozens of permanent timeline switches
* Gesture-based graph editing
* Searchable property/link targets
* Compact contextual menus
* Multi-touch camera navigation
* Context-aware Previous/Next controls

Where AE requires several desktop panels to perform something, consider whether Fluxx can provide the same underlying capability through a faster mobile interaction without reducing control.

---

# 58. Implementation Scope

These notes should **not be interpreted as instructions to implement every listed future feature during the current Phase 2 step**.

They define both immediate changes and the direction of Fluxx's architecture.

### Original Phase 2 interaction scope

The following was the original UI/interaction scope. Implementation status now belongs to the feature references and [Phase 2 matrix](../checkpoints/PHASE_2_STATUS_AND_BACKEND.md); this list is not a fresh instruction to reimplement them:

* Solid-control redesign
* Shared colour-picker foundation
* Layer Inspector redesign
* Effects tab
* Layer Styles tab
* Track Mattes / Masks tab
* Blending & Opacity rename
* Shared Inspector utility rail
* Transform 3D icon
* Position / Anchor Point interaction
* Selected-layer contextual menu
* Extract Audio location/interaction
* Timeline density
* Timeline hierarchy/reorder animation
* Timeline side-control redesign
* Inspector-close viewport restoration
* Preview Resolution control
* Preview Expansion relocation
* View/Preview menu
* Editor Action Bar spacing/layout
* Contextual Previous/Next behaviour
* Marker interaction

### Architect for now, expand later

Where current work touches these systems, avoid implementations that would prevent:

* Universal properties
* Advanced keyframes
* Graph Editor
* Expressions
* Parenting
* Precomps
* Masks
* Track Mattes
* Adjustment Layers
* Nulls
* Shape operators
* Text Animators
* 3D
* Cameras
* Effects stacks
* Presets
* Snapping
* Render Queue
* Proxies
* Advanced colour management
* Reusable templates

### Future functionality

Do not implement features merely because they are mentioned as architectural considerations.

Examples include:

* Autotrace
* Full Camera system
* Full 3D system
* Lights
* Optical Flow
* Advanced Shape operators
* Full Expression engine
* Text Animators
* Performance Profiler
* Essential Properties
* Full Render Queue

These are roadmap targets unless separately requested.

---

# Core Fluxx Principle

Fluxx should aim for **After Effects-level depth without reproducing After Effects' desktop interface limitations on mobile**.

When implementing an established motion-graphics concept, use After Effects as the behavioural reference unless Fluxx explicitly defines a better mobile interaction.

At the architecture level, prioritize reusable systems over isolated features.

A new feature should ideally plug into existing systems for properties, animation, expressions, undo, selection, colour, snapping, rendering and contextual UI rather than creating another independent implementation.

The long-term goal is for Fluxx to handle serious compositions with many layers, effects, masks, keyframes, expressions, precomps, text, shapes, audio and eventually 3D while still feeling intentionally designed for a touchscreen.
