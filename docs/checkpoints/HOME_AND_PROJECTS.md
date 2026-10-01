# Home, Projects & Composition Creation

**Status:** Implemented in source; Android debug Kotlin compilation passed during E1b/E1c. Home/project-specific phone acceptance remains pending.

---

## 1. Shell Navigation & Liquid Bubble Create Button

- **Floating Navigation Pill:**
  - Single floating 64dp-tall rounded pill with a 12dp drop shadow and safe-area insets.
  - Houses the **Home** tab, a central create-button docking slot, and the **Projects** tab.
  - Tab spacing dynamically expands from 80dp on Home to 140dp on Projects as the user swipes.
- **Morphing Create Button:**
  - One continuous semantic button floating above the pager.
  - Starts as a 64dp circular "New project" action on Home.
  - Interpolates position, size (64dp → 48dp), and corner radius, docking into the center slot between Home and Projects with a curved connecting liquid bridge.
  - Tapping on Home triggers a 150ms press / 250ms recovery pulse; rapid repeated taps are ignored.
  - When docked in Projects, tapping opens the identical composition creation sheet directly.
  - Pager dragging and nav taps share unified spring physics (damping 0.55, stiffness 300).

---

## 2. Projects Screen & Document Operations

- **Project Listing:**
  - Sourced directly from `ProjectManager.listProjects()`.
  - Lazy vertical list of project cards (minimum height 96dp to support font scaling and wrapped metadata).
  - Shows project title, aspect ratio, frame rate, duration, and relative edited dates (delegated to Android `DateUtils`).
- **Rendered Thumbnails:**
  - Powered by `ProjectThumbnails`.
  - Renders time zero of the actual composition using `CompositionRenderer` (rather than a raw source video thumbnail).
  - Cached in an 8 MiB in-memory cache and bounded by a single serialized background worker.
  - Empty or unsupported projects display a neutral fallback glyph.
- **Sorting Modes:**
  - **Date Modified:** Newest edits first.
  - **Date Created:** Filesystem creation date (falling back to modified date if unsupported by the device filesystem).
  - **Name:** Alphabetical sorting grouped under explicit **A/B/C section headers**.
- **Card Actions:**
  - **Rename:** In-place rename modal validating input against existing filenames and filesystem rules.
  - **Duplicate:** Clones the `.fluxx` file, respecting `AtomicFile` backup conventions.
  - **Delete:** Deletes project and its atomic backups with a confirmation dialog.
  - **Missing Media Badges:** Proactively inspects referenced asset URIs and displays an alert badge if permissions or files are unavailable.

---

## 3. Composition Creation Sheet

- **Layout & Contrast:**
  - Bottom sheet with an inner wrap-content cap at 85% window height (including bottom navigation bar insets), preventing interference with Material 3 `draggableAnchors`.
  - Primary button labels ("Create Project", "Save Preset") explicitly use high-contrast near-black on the accent background.
  - Background window is blurred on API 31+; clean dark scrim fallback on API 29–30.
- **Configuration Controls:**
  - **Presets:** Built-in standard aspect ratios (9:16, 16:9, 1:1, 4:5, 2.39:1) and custom saved presets.
  - **Custom Geometry:** Numeric fields for custom width and height (restricted to 1080p maximum pixel area; rejects odd dimensions).
  - **Frame Rate & Duration:** 12, 14, 18, 20, 24, 25, 30, 50, 60 and 120 fps presets. New projects have automatic, layer-derived duration and start empty at zero; no creation-time Seconds field. See [Timeline Layout](TIMELINE_LAYOUT.md).
  - **Background Canvas:** Empty projects use the compositor's black clear colour; add a solid layer for coloured content.
  - **Presets Management:** Long-press saved presets to delete with confirmation.
- **Placeholders:**
  - **Motion Blur:** Rendered but disabled.
  - **3D Mode:** Tab renders a "Coming Soon" indicator.
- **Creation Flow:**
  - Saves new `.fluxx` document atomically via `ProjectManager`.
  - Opens directly into `EditorScreen` at the initial frame.

---

## 4. Phone Verification Checklist

- [ ] **Pager Swiping:** Swipe between Home and Projects; confirm the create button fluidly travels into the central dock.
- [ ] **Navigation Taps:** Tap Home and Projects tabs; verify spring settling and indicator alignment.
- [ ] **Project Cards:**
  - Verify thumbnails render the composition's first frame.
  - Test Name sorting (A/B/C headers) vs. Date Modified sorting.
  - Test Rename, Duplicate, and Delete actions.
- [ ] **Creation Sheet:**
  - Tap create on Home and in Projects dock; confirm sheet opens reliably.
  - Verify all fields are reachable without clipping or awkward scrolling.
  - Confirm Create Project button label is high-contrast black.
  - Create a new project with custom dimensions and confirm it launches the editor.
