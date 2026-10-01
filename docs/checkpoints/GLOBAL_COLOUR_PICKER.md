# Global colour picker and inspector grid

## Status — 2026-09-22

The user confirmed the previous picker works and prefers the expanded preview's 4-4 grid. The SV/hue controls, persisted palettes and draggable eyedropper below are newly implemented in source and await verification. No Gradle build, emulator creation, app launch, screenshots or phone tests were performed. **Galaxy A16 measured width remains unverified.** Device settings, insets and sheet padding must be measured on the user's build.

## Shared colour component

- `model/FluxxColor.kt` contains straight-alpha sRGB floats, HSV conversion, packed ARGB conversion and hex parsing. The Compose conversion extension lives in `ui/common/colorpicker/ColorPickerConfig.kt`, keeping the domain free of Compose/Android dependencies.
- `FluxxColorPicker` replaces both `CompactColorSectionContent` and the creation sheet's `RgbSlider` implementation. Inspector-wide touch-target sizing remains; the colour-specific compact branch is gone.
- A square saturation-value box (X = saturation, Y = inverse brightness) and 22dp vertical hue strip occupy the left column. `ColorWheel.kt` and the separate V slider are deleted; value has one visible control. The right column retains preview, editable hex, alpha/RGB sliders and eyedropper, followed by a single 22dp horizontally scrollable palette row. The square is bounded by height and reserves 135dp for the right column. Physical layout acceptance remains pending.
- `isDraggingColorControl` protects hue, saturation **and value** only during SV/hue drags, retaining full precision through ARGB round trips. RGB/A controls call the original gesture callbacks directly and resync the selectors live. Cancel restores pre-drag selector state; near-black/grey colours retain a meaningful hue, and the strip's 360-degree red endpoint is retained.
- Six-digit hex is **RRGGBB**, preserving current alpha; eight-digit hex is **AARRGGBB**. Commit occurs on Done/focus loss. Invalid input is marked and discarded on commit, leaving colour unchanged.
- Alpha is independent of layer opacity. Packed `solidColorArgb`, `SetColor` and FlatBuffers persistence are unchanged. The existing straight-alpha upload/compositor path combines colour alpha with layer opacity.
- Both call sites use the same app-scoped palette library. Creation has no eyedropper. Drag callbacks use the existing gesture protocol with `SetColor` previews and a single undo entry; hex and palette clicks are discrete edits.

## Persisted palettes

- `MainActivity` owns the singleton `PaletteRepository`, starts its idempotent load, and passes it through EditorScreen to both consumers. Palettes are independent of project FlatBuffers and live in `filesDir/color_palettes.json`.
- One `PaletteLibrary` StateFlow publishes the palette list and active ID together. All read/modify/write operations share an IO mutex; accepted writes run in an application-owned coroutine scope so picker dismissal cannot cancel them. State is published after `AtomicFile.finishWrite`; failures retain prior state and are reported. AtomicFile itself does not provide concurrent-writer locking, hence the repository mutex ([Android AtomicFile reference](https://developer.android.com/reference/android/util/AtomicFile)).
- JSON version 1 stores IDs, nullable names and eight-digit ARGB swatches. Unknown active IDs fall back to the first palette. A missing library seeds once with the exact 17-colour deduplicated union of the old palettes, including #222222. Corrupt or unsupported files are reported without overwriting them.
- Create starts empty and selects the new palette. Rename commits on Done, focus loss, Back or dismissal, never on each character. Delete requires confirmation and cannot remove the last palette. Swatches can be added, replaced and removed; 24 is a hard cap with a disabled save icon, never an overwrite policy.
- The nested swatch editor disables eyedropper and palette UI and receives no repository, preventing recursion. External palette import (.ase/.gpl) remains deferred.

## Eyedropper ownership and coordinates

- Entry commits any gesture and pauses playback. Touch and drag move the crosshair immediately; a colour dot displays the latest sample. Requests conflate to the newest position with one read in flight. The source project stays unchanged throughout the drag; release inside the composition applies one discrete colour edit. Back/gesture cancellation edits nothing; release outside the canvas keeps sampling mode active.
- `PreviewController.sampleColorAt` executes on the **existing FluxxPreview dispatcher**, renders a completed paused frame, then calls native readback. No Vulkan work runs on the UI thread. Close and sampling share dispatcher ownership; generation/project/selection checks prevent stale results from editing another document or layer.
- The UI supplies normalized **whole-workspace** coordinates from the full-size Canvas over the SurfaceView. It does not convert through `boxWidth`/`boxHeight` or derive another aspect-fit rectangle.
- `VulkanRenderer::render` records the exact integer destination rectangle used by its blit, including displayed-workspace to swapchain-buffer mapping. `readPreviewPixel` converts the normalized tap using those recorded values, rejects letterboxing, and samples the composition texel. The offscreen composition is sampled, excluding Compose overlays/crosshair; scaled bilinear display pixels can differ slightly from the selected source texel.
- Both offscreen render passes explicitly end in `TRANSFER_SRC_OPTIMAL`; the preview blit reads that layout and leaves it unchanged. The shared `readbackRgba` helper adds a render-write-to-transfer-read image barrier with that **actual** old/new layout, then a transfer-write-to-host-read buffer barrier and waits through `submitWork`. There is no guessed shader-read layout, nor a transition back to an unrelated layout. Export `copyYuv` reuses this helper.
- Readback reuses the renderer's mapped RGBA staging buffer. Repeated drag samples reuse the completed paused frame and its readback; `beginFrame` invalidates the native cache. Seek/resize/project generations invalidate the controller's sampled frame. JNI returns unsigned ARGB in a Long with -1 for failure/outside; valid transparent black cannot collide with the sentinel.

## Grid and shorter sheet

- All supported layer types show eight categories. Video/Image have **three functional and five inert** cells; Solid has four functional and four inert cells. This corrects the plan's inconsistent count for Edit Footage, which is inert.
- Expanded preview (268dp inspector budget, adjusted for font scale and available workspace) is now the default. The preview toggle remains available. Both preview sizes use 4-4: top row Timing / Mattes & Masks / Layer Styles / Blending & Opacity; bottom row Transform / contextual Edit / Motion Blur / Effects.
- Trim controls appear exactly once, on landing only. Property pages retain Back and functional quick tabs. Audio stays video-only in the header. Timing is the current label for every layer type.
- Blending & Opacity keeps functional opacity controls with disabled Normal blend-mode text. Graph and 3D remain inert in the shared Transform/Opacity rail. No backend blend, effect, mask, 3D or footage-editing capability is implied.

## Verification prepared, not executed

`FluxxColorTest` covers channel round trips (including RGB with zero alpha), HSV edges/wrap, hex validity and alpha conventions, colour gesture undo/cancel, and packed alpha persistence independent of layer opacity.

New Android tests use the real built-in `org.json` and `AtomicFile`, with no new dependency: `PaletteRepositoryTest` covers JSON/alpha, concurrent additions, cap/no overwrite, active selection/reopen, rename/edit/delete, corrupt-file preservation and saves surviving caller cancellation. `PaletteBrowserTest` checks that typing does not submit a rename and Done/Back do. None were run.

On the Galaxy A16 build:

1. Record actual window width/density and picker content width/height in Layout Inspector for both call sites, with standard and expanded preview. Test default and enlarged font/display settings. If the floor window is below 360dp, revisit the planned stacked fallback before signing off; no device-width claim is made here.
2. Verify the SV box/strip and right column fit without clipping. Drag near white/black and across both red strip endpoints; then drag RGB/A sliders and confirm live selector updates. Test invalid hex and cancellation.
3. Save/reopen a translucent solid over another layer, with layer opacity below 100%; verify independent colour alpha and opacity, undo/redo and export.
4. Start playback, enter eyedropper, verify pause, drag across known colours in wide/tall/square compositions, and release inside/outside the canvas. Repeat after resizing/rotation. Release must create one undo entry; Back must cancel; no stale result may reach another document or layer.
5. Run Vulkan validation on the readback and export paths; verify mapped readback after composition resize and resource cleanup.
6. Check the new 4-4 order for every layer type in both preview sizes. Check inert categories, functional-only tabs, Back, audio, resets and property-focused keyframes. Trim icons remain landing-only.
7. In both call sites, create/switch/rename/delete palettes, edit/remove/add swatches, fill to 24, and verify the disabled save icon and last-palette guard. Rename must persist on Done/focus loss/dismissal. Relaunch the process and verify colours (including alpha), names and active palette.
