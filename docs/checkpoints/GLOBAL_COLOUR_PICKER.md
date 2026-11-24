# Global colour picker and inspector grid

> **GRAPH G2.1 (2026-11-24): hex entry formats.** One parser, `FluxxColor.parseHex` / `FluxxColor.parseHexEntry`, sits behind every typed colour entry: the shared picker's hex field (`ColourValues.hex`), which covers Edit Text Fill/Stroke, markers, the swatch editor, Edit Solid and Add content → Solid, plus the text popover's `TextColourEdit.hex`. Animator colours use the same picker. It accepts any case, an optional leading `#` and surrounding whitespace:
> - **`RRGGBB`** sets the colour and **keeps the current alpha byte exactly** (opacity stays with the sliders).
> - **`AARRGGBB`** sets colour and alpha (unchanged order).
> - **`RGB`** shorthand: each digit is doubled, then it works like 6 digits.
> - Anything else is invalid and is discarded without change: 4, 5 or 7 digits (so `0000` reverts), non-hex characters, `0x…`, `##…`, or empty.
>
> 6- and 8-digit values still apply live while you type. A 3-digit value applies when the field is left (Done, blur or Back), still as one undo, so typing `00FF00` never flashes `#0000FF` at the third keystroke. The hex shown in the field is unchanged (8 digits). Tests: `HexEntryTest` (JVM table of every accepted and rejected form), `InlineValueFieldTest.threeDigitHexAppliesOnLeaveAsOneUndo`, and A16 `ColourHexEntryTest` (`000000` on a 50% red gives black at 50%; `#fff`, `40FF0000`; `0000` reverts). Record: [GRAPH G2.1 §2](GRAPH_G2_1.md#2-hex-colour-entry).

> **TEXT UI 8 (2026-11-18):** the FULL variant's controls row is exactly the SV square (no stretch), and the picker is centred vertically in leftover height. The marker popover uses COMPACT with `header = false`. See the Edit Text overhaul record in `TEXT.md`, section TEXT UI 8.

> **TEXT UI 7 (2026-11-18):** the shared `FluxxColourPicker` family (COMPACT popover / FULL page) replaced the original picker. The palette repository, eyedropper and hex rules below are unchanged.

## Status — 2026-09-22

The user confirmed the previous picker works and prefers the expanded preview's 4-4 grid. The SV/hue controls, persisted palettes and draggable eyedropper below are newly implemented in source and await verification. No Gradle build, emulator creation, app launch, screenshots or phone tests were performed. **Galaxy A16 measured width remains unverified.** Device settings, insets and sheet padding must be measured on the user's build.

## Shared colour component

- `model/FluxxColor.kt` contains straight-alpha sRGB floats, HSV conversion, packed ARGB conversion and hex parsing. The Compose conversion extension lives in `ui/common/colorpicker/ColorPickerConfig.kt`, keeping the domain free of Compose/Android dependencies.
- `FluxxColourPicker` (`ui/common/colorpicker/FluxxColourPicker.kt`) is the one shared picker, built on `SaturationValueBox`, `HueStrip` and `FluxxColor`. It has two variants: COMPACT for popovers (Edit Text Fill/Stroke, markers, swatch editor) and FULL for pages (Edit Solid, Add content → Solid). Each interaction is one `ColourTransaction`, so one undo. Layout sizes, the surface-to-variant table and in-place hex/alpha/RGB entry are owned by [TEXT UI 7](TEXT.md#4-colour-picker-family) and [TEXT UI 8](TEXT.md#text-ui-8-marker-popover-centred-layer-keyframes-consistent-control-sizing).
- The picker's local drag state protects hue, saturation **and value** only during SV/hue drags, retaining full precision through ARGB round trips. RGB/A controls call the original gesture callbacks directly and resync the selectors live. Cancel restores pre-drag selector state; near-black/grey colours retain a meaningful hue, and the strip's 360-degree red endpoint is retained.
- Six-digit hex is **RRGGBB**, preserving current alpha; eight-digit hex is **AARRGGBB**. Valid complete values apply live in place, with one undo per edit session; invalid or incomplete input applies nothing and reverts on blur.
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
- Expanded preview (268dp inspector budget, restored in TEXT UI 6 after TEXT UI 5 briefly used 308dp; adjusted for font scale and available workspace) is now the default. The preview toggle remains available. Both preview sizes use 4-4: top row Timing / Mattes & Masks / Layer Styles / Blending & Opacity; bottom row Transform / contextual Edit / Motion Blur / Effects.
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
