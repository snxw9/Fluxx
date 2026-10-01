# Media Browser & Content Import

**Status:** Implemented in source; Android debug Kotlin compilation passed during E1b/E1c. Browser/import-specific phone acceptance remains pending.

---

## 1. Add Content Sheet & Full-Screen Browser

- **Add Content Sheet:**
  - Opens beneath the editor preview (preventing overlap with playback).
  - Top category row: **Media** and **Solid**, plus disabled future types (Shape, Text, Adjustment, Camera, Null).
  - Media sub-pills for **Video**, **Image**, and **Audio**.
  - Folder pills for fast filtering across MediaStore device folders.
  - Quick Browse button opens the full-screen browser view.
- **Dedicated Full-Screen Browser:**
  - Dedicated media-only browsing interface.
  - Preserves selected media type, folder, and multi-selection state when navigating Back to the add sheet.
  - Detail area features an aspect-bounded image preview or an interactive video player.
  - **Filmstrip Scrubbing:** Horizontal dragging across the filmstrip seeks video playback with an anchored playhead cursor and time display. Tapping a thumbnail jumps directly to the sampled frame.

---

## 2. Selection & Import Mechanics

- **Inspection & Hold-to-Select:**
  - Long-pressing a thumbnail reveals metadata: filename, dimensions, aspect ratio, duration, and frame rate.
  - Releasing without dragging enters **multi-selection mode**; subsequent single taps toggle items with animated checkmarks across folders and filters.
- **Sequential Batch Import:**
  - Multi-selected items are imported sequentially in selection order via `EditorAction.Add`.
  - Each layer maintains its own undo entry and stable layer ID.
  - Failures leave remaining un-imported items selected and surface an error message.
  - Project generation tokens guard against in-flight imports targeting a newly switched or reloaded project.
- **Performance & Virtualization:**
  - Paginated MediaStore queries.
  - Concurrency limits: 3 permits for thumbnail generation, 1 permit for filmstrip extraction.
  - Automatic bitmap downsampling; grid cells do not instantiate ExoPlayer/MediaCodec instances.
  - Opening the browser automatically pauses composition preview playback.

---

## 3. Audio Browsing (Read-Only)

- Audio sub-pill displays categorized lists of **Songs**, **Albums**, and **Artists**.
- Requires `READ_MEDIA_AUDIO` permission on Android 13+ (or legacy read storage permission).
- **Current Limitation:** Browsing is currently read-only; audio import is deferred until an audio-only layer model (`LayerType.AUDIO`) and pipeline are implemented; this is not part of the current text Step E.

---

## 4. Phone Verification Checklist

- [ ] Filter by MediaStore folders and paginate through large photo/video libraries.
- [ ] Tap Browse to open full-screen browser and return via Back.
- [ ] Test video detail playback: tap to play/pause, drag filmstrip to scrub.
- [ ] Long-press to view video metadata, then release to activate multi-selection.
- [ ] Select multiple videos/images and import them; verify correct track placement in timeline.
- [ ] Open Audio tab, grant audio permission, and browse Albums/Songs.
