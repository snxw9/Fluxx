# Text phase

**Status (2026-11-22): complete.** The user has declared the text phase done; the next big item is the [graph editor](../architecture/FLUXX_ROADMAP.md#next-big-item-graph-editor-planned-not-started). This one document consolidates every text plan, spec and stage record, in build order. Each stage record below is kept as written at the time: its "device-unverified" or "pending" notes describe the evidence available then. Phone acceptance of the final Animate work (F3.1–F3.3) is done (user-confirmed, 2026-11-22). Work is on the single `text` branch, merged to master.

## What the phase built

| Stage                     | What                                                                                                                                       | Evidence                                                    |
|---------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| E1–E3                     | Native shaping, CPU SDF, Vulkan glyph rendering, the text layer, schema v9 and the first Edit Text                                         | user-confirmed on the A16                                   |
| F1                        | Tracking, leading, baseline shift, stroke and paint order (v10)                                                                            | user-accepted                                               |
| F2                        | The selector / animator engine (v11) and per-glyph prepared rendering                                                                      | JVM / host; later device suites in FIX 9 and PERF 10/11     |
| T1–T4                     | Rich styled text (v12), font import and variations, paragraph / box text, Wiggly selector, all 25 animator properties                      | user-tested on the phone                                    |
| FIX, EXPLODE              | Case / script / selection fixes; Explode Text                                                                                              | committed; T1–T4 user-tested                                |
| Edit Text UI, TEXT UI 5–8 | The fixed, non-scrolling Character / Paragraph faces, popovers, colour picker family, marker popover                                       | A16 user reviews through TEXT UI 6, then F3.x               |
| TEXT FIX 9, PERF 10/11    | Import regression, overlap-safe SDF and sharp tiers; measured frame-time fixes, one GPU submit per frame for text, resume / rotation fixes | device-assisted A16 passes                                  |
| F3 … F3.4                 | The Animate tab, its rework, AE parity fixes, popover / navigation / swipe polish, the Tracking cap                                        | JVM + A16 instrumented; phone acceptance done for F3.1–F3.3 |

## Carried forward (open, not text-phase blockers)

- **T5, Arabic / Hebrew direction:** stopped. It needs a cross-style shaping ownership decision; stored RTL is still rejected for rendering ([evidence](#rec-text-fix-explode-t5)).
- **Performance:** a non-debuggable measurement build; animator threading or a batch/draw contract change for 30 animated layers under 16.6 ms p95; the [PERF 11 open items](#still-open-1).
- **Separate verification:** the 16 KB page-size and golden checks recorded in the [text layer record](#rec-text-layer) remain outstanding.
- **Possible follow-up:** a non-linear Tracking slider (F3.4 §1), only if wanted.

## Contents

- [Phase 2 Step E — Revised Native Proof Plan](#rec-text-layer-plan)
- [Text layer checkpoint](#rec-text-layer)
- [F1: Text property completion](#rec-text-f1)
- [Text Animation: F1–F3 Plan](#rec-text-animation-plan)
- [Text completion spec: T1–T5 (verbatim)](#rec-text-completion-spec)
- [FIX → EXPLODE → T5 continuation](#rec-text-fix-explode-t5)
- [Variable-font named-instance correction — 2026-10-09](#rec-text-variable-instance-fix)
- [Proposed fourth work item: Text editing UI improvements](#rec-text-ui-proposal)
- [Edit Text consolidation — approved plan](#rec-text-ui-plan)
- [Edit Text overhaul implementation — 2026-10-09](#rec-text-ui-overhaul)
- [TEXT FIX 9: font import regression, Playfair overlap hole, sharp imported/animated glyphs](#rec-text-fix-9)
- [TEXT PERF 10: text frame-time investigation and fixes](#rec-text-perf-10)
- [TEXT PERF 11: multi-layer text cost, resume/rotation bugs, imported-font latency](#rec-text-perf-11)
- [TEXT F3: Animate tab (animator UI)](#rec-text-f3)
- [TEXT F3.1: Animate tab rework, crash fix, limits, global Prev/Next](#rec-text-f3-1)
- [TEXT F3.2: AE parity fixes, seed flicker, export/playback, Animate polish](#rec-text-f3-2)
- [TEXT F3.3: popover exit, Prev/Next fall-through, sheet swipe, small polish](#rec-text-f3-3)
- [TEXT F3.4: Character Tracking slider cap and docs fixes](#rec-text-f3-4)

---

<a id="rec-text-layer-plan"></a>

## Phase 2 Step E — Revised Native Proof Plan

*Formerly `docs/checkpoints/TEXT_LAYER_IMPLEMENTATION_PLAN.md`.*

**Later authorization/status:** E1d, E1e, E2 and E3 are implemented in source and compiled; all new text device behavior remains unverified. Native debug/release compilation passed for arm64-v8a and x86_64, Kotlin debug/release and instrumentation sources compiled, and the final JVM run passed 150/150 tests. No APK was installed or launched. See [stage evidence and exact user-run checklists](#rec-text-layer). The approved overflow policy uses lazy texture-array growth; range selectors and text animators remain future work.

**Text animation scope:** size/fill and hold-keyed Source Text use the existing property framework; see [section 3.1](#31-universal-text-property-animation). This contract is now implemented under the later authorization above. Original planning and execution-gate wording below is historical; current evidence is in the checkpoint.

**Execution update, 2026-10-01:** the user confirmed E1a A16 render/playback/export smoke and explicitly authorized E1b/E1c in one pass. Both CPU passes are implemented; see [actual results](#rec-text-layer). This overrides the individual stop between E1b and E1c in the original gate table. E1d remains unstarted and requires the user's E1a 16 KB environment run and fixed golden-project comparison. E2/E3 remain outlines; device typography verification is pending.

### 1. Decisions and audit findings

E1 proves native text rendering and resource ownership. It adds no persisted text fields or editor controls. E2 and E3 remain outlines and begin only after E1 device acceptance.

**Confirmed in the current source:**

| Finding                                                                                                                                             | Consequence                                                                                                               |
|-----------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `CompositionRenderer` flushes after four staged layers. Native code separately limits pending entries and resident hardware-buffer imports to four. | Glyphs must not become individual layer entries or hardware buffers.                                                      |
| `flushBatch()` currently issues one six-vertex draw per imported layer.                                                                             | Add a dedicated text draw type within the existing ordered batch.                                                         |
| `submitWork()` waits for `mWorkFence` before returning.                                                                                             | E1 can safely use one fence-protected staging/mesh slot; there is currently no asynchronous multi-frame compositing ring. |
| Preview, export and thumbnails create independent renderer sessions.                                                                                | GPU atlases remain per renderer/device.                                                                                   |
| `samplingRenderer` aliases the preview renderer.                                                                                                    | Eyedropper sampling does not require another atlas or ownership domain.                                                   |
| `LayerGeometry` always applies aspect-fit sizing.                                                                                                   | Text needs an explicit natural-pixel geometry path.                                                                       |
| `LayerType.TEXT` is ordinal 3; the schema stores `type` as an integer. No text payload fields exist.                                                | E2 adds fields without changing existing type ordinals.                                                                   |
| Text currently reaches an asset-required raster branch; export validation explicitly rejects it.                                                    | Existing Text labels/icons are not evidence of rendering support.                                                         |
| `ClipGold = SurfaceElevated`, `ClipGoldText = TextPrimary`.                                                                                         | The existing text clip tokens already satisfy the monochrome design.                                                      |

**Selected decisions:** single-channel SDF; per-renderer GPU resources; native font ownership; shared native shaping/measurement; debug-only injection through the real composition path; NDK r28 upgrade, as selected.

### 2. E1 — Detailed implementation

#### Mandatory internal gates

Each gate requires the user's explicit verification before implementation of the next gate begins. Implementation completion, source inspection, or an unrun test is not gate acceptance. Builds and phone testing remain user-run unless explicitly requested.

| Gate    | Scope                                                                              | Evidence required before proceeding                                                                                                              |
|---------|------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **E1a** | NDK r28c upgrade and 16 KB compatibility; no text code or new native dependencies  | Packaged-library alignment report, APK alignment, 4 KB/16 KB runtime checks, and existing Video/Image/Solid/audio regression results             |
| **E1b** | Vendored FreeType/HarfBuzz and static fonts; CPU-only JNI shaping                  | Checksummed inputs and glyph IDs, clusters, offsets and advances matching `hb-shape` from the same HarfBuzz release/font binaries                |
| **E1c** | CPU-only 48ppem SDF generation and PNG/contact-sheet dumps                         | Visual inspection of overlapping contours, 8/12/14/16px reconstructions, actual atlas packing capacity, and approved 1024/2048 allocation policy |
| **E1d** | Atlas upload and text pipeline render one string through the real composition path | Correct visible output with validation enabled; atlas repack performs zero rerasterization of retained glyphs                                    |
| **E1e** | Scale/quality matrix, mixed batches, lifecycle and teardown stress                 | Visual matrix plus passing native-heap and live GPU-resource criteria below                                                                      |

#### E1a — Toolchain and compatibility plan

**Implementation checkpoint:** [TEXT_LAYER.md](#rec-text-layer) contains E1a changes, input audits, the fixed golden workflow and verification status. Source builds of Oboe/graphics-path are retained, but the initial RELRO-only incompatibility claims were withdrawn after correcting the verifier. Final packaged-APK/runtime acceptance remains pending.

1. Preserve or record the existing r26 build's regression results before changing the toolchain. Record project revision, tested APK hash, device/OS/GPU and page size; distinguish pre-existing failures from new ones.
2. Change `ndkVersion` from `26.1.10909125` to `28.2.13676358` in both `app/build.gradle.kts` and `core-engine/build.gradle.kts`. Retain CMake 3.22.1, C++17, `c++_shared`, both existing ABIs, SDK levels, AGP, Oboe and other dependency versions.
3. Add `target_link_options(fluxxengine PRIVATE "-Wl,-z,max-page-size=16384")` in `core-engine/CMakeLists.txt`. Audit app-owned native code for fixed-page-size assumptions in mapping/alignment operations; only replace actual OS-page assumptions with runtime page-size queries, not unrelated 4096-sized buffers.
4. Supply a read-only PowerShell verification script accepting a user-built APK plus SDK/NDK locations. Extract into a unique temporary directory, enumerate every packaged `.so` in both ABIs, inspect `llvm-readelf -lW` output and fail if any `PT_LOAD` alignment is below `0x4000` or offset/virtual-address congruence is invalid. Inventory GNU_RELRO; its raw endpoints need not be page-aligned because the loader rounds them. Run `zipalign -c -P 16 -v 4` on the original APK. Report per-library hashes, alignment and origin; do not rewrite the APK, run Gradle or install anything.
5. Verify both user-built debug and release APKs. Check `libfluxxengine.so`, `libc++_shared.so`, any packaged Oboe library and every transitive native binary, rather than assuming the library inventory. A failing prebuilt blocks acceptance; identify its owner and propose a focused remediation rather than upgrading unrelated dependencies silently.
6. User verifies the existing compositor on the 4GB phone and a Vulkan-capable 16 KB environment, recording `adb shell getconf PAGE_SIZE`. A 4 KB phone alone cannot establish 16 KB runtime compatibility. In each environment check launch, layer rendering, playback/seek and shutdown without linker errors or crashes.
7. Regress Video (including audio), Image, Solid, a mixed composition with more than four visible layers, transform/opacity/keyframes, Full/Half/Quarter preview, surface recreation/backgrounding, thumbnails and headless export. Exercise both the unchanged single-video fast path and mixed-layer Vulkan export. Check sound, mute, play/pause/seek and no new A/V drift or doubled audio relative to the baseline.
8. Record changes and evidence in `TEXT_LAYER.md`, linking it from the documentation index and roadmap; keep status "E1a implemented in source — verification pending" until the user verifies all checks. Stop for that verification. No E1b code, font downloads, shaping, SDF, shaders, text harness, model or editor changes belong in E1a.

#### A. Reproducible native build and fonts

Pin both Android modules to **NDK r28c, `28.2.13676358`**, retaining both existing ABIs and C++17. Keep CMake 3.22.1 unless an actual dependency configuration failure requires a separately documented change.

Vendor these sources rather than downloading during configuration:

- **FreeType 2.14.1**, built statically with PIC.
- **HarfBuzz 14.3.1**, built statically with PIC from the amalgamated `harfbuzz.cc` as one translation unit, with a repository-owned CMake wrapper and explicit `HB_NO_*` trimming. Preserve OpenType shaping, Unicode, kerning, ligatures and required font functions. Validate the selected trimming against the untrimmed pinned `hb-shape` reference in E1b.
- Disable unused external integrations, tools, tests and optional font-compression dependencies. HarfBuzz uses its OpenType font functions; FreeType rasterizes the same font bytes. Disable FreeType’s optional HarfBuzz auto-hinting dependency to avoid a circular build dependency.

Record upstream release, commit and SHA-256 checksums alongside each vendored dependency. Keep third-party compiler warnings separate from Fluxx’s `-Werror`. These are explicit version pins, not claims about the latest releases. [FreeType release](https://github.com/freetype/freetype/releases/tag/VER-2-14-1), [HarfBuzz release](https://github.com/harfbuzz/harfbuzz/releases/tag/14.3.1).

Direct remote verification on 2026-09-30 resolved HarfBuzz tag `14.3.1` to tag object `e4580ea56b5b3d01082c5d1f77b233d843438504` and peeled commit `ab5ecbb83985034a76214ac0b2b833dcd590d774`. This confirms the tag; it is not an archive checksum. E1b must record SHA-256 of the exact source archives, static font binaries and license files for FreeType 2.14.1, HarfBuzz 14.3.1, Inter 4.1, Noto Serif 2.014 and JetBrains Mono 2.304. Do not substitute versions merely because a download is temporarily unavailable.

Retain an explicit `-Wl,-z,max-page-size=16384` on Fluxx-owned shared targets. Verify **every packaged `.so`**, including `libc++_shared.so`, Oboe and any debug validation libraries, with `llvm-readelf -lW`: every `PT_LOAD` alignment must be at least `0x4000`. Also verify APK ZIP alignment independently. Static archives do not have ELF load segments; their code inherits the final shared library’s alignment. NDK r28 defaults help but do not certify third-party binaries. [Android 16 KB guidance](https://developer.android.com/guide/practices/page-sizes).

Bundle unmodified **static Regular TTFs**, not variable font builds. E1b verifies the actual binaries have no variation axes; do not rely on filenames alone:

| ID            | Face pin             | License |
|---------------|----------------------|---------|
| `fluxx.sans`  | Inter 4.1            | OFL 1.1 |
| `fluxx.serif` | Noto Serif 2.014     | OFL 1.1 |
| `fluxx.mono`  | JetBrains Mono 2.304 | OFL 1.1 |

Ship each font’s original license and record the exact binary checksum. E1’s primary fixture uses Inter; additional fixtures verify the other faces. JetBrains Mono’s license is **OFL**, correcting the supplied draft. [Inter license](https://github.com/rsms/inter/blob/v4.1/LICENSE.txt), [Noto license](https://github.com/notofonts/noto-fonts/blob/main/LICENSE), [JetBrains Mono licensing](https://www.jetbrains.com/lp/mono/).

Documentation includes the required FreeType FTL attribution and HarfBuzz Old MIT notice; package the full notices too. [FreeType FTL](https://raw.githubusercontent.com/freetype/freetype/VER-2-14-1/docs/FTL.TXT), [HarfBuzz license](https://raw.githubusercontent.com/harfbuzz/harfbuzz/14.3.1/COPYING).

#### B. Deliberate rendering choice

| Approach           | Advantages                                                                       | Costs                                                                                                                                                      |
|--------------------|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Android bitmap run | Faster initial integration; platform layout support; existing raster upload path | Whole-run regeneration for content changes; scale-quality/memory tradeoff; future per-glyph animation requires replacing or subdividing the representation |
| Native glyph atlas | Shared glyph storage, native shaping, independently positioned glyph quads       | New native dependencies, layout integration, upload synchronization and cache management                                                                   |

**Choose the native atlas.** Bitmap runs are viable for static titles, but provide the wrong long-term representation for Text Animators. Position-only animation would not require bitmap regeneration, and GPU transforms are inexpensive rather than “zero-cost.”

#### C. SDF policy and bounded cache

Use **FreeType-generated single-channel SDF**, not an enlarged alpha bitmap:

- Rasterize unhinted outlines at **48 pixels per em**, with **8-pixel distance spread**.
- Store in `VK_FORMAT_R8_UNORM`, with one additional guard texel around each allocation.
- Use linear sampling, clamp addressing and derivative-based edge smoothing around FreeType’s encoded zero-distance threshold.
- Glyph cache key: resolved font binary identity, face index, glyph ID and SDF-generation settings. Layer scale, preview quality and font size do not create new raster buckets.
- Derive advances and layout from unhinted font metrics. SDF padding never changes layout bounds.
- Set HarfBuzz scale to the face's units-per-em on both axes and preserve shaping positions in design units until floating-point conversion to composition pixels; do not round advances to 48ppem raster pixels.
- Verify format sampling/filtering support during renderer initialization; report an explicit initialization error if unavailable.

E1c dumps both raw SDF PNGs and reconstructed-coverage contact sheets without creating a Vulkan device. Inspect the pinned static faces' self-overlapping/composite contours and compare FreeType's `sdf` module `overlaps` property disabled/enabled, checking property-call results explicitly. Record glyph IDs, settings and any artifacts; select the artifact-free setting before E1d. An unsupported property or persistent artifact blocks this gate rather than silently using an assumed setting. Include 8px as a diagnostic and 12/14/16px as acceptance fixtures. The proposed 8px editor minimum remains provisional pending these measurements.

FreeType exposes SDF rendering and configurable spread. [SDF properties](https://freetype.org/freetype2/docs/reference/ft2-properties.html#spread).

This removes bucket churn during animated scale. It does **not** promise unlimited sharpness: small text can lose detail, and corners can soften at extreme magnification. Quarter preview necessarily loses information when its reduced render target is enlarged; acceptance compares against the resolution’s achievable result, not Full-resolution detail.

**E1 capacity policy:**

- Start with one **1024×1024 atlas per renderer**, allocated lazily: 1 MiB of texel storage, plus Vulkan allocation overhead. In E1c pack actual unique glyphs from each face and the combined Latin fixture corpus, including spread/guards; report glyph count, occupancy, fragmentation and bytes rather than assuming a 300–400-glyph capacity.
- Evaluate lazy growth to **2048×2048 R8** (4 MiB of texels) using the same corpus. Proposed policy: retain 1024 while the active set fits; grow once at an idle frame boundary if it does not fit and the device supports 2048. Do not shrink during the session. E1c user verification locks this allocation policy before E1d.
- Skyline packing and least-recently-used glyph records across frames.
- Retain immutable 48ppem SDF pixels, dimensions, bearings and generation parameters in a CPU-side cache under the same glyph key. Pin the CPU bitmap for every resident atlas glyph and every pending-frame glyph; bound unpinned cache entries with an 8 MiB LRU budget, evicting matching unpinned GPU residency at a safe frame boundary if needed. Referenced bitmap storage cannot be freed during staging preparation.
- Before recording a frame, pin its required glyph set. If space is insufficient, wait for device idle, evict unpinned records and repack retained/current glyphs.
- Repack copies retained SDF pixels from the CPU cache and uploads them; it never rerasterizes retained glyphs or acquires the font-face mutex. Rasterization is only for genuinely new glyphs in preparation. Instrument and assert zero retained-glyph rasterization calls during repack.
- Increment an atlas generation and rebuild affected UV meshes after repacking.
- Never evict, repack or change UV assignments during that frame’s draws.
- If the required set cannot fit even after repacking, return a typed capacity error **before rendering any part of the frame**. Do not silently omit glyphs or loop through rebuilds.

That last case is an explicit **E1 proof limitation**, not a production text limit. E2 must resolve larger working sets with bounded paging/streaming before general Text support ships. It must not introduce a project layer-count limit.

Missing characters use the resolved face’s `.notdef`; if that glyph has no visible outline, generate a deterministic outlined box with a nonzero advance. Whitespace remains advance-only. Unknown font IDs resolve to `fluxx.sans`; preserve the requested ID in future documents.

#### D. Native ownership and cross-thread lifetime

Use a **native `FontManager`**, not Kotlin-owned FreeType pointers.

| Resource                                                     | Owner and access                                                  |
|--------------------------------------------------------------|-------------------------------------------------------------------|
| Immutable font bytes                                         | Native font catalog, reference-counted                            |
| `FT_Library`, `FT_Face`, HarfBuzz font/face handles          | Native `FontManager`; all mutable face operations under its mutex |
| Shaped layouts and metrics                                   | Immutable native records; bounded CPU cache; no Vulkan handles    |
| Atlas image, descriptors, pipeline, staging and mesh buffers | Owning `VulkanRenderer` session only                              |

`FT_New_Memory_Face` receives stable native storage. Font bytes must outlive the FreeType face and any HarfBuzz blob referring to them. Copy assets into owned storage; do not retain temporary JNI arrays or pointers into closed Android assets.

Preview runs on `FluxxPreview`. Export and thumbnail renderers are used serially within their existing background render operations; no renderer may be called concurrently. A future UI metrics worker accesses only the native layout service, never a renderer’s GPU state.

The shared font mutex can stall preview while export prepares uncached glyphs. Accept this for E1, instrument lock-wait time, pre-warm complete fixture glyph sets during preparation, and **never hold the mutex while waiting on Vulkan**. Per-renderer atlases prevent GPU sharing; they do not eliminate CPU contention.

**Teardown order:**

1. Stop accepting work and complete/cancel the owning render operation.
2. Wait for GPU idle; handle device-loss cleanup without reusing resources.
3. Destroy text pipelines, descriptor resources, mesh/staging buffers, atlas view/image/memory and sampler before their device.
4. Release layout references and font leases; destroy HarfBuzz handles before corresponding faces, then FreeType faces/library before their backing bytes.
5. Continue existing renderer/device/surface cleanup.

Surface-only recreation retains device-owned atlas resources where the existing lifecycle permits it. Full renderer recreation creates a fresh atlas. Both paths receive tests.

#### E. Real layer path, preparation and synchronization

Replace the implicit “every pending entry is an imported image” assumption with an ordered draw entry supporting **raster or text**.

- One text layer consumes **one pending layer entry**.
- Text does not enter the four-entry hardware-buffer import cache.
- A text entry binds the SDF pipeline and draws all its visible glyphs using one indexed mesh: four vertices and six indices per glyph, with 32-bit indices.
- Preserve layer ordering across mixed raster/text batches and existing clear-versus-load render passes.
- Apply the layer matrix, fill alpha and evaluated opacity through the text pipeline. Use straight-alpha output compatible with the current compositor.
- Disable face culling for text so negative scales preserve Flip H/V.

Additive bridge operations provide:

- **Upsert layout:** layer ID plus content revision/hash and complete layout inputs, sent only when content changes.
- **Prepare text:** the active layout handles required by the upcoming frame.
- **Queue text layer:** layout handle, matrix, packed fill colour and opacity.
- **Release layout:** remove renderer references when content/layers disappear.

Native code performs shaping and retains the result. Strings are not resent every frame; hashes are checked against complete keys to avoid collision-based reuse. Transfer Java text with explicit UTF-16 handling, not assumptions about JNI modified UTF-8.

**Frame sequence:**

1. Resolve changed layouts and collect all missing glyphs for the frame.
2. Plan atlas allocations and any frame-boundary repack.
3. Wait for the preceding work fence before modifying reusable staging/mesh storage.
4. Fill one staging buffer and record one `vkCmdCopyBufferToImage` with multiple regions.
5. Transition the atlas from shader-read to transfer-destination with fragment-read → transfer-write synchronization; transition back with transfer-write → fragment-read synchronization. First use starts from `UNDEFINED`.
6. Only then begin render passes and record ordered draws.
7. Retain all submitted buffers and atlas references until the existing work fence signals.

Flush noncoherent mapped memory correctly. Resize or replace mesh buffers only after their fence completes. E1 keeps the existing synchronous submission model; introducing multiple frames in flight would require per-slot storage and fence-delayed destruction and is outside E1.

#### F. Debug-only trigger and measurable proof

Add a debug fixture provider consumed **inside `CompositionRenderer`**, guarded by `BuildConfig.DEBUG`, with a release no-op implementation.

An Android instrumentation entry point selects named scenarios, launches a debug-only surface host and drives the renderer on its owner worker. It requires no editor controls, saved project or `PreviewController` API change. Normal debug editor sessions do not inject text unless explicitly enabled.

The provider creates transient text render entries and submits them through the same layout, geometry, batch, opacity and draw path intended for E2.

Fixtures include:

- Inter `"AV"` for kerning and Noto Serif `"office"` for standard ligatures, with explicit `kern`/`liga` features. Compare glyph IDs, clusters, offsets and design-unit advances against the matching pinned `hb-shape`; test feature-on/off output rather than assuming Inter supplies `ffi`.
- Separate descender/newline and overlapping-contour fixtures from the pinned static faces.
- A deliberately unsupported code point for `.notdef`.
- 72px text at 50%, 100% and 300%, plus a continuous scale sweep.
- 12px, 14px and 16px text at Full/Half/Quarter, plus an 8px diagnostic that informs the later editor minimum.
- Opacity, rotation and negative-scale cases.
- More than four mixed raster/text entries to exercise batch boundaries.
- Successive glyph sets that force atlas repacking, plus a deliberate capacity failure.

Each named case can be held on screen for inspection. The harness logs font checksums, layout revision, atlas generation, glyph misses, upload bytes, draw counts, lock-wait time and live native/GPU resource counts.

Add debug Vulkan validation setup and a debug messenger. The harness must report that validation is actually enabled; an unavailable validation layer is a failed prerequisite, not a “zero errors” result.

**E1 acceptance:**

- On the 4GB device, inspect all nine scale/quality combinations: 50/100/300% × Full/Half/Quarter.
- Check shaping against pinned-font reference results, missing-glyph visibility, correct opacity/order and no glyph bleeding.
- After warm-up, scale-only animation produces no shaping or SDF-generation misses.
- Run five warm-up cycles, then **30 surface recreation cycles and 30 full renderer create/render/destroy cycles**, including background/foreground transitions.
- Require zero validation errors and zero remaining per-renderer text resources after destruction.
- Make GPU-resource accounting an independent pass/fail gate: images/views, samplers, buffers, device-memory allocations/bytes, descriptors/pools, pipelines/layouts and synchronization resources return to the live-session baseline after surface recreation and to zero for the destroyed session after full teardown. Verify counts by category, not only net totals. Driver-internal allocations are not visible in native-heap measurements; record available driver memory telemetry separately without treating missing telemetry as proof of no leaks.
- Record native heap after each cycle. The median of the final ten teardown samples must remain within **1 MiB** of the first ten post-warm-up samples, without sustained upward growth. Retained global caches are measured separately.
- Verify every packaged native library’s ELF alignment and APK alignment. A separate 16 KB environment validates runtime compatibility if the phone uses 4 KB pages.
- Recheck existing video/image/solid rendering and audio after the NDK upgrade.

Builds, installation and device execution remain user-run unless explicitly authorized.

### 3. Shared geometry contract and E2 outline

**One native layout result** supplies shaping, glyph placement and design-unit metrics. Cache by text, resolved font identity/catalog revision and shaping configuration, excluding size and alignment. Derive bounds/positions from evaluated size and static alignment without reshaping. UI consumers receive immutable metrics through an injected provider; JVM geometry tests use a fake. Content/font changes or uncached held-string switches prepare on a worker before frame submission. See the animation revision for bounded-cache and seek behavior.

The result contains logical bounds, ink bounds, line advances, ascent, descent, line gap and positioned glyphs:

- Nonempty multiline logical height: `lineCount × (ascent + descent) + (lineCount − 1) × max(lineGap, 0)`.
- Maximum line advance defines the alignment box. Shorter lines align left, centre or right within it.
- Retain overhanging ink separately so rendering/hit testing does not clip it.
- Empty string returns **zero width and zero height**. Draw nothing; retain timeline selection and a selectable anchor indicator. Disable Fit/Stretch and guard empty geometry explicitly.
- Unknown fonts return the default face’s real metrics and a fallback indicator.
- Text at 100% uses composition-pixel font size, with no contain-fit multiplier. Canvas resizing preserves reference-space position/proportions under the existing reference-canvas rule.

**Anchor correction:** retain stored anchor fractions. On purely static text/font/size/alignment edits, compensate static Position to keep the alignment origin at the first baseline fixed. Compute old/new origins through actual geometry matrices, including rotation and signed scale. Apply text and compensation atomically. Do not compensate during playback/key editing or when layout-affecting text tracks are enabled; see the animation revision.

Match the existing animation restriction: do not rewrite animated Position keys. With static Position and animated scale/rotation, compensation is exact at the editing playhead; it is not a promise of invariant placement throughout that animation. Do not claim equivalence to AE’s pixel-anchor model.

**E2 goals and dependencies:**

- Add a text payload with Universal Properties for Source Text (hold), font size and packed ARGB fill, plus static font ID/alignment and an empty versioned `text_animators` vector; bump **v8 → v9**. The type slot is already reserved; the payload is new. Text has no asset. Typed colour/string extensions use the same evaluator, temporal anchors, history and codec framework as transform.
- Preserve v1–8 loading and requested unknown font IDs. Default legacy placeholder text to empty rather than introducing unexpected visible content.
- Wire the prepared-layout path into preview, headless export and thumbnail rendering; retain the single-video fast-path exclusion.
- Add a text-specific natural-size geometry API without changing media fit behaviour. Fit Width matches width only; Fit Height matches height only; Stretch matches both independently.
- Use an initial upper size of 500px and 4,096 UTF-16-unit source limit. The lower size is provisional: E1c/E1e measure 8/12/14/16px before the E2 plan fixes it. Reject oversized persisted strings with a clear load error; clamp finite out-of-range sizes to the accepted size range, reject nonfinite values. Do not silently truncate stored text.
- Resolve atlas overflow, concurrent font preparation and thumbnail-thread initialization before production enablement.

Detailed E2 implementation follows E1 acceptance.

### 3.1. Universal text-property animation

These revised contracts are planned, not implemented. They supersede the earlier static-only text-property scope; E1d gates remain unchanged.

#### Deviations from the approved plan

1. E2's formerly static size, fill and source fields become Universal Properties immediately: static value plus optional typed animation track. Font size, fill colour and Source Text ship keyframeable in E2/E3, with inspector diamonds in E3. This expands the earlier transform-only animation scope.
2. The shared shaping cache key excludes font size and alignment. Cache design-unit shaped runs by source, resolved font/catalog revision and shaping configuration. Derive geometry from those runs and evaluated size/alignment; do not interpolate between differently shaped strings or populate a size-keyed shaping cache every frame.
3. Add colour and hold-string value types to the existing property/evaluator/persistence framework. Current code has only `AnimatableProperty1D`, `AnimatableProperty2D`, `EASY_EASE`/`LINEAR` and four transform property IDs. Extend these capabilities additively; do not make a separate text timeline, native animation evaluator or undo system.
4. Reserve a versioned, empty Text Animators vector in v9. Its schema envelopes are additive future-extension points, not a claim that selectors/animators execute.
5. Amend the future E1d mesh contract now: optional glyph-local transform, opacity and colour inputs, identity by default. E1d still does not implement animators.
6. Static-edit anchor compensation must not run during property evaluation or keyframe editing. Otherwise animated size/source would mutate Position or create implicit cross-property animation. Static and animated geometry changes have distinct, explicit rules below.

#### Universal Property contract (E2)

Each property has one authoritative static value and an optional immutable, ordered track. Follow the existing `isAnimated`/static/keyframes representation and existing Kotlin worker-side evaluation, time search, easing LUT, history and serialization approach. No duplicate static text fields with competing values.

| Property | This pass | Evaluation |
| --- | --- | --- |
| Font size | Scalar Universal Property; existing 1D track | Existing Linear/Easy Ease interpolation, composition pixels |
| Fill colour | Typed colour Universal Property | One colour key/diamond, common eased progress across RGBA |
| Source Text | Typed string Universal Property | Hold only; no Bezier, easing slider or curve handles |
| Position/scale/rotation/opacity | Existing transform properties | Existing behavior preserved |
| Font family | Static font ID for this pass | Later discrete hold property; font switching needs catalog/layout prewarming and a typed identifier editor |
| Alignment | Static for this pass, as requested | Later discrete hold property; never encode enum values as interpolated floats |
| Stroke, tracking, leading | Not implemented in this pass | When introduced, use typed Universal Properties from their first implementation |

Every eventual text property must be animatable using an appropriate type. Categorical properties (font ID, alignment, style/mode toggles) eventually use hold tracks. Continuous properties use scalar/vector/colour tracks. Unsupported features are not exposed as permanently static substitutes. Synthetic bold/italic, complex-script/RTL layout, colour emoji and arbitrary font import remain outside the current text subsystem's scope.

All text tracks use signed times relative to `resolvedKeyframeAnchorUs`, frame-unique insertion, and the existing endpoint behavior. Source Text evaluates the first key before its time, the latest key at/before the requested time, and the last key afterwards; disabled/empty tracks return the static string. At an exact key boundary the new string wins, including an empty string. String keys structurally admit only hold interpolation; append a common HOLD capability if needed without renumbering persisted Linear/Easy Ease values. No string-to-number conversions.

Colour endpoints persist as packed ARGB. Proposed interpolation policy: decode sRGB RGB to linear light, premultiply by alpha, interpolate channels using the existing eased progress, then unpremultiply and encode for renderer submission; alpha interpolates linearly. Return original packed endpoint values exactly, and guard zero alpha. Layer opacity multiplies the evaluated fill alpha once. This makes the new colour policy explicit rather than interpolating the packed integer; tests must lock it down before other colour properties reuse it.

Append text property IDs and typed text/colour set-key actions. Existing scalar/vector set-action semantics, the begin/preview/commit/cancel protocol and `PreviewController` API remain unchanged. Add colour/string branches to the common property registry, selection, timeline key manipulation, navigation and codec. Reuse common time lookup and easing logic instead of copying it into a text-only evaluator.

#### Animated size, Source Text and the SDF policy

E1b already shapes using font units-per-em. At time `t`, evaluate size with the existing Kotlin evaluator and derive every glyph position, bearing, advance and logical/ink bound using `evaluatedSize / unitsPerEm`. Apply static alignment from the scaled line advances. Geometry and the renderer consume the same immutable layout revision and evaluated size. This is **scaling one shaped layout**, not interpolating two layouts. No aspect-fit contain multiplier is introduced.

Size animation can rebuild/update glyph mesh positions and bounds each frame; it does not call HarfBuzz or rasterize glyphs. Keep runtime buffers reusable. Fill changes update submission colour only. Neither size nor colour belongs in the shaping/SDF cache key. Shared metrics are cached in design units; scalar conversion is permitted per frame and does not acquire the font mutex. Geometry JVM tests use the injected fake metrics provider.

The SDF remains 48ppem, 8px spread, R8, with overlap handling enabled and CPU bitmap retention. Font-size changes and layer scale both affect its final sampling footprint; future E1d coverage reconstruction must use screen-space derivatives. No size buckets or per-frame SDF regeneration. The small-size/extreme-magnification quality limits from E1c remain; do not promise unrestricted sharpness. The minimum allowed size remains subject to E1e quality acceptance; 500px maximum and existing input bounds are provisional planned limits, validated for both static values and keys.

Source Text keys select discrete layouts. Prepare unique strings on a worker when keys/content/font settings change, prefetch upcoming hold transitions, and retain immutable leases for the active/prepared working set. Repeated frames within a hold interval perform no shaping. Seeking to an uncached string may require preparation **before submitting that frame**; it must not put HarfBuzz in the render pass or on the UI thread. Preview retains its last complete frame while preparing the requested revision; export/thumbnails wait for the exact revision. Very large tracks use bounded caches and may re-shape after eviction. Thus the precise rule is *no shaping merely because time/size/colour advanced*, not an impossible promise that arbitrary uncached content switches never require shaping.

#### Bounds, anchors and other operations

Retain anchor fractions. A purely static source/font/size/alignment edit may compensate static Position to preserve the first-baseline alignment origin, as previously planned. Do so only when layout-affecting text properties have no enabled tracks and Position is static; animated scale/rotation retain the previous edit-time-only guarantee.

For keyed size/source edits, playback, or static font/alignment edits on animated text, **do not compensate Position or rewrite any track**. The stored anchor fraction is evaluated against the current bounds; edges can move as size or source changes. If a fixed left/centre/right origin is needed, the user chooses a matching anchor or explicitly animates Position. This is a deliberate deviation from static-edit compensation and must be covered by tests, not presented as AE pixel-anchor equivalence.

Fit Width/Height/Stretch uses evaluated bounds at the playhead and the existing scale auto-key behavior; it does not rewrite font size. Empty evaluated text has zero bounds, draws nothing, retains selection and disables Fit/Stretch for that instant. Move/trim preserve temporal anchors; split rebases every text track under the existing keyframe rules, retaining neighboring keys needed for continuous evaluation. Duplicate/copy/paste, undo/autosave and thumbnails include the entire property payload. Timeline labels display the evaluated held string at the playhead. Text-property focus participates in the existing marker/keyframe navigation priority.

#### Additive v9 persistence outline

Keep all existing Layer slots/type ordinals unchanged. Append one text payload containing size, fill and Source Text Universal Properties, static font ID/alignment, and `text_animators`. Scalar size reuses the existing 1D schema; colour/string add typed property/key tables with the same static/track structure. String keys have time and value, with hold-only semantics. No conversion of colour or source tracks into unrelated transform slots.

Reserve typed versioned envelopes (conceptual schema, not code to compile yet):

```flatbuffers
table TextRangeSelector { version: uint = 1; }
table TextPropertyOverride { version: uint = 1; property_id: uint; }
table TextAnimator {
  version: uint = 1;
  range_selector: TextRangeSelector;
  property_overrides: [TextPropertyOverride];
}
// In the new text payload:
// text_animators: [TextAnimator];
```

The vector is empty in every v9 document written in this pass (an absent vector also decodes as empty). Selector units/ranges and override values/tracks will be appended to these envelopes when animator semantics are designed; do not prematurely bind property IDs to current Kotlin enum ordinals. No runtime animator behavior, editor controls or no-op entries are created now. A future document containing nonempty/unsupported animator data must produce an explicit unsupported-feature load result rather than be silently loaded and re-saved with lost fields. Future writers bump the project/feature version appropriately.

v1–8 load unchanged; missing text payload yields empty, static text defaults. Preserve unknown requested font IDs while rendering with fallback. Validate key ordering/frame uniqueness, signed times, finite size values, allowed interpolation, and source length on **every** static/key value. Add a bounded aggregate source-text payload budget before production enablement; per-string limits alone do not protect a many-key document. Do not silently truncate strings or drop keys. v9 is still planned and has not been written by the current app.

#### E3 editor and typing transaction outline

Edit Text exposes diamonds for **Source Text, Font Size and Fill Colour** alongside their controls. Use the same enable/disable/add/remove/auto-key semantics and frame grid as transform. Turning off a track freezes its evaluated value at the playhead using the existing policy. Scalar/colour keys expose supported easing; Source Text shows Hold and never offers Bezier controls. Fill uses the existing Global Colour Picker; picker gestures preview one colour value/key and commit through existing history.

On text focus, capture the selected layer/property, original snapshot and snapped, anchor-relative edit time. With Source Text animation disabled, drafts change the static string. With animation enabled, drafts update the existing key at that captured frame or insert **one** hold key there, preserving all others. An explicit first-key diamond is a separate intentional history action; typing does not silently enable animation.

All keystrokes preview against the original snapshot. One typing session commits one final source/key mutation on Done, focus loss or dismiss. Cancel restores the exact original source/track and any permitted static Position compensation, removing a draft-created key. An unchanged final draft produces no history entry. IME composition updates are drafts too, never individual undo entries.

Before seek/playback, layer/property change or a key-toggle operation, finish the current typing transaction at its captured time; never retarget an in-progress session as the playhead moves. Commit awaits the latest metrics revision asynchronously. Reject stale worker completions by session/content revision and publish string, layout and permitted compensation atomically. These rules fit the existing gesture transaction contract; they do not add a second undo mechanism.

Retain the approved compact non-scrolling controls, internally scrolling text field, global colour picker and workspace-level IME handling. Main-thread callbacks never acquire the shared font mutex. Size/colour drags and typing all retain their own existing gesture boundaries.

#### Required acceptance additions and risks

- Static/animated size, colour and hold strings round-trip through v9 and undo; v1–8 fixtures load unchanged.
- Exact key boundaries, signed rebasing, trim/move/split, duplicate/copy/paste, empty-string keys and first/last endpoint holds behave consistently.
- Instrument shape/SDF miss counts: warm size/colour sweeps produce zero misses; repeated hold intervals do not shape; arbitrary seek waits for the correct layout revision.
- Size/fill/source preview, export and thumbnail results agree, including transparent colour keys and simultaneous transform animation.
- Inspector diamonds, common navigation and timeline keys include all three new properties; Source Text never exposes interpolation curves.
- Typing between keys inserts one hold key/undo entry; typing on a key edits it; cancel removes the draft; IME/seek/dismiss cannot commit at a different time or revive stale layouts.
- Static compensation and animated anchor behavior are tested separately, including empty bounds and animated Position.
- Empty animator vectors round-trip; unsupported nonempty vectors cannot be silently stripped. Optional glyph modifiers default to identity with unchanged draw ordering/batching.

#### E1d interface amendment only

The future text mesh builder accepts an optional span/provider of glyph-local affine transforms, opacity multipliers and RGBA multipliers; absent input means identity transform, opacity 1 and colour (1,1,1,1). Compose the modifier in glyph-local space before the layer transform and multiply evaluated layer fill/opacity once. Retain per-glyph colour/opacity attributes in the draw representation even when uniform defaults are used, so later animators do not require a different pipeline layout. The callback/provider consumes prepared values; it performs no shaping, allocation or application-state mutation inside the render pass.

Preserve glyph ID, source cluster mapping and line metadata. A shaped ligature can cover multiple characters: this hook is a glyph mechanism, not a promise of one quad per Unicode character. Future range-selector semantics must resolve grapheme/cluster and ligature behavior explicitly. Modifier application and animator execution remain unimplemented now; E1d will only establish the optional identity-compatible mesh contract.

### 4. E3 outline and capability audit

Statuses below describe **today’s source**, followed by the intended E3 result. “Functional” for generic model operations does not imply text currently renders.

| Path | Current Text status | E2/E3 result |
| --- | --- | --- |
| Inspector Transform, Timing, Blending & Opacity | Functional generic controls | Functional with text metrics |
| Contextual Edit | Inert placeholder, labelled Edit Footage | Functional **Edit Text** |
| Mattes/Masks, Layer Styles, Motion Blur, Effects | Inert placeholders | Remain inert |
| Add Content Text pill | Inert placeholder | Functional |
| New-layer placement/duration | Omitted | `"Text"`, sans, 72px, white, centre alignment/anchor, composition centre; snapped playhead start, five seconds, topmost order |
| Automatic duration | Functional generic model | New text extends resolved end; explicit composition duration remains unchanged |
| Trim, Move, Split, Timing | Functional generic model | Preserve complete text payload; split preserves existing keyframe/marker rules |
| Extend | Functional assetless timing | No source-duration limit; start ≥ 0. Quick Extend respects explicit composition end; automatic duration can grow. Preserve existing drag/Timing semantics |
| Duplicate/Copy/Paste | Functional generic copy | Copy text/style without asset allocation |
| Flip H/V | Functional generic actions | Functional with text geometry |
| Fit Width/Height/Stretch | Omitted; reducer rejects Text | Functional for nonzero bounds; disabled when empty |
| Media Info | Omitted | Remain omitted |
| Auto Orient/Autotrace | Inert placeholders | Remain inert |
| Missing-media badges/relink/access checks | Omitted for assetless layers | Remain omitted; font fallback is separate |
| Export eligibility | Explicitly rejected | Functional after pipeline integration |
| Single-video export fast path | Correctly excludes Text | Preserve exclusion |
| Native composition rendering | Omitted; asset-required fallback | Functional text draw entry |
| Project thumbnails | Blocked by support validation | Functional on background worker with bundled fonts |
| Timeline clip colour | Functional monochrome aliases | Retain existing tokens |
| Timeline string label | Inert generic `Text ID` label | Show single-line preview of source; `"Text"` when empty |
| Preview bounds/handles/hit testing | Omitted text metrics | Functional through shared bounds |
| Anchor controls | Omitted text source geometry | Functional, including empty-state anchor |
| Audio/mute/audio inspector | Omitted | Remain omitted |
| Keyframes/markers/navigation | Functional type-independent model | Preserve all four transform channels and marker rules |
| Marker default colour | Functional non-solid branch | Retain orange `FFFF9800` |
| Visibility, ordering, deletion | Functional generic model | Preserve |
| Undo/autosave/project load | Generic snapshots functional; payload absent | Include immutable text fields |
| FlatBuffers codec | Type accepted; payload absent | v9 payload and validation |
| Browser media detail/filmstrip/permissions | Media-only, omitted | Remain omitted |
| Legacy `TimelineSection`/`InspectorPanel` | Generic Text labels only | Keep labels/metrics consistent; do not introduce a second text editor |

**Typography editor outline:** fixed Edit Text controls for source, bundled font, size, alignment and a fill swatch opening the existing Global Colour Picker. Source Text, size and fill have inspector diamonds; Source Text permits Hold only. Typing captures one frame and edits/inserts one held-string key per session when animation is enabled. Only the text field scrolls internally; the picker is a separate existing-style editing surface, not permanently squeezed into the sheet.

**Main-thread responsiveness:** shaping/measurement and any font-mutex acquisition run off the main thread. Conflate pending typing revisions on a dedicated metrics worker; retain the last complete preview while the next layout is computed. Publish the matching text, metrics and anchor-compensated Position atomically, rejecting stale revisions. UI callbacks never synchronously wait for the font mutex, including when export is shaping uncached text. Done/focus-loss commit must await the latest revision asynchronously within the active edit session.

**IME:** handle keyboard insets once at the editor workspace. While typing, hide the timeline/action strip, keep a compact non-scrolling text field/Done panel above the IME, and allocate the remaining upper area to a letterboxed preview. Restore the prior layout after keyboard dismissal. Do not stack `imePadding()` onto the existing fixed sheet and assume it fits. *(Superseded by TEXT UI 5: the keyboard overlays the editor; no layout change while typing; the inline text field lifts above the IME in an anchored popover.)*

**Undo:** begin one session on text focus, preview complete drafts relative to its original snapshot, commit on Done/focus loss/dismiss, and explicitly cancel to restore the original. Reject stale asynchronous layout results by session/revision. Additive text actions carry any static Position compensation; preserve the locked gesture protocol, existing action semantics and `PreviewController` API.

Detailed E3 planning follows the verified E2 pipeline.

### 5. Documentation, gates and remaining risks

- Create `TEXT_LAYER.md`, linked from the documentation index and roadmap, recording E1/E2/E3 separately.
- E1 adds native pipeline, atlas, dependency/license, ownership, alignment and measured device-result sections. Keep the Text creation icon marked nonfunctional.
- E2 records v9 compatibility, shared bounds, export/thumbnail integration and cache overflow handling.
- E3 updates `ELEMENT_INSPECTOR.md`, capability statuses and the Text icon only after editor integration. Mark Step E complete only after acceptance.
- Record bundled-font/SDF choices and deferred Text Animators, Range Selectors, text-on-path, auto-wrap, font import, stroke and tracking/leading controls in the parity vision.
- E1 excludes complex-script support, bidi/RTL layout, emoji and synthetic bold/italic. HarfBuzz’s presence alone does not establish those features.

Primary risks are SDF quality at small sizes/Quarter resolution, shared font-lock contention, atlas working-set overflow, NDK regression, and resource retirement on cancellation/device loss. E1 explicitly measures these; it does not claim general text support or leak freedom from a single successful screenshot.

---

<a id="rec-text-layer"></a>

## Text layer checkpoint

*Formerly `docs/checkpoints/TEXT_LAYER.md`.*

**TEXT F3.1 (2026-11-22):** The Animate tab was reworked after the first A16 test, in source and with A16 instrumented tests:
- one collapsible bar per animator (Property / Selector / Grouping rows), a scrolling body, grip reorder, swipe delete;
- slider or pad/dial/ruler transform controls (app preference);
- Range/Wiggly tiles with Advanced groups. Range Min/Max amount and Wiggly Amount are hidden in the UI only; the engine still applies them.
- The Character offset/value crash (stale UI metrics without a character catalogue) is fixed at the metrics and renderer-preparation boundaries, with a pre-check against the 4,096-codepoint / 16,384-glyph caps.
- Skew is capped at ±85°, Blur at 100px.
- Animator Blur is per-glyph SDF edge softness, not a Gaussian layer blur, and is independent of the future Effects-tab blur.
- Prev/Next follows one global rule.

[Checkpoint](#rec-text-f3-1).

**TEXT F3 Animate tab (2026-11-22):** The Animate tab is implemented in source; reworked in F3.1–F3.3, whose phone acceptance is done. It edits the existing animator model through the existing `AnimatorEdit` actions (no new action types, no schema/preparation/evaluation change). Selectors and properties are as validated by the backend: 16 animators × 8 selectors, all 25 property families, Range and Wiggly. Keys use the same tracks, rail, badges and timeline markers. [Checkpoint, inventory, deviations and A16 list](#rec-text-f3).

**Edit Text UI rework (2026-11-18):** Implemented in source, device-unverified; UI only. No text model, schema, preparation, native, PreviewController, gesture-protocol or batch/draw change. Aggregate popups are deleted; all common Character/Paragraph controls are on fixed tab faces; overlays stay inside the sheet; the header ⋮ has four items; the status line and Hold label are replaced by rail diamond states, a selection chip and the feedback strip; Back returns to the layer inspector grid; size / vertical scale / horizontal scale have distinct glyphs. Stroke clamp feedback still preserves the requested value and reports the effective range only while clamped. [Inventory](#11-rework-2026-11-18-final-surface-and-inventory) and [gate, deviations and user-run list](#rework-commit-2-menu-back-status-and-icons).

### FIX / EXPLODE / T5 continuation — 2026-10-09

**Outcome:** T5 was then stopped. Mandatory Arabic lam-alef is lost across separate style items, so cross-style shaping needs a style-ownership decision. Stored RTL is still rejected for rendering. See [the T5 stop evidence](#rec-text-fix-explode-t5).

EXPLODE implements the user's revised representation: Lines/Paragraphs/Style runs retain the original box; Words/Characters become point text. One compact top-level dialog, logical naming/order, shared prepared visual placement, Group/Each pivots, held layout sampling and one undoable creation action are implemented in source, device-unverified. No frozen layout data is persisted. Non-final justified lines map to justify-all; final lines retain their alignment. Geometry that cannot be reproduced with ordinary pieces is explicitly refused, including some partial style runs and animator shape changes. Characters preserve Unicode Joining_Type cursive words as indivisible spans; contextual forms for other character splits may change as accepted. Anchors extend additively to finite ±1,000,000 normalized units, without silent clamping. See the [current compile/JVM/host evidence, limits and exact manual list](#rec-text-fix-explode-t5). T5 rendering is still gated; its final indivisible-word decision supersedes the historical stop below.

The user has manually tested T1–T4 on a phone; the case/script and responsiveness findings in this continuation are user-reported results. FIX adds lowercase and corrected per-glyph Small Caps, uniform synthetic script positioning, shared transient preview/source selection, composition-fill justification and preparation/cache/idle-warming/timing changes. These changes are implemented in source and device-unverified. Native Debug/Release (both ABIs), Kotlin Debug/Release, both instrumentation source sets and 237 JVM tests passed; host selftest, six shaping references and 1,445 checksums passed. No new Character/Paragraph sub-screen, PreviewController API, gesture transaction protocol or batch/draw contract is introduced. EXPLODE is committed in source; T5 is not implemented. The user's Unicode Joining_Type indivisible-word decision supersedes the old T5 emergency-break decision below. See [the current implementation/evidence, deviations, limits and exact user-run list](#rec-text-fix-explode-t5). Historical entries retain their original evidence; F1 performance/parity/lifecycle remain unrun.

### T4c character substitution continuation — 2026-10-05

Character Offset/Value integration is implemented in source, with its commit gated on compile/JVM/host checks. Worker preparation builds standalone cmap/SDF catalogues for every reachable source alternative and requested font identity, including static variation coordinates and the existing fallback chain. Imported fonts stay in the isolated worker. Character replacement preserves logical selector membership, uses its own nominal advance, and retains original combining characters. It performs no GSUB/GPOS shaping; active replacement therefore does not form ligatures or reapply OpenType caps/script substitutions. The original prepared run scale, baseline and faux styling still apply. This feature interaction is an explicit assumption/deviation.

Offset evaluates `round(currentCodepoint + coverage × requestedOffset)` in animator/property order. Value is an absolute rounded codepoint when coverage is nonzero; zero coverage retains the current/original glyph. Rounding is `floor(x + 0.5)`. Invalid, control, format or surrogate results restore the original source character. Replacement, like animator tracking, changes advances/alignment/pivots after base wrapping and does not reflow or rejustify the line. All members of an original ligature cluster receive its projected coverage, even when nominal replacement emits separate glyphs.

Preparation conservatively bounds signed selector influence to [-1,1] and allows at most 4,096 distinct reachable codepoints per layer, 16,384 font/codepoint catalogue glyphs, 32 MiB character packets per layer and 64 MiB retained text/character packets per renderer project. Exceeding a bound fails explicitly. The unchanged 64-layout cap covers held documents × ligature modes; catalogues are resources, not wrapping variants. Native preparation pins reusable codepoint buffers alongside each modifier-buffer identity, so layers sharing a layout retain independent replacements. Existing 96-byte modifier records use a reserved flag; draw arguments, indexed-mesh batch contract, PreviewController and gesture protocol are unchanged. Per-frame lookup is bounded binary search over the prepared codepoint set, in addition to the existing selector/property work; no shaping, rasterization, upload, permutation generation or allocation is permitted in prepared evaluation.

The full gate in `t4c-final.log` passed native Debug/Release on arm64-v8a and x86_64, Kotlin Debug/Release, both instrumentation-source compilations and all 228 JVM tests (zero failures/errors/skips). The warmed character evaluation fixture asserts zero allocated bytes over 600 frames; host Release/selftest, all six shaping references and 1,445 checksum checks passed. The final baseline correction and final fixtures passed the same complete gate in `t4c-commit-gate.log`. New source-only fixtures cover all 25 property families at representative and maximum counts. No production font assets were added (asset delta zero; code/APK delta unmeasured, no packaging). F1 perf/parity/lifecycle remain deferred and unrun. No device results or A16 timing are claimed.

#### T5 stopped under the approved droppable-stage rule

T5 cannot be completed by retaining the current fixed shaped-cluster advances through every possible emergency wrap. The host `rtlboundaryproof` uses the same trimmed HarfBuzz build, explicit Arabic script/RTL direction and the system Arial outline font. Joined Arabic U+0628 × 3 produced glyph/advance pairs `911:1461, 913:500, 913:500`; isolated U+0628 produced `911:1461`. Splitting that source word across lines therefore requires different joining forms and different advances. Mere visual bidi reordering of the paragraph's prepared glyphs would be degraded Arabic. Disallowing emergency breaks would change approved T3 behavior.

Correct support needs a design extension for boundary-dependent prepared shaping/advances and a wrapping solver that selects those variants without per-frame shaping or allocation, while preserving logical clusters/selector maps. Enumerating animated wrapping layouts or shaping at evaluation is forbidden. The existing HarfBuzz trims do not disable Arabic/Hebrew OT shapers, but that alone does not resolve this layout dependency. This is an architectural finding, not a device/performance gate. Stop after T4 as authorized; do not ship partial Hebrew-only/RTL support or add Arabic/Hebrew production fonts prematurely. Stored RTL attributes remain preserved and explicitly rejected for rendering with the existing actionable error.

Reproduce the diagnostic with `text-proof FONT_DIRECTORY rtlboundaryproof C:/Windows/Fonts/arial.ttf` (or another covering outline font); it is a host dependency, not a copied/bundled font. Future T5 acceptance must include joined Arabic words under animated emergency wrapping, marks, mixed Hebrew/Latin/numerals, logical selectors, physical alignment, mirroring and equal-resolution parity/performance. These are requirements for the future stage, not implemented fixtures or passing results.

Exact T4c user-run list:

1. Run `TextF2AcceptanceTest.characterRepresentativeColdAndThermal` and `characterMaximumColdAndThermal` with unique `textRunLabel` values. Archive both cold/thermal JSON reports from each; check p50/p95/p99/max, native-new/ART allocation deltas and shaping/rasterization/upload/permutation/font-worker canaries. Fixtures use 500+ and 4,096 glyphs, 8×4 and 16×8 animator/selectors, all 25 properties, rich runs, wrapping, mixed modes, wiggly dimensions and held seeds. A16 60 fps is unmeasured.
2. Run `TextF2AcceptanceTest.characterRandomAndReversePreEncodePixelsMatch` for equal-resolution preview/export/thumbnail parity under random access/reverse scrubbing and held source/width alternatives.
3. Inspect debug-authored Character Offset/Value documents: signed/fractional offsets, absolute values, original combining marks, ligature-cluster selection, fallback/missing glyphs, imported static variations, run/faux styling, invalid/control/surrogate restoration and explicit cap failures. Check unchanged membership, nominal advances, post-wrap spacing, bounds/anchors/Fit/Stretch/overlays/hit tests and independence of two layers sharing a prepared layout.
4. Save/reopen, copy/paste/duplicate, reorder, split/trim, undo/redo, freeze/reset and navigate keys for the new properties and keyed wiggly fields. No F3 animator controls are added.
5. Reproduce native Debug/Release both ABIs, Kotlin Debug/Release, both instrumentation-source compilations and the full JVM suite. Run host selftest, six shaping references and checksum verification. Device execution remains user-owned.

### T4b paint/spacing/dimension integration — 2026-10-05

T4a is committed as `adc922b`. T4b integrates property IDs 14–23 and unlocked wiggly dimensions through the existing 96-byte per-cluster modifier record and per-glyph RGBA/affine attributes. No shader, mesh ABI, PreviewController, gesture or batch/draw changes. Character offset/value (IDs 24/25) remain explicitly guarded pending T4c prepared cmap integration; T4 is not complete yet.

Unlocked wiggly uses dimension 0 for scalar/X influence, 1 for Y and 2 for blue/brightness influence. Locked selectors share dimension zero; range selectors are dimension-invariant. Position/scale/anchor use separate X/Y weights; rotation/skew use scalar influence. RGB target blends clamp each component weight in linear-light premultiplied space; shared alpha uses dimension zero. Colour never extrapolates. Fill/stroke opacity multiply their separate existing alpha with weighted requested opacity, independently of glyph/layer opacity. H/S/B adjustments use straight-sRGB HSV: additive hue degrees modulo 360, saturation/brightness in percentage points, clamped to their legal range, preserving alpha. This HSV space and bounded value-noise behavior are explicit AE assumptions/deviations.

Line spacing is an additive composition-pixel adjustment once after each visual line, with source-character-weighted mean selector influence (so ligatures do not bias the mean). It shifts subsequent baselines without reflowing, then updates logical/ink bounds and post-spacing pivots. Empty lines have no selected glyph influence. Grouping alignment offsets the per-animator group pivot from its centre by XY percentages of the post-spacing group size; it changes rotation/scale/skew pivots and is inert under identity transforms. Per-animator grouping remains the documented AE deviation.

Verification: `t4b-modifiers-initial.log` passed Kotlin Debug/Release, both instrumentation source sets and all 222 JVM tests, zero failures/errors/skips. The warmed 600-frame zero-allocation fixture now includes all 23 integrated property families and unlocked wiggly dimensions. Four added fixtures cover independent paint alpha/HSV, grouping pivots, unlocked position/random access and once-per-line spacing/bounds. Native/host sources are unchanged from T3b's passing Debug/Release both-ABI and host/reference checks. New instrumented performance/parity fixtures are compiled, source-only, device-unverified. No APK packaging or device execution; asset increment zero, code/APK delta unmeasured. F1 perf/parity/lifecycle remain deferred and unrun.

Exact T4b user-run list:

1. Run `TextF2AcceptanceTest.advancedModifierRepresentativeColdAndThermal` and `advancedModifierMaximumColdAndThermal` with unique `textRunLabel` values; archive both JSON reports from each. Fixtures use 500+ and 4,096 glyphs, 8×4 and 16×8 animator/selectors, mixed range/wiggly/modes, seed keys, unlocked dimensions, rich runs and wrapping. Check native-new/ART allocation and preparation canaries, cold/three-minute thermal p50/p95/p99/max, and zero permutation-build/font-worker deltas. A16 60 fps remains unmeasured.
2. Run `TextF2AcceptanceTest.advancedModifierRandomAndReversePreEncodePixelsMatch`: compare equal-resolution preview/export/thumbnail pixels under random access/reverse scrubbing, seed changes, held source/width alternatives and all integrated modifier families.
3. Use debug-authored animator documents to inspect fill/stroke opacity independently (translucent paints, both per-glyph paint orders, thick stroke/negative tracking), H/S/B, signed selector modes, locked/unlocked vectors, grouping pivots and line spacing in point/box text. Check anchors/Fit/overlays/hit tests and persistence/copy/split/undo/key navigation; no F3 controls are added.
4. Reproduce compile/instrumentation/JVM checks (222 tests), plus unchanged host selftest/shaping references. Character substitution acceptance follows T4c; supported RTL follows T5.

### T4a animator model/codec/selector foundation — 2026-10-05

T3b is committed as `0da4590`. T4a adds v12 selector table version 3 (v11 version 2 still loads), keyed selector kind/frequency/correlation/phases/dimension lock, exact unsigned 64-bit seed keys, and property IDs 14–25: fill/stroke opacity, fill H/S/B, stroke H/S/B, line spacing, grouping alignment, character offset/value. Typed universal property payloads, validation, reorder/copy/split/undo/key navigation remain shared. Seed keys are hold-evaluated with strictly increasing times, maximum 4,096 keys; all bits survive persistence. New rendering families and unlocked wiggly dimensions fail explicitly until T4b integration, preserving the requested document. No animator UI is added.

Wiggly is a pure deterministic SplitMix64 lattice value-noise function. Let `t = timeSeconds × frequency + temporalPhase/360`, `s = member + spatialPhase/360`; interpolate the four hashed lattice corners using `u²(3−2u)` on each axis. Correlation `rho = correlation/100` blends independent member noise with common noise at `spatialPhase/360`; 0% is independent and 100% identical for all members. Locked dimensions hash dimension zero, otherwise the supplied dimension. Influence is `amount/100 × (lo + (hi−lo) × (noise+1)/2)`, with sorted min/max divided by 100 and final signed clamp. Separator members receive zero. Modes and cluster-mean projection are unchanged. Noise coordinates wrap every 1,000,000,000 lattice units to keep finite huge phase values deterministic; this bounded, non-Gaussian noise is an explicit AE deviation.

Range selectors also support held seed keys. Preparation prepares all four membership permutations for every reachable seed; at most 64 distinct seeds per selector. The shared 32 MiB permutation payload budget now includes membership permutations, not just line counts, for point and box text alike. No seed/permutation generation occurs in evaluation. These are explicit preparation limits, not dropped keys. Frequency is bounded to 0–1,000 wiggles/second; correlation to 0–100%. T4a adds no assets; APK/code size remains unmeasured. Kotlin Debug/Release and both instrumentation source sets passed in `t4a-final.log`; the final known-vector/shared-budget JVM gate passed all 218 tests in `t4a-final-jvm.log`. Final Debug/Release and instrumentation compilation after the shared-budget correction passed in t4a-final-sources.log. Native/host sources are unchanged from passing T3b checks. T4b rendering/substitution and T5 RTL remain outstanding. F1 performance/parity/lifecycle remain unrun.

T4a user-run checks: reproduce the full JVM and Kotlin/instrumentation source compile gate; `WigglySelectorTest` covers random/reverse evaluation, continuity, correlation/dimension endpoints, exact seed bits/hold keys, codec/rebase/undo/navigation, malformed inputs, permanent v11 compatibility and zero allocated bytes over a warmed 600-frame window. Run the unchanged host selftest and shaping references. No new device acceptance or F3 controls are claimed at this foundation stage; T4b must add the complete paint/spacing/substitution parity/performance fixtures before T4 is complete.

### T3b paragraph/box integration — 2026-10-05

T3a is committed as `f05e402`. T3b is implemented in source, device-unverified. The latest continuation supersedes the older wrapping-variant instructions below: wrapping runs each frame as greedy arithmetic over prepared styled advances in reusable native buffers. No wrapping layouts are enumerated under animated size/tracking. The 64-layout limit covers reachable held atomic documents × two ligature modes only.

The implementation bounds each layer to 256 explicit/visual lines. Preparation bounds reachable counts by explicit paragraphs plus shaped clusters, capped at 256; evaluation fails explicitly if actual wrapping exceeds that cap. Randomized Lines selectors prepare inverse permutations for every count in that range, shared across held documents and ligature modes by seed/count. Counts 1–256 use 131,584 bytes of integer payload per seed; 128 distinct seeds use about 16.06 MiB. A 32 MiB payload budget per layer rejects excess preparation; array/map overhead is additional. Evaluation never constructs permutations.

Current source integrates paragraph positions into modifiers/bounds/group pivots, fixed Paragraph subpages and a box-width handle using existing begin/preview/commit/cancel transactions. Direction is stored but RTL remains explicitly unsupported until T5. Paragraph wrapping uses layer/run spacing before selector evaluation; animator tracking moves selected clusters after wrapping. Logical word boundaries survive soft wrapping. Device fixtures for wrapped representative/maximum performance and random/reverse pixel parity are source-only. Native Debug/Release on both ABIs passed in `t3b-resume.log`; final Kotlin Debug/Release, both instrumentation source sets and all 212 JVM tests passed in `t3b-final.log`. Host Release/selftest and all six shaping references passed. The warmed 600-frame wrapping/randomized-line JVM fixture asserts zero allocated bytes. No device runs, protected API or batch/draw changes. T4/T5 remain outstanding; F1 perf/parity/lifecycle remain deferred and unrun.

T3b assumptions/deviations: word membership remains logical across emergency/soft wraps rather than splitting a source word into multiple selector members. Base wrapping and word-space justification precede animator tracking, which does not reflow/rejustify text. Paragraph alignment remains physical. Point text aligns paragraph lines against their common maximum width, including indents. Space-before on the first paragraph is ignored; final space-after affects a following paragraph, not visible trailing height. Overflow is unclipped; oversized clusters remain indivisible. The width handle appears for selected text while its inspector is open; edits retain existing undo transactions. RTL is storage-only at this stage. Asset increment is zero; code/APK delta is unmeasured, with no packaging.

Exact T3b user-run list:

1. Exercise all fixed Paragraph subpages on selected paragraphs and on collapsed selection/all text: seven alignments, left/right/first-line indents, space before/after, box width zero and positive. Verify first-paragraph space-before suppression, hanging trailing spaces, empty paragraphs, overflow and lines without justify spaces.
2. Drag the width handle under layer rotation/scaling and preview zoom. Check commit/cancel, one undo per drag, redo, held Source Text keys, persistence, copy/paste/duplicate/split and inserted-paragraph style inheritance. Compare bounds, anchors, Fit/Stretch, overlays and hit tests while wrapping changes.
3. Animate size and layer tracking through zero while changing held box widths. Lines selectors must follow current visual lines; randomized Lines must match random access and reverse scrubbing. Exceeding 256 lines or the preparation budget must give an actionable error, without dropping text.
4. Run `TextF2AcceptanceTest.wrappedRepresentativeColdAndThermal` and `wrappedMaximumColdAndThermal`, each with a unique `textRunLabel`. Archive both JSON reports from each run. Check native/ART allocations, shaping/rasterization/upload/permutation/font-worker deltas and p50/p95/p99/max; the prepared line-permutation build delta must remain zero. No host result establishes A16 60 fps.
5. Run `TextF2AcceptanceTest.wrappedRandomAndReversePreEncodePixelsMatch` for equal-resolution preview/export/thumbnail parity, including animated spacing and held width alternatives.
6. Reproduce native Debug/Release for both ABIs, Kotlin Debug/Release, both instrumentation compilations and the full JVM suite with `tools/text/build_android.py`; run host build/selftest and the six shaping references. F1 perf/parity/lifecycle remain separate, deferred and unrun.

### T3a paragraph foundation — 2026-10-05

T2b is committed as `e4e6def`. T3a adds cluster-safe paragraph selection editing, preparation-only Android ICU/UAX14 break opportunities and a portable native greedy line builder over shaped clusters. The native builder supports all seven alignment choices, indents, hanging trailing spaces, word-space justification, explicit/empty paragraphs, first-paragraph space-before suppression and unclipped overflow. Oversized unbreakable sequences use emergency cluster breaks; an indivisible oversized cluster remains overflowing. This is a preparation foundation, not integrated box rendering: existing renderer guards remain active. No protected API, gesture or batch/draw changes.

The T3a gate passed native Debug/Release on both ABIs, Kotlin Debug/Release, both instrumentation source sets and all 208 JVM tests, zero failures/errors/skips (`tools/text/results/t3a-paragraph-foundation.log`). Host Release/selftest and six shaping references passed. Three new JVM tests cover selection endpoints, collapsed/all-text selection, empty paragraphs and atomic paragraph/box codec round trips. Host fixtures cover wrapping/hanging spaces, justification, physical alignment, indents, empty paragraphs, spacing, overflow and malformed coverage. Device-unverified. Asset/APK increment: zero assets; code delta unmeasured, no packaging.

T3b remains required: wire the ICU opportunities and styled cluster advances into native layout preparation; enumerate bounded wrapping/membership variants under animated layer size/tracking; prepare selectors/permutations for each; integrate paragraph positions, bounds/anchors/Fit/hit tests, the fixed Paragraph pages and box-width preview handle using the existing gesture protocol; add wrapping parity/performance instrumentation fixtures. The current 64-layout cap is not yet proven for animated wrapping. Do not claim T3 complete or relax the preparation/per-frame requirements. T4 and T5 follow T3; no device results are required to continue.

Exact T3a user-run tests: run the full compile/JVM tasks from `t3a-paragraph-foundation.log`, then `py -3 tools/text/build_host.py`, host `selftest`, and `shaping_parity.py`. There are no new T3 device controls/fixtures yet; the T2b/T1 lists remain valid. F1 performance/parity/lifecycle remain deferred and unrun.

### Current handoff: T2b font library complete in source

T2b implements SAF import, background isolated-process inspection/shaping/rasterization, an AtomicFile app-private SHA-256 font library, collection face selection, named instances and static variation axes. Requested project references survive missing files; the renderer uses the bundled sans fallback and the Text page reports missing fonts. Character controls include fixed Library and Variable Axes pages. Empty text now retains an insertion style. Interactive library changes invalidate prepared metrics and geometry; export pins its prepared font-resource epoch. No PreviewController API, gesture protocol or batch/draw contract changed.

Verification: `tools/text/results/t2b-final-preview-safety.log` passed native Debug/Release for arm64-v8a and x86_64, Kotlin Debug/Release, both instrumentation source sets and all 205 JVM tests. Host Release/selftest, six shaping references, CFF variable-font and TTC face-1 proofs, and all 1,445 checksum checks passed. No APK packaging or device execution occurred. F1 perf/parity/lifecycle remain deferred and unrun. T3, T4 and T5 remain unimplemented; continue T3 next without awaiting device results. Earlier T2a-only handoffs below are historical.

Limits: 16 MiB per imported file, 256 library files/256 MiB total, 256 faces per collection, 16 axes and 256 named instances per face; 64 imported identities/32 MiB font bytes per isolated preparation request; 32 MiB prepared packet and 64 MiB retained imported packets. Metadata and index are bounded to 4 MiB and 8 MiB. Excess limits fail explicitly. The 64-layout limit still covers held source documents × ligature mode; T3 must additionally prepare wrapping membership changes caused by animated size/tracking. It must neither freeze those memberships nor generate permutations during playback.

Deviations/assumptions: collections import all valid faces by default and deletion removes the file/all its faces after confirmation. Collections containing any unsupported face are rejected as a whole. Fonts containing COLR/CBDT/sbix are rejected even if monochrome outlines also exist. Fallback first seeks one bundled face covering the complete base-plus-combining sequence; if none covers it, the requested face's missing glyph remains. Axes are static, clamped to metadata ranges, and committed through held Source Text. The library's CPU SDF preview is not a production-shader parity result. Isolated-process crash containment and the 60-second watchdog are implemented and have source acceptance fixtures; device crash containment and font fuzzing remain unverified.

APK estimate: no new production font assets in T2; code/native size delta is unmeasured because packaging is prohibited. The 1,884,148-byte Source Serif variable CFF fixture and its OFL license are instrumentation assets only. T1's font assets remain approximately 1.86 MB ZIP-deflated.

Exact T2b user-run list:

1. Import TTF, CFF OTF and a multi-face TTC through Library. Select faces/named instances, adjust each axis, undo/redo, save/reopen, copy/paste/duplicate and split keyed Source Text. Verify imported references and styles persist, including empty-text insertion styles.
2. Delete a referenced font with confirmation: the missing-font flag appears, bundled sans renders, and the requested reference remains unchanged. Reimport the same bytes and verify restoration. Check bounds, anchors, Fit/Stretch, overlays and hit testing before/after deletion.
3. Run every `FontImportAcceptanceTest` method: isolated inspection/crash/retry, malformed rejection, app-process parser rejection, metadata round trips, CFF variants, named instances, both ligature modes and missing-font preservation. Test oversized files, invalid axes and colour/bitmap-only fonts; errors must retain the document.
4. Run `TextF2AcceptanceTest.importedVariableRepresentativeColdAndThermal` and `importedVariableMaximumColdAndThermal` with unique `textRunLabel` values. Archive both cold/thermal JSON reports. Expect zero allocation/shaping/rasterization/upload/permutation and font-worker request deltas over each measured 600-frame window; inspect p50/p95/p99/max rather than assuming 60 fps.
5. Run `TextF2AcceptanceTest.importedVariableRandomAndReversePreEncodePixelsMatch`, plus the existing rich-text parity fixture. Check equal-resolution preview/export/thumbnail pixels during random access and reverse scrubbing.
6. Repeat the T1 character/bounds/paint-order checks, especially imported fonts at 8/72/500 px, tracking through zero, faux styles, combining marks and static axes. F1 performance/parity/lifecycle remain separate deferred checks.
7. Reproduce host/JVM checks using `tools/text/build_android.py`, `build_host.py`, `verify_inputs.py`, `shaping_parity.py`, host `selftest`, and `importproof` with the CFF fixture and generated TTC face 1. Expected JVM count: 205; input checks: 1,445.

The original T1–T5 specification is preserved verbatim in [TEXT_COMPLETION_SPEC.md](#rec-text-completion-spec). The continuation authorizes gated sub-commits: T1a foundation, then T1b functional character formatting, followed by T2–T5 in order.

### T1c distance-budget follow-up — 2026-10-04

Faux bold reserves its 0.025 em outline expansion from the shared 0.10 em stroke/softness budget. Requested widths remain in the document; effective stroke is at most 0.15 em on faux-bold runs and blur uses the remainder. The Character spacing page reports clamped effective widths for the current selection. Identical-time evaluation reuses the prepared result. Synthetic small caps identify lowercase by Unicode category, including sharp-s whose simple uppercase mapping is unchanged.

The compile gate passed native Debug/Release on both ABIs, Kotlin Debug/Release, both instrumentation source sets and the full 197-test JVM suite (zero failures/errors/skips). Host Release/selftest, six shaping references and 1,443 checksum checks passed. Evidence: `tools/text/results/t1c-t2a-final.log`. No assets were added; actual APK code delta is unmeasured. Device verification remains outstanding. User checks: repeat T1b tests below, specifically faux bold plus thick stroke/blur at 8/72/500 px in both paint orders; selected-range clamp hints; requested-value persistence; synthetic small caps on sharp-s; and the rich cold/thermal and pre-encode parity fixtures. No protected API, gesture or draw contract changed.

### Current continuation handoff — T1 committed, T2 transport foundation

T1a is committed as `5772f10`; T1b as `df056c4`. The complete T1b compile/JVM/host gate passed. T2a adds a preparation-only binary transport for immutable styled layout/membership data and owned R8 SDF pixels, plus packet export/metric validation and a native renderer preparation entry point. The renderer can retain these results without parsing imported font bytes. This is infrastructure, **not completed T2 font support**: SAF import, isolated font inspection/shaping/rasterization, the private library, missing-font indicators, fallback, static variations/named instances and functional library/axis controls remain T2b work. T3/T4/T5 remain unimplemented. No approval or F1 device result is needed to continue the authorized stages.

T2a verification passed native Debug/Release for arm64-v8a and x86_64, Kotlin Debug/Release, app/core instrumentation-source compilation and the full 197-test JVM suite, with zero failures/errors/skips. Final log: `t1c-t2a-final.log`; instrumentation fixture log: `t2a-instrumentation.log`. The initial native gate rejected misleading indentation under `-Werror`; it was corrected. Host Release/selftest and six independent shaping references passed. Host transport fixtures prove exact layout/membership round trips, owned pixels and rejection of sampled truncated packets, unsupported versions, excessive font counts and nonfinite geometry. Portable font inspection validates all twelve bundled static faces and rejects invalid magic; metadata includes collection face identities and bounded variation axes/named instances. Inspection is not yet exposed to the app: T2b must provide isolated crash containment. The Android `preparedTransportPreservesRichMetrics` fixture is compiled but unrun. These checks do not prove imported-font crash containment or imported-font GPU parity.

Transport safety bounds: 32 MiB per packet, 16,384 physical glyphs (allowing substitutions to expand the 4,096 source units), 4,096 characters/clusters, 4,097 lines, 512 font identities and individual SDF dimensions no larger than a 2048² atlas page. Counts, arithmetic, finiteness, memberships, glyph/pixel identities, pixel lengths and trailing bytes are validated before renderer use. Rejection retains the document; no glyphs are dropped. This transport is ephemeral preparation data and does not change project v12, the 68-byte text vertex, 96-byte modifier, draw/batch contract, PreviewController or gesture protocol. T2b must bound aggregate preparation memory and isolate all untrusted font work; inspecting in a child and then reopening the font in the app would not satisfy the specification.

T2a adds no assets; its APK code delta is unmeasured (no packaging). T1 font assets remain approximately 1.86 MB compressed. All new functionality is source-implemented, device-unverified; F1 perf/parity/lifecycle remain deferred and unrun.

Exact T2a user-run list:

1. `py -3 tools/text/build_android.py --label t2a-user --task app:compileDebugKotlin --task app:compileReleaseKotlin --task app:compileDebugAndroidTestKotlin --task core-engine:compileDebugAndroidTestKotlin --task app:testDebugUnitTest --task core-engine:externalNativeBuildDebug --task core-engine:externalNativeBuildRelease`; expect the compile tasks and all 197 JVM tests to pass.
2. `py -3 tools/text/build_host.py`, then `tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest`; expect prepared transport and existing CPU assertions to pass. Run the six shaping references and 1,443 input checks as for T1a.
3. When performing user device checks, run `TextF2AcceptanceTest.preparedTransportPreservesRichMetrics`: both ligature modes must preserve exact rich metrics and reject unsupported packet versions. It is preparation-only; use the T1b rich parity/performance fixtures for the currently integrated renderer. Font import/variable-font acceptance is not ready until T2b.

The 64-layout bound currently covers held documents × ligature mode, including each document's width/direction fields. T3 must additionally resolve visual wrapping under animated layer size/tracking; the current combinatorial test does not prove bounds for those reflows. Do not silently freeze line membership or generate permutations on the evaluation path to avoid that work.

### T1a foundation gate — 2026-10-04

T1a is implemented in source, device-unverified. The continuation gate passed app Kotlin Debug/Release, instrumentation-source compilation and the full JVM suite (190 tests, zero failures/errors/skips; `tools/text/results/t1a-gate.log`). The earlier native Debug/Release checks for both ABIs, host Release/selftest, six shaping references and all 1,443 checksum checks cover the unchanged foundation sources. This commit contains the rich document/codec/validation, held-source editing preservation, bundled style faces/checksums/licenses and tests, plus explicit guards preventing unfinished rich rendering from degrading silently. T1b rendering and functional controls are next; no T2–T5 functionality is claimed.

Assumptions/limits and the relative-size deviation are recorded below and in the specification. Font assets add 3,646,029 bytes uncompressed; ZIP-deflate estimate 1,855,388 bytes. Actual APK delta remains unmeasured; no APK packaging or device execution occurred. PreviewController, gestures and batching/drawing are unchanged.

Exact T1a user-run checks (host/JVM; no new phone fixture yet):

1. `py -3 tools/text/build_android.py --label t1a-user --task :app:compileDebugKotlin --task :app:compileReleaseKotlin --task :app:compileDebugAndroidTestKotlin --task :app:testDebugUnitTest`; expect 190 tests, zero failures/errors/skips. `TextSourceDocumentTest` covers cluster-safe selections, inherited/merged runs, held document identity, rich codec round trips/unknown fonts and invalid models.
2. `py -3 tools/text/verify_inputs.py`; expect all 1,443 inputs to pass, including nine added font/OFL pairs.
3. `py -3 tools/text/build_host.py`, then `tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest`; expect all nine style faces to shape/rasterize without Regular fallback, plus the existing CPU checks.
4. `py -3 tools/text/shaping_parity.py --proof tools/text/results/host-build/Release/text-proof.exe`; expect all six shaping references to pass.
5. Existing F1 perf/pre-encode parity/lifecycle device runs remain deferred; T1b will supply the functional rich-text device list.

#### T1 foundation validation bounds and early verification

Preparatory verification: pinned-input checksum verification passed (1,443 inputs); the full JVM recheck passed 190/190 with zero failures/errors/skips. The initial 188/190 run exposed schema-generator nullable-accessor compatibility and an obsolete v12 future-version assertion; both were corrected. Host Release compilation and runtime selftest passed, including all nine added style faces; all six independent shaping references passed. Native Debug/Release compilation passed for arm64-v8a and x86_64; app/core Kotlin Debug/Release and instrumentation-source compilation passed. The final Debug/instrumentation compile recheck passed with the latest renderer guards. Logs: `tools/text/results/t1-model-recheck.log` and `t1-final-guards.log`. No APK packaging or device execution occurred. These checks do not establish complete T1 functionality.

Preparatory validation choices: size factors `(0,100]`; horizontal/vertical scales `[0,10000]%`; manual kerning `[-10000,10000]` thousandths em; font face indices `0..255`; named-instance indices `-1..65535`; at most 16 distinct four-ASCII-character axis tags; family/PostScript labels at most 128 UTF-16 units without NUL. Imported-font axes accept static tracks only in this checkpoint. Formatting selections expand to whole source-character clusters. Inserted text inherits the run at the caret (the last run at end-of-text); newly joined combining clusters inherit their initial character's style. Paragraph-record retention during string edits is provisional until T3; paragraph rendering currently fails explicitly.

### T1b styled rendering and character editing — 2026-10-04

T1a was committed as `5772f10`. T1b integrates styled per-run shaping and real face selection, per-glyph geometry/paint, source-preserving caps, native OpenType small-caps/script features with synthetic fallbacks, metrics/off kerning, run/manual spacing, and selection-based Character controls. Bounds, layer anchors, Fit/Stretch, overlays and hit testing consume the rich document. Source typing carries caret ranges and progresses from the preceding draft, including ambiguous repeated-character insertions; animated Source Text writes one atomic held document key. Each formatting operation uses the existing undo transaction. No PreviewController, gesture protocol or batch/draw contract changes were made.

Preparation caches whole rich-document identities (including held box width and paragraph direction) and both effective ligature modes. The 64-layout cap remains appropriate: at most 32 distinct reachable atomic documents × two modes. Width and direction are held inside each source document; multiplying independent values into unreachable combinations would overcount. Exceeding the cap fails explicitly. Box/paragraph rendering and RTL fail explicitly until T3/T5; preserving their v12 records is not implementation of those stages. Renderer evaluation uses reusable spacing, paint, modifier and bounds storage.

Assumptions/deviations: run size is relative to the animated layer size, unlike AE absolute range size. Faux bold is a uniform 0.025 em outline expansion (SDF threshold bias 0.075); faux italic is a 12° baseline shear. Synthetic small caps use 70% size/advance; synthetic super/subscript use 58.3% size and ±33.3% of the parent run em. Parent run font metrics determine natural leading; vertical scaling, caps and script geometry do not change it. Simple uppercase is Java's one-codepoint mapping, so sharp-s remains sharp-s. Synthetic small-cap scale changes split shaping chunks and omit kerning across those boundaries. Manual kerning inside an indivisible ligature is summed at its cluster end. Run/animator tracking does not split ligatures; effective layer tracking selects the prepared non-ligated layout away from zero. Run deltas use the effective run em and horizontal scale. Colour controls are functional ARGB fields; displayed values come from the first selected run. Formatting an empty string has no run to retain an insertion style.

T1b adds no font assets: incremental assets remain 3,646,029 bytes uncompressed / 1,855,388 bytes estimated ZIP-deflate. Native/Kotlin code size is not an APK size measurement; actual APK delta is unmeasured because no APK was packaged. New device fixtures are source-only, device-unverified. F1 perf/parity/lifecycle remain unrun and deferred. Native Debug/Release passed for arm64-v8a and x86_64, and Kotlin Debug/Release, app/core instrumentation compilation and all 196 JVM tests passed (zero failures/errors/skips; `t1b-gate-final.log`, `t1b-final-controls.log`). The final compact-control compile/JVM recheck passed (t1b-compact-gate.log, 196/196). Host Release/selftest, all six independent shaping references and all 1,443 input checks passed. Host tests include styled font/geometry, baseline/shear/bold, uppercase membership, metrics/off kerning and unchanged leading under vertical scaling. The six added JVM fixtures include rich spacing/paint/clamps, caret inheritance, held width/direction layout limits and zero allocations over a warmed 600-frame prepared evaluation.

Exact T1b user-run list:

1. Edit selected ranges, then clear selection to format all text. Exercise all Character subpages: twelve real faces, independent faux toggles, all/small caps, super/subscript, horizontal/vertical scale, metrics/off/manual kerning, relative size, paint overrides and spacing/baseline deltas. Use `office`, `AV`, combining marks, sharp-s, surrogate pairs, multiple paragraphs, trailing spaces and empty text.
2. Style the middle characters in repeated `aaaa`; insert/delete at the middle caret and both ends, paste over a selection and add combining marks. Verify inheritance/merging, one undo per session/edit, redo, persistence, copy/paste/duplicate/split, held Source Text keys and key navigation.
3. Animate layer size/fill/tracking through zero/leading/baseline with runs and F2 modifiers. Run/animator tracking preserves ligatures; nonzero layer tracking splits them. Check 8/72/500 px, both paint orders with thick stroke and negative tracking, clamp display, round joins and enlarged contours.
4. Check mixed fonts/scales/faux/script with alignments, anchors, Fit/Stretch, overlays and hit testing. Vertical scaling must not increase leading. Compare text-only and mixed raster/video projects.
5. Run `TextF2AcceptanceTest.richRepresentativeColdAndThermal` and `richMaximumColdAndThermal`, each with a unique `textRunLabel`; archive both cold and thermal JSON reports. Fixtures retain native-new/ART counters, shaping/rasterization/upload/permutation canaries, warm 120 + measure 600 + three-minute repeat, p50/p95/p99/max. No A16 performance is claimed from host results.
6. Run `TextF2AcceptanceTest.richRandomAndReversePreEncodePixelsMatch` for equal-resolution preview/export/thumbnail parity with styles, random access and reverse scrubbing. Resume unchanged F1 parity/lifecycle fixtures when desired.
7. Run the full JVM suite, input checksum verification, host selftest and six independent shaping references. The prepared evaluator has a 600-frame JVM allocation fixture; Android counters remain the device acceptance evidence.

### E1d implementation pass — 2026-10-01

The user accepted E1b/E1c and explicitly authorized E1d, E1e, E2 and E3 in one ordered pass, superseding the earlier stop gates for implementation. The selected GPU default is one **lazy 2048×2048 R8 atlas per renderer**. Previous 16 KB/golden checks remain outstanding verification evidence; they no longer block this authorized source work.

E1d source adds ordered raster/text entries within the existing four-entry batch, one indexed glyph mesh per text entry, a fence-protected preparation phase, retained CPU SDF leases, whole-atlas staging upload with explicit transfer/sample barriers, derivative coverage, straight alpha and disabled face culling. Optional glyph-local affine/colour/opacity modifiers default to identity. CPU shaping stays on the renderer worker; `PreviewController` and the gesture protocol are unchanged. Shaders are generated/embedded by CMake during the user's build; no generated SPIR-V is checked in.

Debug `TextFixtureProvider` is consumed inside `CompositionRenderer` under `BuildConfig.DEBUG`; release supplies a no-op. The native debug instance enables validation when installed and reports its actual availability/error count. `TextGpuProofTest` fails when validation is absent. Normal debug editor sessions inject nothing.

**Verification:** implemented in source, device-unverified. The user subsequently authorized compile-only checks and JVM/host tests. E1d/E1e native debug/release compilation passed for arm64-v8a and x86_64; app/core Kotlin and instrumentation-source compilation passed. Host Release compilation passed. E1e's three policy JVM tests passed. The initial full JVM run passed 125/126 tests; its older automatic-duration assertion was corrected in E2, whose full run passed 140/140. No APK installation or device execution occurred. E1 proof overflow still raises `TextCapacityException` before composition draws.

**User-run E1d checklist:**

1. In Android Studio compile debug/release and both native ABIs. Confirm CMake generates both text shaders; inspect diagnostics individually.
2. Install/enable `VK_LAYER_KHRONOS_validation` for the debug app on the device. Run `TextGpuProofTest.rendersThroughCompositionRendererWithValidation`; missing validation must fail explicitly.
3. Inspect Inter `AV`, Noto Serif `office`, descenders/newlines and missing-codepoint output through the composition renderer. Compare shaping with the existing independent CPU references.
4. Verify the lazy atlas, one text draw entry/indexed mesh, no glyph hardware buffers, ordered mixed batches, zero new rasterizations on a warm redraw, and zero validation errors.
5. Retain APK hashes, device/OS/GPU/page-size and logs. Re-run packaged native alignment checks and the outstanding r26/r28 golden/16 KB checks; source implementation does not establish these results.

The historical E1a–E1c record below retains its original evidence and superseded gate wording; the authorization above governs this pass.

### E1e instrumentation — implemented in source, unverified

`TextGpuAcceptanceTest` uses a debug-only visible SurfaceView host and drives the same composition renderer on the instrumentation owner thread. It captures PNGs/native counters for nine 50/100/300% × Full/Half/Quarter combinations, 8/12/14/16px typography, opacity/rotation/flips, missing glyphs and seven mixed entries. A 120-frame warm scale sweep and forced retained-pixel repack assert no additional SDF rasterization. The combined three-font Latin corpus exercises typed capacity failure before any text draw, followed by recovery.

After five warm-ups, lifecycle acceptance performs 30 actual surface detach/recreate/attach cycles with the same renderer/device, then five warm-ups and 30 full renderer create/render/destroy cycles. A debug allocation ledger records successful creates and actual destroys separately from renderer fields, by resource category and allocated bytes. Descriptor-pool destruction retires its sets. Surface cycles must return to their live baseline; full teardown must return to the pre-session baseline. Global validation errors include teardown. Native heap first/last ten-sample medians must remain within 1 MiB; three strictly increasing consecutive ten-cycle medians fail as sustained growth even below that ceiling. Global/driver allocations are outside this owned Vulkan ledger.

**User-run E1e checklist:**

1. Compile debug and JVM test sources in Android Studio. `TextProofPolicyTest` already passed on the host; retain fresh results with the device build.
2. Enable Vulkan validation for the debug application; choose a fresh instrumentation argument `textRunLabel`, such as `a16-e1e-01`. Run `scaleQualityMatrixSweepMixedOrderAndRepack`, `requiredCorpusCapacityFailureIsExplicitAndRecoverable`, and `thirtySurfaceAndRendererCyclesReturnResourcesAndBoundHeap` in `TextGpuAcceptanceTest` with the app otherwise idle. At E2 and later, also run `arrayGrowthUsesRetainedCorpusDuringSweepAndTeardownReturnsLedger`.
3. Archive `Android/data/com.fluxx.android.debug/files/text/<textRunLabel>/matrix/` and `lifecycle/`, including PNGs, per-fixture statistics, heap samples and allocation ledgers. Existing result directories are deliberately not overwritten.
4. Visually inspect all nine scale/quality PNGs, the small-size fixtures and on-screen composition. Reduced previews are compared against achievable resolution; watch for bleed, overlap holes, baseline/alignment errors and softened corners.
5. Verify mixed ordering across the four-entry boundary, opacity, rotated/flipped glyphs and visible `.notdef`; require zero validation errors. Warm sweep and retained repack must have zero new rasterizations.
6. Confirm capacity failure is explicit with zero prior text draws and the next small frame succeeds. This is an E1 proof constraint; production overflow is addressed separately in E2.
7. Check all 30 surface and 30 full-renderer cycle counters by category, native heap criteria and post-teardown baseline. Record available driver memory separately. Repeat manual background/foreground, cancellation and orientation interruptions; they are not established merely by SurfaceView replacement.
8. Record device/OS/GPU, APK hash, page size and validation-layer provenance. Repeat packaged alignment/16 KB and existing Video/Image/Solid/audio regressions. No device verification is claimed.

**Historical E1a–E1c update:** E1a render/playback/export smoke was verified by the user on A16. E1b/E1c were accepted before the later authorized pass. Recorded host/Android compilation results below apply to that earlier source only. E1d/E1e compile and host/JVM evidence is recorded above. E2 and E3 are implemented in source and compiled, device-unverified. Outstanding 16 KB/golden/device checks remain unverified.

**Verifier correction:** the initial audit incorrectly required raw GNU_RELRO endpoints to be 16 KB aligned. Android rounds them before applying protection; a nonaligned endpoint alone is not incompatibility. Oboe/graphics-path source rebuilds remain, but earlier RELRO-only failure claims are withdrawn. The corrected verifier checks LOAD alignment/congruence and TEXTREL while inventorying RELRO; prior reports need regeneration. See [Bionic protection logic](https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/linker/linker_phdr.cpp), `_phdr_table_set_gnu_relro_prot`.

### E2 implementation and approved array overflow

E1d is committed as `16f2d10`; E1e as `90af786`. E2 implements FlatBuffers v9 typed source/size/fill properties, native-backed and fake metrics, natural-pixel text geometry, text Fit/Stretch actions, temporal rebasing and common property navigation. Preview, headless export and thumbnails use the shared text renderer. The single-video fast path continues to exclude Text. Product editor controls are implemented in E3, described below.

The approved overflow design replaces the previous streaming proposal. Each renderer starts with one lazy 2048x2048 R8 array layer. If the frame's pinned union does not fit, unused resident glyphs are removed first; necessary growth recreates the 2D_ARRAY atlas at the frame boundary after GPU idle and reuploads retained CPU SDF leases. Growth never requires rerasterization. One staging upload command covers every array layer, followed by transfer/sample barriers, before any composition draw. A per-vertex page index preserves glyph/overlap order and one indexed draw per text entry; the four-entry batch contract, gesture protocol and PreviewController API remain unchanged.

Initialization checks R8 sampled/linear-filter/transfer support, image extent and both image/physical array-layer limits. The cap is four pages (16 MiB R8 texels), or two (8 MiB) on low-RAM devices. Staging, meshes, allocation alignment and CPU leases are additional memory. Beyond the cap, a typed capacity error identifies affected text layers and fails the entire frame before any composition draw; preview publishes an explicit layer error state and retains the last completed presentation. There is no project-layer quota or silent glyph omission.

Arrays never shrink during a renderer session; teardown releases them. Each successful recreation increments `arrayRecreations`; the independent Vulkan ledger records old destruction and new allocation by resource category. Pinned CPU leases survive preparation failure/retry. The E1 fixture provider deliberately keeps its single-page failure mode. The additional instrumentation test warms the corpus in that mode, grows to two pages during a scale sweep with unchanged rasterization count, and checks teardown against the live-resource baseline. Device execution remains outstanding.

Source strings are limited to 4,096 UTF-16 units and the document's aggregate source bytes to 1 MiB. Decode rejects malformed UTF-8, NUL/CR/unpaired surrogates, unsupported animator data, invalid easing and unordered/frame-duplicate text keys; finite persisted sizes clamp to the provisional 8-500px range. The 8px lower limit still needs E1e visual acceptance. Unknown requested font IDs persist and use native fallback. Colour interpolation is linear-light, premultiplied, with exact packed endpoints; Source Text is hold-only. Native layout caches exclude size/alignment. Current and next held strings prewarm on the renderer worker; arbitrary uncached seeks wait for exact layouts.

Compile evidence is recorded per stage; native debug/release for both ABIs, Kotlin debug/release, instrumentation source and 140 JVM tests passed for E2. Host Release compilation and self-tests passed, including the two-page combined corpus, retained-lease growth and four-page rejection. Final source remains device-unverified. Logs are local ignored artifacts under `tools/text/results/`; no APK was installed or launched.

**User-run E2 checklist — implemented in source, device-unverified:**

1. Compile debug/release and both ABIs; run v9 codec/decode-validation and v1–8 compatibility JVM tests. Cover malformed strings, finite size limits, signed/frame-unique keys, aggregate payload limits, unknown font preservation and explicit unsupported nonempty animator rejection.
2. Run fake-metrics geometry tests: natural pixels, multiline logical/ink bounds, empty bounds, static baseline compensation, animated anchors and evaluated Fit/Stretch with Scale auto-keying.
3. Run typed size/fill/hold-source evaluator/reducer tests: exact boundaries, transparent colours, endpoint holds, move/trim/split rebasing, copy/paste, undo and autosave round trips.
4. Compare device preview, headless export and thumbnails with animated text plus transforms, transparent fill and empty-source keys. Preserve single-video fast-path exclusion.
5. Run `TextGpuAcceptanceTest.arrayGrowthUsesRetainedCorpusDuringSweepAndTeardownReturnsLedger`: the combined three-font corpus must fit two pages, growth/sweep must add zero rasterizations, and teardown must restore the live-resource ledger. Exercise >4 entries, complete output, overlap/z-order, bounded residency and explicit allocation failures. Re-run every E1 harness test, including single-page capacity failure.
6. Verify warm size/fill sweeps and held-string playback produce no shaping/SDF misses merely because time/size/fill advances. Uncached seeks wait for the correct revision; exports/thumbnails never use stale layouts.

### E3 editor integration — implemented in source, device-unverified

E3 enables the Add Content Text pill and Edit Text tab, fixed font/alignment/size/fill controls with a scrolling source field, shared Global Colour Picker/eyedropper, held-string timeline labels, shared-metric preview bounds/handles/hit testing and natural-pixel anchor/Fit/Stretch actions. Workspace IME handling temporarily hides timeline/actions and presents a compact Done/Cancel panel with the letterboxed preview.

Typing captures the snapped edit time and original snapshot; revisions conflate worker metrics, and source/layout/allowed Position compensation publish atomically. Navigation waits for the latest draft; one typing session commits one existing gesture undo entry, cancellation restores the snapshot, and stale worker results cannot revive canceled drafts. Metrics calls execute only on the dedicated worker and never wait for the font mutex on the UI thread. Style edits preserve the latest committed source. Size/Fill/Source Text reuse shared keyframe controls, common navigation, freeze behavior, signed keys and easing; source stays Hold-only.

Native mesh storage is reused after the existing completed batch fence, bounding uploads to pending entries without adding a pass break or changing ordered draw entries. The debug E1 harness remains compiled. E3 final verification passed native debug/release for both ABIs, app/core Kotlin and instrumentation source compilation, and 150/150 JVM tests. The ten additional editor tests cover session undo/cancel, captured key times, stale results, invalid drafts, style/source races, creation defaults, geometry, copy/paste/persistence and common navigation. Device behavior remains unverified.

**User-run E3 checklist:**

1. Build/install the debug app yourself. Run E1d/E1e first, then the E2 checks above; retain device, OS/GPU, page size, validation provenance, APK hash and test artifacts.
2. Add Content → Text: confirm an assetless five-second layer at the frame-snapped playhead, selected inspector, default Inter/72px/white/centre, one creation undo, and explicit composition duration preserved. Verify duplicate/copy/paste, undo/redo and save/reopen retain typed properties and requested font IDs.
3. Open Edit Text at both preview sizes, landscape and enlarged system fonts. Verify fixed font/alignment/size/fill controls and source-field-only scrolling; empty source keeps an anchor target and disables Fit/Stretch. Unsupported effects, outlines, range selectors and animators remain inert.
4. Repeatedly show/dismiss the IME, enter multiline text with composing regions, and use Done, Back, focus loss and Cancel. Preview and compact Done/Cancel remain visible; timeline/actions restore their earlier layout. Confirm one undo per typing session, exact redo and Cancel restoring source/keys/Position.
5. Type and immediately seek, play, switch layers/properties, change font/alignment, open the picker, leave the workspace, or save/export. The latest valid draft commits at the captured snapped time before navigation. Rapidly cancel/restart while metrics are busy; stale results must not overwrite the new session. An invalid final draft must roll back the session with an explicit error.
6. Exercise Size/Fill/Source diamonds, add/remove keys, freeze at playhead, common previous/next-key navigation and signed split/trim timing. Size/Fill offer Linear/Easy Ease; Source stays Hold. Inspect held timeline labels and compare evaluated preview, reopened project and export.
7. Reuse the Global Colour Picker and eyedropper for static and animated fill; verify one gesture undo and cancellation. Test font fallback display, alignment, multiline/overhanging glyphs, rotation and negative scale. Compare shared bounds/handles/hit testing, anchor edits and Fit Width/Height/Stretch; fitting changes Scale, preserving Size.
8. Stress rapid typing during export and thumbnail preparation; inspect UI responsiveness and thread traces to confirm no UI-thread font-mutex waits. Trigger array growth and typed capacity failure; require explicit layer error and no partial composition frame, then edit to recover. Re-run E1 lifecycle/resource and existing Video/Image/Solid/audio regressions.

**Design deviations:** the user-approved array growth replaces the earlier streaming proposal; arrays shrink only on renderer teardown. No batch-structure, gesture-protocol or PreviewController API change was needed. The provisional 8px minimum remains subject to device quality acceptance. No device result is inferred from compilation/JVM tests.

### Historical E1a toolchain and packaging changes

- Both modules default to NDK r28c, `28.2.13676358`. CMake 3.22.1, C++17, `c++_shared`, arm64-v8a/x86_64, SDK levels and AGP 9.4.0 are retained.
- Build-tools is explicit at 37.0.0 (installed locally). `jniLibs.useLegacyPackaging = false` is explicit in the app. The source manifest has no conflicting `extractNativeLibs` declaration; AGP supplies it. The verifier requires **false in the compiled manifest**, rather than trusting the DSL.
- `fluxxengine` receives `-Wl,-z,max-page-size=16384` on the normal r28 path. This is intentionally explicit although r28 supplies the alignment default.
- Temporary Gradle property `fluxxNdkVersion=26.1.10909125` selects the r26 baseline in both modules, restores the original Maven Oboe and AndroidX graphics-path binaries, and omits the added engine linker flag. Remove the property for the r28 candidate. The APK records the selected NDK in `BuildConfig.NATIVE_NDK_VERSION`; this is configuration provenance, not a binary compiler attestation.
- E1a changed toolchain/packaging only; E1b/E1c CPU typography is recorded below. Text rendering, model and editor changes remain unimplemented.

The packaging checks follow [Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes). AGP is above 8.5.1 and build-tools above 35.0.0; versions alone are not acceptance evidence.

### Native dependency audit

| Input                        | Current handling / evidence                                                                                                                                                                                                                                                                      |
|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Oboe 1.11.0                  | Rebuilt as PIC static source under r28; explicit r26 comparison mode retains its Maven AAR. Original arm64 RELRO-only rejection was a verifier false positive, not proven incompatibility. No TEXTREL was found. AAR SHA-256 `316f31ce92f07725a41556cb1ec0790b348e36b55791df9e5e44976e1f652c30`. |
| Oboe provenance              | `core-engine/third_party/oboe/FLUXX_SOURCE.json`: archive/source hashes for 143 unmodified retained files; Apache-2.0 source and app notice.                                                                                                                                                     |
| AndroidX graphics-path 1.0.1 | Matching Kotlin/JNI rebuilt from commit `8a05a22af450d589ef911d772a001a49dcb05b71`; normal builds exclude the external module, while r26 retains it. Same class/library names and keep rules. Initial 1.0.1/1.1.0 RELRO-only rejections are withdrawn; no arbitrary version upgrade was adopted. |
| graphics-path provenance     | `core-engine/third_party/graphics-path/FLUXX_SOURCE.json`: archive and 18 unmodified source hashes; original build file retained as reference; Apache-2.0 notices. Fluxx CMake wrapper preserves code-generation options/shared C++ runtime.                                                     |
| `/libs`                      | `.gitkeep` only; no prebuilt .so/.a.                                                                                                                                                                                                                                                             |
| Media3                       | Ten inspected 1.11.1 AARs contain no .so/.a; inventory in `tools/e1a/results/media3-input-audit.json`. Packaged scan remains authoritative for resolved dependencies.                                                                                                                            |
| Native outputs               | Current r28 debug/release ELF audits pass for engine, graphics-path and libc++ across both ABIs. New packaged APK/ZIP and runtime checks remain separate.                                                                                                                                        |

The initial old debug APK SHA-256 was `0049fc88ecebe6cdaf42e57fd3c56e33d663d0742d3daa7e26a29ebca07f325c`: ZIP alignment and compiled `extractNativeLibs=false` passed, while engine/C++ runtime had genuine 4096-byte LOAD alignment. Its RELRO-only findings are superseded by the corrected verifier. This old-artifact report (`tools/e1a/results/existing-debug-native.json`) is not acceptance evidence for new builds.

The [graphics-path release history](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path-1.0.1) identifies the source revision. Static archives inherit their containing shared object's final alignment; verify every packaged .so, including transitive dependencies and libc++.

#### Page-size source audit

Searched native application code and Kotlin audio code for `sysconf(_SC_PAGESIZE)`, `getpagesize`, `mmap`, offsets/lengths, `4096`, `0x1000`, `MAP_FIXED`, `mprotect`, and `PAGE_SIZE`. No app-owned page-size dependencies were found. Vulkan allocations continue to use Vulkan memory requirements.

The current disk-backed `AudioMixer` uses `RandomAccessFile.seek/readFully/write`, **not `FileChannel.map`**. Its offsets describe PCM samples, not native memory mapping. No alignment workaround is needed. Revisit this audit if mapping is introduced later.

Vendored Oboe's `4096` in `AudioStreamAAudio.cpp` is an audio capacity in frames for the legacy FAST track, not a byte/page alignment. Its `aaudio.mmap_*` property names select AAudio policy, not direct application `mmap` calls. These were left unchanged.

The vendored graphics-path sources contain none of the searched page-size dependencies. SDK 37's `android.jar` contains the `FastNative` annotation used by the unchanged Kotlin sources; source compilation passed during E1b/E1c.

#### Clang diagnostics

The [build checks and focused fixes](#build-checks-and-focused-fixes) below are the current diagnostics ledger. Fix genuine diagnostics individually; do not globally suppress warnings. Debug/release native compilation passed; this does not establish packaged/runtime acceptance.

### Read-only artifact verifier

Requirements: Python 3.11+ (`py -3`), installed SDK build-tools 37.0.0 and NDK r28c. From repository root, after **you** build each APK:

```powershell
./tools/e1a/Verify-Native.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk -Sdk "$env:LOCALAPPDATA/Android/Sdk" -Json tools/e1a/results/debug-native.json
./tools/e1a/Verify-Native.ps1 -Apk app/build/outputs/apk/release/app-release.apk -Sdk "$env:LOCALAPPDATA/Android/Sdk" -Json tools/e1a/results/release-native.json
```

Use the actual generated release filename if different. The Python CLI also accepts repeated `--apk`, `--native-input` (AAR, `.so`, `.a`, or directory), and `--abi` for intentionally split APKs. Example additional input inventory:

```powershell
py -3 tools/e1a/verify_native.py --sdk "$env:LOCALAPPDATA/Android/Sdk" --native-input libs --apk app/build/outputs/apk/debug/app-debug.apk --json tools/e1a/results/native.json
```

The verifier inspects **every** packaged `.so`, checks both expected ABIs, hashes binaries/APKs, rejects compressed libraries and TEXTREL, checks LOAD alignment/congruence and inventories GNU_RELRO using `llvm-readelf -lW/-dW`, verifies the merged `extractNativeLibs=false`, and runs `zipalign -c -P 16 -v 4`. It never rewrites the APK. JSON schema 1 and a human summary are emitted; exit 0 = artifact pass, 1 = incompatibility, 2 = input/tool error. Runtime remains explicitly unverified even on an artifact pass. AAR inventory is not a Gradle dependency-resolution report; the final packaged scan covers what actually ships.

### Fixed golden regression — user-run

The instrumentation fixture is `E1aGoldenExportTest.exportFixedMixedProject`. It exports the actual `CompositionExporter` path: six layers (two Video, two Image, two Solid), overlapping audio with different gains and source in-points, animated position/scale/rotation/opacity, and more than four simultaneously visible layers. Content-driven duration is exactly 6 seconds at 640×360, 30 fps (180 frames). It also saves the project and pre-encode mix (288000 stereo sample frames at 48 kHz).

1. Install/use FFmpeg and FFprobe on the host. Generate the source **once**:

   ```powershell
   py -3 tools/e1a/make_golden_media.py
   ```

   Generated MP4 and hash/command manifest live in ignored `app/src/androidTest/assets/e1a/`. The generator refuses to overwrite media. Keep these identical bytes for all comparisons; generator versions are not assumed byte-reproducible. Generation was not run in the recorded E1a audit; check host FFmpeg availability before running it.

2. In Android Studio, set temporary project Gradle property `fluxxNdkVersion=26.1.10909125`. Build the r26 baseline and run only `E1aGoldenExportTest` with instrumentation arguments `-e e1aLabel r26 -e e1aEnvironment physical-4k`. Archive the baseline APK, source revision/diff and build log. Use a clean native rebuild when switching NDKs.
3. Copy the complete device result directory `Android/data/com.fluxx.android.debug/files/e1a/r26/` to `tools/e1a/results/r26/` via Device Explorer. Preserve it before any uninstall. The test intentionally refuses to overwrite an existing result directory; archive it before a repeat.
4. Remove the NDK override, install r28c through Android Studio if needed, rebuild, and repeat on the **same physical phone/OS/codec environment** with label `r28`. Copy its complete directory to `tools/e1a/results/r28/`. Do not change source media, project definition or app code between exports.
5. Compare:

   ```powershell
   py -3 tools/e1a/compare_golden.py --baseline tools/e1a/results/r26 --candidate tools/e1a/results/r28 --json tools/e1a/results/golden.json
   ```

The report validates source/model/export hashes, matching device fingerprint/page size, 180 frames, dimensions, per-frame timestamps and SHA-256, and PSNR for differing frames. Default acceptance is **each** differing frame >=50 dB; aggregate PSNR cannot hide one bad frame. Threshold changes require an explicit recorded decision, not lowering it until a regression passes. Raw audio mixes must hash identically and have exact expected sample counts. Exported audio is checked for equal decoded sample counts and duration within one sample; AAC padding is bounded to one 1024-sample frame around 6 seconds. Encoded MP4 byte equality is not required. Inspect/listen as well: numeric parity alone is not complete device acceptance.

`run.json` captures NDK configuration, normalized project hash, input/output hashes, device/OS fingerprint and actual runtime page size. Keep both APK hashes/build logs alongside reports. The source fixture is implemented but has **not been compiled or run**.

### Runtime matrix and manual checklist

Record device model, OS/build fingerprint, GPU/driver, APK hash, `adb shell getconf PAGE_SIZE`, environment type, logs and result for each run:

| Environment                                                    | Loader / alignment                                                                                                  | Video AHardwareBuffer / MediaCodec on 16 KB                   |
|----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| A16 physical phone                                             | Render/playback/export smoke confirmed by user; explicit page-size result and full regression matrix still required | Not evidence of a 16 KB run                                   |
| 16 KB emulator                                                 | Pending                                                                                                             | **Unverified by emulator testing**, even if it displays video |
| Physical device booted with 16 KB pages (e.g. supported Pixel) | Pending                                                                                                             | Pending real-hardware playback, seeking, lifecycle and export |

An emulator demonstrates loader/alignment behavior only; its Vulkan/media stack does not establish the physical zero-copy path. If only an emulator is available, record that limitation explicitly and do not mark physical 16 KB hardware-buffer compatibility complete. Mixed export also includes CPU readback; do not describe that entire export path as zero-copy.

- [ ] Both debug and release packaged-native reports pass for arm64-v8a and x86_64.
- [ ] Golden r26/r28 comparison passes on the same 4GB device; archive exports, PCM, reports and APK hashes.
- [ ] Video/Image/Solid render correctly, including >4-layer composition and animated transforms/opacity.
- [ ] Full/Half/Quarter preview, expand-preview, seeking and playback show no regressions.
- [ ] Background/foreground, surface recreation and teardown show no new crash/linker/validation errors.
- [ ] Thumbnails and single-video fast-path export remain correct (separate from the mixed golden export).
- [ ] Compose path-dependent UI (timeline diamonds, curves, shapes/clips and inspector controls) remains correct with rebuilt graphics-path, on API 29–33 and API 34+ where available.
- [ ] Audio playback, mute, pause/resume and seeking show no new drift, doubled audio or corruption.
- [ ] 16 KB runtime evidence records emulator vs physical device honestly; record unresolved hardware-path coverage.
- [ ] Review and record each new compiler diagnostic individually.

### Verification boundary

All 11 host artifact/golden-tool regression tests passed during E1b/E1c. These use synthetic fixtures, not the user's actual exported golden project. A16 E1a smoke is user-confirmed; the 16 KB environment and fixed golden comparison remain mandatory before E1d. Detailed CPU shaping/SDF evidence follows.

### Text E1b / E1c — CPU implementation and verification

Historical 2026-10-01 CPU pass: E1a render/playback/export smoke was **confirmed by the user on Galaxy A16**. E1b/E1c added CPU typography only; their pass did not add Vulkan text or editor integration. The earlier E1d implementation gate is superseded by the later authorization recorded above. A16 smoke still does not establish 16 KB compatibility or golden-project parity.

#### Reproducible inputs

`core-engine/third_party/TEXT_INPUTS.json` records resolved release commits, archive SHA-256, individual retained source hashes, static font hashes and license hashes. Builds use vendored files offline; the maintainer download script is never called by CMake/Gradle.

| Input                  | Pin                           | License                               |
|------------------------|-------------------------------|---------------------------------------|
| FreeType               | 2.14.1                        | FTL; full attribution/license shipped |
| HarfBuzz               | 14.3.1; tag actually resolved | MIT-style license in upstream COPYING |
| Inter Regular          | 4.1                           | OFL 1.1                               |
| Noto Serif Regular     | 2.014                         | OFL 1.1                               |
| JetBrains Mono Regular | 2.304                         | OFL 1.1                               |

All fonts are **static Regular TrueType outlines**, verified to contain `glyf` and no `fvar`. Assets and full notices live in `core-engine/src/main/assets/fonts/` and `licenses/`. FreeType is used under the FreeType License (FTL), not its alternative GPL. Portions of this software are copyright © 2025 The FreeType Project (www.freetype.org). All rights reserved.

HarfBuzz compiles the upstream amalgamated `src/harfbuzz.cc` as one translation unit. Trimming disables AAT, bitmap/color/drawing, math/meta, variable fonts, vertical layout, CFF and environment configuration. OpenType Latin shaping, kerning and ligatures remain. `HB_NO_DRAW` already implies no paint. Vertical layout is outside this proof; disabling it also removes an upstream compile dependency on the disabled variation implementation. Vendored sources are unmodified.

#### Native ownership and shaping

The native `FontManager` owns copied font bytes, FreeType library/faces and HarfBuzz blob/face/font handles. Handles die before their backing bytes. JNI sessions lease a shared manager; in-flight calls retain ownership when another session closes. SDF/layout results are immutable leases and can outlive the manager. No GPU lifetime is introduced here.

Face operations and caches are protected by one mutex. Lock-wait time is measured. Worker-thread prewarming is required for later rendering; future E3 measurement must run off the main thread/debounce typing, since export can contend for this mutex. Font assets are available through each caller's AssetManager, including future thumbnail workers.

Shaping uses OpenType font functions, exact units-per-em scale, LTR/Latin/en and UTF-8 byte clusters. LF creates explicit lines; offsets in the flattened result retain global byte positions. Empty input has zero glyphs/lines; explicit blank lines survive. Missing font IDs resolve to `fluxx.sans`, without modifying the source document; missing codepoints retain glyph ID 0. Malformed UTF-8, NUL/CR and inputs over 16,384 UTF-8 bytes are rejected. These proof bounds do not establish the future editor's string limit.

Native output contains glyph IDs, advances, offsets, line advances and font ascent/descent/line gap in design units. A 64-entry layout cache avoids repeated shaping. The diagnostic JNI JSON API is **not** the future per-frame renderer submission API. E2 will keep layout handles keyed by layer/content revision and provide the shared geometry metrics interface.

#### Independent shaping parity

Checked-in reference: `core-engine/src/androidTest/assets/text/shaping-reference.json`. It was generated by the official **hb-shape 14.3.1 Windows release executable**, independently of Fluxx's trimmed library. The file records tool/version/hash, font hashes, exact arguments and raw per-line output. Regeneration requires an explicit `--update-reference` argument; comparisons never rewrite expectations.

All six CPU host cases match glyph IDs, clusters, design-unit advances and X/Y offsets exactly:

- Noto Serif `office`: four glyphs, including the `ffi` ligature.
- Inter `AV`: kerning.
- Noto Serif `office\nAV gy`: explicit newline and global clusters.
- Inter `A` + U+10FFFF + `V`: missing-glyph preservation.
- Inter combining-mark fixture: nontrivial offsets.
- JetBrains Mono `AV office`: third bundled face.

Additional native self-tests passed for fallback, empty/blank lines, missing glyph, malformed UTF-8, pinned-cache eviction/lifetime and concurrent shaping/rasterization. Android `TextCpuProofTest` compares the same checked-in references through JNI and generates CPU diagnostics. Its sources compile; **instrumented tests have not been run on a device**.

#### SDF policy, cache and capacity

FreeType unhinted outline SDFs use **48ppem, 8px spread, unsigned R8**. Production proof rasterization enables FreeType's SDF `overlaps` property. CPU samples reconstruct coverage at 8px (diagnostic), 12/14/16px and 72px. This is CPU quality evidence, not GPU scale or Quarter-preview acceptance.

The CPU cache retains immutable SDF bitmaps, with an 8 MiB unpinned LRU budget and bounded metadata. Active atlas/layout preparation leases pin their bitmaps; pinned allocations are not evicted to pretend a hard cap. Repacking consumes these CPU pixels directly. Two full repacks performed **zero additional rasterizations**. The fixture run retained approximately 5.04 MB of SDF cache data, including approximately 4.96 MB pinned for the capacity measurement.

The deterministic packer sorts by glyph height/width/font/ID and uses best-fit shelves plus one guard texel. The measured combined corpus is unique glyph IDs reached by U+0020–024F, U+1E00–1EFF and U+2000–206F in all three faces, including glyph 0. It contains 2,233 records, 47 with empty outlines. Counts below are nonempty glyph rectangles, not universal theoretical capacities:

| Corpus                   | 1024 × 1024 |        2048 × 2048 |
|--------------------------|------------:|-------------------:|
| All three faces combined |     **338** |          **1,547** |
| Inter alone              |         372 | 841 (all nonempty) |
| Noto Serif alone         |         346 | 868 (all nonempty) |
| JetBrains Mono alone     |         429 | 477 (all nonempty) |

The combined 1024 atlas rejected 1,848 nonempty rectangles; 2048 rejected 639. A lazy 2048 R8 atlas costs 4 MiB of texels versus 1 MiB for 1024 and is a sensible **E1d option**, but still cannot hold this entire combined corpus. E1d must pack the active working set, preserve leases/UV generations and define overflow handling. No GPU allocation has been made in this pass.

#### PNGs and artifact observations

Local output: `tools/text/results/sdf/` (**137 PNGs**) and `report.json`. This generated directory is ignored by Git and reproducible with the commands below.

- `fixture-atlas-1024.png` and `latin-atlas-1024.png` / `latin-atlas-2048.png`.
- `glyphs/`: individual raw SDF glyphs with overlap handling on/off.
- `samples/`: kerning, ligature, newline, missing-glyph, contour and monospace samples at each sample size.
- `synthetic-overlap-off.png` / `synthetic-overlap-on.png`: deliberately intersecting rectangles.

Actual synthetic-overlap result: property-off produced false internal distance edges (interior minimum **8**); overlap-aware output removed them (interior minimum **248**, center **255**; 752 changed texels). The full-interior probe passes with overlap handling enabled. FreeType's property setter takes `FT_Bool`, but its getter writes `FT_Int`; the wrapper respects that asymmetry.

Visual inspection of the overlap-aware 12/14/16px and 72px font samples found no obvious holes or contour corruption. Small SDF text has expected softness; 8px remains diagnostic, not a claim of acceptable editor minimum size. Inter's missing glyph intentionally appears as its font-specific boxed **NO GLYPH** symbol, rather than an unlabelled tofu box. The report records no rasterization errors. Phone rendering quality still needs later verification.

#### Build checks and focused fixes

Host MSVC CPU proof compiled successfully. Android r28c **debug and release native compilation passed for arm64-v8a and x86_64**, together with debug core-engine Kotlin, instrumented-test Kotlin and app Kotlin. No APK was packaged, installed or launched. All 1,425 retained source/font/license checksums passed. Build logs: `tools/text/results/host-build-1.log`, `android-debug.log`, `android-release.log`.

The corrected ELF audit passed all six shared objects in each build variant (engine, graphics-path and NDK C++ runtime across both ABIs): `android-debug-elf.json` and `android-release-elf.json`. This checks compiled ELF LOAD alignment/congruence and text relocations, **not APK ZIP packaging or runtime behavior**. Native symbol inspection confirms the arm64 debug dump JNI symbol exists in debug and is absent from release. All 11 host verifier/golden-tool regression tests pass; these synthetic tool tests are not the user's actual golden-project comparison.

| Observed issue                                                                 | Focused resolution                                                                       |
|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| HarfBuzz variation-disabled build referenced `gvar` from vertical-origin code  | Disable vertical layout, which is outside this Latin horizontal proof; no upstream patch |
| Explicit `HB_NO_PAINT` duplicated the implication from `HB_NO_DRAW`            | Remove redundant definition                                                              |
| MSVC exception-unwind diagnostic                                               | Enable `/EHsc` for Fluxx text sources                                                    |
| Initial vendor flag filtering damaged NDK `-Werror=format-security`            | Match only standalone `-Werror`; preserve the NDK security flag                          |
| Failed header probes from that configure cached `unistd.h`/`fcntl.h` as absent | Rerun only those CMake probes after fixing flags; both found; no source workaround       |
| SDF property readback wrote through an undersized boolean                      | Use the actual `FT_Int` getter contract; host probe now validates readback               |

Fluxx-owned native text keeps `-Wall -Wextra -Werror` (MSVC `/W4 /WX`). Third-party font targets retain upstream warning policy rather than inheriting Fluxx's standalone global `-Werror`; warnings are not globally disabled and NDK security diagnostics are retained. No genuine new Clang warning was suppressed. Logs are in `tools/text/results/`.

The existing E1a verifier falsely rejected r28 binaries (including NDK libc++) because it required raw RELRO endpoints to be 16 KB aligned. [Bionic rounds those endpoints](https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/linker/linker_phdr.cpp) in `_phdr_table_set_gnu_relro_prot`; that unsupported rejection was removed with a regression test. LOAD alignment/congruence and TEXTREL checks remain. The E1a checkpoint explicitly withdraws its older RELRO-only incompatibility conclusions. No compiler/linker security option was weakened to make the check pass.

#### Reproduction (CPU only; no device install)

```powershell
py -3 tools/text/verify_inputs.py
py -3 tools/text/build_host.py
py -3 tools/text/shaping_parity.py --proof tools/text/results/host-build/Release/text-proof.exe
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts dump tools/text/results/sdf
```

The host script requires installed Visual Studio C++ Build Tools and SDK CMake 3.22.1. Optional compile-only Android checks: `py -3 tools/text/build_android.py` and `py -3 tools/text/build_android.py --release`. These never assemble/install APKs. Device diagnostics are explicitly user-run via `TextCpuProofTest`; output is `<test target external files>/text-e1bc/`, not the host directory.

#### Remaining gates

- E1b/c: user review of CPU PNGs and Android instrumented parity/dump results.
- Before **E1d**: user's E1a 16 KB environment result and fixed r26/r28 golden-project video/audio comparison. If only an emulator runs, physical 16 KB AHardwareBuffer/MediaCodec behavior remains unverified.
- E1d/E1e: implemented in source and compiled; GPU quality, validation and heap/resource lifecycle acceptance remain user-run.
- E2/E3: v9, geometry, renderer integration, text creation and typography UI are implemented in source and compiled; device acceptance remains user-run.

#### F2 implementation status (2026-10-04)

F1 is user-accepted; its performance harness, pre-encode parity and lifecycle checks were deferred at this checkpoint and have since run on the A16 (TEXT FIX 9, TEXT PERF 10/11). F2a and F2b are implemented in source, device-unverified. F3 and UI improvements remain deferred. No PreviewController API or gesture-protocol changes, no new animator UI and no agent device runs.

V11 adds default-on hold-keyed Ligatures: effective on only when layer tracking evaluates to zero. Animator tracking does not split ligatures. Both source/font/mode layouts, shared membership maps and seeded permutations for every source alternative are prepared ahead of playback. V10 loading is retained permanently. Per-animator grouping, averaged cluster influence, lookup-table easing, signed spatial influence with clamped colour influence, round SDF joins and bounded SDF softness are documented deviations/semantics. Paint order stays per glyph; no additional paint scope or two-pass draw.

Detailed stage evidence and the exact user-run list are maintained in [the existing text animation plan](#rec-text-animation-plan). No animator UI is added; data/edit actions exist for later F3 diamonds/controls.

F2b integrates native dual-mode HarfBuzz preparation, extended cached layout metadata, reusable Kotlin selector/spacing/pivot/modifier evaluation and per-glyph Vulkan attributes. Layer tracking alone selects the precomputed ligature mode; animator tracking remains cluster spacing. Source/font/mode are the only shaping-key inputs. Native preparation reserves CPU/GPU mesh storage and pins all source/mode handles; no shaping, SDF generation, atlas uploads or permutation construction occurs in prepared evaluation. Bounds use the same modifier evaluation, including baseline shift in post-spacing pivots. The exact production acceptance source is `TextF2AcceptanceTest`; compile/host checks are not device acceptance.

---

<a id="rec-text-f1"></a>

## F1: Text property completion

*Formerly `docs/checkpoints/TEXT_F1_IMPLEMENTATION.md`.*

F1 is approved with the user's eight amendments. Implemented source is subject to the compile/JVM results below and user-run acceptance. E1–E3 text behavior was confirmed on device by the user; this does not establish F1 device, thermal, golden or 16 KB acceptance.

### Properties and persistence

Tracking (thousandths of an em), additive leading (composition pixels), baseline shift (positive upward), stroke colour, requested stroke width and stroke/fill order use the existing static-plus-optional-track framework. Paint order stores only `0` (fill over stroke) or `1` (stroke over fill) and samples with hold evaluation, regardless of the scalar track's easing metadata.

The fixed Text, Spacing and Stroke inspector pages expose diamonds, animation freeze/easing menus where applicable, colour editing and gesture transactions. Continuous scalar edits use the existing text edit controller. New fields participate in key navigation, timeline diamonds, undo, copy/paste, duplicate, signed rebasing, split and persistence.

F1 writes **v10**. Fields append to `TextPayload`; existing fields/ordinals are unchanged. Absent fields use identity defaults, including v9 documents. The additive loader must permanently retain v10 support when F2 introduces v11. F2 is not blocked on finalizing its reserved tables now; its `text_animators` vector remains empty in F1.

### SDF budget and centred stroke

Production remains **48 ppem / 8 texels spread**. The conservative usable outward distance budget is `0.10 × evaluated font size` in composition pixels.

```text
effectiveStrokeWidth = min(requestedStrokeWidth, 0.20 × fontSize)
effectiveSoftness = min(requestedSoftness, max(0, 0.10 × fontSize − effectiveStrokeWidth / 2))
```

Requested width is persisted without clamping. Evaluation recomputes the limit as font size animates. The Stroke page shows requested and effective values when limited. At sizes 8, 72 and 500, full-width caps are 1.6, 14.4 and 100 pixels respectively. F2 blur must use the same shared distance budget, retain its requested value and expose clamping. Its softness is a blur-like SDF edge effect, not Gaussian blur.

Let `d` be normalized sampled distance, `t = 128/255`, `h = halfStroke × 48/(16 × fontSize)` and `b` be the AA half-band plus normalized softness. Then:

```text
fillCoverage = smoothstep(t − b, t + b, d)
outerCoverage = smoothstep(t − h − b, t − h + b, d)
innerCoverage = smoothstep(t + h − b, t + h + b, d)
strokeCoverage = max(0, outerCoverage − innerCoverage)   [zero if h = 0]
```

The stroke is centred: half inside and half outside the outline. With `F = fillAlpha × fillCoverage`, `S = strokeAlpha × strokeCoverage`, straight colours `Cf` and `Cs`, the premultiplied compositions are:

```text
Fill over stroke: C = Cf × F + Cs × S × (1 − F)
Stroke over fill: C = Cs × S + Cf × F × (1 − S)
Both orders:     A = F + S × (1 − F)
```

Return straight RGB `C/A` (zero when `A=0`) to the existing SRC_ALPHA blend pipeline. Apply layer opacity once to the resulting alpha, after composing fill and stroke. A translucent fill therefore reveals the inside stroke in fill-over-stroke order; stroke-over-fill places the full band above the fill.

Round joins only: miter/bevel joins are a documented deviation from AE. Existing glyph quads include the full SDF spread padding; they are not cropped back to outline rectangles. The half-stroke plus AA band remains inside this padding. Ink bounds expand by effective half-stroke plus `fontSize/48` AA padding. Baseline shift changes ink relative to the logical anchor, so recentering the logical box does not cancel the visual shift.

### Shared layout membership and ordering

HarfBuzz remains LTR/Latin. RTL and complex scripts are out of scope. Latin combining marks remain covered.

Preparation caches source-character/grapheme byte boundaries (combining marks share their base character), cluster byte boundaries, glyph-to-cluster membership, source-character spans inside ligatures, line cluster counts, non-space character ordinals, and whitespace-delimited word membership with the immutable layout. Newlines advance line membership; they do not create drawable characters. F2 selectors must consume this map rather than constructing another one and define how partial source-character selection projects onto an indivisible ligature cluster.

Tracking is applied between clusters at every value. Ligatures remain intact, including through zero crossings; this intentionally differs from AE/InDesign behavior that can disable ligatures for tracking. Spacing does not change the source/font shaping-cache key. Line advances include tracking before alignment; leading shifts line baselines; baseline shift moves ink.

F2's order remains: evaluate tracks → compute and combine selector coverage → resolve modifiers → accumulate selected tracking/line-anchor offsets → compute advances/alignment → apply glyph affine/paint/SDF properties → emit mesh/bounds. Randomized permutations use a persisted deterministic seed and fixed algorithm shared by preview/export/thumbnails.

### Per-frame work and instrumentation

Prepared glyph references eliminate string-key atlas lookups in mesh building. CPU mesh capacity and GPU vertex/index buffers are provisioned before animated evaluation. Unchanged handle sets reuse native text preparation through a scalar JNI entry point, avoiding frame-time JNI handle-array copies. Four visible-quad vertices and six indices remain in the single indexed mesh per layer.

Track sampling and mesh building perform O(glyphs + lines) work, without shaping or rasterizing. Prepared text-only compositions share a reusable production path across preview, export and thumbnails; all Source Text keys are prepared before playback. Mixed media compositing retains its existing scheduling path; the allocation fixture measures the complete prepared text-only worker render, not the entire application or decoder/UI threads.

Debug builds replace native `operator new` variants with thread-local counters bound within libfluxxengine. This counts C++ new calls, including aligned/array/nothrow forms, not malloc or Vulkan driver internals. The harness probes the counter with an intentional allocation. Kotlin worker allocation deltas use `Debug.getThreadAllocCount()` (Kotlin `threadAllocCount`) after `startAllocCounting`; an intentional Java array must increase it. Unsupported/inert ART counters fail explicitly and must not be reported as zero-allocation proof.

The fixture asserts 500+ shaped glyphs, warms 120 frames, then records 600 full-resolution frames. It reports mesh-build and total frame p50/p95/p99/max, both allocation deltas, shape/rasterization/atlas-upload deltas and atlas generation. Both allocation deltas must be zero. It repeats after approximately three minutes of continuous render load. A16 measurements remain user-run; no numbers are invented from host timings.

### Spread spike

The host executable uses the production FreeType SDF path with manager-local experimental settings; production defaults do not change. It compares the same three-font Latin corpus and generates CPU-reference masks at 8/72/500px for A/V/g/y, half-strokes 0/.05/.10 em and requested softness 0/.03 em. Softness shares the same half-stroke budget. These masks do not establish Vulkan/device visual quality.

| Settings            | Requested glyphs | Bitmap area (texels) | Visible glyphs on page 1 | Visible glyphs on page 2 | Pages required | Rejected with two pages |
|---------------------|-----------------:|---------------------:|-------------------------:|-------------------------:|---------------:|------------------------:|
| 48 ppem / 8 spread  |            2,233 |            4,951,214 |                    1,547 |                      639 |              2 |                       0 |
| 64 ppem / 12 spread |            2,233 |            9,498,414 |                      764 |                      901 |              3 |                     521 |

47 empty-outline glyphs do not consume placement area. Measured bitmap area increases 1.918×; atlas occupancy includes additional packing/guard overhead. Recommendation: keep 48/8, given the loss of two-page corpus coverage at 64/12. Any spread change requires separate user approval and new capacity/quality acceptance before F2.

Reproduce on the host:

```powershell
py -3 tools/text/build_host.py
& tools/text/results/host-build/Release/spread-spike.exe core-engine/src/main/assets/fonts tools/text/results/f1-spread
& tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest
```

The spike writes `capacity.csv`, `samples.csv` (coverage area and edge coverage), and named PNG masks under the ignored results directory. Host sample comparison and compile/JVM results are recorded below.

The 432 masks comprise 72 paired comparisons per font size. Mean absolute differences between 48/8 and 64/12 over the union of nonzero mask pixels are 0.976% at 8px, 0.553% at 72px and 0.600% at 500px (normalized to 255). Maximum per-sample mean differences are 2.516%, 1.578% and 1.727% respectively. These measure differences between settings, not error against vector ground truth. All sample canvases have zero edge coverage; this establishes no clipping at the reference canvas edge, not a device quad-clipping proof. Inspected 72px thick A and 500px softened serif g masks show similar outer contours; both settings retain visible contour kinks on the enlarged g. There is no demonstrated quality gain sufficient to justify the extra atlas page. A16 visual acceptance remains necessary.

```powershell
py -3 tools/text/summarize_spread.py tools/text/results/f1-spread
```

### Exact user-run acceptance list

1. Build debug app and instrumentation targets in Android Studio. No device installation/run was performed by the agent.
2. Run `TextF1Test` and the existing JVM suite: requested/effective width, shared softness budget, scalar/colour/hold tracks, v9 defaults/v10 round trip and malformed values, key navigation, move/freeze/reset, gesture undo/redo, duplicate/clipboard/split and prepared cluster-spacing bounds.
3. On A16, open Text → Spacing → Stroke. Edit every property statically, enable its diamond, set two keys, scrub, navigate previous/next keys, freeze, undo/redo. Each continuous scalar/colour edit should create one undo entry. Test cancel, colour picker, eyedropper and IME return.
4. Use multiline Latin, spaces, NBSP, empty lines, Noto Serif `office` ligatures and `A` plus U+0301 combining mark. Animate tracking through negative/zero/positive values; expect the intentional ligature pop at tracking zero under the final F2 rule (Ligatures on). The bundled Inter shapes `office` as separate glyphs, so use Noto Serif for the ligature check. Test additive leading and baseline shift under left/centre/right alignment. Exclude RTL/complex scripts.
5. At sizes 8/72/500, test requested stroke 0, half the size-dependent cap, the cap and an oversized request. Animate size 8→500 with requested width 20px: inspector must report 1.6px effective at size 8 while keeping the 20px request. Save/reopen and verify the request remains 20px.
6. Test both paint orders using translucent contrasting fill/stroke, thin and thick glyphs, rotated/flipped/scaled text, and Full/Half/Quarter preview. Explicitly test thick stroke plus tight/negative tracking in both orders: F1 composites per glyph, so a later glyph's stroke can cover an earlier glyph's fill. Inspect round joins, clipping, overlap order and selection/fit/anchor bounds. Include 300%+ scale and enlarged `g` contour kinks. Compare host 48/8 and 64/12 masks at 8/72/500; production spread changes require separate approval; the final F2 decision retains per-glyph composition without a two-pass mode.
7. Save/close/reopen a composition containing all six new animated properties. Repeat after duplicate, copy/paste, trim/extend and split; check key timing/navigation and undo.
8. Run `TextF1AcceptanceTest.equalResolutionPreviewExportAndThumbnailPixelsMatch` with unique `textRunLabel`. It compares equal-resolution pixels at 0/5/10 seconds before encoding. Separately export MP4 and review it; codec output is not required to equal compositor bytes.
9. Run `TextF1AcceptanceTest.fiveHundredGlyphsAllocateNothingColdAndAfterThreeMinutesLoad` on A16, with unique `textRunLabel`. The source is `"AV office gy 0123456789 \n".repeat(30)` in Noto Serif, exercising real ligatures and line spacing. Archive both JSON reports: 600 frames, worker/native allocation deltas zero, shape/rasterization/upload/generation unchanged, mesh/frame p50/p95/p99/max. The second window follows 180 seconds continuous load. Counter canaries must pass; unavailable counters are a failed proof, not a pass.
10. Run existing `TextGpuAcceptanceTest` / `TextGpuProofTest` lifecycle, mixed-order, repack, capacity recovery, quality and resource checks. Require Vulkan validation enabled with zero errors and teardown resource recovery.

F2 engine is implemented in source; F3 UI remains deferred. Per-character 3D, text on path, expressions and wiggly selectors remain deferred.

### Validation results

Passed compile-only checks for app/engine debug Kotlin and both instrumentation source sets; native debug and release compiled for arm64-v8a and x86_64. All 157 JVM tests passed (including seven new F1 tests), with zero failures, errors or skips. The host text self-test passed after adding shared character/cluster membership and ligature-span checks.

Local logs are under the ignored `tools/text/results/` directory: `f1-acceptance-final.log` (Kotlin/JVM), `f1-native-membership-final.log` (both native configurations/ABIs), `f1-final.log` (instrumentation compilation), and `f1-fixture-final.log` (final Noto Serif acceptance fixture compilation). Spread data is in `f1-spread/`; generated results are deliberately excluded from the source commit and can be reproduced with the commands above.

No APK was packaged or installed, and no device tests were run. A16 mesh/frame timings, allocation assertions, thermal repeat, pre-encode parity and visual acceptance remain user-run gates; successful host compilation does not establish them.

#### Current device evidence (final instruction supersedes earlier addenda)

The user accepts F1's visual/property checks. Performance harness, pre-encode parity and lifecycle tests remain unrun and deferred, as explicitly stated in the final F2 instruction. Earlier addenda claiming a successful harness run are superseded and are not accepted evidence; no archived JSON reports establish timings or allocation results for this pass. F2 proceeds by authorization, without those results.

The F2 Ligatures decision supersedes F1's intact-ligature assumption: default-on hold-keyed layer Ligatures is effective only at exactly zero layer tracking; nonzero tracking splits ligatures and zero crossings pop. Both layouts are prepared ahead of playback. Animator tracking never changes shaping.

---

<a id="rec-text-animation-plan"></a>

## Text Animation: F1–F3 Plan

*Formerly `docs/checkpoints/TEXT_ANIMATION_F1_F3_PLAN.md`.*

Status: F2a and F2b are implemented in source in two ordered commits, device-unverified. F1 is user-accepted; its performance harness, pre-encode parity and lifecycle checks were deferred at this checkpoint and have since run on the A16 (TEXT FIX 9, TEXT PERF 10/11). F2a and F2b are implemented in source, device-unverified. F3 and UI improvements remain deferred. No PreviewController API or gesture-protocol changes, no new animator UI and no agent device runs.

The approved amendments take precedence over the original plan: requested stroke width is stored unchanged and clamped per evaluated font size; stroke half-width and F2 softness share a 0.10em distance budget; the host spread spike compares 48/8 with 64/12 without changing production defaults; centred strokes compose in both orders with translucent fill, round joins and padded bounds; the final v11 Ligatures rule supersedes F1 cluster-preservation behavior; RTL/complex scripts remain excluded; F2 may introduce additive v11 while permanently retaining v10 loading; native new and Kotlin worker allocation canaries/deltas measure 600 frames and repeat after three minutes load, with p99; and cluster/character/word/line metadata is prepared once with the layout for F2 reuse.

The user confirmed E1–E3 text behavior on device. Existing roadmap/SDD statements describing device acceptance as outstanding need reconciliation during F1, without claiming additional golden, performance or 16 KB evidence.

F1 ships v10. F2a writes additive v11 and permanently retains the v10 loader. F2a has no rendering integration: its animator data/evaluator are exercised through JVM fixtures until F2b; no animator UI is added.

### F1 — Text property completion

#### 1. Extend the existing property model

Add these properties to `TextProperties`, each with a static value and optional track through the existing Universal Property/keyframe framework:

| Property          | Proposed representation and semantics                                                                           |
|-------------------|-----------------------------------------------------------------------------------------------------------------|
| Tracking          | Existing scalar track; thousandths of an em, default `0`                                                        |
| Leading           | Existing scalar track; additional baseline spacing in composition pixels, default `0` over font-derived spacing |
| Baseline shift    | Existing scalar track; composition pixels, positive moves text upward, default `0`                              |
| Stroke colour     | Existing colour track and its interpolation                                                                     |
| Stroke width      | Existing scalar track; nonnegative composition pixels, default `0`                                              |
| Stroke/fill order | Discrete enum with hold keys; default stroke behind fill                                                        |

Leading's additive definition preserves existing documents and avoids introducing an animated Auto sentinel. The inspector should display the resulting baseline spacing.

Stroke/fill order needs discrete evaluation through the existing property infrastructure; it must never interpolate between enum values. Do not introduce a second animation system.

#### 2. Complete property integration

Extend property IDs, text actions, colour-action targeting, key-time discovery, contextual navigation, diamonds, auto-keying, reset and animation toggling.

Carry all new tracks through copy/paste, duplicate, trim/extend/split, signed key rebasing, undo/redo, save/load and autosave. Continuous edits retain the existing gesture transaction boundary and produce one undo entry.

Keep `PreviewController` and the gesture protocol unchanged.

#### 3. Apply spacing in mesh construction

Preserve cached HarfBuzz layouts and glyph SDFs. Tracking, leading and baseline shift must not enter the shaping-cache key.

Prepare cluster boundaries and line metadata once per source/font layout. Apply tracking between eligible character clusters, preserving combining marks; ligatures split when the final effective mode is off; spaces retain their advance. Reset accumulation at each line and avoid trailing tracking.

Recompute adjusted line advances before alignment, so centre/right alignment uses the new spacing. Apply leading to line baselines and baseline shift to glyph positions.

Update shared text bounds alongside mesh geometry so preview selection, pivots and hit testing remain consistent.

#### 4. Extend the SDF shader

Derive stroke coverage by offsetting the existing distance threshold. Composite fill and stroke coverage in the selected order, with correct alpha handling, within the existing indexed layer mesh.

Prepare shader inputs for SDF softness, which F2 can use for per-glyph blur. Softness is a bounded blur-like effect, not a full Gaussian blur.

The current SDF uses an eight-texel spread at 48 ppem. Store requested stroke width unchanged, clamp its evaluated full width to 0.20 × evaluated font size, and show clamping in the inspector. Half-stroke plus future F2 softness shares a 0.10em budget. Run the approved 48/8 versus 64/12 host spread/capacity spike before finalization; production spread changes require approval. See the implementation checkpoint for exact centred-stroke compositing and padding.

#### 5. Prepare and measure the hot path

The current mesh builder appends to vectors and performs glyph-placement lookups using string-bearing keys. Replace frame-time lookup with prepared glyph references and reserve CPU/GPU capacity before animation begins.

For a prepared layout, F1 costs:

- Track evaluation once per layer.
- Linear spacing, alignment and bounds work over glyphs/lines.
- Four vertices and six indices per visible glyph.
- Mesh upload and the existing text draw.

Target zero allocations, shaping calls, rasterizations and atlas uploads during warmed property-only animation. Source/font changes remain preparation events; animated Source Text requires preparation of its keyed layouts before measured playback.

### F2 — Detailed animator engine plan

Status: F2a is model/codec/pure engine/edit actions, followed by F2b rendering after its commit. F1 performance results are no longer a gate. Per-glyph painting remains; no additional paint scope or two-pass path. PreviewController API and gesture protocol remain unchanged.

The formulas below define Fluxx behavior precisely; they do not claim numerical equivalence with AE's undocumented implementation. Adobe documents the selector controls but places Min/Max Amount under Wiggly selectors. Applying those controls to Fluxx range selectors is an explicit extension. Reference: [Adobe text animation](https://helpx.adobe.com/uk/after-effects/desktop/animating-text/text-animation/animating-text.html).

#### 1. Selector math and membership

Use the F1 cached source-character, cluster, non-space, word and line map. Character means the existing Latin base-plus-combining-marks unit, not a UTF-8 byte or independently selectable combining mark.

| Based on                    | Members                                                                     |
|-----------------------------|-----------------------------------------------------------------------------|
| Characters                  | Source-character units, including spaces; excluding newlines                |
| Characters excluding spaces | Non-whitespace source-character units                                       |
| Words                       | Existing whitespace-delimited words; separator spaces receive zero coverage |
| Lines                       | All logical lines, including empty lines and a trailing empty line          |

Empty lines count for timing and percentage conversion but have no drawable glyphs. This pass does not introduce Unicode word segmentation or general grapheme segmentation.

For N members, member i has selection coordinate x = i + 0.5. Convert values to index coordinates:

```text
Percentage: a = N × (start + offset)/100
            b = N × (end   + offset)/100
Index:      a = start + offset
            b = end   + offset
```

Index coordinates are zero-based boundaries: [0,1) selects the first member. Fractional values are allowed. Offset translates the range without wrapping. Do not clamp stored start/end/offset to the membership domain.

N=0 or b<=a produces zero coverage. Reversed ranges are empty, not silently swapped. Non-square shapes have support [a,b); outside it coverage is zero. Let u=(x-a)/(b-a) inside the range:

| Shape     | Profile f(u)                       |
|-----------|------------------------------------|
| Square    | 1                                  |
| Ramp up   | u                                  |
| Ramp down | 1-u                                |
| Triangle  | 1-abs(2u-1)                        |
| Round     | sqrt(max(0, 1-(2u-1)²))            |
| Smooth    | H(1-abs(2u-1)), where H(t)=3t²-2t³ |

Triangle/Round/Smooth reach one at the midpoint and zero at their endpoints. Ramp Up starts at zero and approaches one before the excluded end; Ramp Down starts at one.

##### Square smoothness

Store s in [0,100]. At zero use the hard half-open interval. Otherwise set w=s/200 in member units:

```text
L = H(clamp((x-a+w)/(2w), 0, 1))
R = H(clamp((b+w-x)/(2w), 0, 1))
envelope = L × R
```

Each boundary feathers by up to half a member on either side. Narrow ranges have overlapping feathers and reduced peak coverage. Smoothness affects Square only; other shapes retain its stored value without applying it.

##### Ease Low/High

Both range from -100 to 100. Apply them to profile values, or to Square's envelope:

```text
p = 2^(easeLow/100)
q = 2^(easeHigh/100)
E(t) = t^p / (t^p + (1-t)^q)
E(0)=0; E(1)=1
```

Positive Ease Low softens departure from zero; positive Ease High softens arrival at one. Zero/zero is identity.

##### Amount and Min/Max Amount

Defaults are 100, 0, 100 respectively. All three are keyframeable percentages bounded to [-100,100]. Validate finiteness/range only. Evaluate lo=min(minAmount,maxAmount) and hi=max(minAmount,maxAmount), including crossings with differing key times/easings.

For non-square shapes, e=1 inside support and zero outside; v=E(f(u)). For Square, e=E(envelope) and v=1.

```text
selectorWeight = clamp((amount/100) × e ×
                 (lo/100 + (hi-lo)/100 × v), -1, 1)
```

Amount zero is exact identity. Min Amount does not affect members outside support.

Combine selectors in persisted order. For accumulator c and new weight v:

| Mode       | Result          |
|------------|-----------------|
| Add        | clamp(c+v,-1,1) |
| Subtract   | clamp(c-v,-1,1) |
| Intersect  | c×v             |
| Min        | min(c,v)        |
| Max        | max(c,v)        |
| Difference | abs(c-v)        |

Initialize c to zero for Add/Subtract/Max/Difference and one for Intersect/Min, based on the first selector's mode. Clamp after every combination. An animator with no selectors affects nothing; creating an animator adds a full-range Add selector.

First-selector Subtract intentionally yields negative weights. Spatial properties extrapolate; colour clamps influence to [0,1] and does not extrapolate.

##### Ligatures

Final rule: layer Ligatures is a default-on hold boolean persisted in v11; v10 defaults on. Effective mode is Ligatures on AND evaluated layer tracking exactly zero. Nonzero layer tracking splits ligatures; animated zero crossings pop by design. Prepare both source/font/ligature-mode layouts and all maps before playback. The cache key includes only those three inputs. Animator tracking and line anchor never affect shaping (an explicit assumption).

Evaluate and combine selectors on source members first, then project onto clusters. A cluster spanning multiple source characters receives their arithmetic mean. Selecting one character of a three-character ligature gives one-third influence without breaking the ligature. All glyphs belonging to the cluster receive the same influence; combining marks never separate from their base.

#### 2. Deterministic randomized order

Persist an unsigned 64-bit seed per selector. Zero is valid with no special meaning. New selectors receive a seed once; duplicate and copy/paste preserve it.

Use SplitMix64 with unsigned wraparound arithmetic:

```text
state += 0x9E3779B97F4A7C15
z = state
z = (z xor (z >> 30)) × 0xBF58476D1CE4E5B9
z = (z xor (z >> 27)) × 0x94D049BB133111EB
output = z xor (z >> 31)
```

Generate a Fisher–Yates permutation from N-1 down to 1. For bound B=i+1, reject unsigned outputs below (-B) mod B, then use output mod B as the swap index. This avoids modulo bias.

Cache the inverse permutation mapping original member to selection rank. Randomization changes selection rank, not glyph drawing order.

Prepare all four Based On maps and a permutation per variant per seed before playback, including Source Text alternatives. Hold-keyed Based On/Units selects prepared variants; units changes coordinates, not membership. Structural edits rebuild preparation. Start/end/offset, shape, amounts, size and time do not regenerate permutations; randomization off uses natural rank.

#### 3. Animator properties and pivots

Animator and selector vector order is significant. Each animator samples tracks once per frame and applies its resolved cluster weight c.

| Property           | Stored semantics                          | Application                                |
|--------------------|-------------------------------------------|--------------------------------------------|
| Position           | XY displacement, local composition pixels | Add c×position                             |
| Scale              | XY percentages; identity 100/100          | Multiply by 1+c×(scale/100−1)              |
| Rotation           | Degrees; identity 0                       | Rotate by c×rotation                       |
| Opacity            | Percentage; identity 100                  | Multiply by clamp(1+c×(opacity/100−1),0,1) |
| Skew               | Degrees; identity 0                       | Shear by weighted angle                    |
| Skew axis          | Degrees                                   | Orient the shear axis                      |
| Anchor point       | XY anchor displacement                    | Move by -c×anchor before affine transform  |
| Fill/stroke colour | Target ARGB                               | Blend current colour toward target         |
| Stroke width       | Signed pixel delta                        | Add to requested width before clamping     |
| Tracking amount    | Signed thousandths-of-em delta            | Add at cluster boundaries                  |
| Line anchor        | Tracking expansion anchor, 0–100%         | Shift line by -anchor/100 × tracking sum   |
| Blur               | Signed pixel delta to requested softness  | Add before shared SDF clamp                |

Colour uses existing linear-light premultiplied interpolation with weight clamp(c,0,1). Negative influence does not extrapolate colour; document this difference from signed spatial properties.

Keep requested stroke/softness through all groups; apply nonnegative and SDF clamps once after accumulation. Transform groups sequentially:

```text
M = T(position×c) × T(pivot) × R(rotation×c)
    × R(axis) × ShearX(skew×c) × R(−axis)
    × Scale(weightedScale) × T(−(pivot + c×anchor))
```

Use existing downward-positive local coordinates; F1 baseline shift remains positive upward.

Anchor grouping choices: Characters (indivisible clusters), Words, Lines, All. Compute centres from post-spacing logical advance bounds, excluding SDF padding, stroke expansion and previous animator transforms. Baseline shift participates in these post-spacing pivot coordinates. Word groups exclude separator spaces; separators fall back to their cluster pivot. Pivots remain fixed while animator matrices compose, avoiding order-dependent pivot drift.

Grouping is per animator here, per layer in AE: different animator pivots are intentional. Anchor offset moves glyphs by -c×anchor even with identity rotation/skew/scale. A JVM fixture verifies anchor-only animation is not a no-op.

Skew axis orients the shear rather than being an independent transformation. Clamp weighted skew to ±85° to avoid singular tan() results; retain and expose the request.

Tracking applies after the selected cluster, excluding the last trailing boundary. Line Anchor specifies which point stays fixed during tracking expansion:

```text
boundaryDelta = fontSize × selectedTracking/1000
lineShift = -(lineAnchor/100) × sum(selected tracking deltas on that line)
```

0% keeps the left edge fixed, 50% the centre, 100% the right. This is bounded and non-recursive; no reference line width is needed. Resolve each animator's selected tracking deltas and their sum before applying its line shift. Line Anchor is sampled once per animator; selection is already included in the tracking deltas.

All numeric fields use existing scalar/vector/colour tracks. Discrete controls use existing scalar tracks with validated integer values and hold evaluation.

#### 4. Blur and shared SDF budget

Keep production 48ppem/8px spread:

```text
requestedStroke = max(0, baseStroke + accumulatedStrokeDelta)
requestedBlur   = max(0, accumulatedBlurDelta)
effectiveStroke = min(requestedStroke, 0.20×fontSize)
effectiveBlur   = min(requestedBlur, max(0, 0.10×fontSize − effectiveStroke/2))
```

Use evaluated font size before per-character affine scale. Glyph scale scales stroke/softness visually; it does not rasterize or recover extra SDF detail.

Retain F1 AA and expand effective ink bounds accordingly. Inspector contracts expose requested/effective values, reason and effective range across affected glyphs; F3 provides full animator presentation. Blur is SDF edge softness, not Gaussian blur of the glyph interior.

#### 5. Stroke order and overlapping glyphs

Confirmed F1: one indexed draw, fill/stroke compositing per glyph in the fragment shader. It does not draw all layer strokes before all fills.

Add F1 acceptance: thick contrasting stroke, translucent fill and negative tracking in both paint orders; AV, Noto Serif office, rotated text and animated overlap.

Final decision: retain per-glyph composition in both orders, including overlap behavior. Do not add an additional paint scope or two-pass rendering. Per-glyph fill/stroke/softness/opacity must be attributes or indexed GPU data, not layer push constants.

#### 6. Evaluation, storage and performance

Preparation creates reusable member coverage, cluster weights, boundary deltas, group bounds, matrices and paint arrays. Prepare all Source Text alternatives and hold-keyed membership/grouping choices before playback.

Per frame:

1. Sample layer, animator and selector tracks.
2. Evaluate member coverage and combine modes.
3. Project influence onto clusters.
4. Resolve tracking/line-anchor deltas.
5. Accumulate positions, calculate advances/alignment.
6. Calculate post-spacing group pivots.
7. Apply ordered affine/paint modifiers.
8. Clamp stroke/softness, emit indexed mesh/bounds.
9. Draw through the shared compositor.

Glyph work is O(glyphs × total selectors + glyphs × total properties), plus linear member/line and animator passes; source-character projection is bounded by the 4096 UTF-16-unit source limit. Track lookup is additional and performed once per track using existing searches, not per glyph.

No allocation, shaping, rasterization, atlas upload or permutation generation in prepared evaluation. Structural edits/preparation may allocate. PreviewController API and gesture protocol remain unchanged.

Implemented v11 limits: 16 animators per text layer, eight selectors per animator, one override per property per animator. These bound storage and evaluation; they are validated source limits, not evidence of A16 real-time performance.

Fixtures:

- Representative: 500+ shaped glyphs, multiline Noto Serif, eight animators × four selectors, all property families.
- Maximum: 4,096 shaped glyphs × 16 animators × eight selectors, mixed modes/randomization. Use non-ligating ASCII to achieve 4,096 glyphs within the 4,096 UTF-16-unit source limit.

Warm 120 frames, measure 600, repeat after 180 seconds continuous load. Archive selector/modifier/mesh/total-worker p50/p95/p99/max. Assert zero native-new and Kotlin-worker allocation deltas with counter canaries; unchanged shaping/rasterization/upload counts and atlas generation.

Report representative and maximum 60fps results separately, frames exceeding 16.67ms, cold/hot differences and thermal state. Revisit limits or optimization if supported workloads fail. No measured F2 numbers exist yet.

Ease zero/zero uses an exact identity fast path. Otherwise use a prepared lookup table interpolating coverage and both ease inputs, including animated fractional ease, without per-frame pow/table rebuilding. Document/test approximation error. Skip disabled groups/selectors and cache static coverage until inputs/membership change. Propose lower limits if the 4,096-glyph maximum cannot meet 60fps; do not silently change them.

#### 7. Additive v11 schema and validation

Preserve all field ordinals and permanently retain the v10 loader.

| Table                | Appended fields                                                                                                                                                                              |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TextAnimator         | id:ulong, name:string, enabled:AnimatableProperty1D, selectors:[TextRangeSelector], anchor_grouping:AnimatableProperty1D                                                                     |
| TextRangeSelector    | id:ulong; enabled, start, end, offset, units, based_on, shape, smoothness, ease_high, ease_low, mode, amount, min_amount, max_amount, randomize_order (all AnimatableProperty1D); seed:ulong |
| TextPayload          | ligatures:AnimatableProperty1D (default-on, validated boolean, hold evaluation)                                                                                                              |
| TextPropertyOverride | scalar:AnimatableProperty1D, vector:AnimatableProperty2D, colour:AnimatableColour                                                                                                            |

Existing property_id identifies one typed payload:

```text
1 position       2 scale          3 rotation
4 opacity        5 skew           6 skew axis
7 anchor         8 fill colour    9 stroke colour
10 stroke width  11 tracking      12 line anchor
13 blur
```

Populated F2 tables use table version 2. Retain singular range_selector without repurposing its ordinal; v11 writers use the selector vector and leave the singular field absent. Reject ambiguous populated representations.

Validation:

- Supported project/table versions only.
- Nonzero unique animator/selector IDs within their owning layer; bounded names without NUL.
- Enforce group/selector limits and unique property IDs.
- Exactly one correctly typed payload per property; reject unknown IDs/enums.
- All scalar/vector components finite, including inactive static values and keys.
- Boolean tracks exactly 0/1; enum tracks valid integers, hold-evaluated.
- Smoothness [0,100], ease [-100,100], amounts [-100,100], opacity/line anchor [0,100].
- Signed spatial/tracking/stroke-delta/blur-delta values finite; retain oversized SDF requests.
- Do not reject crossed Min/Max Amount tracks: validate finiteness/range and sort the evaluated pair. Differing key times/easings invalidate endpoint-only ordering proofs.
- Seeds accept the complete unsigned 64-bit range, without truncation or animation.
- Strictly increasing key times, no duplicate composition-frame keys, supported easing and existing signed-time/rebasing rules.
- Checked arithmetic/buffer sizing; reject preparation-capacity failures rather than partially loading.

#### 8. Editing and persistence

Address properties by (layerId, animatorId, selectorId?, propertyId), not list indices. Extend existing backend edit actions, auto-keying, diamond-state/key-time data, navigation, freeze/reset and timeline aggregation. `aggregateKeyTimes()` supplies the complete anchor-relative union; bulk `MoveKeyframe` moves animator keys atomically with layer keys. F3 exposes animator-specific controls and their diamonds; no new UI controls or timeline gesture protocol are introduced here. Group/selector creation, removal and reordering each form one undo transaction.

Copy/paste and duplicate preserve seeds, values and order, assigning new object IDs. Trim/extend retain established keyframe origins. Split/clipboard rebasing shifts all numeric/discrete tracks with checked signed arithmetic; seeds/permutations do not change with time rebasing.

Test empty/disabled groups, reordering and inactive tracks. F2 may use a debug fixture builder for acceptance; full Add Animator picker and preview handles remain F3.

#### 9. Parity and unsupported features

Preview/export/thumbnails share prepared memberships, permutation algorithm, track samples, matrices, clamping and shaders. Compare equal-resolution RGBA before encoding at identical times/generations, including random access, reverse scrubbing, source changes and thermal windows. Proxy previews use identical composition-space parameters; unequal resolutions need not have identical bytes.

Wiggly selectors, per-character 3D, text on path and expressions remain unsupported. Do not ignore/flatten them. Reject documents requiring unsupported kinds with actionable messages; never save a degraded interpretation over the original.

#### 10. Risks, deviations and exact user-run list

Risks: maximum selector cost, ART allocation-counter availability, GPU property bandwidth, spacing/pivot interactions and per-glyph attribute bandwidth.

Deviations: specified selector/ease formulas, range-selector Min/Max extension, signed mode rules, empty reversed ranges, averaged ligature influence, Latin membership, round joins, bounded softness and colours without negative extrapolation. AE numerical compatibility requires separate comparison fixtures.

Run in priority order:

1. Deferred F1 performance: archive both A16 JSONs when run; report failures, without blocking authorized F2 source work.
2. F1 stroke: 8/72/500px, oversized requests, both paint orders, thick stroke/negative tracking, round joins and 300%+ scale; inspect enlarged g.
3. F1 remaining: Noto Serif tracking through zero, pre-encode parity, inspector/persistence/undo, E1 lifecycle/validation, then JVM suite.
4. F2 performance: representative/maximum fixtures; cold/hot 600-frame windows; canaries/deltas; p50/p95/p99/max; counters and missed frames.
5. Selector boundaries: zero/full/fractional, equal/reversed, out-of-domain offsets, percentage/index, empty/single-member text.
6. Shapes/modes: every shape, Square smoothness 0/50/100, ease -100/0/100, every mode first/subsequent, signed Amount and Min/Max.
7. Membership: spaces/NBSP/punctuation/combining marks/office ligatures, multiple/empty/trailing lines; partial ligature influence without reshaping.
8. Randomization: known seed, save/reopen, duplicate/clipboard, source changes, reverse scrub and independent thumbnail/export preparation.
9. Properties/pivots: each separately/together, all grouping choices, negative/zero scale, skew limits, animated tracking/line anchor, unchanged logical layer anchor.
10. Stroke/blur: requested/effective values during size animation, exhausted budget, negative deltas, per-glyph colours/widths and overlap. Retain the specified per-glyph order, including translucent overlap.
11. Editing: enable/key/navigate/move/freeze/reset; reorder; cancel; undo/redo; duplicate/clipboard; trim/extend/split; save/reopen.
12. Compatibility/parity: permanent v10 fixtures, v11 round trips, malformed/unsupported rejection, exact pre-encode pixels, GPU lifecycle/repack/resource checks and zero Vulkan validation errors.

F2a and F2b are authorized in order; per-glyph painting is final. F1 performance/parity/lifecycle remain deferred and unrun.

#### F2a implementation and verification

Commit `c059f85` implements additive v11 typed tables/model/codec/validation, permanent v10 loading, pure selector/ease/mode/projection/permutation evaluation and backend edit/key/reorder/undo/copy/split integration. App debug Kotlin and 174 JVM tests passed, including 17 animator tests; zero failures/errors/skips. Existing host selftest passed. Log: `tools/text/results/f2a-final-decisions.log`. F2a did not change renderer, mesh, shaders or JNI.

The ease table is 21×21×257 doubles (about 0.91 MB) with exact zero/zero identity and endpoint paths. Sampled regression error is below 0.017; this is an approximation regression, not a formal global bound or an AE equivalence guarantee.

#### F2b implementation and verification

F2b prepares both ligature modes and all source alternatives once, extends the native F1 layout metadata and emits reusable 96-byte direct cluster modifier records into 68-byte glyph vertices. The existing indexed mesh/draw order remains. All per-glyph paint values are attributes; only layer transform/order/opacity remain shared. Both layouts and maps remain pinned through playback. Coverage caches, four variant permutations and disabled execution use the F2a engine. All transform, colour, spacing, pivot, budget and shader logic is shared by preview/export/thumbnail. No PreviewController API or gesture protocol changed. Existing geometry consumers gained ligature-mode selection only; there are no new controls or flows.

Compile evidence: debug/release native arm64-v8a and x86_64, app/core Kotlin and both instrumentation source sets passed (`tools/text/results/f2b-native-instrumentation.log`). Final Kotlin/instrumentation/JVM results are in `tools/text/results/f2b-final-backend.log` and `f2b-final-tests.log`; native finite-geometry verification is in `f2b-complete.log`. Host Release build and selftest passed, including 600 cached dual-mode switches, combining marks, metadata layout and both 4096-glyph modes; all six independent shaping-reference cases passed. All 185 JVM tests passed, zero failures/errors/skips, including 18 animator tests, eight prepared-mesh/evaluation tests and the updated F1 spacing/bounds regression. Host prepared evaluation reported zero allocated bytes over its measured 600-frame window after 10,000 warm frames; this is a host JVM result only.

The evaluator has a host JVM allocation regression covering every property family, animated selector/ease and random membership, with 10,000 warm frames and a 600-frame zero-byte assertion. This does not replace ART and native device proof. Generic mixed-media scheduling retains its existing allocations outside the prepared animator evaluator. Device 60fps, pixel parity and native/ART allocation measurements remain unrun; no throughput claim or lowered limit is made.

#### Exact F2 user-run acceptance list (priority order)

Build the app and **androidTest** target in Android Studio; unitTest is the host JVM suite. Run instrumentation with a fresh `textRunLabel` for each invocation (for example `f2-a16-01`), using the app androidTest target. Reports are under the target app's external files `text/<label>/`. Archive the entire directory before rerunning.

1. `TextF2AcceptanceTest.representativeLimitsColdAndThermal`: Noto Serif source `"AV office gy 0123456789 \n".repeat(30)`, assert 500+ shaped glyphs, eight animators × four selectors, all 13 property IDs, animated ease/offsets and seeded permutations. Archive `f2-representative/pass-0.json` and `pass-1.json`.
2. `TextF2AcceptanceTest.maximumLimitsColdAndThermal`: `"A".repeat(4096)`, exactly 4096 glyphs, 16 animators × eight selectors, mixed shapes/modes/based-on and randomized order. Archive `f2-maximum/pass-0.json` and `pass-1.json`. These two fixtures each warm 120, measure 600, sustain 180 seconds load, warm again and measure 600. Canaries must work; native-new and Kotlin-worker deltas must be zero; shapes/rasterizations/uploads/atlas generation must stay unchanged. Reports include frame/selector/property-geometry/mesh p50/p95/p99/max, coverage builds, missed 16.667ms deadlines and Vulkan validation. p99 must meet 60fps; if not, return the JSON and propose revised limits without silently reducing the fixture. Both reports are written before assertions.
3. `TextF2AcceptanceTest.exportVisualPropertyFixtures`: inspect `f2-visual/*.png` at 0/5/10 seconds. Check anchor-only movement, all four grouping pivots, Line Anchor 0/50/100 with animated tracking, six randomized shapes, per-glyph colours/stroke width, skew ±85 clamp, stroke/blur shared budget and size 8/72/500. Inspect thick negative-tracking translucent overlap in both paint orders, round joins, clipping and enlarged g; later strokes may cover earlier fills by the retained per-glyph order. Compare Ligatures off and the intentional tracking-zero pop; combining marks stay clustered. Animator tracking changes spacing without splitting the ligated layout. No animator controls are exposed yet.
4. `TextF2AcceptanceTest.randomAndReversePreEncodePixelsMatch`: exact 1080×1920 preview/export/thumbnail pre-encode bytes at times 0,10s,5s,0,7.5s,5s,9s,2s, including held source alternatives, ligature mode switches, random selectors and reverse seeking. Archive `f2-parity/result.json`; encoded MP4 is a separate visual check.
5. Run the host JVM suite, particularly `TextAnimatorTest`, `PreparedTextAnimationTest`, `TextEditingTest` and `TextF1Test`: every mode/shape/unit/map, boundary and fractional cases, negative first Subtract, crossed Min/Max easings, SplitMix64 vectors/permutations, partial ligature projection, anchor-only/order/pivots, spacing/budget, fresh copy IDs with preserved seeds, reorder/undo, key operations/navigation/aggregation, freeze/reset and checked trim/extend/split rebasing.
6. Compatibility: load saved v10 projects (Ligatures defaults on), save/reopen v11 and repeat after duplicate/clipboard/split. Run codec malformed tests for versions, IDs, counts, duplicate properties, enum/boolean values, nonfinite inactive values/keys, key ordering and singular/vector ambiguity. Unsupported selector/property/table versions must reject with compatible-version guidance, never flatten.
7. Existing `TextGpuAcceptanceTest` and `TextGpuProofTest`: mixed text/raster order, repack/capacity recovery, suspend/resume/recreate, teardown/resource recovery and validation enabled with zero errors. These source fixtures compile; device outcomes remain pending.
8. Deferred F1 checks: run `TextF1AcceptanceTest.fiveHundredGlyphsAllocateNothingColdAndAfterThreeMinutesLoad` (archive both `f1-performance/pass-*.json`), `equalResolutionPreviewExportAndThumbnailPixelsMatch` and the existing lifecycle suite. The user accepted other F1 device behavior; these three checks remain unrun and deferred until the user chooses to run them.

No agent device run or device JSON report exists for this F2 pass. Limits remain 16×8 and 4096 source units pending measured device acceptance. Wiggly selectors, 3D glyphs, text paths and expressions remain unsupported.

### F3 — Animator UI outline

Use an Add Animator picker and fixed inspector pages for properties and selectors, with paging or drill-in where needed and no scrolling sheet.

Provide selector controls, property diamonds and range handles over the preview. Handle dragging edits the same selector tracks as numeric controls and follows existing gesture/undo transactions. Wrapped lines and overlapping handles need explicit interaction behavior; RTL remains out of scope.

F3 gets its own detailed design and approval after F2 acceptance.

### Schema impact

The reserved vector has been extended additively in [`project.fbs`](../../app/src/main/fbs/project.fbs). Version-2 animator/selector/override tables now carry all listed fields; v11 adds layer Ligatures. The singular selector slot remains reserved for compatibility and populated singular data is rejected. No paint-scope field was added.

- F1: write v10; append typed text fields, retain old field positions and supply identity defaults when reading v9 or older documents.
- F2: retain the reserved vector and extend its tables. F1 does not wait on F2 table design. F2 may bump additively to v11; permanently retain the v10 loader.
- Verify Kotlin/native codecs, malformed-value validation and unsupported-version behavior together.

### Principal risks

- SDF spread limits wide strokes and softness.
- Character-to-glyph mapping affects tracking and selectors in complex scripts.
- Animated spacing/stroke can expose bounds and pivot inconsistencies.
- Heap stability alone does not prove zero allocation; explicit hot-path instrumentation is needed.
- Encoded MP4 pixels cannot be expected to equal compositor pixels exactly. Require identical evaluated geometry/render output before encoding, then assess codec output separately.

### Exact user-run acceptance list

The adjusted executable/user-run list and completed host/JVM checks are recorded in the implementation checkpoint; device checks have not been run by the agent. The user builds in Android Studio and performs phone testing.

1. **Android Studio build:** build the debug app and relevant test targets.
2. **JVM regression suite:** existing tests plus static defaults, scalar/colour/hold evaluation, key navigation, animation toggles, reset, duplicate/copy/paste, signed rebasing and split behavior for every F1 property.
3. **Codec tests:** v9 migration; v10 static and animated round trips; missing fields; invalid enums; nonfinite scalars; negative stroke width; unordered/duplicate keys; unsupported versions.
4. **A16 inspector test:** edit each property statically; enable animation; create two keys; scrub; use previous/next key navigation; disable/reset animation; undo/redo. Confirm one undo entry per continuous edit.
5. **Spacing fixture:** multiline Latin, spaces, empty lines, `office` ligatures, Latin combining marks and NBSP (RTL/complex scripts excluded). Animate tracking, leading and baseline shift under left/centre/right alignment; verify bounds and unchanged shaping/rasterization counters after preparation.
6. **Stroke fixture:** width zero, intermediate width and supported maximum; both paint orders; translucent contrasting fill/stroke; font sizes 8, 72 and 500; Full/Half/Quarter preview; rotated/scaled text and overlapping glyphs.
7. **Persistence test:** save, close, reopen; verify static values, keys and order. Repeat after duplicate, copy/paste and split.
8. **Parity test:** capture shared renderer output at identical composition times for preview, export and thumbnail rendering. Compare equal-resolution pre-encode pixels exactly; review encoded MP4 separately.
9. **A16 performance harness:** use `("AV office gy 0123456789 \n".repeat(30))`, assert at least 500 shaped glyphs, and animate all continuous F1 properties plus hold-keyed paint order. Warm 120 frames, measure 600 frames at Full preview; report selector/evaluation time where applicable, mesh-build and total frame p50/p95/p99/max. Repeat after approximately three minutes of continuous load.
10. **Hot-path/resource test:** during the measured interval require zero debug native operator-new and Kotlin worker Debug.threadAllocCount deltas over the 600-frame window, with allocation canaries proving the counters work, unchanged shaping/rasterization/atlas-upload counters, no buffer growth and no Vulkan validation errors. Run the existing lifecycle/resource recovery checks.
11. **F2 source/device acceptance:** selector boundaries and every supported shape/unit/mode; randomized seek/reopen parity; overlapping animators; all property tracks; 500+ glyph performance with declared animator/selector counts.
12. **F3 gate later:** picker, selector pages, preview handles, cancellation, key navigation and undo/redo on compact phone layouts.

No A16 performance result is claimed yet. F2a/F2b are implemented in source with compile/JVM/host verification; F3 remains deferred.

### Deferred scope

Per-character 3D, text on path, expressions and wiggly selectors remain out of scope.

---

<a id="rec-text-completion-spec"></a>

## Text completion spec: T1–T5 (verbatim)

*Formerly `docs/architecture/TEXT_COMPLETION_SPEC.md`.*

Text completion: T1-T5 in one pass, five gated commits in order (T1 character formatting, T2 fonts, T3 paragraph/box text, T4 animator gaps, T5 text direction). F1/F2 are user-accepted; the user's F1 perf/parity/lifecycle device runs remain deferred and must not cause design compromises. No device runs by the agent: compile (native debug+release both ABIs, Kotlin debug/release, instrumentation sources), JVM tests and host tests only. Do not change the PreviewController API, the gesture protocol or the batch/draw contract; stop and report if a stage forces that. No F3 animator UI and no text UI overhaul: plain functional controls only, as Character and Paragraph pages in the existing Edit Text sheet, fixed layout, no scrolling sheet (use the existing paged-page pattern). Do not ask questions; make reasonable calls and list every deviation.

GLOBAL DECISIONS
1. Style runs. Source Text = string + style runs + paragraph styles + box width, hold-keyed as one document. Runs are sorted, contiguous, cover the whole string, boundaries only at source-character cluster boundaries (never inside surrogate pairs or base+combining marks). Max 256 runs. Run attributes (static, held with Source Text): font ref, size factor (multiplies layer-evaluated size), fill colour override (nullable), stroke colour override (nullable), stroke width delta (px), tracking delta (thousandths em), baseline shift delta (px), faux bold, faux italic, caps mode (none/all/small), script position (normal/super/sub), horizontal scale %, vertical scale %, kerning mode (metrics/off), manual kerning values (per source character, applied after it, thousandths em). Layer-level animatable properties (size, fill, stroke, tracking, leading, baseline shift, ligatures) stay the animatable base; run values combine with the evaluated base (multiply for size/scales, add for deltas, override for colours). Animator modifiers apply after that; F2 evaluation order is otherwise unchanged. Document the deviation from AE's absolute per-range size.
2. Schema v12, additive. v10 and v11 loaders retained permanently; older strings load as one default run. Validate: run ordering/coverage/boundaries, counts, enum ranges, finiteness, manual-kerning bounds, font-ref shape, box width >= 0, paragraph styles vs paragraph count, unknown font refs preserved. Aggregate source-document size cap.
3. Preparation. Prepare layouts for every reachable combination (source alternatives x effective ligature mode x box-width alternatives x direction) before playback, with a hard cap on prepared layouts per layer (state the value) and an explicit error when exceeded; never silently degrade. Prepared evaluation keeps zero allocation, shaping, rasterization, atlas upload.
4. Editing. Character/Paragraph edits apply to the current selection in the source field if non-empty, otherwise to all text. If Source Text is animated, an edit writes a hold key at the playhead (same as typing). One undo entry per edit/session. Inserted text inherits the run at the caret; deletions merge runs.
5. Shared text bounds, anchor, Fit/Stretch, overlays and hit testing use runs and the box. Empty-string rules unchanged.

T1 CHARACTER FORMATTING
- Bundle real Bold, Italic and Bold Italic static faces for Inter, Noto Serif and JetBrains Mono (OFL, checksums in TEXT_INPUTS.json, license files). Font ref = (family, style). Report APK size delta.
- Faux bold (SDF threshold bias equivalent to a uniform outline offset; define the amount, keep it outline-equivalent for future shapes-from-text) and faux italic (shear about the baseline, ~12 degrees), independent toggles; ink bounds expand accordingly.
- All caps: 1:1 simple uppercase codepoint mapping before shaping (Character.toUpperCase(int) semantics), display-only (selector membership counts original characters), fallback to original if glyph missing; document the German sharp-s case.
- Small caps: font smcp/c2sc if present, else synthesize lowercase as uppercase at ~70% scale with scaled advance.
- Superscript/subscript: font sups/subs if present, else synthesize with InDesign-style defaults (58.3% size, 33.3% offset up/down); flag these values as an assumption.
- Horizontal/vertical scale: per-run %; horizontal scales advances, vertical scales glyphs about the baseline without changing leading (state it).
- Kerning: per-run metrics on/off (HarfBuzz kern feature) plus manual per-character adjustments. Optical kerning unsupported (document).
- Shaping per run (font, features); cluster/character/word/line maps span runs; selector membership is logical across runs. Cache keys include run style signature.

T2 FONTS
- Import via SAF OpenDocument: TTF/OTF/TTC (per-face). Validate on a background thread: size cap, FT_New_Memory_Face open, sfnt, cmap present; reject bitmap/colour-only fonts (COLR/CBDT/sbix) with a clear message. Parsing of untrusted fonts must never crash the app; note fuzzing risk in docs. Ensure FreeType CFF support is built for OTF; re-enable only what shaping requires in HarfBuzz.
- App-private library: filesDir/fonts/<sha256>, AtomicFile JSON index (family, style, PostScript name, weight/width/slant, axes, named instances) following the PaletteRepository pattern. Library page: list, preview, delete (confirm). No project embedding, no online downloads.
- Project font ref = (sha256, face index, named instance or axis values) plus family/style/PostScript name for display; bundled fonts keep fluxx.* IDs. Missing font: fall back to the bundled sans, set a visible per-layer missing-font flag, keep the requested ref, never rewrite the document.
- Per-glyph fallback chain: if a glyph is missing, split the run by coverage and use the next bundled font.
- Variable fonts: enable HarfBuzz variations and FreeType variation support. Axis values are STATIC, stored as hold-evaluated tracks (so step interpolation can be added later without a schema bump). Named instances appear in the style list; axis sliders on the Character page; clamp to axis ranges; cap axis count. SDF cache keys and the HarfBuzz font both include variation coordinates so advances match. No animated axes this pass.

T3 PARAGRAPH / BOX TEXT
- Box width (0 = point text, default), held with Source Text. Overflow is not clipped.
- Line breaking: Kotlin android.icu BreakIterator (UAX14) supplies break opportunities; native greedy layout over styled runs; explicit newlines are paragraph breaks; trailing spaces hang.
- Paragraph styles (per paragraph; absent = layer alignment default): alignment left/center/right/justify-last-left/justify-last-center/justify-last-right/justify-all, indent left/right/first-line (px), space before/after (px; space before on the first paragraph ignored, documented). Justify = word-space expansion only; lines without spaces stay unjustified.
- Box width handle on the preview and numeric field on the Paragraph page, using existing gesture/undo protocol. Selector line membership uses wrapped visual lines; prepare variants per held box width.

T4 ANIMATOR GAPS
- Wiggly selector as a new selector kind: Max/Min amount, based on, wiggles/second, correlation, temporal phase, spatial phase, lock dimensions, seed, mode, all keyframeable; a PURE deterministic function of (seed, member, time) so random access matches exactly; define the noise (seeded lattice value noise via SplitMix64 with smooth interpolation) and formulas.
- New animator properties (assign IDs after 13): fill opacity, stroke opacity, fill hue/saturation/brightness, stroke hue/saturation/brightness, line spacing, grouping alignment (XY, offset from group centre as % of group size), character offset, character value.
- Character offset/value: effective codepoint = round(base + c x offset) or absolute value; standalone cmap lookup through the fallback chain, own advance, no shaping. Preparation computes the reachable codepoint set from the animated tracks' min/max and pre-rasterizes within a bounded cap (state it); over the cap reject with an actionable error, never drop glyphs. Invalid/control/surrogate results render the original character. Selector membership unchanged.
- Validation, limits (16 animators x 8 selectors retained), v12 fields, fixtures for all of it.

T5 TEXT DIRECTION
- Paragraph direction attribute (LTR/RTL, explicit, no auto). Kotlin android.icu Bidi + script itemization produce direction/script-tagged runs; native shapes each run (HarfBuzz direction/script/language), line-breaks, then reorders visually per line using embedding levels. Verify HarfBuzz trimming (HB_NO_*) still allows Arabic/Hebrew shaping and RTL mirroring; fix if not.
- Selectors/membership in logical order; glyph positions in visual order; alignment stays physical L/C/R. Tracking is not applied between joined Arabic letters (document; no kashida).
- Bundle Noto Sans Arabic and Noto Sans Hebrew Regular (OFL) and add them to the fallback chain. Arabic and Hebrew are the supported scope; Indic scripts best-effort and unpromised; vertical text, hyphenation unsupported.
- T5 is droppable: if it cannot reach this scope within the architecture, STOP after T4, report, and do not ship partial or degraded RTL.

RULES, DOCS, REPORTING
- Unsupported still: shapes/masks from text, text on path, per-character 3D, expression selector, optical kerning, animated variable axes. Reject documents requiring unsupported features with actionable messages; never flatten.
- Update TEXT_LAYER.md, PHASE_2_STATUS_AND_BACKEND.md, ELEMENT_INSPECTOR.md (Character/Paragraph pages), AE_PARITY_VISION.md (record deferred items; note 3D readiness: layer transform separate from glyph geometry, derivative-based AA, future 4x4 matrices and Z properties), the roadmap, and licenses for new fonts. Mark everything implemented in source, device-unverified; list F1 perf/parity/lifecycle as still unrun.
- Per stage return: compile/JVM/host results, deviations, assumptions, and the exact user-run test list (include rich-text, wrapping, import, variable-font and RTL parity/perf fixtures as source, with counters and canaries as before).

---

<a id="rec-text-fix-explode-t5"></a>

## FIX → EXPLODE → T5 continuation

*Formerly `docs/checkpoints/TEXT_FIX_EXPLODE_T5.md`.*

The user confirmed T1–T4 on a phone and supplied the case/script/interaction/performance findings for this pass. Historical “device-unverified” entries describe the evidence available at their original checkpoints. The fixes below have not been tested on a device. F1 performance/parity/lifecycle remain deferred and unrun.

### FIX source changes and gate

FIX gate passed: native Debug/Release on arm64-v8a and x86_64, Kotlin Debug/Release and both instrumentation source sets; all 237 JVM tests passed, zero failures/errors/skips. Host Release compilation/selftest, six independent shaping references and 1,445 input checksums passed. Logs: `tools/text/results/fix-final.log`, `fix-completion.log`, `fix-commit-check.log` and `host-build-{0,1}.log`; JVM XML reports are under `app/build/test-results/testDebugUnitTest`. No device run, app installation or APK packaging occurred. The source-only instrumentation fixtures remain device-unverified.

- Case uses one persisted enum. This checkout already stored `caps` as `NONE=0`, `ALL=1`, `SMALL=2`, rather than two Boolean flags. Those values remain unchanged; `LOWER=3` is additive to v12. The UI orders them Normal / ALL CAPS / lowercase / Small Caps. There is no second source of truth or lossy Boolean migration. The existing v10/v11 loaders remain permanent.
- Lowercase uses `Character.toLowerCase(int)` in preparation and never alters the source or selector membership. It is locale-independent and one-to-one: capital dotted I maps to `i`, capital sigma maps to ordinary sigma (no context-sensitive final sigma), and multi-codepoint/locale-specific case folding is not performed. A missing mapped glyph falls back to the original scalar before the ordinary per-glyph face fallback. Size/advance scaling is unchanged.
- Small Caps probes `smcp` substitution for each lowercase base in preparation. An unchanged/missing substitution uses the existing synthetic uppercase glyph at 70% size and advance; combining marks follow their base. Capitals, digits and punctuation remain full size. `c2sc` is not requested. Sharp-s keeps the one-to-one Java uppercase mapping and gets synthetic scaling if it has no native small-cap substitution.
- Super/subscript uses synthetic 58.3% glyph size and ±33.3% parent em baseline offset for every glyph and face, including imported fonts and the isolated process. Faux styles compose with that geometry. Neither `sups` nor `subs` is requested.
- Shaping keys exclude box width, paragraph alignment, indentation/paragraph spacing, paint, run tracking/baseline deltas and manual kerning. Font, variation identity, source/run boundaries, case/script, kerning feature, run size/scales and faux geometry remain shaping inputs. Wrapping, alignment, justification and spacing use the original held document and reusable prepared arithmetic.
- The effective ligature layout prepares first. Both layouts prepare before playback when tracking or Ligatures is animated; static documents reuse the effective layout for the unused slot and schedule low-priority alternate-mode preparation after foreground work. Imported prepared packets additionally have a 16-entry / 32 MiB memory LRU to avoid repeated IPC for the same exact shaping request. The 64-layout document cap remains conservative. No shaping, rasterization, atlas upload, permutation generation or allocation is added to prepared evaluation. Visual cluster geometry uses preparation-owned arrays.
- Preview selection is editor-only logical UTF-16 state shared with the source field. Long press selects a prepared cluster, double tap selects its logical word, and the two handles expand/shrink without document/undo changes. Highlight geometry and inverse-transform hits use the evaluated prepared bounds and visual line map. Handle input consumes only handle touches, and suppresses the box handle while a nonempty selection exists. Plain text-layer drags use the existing begin/preview/commit/cancel transaction. Source selection expands to grapheme/ligature clusters. Character edits use the range; paragraph edits use intersecting paragraphs. Done/tapping elsewhere clears it without opening the keyboard.
- New layers remain point text. A justification edit on a point document sets width in the same Source Text transaction to `composition.width / abs(evaluatedTransform.scaleX)`. Width is in unrotated layer units; rotation and position are ignored, and zero X scale produces an actionable error. The existing Paragraph page has Auto (0), Fill composition and numeric width editing.
- Draft work conflates to the display frame when a display clock exists; host controller fixtures retain their normal scheduling. Axis controls retain their page and slider. Font worker binding remains warm for 30 seconds after foreground use; native exact-identity managers use an LRU of at most four managers and 64 MiB of owned font bytes, plus each manager's existing 8 MiB unpinned SDF cache. Multi-face requests are batched.
- Imported SDFs have a disposable 64 MiB persistent LRU cache. The identity hashes font SHA-256, face, named instance and exact variation coordinates; per-glyph records additionally pin settings version 1 (48 ppem, spread 8, overlaps on). Each record has SHA-256 integrity checking; corrupt entries are deleted and cache rejection retries without seeds. At most 8 MiB of records seed an isolated request. Only native SDF cache misses rasterize. Full validated prepared packets still carry their SDF pixels; delta packet transport is not claimed.
- Idle warming starts after text metrics are first requested. It attempts printable ASCII (32–126) and Latin-1 printable scalars (160–255) for twelve bundled faces: at most 2,292 scalar requests, fewer unique covered glyphs. It uses a background-priority worker and cancels between glyphs on foreground work; the existing 8 MiB unpinned cache bounds retained pixels. Imported faces warm ASCII in batches of eight into the disk cache. Time cost is unmeasured on Android; debug logs record actual elapsed CPU wall time and requested pixel bytes. Warming never runs in a prepared frame.
- Debug logs are `filesDir/text-edit-timings.jsonl` and `.previous`, bounded to 4 MiB each. They record edit IDs, key construction, metrics/renderer preparation, isolated shaping/rasterization, IPC round trips, packet encoding/file transfer, first completed frame and native counters. A first-frame record requires the exact successfully prepared draft project, not an unrelated frame. Native counters in that record are cumulative; compute deltas between records. Atlas timing is CPU command recording/copy time, not GPU execution time. Existing surface presentation retains the last completed frame during preparation; no device responsiveness claim is made.

Protected APIs remain unchanged: `PreviewController`, editor gesture transaction protocol, four-entry batch/draw arguments, 68-byte vertex and 96-byte modifier records. APK asset delta for FIX is zero; code delta is unmeasured without packaging.

### Exact FIX user-run list

1. Build in Android Studio; run the source-only `TextFixAcceptanceTest`, existing rich-text/wrapping/import/variable-font fixtures, and existing pre-encode parity/counter canaries. Compilation alone is not acceptance.
2. On Character, exercise all four Case chips on `Hello World`, `Straße`, `İΣ`, `123!?`, `ffi`, and `a` + combining acute. Check full-size capitals/digits/punctuation in Small Caps, per-glyph native/synthetic fallback, all real faces, imported static/variable faces, faux bold/italic, selected ranges, undo/redo and save/reopen.
3. Exercise super/subscript on `Text`, `Hello`, digits and punctuation in every bundled family and an imported font, with faux styles; check that capitals and every remaining glyph move/scale uniformly. Check upper/lower offsets, parent-em scaling, selection bounds and pre-encode parity.
4. With the text layer selected, long-press a glyph, drag both selection handles inward/outward/across lines, and double-tap a word. Include `office`/`ffi` with ligatures on/off, surrogate pairs and base+combining marks. Check source-field two-way sync, Character formatting, intersected Paragraph edits, one undo per option, no undo for selection, no keyboard on preview selection, Done/elsewhere clearing, ordinary layer movement, box handle priority, rotation, negative scale, viewport zoom/pan and animated transforms.
5. Create fresh point text (width zero), select every justification variant and verify composition-fill width in the same undo step. Repeat at X scale 0.5, 2 and -2, with rotation; zero X scale must report the error. Exercise Auto, Fill and numeric width on the existing Paragraph page, held Source Text keys, paragraph selection, undo/redo and save/reopen.
6. Type rapidly, switch bundled/imported static and variable faces/styles, drag axes and box width, and cross tracking through zero during playback. Check last completed frame retention, final-value convergence and no ligature pop. Repeat cold, warm and thermally warmed with 500+ glyphs. Send both timing log files; assess performance only from measured edit-to-frame times and existing counters/canaries.
7. Import a face, let ASCII warming run, restart the app/worker, and repeat editing. Exercise missing/deleted fonts, corrupted disposable SDF cache, restored fonts, IPC parser-crash fixtures, cache-cap eviction and font/axis identity separation. Keep requested font identities through copy/paste and save/reopen.

### EXPLODE and T5 gates

EXPLODE's source gate passed: Kotlin Debug/Release, app instrumentation-source compilation and all 246 JVM tests, zero failures/errors/skips (`explode-complete.log`, `explode-final-jvm.log`). Core/native product sources are unchanged from the passing FIX native Debug/Release/both-ABI and core-instrumentation checks. Host Release compiled the additional boundary proof; that proof and the full host selftest passed. Existing six shaping references and 1,445 checksum results remain applicable to unchanged font/shaping inputs. No device run or APK packaging occurred. T5 follows EXPLODE and uses the user's final Unicode Joining_Type decision: a cursive-joining word is indivisible for emergency wrapping and may overflow; normal UAX14 opportunities remain unchanged. No partial RTL support or new Edit Text sub-screen is authorized by this checkpoint.

FIX is commit `85b9de4`; EXPLODE is commit `59a54a0`. The two pre-existing user edits in `TextF1AcceptanceTest.kt` and `TEXT_ANIMATION_F1_F3_PLAN.md` were excluded.

#### EXPLODE representation finding and concrete proposal

The host `explodeboundaryproof` command uses bundled Inter's actual non-ligating glyph advances and the shared paragraph layout arithmetic. The unchanged substring `A A A` occupies two visual lines in the wrapped source but one in point text. With justification, the first-to-last glyph origin distance is 88.8691 px, versus 38.8477 px in point text. Both invariants survive arbitrary common anchor translation, so an anchor alone cannot make the two layouts equal. Host compilation and this proof passed on 2026-10-09. This is a representation finding, not an assertion that a protected API must change.

The user explicitly rejected a frozen glyph-layout snapshot or new persisted layout data. The final decision retains original box width for Lines/Paragraphs/Style runs, and makes Words/Characters point text. Non-final justified lines map to justify-all; final lines retain their physical alignment. First-line indent is reset on a continuation-line piece; style-run pieces starting inside a wrapped line retain the available first-line width through the existing first-line indent field. The source substring, sliced formatting, original optional Ligatures setting (except Characters), and ordinary document persistence remain the representation.

#### EXPLODE implementation, assumptions and limits

The top-level Text page has an Explode Text row opening one compact dialog, not a sub-screen. Split modes are Characters, Words, Lines, Paragraphs and Style runs. Skip spaces defaults on; Keep original defaults off (retained originals are hidden); Group pivot defaults on. Prepare shows the frozen/dropped-property summary and resulting count before confirmation; more than 200 pieces requires a separate checkbox. Existing text drawing remains batched four at a time.

Preparation/evaluation of editor geometry runs on workers. Characters use the entire source with optional ligatures off and kerning retained, then split at base+mark/surrogate boundaries. Contextual character forms can change as explicitly accepted. Logical ranges determine naming and order; visual positions determine placement. Words use the existing logical whitespace-delimited word map. Empty input refuses; newline-only pieces are omitted even with Skip spaces off because a line separator has no glyph to place. Space pieces are supported when representable; zero-dimensional Group pivots refuse instead of clamping.

Source Text, size, tracking, leading, baseline and Ligatures freeze at the playhead, or the layer in-point when outside the layer. Animator translation can be captured through placement when representable; animator paint is dropped and named in the summary. Per-glyph rotation, scaling, replacement-glyph or other changes that cannot be represented while copying the layer's transform are refused. Layout fidelity is checked before any document mutation: shaped cluster ranges, evaluated origins and ink bounds must agree up to one common translation (0.002 px arithmetic tolerance). Partial justified style runs, emergency-wrapped words, changed contextual shaping in non-Characters modes, or differing multi-line font metrics may fail that check; the message suggests a larger Paragraphs split or Characters. This is an explicit limitation/deviation, not approximate placement. Exact pixel parity remains a separate strict source-only instrumentation requirement; bounds agreement alone is not a pixel proof.

Group pieces copy transform/opacity/non-layout tracks and signed keyframe origins verbatim, retaining the original timing/trim. The spatial anchor satisfies `pieceAnchor = originalAnchor - pieceTranslation`, expressed in piece bounds. Layer anchor validation extends additively from `0..1` to finite `[-1,000,000, +1,000,000]` normalized units; overflow/degenerate pivots reject and never clamp. Each piece pivot requires static spatial tracks (animated opacity is allowed). Creation replaces the original's z-position in logical reading order in one undo entry. Keep original inserts a hidden original at that position. Stale prepared plans reject; no pieces are created partially. The existing 1 MiB aggregate source budget still applies.

No font/assets are added: APK asset delta zero; code-size delta unmeasured without packaging. Device fixtures are source-only. Characters splitting already keeps ICU word spans containing Unicode Joining_Type dual/right/left/join-causing characters indivisible; transparent marks alone do not make a word cursive. This uses the [Android ICU Joining_Type constants](https://developer.android.com/reference/android/icu/lang/UCharacter.JoiningType). T5 rendering is not yet implemented, so this safeguard is not a claim of Arabic/Hebrew rendering support.

#### Exact EXPLODE user-run list

1. Run `TextExplodeAcceptanceTest`: strict pre-encode pixel equality at three transform times for all split modes, Characters `office` against whole-layer ligatures-off, base+combining marks, and wrapped/justified Lines/Paragraphs. Run the existing rich-text/import/variable-font and preview/export parity fixtures too; these have not been executed on a device by the agent.
2. On the top-level Edit Text page, check the compact dialog, all five modes, defaults, both pivots, hidden-original option, Cancel/Prepare/Confirm, more than 200 pieces confirmation, empty text, whitespace with Skip spaces on/off, explicit blank paragraphs and surrogate pairs. Confirm no new inspector page or keyboard is opened.
3. Exercise tracking, kerning, physical alignment, indents, justification, wrapping, mixed fonts/run scales, faux styles, super/subscript and baseline shifts. Verify exact placement at several rotation/negative-scale/position animation times; verify actionable refusal and no document change for unrepresentable partial style runs, emergency-wrapped words and animator shape changes.
4. Verify frozen layout properties at an active playhead and layer in-point outside its range; inspect dropped animators, preserved fill/stroke/opacity/transform tracks, signed keys, trim/start/duration, logical names and z-order. Each piece must refuse animated spatial tracks with the Group suggestion.
5. Verify one-step undo/redo, copy/paste, save/reopen, out-of-bounds Group anchors and Fit/Stretch/hit overlays. Repeat with an imported static/variable font and missing/restored fonts. Run 500+ glyph performance/counter canaries and inspect four-at-a-time batching; no Android performance claim is made.

### T5 dropped after EXPLODE — 2026-10-09

The user's final Joining_Type decision remains accepted: joining words are indivisible for emergency wrapping, while normal UAX14 opportunities remain unchanged. This resolves the historical emergency-break finding. T5 was investigated but is not implemented, and the existing RTL rendering rejection remains. The partial production prototype was removed; no Arabic/Hebrew production fonts were added.

A new independent constraint triggers the approved droppable-stage rule. The renderer shapes each style run separately, including paint-only boundaries. In pinned Noto Sans Arabic, whole lam-alef U+0644 U+0627 shapes to required glyph **704**. Shaping the two style items separately, with the entire source supplied as pre/post context, produces glyphs **668 and 47**. This happens with optional `liga`/`clig` both on and off. Keeping the word on one line cannot restore the mandatory cross-item glyph. Preserving that glyph across differently styled logical ranges requires cross-style shaping and an explicit glyph style-ownership rule (or a representation for partitioning its styling). The current cluster modifier owns one paint/affine set. No claim is made that a protected API necessarily must change; the unresolved design decision itself exceeds the single approved T5 deviation. T5 therefore stops after commit 2 rather than silently dropping styles or required joining.

Reproduce after `py -3 tools/text/build_host.py`:

```powershell
tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts cursivestyleproof tools/text/fixtures/NotoSansArabic-Regular.ttf
```

The host-only OFL fixture comes from [pinned official Noto sources](https://github.com/notofonts/noto-fonts/tree/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoSansArabic), commit `ffebf8c1ee449e544955a7e813c54f9b73848eac`. Font SHA-256: `ceea25b464a656dc3b26849bab9356740401af62aedf1bfa8b7f0d9b75925b1b`. Its OFL file and checksum are registered in `TEXT_INPUTS.json`; `fetch_cursive_style_fixture.py` reproduces the fixture without writing APK assets. Independent hb-shape 14.3.1 also returns glyph 704 for the whole word with `--unicodes=0644,0627 --direction=rtl --script=Arab --language=ar --font-funcs=ot --no-glyph-names --output-format=json --features=liga=0,clig=0`.

After restoring production sources to EXPLODE, host Release compilation, the style-boundary proof (both optional modes), full selftest, all six existing shaping parity references and **1,447** input checksum checks passed. The Arabic fixture is a diagnostic, not a completed Arabic/Hebrew parity suite. No T5 Android compile, device acceptance, performance, visual parity or supported RTL claim is made. FIX/EXPLODE compile and JVM evidence above remains applicable; production code is unchanged. Direction would remain inside held documents, so it would not add a new multiplicative dimension to the existing 64-layout cap, but a completed T5 preparation design is still outstanding.

APK production asset delta remains zero for both commits and this diagnostic; code-size delta is unmeasured without packaging. Host-only Arabic fixture size is 240,456 bytes, excluded from APK assets. No third feature commit was created; diagnostic source/fixture/manifest and current stop reports remain uncommitted. The two pre-existing user edits remain untouched.

#### T5 user-run status

There is no T5 acceptance list to run: it is dropped and Arabic/Hebrew rendering remains unsupported. A regression check may verify that an older RTL-attributed document retains its direction through save/reopen and still reports the existing actionable rendering error. Do not treat this as RTL rendering acceptance. The exact FIX and EXPLODE manual lists above remain required; their source-only instrumentation has not been executed on a device.

---

<a id="rec-text-variable-instance-fix"></a>

## Variable-font named-instance correction — 2026-10-09

*Formerly `docs/checkpoints/TEXT_VARIABLE_INSTANCE_FIX.md`.*

Implemented in source, device-unverified. The user reports that a named style did not render until an axis slider moved; static font style switching worked.

### Cause and correction

`FontAxisControls` kept a separate local `instanceIndex`. The previous/next style buttons changed only that local index and the displayed name. They never called `apply`; applying the displayed preset required an additional, undisclosed tap on its central label. This was selection-event wiring, not a missing renderer cache key: `FontFaceMetadata.reference` already resolves every preset axis, `StyledTextPreparation.shapingDocument` retains font references, and imported worker identities already include SHA/face/instance/axis bits.

Previous/next now select immediately through `FontFaceMetadata.selectInstance`, replacing all custom coordinates with the preset's coordinates in one existing Source Text formatting transaction. Default replaces every axis with its default. The displayed instance derives from the persisted reference, so undo/redo and restored documents cannot leave an unrelated local style label. Axis pending work is keyed/cancelled by the full reference, preventing a queued old-reference update from surviving a preset change. The central label still reapplies/reset-coordinates for the currently selected preset. No layout or UI consolidation is included.

Preparation remains asynchronous and uses the shared preview/export font identity and packet path. No native renderer, schema, PreviewController API, gesture protocol, batch/draw contract or prepared evaluation loop changes. New production font/asset bytes: zero; APK code delta unmeasured without packaging. Older stored references remain unchanged; this corrects new selections, not historical documents whose local-only choice was never stored.

### Verification

The JVM regression `instanceChoiceReplacesCustomAxesPreparesImmediatelyAndIsOnePersistedUndo` uses two axes and a non-positional instance index (7). It selects a preset from custom coordinates, checks old/new geometry preparation without a slider edit, verifies changed shaping identity and all coordinates, codec round-trip, one-step undo/redo, and default restoration. Existing tests also cover coordinate validation/clamping and persisted display style. Actual preview/export pixels remain user-run acceptance; a JVM identity/transaction fixture is not a pixel proof.

Final gate passed: app Kotlin Debug/Release and app instrumentation-source compilation, plus all 247 JVM tests (zero failures/errors/skips), in `tools/text/results/variable-instance-final.log`. The first run passed Debug/Release compilation but exposed a fixture expectation error: anchor compensation measures both old and new geometry, not just the selected geometry. The fixture now explicitly checks both identities; the final full JVM run passed. The existing host CFF variable-font proof passed outline/advance changes, coordinate clamping, independent FreeType/HarfBuzz advance agreement and prepared-packet round-trip. Native production sources are unchanged. No APK assembly, device install, device launch or device test is authorized or performed.

The full host selftest also passed (dual ligature modes, cached switching, cluster metadata, 4,096-glyph modes, fallback, prepared transport/cache/lifetime and concurrent preparation). Protected contracts and the allocation-free prepared evaluation path are unchanged.

### Exact user-run manual list

1. Import a variable font with multiple named instances and at least two axes. In the existing Variable Axes page, use previous/next to select each preset, without touching a slider or the central label. Verify the rendered text and all axis values update immediately; check Default and returning from custom coordinates.
2. Repeat with a selected range, no selection, faux styles, another variable font and a collection face. Static bundled/imported style switching must still work. Start an axis drag, choose a preset immediately afterward, and confirm the final preset coordinates win.
3. Verify one undo/redo per preset choice, saved/restored instance name and coordinates, copy/paste, held Source Text keys at/between frames, missing-font deletion/re-import and no unwanted change to other ranges/keys.
4. Compare preview and pre-encode/export frames at the same playhead after preset selection, including cold/warm imported caches and rapid switching. Run existing imported-font/variable-font acceptance and parity fixtures. Record timing logs for responsiveness; compilation is not device acceptance.

### Part B boundary

The [Edit Text overhaul](#rec-text-ui-plan) is a separate approval draft only. Its tab/grid/popover changes, numeric-entry redesign, icon inventory and Fonts popup are not implemented or included in this bug-fix commit.

---

<a id="rec-text-ui-proposal"></a>

## Proposed fourth work item: Text editing UI improvements

*Formerly `docs/checkpoints/TEXT_UI_IMPROVEMENTS_PROPOSAL.md`.*

Proposal for user review only. These improvements are not authorized or implemented as part of F1. F1 supplies fixed Text/Spacing/Stroke pages, property diamonds and a size-dependent stroke-limit message; F3 separately owns animator creation, selector controls and preview range handles.

1. **Precise numeric entry:** tap scalar values to enter exact size, tracking, leading, baseline shift and requested stroke width. Preserve units, negative values where valid, frame snapping, auto-keying and one undo entry. Numeric stroke entry must retain oversized requests and show the effective width.
2. **Clear property focus:** show which property owns previous/next-key navigation, whether animation is enabled and whether the playhead is on a key. Keep focus stable when opening a colour picker or returning from numeric entry.
3. **Comfortable keyframe controls:** give diamonds and overflow controls distinct accessible targets of at least 48dp where space permits. Label add/remove/enable actions for accessibility and avoid narrow adjacent hit regions.
4. **Consistent animation actions:** provide freeze at playhead, reset and interpolation options from the same property menu. Mark Source Text and paint order as Hold; do not offer meaningless easing controls for discrete values.
5. **Better colour context:** label Fill versus Stroke prominently in the existing picker and eyedropper mode. Preserve the selected property on return, and support cancellation without losing or creating keys.
6. **Compact limit feedback:** show requested/effective stroke values and the reason for clamping as font size changes. Use the same presentation for F2 softness once approved. Announce limit state changes accessibly without adding a modal dialog.
7. **Predictable keyboard and back behavior:** Done commits, Cancel restores the pre-edit value, and Back first leaves numeric/source editing or colour mode before leaving the text tools. Keep the preview visible above the keyboard and preserve the active page.
8. **Phone layout polish:** review fixed page height, typography, swatch contrast, touch targets and large-font settings on A16. Use fixed pages/drill-in instead of a scrolling sheet. Avoid crowding the preview with implementation details.

Acceptance: all eight workflows on A16, keyboard open/closed, portrait/landscape and large system text; verify key navigation, cancellation, persistence and exactly one undo entry per continuous edit. Do not change PreviewController API or the gesture protocol.

---

<a id="rec-text-ui-plan"></a>

## Edit Text consolidation — approved plan

*Formerly `docs/architecture/TEXT_UI_OVERHAUL_PLAN.md`.*

**TEXT UI 8 (2026-11-18):** device feedback on TEXT UI 7. Changes:

- The marker editor is one popover anchored to the marker: a description field plus the COMPACT picker. Edits apply live, with one undo per session. Tapping outside closes it and keeps the edits. There are no Add/Save/Delete/X buttons, and removal is unchanged (tap the playhead on the marker).
- Layer-bar keyframes sit on the bar's vertical centre, drawn above the name with a knock-out ring.
- Section-page controls have fixed, moderate sizes that don't depend on the sheet height. The control group is centred vertically, and the Edit Solid picker is no longer stretched.

See [TEXT_UI_OVERHAUL.md](#text-ui-8-marker-popover-centred-layer-keyframes-consistent-control-sizing). Device-unverified.

**TEXT UI 7 (2026-11-18):** TEXT UI 6 was accepted on the A16 (Edit Text layout unchanged). Changes:

- Section pages (Transform, Timing, Blending & Opacity, Edit Solid) have one header row (← | tabs) like Edit Text, and their controls are resized to use the freed height. The landing grid is unchanged.
- Keyframe badges (hollow / filled diamond) on animatable property tiles.
- Every keyed property, all text properties included, now shows on the timeline layer bar. Unselected properties are drawn quiet.
- One shared colour picker family (COMPACT popover / FULL page). The marker colour is an anchored popover.
- Hex, alpha and RGB values are edited in place (fixes the hex popup closing).
- Ruler labels fade near the needle.

See [TEXT_UI_OVERHAUL.md](#text-ui-7-single-row-section-headers-keyframe-badges-and-layer-markers-colour-picker-family-inline-hex-entry). Device-unverified.

**TEXT UI 6 (2026-11-18):** device feedback on TEXT UI 5. Changes:

- Shared heights are back to the original 268/328dp on every page; Edit Text fits with smaller tiles (top row, two 3×3 blocks, bottom bar).
- One rounded icon family (`InspectorIcons`) across the inspector and action bar.
- The utility rail is a compact top-aligned stack, and its ⋮ is the only Edit Text overflow menu.
- The colour picker lives inside the colour popover.
- The scale ruler is visible again and follows the finger.
- SDF atlas tiers (48/96/192/384 ppem) keep large text sharp.

See [TEXT_UI_OVERHAUL.md](#text-ui-6-fit-layout-icon-family-compact-rail-colour-popover-picker-ruler-fix-sdf-tiers). The TEXT UI 5 note below is historical.

**TEXT UI 5 (2026-11-18):** the pass-2 rework was rejected on the A16 and is superseded by a single-header inspector page with anchored slider popovers, a shared limits table, keyboard-overlay handling and a log-scale Transform ruler. Shared inspector heights rose to 308/344dp. The current surface, limits, ruler spec, deviations and A16 list are in [TEXT_UI_OVERHAUL.md](#text-ui-5-single-header-page-slider-popovers-limits-log-scale-ruler); §11 below is historical.

**Rework status (2026-11-18):** After the user's A16 review ("too cluttered and confusing"), the follow-up rework is implemented in source, device-unverified, in two commits (tab faces; menu/Back/status/icons). It supersedes the surface sketches in §1, the popover/launcher model in §1–§3, the status header in §2 and the mapping in §4. The current surface and the **final old-to-new inventory** are in [§11](#11-rework-2026-11-18-final-surface-and-inventory). Gate results, deviations and the exact A16 user-run list are in [TEXT_UI_OVERHAUL.md](#rework-commit-2-menu-back-status-and-icons).

Implementation status: all four approved stages are implemented in source; device acceptance remains pending. The historical sketches below are superseded by the approved amendments and the measured final budget, complete inventory, gate results and exact manual lists in [TEXT_UI_OVERHAUL.md](#rec-text-ui-overhaul). No F3 animator UI or RTL renderer is included.

Status: APPROVED for four ordered implementation commits, 2026-10-09. User amendments supersede the original sketches below: Character row 2 is Tracking/Leading/Case/Format (Baseline in Format); standard quick row is Stroke width/H scale/V scale/Baseline. Paragraph row 2 is Box width/Indents popover/Spacing popover/Direction. Independently scrolling virtualized Fonts family/axis lists are approved; accessibility-size popovers/choice lists may scroll, with searchable choice dialogs as needed. Wide-range scrubbing uses velocity acceleration; all tunables are centralized. Graph/3D remain visibly disabled. Parent chrome must be subtracted or removed, with source-budget/layout assertions at 360dp and font scales 1/1.3/2. See [implementation gates and inventory audit](#rec-text-ui-overhaul). T1–T4 were tested on device by the user; all new UI remains device-unverified. T5 remains dropped and this plan does not implement F3.

Part A is commit `4dfcf02`: the instance arrows now apply the complete preset instead of only changing a local label. App Debug/Release and instrumentation-source compilation, 247 JVM tests, the host variable-font proof and host selftest passed. [Cause, correction and exact user-run checks](#rec-text-variable-instance-fix). This approval draft is separate from that commit.

### 1. Proposed surface and measured space budget

Exactly three tabs: **Character / Paragraph / Animate**. Animate is an inert, accessible reservation for F3. It is selectable to show “Text animators — coming with F3”, but has no editing actions, diamonds, add button or empty animator list. Source, scope and the contextual rail remain stable when switching tabs. Existing library, axes, stroke, spacing and nested Character/Paragraph pages disappear only during a later approved implementation.

Treat 268dp expanded-preview / 328dp standard inspector height as the **entire** Edit Text surface, including header and rail; do not add the old Edit Text title/back row above this budget. Reuse its dismissal action in the source/overflow region. Confirm the parent sheet's padding/insets before implementation: any non-removable parent chrome must be subtracted, not hidden by overflow. Safe-area/IME insets are outside these content heights. At 360dp use 8dp side margins, leaving 344dp. Icons are 20–24dp monochrome vectors; density does not change hit target dimensions.

#### Portrait, expanded preview: 360 × 268dp, font scale 1

```text
y=  0.. 48  [ Source Text: one-line excerpt / Hold          ][ ⋮ ]
y= 48.. 80  Whole layer · Size · Animation off
             or Selection: 12 characters · Relative size · Source Text Hold
y= 80..120  [ Character       ][ Paragraph       ][ Animate ]
y=120..168  Character: [ Font family + style     ][ Size    ][ Paint ]
y=168..216             [ Tracking ][ Leading     ][ Baseline][ Format]
y=216..220  separator / breathing space (4)
y=220..268  [Prev][Diamond][Next][Actions][Graph][3D][Done*]
```

Four body columns are 86dp; the font chip spans two (172dp). Each cell is 48dp high. A numeric cell divides a compact 36dp icon scrub zone and a 50dp value target. The icon zone is an explicitly documented compact-toolbar exception to 48dp width; it has a full 48dp height, no overlapping expanded hit regions, and an accessible alternative to scrubbing. A tap on the icon focuses the property; a tap on its value opens exact entry. Category cells (Paint, Format, Font) open their popover/sheet and establish context. Seven rail slots are 48dp (336 total), with the remaining 8dp as gap; Done* clears a selection, otherwise dismisses Edit Text. All diamond, animation-actions and sheet-overflow targets are distinct 48dp squares. Tabs are 40dp high under the compact toolbar standard, ~114dp wide.

Paint opens a bounded popover: labelled Fill and Stroke swatches, Stroke width numeric cell, paint-order toggle, inline limit line and Inherit override actions. Format opens a 4-column, at most 4-row icon grid: H/V scale, faux bold/italic, Case launcher, super/subscript, ligatures, kerning launcher, manual kerning, relative-size/inherit tools. Case opens a single 4-choice icon row; Kerning opens Metrics/Off. These are short anchored popovers, not retained inspector pages. At most one is open; a child choice replaces its parent overlay temporarily and returns to it, without adding a tab or page history. All controls remain reachable at this tight height, although secondary controls are not simultaneously visible.

#### Portrait, standard preview: 360 × 328dp

Same 268dp skeleton plus **60dp between the primary grid and rail**: 48dp quick row and 12dp for nonmodal feedback. Character quick row has [Stroke width][H scale][V scale][Case]. Paint/Format retain these same controls as access paths, backed by the same property state; this is not a second value model. Feedback gives requested/effective stroke or the active property's constraint. No extra page, scrollbar or taller slider appears.

Paragraph uses the same source/scope/tabs/rail and two 48dp body rows:

```text
expanded body: [Left][Centre][Right][Justify choices ▾]    (4 × 86)
               [L indent][R indent][First indent][Paragraph settings ▾]
settings popover: [Before][After][Box width][Direction]
                 Box choices: Auto (0) / Fill composition / exact px
standard extra: [Before][After][Box width][Direction] + 12dp feedback
```

Justify popover contains four separate 48dp-wide choices: last-left, last-centre, last-right and all-lines. The three physical alignments plus these four make all seven alignments. Inherit layer alignment is an additional explicit menu action. A selected justify choice is shown in the launcher icon; selecting L/C/R clears that choice. Indents are physical. First-line indent follows the supported paragraph-start rule; the plan does not enable bidi. Paragraph settings remain accessible in expanded preview. The box handle stays on the preview and uses the existing gesture transactions.

Animate at both heights: same source bar, scope and tabs; its two body rows contain the short F3 placeholder and no controls. The rail reserves the same geometry but disabled graph/3D/animation slots announce why they are unavailable; Done remains active. Do not accidentally expose Character's focused animation actions while Animate is shown. Restore the previous Character/Paragraph focus on return.

#### Landscape and large-font fallback

At landscape widths ≥600dp, retain three tabs and the fixed height; use six body columns and expose stroke/H/V/Case directly in the space saved by the font chip. Keep the same ordering and property identities. At a 360dp-wide landscape window use the portrait grid. Preview remains above the keyboard where usable height permits; do not promise a useful preview when the OS IME consumes almost all short-window height.

Continue the existing height formula: 268/328dp × max(1, fontScale), **capped by available window height after safe insets and a minimum preview region**. At 1.3–2.0 font scale, replace the four-column primary grid with three columns: Character [Font (2)][Size], [Paint][Spacing launcher][Format]; Paragraph [Alignment launcher (2)][Box], [Indents launcher][Spacing launcher][Direction]. Each category popover offers labelled 48dp rows instead of tiny glyph-only choices, with two choices per row when necessary. Font/style names ellipsize visually but expose their full accessible text. Header can wrap to two lines within the scaled budget. No reduced text scale and no overlapping touch targets.

Hard limit: arbitrary accessibility font sizes, every font/axis, the IME, useful preview and every control cannot all fit a fixed short surface without some overflow strategy. Recommended fallback: keep the main inspector fixed and non-scrolling, move the focused property's **bounded editor overlay** above it, and virtualize/scroll only long font/axis lists. If even a labelled choice overlay cannot fit, use a searchable choice dialog with a bounded scrolling list. Alternative for approval: allow scrolling inside a property popover only. Neither alternative introduces inspector sub-pages. Do not silently scroll the whole Edit Text sheet, shrink text or remove functions. Test the actual insets/font metrics before accepting this budget.

### 2. Scope, focus and property transactions

- Header begins with exactly “Whole layer” or “Selection: N characters”. Count original logical grapheme clusters, not UTF-16 units, display-case expansions or glyph count; a selected ligature can count multiple source characters. Then show the focused property, units and animation status. Paragraph edits add “N intersected paragraphs”; selection remains logical/cluster safe and preview positions remain visual. Mixed values show an em dash and “Mixed”, not the first run's value as though uniform.
- Single icons change meaning with scope: Size = layer px without selection, relative run size (%) with selection; Tracking/Baseline/Stroke width = layer base or selected-run delta; Fill/Stroke = layer paint or selected override. Header and exact-entry title spell out Base / Relative / Delta / Override. Selection overrides get an Inherit action; inheritance is distinct from transparent paint or a zero delta. Do not flatten unselected run styles when adjusting a layer base.
- The current model has **no layer tracks** for H/V glyph scale, faux styles, Case, script position, kerning mode, font identity/axes, paragraph attributes or box width. With no selection these edit all applicable runs/paragraphs inside the held Source Text document. Mark them “Whole layer · [property] · Source Text Hold”, with a Source Text key shortcut, rather than inventing independent animation. With selection these edit only the range (paragraph attributes: intersected paragraphs). Leading, Ligatures and paint order have no run equivalents: keep them layer-only and disable them with a selection, with “Clear selection to edit layer leading/ligatures/paint order”. Box width is document-wide: same explicit layer-only exception. This is a necessary scope deviation from a literal all-controls-have-run-values rule; no schema extension is proposed.
- Preserve relative run size access even in Whole layer mode: Size's property popover has “Relative size of all runs” (Source Text Hold), plus Inherit/reset; its primary scrub remains the animated layer px size. Manual kerning without selection addresses all original character boundaries as today; with selection it addresses selected source boundaries only. Explain “after each selected character”, including accumulated spacing at a ligature's end, rather than implying pairwise optical kerning.
- Focus is a stable editor value `(layerId, tab, propertyId, scope, range, storageOwner)` and survives popovers, numeric entry and colour mode. Previous/next key navigation always belongs to the focused **storage owner**. Header states Animation off / Animated, between keys / On key at [timecode], or Source Text Hold. Merely opening/focusing never enables animation or creates keys. Changing selection changes scope, not the document; re-resolve mixed state and disable incompatible layer-only actions.
- Diamonds operate only in Whole layer scope. A continuous layer property owns its diamond. A document-backed property points to Source Text and explicitly labels that ownership. With selection, hide diamond/add/remove/previous/next mutation actions and show “Range edits use the current Source Text value”; editing still updates the current held Source Text key atomically when that track is animated. This is not independent range animation.
- Freeze target layer, range, snapped playhead, reference coordinates and baseline document on transaction start. Lock tab/layer/key navigation until commit/cancel completes; a queued navigation first commits the current valid draft. Apply only one completed transaction to undo; asynchronous prepare keeps the last completed frame. Stale worker replies never replace newer drafts. No changes to PreviewController, gesture protocol or native batch/draw contracts are expected; stop if integration requires one.

### 3. Scrub, exact entry and animation actions

Drag horizontally on an icon after pointer slop to scrub; tap focuses; long press without movement shows its label and current scope. A category icon taps open its choices. Numeric values tap into exact entry, never require a long press. Cancel the tooltip if scrubbing begins. Use a local accumulator anchored at touch-down, not rounded displayed values; cancel/lost pointer restores the captured draft. Axis/typing/width updates remain last-wins once per frame, and all font/shaping/raster/IPC work stays off the UI thread. No new per-frame preparation or allocation is proposed.

Suggested starting sensitivity (to tune with manual tests, not a measured usability claim): size/stroke/baseline/leading/indents/spacing/width 1px per 4dp; tracking/manual kerning 1/1000 em per 2dp; percentages 1% per 4dp; relative size 1% per 4dp. Hold a second finger for ×0.1 precision (also offer a visible Fine toggle in exact entry for one-handed/accessibility use). Axes use 1% of axis span per 4dp, with metadata bounds and a fine multiplier; use stable numeric precision rather than assuming all axes are weight. Detents: zero, inherited/default, 100%, named-instance coordinate and every 10px/100 tracking units; selection haptic at crossings only, rate-limited, respects system haptic preferences. No haptic every frame.

Exact entry has property/scope title, explicit unit suffix, signed decimal keyboard where valid, full-value input, step −/+ actions, live preview, Cancel and Done. Units: layer geometry px, tracking/manual kerning 1/1000 em, run relative size % (`100%=factor 1`), H/V %. Leading is **additional leading px**, not absolute line height. Baseline sign matches the existing renderer. Stroke width has nonnegative layer base but signed run delta; zero is valid. Keep existing model validation, reject nonfinite/out-of-range input inline and disable Done; do not replace model bounds with current slider ranges. Preserve requested stroke width and expose effective width separately. Values are not frame-snapped: the **captured playhead time** is snapped using the composition's rational frame rate. Animated edits auto-key there using existing signed keyframe origins and preserve the existing key's interpolation. Static edits remain static. Done creates one undo entry; Cancel restores both value and original key existence.

The rail is shared, not repeated beside every control: Previous key, Diamond, Next key, Animation actions, Graph, 3D, Done. Diamond labels change to “Enable [property] animation at playhead”, “Add [property] keyframe at playhead”, or “Remove [property] keyframe at playhead”. Graph uses existing support only; otherwise visibly disabled with reason, never a working-looking no-op. 3D remains an inert reserved control. Animation-actions menu is the same for every owner: Enable animation if off; Freeze at playhead if on; Reset value; Reset animation… with confirmation when deleting multiple keys; Interpolation for an eligible key. Reset value targets current scope/current key and keeps other keys; Reset animation has explicit scope and removes the track only after confirmation. Source Text, paint order and Ligatures display **Hold** and never offer easing. Colours and continuous scalars offer existing supported interpolation only; no new curves are promised.

Colour opens the existing Global Colour Picker with prominent “Fill — Whole layer”, “Stroke — Whole layer”, or “Fill override — Selection: N characters”. Restore the same focus/tab/range after it closes. Picker draft previews use one transaction; Done commits once; Cancel/Back restores paint and key existence. Merely opening it does not auto-key. Changing Fill/Stroke while a picker draft is open must first commit/cancel explicitly; never retarget an uncommitted colour draft. Palette/eyedropper capabilities stay available. Inherit selected paint clears the override; transparent swatch remains a real ARGB value.

Stroke/softness feedback is one inline line under Paint, or the standard 12dp feedback strip: “Requested 24px · Effective 6px · SDF spread / size limit”. Where selection metrics vary, report an effective range and reason, not an invented single number. Preserve the requested value. A small information popover can explain centred stroke, round joins and imported-font spread limits. Softness is not currently an independent Edit Text property: show its applicable renderer limit as feedback only, do not add a new control or persist a new value. Zero-scale composition fill, source/run/cached-layout limits and missing-font reasons use the same nonmodal status area; blocking invalid operations stay actionable.

### 4. Complete old-to-new mapping

| Existing control / capability                                           | New location and semantics                                                                                                                          |
|-------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| Text page Source field; Source Text Hold keys                           | Always-present one-line Source bar; typing expands it in IME mode; focus owner SOURCE_TEXT, Hold menu/diamond in rail                               |
| Layer font family dropdown; Character family/style; Inherit layer font  | Font chip → single Fonts sheet; scope-aware family/style and explicit Inherit for selected/all run references                                       |
| Static face style; variable named instance                              | Fonts sheet style list; named instance applies all coordinates atomically (Part A), no extra Apply required                                         |
| Library import, use face, delete confirmation, collection faces         | Fonts sheet unified list and toolbar; select face to apply; delete file lists affected faces and confirms                                           |
| Missing font indicator, requested font identity                         | Font chip warning badge and Fonts sheet banner; fallback labelled, never silently rewrite requested identity                                        |
| Variable Axes page/slider; axis defaults/custom values                  | Fonts sheet compact axis icon+scrub rows; exact entry/default action; bounded list if many; static axes stored in Source Text                       |
| Layer size slider; run size factor                                      | Character Size cell; Whole layer px vs Selection relative %; all-run relative size in Size popover                                                  |
| Layer Fill picker; run Fill ARGB override                               | Character Paint → Fill swatch → Global Colour Picker, scope labelled; Inherit override action                                                       |
| Layer Stroke colour; run Stroke ARGB override                           | Paint → Stroke swatch → same labelled picker; Inherit override action                                                                               |
| Stroke page width; run stroke delta                                     | Paint → Stroke width scrub/value; standard quick row; requested/effective inline feedback                                                           |
| Stroke/fill order                                                       | Paint popover, two icon states; layer-only Hold property, selection explanation                                                                     |
| Tracking slider; run tracking delta                                     | Character Tracking cell; base vs selected delta in 1/1000 em                                                                                        |
| Leading slider                                                          | Character Leading cell; additional px, layer-only                                                                                                   |
| Baseline slider; run baseline delta                                     | Character Baseline cell; base vs selected px delta                                                                                                  |
| Metrics/Off kerning mode                                                | Format → Kerning two-choice popover; held run formatting, no optical mode invented                                                                  |
| Manual kerning amount, Apply, Reset selected kerning                    | Format → Manual kerning numeric popover; applies after selected source characters; reset only applicable entries                                    |
| H scale; V scale                                                        | Format numeric cells; standard quick row; run % values, Source Text Hold, no layer-transform scale substitution                                     |
| Faux bold; faux italic                                                  | Format icon toggles; separate from real font face/instance style                                                                                    |
| Normal / ALL CAPS / lowercase / Small Caps                              | Format → Case four-choice icon row; standard Case shortcut; display-only semantics preserved                                                        |
| Normal / superscript / subscript                                        | Format Super/Sub mutually exclusive toggles; tap active choice returns Normal; synthetic renderer values unchanged                                  |
| Ligatures property                                                      | Format toggle, layer-only Hold; tracking/joining limits explained; current RTL guard unchanged                                                      |
| Run paint overrides and spacing deltas                                  | Same Paint/Tracking/Baseline/Stroke icons in selection scope; explicit Inherit/zero-reset, mixed-state handling                                     |
| Layer L/C/R; paragraph seven alignments and Inherit                     | Paragraph first row L/C/R plus four-choice Justify launcher; Inherit action; physical alignment, paragraph exceptions retained                      |
| Left/right/first-line indents                                           | Paragraph second row icon+px values; selected intersected paragraphs or all; standard fallback Indents popover                                      |
| Space before/after                                                      | Paragraph settings popover; standard extra row; selected intersected paragraphs or all                                                              |
| Box Auto / Fill composition / numeric and preview handle                | Paragraph Box width popover; standard shortcut; document-wide, held Source Text; existing preview handle stays; justify point→fill remains one undo |
| Paragraph direction attribute                                           | Paragraph settings Direction icons; LTR/RTL stored only; RTL choice warns inline “Stored; RTL rendering unsupported”, never implies support         |
| Explode Text row/dialog                                                 | Sheet overflow → Explode Text, existing compact dialog/options/fidelity refusals unchanged                                                          |
| Reset text style; inherit font/paint, reset kerning and other utilities | Sheet overflow reset scope submenu; property popover reset; resets distinguish current value from all animation                                     |
| Selection header; Done selection                                        | Shared header logical character count; rail Done clears transient range; preview/source two-way sync retained                                       |
| Per-property diamonds and overflow; key navigation                      | Single focus rail with explicit owner/status; selection hides independent diamonds; document properties point to Source Text in Whole layer scope   |
| Graph and 3D placeholders                                               | Focus rail, existing graph capability only; unavailable graph and 3D visibly disabled and labelled                                                  |
| Stroke/softness clamps and model limits                                 | Inline feedback strip / property popover, requested and effective plus reason; no modal just for clamping                                           |
| Typing Cancel/Done; colour Cancel/Done; navigation                      | Modal editor state rules below; each draft commits once or restores fully                                                                           |

Reset text style is proposed to reset current scope's run formatting (font inheritance, size factor 1, scales 100%, overrides inherited, deltas/manual kerning zero, faux off, Case Normal, script Normal, kerning Metrics). Paragraph reset is separate. Whole-layer base/reset-animation are separate explicit commands; do not delete source text, imported files, transforms, animators or unrelated keys under a vague “Reset”. Implementation must inventory any additional existing utility actions before removal and map them, rather than discard them.

### 5. Monochrome ImageVector inventory

Build reusable cached `ImageVector`s in Kotlin, using the project's monochrome strokes/fills and optical sizing. No bitmap assets or coloured category icons. Paint swatches may display the actual selected colour; their icon frame remains monochrome. Never use hue alone for selected/enabled/mixed states. Every icon has the semantic label below, a value/state description, and the same label on stationary long press. Translate labels; do not use the ASCII sketches as final glyph art.

| Vector                                           | Form / accessible label                                                                                                                             | Ambiguity and mitigation                                               |
|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| Font                                             | F / “Font family and style”                                                                                                                         | Distinguish from size with no measurement arrows                       |
| Size                                             | A with vertical measure / “Font size, px” or “Relative run size, percent”                                                                           | Header/title expose changing scope                                     |
| Leading                                          | Two baselines and vertical double arrow / “Additional leading, px”                                                                                  | Could mean line height; label always says Additional                   |
| Tracking                                         | AV with outward arrows / “Tracking, 1/1000 em”                                                                                                      | Easily confused with kerning; use different paired-glyph form below    |
| Kerning                                          | A/V pair with inward notch / “Kerning mode”                                                                                                         | Label Metrics/Off in popover, no optical implication                   |
| Manual kerning                                   | A                                                                                                                                                   | V with one gap arrow / “Manual spacing after selected characters”      | Not a true pair-kerning editor; label states actual behavior |
| Baseline                                         | A on line with up/down arrow / “Baseline shift, px” or “Baseline delta, px”                                                                         | Keep numeric sign visible                                              |
| H scale / V scale                                | A with horizontal / vertical stretch arrows / “Horizontal glyph scale, percent”, “Vertical glyph scale, percent”                                    | Different from layer transform scale                                   |
| Stroke width                                     | Outlined A with outward thickness ticks / “Stroke width, px” or “Stroke width delta, px”                                                            | Separate from stroke colour swatch                                     |
| Fill / Stroke                                    | Solid A / outlined A with swatch / “Fill colour”, “Stroke colour” plus scope                                                                        | Prominent words in picker/popover required                             |
| Paint order                                      | Overlapping solid/outline squares with front marker / “Stroke over fill” or “Fill over stroke”                                                      | Ambiguous alone; announce full current order                           |
| Faux bold / italic                               | Heavy B / slanted I / “Faux bold”, “Faux italic”                                                                                                    | Real style remains in Fonts; retain Faux in tooltip                    |
| Format launcher                                  | A with small settings dots / “Character formatting”                                                                                                 | Category icon needs long-press label                                   |
| Case launcher / Normal                           | Aa / “Case”, “Normal case”                                                                                                                          | Case launcher displays active variant plus disclosure marker           |
| ALL CAPS / lowercase / Small Caps                | AA / aa / large A small A / “All caps”, “Lowercase”, “Small caps”                                                                                   | Small Caps ambiguous; full label and selected outline in choice row    |
| Super / Sub                                      | x² / x₂ built as vector paths / “Superscript”, “Subscript”                                                                                          | Mutually exclusive, Normal is both off                                 |
| Ligatures                                        | Joined fi path / “Optional ligatures”                                                                                                               | Required joining not promised; RTL unsupported                         |
| Align left / centre / right                      | Ragged horizontal lines / “Align left”, “Align centre”, “Align right”                                                                               | Physical, never auto-mirror these icons                                |
| Justify last-left / centre / right / all         | Full upper lines + distinct last line / “Justify, last line left”, “Justify, last line centre”, “Justify, last line right”, “Justify all lines”     | Last-line distinction subtle; labelled popover and semantics essential |
| Indent left / right / first                      | Boundary guide + arrow at left/right/top line / “Left indent, px”, “Right indent, px”, “First-line indent, px”                                      | Physical guides do not mirror                                          |
| Space before / after                             | Paragraph block + top/bottom arrow / “Space before paragraph, px”, “Space after paragraph, px”                                                      | Distinguish from leading with paragraph marker                         |
| Box width                                        | Box with horizontal measure / “Text box width, px”                                                                                                  | Not glyph H scale; Auto/Fill labels mandatory                          |
| LTR / RTL                                        | Paragraph marker + directional arrow / “Paragraph direction left to right”, “Paragraph direction right to left, stored only, rendering unsupported” | Never imply bidi support merely by enabling storage                    |
| Spacing / Indents / Paragraph settings launchers | AV-spacing / guide / paragraph+dots / “Text spacing”, “Paragraph indents”, “Paragraph settings”                                                     | Large-font fallback only; label tooltip                                |
| Instance / Axis                                  | Face-style stack / slider ticks / “Font style or named instance”, “[axis name] ([tag]), [value]”                                                    | Arbitrary axes need text name/tag in Fonts sheet                       |
| Import / Delete / Missing                        | Tray+arrow / bin / warning outline / “Import font”, “Delete font file”, “Requested font missing; fallback active”                                   | Delete always confirms affected faces                                  |
| Inherit / Reset                                  | Linked chain / return arrow / “Inherit [property]”, “Reset [property] value”                                                                        | Inherit is not reset animation; label full action                      |
| Source / Animate tabs                            | Text lines / stacked diamond / “Character”, “Paragraph”, “Animate, controls not yet available”                                                      | Tab text remains visible, icons optional                               |
| Prev / Diamond / Next                            | Key-chevron / hollow or solid diamond / key-chevron / action-dependent labels                                                                       | State describes animation off/on/on-key; no reliance on fill alone     |
| Animation actions / Overflow                     | Clock+dots / vertical dots / “Animation actions for [owner]”, “Text utilities”                                                                      | Distinct locations/48dp targets                                        |
| Graph / 3D                                       | Curve axes / wire cube / “Graph for [owner]” or unavailable reason, “3D controls, unavailable”                                                      | Disabled semantics and no no-op buttons                                |
| Explode / Done / Cancel / Info                   | Separated text blocks / check / cross / info / “Explode Text”, “Done [mode]”, “Cancel [mode]”, “Explain effective limit”                            | Text labels on destructive/exact-entry confirmations                   |

### 6. Fonts sheet, utility placement and performance

One popup sheet from the font chip, with current scope at top. Unified bundled/imported searchable family list, selected family/face section, style/named-instance choices, and variable axes only when present. Collection faces retain their index. “Default instance”, named presets and “Custom coordinates” are visually distinct; retain preset identity after axis adjustment but display “Bold · modified” if coordinates no longer match it. Choosing any instance replaces **all** axis coordinates in one undo transaction and prepares immediately. Choosing Default restores all defaults. Axis default/reset and precise entry use the same metadata validation and frame conflation as scrubs. Mixed-font selection explicitly shows Mixed until a choice is made.

Recommended bounded popup: header/scope 48dp, search/import toolbar 48dp, family list viewport at least 96dp, selected style control 48dp, axis viewport 96dp when variable, bottom Done 48dp, all capped by available height. On short windows use one visible family row and one axis row with next/previous controls or search, preserving current compact-axis functionality. **No scrolling of the inspector**; a large library and up to 16 axes require either independently scrolling virtualized list viewports in this Fonts popup (recommended exception) or explicit next/previous pagination. This exception must be approved; if “no scrolling sheet” includes every popup, choose pagination instead. Never grow the popup past the safe viewport or add Library/Variable Axes tabs.

Rendered family names are optional, worker-prepared, cached thumbnails for visible rows only, cancellable and lower priority than foreground edits. Fall back to normal UI text immediately; no Android Typeface parsing of untrusted fonts, no shaping during Compose drawing, no full-library eager rasterization. Missing fonts retain SHA/face/instance/coordinates, display fallback and allow re-import; deletion confirms all affected faces and leaves project requests intact. Existing cache/warm-worker limits remain. Font browsing without choosing never mutates the project. Font choice itself commits once; Done dismisses browsing, not a second Apply transaction.

Source-row overflow contains Explode Text (existing compact dialog), Reset text style…, Reset paragraph style…, clear selection and existing applicable utilities. Keep animation actions in the rail menu, not mixed among destructive font actions. Explode must first finish a valid active draft or require Cancel if invalid; preserve its freeze/drop summary, >200 confirmation, box-preserving pieces, cursor/playhead sampling and exactness refusals. It has no new screen.

### 7. State, IME, Back and accessibility

Use a single editor mode: Browsing / SourceEditing / NumericEditing(property) / ColourEditing(property) / Fonts / PropertyPopover / ExplodeDialog. Carry saved tab and focus independently. Only one transaction draft and one overlay may be active. Scope/range and captured time belong to the draft, not a later recomposition. No persisted document fields are needed for mode, tab, focus or selection.

Source bar expands only while typing, replacing tab body with the existing multiline editor (internal text-field scrolling allowed), Cancel/Done and a compact scope/Source Text Hold header. Preview stays visible in remaining height above IME. Numeric mode uses a single exact-entry field instead of expanding every grid cell. Keyboard Done commits valid input once and returns to the original tab; explicit Cancel restores. Back first leaves numeric/source/colour mode by cancelling its draft and hiding IME, then dismisses a popover/Fonts/Explode, then clears selection, then closes the inspector. Outside tap on a modal numeric/colour draft cancels; source outside tap finishes valid text under the existing typing policy, with this distinction documented in UI tests. Predictive Back must execute exactly one transition, never both cancel and dismiss. App background/layer deletion cancels transient drafts; rotation preserves draft/tab/focus in editor state without creating undo entries. Navigation to another layer waits for valid commit or explicit cancellation, never writes to the wrong layer.

Use MaterialTheme roles and existing dynamic-colour/fallback scheme: onSurface on surface, onSurfaceVariant on surfaceVariant, onPrimaryContainer on primaryContainer. Preserve the project's strict monochrome icon language; selected states also use outline/check/state semantics, not hue alone. Show focused-property underline/border and header text. Keep value text readable, strong contrast for limits, disabled controls exposed with reasons. Long-press labels supplement, never replace, TalkBack labels. Each numeric value has accessible Set value / Increase / Decrease / Reset actions; category controls expose labelled choices. TalkBack users never need a drag gesture. Announce scope changes, validation, key creation/removal and missing fonts without announcing every scrub frame. Traversal order: source, scope, tabs, property grid, rail. Hardware keyboard supports Tab, Enter to edit/commit, Escape to cancel and arrow stepping only in focused controls; preserve source-field cursor navigation.

Future F3 plugs into the reserved Animate tab through a separate animator editing model and spec. Do not overload current layer property focus with animator selector focus or add a dummy animator. Until approved, no F3 controls or persisted animator UI state are implemented. Existing animators still render and Explode still reports dropping them.

### 8. Risks, deviations and approval choices

1. A 268dp surface cannot display the entire inventory as direct 48dp controls. The proposal uses eight primary grid cells, short property popovers and the single Fonts popup. Secondary controls cost one or two extra taps. Standard preview exposes four shortcuts; both heights retain all functionality.
2. Main sheet never scrolls. Recommended exception is bounded font/axis list scrolling in the Fonts popup, plus a bounded choice list only at extreme accessibility size. Pagination is the strict no-scroll alternative. This decision is part of approval, not silently implemented.
3. Run-only properties remain held Source Text formatting; layer-only properties have no fabricated run equivalent. No independent axis animation, paragraph property tracks, softness field or new schema is proposed. The header explicitly states ownership. Selection diamonds stay hidden even if Source Text is animated.
4. Scope switching can surprise users; labels, inherited/mixed indicators and captured draft scope are mandatory. No automatic conversion of existing base values into overrides or flattening of all runs.
5. Icon ambiguity is highest for tracking/kerning/manual spacing, Small Caps, last-line justification, leading/before/after and glyph-vs-layer scale. Full labels in popovers, long-press hints and accessible names are mandatory.
6. Portrait/landscape + IME + very large fonts cannot guarantee useful preview and all labels at once. Use the explicit overlay fallback; do not claim conformance until tested with actual parent chrome and safe insets.
7. Part A fixes the local instance-selection event wiring; no UI overhaul is required for that bug. This plan does not claim Android responsiveness, pixel parity or device accessibility acceptance. No new font assets are required; APK asset estimate zero, code-size change unmeasured until implementation.

### 9. Suggested implementation commits, after approval only

1. **State/focus and exact-entry foundations:** typed scope/storage owner, mixed-state presentation, single draft mode, transaction/time capture, consistent actions menu and cancellation. JVM tests for one undo, auto-key/signed origins, key-existence rollback, discrete Hold and range isolation. Preserve current UI while validating behavior.
2. **Monochrome icons and fixed three-tab shell:** measured 268/328 budgets, source bar, scope header, shared 48dp rail, inert Animate slot; consolidate Character/Paragraph into primary grid/popovers, remove old pages only once inventory coverage is checked. Source-only UI layout/semantics fixtures at 360dp and font scales 1/1.3/2.
3. **Fonts popup consolidation:** unified family/style/instance/axes/import/delete/missing state, bounded list/pagination decision, low-priority optional previews; remove Library/Axes pages. Regression tests for immediate preset application, custom-axis cancellation and missing identity persistence.
4. **Paint/numeric/IME integration and utilities:** labelled Global Colour Picker for base/overrides, requested/effective stroke feedback, overflow Explode/reset, deterministic Back, large-font/landscape fallback; remove old Spacing/Stroke controls and duplicated diamonds. Finish inventory audit and docs.

Each commit: compile/JVM/host checks only, no APK/device runs by the agent; list deviations and exact user-run checks. Run appropriate Kotlin Debug/Release and affected instrumentation-source compilation, JVM behavior tests, native host selftest/parity only where shared preparation changes. No per-frame shaping/rasterization/permutation work or new evaluation allocation. Stop and report if a protected API change is necessary. Device tests below remain user-run and no visual/performance claim precedes measurement.

### 10. Exact user-run manual acceptance list (future implementation)

1. At 360dp portrait, standard 328dp and expanded 268dp, inspect all three tabs, source bar, header, eight-cell grids, popovers and distinct diamond/overflow targets. Verify no main-sheet scrolling, no clipped controls and every mapping-table item reachable. Repeat landscape narrow/wide, cutouts/navigation modes and font scales 1, 1.3, 1.5 and 2. Confirm the agreed extreme-size fallback.
2. Long-press every inventory icon for its label; check TalkBack traversal, selected/mixed/disabled state announcements, Increase/Decrease/Set value actions, switch access, hardware keyboard and haptics disabled/enabled. Confirm compact 36dp scrub zones have usable numeric alternatives and no overlapping targets.
3. With no selection, focus each continuous property; verify header owner, animation off/on/on-key, previous/next navigation, Enable/Add/Remove labels, graph support/disabled reason and inert 3D. Open numeric/colour/Fonts, return, and verify the same focus. Animate must show only the inert F3 placeholder; return restores previous tab focus.
4. Enter precise positive/negative/decimal size, tracking, additional leading, baseline, stroke width/delta, scales and paragraph values. Reject NaN/infinity and model-invalid values inline. Test fine/coarse scrubs, detents, pointer cancellation, min/max/default, rapid final values and axis throttling. Confirm one undo/redo per completed gesture or numeric edit and no undo/key on Cancel.
5. At keyed/unkeyed and between-key playheads, edit with auto-key; verify composition frame snapping, signed key origins and preserved interpolation. Test Freeze, Reset value and confirmed Reset animation separately. Source Text, paint order and Ligatures must show Hold and never easing. Do not delete unrelated keys.
6. Select via preview long press/handles/double tap and source-field range; include ffi/office, surrogate pairs, combining marks, multi-line ranges and mixed formatting. Confirm logical character count, two-way sync, selection delta/relative/override semantics, disabled layer-only controls, hidden independent diamonds, paragraph intersection and Source Text Hold edits. No keyboard opens for preview selection; ordinary layer movement still works. Done clears transient selection without undo.
7. Exercise all Fonts functions: each bundled real style, imported static/variable fonts, collections, named instance→custom axis→different instance→Default, rapid switching and missing/restored fonts. Confirm immediate render after preset selection without slider movement, all coordinates updated, one undo, save/reopen/copy/paste and preview/export parity. Cancel pending axis work by selecting another preset. Verify bounded list/pagination and foreground priority for optional name previews.
8. Open Fill and Stroke picker in Whole layer and Selection scopes. Verify prominent context, inherited versus transparent values, palette/eyedropper, keyed/live previews, Done/Cancel/Back and unchanged key existence after cancellation. Check requested/effective stroke at small sizes, imported faces, faux styles, super/subscript and mixed runs; no requested value is silently clamped away.
9. Check all four Case values, faux bold/italic, normal/super/subscript, metrics/off/manual kerning, H/V scale, relative size and Ligatures/tracking interactions. Confirm Source Text-backed ownership, selective resets and inheritance. RTL remains explicitly unsupported; storage-only direction must survive save/reopen without claiming correct rendering.
10. Check seven physical alignments, Inherit, all indents, before/after, Auto/Fill/exact width and preview width handle. Justifying point text sets fill width in one undo; test zero/negative/animated X scale and actionable zero-scale error. Verify selection edits only intersected paragraphs and preserve other paragraph styles.
11. Open overflow Explode from a clean and active draft; verify existing dialog, all modes, box-preserving pieces, freeze/drop summary, >200 confirmation, exactness refusals, one-step undo/redo, copy/paste and save/reopen. Exercise style/paragraph/property resets and cancellation without deleting text, fonts, animation or transforms unexpectedly.
12. Test IME Done, explicit Cancel, system/predictive Back, outside tap, tab/layer/key navigation, rotation, background/foreground and layer deletion in every editor mode. Verify first Back exits the mode, preview visibility where height permits, active tab/focus restoration and no double commits or writes to another layer. Repeat 500+ glyph typing/font/axis/width stress with timing logs and existing allocation/parity counters; assess latency from measurements only.

Approval requested on this document before any Part B product changes, especially Fonts popup list scrolling versus pagination and the extreme large-font overflow fallback.

### 11. Rework 2026-11-18: final surface and inventory

Implemented in source, device-unverified. No PreviewController, gesture-protocol or batch/draw change; no new sub-screen; the sheet never scrolls.

**Surface (360dp, font scale 1, one fixed height 344dp):** Source bar 48 (excerpt, selection chip only while a selection exists, one ⋮) · tabs 40 (Character / Paragraph / Animate) · tab face 4 × 48 · feedback strip 16 · rail 48 (Prev, diamond, Next, animation-actions ⋮, Graph, 3D, ✓). Text mode has no parent chrome. Preview Expansion is ignored while Edit Text is open.

```text
Character  [ Font chip (1.8)       ][ Size    ][ Fill|Stroke (1.2) ]
           [ Tracking ][ Leading  ][ Baseline][ Stroke width       ]
           [ H scale  ][ V scale  ][ Kerning ][ Paint order        ]
           [ B ][ I ][ Case ][ x² ][ x₂ ][ fi ]   (six 57dp toggles)
Paragraph  [ Left ][ Centre ][ Right ][ Justify ▸ ]
           [ L indent ][ R indent ][ First indent ][ Direction ]
           [ Before ][ After ][ Box width ][ — ]
```

At font scale >1.2 the same cells reflow, in the same order, into a labelled 3-column grid (6 Character rows, 4 Paragraph rows). When a window cannot grant the fixed height, whole rows page from an `n/m` button at the end of the tab row.

**Overlays (only real choices), all inside the sheet:** Case (one row of four), Justify (one row: four justify variants, Inherit, Layer base ▸ which swaps the row to ‹ / base L / C / R), Box width (one row: Auto (0) / Fill composition), exact numeric entry (two rows), Fonts (≤ max(40% window, 240dp), never taller than the sheet; lists scroll internally) and the Global Colour Picker (labelled "Fill colour — Whole layer" / "… override — Selection: N characters"). The sheet is docked below the action bar and never overlaps the preview, so no overlay can hide the edited layer; no viewport pan is needed.

| Old control / popup action / menu item (TEXT UI 4)                                    | New location (rework)                                                                                                                                                                                                                                             |
|---------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source bar excerpt; tap to type                                                       | Source bar; unchanged typing mode (Cancel / Done header now reads "Editing Source Text · scope")                                                                                                                                                                  |
| Source bar "Hold" label                                                               | Removed; Hold is shown by the rail diamond's square glyph and its accessibility state                                                                                                                                                                             |
| Status line "Whole layer · property · Source Text Hold · Animation off"               | Removed. Key/animation state: diamond glyph (hollow / centre mark / filled; squares for Hold owners) + state description + polite live region. Scope: selection chip. Property name/unit: long-press label and numeric-entry title. Errors/limits: feedback strip |
| Font chip → Fonts popup (Dialog)                                                      | Character R1 Font chip → in-sheet Fonts                                                                                                                                                                                                                           |
| Fonts: search, import, delete, close                                                  | Fonts toolbar row (delete uses a bin glyph)                                                                                                                                                                                                                       |
| Fonts: delete confirmation dialog                                                     | Inline confirmation row inside Fonts                                                                                                                                                                                                                              |
| Fonts: missing / error lines                                                          | Same, one line each inside Fonts                                                                                                                                                                                                                                  |
| Fonts: bundled + imported family list                                                 | Fonts family list (internal scroll)                                                                                                                                                                                                                               |
| Fonts: style dropdown (Default / named instances / bundled styles / static note)      | Fonts horizontally scrolling style row; current choice ✓, modified preset labelled                                                                                                                                                                               |
| Fonts: axis rows (scrub, exact, reset)                                                | Fonts axis list; exact entry is an inline row (Default / Cancel / Done) instead of a dialog                                                                                                                                                                       |
| Fonts: Inherit layer font; Layer base font menu                                       | Fonts bottom row (unchanged actions)                                                                                                                                                                                                                              |
| Size cell                                                                             | Character R1 Size (layer px; relative % with a selection)                                                                                                                                                                                                         |
| Paint launcher → Paint popup                                                          | Character R1 Fill / Stroke swatch pair (solid chip vs ring, labelled "Fill"/"Stroke")                                                                                                                                                                             |
| Paint: Fill / Stroke colour → picker (Dialog)                                         | Swatch → in-sheet Global Colour Picker, Fill vs Stroke and scope in its title                                                                                                                                                                                     |
| Paint: Inherit fill / Inherit stroke (selection)                                      | "Inherit" button in the colour picker title row (selection scope); also rail ⋮ → Reset value                                                                                                                                                                      |
| Paint: Stroke width                                                                   | Character R2 Stroke width                                                                                                                                                                                                                                         |
| Paint: Paint order                                                                    | Character R3 Paint order two-state toggle (layer-only; selection explains)                                                                                                                                                                                        |
| Paint: requested/effective stroke line (always shown)                                 | Feedback strip, only while clamped: `Requested X px \| Effective Y px \| SDF limit`                                                                                                                                                                               |
| Tracking cell                                                                         | Character R2 Tracking                                                                                                                                                                                                                                             |
| Leading cell / Format → Leading                                                       | Character R2 Leading                                                                                                                                                                                                                                              |
| Quick row / Format → Baseline                                                         | Character R2 Baseline shift                                                                                                                                                                                                                                       |
| Quick row / Format → H scale, V scale                                                 | Character R3 H scale, V scale (three distinct glyphs: size, V scale, H scale)                                                                                                                                                                                     |
| Format → Kerning launcher → Metrics / Off                                             | Character R3 Kerning; tap toggles Metrics ↔ Off                                                                                                                                                                                                                   |
| Format → Bold, Italic                                                                 | Character R4 toggles                                                                                                                                                                                                                                              |
| Case cell / Format → Case → four choices                                              | Character R4 Case → one-row choice of four                                                                                                                                                                                                                        |
| Format → Superscript, Subscript                                                       | Character R4 toggles (tap active returns Normal)                                                                                                                                                                                                                  |
| Format → Ligatures                                                                    | Character R4 toggle (layer-only)                                                                                                                                                                                                                                  |
| Format / Character controls → Relative size                                           | Header ⋮ → Relative size (existing numeric editor)                                                                                                                                                                                                                |
| Format / Character controls → Manual kerning; "Reset selected manual spacing"         | Header ⋮ → Manual kerning (existing numeric editor; its action row has Reset selected manual spacing)                                                                                                                                                             |
| Character controls popup (all of the above + Fonts/Paint links)                       | Deleted: every item is on the face or in the menu                                                                                                                                                                                                                 |
| Format popup                                                                          | Deleted                                                                                                                                                                                                                                                           |
| L / C / R alignment                                                                   | Paragraph R1                                                                                                                                                                                                                                                      |
| Justify launcher → four justify choices                                               | Paragraph R1 Justify → one-row choice                                                                                                                                                                                                                             |
| Justify popup: Inherit layer alignment                                                | Same choice row: Inherit                                                                                                                                                                                                                                          |
| Justify popup: Layer base alignment menu                                              | Same choice row: Layer base ▸ → ‹ / base L / C / R (layer-only)                                                                                                                                                                                                   |
| Paragraph controls popup (7 alignments, launchers, Inherit, More/base)                | Deleted: Paragraph R1 + Justify row                                                                                                                                                                                                                               |
| Indents launcher → three indent fields                                                | Paragraph R2 Left / Right / First-line indent                                                                                                                                                                                                                     |
| Spacing launcher → before / after                                                     | Paragraph R3 Space before / after                                                                                                                                                                                                                                 |
| Box launcher / cell → numeric, Auto, Fill composition                                 | Paragraph R3 Box width: value = exact entry; icon tap = one-row Auto (0) / Fill composition                                                                                                                                                                       |
| Direction launcher → LTR / RTL + note                                                 | Paragraph R2 Direction toggle; RTL shows "stored only; RTL rendering unsupported" in the feedback strip                                                                                                                                                           |
| Numeric exact-entry dialog (field, Fine, −, +, Cancel, Done, validation, stroke note) | In-sheet two-row strip beside the cell; validation in the feedback strip                                                                                                                                                                                          |
| Large-font "Open … controls" fallback                                                 | Labelled 3-column grid of the same cells                                                                                                                                                                                                                          |
| Header ⋮: Character controls / Paragraph controls                                     | Removed (everything is on the face)                                                                                                                                                                                                                               |
| Header ⋮: Animate · F3 unavailable                                                    | Removed (Animate tab remains visibly inert)                                                                                                                                                                                                                       |
| Header ⋮: Explode Text                                                                | Header ⋮ → Explode Text (existing dialog unchanged)                                                                                                                                                                                                               |
| Header ⋮: Reset text style; Reset paragraph style                                     | Header ⋮ → one "Reset {Character\|Paragraph} style · {scope}" for the current tab; one undo entry; disabled on Animate                                                                                                                                            |
| Header ⋮: Clear selection                                                             | Selection chip × (and Back)                                                                                                                                                                                                                                       |
| Header ⋮: Layer tools                                                                 | Back (top-left arrow or system Back) returns to the layer inspector grid                                                                                                                                                                                          |
| Header ⋮: Done editing text                                                           | Rail ✓ (unchanged Done behaviour)                                                                                                                                                                                                                                |
| Format cell ⋮ glyph (third ⋮)                                                         | Removed with Format; rail animation-actions glyph is now dots + diamond                                                                                                                                                                                           |
| Rail Prev / Diamond / Next / Graph / 3D / Done                                        | Unchanged positions; diamond shows key state                                                                                                                                                                                                                      |
| Rail ⋮: Reset value, Enable / Freeze, Reset animation…, Hold, easing                  | Unchanged; Reset animation confirms in a one-row strip above the rail instead of a dialog                                                                                                                                                                         |
| Back: cancel mode, then clear selection, then the parent                              | Back leaves the innermost state (Explode → draft → confirm → base row → choice row → Fonts); otherwise returns to the layer inspector grid and clears the selection. The top-left arrow dispatches the same Back                                                  |
| Animate tab                                                                           | Unchanged inert placeholder with reason                                                                                                                                                                                                                           |

Nothing in the TEXT UI 4 inventory was removed without a destination above.

---

<a id="rec-text-ui-overhaul"></a>

## Edit Text overhaul implementation — 2026-10-09

*Formerly `docs/checkpoints/TEXT_UI_OVERHAUL.md`.*

The plan is approved with the user's amendments: velocity-accelerated scrubbing with centralized tuning; Character row 2 Tracking/Leading/Case/Format and standard Stroke/H/V/Baseline; Paragraph row 2 Box/Indents/Spacing/Direction; virtualized independently scrolling Fonts lists; bounded accessibility popovers; inert Animate and disabled Graph/3D. No inspector-sheet scrolling. Source implementation is device-unverified throughout. T1–T4 device reports belong to the user, not these UI changes. T5 remains unsupported.

### Commit 1: foundations

Typed tab/focus/mode/scope and storage ownership, captured exact numeric draft, original logical character counting, range-relative values and delta edits, signed/snapped key origins and existing-key interpolation, model-bound validation, centralized scrub acceleration/fine/detent/haptic tuning and source-level layout budget are implemented. No renderer or protected API changes. UI integration follows in commits 2–4; this commit alone does not change the old surface.

Source budget audit: existing text parent consumes 16dp vertical padding + 36dp text header + 4dp top content padding = 56dp. All are removable in text mode. Commit 2 removes them only in text mode: final non-removable parent chrome **0dp**, 8dp horizontal margins, 344dp usable width at 360dp. Budget: Source 48 + scope 32×fontScale + tabs 40 + body remainder + separator 4 + rail 48; at scale 1 and 268dp body=96dp, at 328dp body=156dp. JVM layout assertions cover both heights × font scales 1/1.3/2; bounded window fallback uses overlays if body<96dp. This is source arithmetic, not measured device layout or visual acceptance.

Foundation checks: app Kotlin Debug/Release and all 253 JVM tests passed, zero failures/errors/skips (`tools/text/results/text-ui-1.log`). Host production is unchanged; the full host selftest passed again at this gate. No APK packaging/device run. Asset delta zero; code-size delta unmeasured. Prepared evaluation is untouched.

Exact user-run list for commit 1: run the new `TextInspectorStateTest` and existing text edit/paragraph/font tests in Android Studio; existing UI should retain behavior because foundations are not wired yet. Check previous named-instance fix, one-step text undo, source-key round-trip and preview/export parity. Numeric interaction and layout acceptance must wait for the integrated surface and the full list below; compilation is not device acceptance.

### Shared exact user-run list for integrated commits

The numbered list in [the approved plan](#10-exact-user-run-manual-acceptance-list-future-implementation) is the complete acceptance list. Per-commit applicable subsets and deviations will be recorded here, with compile/JVM/host results after each gate. No Android performance, accessibility or pixel-parity claim is made from source-only checks.

### Commit 2: monochrome three-tab shell

Character/Paragraph/Animate replace the inspector's top-level text pages. Source/scope/focus rail, amended primary grids, standard quick rows, bounded property popovers, physical alignment icons, Case and other cached monochrome ImageVectors are wired. Parent text-mode chrome is removed (0dp remaining, 344dp inner width at 360dp); other layer-tool layouts are unchanged. Graph and 3D are visibly disabled with reasons; Animate has no animator controls. Old page source files remain until the final inventory audit/removal in commit 4. Fonts temporarily uses the existing imported controls; the unified bundled/imported popup lands in commit 3. Numeric/colour/scrub/IME behavior is integrated and polished in commit 4; this intermediate shell is not final acceptance.

Gate passed: Kotlin Debug/Release, app instrumentation-source compilation and all 253 JVM tests (`text-ui-2-final.log`); full host selftest passed. First compile found incorrect selection range accessor names, fixed before the passing gate. `TextInspectorLayoutTest` compiles real Compose bounds assertions for 360dp, both heights and scales 1/1.3/2; it has NOT run on device. Existing Compose-rule deprecation and an existing unnecessary-safe-call warning remain. No native/product font assets changed; APK asset delta zero, code delta unmeasured. No protected APIs or prepared evaluation changes.

Exact commit-2 user-run list: (1) run `TextInspectorLayoutTest` in Android Studio on the user's device; (2) compare 268/328dp portrait and landscape at font scales 1/1.3/2, verify three tabs and amended grids; (3) open every property popover and check icons/long-press labels/physical alignments, Case and standard shortcuts; (4) verify the scope header, logical selection count and shared focus rail, disabled Graph/3D and inert Animate; (5) confirm no inspector scrolling, no duplicate parent header, layer-tools/Done exit routes, and unchanged preview move/box gestures. Use the complete integrated list after commit 4; these are source-only checks until the user runs them.

### Commit 3: unified Fonts popup

One searchable popup contains bundled/imported families, collection faces, real styles/named instances, complete preset/default coordinates, modified-instance feedback, inherited font, import/delete confirmation and missing-request feedback. Family and axis lists are separately virtualized/scrollable. Static imported faces explain that alternate styles are separate faces. Axes have icon scrubbing with bounded velocity acceleration/default detents, frame-conflated previews, one captured Source Text transaction per drag, exact entry and default reset. The font chip marks missing identities. Font name previews use ordinary UI text: optional font-rendered thumbnails are deliberately omitted to avoid foreground work; no font parsing/shaping occurs in UI drawing. The original worker, cache and fallback paths are unchanged.

Gate passed: Kotlin Debug/Release, app instrumentation-source compilation and 253 JVM tests (`text-ui-3.log`). Host CFF variable-font proof passed outline/advance changes, clamping, independent FT/HB agreement and prepared transport. Existing named-instance JVM regression remains green. Asset delta zero; no APK packaging or devices. Commit 4 finishes transaction-ready gating, restoration and interaction/accessibility polish before acceptance.

Exact commit-3 user-run list: (1) search and choose each bundled family/style and imported static/variable/collection face; (2) choose named presets and Default without moving an axis, verify all coordinates, modified preset labeling and inheritance; (3) scrub axes slowly/quickly, exact-enter/reset values, cancel gestures and verify one undo/redo plus save/reopen/copy/paste; (4) import, cancel import, delete with confirmation, reopen missing identities and re-import; (5) verify independent virtualized family/axis scrolling, large-font and short-window bounds, no main-sheet scroll, source-scope preservation, cold/warm preview/export parity and timing logs. These checks are device-unverified; finish the full acceptance list after commit 4.

### Final inventory audit (performed before removing old pages)

The old Text/Spacing/Stroke pages, ten Character pages (including Library/Axes), five Paragraph pages and their helpers have no remaining external call sites. Each capability maps below to the new single fixed sheet, a property popover or the one Fonts popup. Character/Paragraph/Animate are the only retained tabs. Entries marked layer-only cannot fabricate run equivalents; they retain explicit scope explanations. Animate, Graph and 3D remain inert/unavailable.

| Old control / capability | Final location / owner |
|---|---|
| Source text multiline editor, Cancel/Done | Always-visible one-line Source bar expands in typing mode; shared source-field/preview range; Done commits, Cancel restores |
| Source Text hold keys | Source bar focus → shared key rail, explicit Source Text/Hold ownership |
| Layer base family dropdown | Fonts → Layer base font submenu, preserves run overrides; static layer property, no independent diamond |
| Run font family, real style, named instance | Font chip → unified searchable Fonts popup, selected range or all runs, held Source Text |
| Inherit layer font | Fonts → Inherit layer font; distinct from selecting Inter |
| Bundled/imported list, collection face, Use face | Fonts family viewport; tap a face applies once; face index retained |
| Import, progress/error, delete confirmation | Fonts toolbar; delete confirms all faces in that file, requested project references retained |
| Missing-font indicator | Font chip and Fonts banner, requested identity retained; Inter fallback labelled |
| Variable axes, defaults, exact values | Separate virtualized axis viewport in Fonts; icon scrub/value entry/default reset; preset replaces all coordinates |
| Layer size | Character Size cell, px, animatable base |
| Relative run size | Same Size cell in selection scope (%); Format → Relative run size for whole-layer runs |
| Fill / run fill override | Character Paint → labelled Fill swatch → Global Colour Picker; layer base or selected override |
| Stroke colour / run stroke override | Paint → labelled Stroke swatch → Global Colour Picker; layer base or selected override |
| Stroke width / run stroke delta | Paint → Stroke width; standard quick row; same icon changes to selected delta |
| Inherit fill/stroke overrides | Paint → explicit Inherit fill / Inherit stroke; transparent ARGB remains a real value |
| Stroke/fill order | Paint → paint-order toggle, layer-only Hold track |
| Layer tracking / run tracking delta | Character row 2 Tracking, 1/1000 em, base versus selected delta |
| Additional leading | Character row 2 Leading (large-font Format); px, layer-only |
| Baseline shift / run baseline delta | Format → Baseline; standard quick row; px base/delta |
| Metrics/Off kerning | Format → Kerning choices; static run formatting, Source Text Hold |
| Manual spacing amount/Apply/reset | Format → Manual spacing numeric entry, applies after selected original source boundaries; Reset selected manual spacing |
| Horizontal/vertical glyph scales | Format → H/V percent; standard quick row and wide-window direct cells; not layer transform scale |
| Faux bold / faux italic | Format icon toggles, distinct from real style |
| Normal / ALL CAPS / lowercase / Small Caps | Visible Character Case cell → four-choice vector row; also Format at accessibility sizes |
| Normal / superscript / subscript | Format mutually exclusive Super/Sub toggles; tap active toggle returns Normal |
| Optional ligatures | Format → layer-only Ligatures toggle; Hold track, existing tracking/required-joining limits unchanged |
| Layer base L/C/R alignment | Paragraph alignment popover → Layer base alignment submenu; preserves paragraph overrides |
| Seven paragraph alignments | Paragraph L/C/R plus Justify popover (last-left/centre/right/all); physical alignment |
| Inherit paragraph alignment | Alignment popover → Inherit layer alignment |
| Left/right/first-line indents | Paragraph row 2 Indents → three icon numeric fields; selected intersected paragraphs or all |
| Space before/after | Paragraph row 2 Spacing → before/after numeric fields; standard extra row |
| Box Auto / Fill / numeric | Paragraph row 2 Box → Auto (0), Fill composition and exact/scrub px; document-wide Source Text Hold |
| Preview box handle | Existing preview handle and begin/preview/commit/cancel callbacks unchanged |
| LTR/RTL attribute | Paragraph Direction popover; storage-only Source Text edit, explicit RTL rendering error retained; no bidi support claimed |
| Explode Text | Source-row overflow → existing compact dialog, all options/summary/count/fidelity gates retained |
| Reset text / paragraph style | Source overflow, scope-labelled; property actions reset only their focused property |
| Property reset, freeze, animation reset, interpolation | Shared Animation actions menu; continuous/colour interpolation only, Source Text/order/ligatures marked Hold |
| Selection header / Done selection | Header original logical character count; rail Done clears transient selection without document/undo edit |
| Property focus, previous/next keys | Scope header and shared rail, stable through overlays; owner identified, selection has no independent diamonds |
| Enable/Add/Remove diamond and utility overflow | Distinct 48dp rail/source targets with action-specific accessible labels |
| Graph / 3D rail reservations | Visibly disabled, accessible unavailable reasons |
| Stroke/softness/clamp feedback | Paint/numeric overlays, requested versus effective/range and size/SDF reason; requested values retained, no independent softness field added |
| Layer rename/duplicate/copy/delete and other tools | Source overflow → Layer tools returns existing inspector landing/header actions |
| Large-font / short-window access | Bounded property overlay with scrollable choices; Character/Paragraph utilities expose complete inventories even when main cells cannot fit |
| Animate / future F3 slot | Third inert tab; no animator editor or persistence added |

Optional font-rendered list previews are omitted; names use normal UI text and the selected face renders in the existing preview. No control is removed for that optimization. The legacy base font/alignment submenus preserve functions that would otherwise be lost by mapping all font/alignment edits only to run/paragraph overrides.

### Commit 4: interaction, restoration and inventory completion

Implemented in source, device-unverified: captured numeric/colour/source drafts wait for earlier preparation before beginning; numeric edits retain units, signed key origins and interpolation. Icon scrubs publish last-wins once per display frame, accelerate wide-range properties, offer fine adjustment and rate-limit system-respecting haptics. Numeric mixed selections display a dash. Colour controls retain focus and use one draft through channels, palette and preview sampling; Cancel and cancelled subgestures restore without creating keys. Source/selection ranges synchronize through the shared prepared cluster path when available. Scope, focused property, tabs and transient drafts survive same-process configuration changes; saved-state tokens retain immutable drafts in memory instead of serializing large documents into the Bundle. Process death safely discards uncommitted drafts.

Removed the four audited obsolete page implementations only after writing the complete mapping above. Preserved static layer base font/alignment, explicit inheritance, selected paragraph formatting, font import/delete/missing identities, all variable axes and the existing Explode dialog. Direction remains storage-only with the existing actionable RTL rendering rejection. Graph/3D remain disabled and Animate inert. No protected API or native prepared evaluation changes.

Source budget: the former text-specific parent padding/header/gap consumed 56dp; text now consumes zero parent chrome. The 268/328dp inspector budget includes Source 48dp, scope header 32dp times font scale, tabs 40dp, separator 4dp and rail 48dp; remaining height belongs to controls. At extreme constrained heights the header/tabs collapse into scope-labelled Source and overflow access, retaining the same tab state. The main inspector never scrolls. The bounded Fonts/property overlays may scroll at approved large-font/short-window sizes; family and axis lists are independently virtualized. Compact scrub icon hit areas are 36x48dp beside separate numeric targets; utility/rail targets are 48dp. Optional rendered font thumbnails are omitted. APK asset delta is zero; compiled code delta is unmeasured (no APK packaging).

Exact commit-4 user-run acceptance list:

1. Run `TextInspectorLayoutTest` from Android Studio: 360dp, 268/328dp budgets, font scales 1/1.3/2 and constrained heights. Inspect portrait/landscape, preview expansion, contrast, TalkBack traversal and all bounds. Confirm no main-sheet scrolling and accessible fallback inventories.
2. Audit every row of the mapping above: open every control, verify monochrome icons, long-press labels, scope header, units, inheritance, disabled reasons and layer-tools/Done exits. Graph/3D remain unavailable; Animate has no animator controls.
3. Scrub size, tracking, leading, baseline, box, all indents and paragraph spacing slowly and quickly in both directions; exercise fine adjustment, bounds, system haptics off/on, cancellation and one undo/redo. Verify practical wide ranges in one/two fast swipes and exact entry for larger finite values. Confirm existing preview layer/box gestures.
4. Exact-enter positive/negative/fractional values and invalid/empty input for all numeric fields; verify px, percent and 1/1000-em units, frame-snapped keys, existing easing, auto-key, requested/effective stroke hints and unchanged opening/closing creates no undo/key.
5. Select mixed runs from preview and Source, including surrogate pairs, combining marks and ligatures. Verify two-way cluster-safe selection/count, mixed numeric values, relative size/deltas, manual spacing boundaries, paint override inheritance, intersected paragraphs and no selection diamonds. Clear with Done/outside tap.
6. Exercise every continuous/discrete focused property: enable animation, add/remove key, previous/next, freeze, property reset, confirmed animation reset and interpolation. Source Text/paint order/ligatures show Hold only. Focus and key ownership stay stable through numeric/colour modes.
7. Open Fill and Stroke separately; use all picker channels, palette, hex and eyedropper; cancel a subgesture, cancel sampling, cancel whole picker, accept, undo/redo. Verify no unwanted keys on cancellation and one transaction on accept; selected overrides versus layer base remain distinct.
8. Type and replace Source, test IME composition, Done, Cancel, Back, keyboard dismissal, hardware Escape, preview visibility and preserved tab. Repeat numeric/colour modes; rotate while drafting, background/return, and terminate/reopen (uncommitted drafts discarded after process death).
9. Repeat commit-3 Fonts acceptance: bundled styles, imported static/variable/collection faces, presets/default/modified instance, independent lists, exact axes/scrubs/cancel, import/delete/missing/re-import, inheritance, undo/redo/save/reopen/copy/paste and preview/export parity.
10. Repeat Explode Characters/Words/Lines/Paragraphs/Style runs, skip spaces/original/pivots/count confirmation, animated-transform restrictions, preserved box/alignment rules, logical naming/visual positions, undo/redo/save/reopen and accepted contextual-form limits. No RTL rendering support is introduced.

Gate results are recorded below after completion. Device checks above have not been run; no responsiveness or visual acceptance claim is made without user measurements.

Host gate: selftest passed (600 cached ligature switches, 4096-glyph modes, cluster/fallback/UTF-8/LRU/lifetime/concurrency checks); all six independent shaping references passed; integrity verification passed 1,447 inputs; imported CFF variable outline/advance/clamping/FT-HB/prepared-transport proof passed. The initial overlapping Android gate encountered an incremental-cache race and stuck compiler daemons; those gate processes were stopped and the final run uses Kotlin in-process. No project build settings were changed to recover the tooling.

Final Android gate PASS: `text-ui-4-inprocess.log`, app Kotlin Debug/Release, instrumentation-source compilation and all 256 JVM tests, zero failures/errors/skips (12m 56s). Existing Gradle option, redundant/safe-call and Compose test-rule deprecation warnings remain. Source-only layout assertions cover 360dp at both budgets and font scales 1/1.3/2 plus constrained heights; they compiled but have not executed on a device. All four stages are implemented in source; the exact acceptance lists above await user execution. No APK packaging or device runs. The complete final inventory above is the removal audit.

### Rework commit 1: tab faces (follow-up to the overhaul) — 2026-11-18

User review on the A16 found the overhaul cluttered: the same controls appeared on the tab face, in Character controls and in Character formatting; the Paragraph popup repeated the whole tab; popups covered the edited layer. Implemented in source, device-unverified:

- Deleted the aggregate popups (Character controls, Character formatting/Format, Paint, Paragraph controls, Indents/Spacing launchers). Every common control sits directly on its tab as a 4-column grid of 48dp cells. Character: Font chip · Size · Fill/Stroke swatch pair / Tracking · Leading · Baseline · Stroke width / H scale · V scale · Kerning (tap toggles Metrics/Off) · Paint order (toggle) / Bold · Italic · Case · Superscript · Subscript · Ligatures. Paragraph: Left · Centre · Right · Justify / Left · Right · First-line indent · Direction (storage only) / Space before · Space after · Box width. Animate stays an inert placeholder.
- Remaining overlays are only real choices: Case (one row of 4), Justify (one row: four justify variants, Inherit, Layer base ▸ which swaps the row to base L/C/R), Box width (one row: Auto / Fill composition; tap the value for exact entry), the Fonts sheet and the Global Colour Picker. Exact numeric entry is a two-row strip beside its cell.
- No overlay can hide the edited layer: the Edit Text sheet is docked below the action bar and never overlaps the preview, and every overlay is laid out inside the sheet's bounds (no Dialog windows, no viewport pan). Fonts is capped at max(40% of the window, 240dp) and never taller than the sheet; its family, style and axis lists scroll internally. Exact axis entry and font deletion confirm inline. Reset animation confirms in a one-row strip above the rail.
- One fixed Edit Text height sized for the 4-row Character face, independent of Preview Expansion. At font scale >1.2 the same cells reflow in order into a labelled 3-column grid (6 Character rows, 4 Paragraph rows). If the window cannot grant the height, whole rows page from a `n/m` button in the tab row; the sheet never scrolls.
- Stroke clamp feedback is non-modal and shown only when clamped, in the feedback strip: `Requested X px | Effective Y px | SDF limit` (shorter forms at 1.3/2.0; the full text is the accessibility description).

Gate: app Kotlin Debug/Release, instrumentation-source compilation and all 264 JVM tests, zero failures/errors/skips (`text-ui-rework-1-gate.log`). Host build and selftest passed. No APK packaging or device run. Commit 2 removes the status line (sheet becomes 344dp) and finishes the menu, Back, status and icons; the complete inventory and user-run list are recorded there.

### Rework commit 2: menu, Back, status and icons

Implemented in source, device-unverified.

- **Menus.** The ⋮ inside the grid is gone (it was the Format cell). Two remain: the rail's animation-actions menu (glyph now dots + diamond, so it no longer matches the header ⋮) and one header ⋮ containing exactly **Explode Text | Reset {Character|Paragraph} style · {scope} | Manual kerning | Relative size**. Reset resets the current tab's formatting for the current scope (selection, or whole layer) as one format/paragraph transaction (one undo entry); it is disabled on Animate. Removed: Character controls, Paragraph controls, "Animate · F3 unavailable", Done editing text, Clear selection, Layer tools and the separate paragraph reset.
- **Status.** The status line and the always-visible Hold label are deleted. The rail diamond shows state by glyph (hollow = off, centre mark = animated between keys, filled = on key; square glyphs for Hold owners Source Text / paint order / ligatures), with a full state description, a polite live region for announcements and the state in its long-press label. A scope chip ("N characters" ×) appears only while a selection exists; tapping it clears the selection.
- **Back.** One state machine (`TextInspectorBack`) serves system Back, Escape and the editor's top-left arrow (which, while Edit Text is open, dispatches system Back and announces "Back: close the open text control, or return to layer tools"). Order: Explode dialog → cancel numeric/source/colour draft → reset-animation confirmation → base-alignment row (returns to the Justify row) → choice row → Fonts → otherwise return directly to the layer inspector grid (Transform, Blending & Opacity, Edit Text…), clearing any selection. Previously the arrow deselected the layer and closed the inspector. The ✓ keeps its Done behaviour.
- **Icons.** Size = small T + large T; vertical scale = tall T + vertical arrows; horizontal scale = wide T + horizontal arrows (all three were "A + arrow" variants). Relative size is a text menu item. Audit fixes: Source (was ragged lines = Align left) is now T + text cursor; Delete font (was ✕ = Cancel) is a bin; Inherit (was the Reset arrow) is a chain link; Stroke colour (was the Stroke width glyph) is a hollow A; animation actions (was ⋮ = header menu) is dots + diamond. A JVM test asserts that no two visible glyphs are identical except deliberate aliases (Case/Normal, Direction/LTR, Fill/Paint, Cancel/Close, Key/Animate). Remaining look-alikes, reported not changed: Tracking vs Kerning (both A V pairs; arrows out vs in; Kerning cell also shows Metrics/Off text); Align left vs Justify last-left (differ only in the upper lines; labelled choice row); Leading vs Space before/after (lines + arrows; different arrow placement). Long-press and accessibility labels are kept on every control.

#### Gate

`text-ui-rework-2-gate.log`: app Kotlin Debug/Release, instrumentation-source compilation and all **270** JVM tests, zero failures/errors/skips. The first attempt failed only on a missing test import (`AnimPropertyType`), fixed before the passing run. Host build and selftest passed. Existing Gradle-option, pre-existing safe-call and Compose test-rule deprecation warnings remain. No APK packaging or device run; `TextInspectorLayoutTest` (layout bounds at 360dp × font scales 1/1.3/2, selection chip, no status/Hold text, measured stroke-clamp width) compiles but has not executed.

#### Deviations

1. **Sheet height 344dp, not 328dp.** Source 48 + tabs 40 + 4 × 48 rows + feedback 16 + rail 48 = 344 at font scale 1 (no parent chrome). This is the "one fixed height sized for the 4-row face"; it is 16dp taller than the old standard and 76dp taller than the old expanded 268dp. At 1.3 / 2.0 the labelled 3-column face needs 6 rows: 444.8 / 456dp. The editor clamps the sheet to leave the minimum preview (96dp, or 48dp in very short windows); when that clamp bites, whole rows page from the tab row (`n/m`) rather than scrolling.
2. **Font chip is 1.8 columns, Fill/Stroke 1.2** (spec: 2 + 1). With two swatches in one 86dp cell each target would be 43 × 48dp, below the project's 48dp standard. At 1.8 / 1.2 each swatch is 51.6 × 48dp (57 × 48dp in the 3-column fallback); the font chip is 154.8dp.
3. **Numeric exact entry is two rows** (field row + Fine / − / + / [Reset spacing] / Cancel / Done), because those 48dp targets plus a usable field cannot fit one 344dp row. It sits beside its cell, never on it when the page has room.
4. **Colour picker and Fonts are taller than one row** (Fonts was an allowed exception; the picker needs about 290dp). Both are drawn inside the sheet and cover the face while open; they never cover the preview.
5. **Box width choices** open from the Box icon zone (the value still opens exact entry). **Direction** is a two-state toggle on the face instead of a popover; RTL shows "stored only; RTL rendering unsupported" in the feedback strip.
6. **Justify row** has six segments (four justify variants, Inherit, Layer base ▸); Layer base swaps the same row to ‹ / base L / C / R. This keeps Inherit layer alignment and the layer base alignment, which had no other home.
7. **Stroke clamp text** is `Requested X px | Effective Y px | SDF limit` at scale 1 (the full "SDF spread / size limit" is the spoken text). Shorter visible forms are used at 1.3 (`Req X | Eff Y px | SDF limit`) and 2.0 (`Req X | Eff Y`) to stay on one line. Proven envelope: requested ≤ 999.99, effective ≤ 99.99–99.99; estimated widths 332 / 320 / 320dp of 344dp (JVM, 0.52em advance); the instrumented test measures real glyph widths. Other feedback messages ellipsize to one line, with the full text as the accessibility description.
8. **Explode Text stays a centred dialog** (existing compact dialog, not changed by this request). Reset-animation, axis-entry and font-delete confirmations moved into in-sheet rows.
9. The old 6-column wide-window variant was dropped: the 4-column face stretches, since nothing needs extra columns any more.

#### Assumptions

- "Doesn't hide the layer" is met by containment: the Edit Text sheet sits below the action bar and timeline anchor and never overlaps the preview, so overlays bounded by the sheet cannot intersect the layer's on-screen bounds. No viewport pan was added and the document is never changed.
- **Preview Expansion conflict:** while Edit Text is open the preview expand toggle still flips its saved state but the sheet height doesn't change, so the toggle appears to do nothing until Edit Text closes. Nothing else depends on it in this mode.
- Reset {tab} style keeps the existing semantics: Character resets run formatting only (font inheritance, size factor, scales, overrides, deltas, manual spacing, faux, case, script, kerning); layer base values, animation, Source Text, fonts and transforms are untouched. Paragraph resets paragraph attributes of the scope.
- Hardware Escape does nothing when no transient state is open (only the arrow / system Back leave Edit Text).
- Inside Fonts, Back closes the whole Fonts sheet, discarding an open inline axis-entry or delete-confirmation row with it.

#### Exact A16 user-run list

1. Run `TextInspectorLayoutTest` and the JVM inspector tests (`TextInspectorFaceTest`, `TextInspectorChromeTest`, `TextInspectorStateTest`) from Android Studio.
2. Portrait, font scale 1: open Edit Text from both preview sizes. Confirm the sheet height is identical, the Character face shows 4 rows exactly as listed in the plan §11, Paragraph 3 rows, Animate an inert message; nothing scrolls and nothing is clipped.
3. **No popup hides the edited text.** Place the text at the bottom edge of the composition and at the centre. Open Case, Justify (and Layer base ▸), Box width choices, exact entry for a row-1 and a row-4 cell, Fonts (scroll each list, open an axis exact entry, start a delete and cancel), Fill and Stroke pickers (incl. eyedropper). The text must stay fully visible above the sheet each time; each one-row choice must sit beside its own cell.
4. **Back from every state**, once with system Back and once with the top-left arrow: Explode dialog, numeric entry (incl. with keyboard up), source typing, colour picker, colour eyedropper sampling, reset-animation confirmation, Layer base row (→ Justify row), Case / Justify / Box rows, Fonts, and finally nothing open (→ layer inspector grid, selection cleared). One press = one step; no draft is committed by Back; Cancel restores values and key existence. The ✓ still clears a selection or closes the inspector as before.
5. **Menu contents:** header ⋮ shows exactly Explode Text, Reset Character style · scope (Paragraph on the Paragraph tab; disabled on Animate), Manual kerning, Relative size. Exercise each: Reset is one undo step for selection and for whole layer; Manual kerning offers Reset selected manual spacing; Relative size edits the run size factor. The rail ⋮ (dots + diamond) still offers Reset value / Enable or Freeze / Reset animation (in-sheet confirm) / Hold or easing.
6. **Selection chip:** select from the preview and from the Source field (ligature, combining mark, surrogate pair). The chip shows the logical count, only while a selection exists; × clears it; no status line or Hold label appears anywhere.
7. **Diamond state:** for Size, Tracking, Fill and Source Text / paint order / Ligatures check off → animated between keys → on key glyphs (squares for the Hold owners) and TalkBack state announcements when moving the playhead onto/off a key.
8. **Large font scale** 1.3 and 2.0: labelled 3-column face, every control present, toggles readable, no overlapping targets, no scrolling; on a short or landscape window confirm `n/m` paging switches whole rows and the tabs keep working.
9. **Stroke clamp message:** set a large stroke on small text (whole layer, then a selection with a delta, then an imported face). The feedback strip shows `Requested X px | Effective Y px | SDF limit` on one line only while clamped and disappears when not; TalkBack reads the full text.
10. **Icons:** at 24dp distinguish Size, V scale, H scale, Baseline, Leading; Source vs Align left; Delete (bin) vs Cancel/Close; header ⋮ vs rail actions; long-press shows the label for every face control.
11. Regression: all text controls still edit with one undo per gesture or entry; Fonts import / named instances / axes / missing fonts; Explode; preview move and box-width handle; save/reopen.

### TEXT UI 5: single-header page, slider popovers, limits, log scale ruler

The pass-2 surface was rejected on the A16 (cluttered, flat, typing-first, and the numeric IME resized the window and shrank the preview). This pass replaces it in one commit. Implemented in source, device-unverified. No `PreviewController`, gesture-protocol, `EditorAction`, batch/draw or schema change. The only model constant touched is `TextLimits.MAX_SIZE` (500 → 1000 px; see Deviations).

#### Frame and navigation

- **Edit Text is an ordinary inspector page.** Same `ElementInspectorSheet` frame (12dp sides, 8dp top/bottom), same height and same font scaling as Transform. The fixed 344dp text sheet, its separate height path in `EditorScreen` and the "Preview Expansion ignored" deviation are gone: every page uses `InspectorSheetBudget.height(expanded, fontScale)`.
- **Shared heights rose to 308 / 344dp** (expanded / standard), for all pages. Numbers: the Character tab needs 6 tile rows (row 1, row 2, 3 style rows, bottom bar). Frame 16 + header 40 = 56dp of chrome. Compact (sheet < 320dp, 40dp targets): 6 × 42dp pitch = 252dp, total 308. Standard (48dp targets): 6 × 48 = 288dp, total 344. The old budgets leave 268 − 56 = 212 < 252 and 328 − 56 = 272 < 288, so they provably cannot hold the tab; both rise by the minimum (+40 / +16dp). Preview/timeline arithmetic is otherwise unchanged (the same clamp leaving a 96/48dp minimum preview, now applied to every layer type).
- **One header row:** `←` | Character | Paragraph | Animate | (⚠ only while there is an error or message; tap shows it) | ⋮. Removed: second chrome row, Layer tools text, duplicate/delete icons, ✓/Done everywhere (rail, numeric entry, colour picker, Fonts, and the dead fill-colour "Done" row in `ElementInspectorSheet`), per-tab ⋮ and the feedback strip. The one ⋮ holds exactly Explode Text, Reset {tab} style · scope, Manual kerning, Relative size (the last two open the slider popover anchored on ⋮).
- **Back** (`TextInspectorBack`): Explode dialog → reset-animation confirmation → popover (the Layer base row first returns to Justify) → Source typing commits → sub-page (Fonts / Colour) returns to the tab → landing grid (selection cleared). System Back, Escape and the editor's top-left arrow share it. Popovers are focusable popups, so system Back closes them before the page sees it.
- **Rail:** the same `InspectorUtilityRail` as Transform/Opacity (now `internal`, with optional key glyph/state, extra menu items and an anchored confirmation). The diamond keeps hollow / centre-mark / filled and the Hold squares and acts on the focused text property; Graph disabled; property-options ⋮ = Reset {property} value, Enable/Freeze, Reset animation… (anchored confirmation), Hold or easing. Prev/Next-key buttons, ✓ and the 3D icon are removed from the text rail (`show3D=false`).
- **Prev/Next follow focus:** `TextFocus.owner` = the open popover's property, otherwise the last touched control (the colour popover uses its Fill/Stroke target). The panel reports it through the existing `onPropertyFocus`; the action bar's Prev/Next already route through `ContextualNavigation` with that property. The only fix needed was making an open popover count as focus; `TextFocusNavigationTest` covers it.

#### Final tile inventory

Tiles use the landing-grid language: 40dp, `SurfaceHigh`, 10dp radius, 1dp `TextPrimary` border when active, pressed overlay, icon plus optional 8sp value caption bottom-right, and a long-press label, content description and state on every tile. Pitch is 42dp compact / 48dp standard (the whole pitch is the touch target). 1dp `Border` hairlines separate groups.

| Tab / row | Tiles (left to right) |
|---|---|
| Character row 1 | Fill/Stroke dual-colour tile (front square = fill, back ring = stroke) · Stroke width · right-aligned "Fill over stroke" label + real `Switch` |
| Character row 2 | Tracking · Leading · Horizontal scale · Link (uniform H/V) · Vertical scale · Baseline shift |
| Character style grid (3×3) | Bold · Italic · Underline / Strikethrough · Superscript · Subscript / All caps · Lowercase · Ligatures |
| Beside the grid (hairline) | Small caps · Kerning (Metrics/Off) |
| Paragraph row 1 | Align left · centre · right · Justify (choice popover: 4 justify variants, Inherit, Layer base ▸ → base L/C/R) |
| Paragraph row 2 | Left indent · Right indent · First-line indent · Space before · Space after |
| Paragraph row 3 | Point/Box toggle · Box width (popover adds Auto and Fill composition) · Direction (LTR/RTL, storage only) |
| Bottom bar (Character, Paragraph) | Font chip (opens Fonts page) · Size (ladder) · inline text field (selection chip "N ×" inside it while a selection exists) |
| Animate | F3 placeholder; no bottom bar |
| Header ⋮ | Explode Text · Reset {tab} style · Manual kerning (popover + Reset selected manual spacing) · Relative size (popover) |

Large font scales (> 1.2): the same face tiles reflow in order into a labelled 3-column grid; whole rows page from an `n/m` button; the page never scrolls.

#### Slider popovers and typing

- Tapping a numeric tile opens a small popover anchored directly above it (below if there is no room), scaled out from the anchor and clamped horizontally with 8dp margins (`PopoverPlacer`). It is never centred and never a sheet. Contents: `[↺]  slider ——●  [value]`. Tap outside or Back closes it.
- The value is tappable: it becomes an inline field (select-all, numeric IME). IME Done or focus loss / tap outside commits; Escape cancels; Back (which first hides the keyboard) cancels (`TypedEntry`). Typed values clamp to the typed range; every value clamps to the hard cap.
- Reset returns to the property default and respects inheritance: run values return to neutral (relative size 100%, deltas 0) and layer values to the model default (e.g. 72px).
- Font size snaps to the 23-rung ladder (8 … 300); Leading follows the same ladder plus 0. Other sliders are continuous with a soft snap (1.2% of span) at 0 and the default; detents every 10 px / 10 thousandths of an em / 50% / 5 px of stroke.
- Haptics go through the View, so the system touch-feedback setting is respected: a press when the thumb is grabbed, and a tick per ladder rung or detent bucket and on crossing 0/default (≥ 35ms apart).
- One undo per drag (begin on first move, finish on release) or per typed commit/reset; live preview is conflated to one publish per frame through the existing `onPayloadPreview` path.
- Icon drag-scrubbing and every −/+ button are gone (`TextNumericGesture.kt` deleted).

#### Keyboard

`EditorScreen` no longer applies `imePadding()` and no longer re-lays out the editor while typing: the action bar and timeline stay and the preview height is unchanged. With the existing edge-to-edge window this is the adjustNothing equivalent, scoped to the editor. Other screens keep their own inset handling and the manifest is unchanged. Only the focused popover or the inline text editor lifts above the keyboard: popovers are positioned using the host window's IME height (`rememberVisibleBottom`, `LocalHostImeBottom`). The Transform/Opacity value boxes now open the same anchored entry popover.

#### Fonts page and colour

- **Fonts** is a page in the same frame (`←` returns to the tab). Left column: search, import, delete (imported families, inline confirmation), and the families, with bundled names drawn in their own face (Sans/Serif/Mono; imported names use UI text). "Default" merges Inherit and Layer base: choosing it inherits, and its right column lists the layer base fonts. Right column: styles / collection faces / named instances (a modified preset is labelled). Variable faces add one row of axis tiles; each opens the slider popover (frame-conflated preview, one transaction per drag, typed entry, reset to default). Library hashing, isolated-process import and missing-identity handling are unchanged.
- **Colour** opens from the Fill/Stroke tile as an anchored popover: Fill/Stroke target, palette swatches (the active palette or 12 defaults; a swatch keeps the current alpha), an alpha slider, Inherit (with a selection) and "Custom…", which swaps to the Colour page (global picker incl. eyedropper) in the same frame. There is no Done: edits apply live, leaving the page commits once, and an untouched page cancels.

#### Limits as implemented (`PropertyLimits`)

| Property | Slider / ladder | Typed range | Hard cap |
|---|---|---|---|
| Font size (px) | ladder 8–300 | 8–1000 | 8–1000 |
| Relative run size (%) | 10–400 | 1–1000 | 1–1000 |
| Tracking / manual spacing (1/1000 em) | −100…+100 | −500…+500 | ±1000 |
| Leading (px) | ladder 0, 8–300 | 0–2000 | ±2000 |
| Horizontal / vertical glyph scale (%) | 10–400 | 1–1000 | 1–1000 |
| Baseline shift (px) | −100…+100 | −1000…+1000 | ±1000 |
| Stroke width (px) / selection delta | 0–50 / −50…+50 | 0–200 / ±200 | 0–200 / ±200 |
| Indents L/R, space before/after (px) | 0–100 | 0–500 | 0–500 |
| First-line indent (px) | −100…+100 | −500…+500 | ±500 |
| Box width (px) | 0–2000 | 0–20000 | 0–20000 |
| Opacity / colour alpha (%) | 0–100 | 0–100 | 0–100 |
| Layer scale (%) | log ruler 0.05–10000 | ±50000 | ±50000 |
| Position (px) | pad, unbounded | ±10 × composition size | ±10 × composition size |
| Rotation (°) | dial, never wraps | ±36000 (±100 turns) | ±36000 |

Extreme-value audit: SDF glyphs rasterise at a fixed 48 ppem with spread 8 (`font_manager.h`), so display size and layer scale never grow the atlas or the glyph cache; stroke and softness are bounded by `TextSdfLimits` (10% of size). Layer matrices, hit testing and anchor compensation stay finite at ±500× and ±100 turns (`ExtremeValuesTest`). Layout bounds are floats scaled from the measured layout, with no integer conversion.

#### Scale ruler (Transform)

`LogScaleRuler` replaces the linear ruler (0.8dp per 1%, clamped 5%–1000%).

- **Look:** a rounded `SurfaceHigh` strip with a hairline border and a fixed bright centre needle with a notch; ticks scroll under it. Majors at 1/2/5 × 10ⁿ % are labelled (0.05 … 10k); minors sit at the other integer multiples; 100% is taller and brighter; ticks fade and shrink towards both edges. A large monospace readout sits above (`175%`): tap to type (anchored entry, ±50000%), double-tap to reset to 100%. The link toggle sits at its right.
- **Mapping:** `scale = base · 2^(dx / 96dp)`, relative to the value at drag start, with a velocity gain from 1× (≤ 400dp/s) to 2.5× (≥ 2400dp/s). Range 0.05%–10000%; typed values beyond it pin the needle to the end, and a negative scale keeps its sign while the ruler moves its magnitude.
- **Detents:** a light tick crossing 25/50/100/200/400…, a strong tick crossing or landing on 100%, and a soft snap of ±0.03 doublings (≈ ±2%) around 100% that a continued drag passes through.
- **Inertia:** flings above 600dp/s continue (τ = 300ms) and stop on touch; one undo per drag, inertia included.
- **Link:** linked mode keeps the X:Y ratio captured at drag start (Y now within the ±500× hard cap); unlinked shows Width and Height rulers.
- **Drawing:** a single `Canvas`; tick geometry and label layouts are prepared once, so nothing is allocated per frame. A `@Preview` is included. The W/H typed boxes are replaced by the ruler readouts; Flip H/V stays in the layer menu.

#### Deviations

1. **Shared heights are 308/344dp, not 268/328.** The numbers are above. All pages, the preview and the timeline scale identically; Edit Text and Transform have the same height.
2. **No drag handle on Edit Text.** The single header row replaces the parent chrome (handle, name row, section tabs), so swipe-to-dismiss is unavailable on text pages; `←` / Back navigate.
3. **The font size typed minimum is 8px, not 1px.** `TextLimits.MIN_SIZE` is provisional pending the small-size acceptance, so typing 1–7 clamps to 8. The maximum was raised in the model (500 → 1000px). Decoding clamps, so older files are unaffected.
4. **Underline and Strikethrough are disabled tiles** with a stated reason: the document has no such run fields and this pass forbids schema changes.
5. **Small caps and Kerning** (existing capabilities) sit beside the 3×3 style grid behind a hairline; the grid itself is exactly the nine requested tiles.
6. **"Split ligatures at nonzero tracking"** stays the fixed renderer rule; there is no model flag to toggle. The Ligatures tile's state reads "On, split while tracking is not zero".
7. **Limits rows:** Leading's typed range is 0–2000 per the table, but its hard cap is ±2000 so existing negative leading is preserved rather than silently rewritten. First-line indent keeps negative (hanging) indents, ±500. Relative run size, stroke delta and box width were added as rows (they were not in the requested table).
8. **Hyphenation** has no model field and is not shown. The Paragraph point/box toggle sets point (width 0) or box (fill composition width).
9. **The feedback strip is removed.** Errors and messages appear behind a ⚠ header icon (messages auto-clear after 5s); the stroke clamp line appears inside the Stroke width popover.
10. **Source text** is typed in an anchored editor lifted above the IME. Tap outside, Back or keyboard dismissal commits; hardware Escape cancels. Back does not discard typed text, unlike numeric fields where Back cancels.
11. **Imported font names** use UI text (no font parsing in Compose); bundled names render in their own face.
12. **Explode Text stays a centred dialog** (the existing dialog, unchanged).
13. **Transform value boxes** (Position X/Y, rotation, opacity) keep their boxes but type through the anchored entry popover, so the keyboard never resizes the editor.

#### Tests

These JVM tests were updated because the UI structure they test legitimately changed:

- `TextInspectorChromeTest`: Back signature, shared heights, icon list without Done/−/+.
- `TextInspectorFaceTest`: new tab inventory, widths, labelled fallback.
- `TextInspectorStateTest`: the scrub-acceleration test is replaced by linked-scale/reset tests; the numeric-mode restore by colour/source/fonts restore; caps now come from `PropertyLimits`.

New tests:

- `InspectorLimitsTest`: every limits row, ladder snapping, soft snap/detents, typed parse/clamp, typed commit/cancel, popover placement and clamping.
- `ScaleRulerMathTest`: round trip, range ends, base-relative drag, 100% snap, detents, gain, reach, inertia, linked ratio, ticks.
- `TextFocusNavigationTest`: focus owner; Prev/Next via `EditorViewModel.navigateBoundary`.
- `ExtremeValuesTest`: size 1000, ±500× scale, ±100 turns.

The instrumented `TextInspectorLayoutTest` was rewritten for the shared frame (it compiles; it has not been run).

**Gate:** `text-ui-5-gate.log`: app Kotlin Debug and Release, instrumentation-source compilation and **291** JVM tests (270 previous + 21 new), zero failures, errors or skips. The first run failed one new test (`tileCaptionsStayTiny`: the caption printed `1000` instead of `1k`), which was fixed before the passing run. No APK packaging, device run or host/native change (the native text code was read, not modified). Existing Gradle-option and pre-existing safe-call warnings remain.

#### Exact A16 user-run list

1. Run the JVM inspector tests and `TextInspectorLayoutTest` from Android Studio.
2. **Sheet height identical to Transform:** select a text layer, open Transform and note the sheet top; open Edit Text: same top, same preview and timeline size, in both preview sizes (308 / 344dp) and after toggling Preview Expansion while Edit Text is open.
3. **The keyboard does not shrink the preview:** tap the text field, a numeric value in a popover, a Transform X/Y box, the scale readout and the Fonts search. The preview, action bar, timeline and sheet must not move or resize; only the editor/popover sits above the keyboard, and the edited layer stays visible and unscaled.
4. **Popover anchoring at screen edges:** open popovers for the leftmost (Tracking, Fill/Stroke) and rightmost (Baseline, Space after) tiles, the Size tile in the bottom bar and the header ⋮ popovers. Each sits directly above its tile (below if there is no room), fully on screen with ~8dp margins, scales out from the tile, and closes on an outside tap and on Back.
5. **Typing in a popover:** tap the value and confirm everything is selected; type and press IME Done (commits, one undo). Repeat and tap outside (commits). Repeat and press Back (the keyboard hides and the value is unchanged). Hardware Escape cancels.
6. **Font ladder haptics:** drag the Size slider slowly across the range: one tick per rung (8…300), snapping to rungs. Typed 70 and 1000 are kept; reset returns to 72 (or 100% with a selection). Repeat with system touch feedback off: no haptics.
7. **Other sliders:** Tracking, Leading, H/V scale with Link on and off, Baseline, Stroke width (clamp note), indents, spacing, Box width (+ Auto/Fill), Manual kerning, Relative size and colour alpha: soft snap at 0/default, ticks at detents, one undo per drag, live preview.
8. **Scale ruler:**
   - Feel the strong tick and soft snap at 100%, and drag through it; light ticks at 25/50/200/400.
   - 10000% is reachable in one or two quick drags; a fling continues briefly and stops when touched.
   - Double-tap the readout to reset to 100%; type 50000 and −200 (the needle pins to the end / moves the magnitude).
   - Linked and unlinked rulers; one undo per drag.
9. **Rotation 5+ turns without typing:** keep circling the dial past 1800°; the value never wraps or clamps. Typing `7x90` gives 2610°.
10. **Fonts page two-column navigation:**
    - Search; choose Default (the right column shows the layer base fonts).
    - Bundled families (names in their own face) and styles; imported family faces and named instances.
    - Axis tiles with slider popovers.
    - Import, delete with inline confirmation, the missing-font banner; `←` returns to the tab.
11. **Colour popover from the icon:**
    - It opens anchored on the Fill/Stroke tile.
    - Switch target, tap swatches (alpha kept), drag alpha, Inherit with a selection.
    - Custom… opens the Colour page in the same frame (the eyedropper works).
    - `←` commits once; an untouched page leaves no undo entry.
12. **`←` and system Back:** popover → sub-page → tab → landing grid, one step per press. Start from each of: a slider popover, a typed value, the Justify and Layer base rows, the colour popover, the Colour page, the Fonts page with an axis popover open, Source typing, the reset-animation confirmation and the Explode dialog.
13. **Rail and focus:**
    - Diamond states (off / between keys / on key; squares for Source Text, fill-over-stroke, ligatures) follow the focused property.
    - Check the property-options ⋮ items; there is no Prev/Next, ✓ or 3D in the text rail.
    - Touch Tracking, then press the action bar's Next keyframe: it goes to the next Tracking key. Open the Size popover: Prev/Next follow Size.
14. **Large font scale (1.3 and 2.0):** labelled 3-column grid, all tiles present, `n/m` paging of whole rows, nothing scrolls or overlaps, popovers stay on screen.
15. **Regression:** the selection scope chip in the text field (× clears), Reset {tab} style is one undo, Explode, the Direction RTL message, preview move/box handle, save/reopen, the Transform position pad and anchor mode, Opacity, Timing.

### TEXT UI 6: fit layout, icon family, compact rail, colour popover picker, ruler fix, SDF tiers

Device feedback on TEXT UI 5 (A16): the keyboard, popovers, 10000% scale reach, rotation and the Fonts page are good; everything below still needed work. Implemented in source in one commit, **device-unverified**. Scope rules held: no change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract (`textLayer`/`flush`/`finish` arguments are unchanged; two tier calls were added before preparation) or the document schema. Renderer/atlas internals changed only for §8. The Fonts page is untouched apart from its three icons now coming from the shared family.

#### 1. Sheet height: back to 268 / 328dp

- `InspectorSheetBudget` is 268dp (expanded preview) / 328dp (standard) for **every** page, font-scaled exactly as before TEXT UI 5. The `EditorScreen` clamp is again applied only to text layers, as originally, so preview and timeline sizing match pre-TEXT UI 5 behaviour.
- Edit Text budget: frame 16 + header 40 = 56, so the body is **212dp at 268** and **272dp at 328**. It fits by using smaller tiles, not by raising the height.

#### 2. Edit Text layout (positions and heights)

Geometry comes from `TextInspectorBudget.forBody` (`TextFaceMetrics`), which is the same arithmetic the composable lays out.

**Vertical layout:** body = top + 8 + middle (3 tiles + 2 × 4) + 8 + bottom (1 tile). Each 8dp group gap holds a 1dp hairline at its centre. Half of each gap belongs to each neighbouring band, so every row is a full touch target.

**Horizontal layout:** the content is 6 equal cells: two 3-column blocks plus a 4dp spacer. Every touch target is a full cell (tile + gap). Tiles are drawn inset by 2dp, so the visual gap is 4dp inside a block and 8dp between blocks. Block edges, bottom-bar edges and the top row share the same columns.

| | Compact 268 | Standard 328 |
|---|---|---|
| Body | 212 | 272 |
| Tile height / in-grid gap / group gap | 36 / 4 / 8 | 44 / 4 / 8 |
| Top row | 44: Fill/Stroke tile 28 + switch 16 stacked, no gap; Explode pill 28, vertically centred | 72: tile 44 + 4 + switch 24 |
| Middle (two 3×3 blocks) | 3 × 36 + 2 × 4 = 116 (row touch targets 42 / 40 / 42) | 3 × 44 + 2 × 4 = 140 (50 / 48 / 50) |
| Bottom bar | 36 (touch 40) | 44 (touch 48) |
| Cell width at 360dp (content 292) | 48 (tile 44) | 48 (tile 44) |

If the top row cannot hold a 28dp tile with a 16dp switch under it, the switch moves to the right of the tile (`switchBeside`). That never happens at 268 or 328 with font scale 1. Above font scale 1.2 the page uses the labelled 3-column reflow: 48dp rows, whole rows paged by an `n/m` button above the same bottom bar, all inside the font-scaled 268/328 sheet.

**Character tab**

| Position | Content |
|---|---|
| Top, left block | **Explode Text pill** (rounded, `SurfaceHigh`, icon + "Explode Text", 28dp; opens the existing Explode dialog) |
| Top, right block | **Fill/Stroke dual-colour tile** (block width × 28/44dp) with the **Fill over stroke** switch directly under it (custom 28×16dp, `Role.Switch`, full contentDescription and state) |
| Left block | Stroke width · Tracking · Leading / Horizontal scale · Link (uniform H/V) · Vertical scale / Baseline shift · Kerning (Metrics/Off). The last row is centred, and the spare slot stays empty. |
| Right block | Bold · Italic · Underline / Strikethrough · Superscript · Subscript / All caps · Lowercase · Ligatures |
| Bottom bar | Font chip (2 cells; icon + truncated family; opens Fonts) · Size (ladder popover) · inline **text content** field (3 cells, the right block's edges; selection chip inside) |

**Paragraph tab** (same skeleton)

| Position | Content |
|---|---|
| Top row (full width, 4 cells) | Align left · centre · right · Justify (choice popover as before) |
| Left block | Left indent · Right indent · First-line indent / Space before · Space after (last row centred) |
| Right block | Point/Box toggle · Box width · Direction |
| Bottom bar | Same as Character |

**Removed:** the Small caps tile and control (`TextControl.SMALL_CAPS` is gone; the model and renderer keep `TextCaps.SMALL`), the header ⋮, the Colour page, the "Custom…" entry, and the Canvas dual-colour icon (it is now a Box swatch with real colours). The field above the preview is still the **layer name** and is not merged with the text field.

#### 4. Colour popover (picker inside the popover)

- The footprint stays a ~288dp-wide anchored popover from the Fill/Stroke tile (placement clamps to the window with 8dp margins). It has no second page.
- **Row 1 (40dp):** Fill / Stroke target toggle (left) · eyedropper · Inherit (only with a selection) · Reset.
- **Row 2 (96dp):** the saturation/value square (96dp) and hue bar (16dp) are the global picker's own `SaturationValueBox` and `HueStrip` with the `FluxxColor` model. The colour maths is not forked, and hue/saturation survive greys and black. On the right:
  - preview swatch + **hex value** (tap to type in an anchored `InspectorValueField`; RRGGBB keeps the alpha, AARRGGBB replaces it, invalid text applies nothing);
  - **alpha slider** + tappable **%** value (same entry and IME-overlay rules).
- **Row 3 (40dp):** one strip of swatches (active palette or the 12 defaults; a swatch keeps the current alpha). It scrolls horizontally.
- R/G/B sliders are not in the popover (they stay in the global picker).
- Edits apply live, with **one undo per interaction** (`TextColourSession`): one SV/hue/alpha drag, one swatch, one typed hex/alpha, one eyedropper sample, or one reset. There is no Done; Back or an outside tap closes the popover, and an open drag is cancelled.

#### 5–6. Rail and menus

- **`InspectorUtilityRail`** (every page) is a tight top-aligned stack of 40dp tiles with 6dp gaps: Keyframe diamond, Graph (disabled), 3D (disabled; only on pages that already showed it, now after Graph), property-options ⋮. Its top edge lines up with the first content row.
  - On Transform/Opacity at 268 the 4-item stack (178dp) does not fit the 158dp content area. Only the two disabled placeholders shrink (to 30dp); the diamond and ⋮ stay 40dp targets.
- **Header:** `←` | Character | Paragraph | Animate, plus ⚠ only when there is an error or message. There is no ⋮.
- **Rail ⋮ is the page's only overflow menu**, in this order:
  1. Reset {property} value
  2. Enable animation / Freeze at playhead
  3. Reset animation… (anchored confirmation)
  4. Hold (discrete) or easing presets
  5. Reset {Character|Paragraph} style · scope (one undo)
  6. Manual spacing… (slider popover anchored on the ⋮; Character tab)
- Relative size is **omitted**: with a selection the Size tile already edits relative run size, and resetting run factors is covered by Reset style.
- **Back** (`TextInspectorBack`): Explode → confirmation → popover (Layer base row → Justify first) → Source commit → Fonts page → landing grid.

#### 7. Icon family (`InspectorIcons`)

**Spec:**
- Path-based `ImageVector`s on a 24×24 grid, built once and cached.
- **One stroke of 1.5 units** (it scales with the icon), `StrokeCap.Round` and `StrokeJoin.Round` everywhere.
- Boxes are rounded rectangles (radius ≥ 2 units, built with arcs), and polygons (diamonds, triangles, the cube, play) have rounded vertices.
- Arrowheads are open chevrons (polylines). Letters are simple geometric strokes.
- Only state glyphs are filled: `FILL`/`PAINT` swatch, `KEY_ON`, `HOLD_ON`.
- Drawn size is 22dp in tiles and buttons, 18dp beside text.
- `IconPath` builds the data; OUT/Redo/right/RTL variants are exact x-mirrors.

**Enforced by `InspectorIconFamilyTest`:**
- stroke width, cap and join on every path;
- hollow-unless-state;
- no Canvas-drawn `*Icon` function in `ui/inspector` or `EditorActionBar.kt`;
- no Z-closed shape without curves;
- pairwise distinct glyphs except the declared aliases;
- every face control, category, clip action and rail item resolves to a family icon.

| Icons | Old source | New |
|---|---|---|
| Text controls: FONT, AXIS, SIZE, FILL/PAINT, STROKE, STROKE_WIDTH, TRACKING, KERNING, LEADING, BASELINE, H_SCALE, V_SCALE, BOLD, ITALIC, UNDERLINE, STRIKE, SUPER, SUB, ALL_CAPS, LOWERCASE, LIGATURES, ORDER, SCALE_LINK/LINK_OFF, EXPLODE, SOURCE | `TextInspectorIcons` path strings: angular letterforms, sharp Z-closed boxes, filled A/B, 1.4 stroke | `InspectorIcons` (rounded letters and boxes, hollow; FILL is the only filled one) |
| Paragraph: alignment ×7, INDENT_LEFT/RIGHT/FIRST, BEFORE, AFTER, POINT, AREA, BOX, LTR/RTL | `TextInspectorIcons` (no round caps on alignments, sharp boxes) | `InspectorIcons` + `alignmentPath` |
| Popover and page chrome: BACK, NEXT, CLOSE/CANCEL, RESET, INHERIT, SEARCH, IMPORT, DELETE, WARNING/MISSING, EYEDROPPER (new) | `TextInspectorIcons` (sharp warning triangle and trash) and the global picker's Canvas pipette | `InspectorIcons` |
| Rail: KEY, KEY_ANIM, KEY_ON, HOLD_ANIM, HOLD_ON, GRAPH, CUBE (3D), OVERFLOW (⋮) | `KeyframeVectorIcon`/`GraphVectorIcon`/`OverflowDotsVectorIcon` (Canvas, horizontal dots), `InspectorCategoryIcon(cube=true)` (Canvas, sharp hexagon), `TextInspectorIcons` diamonds | `InspectorIcons` (rounded diamond/hexagon, vertical ⋮) |
| Landing grid: TRANSFORM, TIMING, BLENDING_OPACITY, EDIT_TEXT, EDIT_SOLID, EDIT_FOOTAGE (new), EFFECTS, LAYER_STYLES, TRACK_MATTES (sharp rect), MOTION_BLUR | `InspectorCategoryIcon` Canvas, 1.3dp | `InspectorCategoryIcon` → `Icon(InspectorIcons…)` |
| Transform: POSITION, ROTATION, SCALE (filled arrowheads when active), ANCHOR, LINK/LINK_OFF | `Position/Rotation/ScaleSubModeIcon`, `AnchorSubModeIcon`, `LinkVectorIcon` (Canvas) | `InspectorIcons`; active is shown by tint only. The anchor-cell marker is a Box frame + dot. |
| Sheet header: back chevron, DUPLICATE, DELETE_LAYER, AUDIO | Inline Canvas chevron, `DuplicateVectorIcon`, `DeleteVectorIcon`, `AudioVectorIcon` (Canvas) | `InspectorIcons` |
| Clip quick actions: MOVE_IN/OUT, EXTEND_IN/OUT, TRIM_IN/OUT, SPLIT | `ClipQuickVectorIcon` (Canvas, 1.3dp) | `InspectorIcons` (OUT = mirror) |
| Editor action bar: UNDO, REDO, PREV_KEY, NEXT_KEY, PLAY, PAUSE, LAYER, PASTE, SELECT_ALL | Canvas, 1.3–1.6dp, sharp triangles, alpha-faded back cards | `InspectorIcons` (open chevrons, rounded play) |
| Deleted (unused) | `CloseVectorIcon`, `TimingVectorIcon`, `TransformVectorIcon`, `ColorPaletteVectorIcon`, `OpacityVectorIcon`, `BackChevronVectorIcon`; glyphs DONE, MINUS, PLUS, SMALL, ACTIONS, PREV, RELATIVE_SIZE, MANUAL_KERNING | removed |

Deliberate aliases: FILL = PAINT, CANCEL = CLOSE, DIRECTION = LTR, MISSING = WARNING, LINK = SCALE_LINK, DELETE = DELETE_LAYER. Meanings are unchanged.

#### 8. SDF atlas tiers

**Tiers and spread**

| Tier (ppem) | Spread | Encodable distance | Sharp up to (≤ 1.5× magnification) |
|---|---|---|---|
| 48 | 8 | 0.167 em | 72 px/em |
| 96 | 16 | 0.167 em | 144 px/em |
| 192 | 32 | 0.167 em | 288 px/em |
| 384 | 32 (FreeType's limit) | 0.083 em | 576 px/em |

The shader is unchanged and its `fwidth` smoothing adapts per texel. Base-encoded distances (half stroke, softness, faux bold, animator stroke and blur) are multiplied by `tiers::distanceScale(ppem)`: 1 for tiers up to 192, 2 for 384. Edges therefore look identical across tiers.

**Selection** (`SdfAtlasTiers.select`, per text layer, every frame):
- Input: pixels per em = font size × max run scale (relative size × max(H, V) scale) × largest per-glyph animator scale (from the modifier buffer) × layer scale (× canvas projection) × output scale.
- Preview uses the preview target/composition ratio; export uses 1, since it renders at composition size.
- **Hysteresis is 15%:** move up only above a tier's limit × 1.15, move down only below the lower tier's limit × 0.85.
- A tier is skipped if its spread cannot encode the layer's stroke + softness + bold + 0.01 em. For example, a 0.1 em half stroke caps the layer at 192.
- Inactive layers use 48.

**Generation:**
- One R8 texture array holds every tier, keyed by (font, glyph, ppem). Each `SdfGlyph` carries its ppem and spread, and the mesh sizes quads from them.
- Tier glyphs are rasterised by a second bundled-font `FontManager` (`acquireTierCatalog`) with its own FreeType library and lock, on a worker thread. The render thread never waits on them.
- **Preview fallback:** the highest tier whose glyphs are all ready is used (`tiers::resolve`; 48 is always available), so nothing flickers. When the sharper tier is ready, the next preparation re-collects (`needsCollect`).
- After a settled preview frame (not streaming playback), `renderPreview` waits up to 1.5s (cancellable by any new request) and redraws once.
- **Export** generates tier glyphs inline (deterministic).

**Memory cap (all tiers combined):**
- Device atlas: 6 × 4 MiB pages = **24 MiB** (low-RAM: 3 pages = 12 MiB).
- Host staging buffer: the same size, during uploads.
- CPU tier-glyph cache: **16 MiB** (8 MiB low-RAM).
- **When the atlas cannot pack:**
  1. Glyphs not needed this frame are evicted, highest tier first, then least recently used.
  2. If that is not enough, the layouts on the highest tier step down one tier until they fit (the cap holds until their wanted tier changes).
  3. Only a base-tier overflow raises the existing capacity error.
- Kotlin pre-degrades with the same order using a byte estimate.
- The CPU cache evicts in the same order and never drops a glyph touched this frame.

**Limits (documented, not hidden):**
- Above 576 px/em the 384 tier is magnified. Corner rounding is about em/384: 2.6 px at 1000 px, 13 px for 100 px text at 50× (5000 px/em). That is 0.26% of the em, versus 2% at 48 ppem. No MSDF in this pass.
- Imported-font text and per-character animation catalogue glyphs stay at 48 ppem, because the isolated font worker produces their bitmaps at the base tier.
- Tier choice is per layer (its largest glyph scale), not per glyph.

#### 9. Scale ruler

- **Invisible ruler fixed:** the strip used `weight(1f, fill = false).heightIn(40, 56)` inside a column with no remaining height and measured to zero. Each ruler is now a fixed readout row (24dp × font scale), a 4dp gap and a fixed **48dp strip**, with no weights.
  - Linked: 76dp. Unlinked Width + Height: 156dp.
  - That fits the Transform content area: 158dp at 268, 200 at 328, 215.6 at 328 × 1.3, 392 at × 2.
  - `RailAndRulerLayoutTest` checks every combination; there are two `@Preview`s, including the unlinked pair in a 158dp box.
- The link toggle sits in the readout row (40 × 24dp).
- **Direction:** the tape follows the finger. `travel = −dx · gain / 96dp`, and inertia uses the same sign, so dragging right decreases the value and dragging left increases it.
- Unchanged: log mapping, gain, inertia, the 100% snap and detents, ±50000% typed, the 10000% ruler maximum, and Flip in the layer menu.

#### 10. Other inspector pages

- Name row: 40dp in compact sheets, 48dp otherwise; duplicate/delete targets follow it (they were fixed at 48dp). This gives Transform/Opacity 158dp of content at 268.
- Section tab padding is now the `InspectorChromeBudget` constants, so content heights are arithmetic rather than estimates.
- Rail-to-content gap is 4dp on Transform, Opacity and Edit Text (Opacity used 8).
- The scale ruler fix above.
- Shared icons on Transform, the landing grid, the quick action bar, the action bar and the Edit Solid picker's eyedropper.
- Timing and Audio needed no change (no rail; 2–3 slider rows fit 158dp).

#### Deviations

1. **One shared texture array for all tiers** instead of one lazily created array set per tier. It keeps a single descriptor, pipeline and draw call per layer, so the batch/draw contract is unchanged, and makes the combined memory cap a single number. Per-tier LRU is the eviction order (tier, then last use).
2. **384 ppem keeps spread 32** (FreeType's maximum), so its relative distance range is half that of the other tiers. Distances are rescaled ×2, and layers whose stroke/bold needs more range stay at 192.
3. **Imported fonts and per-character catalogue glyphs stay at 48 ppem**, and **tier selection is per layer**, using the largest animated glyph scale. See Limits.
4. **Preview upgrade without touching `PreviewController`:** a settled frame waits up to 1.5s (cancellable) and redraws once. During streaming playback the sharper glyphs appear in later frames instead of stalling playback.
5. Atlas pages rose from 4 to 6 (2 to 3 on low-RAM) so tiers have room. The cap is stated above.
6. **Paragraph middle:** both blocks have at most 2 rows, so the rows are centred vertically in the middle band rather than leaving an empty third row.
7. **Rail on Transform/Opacity at 268:** the disabled Graph/3D placeholders shrink to 30dp, because 4 × 40 + 18 = 178 > 158. Interactive items stay 40dp.
8. **Relative size** is not in the rail menu (see §5–6).
9. **Swatch strip:** 28dp swatches in a horizontally scrolling strip. Twelve swatches at a 40dp pitch do not fit 288dp, and the popover footprint was kept.
10. The **Fill over stroke** switch and the scale **link** toggle have a 16–24dp tall touch area (the spec allows a small switch). Everything else is ≥ 40dp.
11. Icons are drawn at 22dp in tiles and buttons and 18dp inline beside text (chips, pill, text field).
12. The colour **eyedropper closes the popover** first so the preview can be tapped. The sample applies as one undo.
13. `TextFontsPopup.kt`: only the icon object changed (`TextInspectorIcons` → `InspectorIcons`); its layout and behaviour are untouched.
14. Tests whose structure changed:
    - `TextInspectorChromeTest`: no Colour page; header menu → rail menu; Explode pill; heights 268/328.
    - `TextInspectorFaceTest`: new face structure and arithmetic.
    - `TextInspectorStateTest`: the Colour-mode codec case was removed.
    - `ScaleRulerMathTest`: drag/inertia sign.
    - androidTest `TextInspectorLayoutTest`: new tags, body 212/272.

#### Gate

- Compiles: `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, `:core-engine:compileDebugKotlin`, and the native `:core-engine:externalNativeBuildDebug` and `externalNativeBuildRelease`.
- JVM: `:app:testDebugUnitTest` ran **316 tests (291 previous + 25 new)**, with 0 failures, errors or skips.
  - New suites: `InspectorIconFamilyTest` 5, `SdfAtlasTiersTest` 8, `TextColourPopoverTest` 6, `RailAndRulerLayoutTest` 4. `TextInspectorFaceTest` went from 8 to 10.
- No APK packaging and no device run. One pre-existing safe-call warning in `CompositionRenderer` remains.

#### Exact A16 test list

1. **Sheet height identical to the original on every page.** Compare against TEXT UI 4 screenshots or measurements: the sheet top is the same on the landing grid, Transform, Timing, Blending & Opacity, Edit Text and Edit Solid. The preview and timeline match pre-TEXT UI 5 sizing in both preview sizes (268 / 328dp) and after toggling Preview Expansion.
2. **Character tab:**
   - The top row shows the Explode Text pill (left) and the Fill/Stroke tile with the Fill-over-stroke switch directly under it (right).
   - Two 3×3 blocks: Baseline and Kerning are centred in the last left row, and there is no filler tile.
   - The bottom bar has the font chip, Size and the text field on the same column edges.
   - There are no empty or crowded areas at 268 and 328, and every tile responds to a tap anywhere in its cell.
3. **Paragraph tab:** alignment row on top, indents/spacing (left) and Point/Box · Box width · Direction (right) centred in the middle band, then the same bottom bar. There is no Explode pill. Small caps appears on neither tab.
4. **Rail is compact on every page:**
   - Diamond, Graph, (3D), ⋮ are stacked at the top with 6dp gaps, and the top lines up with the first row of content.
   - At 268 on Transform/Opacity the Graph/3D placeholders are shorter but the diamond and ⋮ are full size.
5. **Rail ⋮ on Edit Text:**
   - Items: Reset {property} value, Enable/Freeze, Reset animation…, Hold/easing, Reset {tab} style · scope, Manual spacing…
   - Manual spacing opens a slider popover anchored on the ⋮.
   - There is no ⋮ in the header; ⚠ appears only with an error.
6. **Explode pill** opens the Explode dialog. Back closes it first.
7. **Colour popover, everything without leaving the popover:**
   - Switch Fill/Stroke.
   - Drag the SV square and the hue bar (hue survives dragging to grey or black).
   - Drag alpha, and tap the % to type a value.
   - Tap the hex to type RRGGBB and AARRGGBB; invalid text changes nothing.
   - Eyedropper: the popover closes, tap the preview, and the colour applies.
   - Tap swatches (scroll the strip), use Inherit with a selection, and Reset.
   - Each interaction is exactly one undo, there is no Done, and Back or an outside tap closes it.
   - At both screen edges it stays on screen with ~8dp margins.
8. **Scale ruler is visible at both heights** (268 and 328, font scale 1, 1.3 and 2), linked and unlinked: the strip has ticks, labels and the needle.
   - Dragging **right decreases** the value and dragging **left increases** it; flings continue in the same direction.
   - Unchanged: 100% snap and detents, 10000% reach, typed ±50000%, link toggle in the readout row.
9. **Icons are consistent** across Transform (Position, Rotation, Scale, Anchor, link), Opacity, Timing, the landing grid, Edit Text (both tabs, popovers, Fonts page chrome), the rail, the sheet header (back, duplicate, delete, audio), the clip quick action bar and the editor action bar:
   - same stroke weight, rounded ends and corners, open chevrons;
   - filled only for the active key/hold and the fill swatch;
   - every icon still recognisable at 22dp.
10. **Text sharp at 300, 500 and 1000px** (Inter and Noto Serif, bold, with and without stroke), in preview at Full and Half quality and in an exported video. Corners should be crisp up to about 576 px/em on screen and only slightly soft at 1000px.
    - Watch the moment the sharper tier arrives: there must be no flicker, only a sharpening.
11. **Scale 5× and 50×** on 100px text, in preview and export: sharp at 5×. At 50×, corners are rounded by about em/384 (expected, see Limits).
    - With a stroke ≥ 0.15 × size the layer stays on the 192 tier, and the stroke must not clip.
12. **Hysteresis:** slowly scale a text layer through 72/144/288 px per em and back. Tiers change once per crossing; nothing pumps or flickers while scrubbing near a threshold.
13. **Memory and frame time while scrubbing large text:** open several 1000px text layers. Check with `textStats` (`tierGlyphs`, `tierBytes`, `tierPending`, `tierDegrades`, `atlasPages` ≤ 6) and the Android Studio profiler.
    - Atlas ≤ 24 MiB; no capacity error until even the base tier overflows; scrubbing stays smooth (tier generation is off the render thread).
14. **Imported font and per-character animation text** still render; they stay on the 48 tier by design.
15. **Regression:**
    - keyboard overlay (preview never shrinks), popover typing (Done, outside tap, Back, Escape), Size ladder haptics;
    - Fonts page unchanged; Back chain; Prev/Next following the focused property;
    - large-font labelled reflow paging; selection chip; save/reopen;
    - Transform position pad and anchor mode, rotation dial, Opacity slider, Timing dialogs.

### TEXT UI 7: single-row section headers, keyframe badges and layer markers, colour picker family, inline hex entry

TEXT UI 6 was device-tested on the A16; the Edit Text layout was accepted ("perfect") and is unchanged here. Implemented in source in one commit, **device-unverified**. Scope rules held: no change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract or the document schema. Sheet heights stay 268 / 328dp. Edit Text's layout, the Fonts page and the compact colour popover's look are unchanged (apart from the hex/alpha entry fix, §4e).

#### 1. Section-page header: one row

- Transform, Timing, Blending & Opacity and Edit Solid (and Audio for video layers) now have **one header row, exactly like Edit Text**: `←` | page tabs, `InspectorChromeBudget.header` = `TextInspectorBudget.header` = 40dp × font scale. The layer-name row, duplicate and delete are gone from these pages (`SectionHeader` in `ElementInspectorSheet.kt`).
- The **landing grid is unchanged**: drag handle, layer name with duplicate (hold: copy) and delete, audio toggle for video, quick actions, 4×2 tiles.
- `←` returns to the landing grid exactly as before. System Back is unchanged, because the sheet's back-stack code was not touched.
- Content height is now `sheet − 16 frame − header − 4`:

| Sheet × font scale | TEXT UI 6 content | TEXT UI 7 content | Grew by |
|---|---|---|---|
| 268 × 1 | 158 | **208** | 50 |
| 328 × 1 | 200 | **268** | 68 |
| 348.4 (268 × 1.3) | 215.6 | **276.4** | 60.8 |
| 426.4 (328 × 1.3) | 293.6 | **354.4** | 60.8 |
| 536 (268 × 2) | 392 | **436** | 44 |
| 656 (328 × 2) | 512 | **556** | 44 |

Every control size comes from `SectionLayout` arithmetic (in `TextInspectorState.kt`), not guesses. Touch rows are always ≥ 40dp.

- **Transform:**
  - X/Y value boxes are 40 / 48dp rows, with a larger monospace readout in standard sheets.
  - The position pad is `min(column, content − row − 8, 240)`: **160dp** at 268 (was about 118) and **212dp** at 328.
  - The rotation dial is `min(column, content, 240)`: **208dp** at 268 and **240dp** at 328.
  - The scale ruler uses a **32dp readout** (titleLarge) and a **64dp strip**, both linked and unlinked. Unlinked Width + Height takes 204 ≤ 208dp at 268.
  - The mode column is 48dp wide with 40 / 48dp tiles, top-aligned with the first content row. The rail is unchanged and now fits at full size at 268, so the Graph/3D placeholders no longer shrink.
- **Blending & Opacity:**
  - The blend-mode row is 40 / 48dp with 13sp text.
  - The opacity slider row is a 24dp label line (with the badge), then a **56dp touch row** holding an 8dp-thick track, a 24dp thumb and an 88 × 48dp value box.
  - The slider is `SectionSlider`: one undo per drag, as before.
- **Timing (and Audio):** each slider row is a label line plus a touch row of `(content − gaps) / rows − label`, clamped to 40–56dp.
  - Two rows give 56dp touch rows at both heights.
  - Video (three rows) gives 40dp at 268 and 56dp at 328.
  - Large rows get the thick track and a titleSmall value box.
  - Typed timing values still use their numeric dialog.
- **Edit Text:** it already had a single header, so it is unchanged (body 212 / 272dp).
- **Edit Solid:** the FULL colour picker (§4) gets the whole content area.

#### 2. Keyframe badge on property tiles

- **Look:** about a 10dp drawn diamond with a 3dp inset in the top-right corner (`KeyBadgeGlyph`).
  - It uses the shared `InspectorIcons` key glyphs: **hollow `KEY` means the property has keyframes; filled `KEY_ON` means there is a key exactly at the playhead.**
  - There is no badge without keys. It is drawn above the icon and below the caption, and never overlaps the caption (the caption is bottom-right).
  - It is monochrome, in the tile's ink.
- **Accessibility:** the tile's state gains "keyframes active" / "keyframe at playhead".
- **Source of truth:** `KeyBadges.of(layer, property, local)`, which reads `propertyKeyTimes`, the same model the rail diamond uses. No state is stored. It is derived per property at the snapped playhead (`remember(track, playhead)` on Transform/Opacity).
- **Edit Text:** the badge appears on:
  - Size;
  - Fill · Stroke (the stronger of the two states);
  - Stroke width, Tracking, Leading, Baseline, Fill-over-stroke order and Ligatures (each keys its own layer property);
  - the bottom-bar text field (Source Text, which carries keyed run and paragraph styles).
- **Transform:** on the Position / Rotation / Scale mode icons. There is none in Anchor mode, because the anchor is not animatable.
- **Opacity:** beside the slider label.
- **Timing:** no badge, because clip timing is not animatable.

#### 3. Keyframes on the layer (timeline)

- **Bug cause:** the timeline bar built its own key set from position/scale/rotation/opacity plus Source Text/Size/Fill only. Tracking, Leading, Baseline, Stroke colour, Stroke width, Fill-over-stroke, Ligatures and animator tracks never reached it.
- **Fix:** the bar now uses `LayerKeyMarkers.clipTimes` → `aggregateKeyTimes()`, which already covered every text property, Source Text (style-run and paragraph keys) and animators. All layer types and properties are covered.
- **Marker spec:**
  - Keys of the focused property on the selected layer are **full ink, 10dp**, and keep their existing tap / hold-drag behaviour.
  - Keys of every other property are **45% alpha, 7dp** and display only. Hit-testing is unchanged.
  - Keys at the same time collapse into one marker; if any belongs to the focused property it draws at full ink.
  - Markers sit along the bottom of the bar (2dp inset), below the centred layer name. The trim and move targets are unchanged.
- **Performance:** times are aggregated once per layer/keyframe/undo change (`remember(anim, text, offset)`). The draw reuses one `Path` and one `Stroke` per layer, so nothing is allocated per frame.

#### 4. Colour picker family

- **Shared component:** `FluxxColourPicker(variant = COMPACT | FULL, …)` in `ui/common/colorpicker/FluxxColourPicker.kt`.
  - It is built on `SaturationValueBox`, `HueStrip` and `FluxxColor`; the colour maths is not forked.
  - Shared by both variants: rounded 10dp containers; a target toggle that shows only when there are several targets; eyedropper, inherit and reset from `InspectorIcons`; SV square and hue bar; alpha; the swatch/palette area below the controls; live apply.
  - Each interaction (a drag, a typed-value session, a swatch) is **one `ColourTransaction`**, which means one undo.
  - The old `FluxxColorPicker`, `HexColorInput`, `ColorChannelSlider` and `PaletteRow` were deleted.
- **COMPACT:** exactly the TEXT UI 6 text popover: 288dp wide; a 40dp header; a 96dp SV square, 16dp hue bar, hex row and alpha row; a 40dp strip of 28dp swatches.
- **FULL:** the same order and language, scaled up.
  - Header (40), then an SV square up to 200dp, a 20dp hue bar, and a right column of five rows: hex, then A / R / G / B gradient sliders with in-place values.
  - The palette area is below:
    - the palette browser, with all existing management (select, create, rename, delete, edit swatches);
    - the active palette's swatches;
    - save-to-palette (new `PALETTES` / `SAVE_SWATCH` icons);
    - a "Recent" row when there is room.
  - Sizes come from `ColourPickerLayout.full`. Edit Solid at 268: square 122 with 24dp rows. At 328: square 182 with 36dp rows.
- **Surface → variant:**

| Surface | Variant |
|---|---|
| Edit Text Fill / Stroke popover | COMPACT (targets Fill/Stroke, inherit with a selection, eyedropper) |
| Marker colour (composition and layer markers) | COMPACT, anchored popover, marker swatches |
| Palette browser swatch editor | COMPACT, no swatches or eyedropper (prevents recursion) |
| Edit Solid page | FULL (palette, eyedropper) |
| Add content → Solid tab (global picker page) | FULL (palette, no eyedropper while creating) |

- **Marker colour:** the colour is now a swatch + hex control in the marker editor. It opens the COMPACT picker as an `AnchoredPopover` from that control: it opens from the trigger, is clamped to the window with 8dp margins, and is never centred. The colour is still staged until Add/Save (one undo, same persistence). The strip is the marker palette: red `F44336` (composition default), orange `FF9800` (layer default), then ten more.

#### 4e. In-place hex / alpha / RGB entry

- **Cause:** tapping hex or alpha opened an `InspectorValueField` inside a second popup nested in the picker popover. The nested popup lost focus and closed at once.
- **Fix:** `InlineValueField` (`ui/common/InlineValueField.kt`) edits the value **in place**, with no popup or dialog.
  - Focusing it selects all. Hex uses an ASCII IME (caps); alpha and R/G/B use a number IME.
  - When the field is cleared, the **previous value stays visible at 40% alpha** behind the empty input, and typing replaces it.
  - Leaving it empty (focus loss, IME Done, tap outside, Back after the keyboard hides) changes nothing.
  - Hex accepts `RRGGBB` (keeps alpha) or `AARRGGBB` (replaces alpha), with or without `#`, in any case. Alpha accepts 0–100 (an optional `%`); R/G/B accept 0–255.
  - A **valid complete** value applies live (begin on the first valid value, one preview per distinct value). Invalid or incomplete text applies nothing and reverts on blur. Escape cancels.
  - **One undo per edit session** (focus to blur), driven by `InlineEntrySession`.
- **IME:** inside a popover, the popover lifts above the keyboard as before. On a page (the Edit Solid FULL picker), the inspector sheet is translated above the keyboard while a field is focused (`ImeLiftHost`); nothing resizes.

#### 5. Ruler label near the needle

Tick labels fade with a smoothstep of the gap between the label's nearest edge and the needle: hidden when touching, fully shown from **12dp** away (`ScaleRulerMath.needleFade`). The ruler maths and behaviour are unchanged.

#### Deviations

1. **Swipe-to-dismiss on section pages** moved from the removed drag handle to the single header row (a vertical drag; taps still select tabs). The landing grid keeps its handle.
2. **Audio for video layers** is an extra tab in the section header, because its toggle lived in the removed name row. The toggle remains on the landing grid.
3. **Edit Text style and paragraph tiles** (bold, italic, case, alignment, indents and others) show no badge of their own. Their keys are Source Text keys, badged on the text field. Repeating one Source Text state on about 20 tiles would be noise.
4. Badges reflect the **layer property** (whole-layer owner) even while a selection is active, because run overrides are not separately keyed.
5. **Timing has no badges**: clip timing is not animatable.
6. **Recent colours are session-only** (in memory, newest first, 12), because there is no schema or persistence change. The Recent row appears only when FULL has ≥ 36dp value rows: yes on the Add Solid tab, no on Edit Solid at 268/328.
7. **Marker palette** is a fixed marker swatch list; no persisted marker palette existed. The marker editor itself (description, Add/Save/Delete) stays a dialog. Only the colour moved to an anchored popover.
8. The **Add content → Solid** page has no sheet-lift host. If the keyboard covers its hex field there, the field still edits in place. Only the inspector sheet lifts.
9. **Timing typed values** keep their numeric dialog; only colour values were required in place.
10. **FULL R/G/B/A tracks** draw real colours (the picker shows colours). All other picker chrome is monochrome.
11. **FULL at 268** uses 24dp value rows; touch targets are the full-width slider rows and the in-place fields. This was the trade-off for keeping the palette below the controls in the same order as COMPACT.
12. Opacity, Timing and Audio sliders are `SectionSlider` (thick track) instead of the M3 `Slider`. The begin / live preview / commit protocol and one undo per drag are unchanged.
13. **Tests whose structure changed:** only `RailAndRulerLayoutTest`.
    - Content 158/200 → 208/268.
    - The 4-item rail no longer shrinks at 268.
    - Ruler sizes now come from `SectionLayout`.
    - It gained pad/dial, slider-row and label-fade tests.
    - No other existing test was edited.

#### Gate

- Compiles: `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`. Native code was not touched.
- JVM: `:app:testDebugUnitTest` ran **343 tests (316 previous + 27 new)**, with 0 failures, errors or skips.
  - New suites: `KeyframeBadgeTest` 4, `LayerKeyMarkersTest` 5, `ColourPickerFamilyTest` 8, `InlineValueFieldTest` 7. `RailAndRulerLayoutTest` went from 4 to 7.
- No APK packaging and no device run.

#### Exact A16 test list

1. **Header row:** on Transform, Timing, Blending & Opacity, Edit Text and Edit Solid the header is a single row: `<` plus tabs only. There is no layer name, duplicate or delete.
   - `<` returns to the landing grid, and system Back behaves exactly as before.
   - Swiping down on the header row dismisses the sheet.
   - Video layers show an Audio tab.
2. **Landing grid unchanged:** handle, layer name (rename), duplicate (tap) / copy (hold), delete, audio toggle for video, quick actions, and the 4×2 tiles in the same arrangement.
3. **Freed height is used, at 268 and 328 and font scales 1 / 1.3 / 2:**
   - Transform: the position pad is visibly bigger, the rotation dial is bigger, the scale ruler has a 64dp strip and a large readout (linked, and unlinked Width + Height both fully visible), and the X/Y boxes are taller. The rail and mode column line up with the first content row, and the rail's Graph/3D are full size at 268.
   - Opacity: a large slider row (thick track, big thumb, big value box).
   - Timing: larger rows with no overflow (video has three rows).
   - Nothing is clipped.
4. **Badges:**
   - Add a keyframe to Size, Tracking, Leading, Baseline, Stroke width, Fill or Stroke, Fill-over-stroke, Ligatures and Source Text; and to Position, Rotation, Scale and Opacity.
   - The tile, mode icon or label shows a hollow diamond top-right, which is filled when the playhead is exactly on a key.
   - Move the playhead off and on a key: hollow ↔ filled. Remove the animation: the badge disappears.
   - The caption is never covered. TalkBack reads "keyframes active" / "keyframe at playhead".
5. **Timeline markers for Edit Text keys:** keying any Edit Text property (above, including Tracking / Leading / Stroke / Ligatures) shows a marker on that layer's timeline bar immediately. Undo removes it.
6. **Quiet markers:**
   - With several properties keyed, the focused property's keys are full size and bright and keep tap-to-seek and hold-drag.
   - Other properties' keys are visible but dimmed and smaller, and don't react to touch.
   - Shared times show one marker (bright if the focused property is among them).
   - Markers sit low on the bar and never cover the name or the trim handles. The same holds on solids, video and images.
7. **FULL picker matches the compact look:** on Edit Solid the header icons, rounded corners, SV + hue, hex row, A/R/G/B and the palette below match the compact picker's order and style.
   - Palette browser management, save swatch and eyedropper all work.
   - On Add content → Solid the Recent row shows recently applied colours.
8. **In-place hex and alpha** (text popover, marker popover, Edit Solid, Add Solid):
   - Tap hex: it edits in place and the popover does NOT close.
   - Clear it: the faded previous value shows. Leave it empty (outside tap, Done, Back): nothing changes.
   - Type `00FF00`: it applies live and keeps alpha. Type `80FF0000`: it replaces alpha. Invalid text reverts on blur.
   - The same applies to alpha % (0–100) and Edit Solid R/G/B (0–255).
   - Each edit session is exactly one undo.
   - The keyboard never hides the field: the popover or sheet lifts and the preview does not resize.
9. **Marker colour:** hold the playhead to add or edit a marker, then tap the colour control.
   - The compact picker opens anchored to the control, never centred, within 8dp of the screen edges. Try a marker near the left and right edges.
   - It shows the marker swatches. The colour is saved only with Add/Save, and undo/redo work as before.
10. **Edit Solid uses the FULL picker.** Dragging SV, hue or a channel is one undo per drag, and the eyedropper still samples.
11. **Ruler label fades near the needle:** drag the scale ruler slowly through 100%. The "100" label (and every other label) fades smoothly as it reaches the needle and reappears beyond about 12dp, with no pop. The value, detents and inertia are unchanged.
12. **Regression:**
    - Edit Text layout, Fonts page, rail menu, Explode, colour eyedropper;
    - keyboard overlay on Edit Text; scale 10000% reach and ±50000% typing; anchor mode;
    - Timing dialogs, Audio mute/gain; save and reopen.

### TEXT UI 8: marker popover, centred layer keyframes, consistent control sizing

TEXT UI 7 was device-tested on the A16 (user screenshots) and left three issues, all addressed here:

- The marker editor was a centred dialog that opened a second popover.
- Layer keyframes sat on the bottom edge of the bar.
- Section-page controls grew to fill the sheet and were too big.

Status: implemented in source in one commit, **device-unverified**.

Scope rules held:

- No change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract or the document schema.
- Sheet heights stay 268 / 328dp.
- Unchanged: the Edit Text layout, the Fonts page, the compact picker's look, the single-row section headers, the keyframe badges and the rail.

#### 1. Marker editor: one anchored popover

**What it is.** There is one `AnchoredPopover` (test tag `marker-popover`) and nothing else.

**Anchor.** It is hosted inside the marker glyph:

- **Composition markers:** a 9×12dp box over the ruler pentagon (`composition-marker-anchor`, y = 16–28dp of the ruler).
- **Layer markers:** the 8×7dp box of the orange pennant above the layer bar.
- **Fallback:** a retained layer marker outside its trimmed clip has no pennant. Holding the playhead on it anchors the popover to the playhead target (`MarkerEditing.drawnOnBar`), which is where that marker sits.

**Placement.** Placement is the shared `PopoverPlacer`:

- above the anchor when it fits, otherwise below;
- clamped to the window with 8dp margins;
- never centred;
- scales out from the anchor.

**IME.** The popover's bottom stays above the keyboard. Only the popover lifts and nothing else resizes. This is the same IME rule as the other popovers.

**Contents, top to bottom.** There are no Add, Save, Delete, X or close buttons.

1. **Description.** A single-line in-place field (40dp), 128 characters at most, with newlines stripped (the schema rule). Its placeholder reads "Composition marker description" or "Layer marker description".
2. **`FluxxColourPicker(variant = COMPACT)`.** It has the in-place hex and alpha fields, the SV square, the hue strip, the alpha slider and the `MARKER_SWATCHES` strip. There is no target toggle, because there is a single target.

**Closing.**

- Tapping outside closes the popover. Back also closes it, but hides the keyboard first.
- Closing **never discards**: `MarkerEditSession.close()` commits whatever session is in flight.

**Live apply and undo.**

- Each description keystroke and each colour move previews live. Each preview is `Batch([Edit{Composition|Layer}Marker])`, sent through the existing transient-gesture protocol (`beginGesture` / `previewGesture` / `commitGesture`, the same `Batch` wrapper that text edits already use).
- **One undo entry per session:**
  - A description session runs from focus to blur, IME Done or close.
  - A colour session is one picker interaction: one drag, one swatch, or one hex/alpha entry.
- Starting one kind of session commits the other.
- A cancelled pointer interaction reverts only that interaction. Closing the popover is not a cancel.

**Creating a marker.**

- Tapping the playhead where there is no marker dispatches the existing add action at the snapped playhead. That is one undo entry.
- The marker is then selected (as before), playback pauses, and the popover opens on it.
- The marker stays whether or not anything is entered; an empty description is allowed.
- Default colours are unchanged:
  - composition red `F44336`;
  - layer orange `FF9800`;
  - the solid-layer ink/amber rule.
- Holding the playhead where there is no marker does the same.

**Editing an existing marker.** Hold the playhead on it, the same gesture that opened the old dialog. This now opens the popover.

**Removal (unchanged).**

- Tapping the playhead while it is on a marker removes it.
- Tapping an already-parked ruler or layer flag removes it.
- No delete button was added.
- If the popover's marker disappears for any reason (removal, undo, layer deletion), the timeline closes the popover. The session is detached without committing, because the delete already cancelled its gesture.

**Removed:**

- `MarkerDialog.kt`: the centred `Dialog` with its title, X, Add/Save/Delete buttons and outlined text field.
- `MarkerColourControl`: the swatch-and-hex chip with its nested anchored picker.
- The staged-until-Save colour and description logic (`MarkerDraft`).

**Kept:** marker persistence, ordering, frame snapping, ID rules, the reducer actions and undo semantics.

**Code:** `ui/timeline/MarkerPopover.kt`:

- `MarkerRef`
- `MarkerEditing`: playhead tap/hold rules, `find`, `drawnOnBar`, `edit`, `sanitize`
- `MarkerGestureSink`
- `MarkerEditSession`
- `MarkerPopoverContent`

The timeline wiring is in `TimelineView` (`markerSession`, `markerPopover`).

#### 2. Layer-bar keyframes: vertically centred

**Position.** Markers sit on the bar's vertical midline:

- `LayerKeyMarkers.markerTop` / `markerCentreY` equal the bar centre for any bar height.
- In the timeline, the 48dp × row-height key box uses `Alignment.Center`. The bottom inset is gone.

**Unchanged:**

- Focused-property keys draw full ink at 10dp.
- Other keys draw at 45% alpha and 7dp.
- Keys at the same time collapse into one marker, full ink if the focused property is among them.

**Hit-testing.** It moves with the marker: the focused-property target is 48dp wide and the full row tall, centred on the key (`LayerKeyMarkers.hitRegion`). The trim and move handles are unchanged.

**Readability over the name.**

- Markers are composed after the clip body, which holds the name, so they draw above the text.
- Each diamond first gets a **knock-out ring** in the bar's own background colour (`clipBgColor`). The ring is a 3dp stroke centred on the path, which leaves about 1.5dp outside the diamond.
- The ring is drawn always (`KNOCKOUT_ALWAYS`): measuring glyph overlap per frame would cost more than one stroke.
- The name is never hidden or shortened, and markers are never hidden.

**Coverage and cost.** This works for every layer type, selected or not. Drawing stays allocation-free: one remembered `Path`, plus remembered `Stroke`s for the outline and the knock-out ring.

#### 3. Consistent control sizing on the section pages

**Rule.** Every control size is a constant in `SectionLayout`:

- It does not depend on the sheet height (268 vs 328) or on whether the preview is expanded.
- Rows that contain text grow with font scale only, by `SectionLayout.text(base, fs) = base + 20 × (fs − 1)`.
- The pad, the dial, the ruler strip, the tracks and the thumbs never grow.
- The leftover height becomes equal margins above and below the control group. The group is centred vertically in the content area, and horizontally between the rail and the mode column (`CentredSectionGroup`, `SectionLayout.centre`).

**Final control sizes** (dp, at font scale 1):

| Control | Size | Grows with font scale |
| --- | --- | --- |
| Position pad | 128 × 128 | no |
| X / Y value boxes | 40 high (two boxes, full column width, 8 gap) | yes (`valueBox`) |
| Position group | 40 + 8 + 128 = **176** | value row only |
| Rotation dial | 144 circle, value box inside 40 high (70% width) | value box only |
| Scale ruler, linked | readout 28 + gap 4 + strip 48 = **80** | readout only |
| Scale rulers, unlinked | 2 × 80 + 4 = **164** | readouts only |
| Ruler readout style | `titleMedium` (was `titleLarge` at 32dp) | — |
| Scale link toggle | 40 × 40 | — |
| Blend-mode row | 40 high | yes |
| Slider row (Opacity / Timing / Audio gain) | label line 20 + touch row 48; 6dp track, 20dp thumb, value box 72 × 40 | label and value box |
| Opacity group | 40 + 8 + 68 = **116** | text rows |
| Timing group | 2 × 68 = 136; video 3 × 68 = **204** (no extra gap between slider rows) | text rows |
| Audio mute row | 40 high (was 38) | yes |
| Transform mode tiles | 40 × 40, column 40 wide (was 48 × 40/48) | — |

Every touch target is at least 40dp.

**Centring.**

- **Transform:** the active mode's group is centred. The groups are:
  - readout + ruler;
  - both rulers as one unlinked group;
  - X/Y boxes + pad;
  - dial.

  On a 268 sheet at fs 1 the content is 208dp, so:

  | Group | Margin above and below |
  | --- | --- |
  | Linked ruler | 64dp |
  | Unlinked rulers | 22dp |
  | Position | 16dp |
  | Dial | 32dp |
- **Opacity, Timing and Audio:** centred the same way. Audio's group is measured rather than computed, because its info text has no fixed height.
- **Mode column:** stays top-aligned.
- **Fallback:** a group taller than its area is top-aligned and scrolls. It never overlaps and is never clipped. Because sheet heights scale with font scale, every group fits at 1, 1.3 and 2.

**Edit Solid FULL picker.**

- `ColourPickerLayout.full` keeps its square cap: `SQUARE_MAX` 200, or less when the width is narrow.
- The controls row is now exactly the square, instead of being stretched to the page.
- The picker (`FullPickerMetrics.height`) is centred vertically in any leftover height.
- The COMPACT variant is untouched. Its header row is only skipped when a caller passes `header = false` (the marker popover).

#### Deviations

1. **The marker popover's compact picker has no header row** (new `header = false` parameter on `FluxxColourPicker`). With a single target, no eyedropper, no reset and no inherit, the header would be an empty 40dp row. Every other compact picker keeps its header, so the compact look is unchanged.
2. **No eyedropper in the marker popover.** Sampling needs the preview surface, which the timeline popover does not own, and "where it applies" allowed leaving it out.
3. **Tapping the playhead to add now also pauses playback** (`onMarkerConfigure`), because it opens the popover. Before, only hold paused.
4. **Holding the playhead on an empty frame creates the marker immediately**, the same as tap. There is no longer a staged, unsaved draft.
5. **Off-bar layer markers anchor to the playhead target.** A retained layer marker outside its trimmed clip has no glyph on the bar, so this is the only anchor available.
6. **The layer-marker popover lives inside the lazy timeline row.** If that row scrolls out of composition while the popover is open, the popover leaves with it; the session stays and closes on the next open or removal. In practice an outside tap, including the start of a scroll, closes the popover first.
7. **Description placeholder** is plain secondary text in the field, not a floating label (monochrome, single row).
8. **Slider rows stack with no extra gap** (`SLIDER_GAP = 0`): the 20dp label line separates them. With an 8dp gap, video Timing (3 × 68 + 16 = 220) would not fit the 208dp content of a 268 sheet at font scale 1.
9. **Text rows grow by 20dp per +1.0 font scale**, rather than by multiplying the whole height. That is one extra text line at 2×, enough for the 13–16sp text in these rows without doubling the boxes.
10. **The mode column is 40dp wide**, matching the 40dp tiles (was 48). The column freed 8dp, which goes to the centred content.
11. **Audio uses measured centring.** Its info text has no fixed height, so `CentredSectionGroup(null, …)` measures the group instead of using arithmetic.
12. **`LogScaleRuler(readoutDp)` is now the final readout height** (already font-scaled by `SectionLayout`). `null` keeps the legacy 24 × fs default.
13. **The anchor-point controls are unchanged** (not centred). They are a 3×3 grid that already fills the column, and the spec named only the Position, Rotation and Scale groups.

#### Updated existing tests (structure legitimately changed)

- **`RailAndRulerLayoutTest`.**
  - Replaced (the fill-the-area arithmetic was removed): `scaleRulerUsesTheFreedHeightAndAlwaysFits`, `positionPadAndDialAreLargerAndFit`, `sliderRowsAreLargerAndNeverOverflow`.
  - Added:
    - `targetSizesAtFontScaleOne`
    - `sizesDoNotDependOnTheSheetHeightOrPreviewState`
    - `groupsAreCentredWithEqualMargins`
    - `everythingFitsAtEveryFontScaleAndHorizontally`
    - `touchTargetsAreAtLeast40`
    - `editSolidPickerIsNotStretched`
  - The suite went from 7 to 10 tests.
- **`ColourPickerFamilyTest`.** The marker surface moved from `MarkerDialog.kt` to `MarkerPopover.kt`. The anchored-popover assertion now reads `AnchoredPopover(markerPopoverOpen` in `TimelineView`.

#### Gate

- **Compiles:** `:app:compileDebugKotlin`, `:app:compileReleaseKotlin` and `:app:compileDebugAndroidTestKotlin`. Native code was not touched.
- **JVM:** `:app:testDebugUnitTest` ran **357 tests (343 previous + 14 new)**, with 0 failures, errors or skips.
  - New suite: `MarkerPopoverTest` (8).
  - `LayerKeyMarkersTest` went from 5 to 8.
  - `RailAndRulerLayoutTest` went from 7 to 10 (3 replaced, 6 added).
- No APK packaging and no device run.

#### Exact A16 test list

1. **Add opens the popover.**
   - With no layer selected, tap the playhead on an empty frame. A red marker appears at the playhead immediately.
   - One popover opens, anchored to the ruler pentagon (below it when there is no room above) and never centred.
   - It shows a description field, then the compact picker: hex/alpha, SV square, hue strip, alpha slider and marker swatches.
   - Select a layer and repeat. The orange pennant gets the popover, above or below the layer bar.
   - Try markers near the left and right screen edges: the popover stays 8dp inside the screen.
2. **Live apply.**
   - Type a description: the label beside the flag updates as you type.
   - Drag SV/hue, tap a swatch, type hex `00FF00` and alpha `50`: the flag and the playhead tint change live.
   - The keyboard never covers the field: only the popover lifts and the preview/timeline do not resize.
3. **Closing keeps edits.**
   - Tap outside: the popover closes and the description and colour stay.
   - Reopen by holding the playhead on the marker; edit; press Back (the keyboard hides first, then the popover closes). The edits stay.
   - Undo steps back once per description session and once per colour interaction, not per keystroke. A further undo removes the added marker.
4. **Removal unchanged.**
   - With the playhead on a marker, tap the playhead: the marker is removed (one undo restores it).
   - Tapping a parked ruler/layer flag also removes it.
   - If the popover was open on a marker that undo removes, the popover closes.
5. **No Add/Save/Delete/X anywhere** in the marker UI, and no centred dialog.
6. **Layer keyframes centred.**
   - Key several properties on video, image, solid and text layers. The diamonds sit on the vertical middle of the bar.
   - Where they cross the layer name, each diamond keeps a thin ring in the bar colour and stays readable, and the name is not hidden or shortened.
   - The focused property's keys are full-size and tappable/draggable at the new height. The others are small and dim.
   - Check with the preview normal and expanded, with the layer selected and not.
   - Trim and move handles behave as before.
7. **Same control sizes everywhere.**
   - Open Transform (Position, Rotation, Scale linked/unlinked), Opacity and Timing (including a video layer) with the preview normal (328) and expanded (268).
   - The pad (128), dial (144), ruler (80 / 164), X/Y boxes (40) and slider rows (20 + 48) are identical in both.
   - Each group is vertically centred, with equal space above and below.
8. **Scale ruler centred:** linked and unlinked, the readout + ruler group is in the middle of the content, between the rail and the mode column. The readout is medium-sized (not the large TEXT UI 7 readout).
9. **Edit Solid picker not oversized:** the SV square stays at most 200dp and the picker sits centred with the palette below, at 268 and 328.
10. **Font scale 1.3 and 2:** nothing overlaps or clips on Transform, Opacity, Timing, Audio or Edit Solid. Text rows are taller; pads, dials and strips are not.
11. **Regression:**
    - Edit Text layout, Fonts page, rail, badges, single-row headers;
    - text colour popover;
    - keyframe tap-to-seek and hold-drag snapping to markers;
    - marker navigation (Prev/Next);
    - save and reopen (marker colours and descriptions persist).

### TEXT FIX 9 (font import, variable-font SDF holes, sharp imported/animated glyphs, device suites)

See [TEXT_FIX_9.md](#rec-text-fix-9). It covers:

- the import regression root cause and the visible-error rules (9a);
- the overlap-safe SDF and its generator-version cache key (9b);
- the imported/animated tier protocol and caps (9c);
- the instrumented suite results, fixes and findings, the deviations, and the A16 manual test list (9d).

---

<a id="rec-text-fix-9"></a>

## TEXT FIX 9: font import regression, Playfair overlap hole, sharp imported/animated glyphs

*Formerly `docs/checkpoints/TEXT_FIX_9.md`.*

Device-assisted pass on the A16 (SM-A165F), with the user present. Linked from `TEXT_UI_OVERHAUL.md`.

### 9a. Font import regression (fixed, device-confirmed)

#### Symptom

Importing any font (static or variable, including delete then re-import) did nothing visible:

- no new family in the Fonts list;
- search found nothing;
- no error line.

#### Evidence (logcat on the A16)

Debug build, with temporary `FluxxFonts` log lines on the import path:

```
18:33:55.103 D/FluxxFonts: page composed
18:33:57.723 I/ActivityTaskManager: START u0 {act=android.intent.action.OPEN_DOCUMENT … PickActivity} from uid 10352 (com.fluxx.android.debug)
18:33:59.654 D/FluxxFonts: ON_STOP cancelAll mode=Fonts
18:34:00.157 D/FluxxFonts: page disposed
```

What did not happen:

- No `picker result` line followed.
- No `:font_worker` process was started.
- `files/fonts/index.json` stayed at 24 bytes (an empty library).

In the first, uninstrumented capture the window manager logged `appStopped=true` for `MainActivity` at 18:28:37.467, while the picker was in front.

#### Root cause

`TextInspectorPanel` registers an `ON_STOP` observer that calls `cancelAll()`. Since TEXT UI 5 (`769e4bf`, which turned the Fonts popup into the in-sheet Fonts page), `cancelAll()` ended with `mode = Browse`.

The system document picker is a full-screen activity, so opening it stops `MainActivity`. That leads to this chain:

1. `ON_STOP` fires `cancelAll()`.
2. The inspector leaves the Fonts page.
3. `TextFontsPage` is disposed, which unregisters its `rememberLauncherForActivityResult` launcher.
4. When the picker returns its URI there is no registered callback, so the result is dropped.

`FontLibraryRepository.import` never ran, and no error was possible. The worker, the native library, `TextIdlePrewarm` and `SdfDiskCache` were not involved; the device log shows the worker was never started.

#### Fix

- `cancelAll()` still cancels every live draft (numeric, colour, Source Text, axis drag), but keeps the Fonts page open (`TextInspectorLifecycle.afterStop`).
- Imports now run in the repository's own scope (`FontImportRunner` inside `FontLibraryRepository.startImport`), not a composition scope. Leaving the page or recomposing can no longer cancel an import or lose its result.
- The outcome is published as `FontLibraryRepository.importStatus`: `Idle` / `Importing` / `Imported(family, faces)` / `Failed(message)`.
- If the picker result arrives after activity recreation, the page is restored by `rememberSaveable` and the launcher re-registers with its saved key.
- The index merge and remove arithmetic is extracted into `FontLibraryIndex`, so it can be tested on the JVM.
- The idle prewarm after import is wrapped so it can never fail or stall an import.

#### Visible-error rules (`TextFontsModel.statusLines`)

1. Every status line is shown, in this order:
   1. page error (load / delete);
   2. import failure;
   3. "Importing font…" or "Imported *family* (N faces)";
   4. the requested-font-missing notice.

   Before this fix, an ordered `when` let the missing-font notice hide the error.
2. Error lines stay until tapped (dismiss) or until the next import or delete action.
3. The "Imported …" confirmation clears after 4 s (`CONFIRMATION_MS`).
4. Every `Throwable` is reported, not only `Exception`, via `FontImportErrors.message`:
   - `UnsatisfiedLinkError` → "Font engine unavailable (…)".
   - `OutOfMemoryError` → "Not enough memory…".
   - Anything without a message → "Font import failed (*class*)".
5. Worker calls (inspect / prepare) are bounded by `WORKER_TIMEOUT_MS = 60 000 ms`. On timeout the call is cancelled, its binding is released, and the status becomes "Font worker did not respond within 60 s; the font was not imported". `importing` therefore cannot stay true forever.

#### Device confirmation

After installing the fix, the user imported:

- a static Playfair Display;
- the variable Playfair Display (`wght` axis).

Both appeared in the list and applied. On the device afterwards:

- `index.json` was 913 bytes and listed both files;
- the `:font_worker` process started at 19:07:43;
- there were no crashes.

#### Tests

`FontImportTest`, 7 tests:

- the picker no longer closes the Fonts page;
- error-priority rendering, including an import error while a missing-font reference exists;
- worker timeout;
- non-`Exception` failures;
- the import survives the composition leaving;
- delete then re-import of the same sha256 shows the family again;
- static, variable and TTC fixtures.

Total: JVM 364 (357 previous + 7), 0 failures.

### 9b. Overlap-safe SDF (fixed, device-confirmed)

#### Symptom

On imported Playfair Display and Vibes, both the variable fonts and their static instances, glyphs showed holes and cuts inside the strokes:

- the crossbar of `e`;
- the bowls and descenders of `g p q y`;
- `&`;
- serif joins.

Other static fonts (e.g. FOT-Matisse Pro) rendered cleanly. A device screenshot zoom showed hairline cuts at contour junctions.

#### Root cause

These fonts keep **overlapping contours**. Variable fonts always do, and statics generated from the variable masters often do too. The vendored **FreeType 2.14.1** SDF renderer (`FT_RENDER_MODE_SDF`), even with the `sdf` module's `overlaps` property on (the engine's default since F1), inverts the inside/outside sign along those junctions. The error grows with ppem.

Host evidence, using `text-proof sdfcheck` against an independent FreeType coverage raster of variable Playfair `e`:

| Tier (ppem) | Holes |
|---|---|
| 48 | 8 |
| 96 | 36 |
| 192 | 140 |

The holes sit exactly where the crossbar contour overlaps the bowl.

The **bundled** fonts are affected too, which was not known before: 133 of 5,584 Latin-range glyphs at 48 ppem had sign errors of up to 168 pixels (composites and overlapping shapes, e.g. Inter gid 1550, Noto Serif gid 112).

#### Fix

`core-engine/src/main/cpp/text/sdf_coverage.{h,cpp}`, called from `FontManager::sdf`:

1. FreeType renders the SDF as before. Its grid and bearings are unchanged.
2. **Self-check.** The same outline is rasterised once more with FreeType's non-zero-winding coverage rasteriser, which unions overlaps correctly, into a k× supersampled mask on the SDF's own grid. Bounds: k = 1–4, chosen so the mask is at most **1 Mi pixels** (e.g. k = 4 at 48 ppem, k = 2 for a 384 ppem `@`). A glyph fails if any pixel with ≥ 7/8 coverage reads outside, or any pixel with zero coverage reads inside.
3. **Only failing glyphs** are rebuilt:
   - an exact Euclidean distance transform (Felzenszwalb–Huttenlocher, both sides) of the mask;
   - the distance sampled at each output pixel centre;
   - the result encoded with FreeType's own byte mapping (`map_fixed_to_sdf`: 128 = edge, ± spread → 255 / 0).

   The tier ppem and spread table from TEXT UI 6 is unchanged.
4. Glyphs that pass keep FreeType's bytes exactly, so there is no edge, stroke or faux-bold change on correct glyphs. `FontStats::coverageRebuilds` counts the rebuilds.

The worker (imported fonts), the base catalog and the tier catalog all use `FontManager::sdf`, so every path is covered.

#### Cost (host, Windows Release build)

| Step | Cost |
|---|---|
| FreeType SDF | 12.5 ms/glyph at 48 ppem (unchanged) |
| Self-check | **0.19 ms/glyph** (≈ 1.5 % extra) |
| Rebuild, failing glyphs only | **3.4 ms/glyph** at 48 ppem |

Memory: a mask of at most 1 MiB, plus two float distance grids of at most 4 MiB each, held only during a rebuild.

#### Cache keys

The generator version is part of the persisted key:

- `SdfDiskCache.GENERATOR = 2`, which must equal native `coverage_sdf::GeneratorVersion`; a JVM test reads both.
- Files are now named `v2-48-8-<font>-<glyph>.sdf`.
- Files from any other generator are deleted on first use and never seeded.

On the A16, the old `v1-48-8-…` files were replaced by `v2` files after the first preparation.

#### Tests and proofs

- **JVM:** `TextSdfDiskCacheTest.keyCarriesTheGeneratorVersionAndOlderBitmapsAreNeverReused`. JVM total 365.
- **Host native:**

  ```
  tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts sdfregress [FONT...]
  ```

  It checks, in turn:
  1. A synthetic overlap union has no hole.
  2. FreeType byte mapping and supersample bounds.
  3. Rebuild parity against FreeType on clean bundled glyphs: worst mean |Δ| **0.98 / 255**, worst pixel 11.
  4. All 12 bundled faces: every glyph whose FreeType SDF is correct is **byte-identical**, and every glyph that is not is rebuilt with **0 holes**.
     - 48 ppem: full Latin-1, 2206 identical / 58 rebuilt.
     - 96 / 192 / 384 ppem: mixed sample, 132 / 0, 132 / 0, 128 / 4.
  5. Every given font file at all four tiers: 0 holes.

  Run with the user's variable and static Playfair (kept in the scratchpad, not committed): 74 identical / 20 rebuilt at 48 / 96 / 192, 73 / 21 at 384, 0 holes. `PASS`.
- **Diagnostics:**
  - `text-proof … sdfcheck FONT CHARS [TIER|tag=value] [art]` shows holes per glyph, with an ASCII map.
  - `text-proof … ftaudit bundled|FONT [PPEM]` lists raw FreeType failures and per-step timings.

#### Device confirmation

The user checked variable Playfair (several weights via the axis and named instances), Vibes, and static Playfair: "holes are gone".

#### Finding for 9c

FreeType's SDF renderer is very slow at the large tiers: about 0.3 s per glyph at 192 ppem and 0.45–1.1 s at 384 ppem on the host (half of the measured time including the reference render). The coverage path costs a few ms for the same glyph.

### 9c. Sharp tiers for imported fonts and animated glyphs (device-confirmed)

#### Symptom and cause

Imported-font text was a little soft at large sizes, and high-contrast hairlines broke (e.g. the "@" ring in Playfair). The cause is the TEXT UI 6 limit: layouts whose base bitmaps came from the isolated worker (imported faces, and per-character catalogue packets used by Explode and animators) were pinned to the 48 ppem base tier, because the in-app tier catalog can only open bundled faces.

#### Spec

**Tier selection.** One per-layer policy for every font. It is unchanged from TEXT UI 6 (`SdfAtlasTiers.select`: px/em with 15 % hysteresis, distance-encoding cap, `degrade` under the cap). `collectOne` no longer special-cases prepared bitmaps (`wanted = tierFor(layout)`).

Catalogue handles (Explode, animators, character offset and value) already received the layer's tier in `applyTextTiers`, and now actually use it. The policy stays **per layer**, as documented; per-glyph selection was not added.

**Who rasterises.** The key `(font, glyph, ppem)` is unchanged.

- Bundled ids (`fluxx.*`) go to the existing off-thread tier catalog.
- Imported ids (`user.<identity>`) are **external**. `TextRenderer::takeExternalRequests` returns the missing ones, at most 256 per call, and marks them in flight.

**Protocol.** `RenderBridge.textTierRequests` → `ImportedTierFetcher` → `FontWorkerClient.tiers` → `FontWorkerService.TIERS` → `IsolatedFontNative.tiers` → `rasteriseTiers` → reply → `RenderBridge.supplyTextTierGlyphs` → `TextRenderer::supplyTierGlyphs`.

- **Transfer.** The same file-descriptor scheme as PREPARE:
  - the request packet goes into a temp file;
  - the imported font files are passed as read-only descriptors with face, named instance and axis coordinates;
  - the reply comes back in an output temp file.
- **Wire format** (`prepared_text_packet.{h,cpp}`, mirrored by `TierWire` in Kotlin), big-endian:
  - request `FTRQ` v1: keys;
  - reply `FTTG` v1: glyph records with font, glyph, ppem, spread, w, h, left, top and pixels; then a `failed` list and a `deferred` list.
- **Caps:**

  | Limit | Value |
  |---|---|
  | Keys per call | ≤ 256 |
  | Reply size | ≤ 8 MiB (`MaxTierReplyBytes`; checked in the worker, the client and the decoder) |
  | Font files | ≤ 64 faces / 32 MiB |
  | Request size | ≤ 1 MiB |
  | Glyph bitmap | ≤ 2048 × 2048 |
  | Worker time per call | 20 s; the rest is **deferred**, never dropped |
  | Worker call timeout | 60 s (`WORKER_TIMEOUT_MS`) |
- **Validation:**
  - only tiers 96 / 192 / 384, with exactly the TEXT UI 6 spread table (16 / 32 / 32), are accepted;
  - the base tier is never requested, since it ships in the prepared packet;
  - unknown fonts fail, and never fall back to `fluxx.sans` (`FontManager::contains`);
  - the renderer accepts only keys it has in flight.
- **Never blocking the render thread.** The preview submits to one background thread (`fluxx-imported-tiers`) and keeps drawing the lower tier. `awaitTiers` counts in-flight external keys, so the existing settle-and-redraw (≤ 1.5 s, cancellable) shows the sharp frame when the glyphs land.
- **Export.** Export fetches inline before preparing each frame (`EXPORT_TIER_ROUNDS = 8` for deferred keys), so it stays deterministic like bundled tiers.
- **Failures:**
  - A worker crash, timeout, malformed reply or deleted font file supplies a failure reply. Those keys stay on the lower tier, `lastError` and logcat (`FluxxText`) record why, and the app never crashes.
  - Missing-font fallback, hash verification on `resolve` and isolated-process crash isolation are unchanged.

**Sharing.** Supplied glyphs join the same CPU ready cache and atlas as bundled tiers:

- key `(font, glyph, ppem)`;
- eviction highest tier first, then LRU;
- CPU tier budget 16 / 8 MiB;
- atlas cap 24 / 12 MiB;
- 15 % hysteresis;
- degrade under the cap.

**Tier generator.** Tiers above 48 ppem are generated from coverage on FreeType's grid (`coverageTier`, see 9b) instead of FreeType's SDF renderer. On the host that renderer costs about 0.3 s per glyph at 192 ppem and about 0.5–1 s at 384 ppem, which would make imported (and bundled) tiers impractically slow. The coverage path averages **66 ms per glyph across the 96/192/384 tiers** for variable Playfair. The base tier keeps FreeType's bytes plus the 9b self-check.

**Disk cache.** `SdfDiskCache` still holds only base-tier imported glyphs: `v<generator>-48-8-…`. Tier glyphs are memory-only.

#### Memory impact

Imported tiers spend the same budgets as bundled tiers (no new cap):

| Budget | Normal | Low-RAM |
|---|---|---|
| Atlas | 24 MiB | 12 MiB |
| CPU ready cache | 16 MiB | 8 MiB |

Per call there is one transient reply of ≤ 8 MiB plus its file. The worker holds a ≤ 1 MiB coverage mask and ≤ 8 MiB of distance grids during a glyph.

#### Tests

- **JVM** — `ImportedTierTest` (8 tests):
  - tier selection for imported faces and catalogues;
  - request wire round trip and caps (Kotlin/native constants agree);
  - reply validation before native code;
  - the preview never waits, while export fetches inline;
  - worker death / timeout / `UnsatisfiedLinkError` / malformed reply fall back to the lower tier with an error;
  - a deleted font file keeps the lower tier;
  - eviction order, cap and hysteresis are shared;
  - cache keys carry ppem and generator version.

  JVM total: **373**.
- **Host native** — `text-proof … tierproof [FONT]`:
  - wire round trip, the 256-key cap and base-tier rejection;
  - byte and time budgets both defer;
  - unknown font fails;
  - forged spread rejected;
  - the spread table;
  - variable Playfair `wght=700` at 96/192/384: 12 glyphs, 618 KiB reply.

  `PASS`.

#### Device confirmation

The user checked imported (variable Playfair) text at 300 / 500 / 1000 px, plus bundled text and animated / Explode text: "imported text looks sharp now". Logcat shows the font worker serving requests, with no tier failures and no crashes.

#### Device results (A16, `ImportedTierAcceptanceTest`, 3/3 pass)

The test uses the committed OFL fixture `SourceSerif4Variable-Roman.otf`. The fixture is imported and deleted again; the user's fonts are untouched.

| Test | Result | Time |
|---|---|---|
| `isolatedWorkerRasterisesImportedTiersWithinCaps` | 36 tier glyphs (96/192/384), 0 failed, 0 deferred, 1441 KiB; 5.5 s total, **153 ms/glyph including IPC** | 5.7 s |
| `workerCrashDegradesToTheLowerTierWithAnError` | after `crashForTest`, a failure reply ("The isolated font parser stopped…") and no app crash; the next call succeeds | 1.8 s |
| `exportUsesTheSharpImportedTierDeterministically` | 24 px needs no imported tier; 600 px export supplied **10 tier glyphs inline**, 0 failed, 0 pending; first frame 2833 ms, `tierBytes` 0.8 MiB (cap 16 MiB) | 6.4 s |

#### Host regression with coverage tiers

`sdfregress` passes:

- Bundled tiers 96 / 192 / 384 are on FreeType's grid with 0 holes, worst mean drift **0.53 / 255** wherever FreeType is correct.
- At 384, 4 bundled glyphs had FreeType holes; the coverage tier has none.
- The 48 ppem base is unchanged: 2206 glyphs identical, 58 rebuilt.
- Playfair has 0 holes at every tier.

### 9d. Deferred device suites: results, fixes, findings

#### How the suites were run (A16, SM-A165F, Android 16)

- The app and test APKs were installed with `adb install -r` (`-t` for the test APK). App data and projects were kept, and **nothing was uninstalled**.
- `connectedDebugAndroidTest` was used only with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, because the default uninstall would delete the user's projects. That task applies only the first class of a comma-separated `class` argument, so the later runs use `am instrument` directly. The long F2 run used a detached `nohup am instrument … > /data/local/tmp/f2b.txt` so a host session loss cannot stop it.
- Run labels: `textRunLabel=a16-fix9-0N` and `e1aLabel=r28` (the build uses NDK 28.2). Reports are under `Android/data/com.fluxx.android.debug/files/text/<label>/`.

Commands used:

```
gradle :app:connectedDebugAndroidTest :core-engine:connectedDebugAndroidTest --continue \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.fluxx.android.render.TextF2AcceptanceTest
adb shell am instrument -w -r -e class <Class[#method],...> -e textRunLabel a16-fix9-0N \
  com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

#### Real bugs found by the device suites and fixed here (all inside this task's tier/atlas scope)

1. **Per-frame allocations in the preview loop** (F1 `fiveHundredGlyphsAllocateNothing…`: *Kotlin worker must allocate zero objects*). The measured window held 54,136 objects over 600 frames.
   - Source: an on-device ART per-thread counter diagnostic (temporary, not committed).
   - About 70 objects per frame came from the TEXT UI 6 tier policy in `applyTextTiers`:
     - `HashMap`, `ArrayList` and `Request` objects;
     - `Long`/`Int` boxing;
     - `filterNot().toSet()`;
     - `filter` / `lastOrNull`.
   - 19 per frame came from the settle check's `FrameRate.frameTimeUs` arithmetic.
   - 1 per frame came from a `{ false }` default-argument lambda.
   - 9c's empty JNI tier-request array added 1 more.
   - **Fix:**
     - per-layer glyph counts, handle lists and tier arrays are prepared once;
     - `SdfAtlasTiers.selectTier` and `degradeInPlace` are primitive forms (`select` and `degrade` keep their API and decisions; JVM tests check equivalence);
     - `textTiers` is only called when a handle's tier changes;
     - `textTierRequests` returns `null` and is skipped when every layer is on the base tier;
     - the frame span is cached per frame rate;
     - one shared `NEVER_CANCELLED` lambda is used as the default;
     - natively, external tier scans are skipped once a layout's keys have settled, and the layout vector is reused.
   - **Result on the A16:** `workerThreadAllocDelta` **0** and `nativeNewDelta` **0** in the F1 window. `workerThreadAllocDelta` is also 0 in every F2 report.
2. **The atlas packer rejected the TEXT UI 6 page cap** (F2 `characterRepresentativeColdAndThermal`: *Invalid atlas page budget*).
   - TEXT UI 6 configures 6 pages (24 MiB; 3 on low-RAM devices), and the JNI accepts 1–8. `packGlyphPages` still allowed only 4 (an E2 bound).
   - So a frame that had to grow the atlas past 4 pages threw instead of growing or raising the typed capacity error.
   - **Fix:** `MaxAtlasPages = 8`. The host `selftest` now asserts that 6 pages are accepted and 9 are rejected.
3. **Multi-page packing dropped a glyph's other tiers** (F2 `characterRepresentativeColdAndThermal`, second run: *Missing prepared glyph*). Fix 2 exposed this one.
   - `packGlyphPages` removed glyphs that had been placed on a page by `(fontId, glyphId)`, ignoring the ppem.
   - When the 48 ppem copy of a glyph landed on page 1, its 192/384 ppem copy, which did not fit on page 1, was discarded instead of carried to page 2.
   - The glyph was not counted in `rejected`, so `prepareMesh` found no placement for a required glyph.
   - This has been latent since TEXT UI 6 (tiers). It only shows once tiered text spills past one page.
   - **Fix:** placed glyphs are identified by bitmap identity (`const SdfGlyph*`).
   - The host `selftest` adds a two-tier case where the 384 ppem copies only fit on page 2. Every copy must be placed, with 0 rejected.

#### Environment-blocked (not product failures)

- **Vulkan validation:** `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests (matrix, capacity, array growth, and the 30-cycle surface/renderer lifecycle), plus the final assertion of F1 and F2 performance, require `VK_LAYER_KHRONOS_validation`.
  - The official Khronos Android release (1.4.363.1; sha256 matches GitHub's digest) was downloaded to the host scratch folder.
  - Installing it into the debug app's data directory and enabling `gpu_debug_*` on the phone was blocked by the session's permission policy. Nothing was changed on the phone.
  - The commands for the user are listed in the final report.
- **`E1aGoldenExportTest`:** needs `app/src/androidTest/assets/e1a/source.mp4`, which is git-ignored local media that is not present.

#### Findings (reported, not changed: design or limits outside this task)

- **60 fps is not met.** F2 frame time on the A16, p50 / p99, at FULL preview:
  - 660 glyphs: **33–37 / 40–44 ms**;
  - 720 glyphs, rich: 34 / 40–41 ms;
  - 4,096 glyphs (maximum, rich maximum, wrapped maximum): **330–341 / 354–365 ms** in run 05, and **434–439 / 449–480 ms** in run 06 (rich and imported maximum, after about 80 min of continuous load).

  The tests assert p99 ≤ 16.7 ms and fail ("report limits before lowering them"). Limits and fixtures were not changed.
- **Native allocations in the rich and wrapped paths:** `nativeNewDelta` was 624–1,277 per 600-frame window for rich-representative, and 2,063–3,618 for rich/wrapped-maximum. The plain F1/F2 fixtures are 0. This comes from the T1/T3 rich/wrapped mesh path, which this task did not touch.
- **The idle prewarm counts against F1's preparation counter.**
  - `TextIdlePrewarm.foreground` (the FIX pass, `85b9de4`) warms 12 bundled faces × 190 scalars on the **shared** base catalog, 1.5 s after the last layout preparation.
  - Rendering frames does not count as foreground activity, so it runs during playback.
  - Diagnostic on the A16: +572 rasterisations in the window while it runs, **+0** once it has finished. Atlas uploads, shapes and generation were 0 in both cases, so the render path itself is clean.
  - F1 and F2-representative "prepared counter 1 changed" is this. Whether the prewarm should pause during playback is a design decision for later.
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal` time out** under the 15-minute harness limit at the measured 4096-glyph frame time (see the run 06 results below).

#### F2 results, run `a16-fix9-06` (APK with fixes 1–2; detached `am instrument`, 10 tests, 21:50 → 23:11)

`Tests run: 10, Failures: 8`.

- **Passed:**
  - `characterRandomAndReversePreEncodePixelsMatch`
  - `randomAndReversePreEncodePixelsMatch`

  Both have pre-encode pixels identical for random and reverse seeks (`matched: true`, 8 seek times).
- **Frame time.** All of these failures report "p99 exceeds 60fps; report limits before lowering them" and "Vulkan validation failure". The validation failure is environmental: the layer is absent, see above.

  | Fixture (pass 0 cold / pass 1 after 180 s thermal load) | glyphs | frame p50 ms | frame p99 ms | mesh p50 ms | native `new` Δ | Kotlin alloc Δ | worker IPC Δ |
  |---|---|---|---|---|---|---|---|
  | t1 rich maximum | 4096 | 438.9 / 437.6 | 477.5 / 470.4 | 10.5 / 10.5 | 3618 / 2063 | 0 / 0 | 0 / 0 |
  | t2 imported variable maximum | 4096 | 434.4 / 433.9 | 479.9 / 449.5 | 10.6 / 10.5 | 65593 / 24582 | 3 / 0 | 1 / 0 |
  | t2 imported variable representative | 700 | 39.6 / — | 53.8 / — | 1.6 / — | 27272 / — | 18 / — | 1 / — |
  | t3 wrapped representative | 720 | 35.6 / 34.8 | 43.6 / 255.9 | 1.7 / 1.6 | 1277 / 624 | 0 / 0 | 0 / 0 |
  | t4 modifiers representative | 720 | 94.0 / 93.9 | 1602 / 359.9 | 1.6 / 1.6 | 4422 / 624 | 0 / 0 | 0 / 0 |
  | t4 characters representative | 720 | 105.9 / — | 1631 / — | 2.1 / — | 39566 / — | 0 / — | 0 / — |

- **Also failed on prepared counters:** the rich, imported and modifier maximum or representative pass 0 reports "prepared counter 2/5 changed".
  - Counters 2 and 5 are atlas uploads and atlas generation (`TextDebugBridge.frameCounters`).
  - Pass 0 starts 120 frames after the first frame. Sharp tiers generated off-thread arrive inside the window, and the atlas re-uploads once to switch the layer up a tier. This is the TEXT UI 6 asynchronous tier design.
  - Pass 1 (after warm-up and thermal load) has no change on either counter.
- **Imported variable (maximum and representative):** pass 0 has 1 worker IPC (`FontWorkerClient.requestCount`, which is process-wide) and 3 and 18 render-thread Kotlin allocations inside the window. This fits one imported-tier fetch (9c): the render thread hands the request bytes from JNI to the tier executor, and the IPC runs there without blocking the frame. The exact frame was not traced.
  - It is asynchronous: the frame never waits on it.
  - The harness still counts any IPC during prepared evaluation as a failure. It is listed under Findings.
- **`characterRepresentativeColdAndThermal`:** *Missing prepared glyph* (bug 3 above, fixed).
- **`importedVariableRepresentativeColdAndThermal` (run 06):** pass 0 completed. Pass 1 failed with `check(RenderBridge.finish(session))`, meaning `finishFrame` returned false: either the 5 s GPU fence wait expired or `flushBatch` failed.
  - The log buffer had already wrapped when it was read.
  - It was re-run with the packer fix and logcat captured (below).
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal`:** `TimeoutException`.
  - The whole test runs under `future.get(15, MINUTES)`. At about 435 ms per 4096-glyph frame, two passes of 720 frames plus 180 s of thermal load and 120 warm-up frames come to about 14–15 min before cold preparation, so the limit expires.
  - This is a consequence of the frame-time finding, not a hang.

#### Re-run after the packer fix (`a16-fix9-07`)

This run used the APK with fixes 1–3. Command:

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest#characterRepresentativeColdAndThermal,com.fluxx.android.render.TextF2AcceptanceTest#importedVariableRepresentativeColdAndThermal -e textRunLabel a16-fix9-07 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/f2c.txt 2>&1 &"
```

Result: `Tests run: 2, Failures: 2`. Both tests now **complete both passes**: no *Missing prepared glyph* and no `finish` failure. Logcat for the run (from 00:22, captured this time) has no `VK_ERROR`, device-lost or fence messages. The `finish` failure in run 06 did not reproduce, so it stays a finding. Both remaining failures are the known findings only (p99, validation, native allocations, cold-pass counters, one imported-tier IPC).

| Fixture (pass 0 / pass 1) | glyphs | frame p50 ms | frame p99 ms | mesh p50 ms | native `new` Δ | Kotlin alloc Δ | worker IPC Δ |
|---|---|---|---|---|---|---|---|
| t2 imported variable representative | 700 | 27.5 / 28.7 | 35.1 / 32.8 | 1.8 / 1.8 | 73996 / 4334 | 13 / 0 | 1 / 0 |
| t4 characters representative | 720 | 81.9 / 82.0 | 1607 / 165.5 | 2.2 / 2.1 | 38283 / 1884 | 0 / 0 | 0 / 0 |

`characterMaximumColdAndThermal` was not re-run. The 15-minute harness limit makes a 4096-glyph run at the measured frame time time out regardless of the fix (see above).

### Deviations from the TEXT FIX 9 spec

1. **Tiers above 48 ppem are built from coverage, not FreeType's SDF renderer** (9c).
   - The tier bitmap is the coverage-correct SDF (`sdf_coverage`) on FreeType's NORMAL-preset grid padded by the spread.
   - FreeType's SDF has holes at overlapping contours (9b) at every size.
   - At 48 ppem, FreeType output that passes the coverage check is used byte for byte, and only failing glyphs are rebuilt.
   - The host `sdfregress` puts bundled tier drift against the previous output at ≤ 0.53/255, with 0 holes.
2. **Vulkan validation was not enabled on the phone.**
   - Installing the layer and changing `gpu_debug_*` settings was blocked by this session's permission policy.
   - As a result:
     - `TextGpuProofTest` is not run;
     - four `TextGpuAcceptanceTest` tests are not run;
     - every performance test also reports "Vulkan validation failure".
3. **`E1aGoldenExportTest` was not run:** its source video asset is not in the checkout.
4. **The instrumented runs use `am instrument`, not only `connectedDebugAndroidTest`.**
   - The Gradle task only honours the first class in a list.
   - By default it uninstalls the app, which would delete the user's projects.
5. **The performance limits (60 fps p99, zero native allocations in the rich/wrapped paths, idle-prewarm counters) fail as findings.** As the spec requires, these were reported, not relaxed.

### Tests

- **JVM:** **377 tests, 0 failures** (`gradle testDebugUnitTest`). 357 at the start of TEXT FIX 9, plus 20 new:

  | Commit | New tests |
  |---|---|
  | 9a | `FontImportTest` × 7 |
  | 9b | `TextSdfDiskCacheTest.keyCarriesTheGeneratorVersionAndOlderBitmapsAreNeverReused` |
  | 9c | `ImportedTierTest` × 8 |
  | 9d | `PerFrameTierPathTest` × 4 |

- **Existing tests updated:** `ImportedTierTest.importedFacesAndCataloguesSelectTiersLikeBundledText` (9c's own test) now checks the prepared `tierHandles` line instead of the per-frame `map` expression it replaced. No other existing test was changed.
- **Host (`text-proof.exe core-engine/src/main/assets/fonts …`):** `selftest` PASS (including the 9d page-budget and two-tier packing cases), `sdfregress` PASS, `tierproof` PASS.
- **Instrumented (A16):**

  | Suite | Result |
  |---|---|
  | core-engine `TextCpuProofTest` | 2/2 |
  | `FontImportAcceptanceTest` | 4/4 |
  | `ImportedTierAcceptanceTest` | 3/3 |
  | `TextExplodeAcceptanceTest` | 4/4 |
  | `TextFixAcceptanceTest` | 2/2 |
  | `TextInspectorLayoutTest` | 3/3 |
  | `PaletteRepositoryTest` | 5/5 |
  | `LayerTransformationTest` | 3/3 |
  | `PaletteBrowserTest` | pass on rerun |
  | F1 parity | pass |
  | F1 zero-allocation window | allocations 0/0 after the fix; fails only on the idle-prewarm counter (finding) |
  | F2 | parity tests pass; performance tests fail on the findings above |
  | `TextGpuProofTest`, 4 × `TextGpuAcceptanceTest`, `E1aGoldenExportTest` | blocked (environment) |

### A16 manual test list

Install the debug APK with `adb install -r` (keeps projects). Then:

1. **Import a static font.** Fonts page → Import → pick a static `.ttf` or `.otf`.
   - "Importing font…" appears, then "Imported <family> (<n> faces)" for about 4 s.
   - The family appears in the list.
   - Apply it to a text layer.
2. **Import a variable font** (Playfair, Vibes or Source Serif variable).
   - It imports with its named instances and axes.
   - Weight and optical-size changes apply.
3. **Import errors are visible.**
   - Import a non-font file (for example a `.txt` renamed to `.ttf`).
   - A red error line stays until dismissed.
   - Leave the app during an import and come back: the status (success or error) is still shown, and the Fonts page is still open.
4. **Delete and re-import.**
   - Delete an imported family: layers that use it show the missing-font line.
   - Import the same file again: those layers render again without restarting.
5. **Playfair weights.** Playfair variable and the Playfair static faces at Regular, Medium, Bold and Black, upper and lower case, digits and symbols:
   - no black holes or filled counters (g, p, q, y, e, a, o, 8, @, &);
   - also at small sizes (24–48 px).
6. **Sharpness at 300, 500 and 1000 px.** Set the text size (or the layer scale) so that letters are about 300, 500 and 1000 px tall in the preview, for bundled Sans/Serif/Mono and for an imported static and variable font:
   - edges and corners stay crisp after a brief settle (≤ 1 s), with no blur and no rounded corners;
   - export a short clip and check the same frames in the exported video.
7. **Explode and animated sharpness.**
   - Apply Explode, or a per-character scale-up animator, to large imported text.
   - The magnified glyphs are as sharp as static text of that size.
   - No flicker when scrubbing back and forth across the animation.
8. **Worker crash tolerance.** The isolated `:font_worker` runs under its own uid, so a non-root `adb shell kill` cannot stop it. Use the instrumented check instead:
   `adb shell am instrument -w -e class com.fluxx.android.fonts.ImportedTierAcceptanceTest#workerCrashDegradesToTheLowerTierWithAnError com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
   It kills the worker mid-request, then checks that the lower tier stays in use with an error (no app crash) and that the next request succeeds. Afterwards, open the app and check that large imported text still renders sharply.
9. **Scrubbing performance with large imported text.**
   - A project with a 1080×1920 composition, one paragraph of about 600 characters in an imported variable font at 100+ px, and one animator.
   - Scrub quickly for 30 s: no stalls longer than a frame or two, no "font worker timed out" error, and no growth in app memory in Android Studio's profiler after the scrub.
10. **Regression.**
    - Bundled fonts and the Fonts page browse/search work.
    - Text inspector pages work.
    - Save and reopen a project with imported fonts: the fonts resolve and the text renders the same.

---

<a id="rec-text-perf-10"></a>

## TEXT PERF 10: text frame-time investigation and fixes

*Formerly `docs/checkpoints/TEXT_PERF_10.md`.*

Branch `text-perf-10` (from master `9fd4499`). Device: Galaxy A16 (SM-A165F, MediaTek mt6789 / Helio G99: 2× Cortex-A76 at 2.2 GHz and 6× Cortex-A55 at 2.0 GHz, Mali-G57 MC2), Android 16. The build is the debug APK, the same one the 9d suites run. It is JIT-only (`dexopt status=verify`, debuggable).

### 10a. Profile (before any fix)

#### How it was measured

- **Stage timers.** These are diagnostic only: they never change output and do not allocate.
  - Kotlin: `CompositionRenderer.frameStages` covers frame, tiers, evaluate, prepareText, textLayer (mesh) and finish, using `System.nanoTime` around each stage.
  - Native: `VulkanRenderer::frameStages` is exposed as `TextDebugBridge.frameStages` in debug builds only. It covers the work-fence wait, batch record, batch submit+wait, present-fence wait, acquire, present record, present submit and the post-present idle wait.
  - GPU time comes from Vulkan timestamp queries around the text render pass and the present blit.
  - Perfetto/atrace sections: `fluxx.frame`, `fluxx.tiers`, `fluxx.prepareText`, `fluxx.textMesh`, `fluxx.batchRecord`, `fluxx.batchSubmitWait`, `fluxx.acquire`, `fluxx.queuePresent`, `fluxx.presentIdleWait`.
- **Harness.** `TextPerfProfileTest` runs the TEXT FIX 9d F2 fixtures, built by the same `TextF2AcceptanceTest.fixture`, in three modes:
  - **static:** the same time every frame;
  - **scrub:** deterministic random seeks;
  - **playback:** consecutive 60 fps frames.

  Each mode runs 120 warm-up frames and 300 measured frames (4096 glyphs: 10 + 40). The harness writes `files/text/<label>/perf10-<case>-<mode>.json`.
- **Commands.** The phone was kept awake with `svc power stayon true` (the user approved this).

  ```
  adb shell am instrument -w -r -e class com.fluxx.android.render.TextPerfProfileTest#representativeStages,com.fluxx.android.render.TextPerfProfileTest#maximumStages \
    -e textRunLabel a16-perf10-before com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
  adb shell simpleperf record --app com.fluxx.android.debug -g --duration 12 -f 2000 -o /data/local/tmp/p10/rep-playback.data
  adb shell "cat /data/local/tmp/p10/perfetto.txt | perfetto --txt -c - -o /data/misc/perfetto-traces/perf10-rep-playback.pftrace"
  ```

  The Perfetto config records sched, cpu/gpu frequency, cpu idle, thermal, atrace gfx/view/dalvik/power and the app's sections.
- **Clocks and thermal.**
  - A shell sampler logged `scaling_cur_freq` once a second. The big cores stayed at **2.1–2.2 GHz** and the little cores at 2.0 GHz under load, so there was no throttling.
  - `PowerManager.currentThermalStatus` was **0 (none)** before and after every mode.
  - The GPU frequency nodes (`/proc/gpufreqv2`, `/sys/kernel/ged`) are not readable from the shell. GPU time is therefore measured with timestamps, and the Perfetto traces carry the `gpu_frequency` events.
- **Artefacts.**
  - Traces: `perf10-rep-playback.pftrace` and `perf10-max-playback.pftrace`, kept outside the repo. Open them in ui.perfetto.dev.
  - Profiles: simpleperf `rep-playback.data` and `max-playback.data`.

#### Per-frame breakdown, before (ms, p50 / p95)

| Stage | 720 static | 720 scrub | 720 playback | 4096 static | 4096 playback |
|---|---|---|---|---|---|
| **Frame (render thread, wall)** | **16.50 / 19.59** | **85.37 / 91.52** | **80.72 / 86.72** | **29.81 / 32.63** | **897.69 / 948.16** |
| Tiers + layout evaluation (CPU) | 1.13 / 4.85 | 72.23 / – | 66.58 / 74.58 | 7.25 / – | 875.57 / 924.72 |
| ↳ animator/selector evaluation (CPU) | 0.00 / 0.01 | 70.93 / – | **65.39 / 73.30** | 0.01 / – | **870.05 / 918.91** |
| Glyph preparation (`prepareText` reuse, CPU) | 0.04 / 0.07 | – | 0.06 / 0.07 | – | 0.07 / 0.08 |
| `textLayer` JNI + native mesh build (CPU) | 1.48 / 2.96 | 1.79 / – | 1.67 / 1.82 | 11.95 / – | 10.57 / 11.39 |
| `finish` (all waits below, wall) | 13.46 / 15.42 | 11.44 / – | 12.28 / 15.42 | 10.67 / – | 10.94 / 12.19 |
| ↳ batch command record (CPU) | 0.10 / 0.17 | – | 0.11 / 0.16 | – | 0.37 / 0.55 |
| ↳ batch submit + fence wait (wall) | 4.08 / 5.09 | 6.48 / – | 6.54 / 9.36 | 4.57 / – | 4.70 / 5.63 |
| ↳ acquire (wall) | 0.14 / 0.20 | 0.14 / – | 0.15 / 0.25 | 0.14 / – | 0.16 / 0.24 |
| ↳ present record + submit (CPU) | 0.69 / 0.99 | – | 1.17 / 1.87 | – | 1.34 / 1.81 |
| ↳ post-present `vkQueueWaitIdle` (wall) | 8.02 / 10.39 | 3.34 / – | 3.75 / 4.98 | 3.69 / – | 3.64 / 4.14 |
| **GPU: text batch** (timestamps) | 3.03 / 3.41 | 3.56 / – | 3.21 / 5.77 | 1.22 / – | 1.19 / 1.65 |
| **GPU: present blit** (timestamps) | 7.61 / 9.61 | 3.36 / – | 3.65 / 4.34 | 3.57 / – | 3.51 / 3.85 |

(– = not extracted; the full p50/p95/max/mean for every stage is in the JSON reports.)

Atlas upload is 0 in steady state (`atlasUploadsDelta` 0; `upload()` only copies the per-batch vertex/index buffers). Layout/shaping is 0 in steady state (the prepared layouts are reused; no shaping in the window).

#### Verdict: CPU-bound in Kotlin animator evaluation

- **720 glyphs, playback:** evaluation is **65 of 81 ms (81%)**. GPU work is about 6.9 ms (text 3.2 + present 3.7), but every frame waits for it serially (submit → fence, present → `vkQueueWaitIdle`), so about 11–12 ms of each frame is spent waiting on the GPU.
- **Static 720-glyph frame (no evaluation):** 16.5 ms p50, and 13.5 ms of that is waiting. The present blit costs 7.6 ms GPU when the GPU clocks down on an idle-ish frame.
- **4096 glyphs:** evaluation is **870 of 898 ms (97%)**. Mesh build adds 10.6 ms and the GPU waits add 10.9 ms.
- **GPU fill rate is not the problem.** The text pass is 1.2–3.6 ms for these small glyphs (8–72 px).

#### What the CPU time is (simpleperf, render thread, 720 playback, cpu-cycles)

- **About 55–60% is the ART interpreter** (`ExecuteSwitchImplCpp` 21%, `DoCall` 16%, `GetFieldInfo` 3%, plus allocator time for shadow frames).
  - **`PreparedTextAnimation.evaluate` is never JIT-compiled.** It is 5,123 dex code units with 115 registers, about 8× the next-largest hot method (`dexdump`: `PreparedSelectorEngine.coverage` 851, `renderTarget` 1,357).
  - `Jit::MaybeDoOnStackReplacement` takes 3–5% by itself: OSR is attempted on loop back-edges and never succeeds.
- **About 15–20% is libcore math and buffer intrinsics routed through the generic JNI trampoline** in the debuggable JIT (`artQuickGenericJniTrampoline`, `GetShorty`, `BuildGenericJniFrameVisitor`):
  - `Math.pow` from `interpolateColour` (sRGB↔linear): 12.7% inclusive;
  - `DirectByteBuffer.putFloat` → `Memory.pokeInt`: 3.9%;
  - `sin`/`cos`/`tan` in `SelectorMath.affine`: 4%;
  - `Math.abs` → `doubleToRawLongBits`;
  - `coerceIn` (2–6% self, not inlined).
- **The idle prewarm competes for the CPU.** `Fluxx-text-idle` (`TextIdlePrewarm.foreground`) used **48–66% of all app CPU samples** during playback, about one full core, rasterising bundled faces while frames render.
- **4096-glyph playback** has the same profile (interpreter 35%+, JNI trampolines 13%, `pow` 3.4%, `coerceIn` 3.6%).
- **Not hot:** JNI crossings per glyph (one `textLayer` call per layer), re-layout/re-shaping, tier selection (≤ 1.2 ms at 720), atlas upload, draws (one indexed draw per layer), and command recording (≤ 0.4 ms).

#### Instrumentation added in 10a

- `vulkan_renderer.{h,cpp}`: stage accumulators, a 4-entry timestamp query pool (created only where the graphics queue reports `timestampValidBits`), ATrace sections, and `frameStages(out)`.
- `jni_bridge.cpp` and the debug source set `TextDebugBridge.kt`: `frameStages(session, LongArray)`, with `STAGES = 12`.
- `CompositionRenderer.kt`: `stageNs`, `frameStages(out)`, `STAGE_*` constants, and `android.os.Trace` sections on frame and tiers.
- `TextPerfProfileTest` (androidTest): the harness above.
- `PreparedTextAnimationGoldenTest` (JVM): SHA-256 of every evaluation output for four F2 animator stacks at 11 times, **recorded before any fix**. It is the bit-identity gate for 10b.

### 10b. Fixes, in order of measured impact

Every fix keeps the output bit-identical. Proof:
- `PreparedTextAnimationGoldenTest` still produces the four hashes recorded from TEXT FIX 9. It also asserts that the record-based tier inputs equal the old buffer scan at every sampled time.
- `SrgbCodecTest` compares the codec against the `pow` formulas: all channel pairs, 2.5 M random inputs, and the 129 doubles around each of the 255 byte boundaries.

1. **`PreparedTextAnimation.evaluate` split into stage methods** (`evaluateSelectors`, `evaluateAnimator`, `evaluateLines`, `positionClusters`, `applyAnimator`, `writeRecords`, `evaluateBounds`, `characterBounds`).
   - The statements are the same, in the same order. Cross-stage locals became fields with the same names.
   - The 5,123-unit method was never JIT-compiled; every stage now compiles.
   - The 96-byte modifier records are staged in a `FloatArray` and copied in one bulk `FloatBuffer.put`. Before, each float was its own `DirectByteBuffer.putFloat` → `Memory.pokeInt` JNI call, 22 per cluster.
   - The SDF tier inputs (`maxGlyphScale`, `maxModifierDistanceEm`) read the same floats from the staging array instead of `getFloat` on the direct buffer.
2. **`SrgbCodec`**: exact table forms of the sRGB transfer functions, used by `interpolateColour` and `TextAnimatorColour.blend`.
   - Decoding uses a 256-entry table of `linear(i/255.0)`.
   - Encoding brackets the value between the analytic byte thresholds and falls back to the formula within 1e-9 of a threshold. It finds the bracket with a 1,024-bucket lookup plus a short forward scan.
   - Before, these were six `pow` calls (through JNI here) per colour interpolation.
3. **Remaining JNI-routed and out-of-line helpers in per-glyph loops.**
   - `SelectorMath.clamp`/`magnitude`/`least`/`greatest` are inline and equal `coerceIn`/`abs`/`Math.min`/`Math.max`, including signed zeros and NaN pass-through. Kotlin's versions are out-of-line calls, and min/max/abs call `doubleToRawLongBits` (JNI here) on zeros.
   - `f - f == 0f` is an exact `isFinite()`, which otherwise goes `Math.abs` → `floatToRawIntBits`.
   - `AffineTrig` memoises sin/cos/tan on exact radian inputs. The axis is constant per animator; rotation and skew repeat whenever weights repeat.
   - Property ordinals are read once per animator; `coerceIn` with possibly empty ranges stays exact, including its exception.
4. **No `vkQueueWaitIdle` after present.**
   - That call held the render thread for the whole present blit: 3.6–8 ms per frame.
   - GPU order already covers every later use:
     - the offscreen render pass has an external dependency on `TRANSFER` reads (the blit);
     - `render()` waits `mInFlightFence` before reusing the present command buffers;
     - readback, resize and teardown wait on their own fence or `vkDeviceWaitIdle`.
   - **The per-batch fence wait (`submitWork`) is unchanged**, because resource lifetime depends on it (decoder images and mesh storage are reused after a flush).
5. **Wiggly noise and character values without JNI** (commit `296908c`, found by the 10d F2 run, where the per-character fixture was still 66 ms p50).
   - simpleperf on `characterRepresentativeStages` playback: `WigglySelectorMath.noise` was **46%** of the render thread, mostly `Math.floor` through the generic JNI trampoline. It made 4 floor calls per noise sample and 2 samples per value.
   - `SelectorMath.floor` is an exact inline `Math.floor`: signed zero, infinities and integral values ≥ 2^52 pass through unchanged, and NaN stays NaN.
   - The SplitMix axis hashes (x, x+1, y, y+1, dimension) are computed once per value and shared by the four corners and both samples. That is 17 `mix` calls per value instead of 40, and XOR plus wrapping addition keep the result exact.
   - `TextCharacterPreparation.valid` makes one `Character.getType` call (JNI) instead of two and checks finiteness without `Math.abs`.
   - **Proof:**
     - `SelectorFloorTest`: 6 M random doubles across every exponent, plus edge values; bit-equal to `Math.floor`. NaN input only has to give NaN, because HotSpot's own `Math.floor` returns different NaN payloads in interpreted and compiled code.
     - `WigglyReferenceTest`: 300 k random parameter sets plus non-finite inputs, bit-equal to a verbatim copy of the TEXT FIX 9 noise; `valid()` equals the old expression for every codepoint and 200 k random values.
     - The golden hashes, whose advanced stack includes Wiggly selectors and character offset/value, are unchanged.
   - **A16 result:** character-representative playback went from frame **68.2 / 70.4** to **29.7 / 31.7** ms (p50 / p95), and evaluation from **58.6 → 21.5** ms.

#### Candidates checked and not changed (the profile shows they are not costs)

| Candidate | Result |
|---|---|
| Re-layout / re-shaping when nothing changed | 0 shapes in steady state; prepared layouts are reused (`shapesDelta` 0). |
| Per-frame glyph-key hashing or tier selection for unchanged layers | `prepareText` reuse is 0.04–0.07 ms; tier selection is under 0.5 ms at 720 glyphs. |
| Uploading unchanged atlas data | `atlasUploadsDelta` 0 in steady state. `upload()` copies only the frame's vertex/index buffers. |
| One draw per glyph | One indexed draw per layer. |
| Per-glyph JNI calls | One `textLayer` call per layer, with modifiers in one direct buffer. |
| Redundant mesh rebuilds | One mesh build per layer per frame (1.7–2.0 ms at 720, 10–11 ms at 4096). |
| SDF fragment cost and oversized quads | The GPU text pass is 1.0–3.4 ms; quads are the glyph box plus the tier spread. |
| Sync points | Fixed the post-present idle wait (above). The batch fence wait is the batch contract. |

#### Cache invalidation rules

This pass adds four caches. Each one is keyed on everything that affects its output.

| Cache | Key | Invalidated by | Test |
|---|---|---|---|
| `SrgbCodec` tables | The formulas themselves (constant). | Never: the tables equal the formula for every input, with the formula used near thresholds. | `SrgbCodecTest` (exhaustive per input). |
| `AffineTrig` | Exact radian input bits, including the sign of zero. NaN never matches. | Any change in the input bits recomputes. | `PreparedTextAnimationGoldenTest`: animated rotation, skew and axis across 11 times and four stacks. |
| Staged records (`records`) | Rewritten in full by every `evaluate(time)`. The time memo (`evaluatedTime`) is unchanged from TEXT FIX 9. | Every evaluation. | The golden test hashes the buffer after every evaluation, including a repeated time and backward seeks. |
| External-tier settle (`externalSettled_`) | (layout, tier, epoch). | Epoch bump on atlas repack, on eviction of an external CPU tier glyph, and on deferred worker replies. `forget()` erases the node. | `ImportedTierAcceptanceTest` (device), with external requests and supplies re-verified in the 10d suites. |

### 10c. Idle prewarm and native allocations

- **`TextIdlePrewarm.foreground` pauses while frames render.**
  - `CompositionRenderer.renderTarget` marks `TextIdlePrewarm.activity` (a `FrameActivityGate`, one volatile store, no allocation) on every frame: preview playback, scrubbing and export.
  - The prewarm steps through `IdleWarmLoop`, which returns `PAUSED` while a frame arrived within the last 750 ms. It resumes from the same cursor when the renderer is quiet.
  - **A16 result:** `Fluxx-text-idle` went from **48–66% of all app CPU samples** during playback (before) to **0.2%** (after, simpleperf).
  - `IdleWarmLoopTest`:
    - the rasterisation counter does not move across 600 simulated 60 fps frames, nor across irregular scrubbing gaps (30–400 ms);
    - it resumes on the same cursor after the quiet window;
    - a source check confirms every rendered frame marks the gate.
- **Rich and wrapped native allocations.** A temporary per-frame backtrace diagnostic was used here and removed before commit.
  - **598 of 600 frames had 0 native allocations** (rich and wrapped representative, a full 0–10 s sweep).
  - Frame 542, where the rich runs (run scale up to 1.22) cross into the 96 ppem tier, had 3,071 allocations. The atlas generation went from 1 to 2 and 240 tier glyphs were collected and packed: `std::map` nodes plus `std::string` font ids in the glyph keys.
  - Frame 543 had 2 allocations: 9d's external-tier settle map was cleared on repack and re-inserted one node per layout.
  - **Fix:**
    - settle entries are invalidated by an epoch instead of `clear()`;
    - the node is created on the tier-change frame (`setTier`).
  - **After: 599 of 600 frames allocate nothing.** The one frame that does is the tier switch itself (see Findings).

### Before / after (A16, debug APK, ms p50 / p95)

| Stage | 720 playback before | 720 playback after | 4096 playback before | 4096 playback after |
|---|---|---|---|---|
| **Frame** | **80.72 / 86.72** | **17.84 / 24.38** | **897.69 / 948.16** | **132.01 / 136.21** |
| Animator/selector evaluation | 65.39 / 73.30 | 9.89 / 12.09 | 870.05 / 918.91 | 113.12 / 116.74 |
| Tiers + evaluation | 66.58 / 74.58 | 10.31 / 12.61 | 875.57 / 924.72 | 114.69 / 118.21 |
| prepareText reuse | 0.06 / 0.07 | 0.06 / 0.07 | 0.07 / 0.08 | 0.06 / 0.11 |
| textLayer + native mesh | 1.67 / 1.82 | 1.89 / 2.43 | 10.57 / 11.39 | 11.00 / 11.21 |
| finish (waits) | 12.28 / 15.42 | 6.30 / 9.52 | 10.94 / 12.19 | 6.13 / 7.33 |
| ↳ batch record | 0.11 / 0.16 | 0.11 / 0.16 | 0.37 / 0.55 | 0.40 / 0.46 |
| ↳ batch submit + fence | 6.54 / 9.36 | 4.70 / 7.75 | 4.70 / 5.63 | 3.96 / 4.78 |
| ↳ acquire | 0.15 / 0.25 | 0.13 / 0.32 | 0.16 / 0.24 | 0.14 / 0.18 |
| ↳ present record + submit | 1.17 / 1.87 | 0.86 / 1.44 | 1.34 / 1.81 | 0.98 / 1.30 |
| ↳ post-present idle wait | 3.75 / 4.98 | **0 (removed)** | 3.64 / 4.14 | **0 (removed)** |
| GPU text batch | 3.21 / 5.77 | 2.77 / 4.43 | 1.19 / 1.65 | 1.02 / 1.44 |
| GPU present blit | 3.65 / 4.34 | 3.84 / 15.50 (1) | 3.51 / 3.85 | 3.30 / 3.83 |

(1) The GPU present span starts at `TOP_OF_PIPE`, before the blit's wait on the swapchain-acquire semaphore. It therefore includes display pacing (vsync) when a frame waits for a free image; it is not shader time.

Other modes (after):

| Case | Frame p50 / p95 | Evaluation p50 |
|---|---|---|
| 720 static | 16.58 / 17.88 (vsync-bound: the batch waits behind the paced present) | 0.01 |
| 720 scrub | 20.79 / 26.84 (before: 85.37 / 91.52) | 10.44 |
| 720 rich playback | 16.58 / 20.84 | 9.24 |
| 720 wrapped playback | 16.54 / 21.54 | 9.24 |
| 720 character playback | 29.73 / 31.71 (before fix 5: 68.17 / 70.43) | 21.45 (before fix 5: 58.58) |
| 4096 static | 15.99 / 18.99 (before: 29.81 / 32.63) | 0 (mesh 10.16) |
| 4096 scrub | 126.11 / 134.37 (before: 889.60 / 896.53) | 108.86 |

Run-to-run variation is about ±1 ms at p50 and ±3 ms at p95; another 10b run measured 720 playback at 16.86 / 18.98. The render thread ran on the A76 cores for 99.5% of its samples (simpleperf `--cpu 6-7`). Thermal status was 0 in every mode.

#### Targets

- **720 glyphs, p95 < 16.6 ms: not met on the debug APK.**
  - Measured: **p50 16.5–17.8 ms, p95 19–24 ms** (rich and wrapped p95 20.8 / 21.5). Before: p50 80.7, p95 86.7.
  - The remaining floor is about 12 ms of CPU plus about 4.7 ms of synchronous GPU batch wait:
    - about 10 ms of animator evaluation (8 animators × 4 selectors × 13 properties over 720 characters);
    - 1.9 ms of mesh build;
    - about 0.5 ms of other CPU work;
    - the batch fence wait, which includes queueing behind the previous frame's paced present.
  - Within evaluation, the largest remaining items are:
    - selector coverage, ease tables and `combine` (about 17% of the thread);
    - affine trigonometry via JNI (about 11%: the per-glyph weights differ, so the memo misses);
    - colour interpolation (about 7%).
- **4096 glyphs: 60 fps is not reachable** with the current design. Reachable now: **p50 126–132 ms, p95 134–136 ms**, versus 898 / 948 before. The floor has three parts:
  1. Evaluation is about 109–113 ms: 16 animators × 8 selectors over 4,096 characters is about 524 k selector-character evaluations plus 65 k affine compositions per frame on one core.
  2. The native mesh rebuild is about 10–11 ms. Even a static 4,096-glyph frame costs 16.0 / 19.0 ms.
  3. The synchronous batch wait is about 4 ms.

#### Next steps (not done here)

1. **Measure a non-debuggable build.**
   - The suites run on the debuggable APK, where libcore math (`sin`/`cos`/`tan`/`hypot`) and Kotlin parameter checks go through the generic JNI path and the JIT does not intrinsify them.
   - A `benchmark` build type (non-debuggable, debug-signed, with the debug-only harness) would give the number users actually see. It is a build-system change, so it was not made in this pass.
2. **Overlap GPU batch completion with the next frame's CPU work** by double-buffering batch resources.
   - This changes when Kotlin may reuse resources after `flush`/`finish`, which is the batch/draw contract.
   - Reported, not changed: it would save about 3–5 ms per frame.
3. **Evaluate animators on the second A76 core.** Selector coverage is independent per animator.
4. **Move per-cluster modifier application to the vertex shader**, so the 4,096-glyph mesh is static and the per-frame 10 ms rebuild goes away.
5. **Make tier-switch frames allocation-light** by interning font ids in `TextRenderer::Key` and using flat containers. A tier switch currently costs about 3 k allocations on that one frame.

### 10d. Suites and numbers

Every device run used the debug APK and `am instrument` directly, with no uninstall and no data clearing. APKs were installed with `adb install -r`.

#### App instrumented suite (`a16-perf10-suite`, 10c APK): 33 run, 26 passed, 7 failed

```
adb shell am instrument -w -r -e notClass com.fluxx.android.render.TextF2AcceptanceTest,com.fluxx.android.render.TextPerfProfileTest \
  -e textRunLabel a16-perf10-suite -e e1aLabel r28p10 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- **Passed:**
  - LayerTransformation 3, FontImportAcceptance 4, ImportedTierAcceptance 3, PaletteRepository 5, TextExplode 4;
  - F1 parity, TextFix 2, PaletteBrowser 1, TextInspectorLayout 3.
- **Failed, all environmental (not in this pass):**
  - `TextGpuProofTest` and the four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
  - F1 `fiveHundredGlyphs…` fails **only** on its final validation assertion. Everything else in its window is clean:
    - frame **16.38 / 18.63** ms p50 / p95 at 660 glyphs (TEXT FIX 9d: 33–37 ms p50);
    - native allocation Δ 0 and Kotlin Δ 0;
    - rasterisations, uploads, shapes and generation Δ 0. The 9d "prepared counter 1 changed" from the idle prewarm is gone.
  - `E1aGoldenExportTest` is missing its local source asset.

#### Core-engine instrumented: `TextCpuProofTest` 2 / 2 passed (runner `com.fluxx.android.engine.test/androidx.test.runner.AndroidJUnitRunner`)

#### F2 (`TextF2AcceptanceTest`, `a16-perf10-f2`, 10c APK): 20 run, 8 passed, 12 failed

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest -e textRunLabel a16-perf10-f2 \
  com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p10/f2.txt 2>&1 &"
```

The run took 5,031 s (84 min). Thermal status stayed 0.

- **Passed (8):** all seven pre-encode pixel parity tests (`randomAndReverse…` for plain, rich, wrapped, imported variable, character and advanced modifier, plus `characterRandom…`), `exportVisualPropertyFixtures` and `preparedTransportPreservesRichMetrics`.
- **Failed (12):** every performance test.
  - All 10 that complete fail on **p99 > 16.7 ms** and on **Vulkan validation** (the layer is absent).
  - The two maximum per-character and modifier tests hit the **15-minute `TimeoutException`** (see below).

| Fixture (pass 0 cold / pass 1 after 180 s thermal load) | glyphs | frame p50 ms | frame p95 ms | frame p99 ms | native `new` Δ | TEXT FIX 9d p50 |
|---|---|---|---|---|---|---|
| f2 representative | 660 | 19.0 / 22.0 | 25.7 / 31.4 | 28.1 / 32.4 | 0 / 0 | 33–37 |
| t1 rich representative | 720 | 17.1 / 18.5 | 24.8 / 24.0 | 27.4 / 25.6 | 1277 / 624 | 34 |
| t2 imported variable representative | 700 | 17.7 / 17.6 | 24.3 / 24.2 | 26.6 / 26.3 | 85407 / 4334 | 27.5 / 28.7 |
| t3 wrapped representative | 720 | 16.2 / 16.3 | 19.2 / 19.4 | 20.6 / 23.7 | 1277 / 624 | 35.6 / 34.8 |
| t4 characters representative | 720 | 66.5 / 66.3 | 72.9 / 68.6 | 1590 / 70.0 | 38269 / 1884 | 81.9 / 82.0 |
| t4 modifiers representative | 720 | 59.7 / 59.7 | 63.3 / 61.7 | 66.6 / 63.0 | 1277 / 624 | 94.0 / 93.9 |
| f2 maximum | 4096 | 124.6 / 125.0 | 135.1 / 129.0 | 143.9 / 133.9 | 0 / 0 | 330–341 |
| t1 rich maximum | 4096 | 125.5 / 125.4 | 129.7 / 128.9 | 132.4 / 131.1 | 3612 / 2063 | 438.9 / 437.6 |
| t2 imported variable maximum | 4096 | 124.7 / 124.5 | 128.5 / 128.3 | 132.6 / 130.2 | 65587 / 24582 | 434.4 / 433.9 |
| t3 wrapped maximum | 4096 | 129.9 / 129.8 | 133.5 / 133.6 | 138.0 / 136.1 | 3612 / 2063 | 330–341 |

- **Mesh build p95:** 1.9–2.7 ms at 660–720 glyphs and 10.2–10.8 ms at 4096, the same as 9d.
- **Kotlin allocation Δ:** 0 everywhere, except imported variable pass 0 (13 representative, 3 maximum). This is the known asynchronous imported-tier fetch, which also counts as 1 worker IPC.
- **Prepared counters 2/5** (atlas upload and generation) change in pass 0 of the maximum, character and imported fixtures. That is the asynchronous sharp-tier arrival inside the cold window, unchanged from 9d.
- **Native allocations in the rich, wrapped and modifier windows are unchanged from 9d** (1277 / 624 representative, 3612 / 2063 maximum).
  - The 10c per-frame diagnostic attributes them to tier-switch frames. 10c removed only the 2-allocation settle re-insert. Every other frame in the window allocates nothing.
  - F2 asserts on the window total, so it still fails. See Still open.
- **The character and modifier fixtures were the outliers.** Profiling the character fixture led to fix 5 (Wiggly and character values without JNI). The rerun is below.

#### F2 rerun of the affected tests after fix 5 (`a16-perf10-f2b`, APK at `296908c`)

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest#characterRepresentativeColdAndThermal,\
…#advancedModifierRepresentativeColdAndThermal,…#characterRandomAndReversePreEncodePixelsMatch,\
…#advancedModifierRandomAndReversePreEncodePixelsMatch,…#characterMaximumColdAndThermal,…#advancedModifierMaximumColdAndThermal \
  -e textRunLabel a16-perf10-f2b com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p10/f2b.txt 2>&1 &"
```

`Tests run: 6, Failures: 4`. The run took 2,137 s.

- **Passed:** `characterRandomAndReversePreEncodePixelsMatch` and `advancedModifierRandomAndReversePreEncodePixelsMatch`. Pixels are unchanged by fix 5.
- **`characterMaximumColdAndThermal` and `advancedModifierMaximumColdAndThermal` now finish** within the unchanged 15-minute limit:
  - **13 min 59 s** for the character test (in 9d and the 10c run, both timed out at 15 min);
  - **11 min 57 s** for the modifier test.

  Both still fail on p99, validation, native allocations and the cold-pass counters.

| Fixture (pass 0 / pass 1) | glyphs | frame p50 ms | frame p95 ms | frame p99 ms | native `new` Δ | 10c run p50 | TEXT FIX 9d p50 |
|---|---|---|---|---|---|---|---|
| t4 characters representative | 720 | 30.7 / 30.5 | 33.5 / 32.4 | 1556 / 33.3 | 38269 / 1884 | 66.5 / 66.3 | 81.9 / 82.0 |
| t4 modifiers representative | 720 | 25.6 / 25.5 | 27.8 / 27.9 | 29.0 / 29.2 | 1277 / 624 | 59.7 / 59.7 | 94.0 / 93.9 |
| t4 characters maximum | 4096 | 399.2 / 398.5 | 406.2 / 403.6 | 1923 / 406.4 | 31635 / 34566 | timeout | timeout |
| t4 modifiers maximum | 4096 | 341.4 / 340.7 | 348.8 / 346.3 | 364.2 / 354.0 | 3612 / 2063 | timeout | timeout |

- **Characters maximum** also changes prepared counters 2/5 in pass 1, and counter 1 (rasterisations) in pass 0.
  - With 4,096 glyphs whose codepoints change every frame, the per-character catalogue keeps generating and uploading glyphs during the window.
  - The pass 0 p99 of about 1.9 s, and the representative 1.56 s, is the cold first catalogue preparation, which lands inside the window.
  - Both are as in 9d.

#### JVM

`:app:testDebugUnitTest`: **390 tests, 0 failures**.
- That is 357 before TEXT FIX 9d, plus 20 from 9d, the golden test (1), `SrgbCodecTest` (3), `IdleWarmLoopTest` (4), `SelectorFloorTest` (2) and `WigglyReferenceTest` (3).
- `core-engine` has no JVM tests.

### Deviations

1. **Perfetto traces were captured but not analysed with `trace_processor`.** The session's permission policy blocked downloading the Windows `trace_processor_shell`.
   - The per-stage numbers come from the in-app stage timers and GPU timestamp queries.
   - The traces are available for ui.perfetto.dev: `perf10-rep-playback.pftrace` and `perf10-max-playback.pftrace`, kept outside the repo.
   - GPU frequency is in their `gpu_frequency` events. The sysfs GPU clock nodes are not readable from the shell.
2. **All numbers are from the debug (debuggable, JIT-only) APK**, which is what the suites and Android Studio installs run. A release-like measurement needs a new build type (see Next steps).
3. **The 720-glyph p95 target is not met** (19–24 ms against 16.6 ms). Numbers and the floor are reported above, and no limit was changed.
4. **The batch fence wait was kept** (batch/draw contract). Only the post-present `vkQueueWaitIdle` was removed. This is GPU synchronisation inside `finishFrame`; the Kotlin-visible flush/finish semantics are unchanged.
5. **`TextF2AcceptanceTest.fixture` is now `internal`** (was `private`), so the profiling harness builds the identical fixtures.

### Still open

- **Vulkan validation-layer tests** (the layer cannot be installed on the phone in this setup):
  - `TextGpuProofTest`;
  - the 4 `TextGpuAcceptanceTest` tests;
  - the final validation assertion in F1 and in every F2 performance test.
- **`E1aGoldenExportTest`:** the source video asset (`app/src/androidTest/assets/e1a/source.mp4`, git-ignored) is missing.
- **720-glyph p95 under 16.6 ms**, and **60 fps at 4,096 glyphs** (Next steps above).
- **Tier-switch frames allocate.** They allocate natively (about 3 k on the switch frame, from collect and pack) and spike in CPU time. These are not steady-state frames, but the F2 harness counts them whenever the size animation crosses a tier inside its window, so the F2 rich, wrapped and modifier windows still total 624–3,612, unchanged from 9d.
- **Per-character catalogue churn.** The character fixtures still allocate natively after warm-up (1,884 per window at 720 glyphs and 34,566 at 4,096 after warm-up) and keep uploading at 4,096 glyphs, because the animated codepoints keep generating catalogue glyphs. This is design work in the T4 catalogue, not in this pass.
- **The host JVM allocation proof is intermittent.** `PreparedTextAnimationTest.hostPreparedEvaluationAllocatesZeroAfterWarmup` reported 352 bytes in 2 of about 6 full runs while the exhaustive `SrgbCodecTest` was keeping the host JIT busy. It passed every isolated rerun, and the device ART counters are 0. It looks like a HotSpot deoptimisation inside the measured window.

### A16 manual test list (debug build, installed with `adb install -r`)

1. **Playback, 700-ish glyphs.**
   - Make a 1080×1920 project with one text layer of about 30 lines (for example `AV office gy 0123456789` repeated) at 40–70 px.
   - Add 2–4 animators, each with a Range selector (Offset keyframed from -100 to 100) changing Position, Rotation, Scale, Opacity and Fill colour.
   - Play it. It should look smooth with no stutter, and the Fill colour transition should look the same as before (no banding or colour shifts).
2. **Scrubbing.** Drag the playhead quickly back and forth across the animation for 20 seconds.
   - The text follows your finger without lag spikes.
   - Releasing the playhead leaves a sharp, settled frame.
3. **Static frame.** Leave the playhead still on a frame mid-animation for 10 seconds.
   - Nothing flickers.
   - Glyphs look identical to TEXT FIX 9: same sharpness, no holes in Playfair, same stroke width.
4. **Large text and tier switch.** Animate the text size from 30 to 300 px over 3 seconds and play it.
   - The text becomes sharper as it grows, with at most a brief one-frame hitch at the moment it sharpens.
   - No blur remains after it settles.
5. **Imported fonts.** Repeat steps 1 and 4 with an imported variable font (Playfair or Source Serif).
   - Same smoothness.
   - The large sizes are sharp after a moment.
6. **Big paragraph.** Paste about 4,000 characters into one layer with 8+ animators.
   - Playback will be slow (about 7–8 fps is the measured floor on this phone; see the doc).
   - It must not freeze, crash or show an error, and scrubbing must stay responsive.
7. **Idle prewarm.** After opening a project, keep the app idle for 5 seconds, then open the Fonts page.
   - Bundled font previews appear immediately.
   - During playback (step 1) the phone should run cooler than it did on TEXT FIX 9, because the background prewarm no longer runs while frames play.
8. **Eyedropper and export.** Pause on a frame, use the colour eyedropper on the text fill, then export a 3-second clip.
   - The eyedropper colour matches the text (the frame is still read correctly after the present-wait change).
   - The exported video matches the preview frame for frame.
9. **Backgrounding.**
   - Press Home during playback, come back, and play again: the preview recovers.
   - Rotate the phone or resize the window (split screen) while paused: the preview redraws correctly.

---

<a id="rec-text-perf-11"></a>

## TEXT PERF 11: multi-layer text cost, resume/rotation bugs, imported-font latency

*Formerly `docs/checkpoints/TEXT_PERF_11.md`.*

Branch `text-perf-11` (from master `20671a6`). Device: Galaxy A16 (SM-A165F, Helio G99, Mali-G57 MC2), Android 16. All numbers come from the **debug APK** (debuggable, JIT-only), installed with `adb install -r`. Measurements were run without tapping the UI. Device settings changed during the runs (stay-on, rotation, `wm size`) were restored; see [Device settings](#device-settings).

Commits:

| Commit | Content |
|---|---|
| `affdcc2` 11a | Instrumentation only: per-layer counters, multi-layer harness, lifecycle, rotation and imported-font tests. |
| `0b3de93` 11a follow-up | Fair fixture spacing, stacked/imported variants, replay phase. |
| `01d598d` 11b | Merged text batches, shared track memo. |
| `42f4dc4` 11c | Rotation/resize keep the project; playback clock gate; streaming test; tier catalog retention. |
| `53bf4dc` 11c follow-up | The canvas keeps height in short windows. |
| `bc19e5c` 11d | Imported-font caches, keep-warm, imported settle, idle tier and JIT warm-up. |
| `1d2ccb3` 11c follow-up | The swapchain follows the reported surface size (stale extent after rotation). |
| `fcfd386` 11d follow-up | Tier warm-up sent from settled frames only (fixes the F1 allocation regression the suite caught); warm counters; `TierWarmUpTest`. |
| 11e | This document. |

### 11a. Profile (before any fix)

#### How it was measured

- **Fixtures** (`TextMultiLayerFixture`). The text is the F2 representative line `AV office gy 0123456789` (22 glyphs).
  - `single30`: **one layer** holding that line 30 times (the PERF 10 baseline).
  - `layers30` / `layers60`: **30 / 60 separate layers**, one line each.
  - Each row sits where the single layer draws its line: the native line height of the font, with the row position keyframed with the same times and easing as the size. Both cases therefore cover the same area.
  - The first run spaced rows for 40 px, so at 300 px the layers overdrew each other and the GPU time was not comparable. It was discarded.
  - Three kinds:
    - `plain`: 40 px, no animators;
    - `animated`: the F2 representative stack (8 animators × 4 selectors × 13 properties, size 8 → 72 px);
    - `size`: font size keyframed **30 → 300 px over 3 s** on every layer.
- **Modes and frames.**
  - The modes are static, scrub (seeded random seeks) and playback (consecutive 60 fps frames).
  - Each mode runs 120 warm-up frames and 300 measured frames. Scrub and playback stay inside the 3 s span for `size`.
- **Counters.** All are diagnostic only.
  - Kotlin stages (PERF 10), plus text layers drawn, mid-frame flushes and tier JNI time.
  - Native stages (PERF 10), plus text layers, native mesh build, re-collect+pack time and count, atlas upload, tier calls, glyph-placement lookups and (from 11b) merged flushes.
  - Per-frame Kotlin (`Debug.getThreadAllocCount`) and native `new` counts.
  - Window deltas of shaping, rasterisation, uploads and generation.
- **Command.**

  ```
  adb shell am instrument -w -r -e class com.fluxx.android.render.TextPerfProfileTest#multiLayerStages \
    -e textRunLabel <label> com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```

  Narrow a run with `-e perfCases layers30-size,…` and `-e perfModes playback`.
- **Profile.** simpleperf: `simpleperf record --app com.fluxx.android.debug -g --duration 10 -f 2000` during `layers30-animated` playback.
- **Thermal.** Status was 0 in every mode.

#### Before: frame and batch cost (ms, p50 / p95, playback)

| Case | Frame | Batch submit + fence wait | GPU batch (sum) | Submits per frame | Evaluate | textLayer + mesh |
|---|---|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 | 8.8 / 13.5 | 2.8 | 1 | 1.7 | 3.8 |
| **layers30-plain** | **23.6 / 29.3** | 12.5 / 16.1 | 6.3 | **8** | 2.1 | 4.5 |
| layers60-plain | 50.1 / 56.4 | 28.6 / 33.0 | 11.6 | 15 | 4.0 | 9.7 |
| single30-animated | 16.6 / 23.5 | 4.7 / 7.6 | 2.6 | 1 | 8.9 | 1.7 |
| **layers30-animated** | **45.2 / 58.7** | 19.6 / 23.9 | 8.0 | **8** | **18.6** | 3.5 |
| layers60-animated | 65.6 / 90.6 | 19.6 / 32.2 | 9.7 | 15 | 32.0 | 6.4 |
| single30-size | 16.7 / 18.7 | 8.6 / 13.4 | 6.2 | 1 | 1.7 | 3.9 |
| **layers30-size** | **32.2 / 35.5** | 19.8 / 22.8 | 11.0 | **8** | 2.1 | 4.9 |
| layers60-size | 53.3 / 58.1 | 30.3 / 34.2 | 14.7 | 15 | 4.1 | 9.9 |

Scrub and static show the same pattern. The full tables are under [Before / after](#before--after-a16-debug-apk).

#### Per-layer mean cost before (30 layers, playback, ms per layer)

| | plain | animated | size |
|---|---|---|---|
| Batch submit + wait | 0.42 | 0.63 | 0.66 |
| Kotlin `flush()` (mid-frame submits, inside "other") | 0.44 | 0.63 | 0.68 |
| Animator evaluation | 0.06 | **0.65** | 0.07 |
| Tier policy (incl. evaluation) | 0.09 | 0.68 | 0.11 |
| `textLayer` JNI | 0.14 | 0.12 | 0.16 |
| ↳ native mesh build | 0.11 | 0.10 | 0.14 |
| Batch record | 0.03 | 0.02 | 0.03 |
| Batches per layer | 0.27 | 0.27 | 0.27 |

#### Verdict: the cost is a submit + fence wait per 4 layers, then per-layer animator track evaluation

1. **Batch flush and fence per 4 layers (all cases).**
   - Kotlin flushes every 4 entries (the four-entry batch/draw contract).
   - Before this pass, each flush recorded a full render pass on the 1080×1920 target (load + store), submitted it and waited for its fence. 30 layers cost **8 round trips per frame** and 60 layers 15.
   - The wait is 12–30 ms of a 24–53 ms frame. The GPU adds about 0.6 ms per extra pass, just for the target load/store: GPU batch time is 6.3 ms for 30 layers against 2.8 ms for the same glyphs in one layer.
   - This is the top cost in every multi-layer case.
2. **Per-layer animator overhead (animated only).**
   - Thirty layers evaluate in 18.6 ms against 8.9–10.9 ms for the same 720 glyphs in one layer.
   - simpleperf on the render thread (30 animated layers): keyframe tracks account for **23%** inclusive (`AnimatableProperty1D.evaluate` → `KeyframeEvaluator.evaluate1D`, with `FrozenList.get` and `last()`), and colour interpolation for another **8.5%**.
   - Each layer evaluates its own ~650 animator, selector and layer tracks per frame. With duplicated layers they are identical.
   - Selector coverage (31%) scales with the total glyph count, not the layer count.
3. **Not costs.** Measured, not assumed:
   - `textLayer` JNI and mesh build: 0.1–0.16 ms per layer.
   - Tier policy: about 0.1 ms per layer.
   - Shaping, rasterisation and atlas uploads in the window: all Δ 0.
   - Kotlin allocations: 0.

#### The specific checks

- **Does each text layer trigger its own batch submit and fence wait?** One submit per **4** layers, not per layer, with a fence wait each (above). See 11b for the fix and why it stays within the contract.
- **Does animating font size re-layout or re-shape per frame per layer?** **No.**
  - In the `size` windows, `shapingCpuNs` and `rasterizations` Δ are 0.
  - The mesh scales advances and quads by `size / upem`.
  - Layouts are keyed by (font, shaping document, ligature mode), with no size in the key. The 30 layers with equal text share **one** layout handle.
  - Re-scaling rather than re-shaping is already what happens, and it is exact.
- **Do tier switches cause per-layer repacks, collection or allocation spikes?** No; they are already batched across layers.
  - The tier policy takes the per-handle maximum and makes one `textTiers` JNI call per change frame. Hysteresis is 15%.
  - In 300 playback frames of `layers30-size` (1.7 loops of 30 → 300 px) the counts were:
    - 4 tier calls, 4 re-collects and 4 placement lookups (one shared layout);
    - 0 atlas uploads (the warmed atlas retains every tier);
    - 92 native allocations, all on those 4 frames (the PERF 10 tier-switch finding).
  - Collect+pack costs at most 1.2 ms (p95 0 in playback).
  - Nothing to change here.
- **Per-layer allocations, JNI crossings, lock contention, font/face lookups, GPU resources.**
  - Kotlin allocations Δ 0 in every window.
  - Native allocations: 0, except the tier-switch frames above.
  - JNI per layer: 1 `textLayer` call, plus 1 `flush` per 4 layers.
  - Tier mutex wait: 1–2 µs in total per 300-frame window.
  - No font or face lookups per frame: no shaping or rasterisation, and one shared layout.
  - No per-layer GPU resources: one shared vertex/index buffer and atlas, resized only during preparation.

### 11b. Multi-layer fixes (in order of impact)

#### 1. One GPU submit per frame for text layers (`01d598d`)

**Change.** `VulkanRenderer::flushBatch` behaves as follows.

- If the batch Kotlin flushes holds **only text entries**, it now moves them to a pending list instead of recording, submitting and waiting.
- The next real submit records everything pending in draw order, in **one** render pass: pending text first, then the current batch. A real submit happens on:
  - any flush whose batch contains a raster (video, image or solid) entry;
  - `finish`;
  - running out of merged mesh storage.

**Why this stays inside the batch/draw contract.**

- The Kotlin-visible API is unchanged: `textLayer` / `layer` / `flush` / `finish` arguments, the 4-entry limit (native still rejects a fifth entry), and Kotlin still flushing every 4 entries.
- The contract's purpose is that after `flush` returns, nothing Kotlin owns is still in use by the GPU.
  - A **text** entry owns nothing Kotlin can release. Its mesh is built into native storage and its matrix copied when `textLayer` returns; the modifier buffer is read only during that call.
  - Every batch containing a Kotlin-owned resource (a decoder image or `HardwareBuffer`) still submits and **waits for its fence before `flush` returns**, together with all pending text, and so does `finish`.
- The fence wait is therefore kept everywhere it protects a resource. **Please review this reading of the contract**; it is listed under Deviations.

**Storage.**

- Merged batches need mesh room for every text layer since the last submit.
- `TextRenderer::reserveFrame()` runs during preparation, after the previous submit's fence. It reserves last frame's demand, bounded to **16,384 glyphs** (8,192 on low-RAM devices), about 4.5 MB for vertices and indices.
- `meshFits()` submits early beyond that, so correctness never depends on the reserve.
- Steady-state frames neither allocate nor split (native allocation Δ 0).

**Bit-identity.** `TextMergedBatchParityTest` (device, passed) renders every frame twice, with merging on and off (`TextDebugBridge.mergeTextBatches`), and compares full RGBA readbacks. It covers:

- 30 layers plain, animated and size, and 60 layers animated, at 6 times each, including tier switches;
- 30 animated layers with translucent solid layers interleaved, so raster entries force real submits between merged text.

It also asserts **1** submit per frame with merging against **8** without.

#### 2. Shared keyframe-track memo (`01d598d`, `TrackMemo`)

**What it does.**

- A track is a pure function of (track, time), so equal tracks now share one slot per prepared project.
- The first layer evaluated at a time computes the value; layers with an equal track read it back.
- Slots are resolved during preparation, so per frame a slot costs one comparison of the last time and allocates nothing.

**Equality and confinement.**

- Equality is the data-class equality of the track: static value, keyframes with their times, values and easing. Floats compare as `Float.equals`, so `-0` and `+0` stay apart.
- That covers everything the value depends on.
- The memo is confined to the renderer's thread. Other users (UI bounds, the warm-up thread) get a private memo.

**Coverage.** Selector tracks (20 per selector), animator `enabled`/`anchorGrouping` and property values (scalar, vector, colour), and the layer's size, tracking, leading, baseline, fill, stroke colour and stroke width.

**Bit-identity.**

- `PreparedTextAnimationGoldenTest` still produces the four PERF 10 hashes.
- The new `sharedTrackMemoIsBitIdenticalAcrossLayers` evaluates three layers per F2 stack on one memo and compares full records, bounds and geometry, byte for byte, with private memos. The three layers are two equal ones and one with a different start track; they are evaluated interleaved, one at a different local time, with repeated and backward times.
- The host zero-allocation test still passes.

**Effect.** `layers30-animated` evaluation fell from 15.0 to 11.7 ms (the single layer takes 9.6 ms), and `layers60-animated` from 27.2 to 18.7 ms.

#### Candidates checked and not changed

| Candidate | Evidence |
|---|---|
| Re-shaping or re-layout on size change | `shapingCpuNs` / `rasterizations` Δ 0; layouts are size-independent and shared by equal text. |
| Tier-switch repacks per layer | One collect per tier-change frame for all layers; 0 uploads; hysteresis already 15%. |
| Per-layer JNI or allocations | 1 call per layer, 0.1–0.16 ms; Kotlin Δ 0. |
| Vertex-shader modifiers, animators on the second core, double-buffered batches | Not the top multi-layer cost after fixes 1–2. They remain next steps for the evaluation floor (below); they would change the batch/draw contract or the threading model, so they need your decision. |

### Before / after (A16, debug APK)

Before is the 11a build. After is the final code paths (round-two run on `bc19e5c`'s code). Each cell is frame p50 / p95 in ms. Batch wait is submit + fence. GPU is the summed text batches.

#### Playback

| Case | Frame before → after | Evaluate | Batch wait p50 / p95 | GPU | Submits |
|---|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 → **16.6 / 18.1** | 1.7 → 1.7 | 8.8 / 13.5 → 8.7 / 13.4 | 2.8 → 2.8 | 1 → 1 |
| layers30-plain | 23.6 / 29.3 → **16.5 / 18.1** | 2.1 → 2.2 | 12.5 / 16.1 → 5.4 / 7.1 | 6.3 → 3.0 | 8 → 1 |
| layers60-plain | 50.1 / 56.4 → **22.5 / 24.8** | 4.0 → 3.7 | 28.6 / 33.0 → 6.6 / 7.5 | 11.6 → 3.7 | 15 → 1 |
| single30-animated | 16.6 / 23.5 → **19.7 / 26.2** (1) | 8.9 → 9.6 | 4.7 / 7.6 → 5.9 / 8.9 | 2.6 → 3.0 | 1 → 1 |
| layers30-animated | 45.2 / 58.7 → **22.4 / 27.5** | 18.6 → 11.7 | 19.6 / 23.9 → 5.9 / 8.6 | 8.0 → 3.1 | 8 → 1 |
| layers60-animated | 65.6 / 90.6 → **32.9 / 35.6** | 32.0 → 18.7 | 19.6 / 32.2 → 6.8 / 8.3 | 9.7 → 3.8 | 15 → 1 |
| single30-size | 16.7 / 18.7 → **17.0 / 18.6** | 1.7 → 1.7 | 8.6 / 13.4 → 8.1 / 9.3 | 6.2 → 6.5 | 1 → 1 |
| layers30-size | 32.2 / 35.5 → **18.7 / 21.2** | 2.1 → 2.0 | 19.8 / 22.8 → 8.4 / 12.4 | 11.0 → 6.5 | 8 → 1 |
| layers60-size | 53.3 / 58.1 → **29.1 / 31.0** | 4.1 → 4.0 | 30.3 / 34.2 → 10.4 / 11.7 | 14.7 → 6.7 | 15 → 1 |

**Reproducibility.** A third run on the final build (`a16-perf11-final`, playback, frame p50 / p95) gave:

- single30-plain 16.5 / 18.5; layers30-plain 16.5 / 18.1; layers60-plain 23.2 / 26.8;
- single30-animated 18.0 / 26.7; layers30-animated 24.1 / 28.6; layers60-animated 35.4 / 38.6;
- single30-size 17.5 / 19.0; layers30-size 18.9 / 20.8; layers60-size 27.5 / 30.7.

Every case had 1 submit per frame. The two after-runs agree within about ±2 ms at p50 and ±3 ms at p95.

(1) The single layer has no layer-count change. Its "before" run measured evaluation at 8.9 ms, the first 11a run at 10.9 ms and the round-one after run at 10.6 ms. This is run-to-run variation (±1–2 ms p50, ±3 ms p95, as PERF 10 noted), not a regression.

#### Scrub

| Case | Frame before → after | Evaluate | Batch wait p50 / p95 | Submits |
|---|---|---|---|---|
| single30-plain | 16.6 / 18.4 → 16.5 / 18.1 | 0.7 → 1.8 | 13.0 / 13.8 → 7.9 / 9.6 | 1 → 1 |
| layers30-plain | 27.0 / 32.9 → **16.6 / 18.2** | 2.4 → 2.4 | 14.0 / 18.4 → 4.5 / 6.9 | 8 → 1 |
| layers60-plain | 45.8 / 54.4 → **22.4 / 25.0** | 4.1 → 3.6 | 25.5 / 31.6 → 6.6 / 7.6 | 15 → 1 |
| single30-animated | 17.0 / 23.9 → 16.5 / 22.0 | 8.0 → 7.5 | 5.6 / 10.1 → 5.1 / 9.7 | 1 → 1 |
| layers30-animated | 49.4 / 60.5 → **23.7 / 28.0** | 20.8 → 12.1 | 20.2 / 26.0 → 6.5 / 9.6 | 8 → 1 |
| layers60-animated | 88.6 / 99.8 → **33.5 / 36.0** | 36.4 → 18.7 | 35.9 / 42.3 → 7.5 / 9.7 | 15 → 1 |
| single30-size | 17.8 / 35.3 → 17.0 / 34.4 | 1.0 → 1.0 | 7.9 / 10.9 → 7.8 / 10.3 | 1 → 1 |
| layers30-size | 34.6 / 41.9 → **19.6 / 21.9** | 2.1 → 2.0 | 20.8 / 27.2 → 8.4 / 9.8 | 8 → 1 |
| layers60-size | 48.4 / 57.0 → **26.1 / 30.7** | 4.0 → 3.8 | 28.0 / 33.2 → 8.9 / 11.4 | 15 → 1 |

#### Static

| Case | Frame before → after | Batch wait p50 / p95 | Submits |
|---|---|---|---|
| layers30-plain | 21.0 / 26.5 → **16.6 / 18.0** | 10.5 / 14.3 → 6.8 / 8.4 | 8 → 1 |
| layers60-plain | 37.3 / 42.9 → **21.8 / 23.3** | 19.9 / 24.3 → 6.8 / 9.8 | 15 → 1 |
| layers30-animated | 30.4 / 34.3 → **16.5 / 18.8** | 18.5 / 22.2 → 7.2 / 11.4 | 8 → 1 |
| layers60-animated | 54.7 / 62.1 → **23.9 / 25.5** | 33.8 / 40.0 → 7.9 / 10.9 | 15 → 1 |
| layers30-size | 29.2 / 33.2 → **17.9 / 19.3** | 19.0 / 22.3 → 8.5 / 9.6 | 8 → 1 |
| layers60-size | 49.1 / 54.6 → **26.1 / 27.9** | 30.5 / 34.4 → 10.6 / 12.1 | 15 → 1 |

The single-layer static rows are unchanged (16.5 / 18.0–18.2).

#### Per layer after (30 layers, playback, ms per layer)

| | plain | animated | size |
|---|---|---|---|
| Batch submit + wait | 0.19 | 0.19 | 0.28 |
| Kotlin "other" (mid-frame flushes) | 0.03 | 0.01 | 0.02 |
| Evaluation | 0.07 | 0.38 | 0.06 |
| Batches per layer | 0.03 | 0.03 | 0.03 |

#### Targets: 30 layers, p95 under 16.6 ms

- **plain and size: not met at p95, but at the single-layer floor.**
  - 30 layers now run like one layer: plain is 16.5 / 18.1 against single 16.6 / 18.1, and size is 18.7 / 21.2 against single 17.0 / 18.6.
  - p50 sits at the 60 Hz pacing (16.5 ms).
  - The p95 of 18–21 ms is the same floor the single 30-line layer has on this phone. The batch fence includes waiting behind the paced present of the previous frame (PERF 10 finding), and present pacing is not text work.
- **Size animation (the reported "lags badly and skips").** 30 layers went from 32.2 / 35.5 to **18.7 / 21.2**, and 60 layers from 53.3 / 58.1 to 29.1 / 31.0. The *skips* also had a separate cause, the streaming test; see 11c Part 2.
- **Animated (F2 stack on every layer): not reachable on the debug APK.**
  - 30 layers run at **22.4 / 27.5**, against 19.7 / 26.2 for the same glyphs in one layer.
  - The floor is evaluation:
    - about 9.6 ms for 720 glyphs (as for one layer);
    - about 2 ms of remaining per-layer evaluation (selector setup per layer);
    - mesh about 2 ms;
    - waits about 6 ms.
  - 60 layers: 32.9 / 35.6 ms, floor about 18.7 ms of evaluation.
  - Going lower needs the PERF 10 next steps (animators on a second core, vertex-shader modifiers) or a non-debuggable build. See Still open.

### 11c. Lifecycle

#### Part 2: background and resume during playback

**Where the clock lives.**

- `PcmPlayback`: the AudioTrack playback head. The preview worker (`PreviewController`) reads `positionUs` each frame.
- `PreviewController` itself is unchanged.

**What happens on Home.** Measured, not assumed:

- `MainActivity.onPause` pauses the preview, and its playback and clock are closed.
- `surfaceDestroyed` closes the controller and its renderer. That destroys the Vulkan device, the atlas and, before this pass, the sharp-tier glyph catalog.
- On return a new controller and renderer start at the editor's playhead.

**Reproduction without tapping.**

- The `PreviewLifecycleTest` harness wires a preview exactly as `MainActivity` does.
- `EditorLifecycleTest#homeDuringPlaybackInTheEditor` drives the **real** `MainActivity` and `EditorScreen` with an in-memory 30-layer size-animated document:
  - `PreviewController.play()` (what the Play button calls);
  - a real `input keyevent KEYCODE_HOME`;
  - 4 s in the background;
  - return to the task, then Play;
  - the editor playhead is sampled every 10 ms.

**Results** (11a app, 30 stacked layers):

| | Playhead during the 4 s stop | After return: first update after Play | Playhead jump at that update | Largest jump |
|---|---|---|---|---|
| Play 2 s after return | frozen at 2.166 s once the pause landed (1) | 356 ms | +100 ms | 133 ms |
| **Play immediately after return** | frozen at 2.100 s (1) | **1,277 ms** (1,194 ms stall) | **+733 ms** | 733 ms |
| Immediately, imported variable font | frozen at 2.100 s (1) | 754 ms | +266 ms | 266 ms |

(1) Frames already in flight when Home is pressed still report their positions (≤ 100 ms) before the pause lands; after that the playhead does not move for the whole 4 s.

**Root causes, with evidence.**

1. **The playhead does not advance while the app is stopped.** The pause holds it, and the 4 s gap shows no movement. The *jump* comes after Play.
2. **The clock started before the first frame of a playback was rendered.**
   - After a return, that first frame is cold: Vulkan init, text preparation, tier waits. It took 1.2 s.
   - The AudioTrack head ran during it, so the first presented frame was 733 ms ahead. A short clip ends in one or two such frames ("skips to the end").
3. **Slow frames counted as seeks.**
   - `renderPreview` decided "streaming" (playback) from the end-to-end time between frames (< 100 ms) and at most 3 frames of progress.
   - Any frame over about 96 ms, or playback under 20 fps (30 layers with size animation, a renderer rebuilt after Home, imported fonts), looked like a seek.
   - Each such frame then **blocked for up to 1.5 s** waiting for pending sharp tiers while the audio clock ran on. This is the "lags, stutters and skips" mechanism during playback itself.
4. **The rebuilt renderer started with no sharp tiers.** The tier catalog was held by a weak pointer that the destroyed renderer owned, so every 96/192/384 ppem glyph was rasterised again after each return.

**Fixes.**

- **`PlaybackGate`.** The track is filled immediately but plays only once a frame rendered after `play()` has been presented (`PreviewFrames`, counted in `renderPreview`). There is a 2 s safety hold, so audio never waits forever. Until then the position is the start position.
- **Streaming test.** It now uses the gap between the end of the previous call and the start of this one (< 100 ms), and accepts forward progress up to the elapsed wall time plus 3 frames. Seeks still settle and wait for sharp tiers; playback never blocks.
- **Tier catalog retained across renderers.** Memory stays within its own unpinned budget (16 MiB, 8 MiB on low-RAM devices).
- **Imported tiers** are cached across renderers (11d).

**After** (final code, Play immediately after return, 30 stacked layers): the first update arrives at 684 ms and the **playhead moves +33 ms** (one frame) against +733 ms. The largest jump is 133 ms against 733 ms, and frames run about 50 ms against 90 ms (merged batches). With the imported font: +33 ms against +266 ms.

**Test.** `PlaybackGateTest` (JVM) runs PcmPlayback's clock arithmetic with a fake clock and a fake head. It proves:

- the playhead stays fixed across a 60 s stopped interval;
- it does not jump during a 900 ms cold first frame after the return;
- it advances normally once that frame is presented;
- it is released by the hold if no frame is ever presented;
- paused frames drawn before Play do not open the gate.

**"Skips to the end" itself was not reproduced end to end.** The clip in the repro is 12 s. The measured 733 ms jump from one cold frame, and the up-to-1.5 s blocking per misclassified frame, are the mechanisms. Both are fixed and measured above.

#### Part 3: rotation and split screen closed the project

**Reproduction.** `EditorLifecycleTest#rotationResizeAndRecreationKeepTheProjectOpen`:

- the real `MainActivity`, editor opened on an in-memory document (no project file touched);
- `settings put system user_rotation 1`, then `wm size 1080x1500`, then `ActivityScenario.recreate()`, each restored after.

**Before:**

```
rotation: recreated=true destination=Shell document=true; resize: recreated=true destination=Shell document=true;
recreate: destination=Shell document=true
```

The Activity was **recreated**, not crashed or killed. The document survived in `EditorViewModel`, but the app showed Home.

**Cause.**

- `MainActivity` declared no `configChanges`.
- The destination was an Activity field, and `onSaveInstanceState` deliberately saved the editor as `"shell"` ("process restoration belongs to the existing editor lifecycle").

**Fix.**

- `AppNavigation` (a ViewModel) owns the destination and survives recreation together with the unsaved document.
- Saved state now carries `editor` plus the project file path. A **new process** (a fresh ViewModel with saved state) reopens that file from its last autosave. An editor without a file falls back to Home, since there is nothing on disk.
- The manifest handles `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation` in place.
  - For those changes Compose relayouts, and the preview surface gets `surfaceChanged`, then `resize`.
  - The renderer, decoders and tiers survive, and nothing is rebuilt.
  - Other changes (dark mode, locale, font scale) still recreate the Activity and go through `AppNavigation`.
- `AppNavigationTest` (JVM) covers save, restore, process death and round trip.

**After:**

```
rotation: recreated=false destination=Editor(projectFile=null) document=true; resize: recreated=false … document=true;
recreate: destination=Editor(projectFile=null) document=true
```

**Autosave on every path that can destroy the editor.**

- `onPause`, `onStop` and `onSaveInstanceState` each queue a save of the committed document. The queue survives the Activity, and `onStop` precedes destruction and process death.
- Leaving the editor saves first, via the existing `onExitClick` loop. System Back is routed to it by `EditorScreen`'s `BackHandler`.
- Rotation and resize no longer destroy anything.

**What landscape and short windows look like** (screenshots from the test, A16):

- **Before the follow-up (`53bf4dc`).**
  - In landscape the **canvas had 0 height**. The editor reserves its full inspector budget (268/328 dp) below the preview, which is more than a landscape phone window has.
  - Only the top bar, transport and timeline showed. In a 1080×1500 window the canvas was a strip about 50 dp tall.
- **After** (final screenshots).
  - Landscape: the canvas is a letterboxed portrait frame, 212 px high, above the transport and timeline. A 1080×1920 composition is small there (90 px text is about 10 px), but visible and live.
  - 1080×1500 window: the canvas takes the top 40%, and the text is readable.
  - Below 560 dp of window height the canvas keeps at least 45% of the space.
  - **Swapchain follow-up (`1d2ccb3`).** Rotating in place exposed a stale-extent path: the swapchain took `currentExtent`, which right after a resize still reported the old size, so the preview stayed slightly stretched (requested 2240×219, swapchain 2340×212). It now uses the reported surface size, clamped. Every logged resize now matches (2340×212, 2240×219, 980×996, 1080×401).
  - Portrait phone windows (about 780 dp and up) never reach this branch, so the accepted portrait layout is unchanged.
  - The inspector sheet in a short window gets the remaining height, which is cramped for Edit Text. It already has a compact mode below 320 dp.
- **Split screen** was not driven from the shell; window resize (`wm size`) stood in for it. It is in the manual list.
- **Follow-up for real landscape work** (not in this pass):
  - preview and timeline side by side;
  - the inspector as a side sheet;
  - landscape-aware timeline row heights;
  - a minimum touch-target audit in short windows.

### 11d. Imported fonts and first play

#### Where the wait goes (A16, ms)

| | static (274 KB TTF) before → after | variable (1.9 MB OTF) before → after |
|---|---|---|
| **Apply: metrics the canvas waits for** (`TextMetricsRepository.measure`) | **385 → 194** | **536 → 383** |
| Warm preparation, total | 405 → 183 | 472 → 383 (2) |
| ↳ send → reply (IPC + worker) | 208 → 166 | 249 → 360 (2) |
| ↳ outside the IPC (seed read/verify, hash, stores) | 197 → 16 | 223 → 23 |
| Bind → connected, worker alive | 3–5 | 2–7 |
| Bind → connected, after 35 s idle | 4.8 → **770** (1) | 7 → 4 |
| Library resolve (file SHA-256) | 2.8 | 22 per call before; memoised after (3) |
| Cold preparation (first glyphs, worker rasterisation) | 800 (425 raster) | 1,985 (1,592 in worker) |
| First frame 40 px, settled | 656 → 526 | 600 → 406 |
| 300 px frame: sharp tiers | gave up at 1.5 s, **13 pending** (stayed blurry) → sharp at 3.2 s, 0 pending | gave up at 1.5 s, **15 pending** → sharp at 4.1 s, 0 pending |
| Bundled font apply, for reference | 126–138 | |

(1) After 35 s idle the isolated process had been killed in that run, so binding a new process took 770 ms. With the old 30 s keep-warm, any pause longer than 30 s risked this. The worker now stays bound for **3 minutes** after its last request.

(2) The variable warm row varied between runs, with the in-worker share up in the after run. The part this pass changed (outside the IPC) fell from 223 to 23 ms. The in-worker time is FreeType variable-instance set-up and shaping in the isolated process, which this pass did not change.

(3) The resolve call in the after test was its first in that process, so it hashed (28 ms). The memo applies to the later calls of an apply, and to every later preparation of that file.

The "cold" rows are not comparable before and after: the SDF disk cache built by the before run persisted, and clearing app data is not allowed here. The worker's first rasterisation of a face is unchanged.

**Changes.**

- **`SdfDiskCache.shared`.** A verified in-memory view per directory.
  - Before, every request listed the cache directory and read, SHA-256-verified and touched **every** cached glyph of the font to build the seeds. After storing, it listed and stat'ed the whole directory again. That was about 200 ms of a warm request.
  - Now records are read and verified once per process, served from memory (16 MiB LRU across fonts) and written to disk atomically as before. The disk cap is tracked from a running total, and an evicted file leaves memory too.
- **Font-file verification memo.** A file verified in this process is trusted while its length and modification time are unchanged.
- **`TierGlyphCache`.** Imported sharp-tier glyphs from the worker are kept per process (16 MiB LRU), so a rebuilt preview (after Home) or a re-applied font does not wait for the worker again.
- **Imported settle.** While an imported-tier fetch is in flight, a settled frame may wait up to 6 s for the sharp glyphs; any new request still cancels it at once. Before, it gave up at 1.5 s and nothing redrew it.
- **Keep-warm.** 30 s → 3 min, still released.
- **Not on the main thread.** None of this runs there; the bind/unbind hop is unchanged.
- **Errors.** They are still reported on the existing paths: `FontImportErrors` on the Fonts page, `TextLayerErrors` and the preview status. A tier failure keeps the lower tier and records `lastError`; failures are never cached.

**Picker and placeholder.**

- The Fonts page lists imported families as text rows in the system font. There are **no per-font preview bitmaps** in the picker today, so there was nothing to cache there, and adding them would be new UI.
- The wait you see after tapping an imported face is the apply path above.
- **Placeholder.** The canvas keeps its previous frame while the new face is prepared, and shows the base tier at once before the sharp tier arrives. A separate fallback-font placeholder (draw in Inter, then swap) was **not** implemented; see Deviations.

#### First play

**The measured first-play hitch was the JIT, not tiers.**

- With a fresh renderer, a settled paused frame and 2 s idle, the first playback frames of a single animated 30-line layer were **10, 166, 100, 71, 66, 56, 51, 47** ms, then 18 ms steady.
- The animator evaluation runs interpreted until the debug JIT compiles it.
- The size-animated case had no first-play hitch (the max of its first 30 frames is 18 ms), because sharp tiers are generated off-thread and playback never waits for them.

**Changes.**

- **Evaluation warm-up.**
  - When a preview renderer prepares a project with animated layers, the idle prewarm thread evaluates a **private copy** of up to four distinct animated layers' animations (30 evaluations each).
  - The JIT therefore compiles the evaluation stages before Play.
  - It uses PERF 10's activity gate: it pauses while any frame renders and resumes after 750 ms of quiet. The renderer's own animations and output are untouched.
- **Sharp-tier warm-up.**
  - The tier thread has an idle-priority queue, drained only after 750 ms without a frame and never counted as pending, so a settled frame never waits for it.
  - Each text layer queues the bundled glyphs of every tier its keyframed size reaches. That includes the current tier and the tier above it that hysteresis would switch to.
  - It is sent once per change of target, not per frame. Imported faces are not warmed: their tiers come from the worker on demand and are then cached.

**First-play frames (ms):**

| | First 8 frames before | First 8 frames after | First 30 frames max, before → after | Steady p50 / p95 after |
|---|---|---|---|---|
| single30-animated | 10, **166, 100, 71, 66**, 56, 51, 47 | 16, 17, 20, 19, 16, 16, 16, 16 | **166 → 20** | 15.6 / 19.3 |
| layers30-animated | 30, **75, 67, 61, 63**, 57, 58, 60 | 15, 38, 30, 29, 28, 29, 28, 28 | **75 → 38** | 21.8 / 24.5 |
| single30-size | 11, 8, 9, 13, 13, 14, 15, 14 | 13, 8, 8, 13, 9, 10, 15, 16 | 18 → 19 | 18.7 / 20.5 |
| layers30-size | 40, 36, 37, 37, 35, 38, 36, 35 | 16, 9, 11, 10, 9, 11, 9, 11 | 42 → 23 | 23.9 / 27.4 |

### Cache invalidation rules

| Cache | Key | Invalidated by | Test |
|---|---|---|---|
| Merged text storage (`reserveFrame`) | None; capacity only. | Rewritten every submit; reserve = last frame's demand, bounded. | `TextMergedBatchParityTest` (device): pixels identical, merged and separate. |
| `TrackMemo` slots | Track equality (static value, keyframes: time, value, easing) + last time. | A new memo per prepared project; a different time recomputes; unequal tracks never share. | `PreparedTextAnimationGoldenTest`: unchanged PERF 10 hashes + shared-memo bit-identity. |
| Tier catalog (retained) | Bundled font + glyph + ppem (+ spread) inside FontManager. | Its own unpinned LRU budget (16 / 8 MiB); bundled fonts never change. | Existing tier tests; device suites. |
| `SdfDiskCache` memory view | Generator version + ppem + spread (file prefix), exact font identity (SHA-256 of font, face, instance, coordinates), glyph. | Generator change deletes stale files; corrupt file is a miss and is deleted on the next process's read; disk eviction also leaves memory; 16 MiB memory LRU. | `TextSdfDiskCacheTest` (adapted: corruption caught on the next process read). |
| Font verification memo | Font SHA-256 → (length, mtime) at verification. | Any rewrite (length or mtime change) re-hashes; deleting the font forgets it. | Covered by resolve paths; JVM suite. |
| `TierGlyphCache` | (font identity, glyph, ppem); spread follows from ppem; exact worker reply record. | 16 MiB LRU; failures never cached; library deletion changes the identity set (missing fonts fail before the cache). | `TierGlyphCacheTest`: record round trip, second renderer without worker, partial hits, failures, LRU. |
| Tier warm queue | (font, glyph, ppem) as the ready cache. | Skips keys already retained, ready, failed or queued; cleared with the renderer. | Device check (11e). |

### 11e. Suites and numbers

Every device run used the debug APK, installed with `adb install -r` (no uninstall, no data clearing), and `am instrument` directly.

#### JVM

`:app:testDebugUnitTest`: **403 tests, 0 failures**.
- PERF 10 ended at 390.
- New: `AppNavigationTest` (4), `PlaybackGateTest` (4), `TierGlyphCacheTest` (4) and `PreparedTextAnimationGoldenTest.sharedTrackMemoIsBitIdenticalAcrossLayers` (1).
- `TextSdfDiskCacheTest` was adapted (Deviation 7).
- `core-engine` has no JVM tests.

#### App instrumented suite (`a16-perf11-suite`, on `bc19e5c`): 38 run, 31 passed, 7 failed

```
adb shell am instrument -w -r -e notClass com.fluxx.android.render.TextF2AcceptanceTest,com.fluxx.android.render.TextPerfProfileTest \n  -e textRunLabel a16-perf11-suite -e e1aLabel r28p11 com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- **Passed (31).**
  - New in this pass: `EditorLifecycleTest` (rotation/resize/recreate; Home during playback in the editor), `PreviewLifecycleTest`, `ImportedFontLatencyTest`, `TextMergedBatchParityTest`.
  - Unchanged: LayerTransformation 3, FontImportAcceptance 4, ImportedTierAcceptance 3, PaletteRepository 5, TextExplode 4, F1 parity, TextFix 2, PaletteBrowser 1, TextInspectorLayout 3.
- **Failed, environmental as in PERF 10 (6).**
  - `TextGpuProofTest` and the four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
  - `E1aGoldenExportTest` is missing its local source asset.
- **Failed, regression (1), fixed in `fcfd386`.**
  - F1 `fiveHundredGlyphsAllocateNothing…` counted **42 native allocations** in its window (11d's tier warm-up re-sent requests on a tier boundary). Frame 16.07 / 18.48 ms p50 / p95.
  - Rerun on `fcfd386` (`a16-perf11-verify`): native Δ **0**, Kotlin Δ 0, rasterisations, uploads, shapes and generation Δ 0, frame 17.74 / 24.19 ms. It now fails **only** on its final validation-layer assertion, as in PERF 10. The frame difference between the two runs is run-to-run variation; the rerun started straight after the heavy phase-one tests.
- **Rerun on the final code (`fcfd386`).** `TextMergedBatchParityTest` passed. `EditorLifecycleTest#rotation…` passed, with every swapchain extent matching its requested size.

#### Core-engine instrumented: `TextCpuProofTest` 2 / 2 passed (runner `com.fluxx.android.engine.test/androidx.test.runner.AndroidJUnitRunner`)

#### F2 (`TextF2AcceptanceTest`, `a16-perf11-f2b`, final code)

```
adb shell "nohup am instrument -w -r -e class com.fluxx.android.render.TextF2AcceptanceTest -e textRunLabel a16-perf11-f2b   com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/p11/v-f2.txt 2>&1 &"
```

**20 run, 8 passed, 12 failed: the same split as PERF 10.** Recorded as it stands; not rerun.

- **Passed (8):** all seven pre-encode pixel parity tests (plain, rich, wrapped, imported variable, character, advanced modifier, character random) and `preparedTransportPreservesRichMetrics`. Merged batches, the track memo and the warm-ups change no pixel.
- **Failed (12):** every performance test.
  - All fail on **p99 > 16.7 ms** and on **Vulkan validation** (the layer is absent), as in PERF 10.
  - The two 4,096-glyph character and modifier tests hit the 15-minute `TimeoutException`.
  - `importedVariableMaximum` failed on an internal `check`.

**Frame times in this run were slower than PERF 10's, and that was the phone's state, not this branch.**
- Measured: f2 representative 23.2–26.0 ms p50 (PERF 10 19.0); characters representative 61–63 (30.7); 4,096 glyphs 165–173 (124.6).
- The run lasted about 84 minutes, back to back after the other suites.
- To check, `TextPerfProfileTest` ran the same F2 stacks (representative and characters representative, playback) on this build and on the PERF 10 build (`20671a6`). The runs alternated (this, PERF 10, this, PERF 10), with thermal status 0 and the big/little cores at 2.2/2.0 GHz before every run:

| frame p50 / p95, ms | this build #1 | PERF 10 #1 | this build #2 | PERF 10 #2 |
|---|---|---|---|---|
| f2 representative | 16.51 / 18.05 | 16.63 / 19.30 | 16.70 / 19.46 | 16.55 / 17.98 |
| characters representative | 29.43 / 31.51 | 30.43 / 40.92 | 28.86 / 31.66 | 31.95 / 33.68 |

The builds match, so the slowdown in the F2 run was thermal or clock state from the long run.

**Native allocations in the F2 windows.**
- Second pass: unchanged from PERF 10 (rich, wrapped and modifiers 624; characters 1,884; maximum 2,063).
- First pass: rich, wrapped and modifiers rose from 1,277 to about 4,300. Each window starts with a seek back to frame 0, which is a settled frame, so the idle warm-up request can land in it once.
- That is cold-pass, one-off work and does not recur in the second pass. Steady-state playback windows allocate nothing; see F1 above.

**Characters fixture, cold first frame.** The cold first frame takes about 20 s. It is the same on the PERF 10 build: the test took 42 s in total on PERF 10 and 39 s on this build. This is the per-character catalogue preparation, already present before this pass.

#### Tier warm-up (`TierWarmUpTest`, final code)

```
adb shell am instrument -w -r -e class com.fluxx.android.render.TierWarmUpTest -e textRunLabel a16-perf11-warm   com.fluxx.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**Passed.** A layer keyframed 30 → 300 px (bundled mono font, 15 glyphs) opens paused at 30 px.
- **While 30 frames render:** the warm counter stays at 0 (PERF 10 rule).
- **Once quiet:** all **45 glyphs** (three sharp tiers) were generated in **7.2 s** of idle (384 ppem glyphs take more than 100 ms each on the A16).
- **Seek to 300 px:** it then settled in **155 ms with 0 pending**. On the first run, with only 26 of 45 warmed after 3 s, the same seek took 1,652 ms and left 9 pending.

### Deviations

1. **Merged text batches and the batch/draw contract.** I read the contract as "after `flush` returns, nothing Kotlin owns is in use by the GPU". Text-only flushes therefore no longer submit on their own; they join the next real submit, which still waits for its fence. Kotlin-visible arguments, the four-entry limit and every fence wait that protects a Kotlin resource are unchanged. If you read the contract as "every flush submits and waits", this is a contract change: tell me and I will revert to per-flush submits.
2. **No non-debuggable benchmark build.** Every number is from the debug APK. Adding one needs a build type, and since `adb uninstall` is not allowed, installing it would mean either temporarily replacing the debug app or leaving a second app on the phone. I did neither.
3. **"Skips to the end" not reproduced end to end.** The mechanisms were measured and fixed (Part 2): a 733 ms single-frame jump, and up-to-1.5 s waits per misclassified frame.
4. **No fallback-font placeholder for imported faces.** The canvas keeps its previous frame, then the base tier, then the sharp tier.
5. **Split screen not driven from the shell.** `wm size` resize stood in for it. Process death is covered by the JVM test of the restore decision, not on device.
6. **Short-window layout change (`53bf4dc`).** This is a layout rule, not new UI: the canvas keeps 45% of the height in windows under 560 dp. Portrait phone windows are unaffected.
7. **`TextSdfDiskCacheTest` adapted** to the memory view: a file corrupted after a font is in memory is detected by the next process instead of the next request. The record still never reaches the worker and is deleted.
8. **Regressions caught and fixed in this pass.**
   - The 11a stage timer folded `if (!sharp) return`, so 11a's commit alone fails `PerFrameTierPathTest`. 11b restores the literal.
   - 11d's tier warm-up allocated in F1's window; fixed in `fcfd386` (11e).
9. **Commit split.** The 11b–11d commits were split by hunk from one working tree. Only the final tree (`fcfd386`) was built and tested on device; intermediate commits were not built on their own.

### Still open

- **Vulkan validation-layer tests and `E1aGoldenExportTest`** (environmental, as in PERF 10).
- **30 animated layers at p95 under 16.6 ms on the debug APK.** Measured 22.4 / 27.5 ms; the floor is evaluation.
  - Next steps from PERF 10, which need your decision on threading or the batch/draw contract: animators on the second A76 core (evaluation is per layer, so layers parallelise naturally), and per-cluster modifiers in the vertex shader.
  - Also a non-debuggable measurement build.
- **Plain/size p95 of 18–21 ms at 30 layers.** This equals the single-layer floor (paced present plus fence); it is not a text cost.
- **Imported faces: the worker's first rasterisation** (0.4 s static, 1.6 s variable for about 20 glyphs; 3–4 s for 15 glyphs at 384 ppem) is unchanged. Caches remove the repeats.
- **Real landscape and split-screen editor layout** (Part 3 follow-up).
- **Character catalogue cold preparation:** about 20 s for the first frame of the 720-glyph characters fixture, the same on the PERF 10 build.
- **Tier-switch frames still allocate** (92 native allocations per switch window here; PERF 10 finding).

### Device settings

- Recorded before any run: `stay_on_while_plugged_in=0`, `user_rotation=0`, `accelerometer_rotation=0`, no `wm size` override.
- `svc power stayon true` kept the phone awake during the suites.
- The rotation test sets and restores `user_rotation`, `accelerometer_rotation` and `wm size` in `finally`.
- **Final state** (checked at the end): `stay_on_while_plugged_in=0`, `user_rotation=0`, `accelerometer_rotation=0`, `wm size` with no override (physical 1080×2340, density 450), no instrumentation or tracing running.
- One stale F2 run was stopped with `am force-stop com.fluxx.android.debug`, so F2 could run on the fixed build. No data was cleared.

### A16 manual test list (debug build)

1. **30 layers, scrub and playback.**
   - Make a 1080×1920 project with one text layer of `AV office gy 0123456789` at 40 px, then duplicate it to 30 layers and spread them down the canvas.
   - Play: it should be smooth.
   - Drag the playhead quickly for 20 s: the text follows your finger with no stalls.
   - Then add 2–4 animators to one layer, duplicate it to 30, and repeat. Playback should look as smooth as with one layer, and visibly smoother than before.
2. **30 layers with font size animation.** Keyframe the size of every layer from 30 to 300 px over 3 s (duplicate after keyframing), then play a few times.
   - There should be no long freezes or skipped seconds.
   - The text sharpens as it grows, with at most a brief one-frame hitch.
3. **Background and return during playback.**
   - Play the project from step 2, press Home, wait 5 s and return. The playhead is where you left it and the preview is paused.
   - Press Play straight away: it starts from that position. It may take a moment for the first frame, but the playhead must not jump ahead or reach the end on its own.
   - Repeat, waiting 10 s before Play.
4. **Rotation.**
   - With the editor open and unsaved edits (move a layer), rotate the phone to landscape and back.
   - The project stays open, edits and undo stay, and the canvas stays visible in landscape (smaller, above the transport and timeline).
   - Rotate during playback too: playback continues or pauses, but nothing closes.
5. **Split screen.** Open Fluxx in split screen with another app, with the editor open.
   - The project stays open, and the canvas is visible in the half window.
   - Drag the divider: the preview redraws and nothing closes.
   - Return to full screen.
6. **Imported font load.**
   - Import a font if needed. In Edit Text → Fonts, tap an imported family: the canvas should update about half a second faster than before (the bundled faces are still the reference).
   - Tap another imported face, then go back to the first: the second time should be quicker.
   - Wait 2 minutes and apply again: no long first wait.
   - At 250 px or more the text should become sharp within a few seconds, without touching the canvas.
7. **First play.**
   - Open a project with an animated text layer (2–4 animators), wait 2 s and press Play.
   - The first second should be as smooth as the rest; before, it stuttered for about the first 10 frames.
8. **Unchanged behaviour.**
   - The eyedropper on text fill still matches.
   - Export a 3-second clip: it matches the preview.
   - Static frames look identical to TEXT PERF 10.

---

<a id="rec-text-f3"></a>

## TEXT F3: Animate tab (animator UI)

*Formerly `docs/checkpoints/TEXT_F3.md`.*

> **Superseded in part by [TEXT F3.1](#rec-text-f3-1) (branch `text-f3-1`, merged in `c8109d9`).** The animator strip, the Properties | Selectors switch, the paged grid, the Range handle strip, the More popover and the menu Move/Delete entries are replaced. F3.1 adds one collapsible bar per animator with Property / Selector / Grouping rows, a vertically scrolling body, grip reorder and swipe delete. It also adds slider-or-pad transform controls, rebuilt Range/Wiggly tiles with Advanced groups and hidden redundant fields, the Character offset/value crash fix, new Skew/Blur caps and the global Prev/Next rule. The record below stays as the F3 history.

Branch `text-f3`. The Animate tab of Edit Text, built on the existing F2/T4 animator backend. UI, state and actions only. Rendering, animator evaluation and text preparation are unchanged. So are the schema, `PreviewController`, the gesture protocol, the batch/draw contract and the `EditorAction` contract.

Status: implemented in source. Compile, JVM and instrumented results are in [Verification](#verification-1). Its own [A16 list](#a16-test-list) was superseded by F3.1; phone acceptance of the reworked tab (F3.1–F3.3) is done (user-confirmed, 2026-11-22).

### What the page is

Pages row (unchanged): ← | Character | Paragraph | Animate. Explode stays on the Character page.

Animate body, top to bottom:

1. **Animator strip.**
   - One tile per animator: the animator glyph and its number. A disabled animator reads "n off".
   - The selected tile is filled (inverted ink); a hollow add tile sits at the end.
   - The strip scrolls sideways when it overflows (16 animators + add).
   - Tapping the selected tile does nothing.
   - Long press opens an anchored menu: Rename…, Enable/Disable, Duplicate, Move earlier, Move later, Delete.
2. **Switch row.**
   - Properties | Selectors icon tabs, two cells each, with a count caption.
   - Then the animator's own keyframeable fields: **Anchor point grouping** (Characters / Words / Lines / All) and **Animator on**.
3. **Properties tab.**
   - One tile per property: icon, value caption and keyframe badge. Colour properties show a swatch; Character value shows the character itself.
   - A hollow add tile opens an anchored popover with all 25 backend properties in three groups (Transform, Colour, Text). Already-added properties show their selected state; tapping one shows it instead of adding it again.
   - Long press a tile: Reset value, Remove.
   - The grid pages when full: the last cell becomes a `1/2` pager tile. Nothing is ever hidden behind a scroll.
4. **Selectors tab.**
   - A selector strip: Range or Wiggly glyph plus number, up to 8, then the add tile. Add asks Range or Wiggly through two icon tiles in an anchored popover.
   - Long press a selector: Enable/Disable, Duplicate, Move earlier, Move later, Delete.
   - Then the selected selector's controls:
     - **Range:** a handle strip (0–100%, or 0–N units for Index units). Drag the start or end handle, or drag the band for Offset. Below it the primary tiles: Shape, Based on, Mode, Amount, Units. **More** (the overflow glyph) opens a popover with the same tiles for the rest: Randomize order, Seed, Start, End, Offset, Smoothness, Ease high, Ease low, Min amount, Max amount, Selector on.
     - **Wiggly:** no strip. Its whole face fits: Max amount, Min amount, Based on, Mode, Wiggles/second, Correlation, Temporal phase, Spatial phase, Lock dimensions, Seed, Amount, Selector on.
5. **Empty states.**
   - No animators: only the add tile, plus a dimmed animator glyph as an icon-only hint.
   - An animator with no properties or no selectors: only that add tile.

Value editing:

- **Scalars:** the anchored slider popover, the same one the Character tiles use (reset, slider, tap-to-type value, `PropertyLimits` slider/typed/hard caps).
- **Vectors** (Anchor point, Grouping alignment): one popover with an X and a Y slider row.
- **Colours** (Fill, Stroke colour): the COMPACT `FluxxColourPicker` in an anchored popover.
- **Choices** (Shape, Based on, Mode, Grouping): one row of icon buttons in a popover.
- **Toggles** (Units %/#, Randomize order, Lock dimensions, Selector on, Animator on): tap toggles.
- **Seed:** a popover with new random seed, reset to 0 and tap-to-type (unsigned 64-bit).
- **Position, Rotation, Scale:** an in-sheet sub-page with the **Transform tab's own controls**: X/Y value boxes beside the dot-grid pad, the rotation dial, and the log scale rulers with the link toggle. ← or system Back returns to the page.

Every popover closes by tapping outside. There are no Done, ✓ or −/+ buttons.

### Layout figures (360dp window, content 292dp = 6 cells of 48.67dp)

| Sheet | Body | Strip | Switch | Gap | Rows | Slack (centred) | Tile drawn |
|---|---|---|---|---|---|---|---|
| 268, font 1.0 (expanded preview) | 212 | 40 | 40 | 8 | 3 × 40 | 4 | 44.7 × 36 |
| 328, font 1.0 | 272 | 48 | 48 | 8 | 3 × 48 | 24 | 44.7 × 44 |
| 268 × 1.3 = 348.4 | 280.4 | 48 | 48 | 8 | 3 × 48 | 32.4 | 44.7 × 44 |
| 328 × 1.3 = 426.4 | 358.4 | 48 | 48 | 8 | 5 × 48 | 14.4 | 44.7 × 44 |

- Pitch is tile + 4dp gap, so every touch target is a full 40dp (268) or 48dp (328) cell.
- Selectors tab: the selector strip takes one row. Range then has the handle strip (one row) and tile rows; Wiggly has tile rows only.
- At 268/328 font 1.0: Range shows 5 primary tiles plus More; Wiggly shows all 12.
- At 328 × 1.3 the Range face holds all 16 tiles and no More.
- Sub-pages use a 40/48dp header row (←, glyph, "Position · Animator 1", badge). Under it at 268:
  - Position: X/Y boxes (40 + 8 + 40) beside the 128dp pad.
  - Rotation: the 144dp dial.
  - Scale: one or two 80dp rulers (164 unlinked).

The sheet height never changes; nothing scrolls vertically.

### Control inventory

Every field comes from the backend (`TextAnimators.kt`, `SelectorField`, `TextSelectorEngine.coverage`, `PreparedTextAnimation`). `TextAnimateStateTest` asserts that every `AnimatorProperty` appears exactly once and that each selector kind shows exactly the fields its engine reads.

**Animator:**

| Field | Where | Keyframes |
|---|---|---|
| Name | Long press → Rename… (128 chars, trimmed, no NUL) | – |
| Enabled | Switch row "Animator on" tile; long press → Enable/Disable | Hold |
| Anchor grouping (Characters / Words / Lines / All) | Switch row grouping tile → choice popover | Hold |
| Order | Long press → Move earlier / later | – |

**Properties** (add popover group / editor / limit):

| Property | Group | Editor | Limit (slider · typed/hard) |
|---|---|---|---|
| Anchor point | Transform | X/Y sliders | ±500 · ±100000 px |
| Position | Transform | Sub-page: X/Y boxes + pad | ±500 · ±100000 px |
| Scale | Transform | Sub-page: log rulers, link | ruler · ±50000 % |
| Skew | Transform | slider | ±85 · ±3600 ° |
| Skew axis | Transform | slider | ±180 · ±3600 ° |
| Rotation | Transform | Sub-page: dial (turns notation) | ±360 · ±36000 ° |
| Opacity | Transform | slider | 0–100 % (default 100) |
| Fill colour, Stroke colour | Colour | COMPACT picker | – |
| Fill / Stroke opacity | Colour | slider | 0–100 % (default 100) |
| Fill / Stroke hue | Colour | slider | ±180 · ±3600 ° |
| Fill / Stroke saturation, brightness | Colour | slider | ±100 percentage points |
| Stroke width | Colour | slider | ±50 · ±200 px |
| Tracking | Text | slider | ±100 · ±500 (hard ±1000) 1/1000 em |
| Line anchor | Text | slider | 0–100 % |
| Line spacing | Text | slider | ±100 · ±2000 px |
| Grouping alignment | Text | X/Y sliders | ±100 · ±1000 % |
| Blur | Text | slider | 0–50 · 0–200 px |
| Character offset | Text | slider | ±100 · ±1114111 code points |
| Character value | Text | slider (32–591; caption shows the glyph) | 0–1114111 |

**Selectors:**

| Field | Range | Wiggly | Editor / limit | Keyframes |
|---|---|---|---|---|
| Kind | glyph on the strip tile, chosen when added | same | – | – |
| Start, End | handle strip; More → exact | – | 0–100 · ±100000 % (or index) | animated |
| Offset | drag the band; More → exact | – | ±100 · ±100000 | animated |
| Units (% / index) | face toggle | – | toggle | Hold |
| Based on | face choice | face choice | 4 choices | Hold |
| Shape | face choice | – | 6 choices | Hold |
| Smoothness | More | – | 0–100 % | animated |
| Ease high / low | More | – | ±100 % | animated |
| Mode | face choice | face choice | 6 choices | Hold |
| Amount | face | face | ±100 % (default 100) | animated |
| Min / Max amount | More | face | ±100 % | animated |
| Randomize order | More (toggle) | – | toggle | Hold |
| Seed | More | face | popover: random, reset, typed unsigned 64-bit | Hold (seed keys) |
| Wiggles per second | – | face | 0–10 · 0–1000 /s | animated |
| Correlation | – | face | 0–100 % | animated |
| Temporal / Spatial phase | – | face | ±360 · ±36000 ° | animated |
| Lock dimensions | – | face toggle | toggle | Hold |
| Selector on | More (toggle); long press | face toggle; long press | toggle | Hold |

### Keyframes

There is no second keyframe system. The focused field (the last tile touched, the handle being dragged, the open popover's field) owns:

- **The rail diamond.**
  - Static field: enable at the playhead (`Key ENABLE`).
  - On a key: remove it (`Key REMOVE`).
  - Otherwise: add a key with the current value (`Key SET`).
  - Hold fields use the square glyphs.
- **The rail ⋮.**
  - Reset value: the backend default, keyed if animated.
  - Freeze at playhead / Enable animation; Reset animation… (confirmed, then `Key FREEZE`).
  - Linear / Easy Ease on a key; "Hold (discrete)" for Hold fields.
- **Tile badges:** hollow diamond while keyed, filled on a key at the playhead. They come from `TextAnimatorActions.keyTimes`, the same model.
- **Timeline.** Every animator key is drawn on the layer bar, dimmed when inactive (unchanged aggregation). The focused field's keys are full size and editable. Dragging one previews `AnimatorEdit.Key(MOVE)` for that field only, on the existing begin/preview/commit calls.
- **Prev / Next:** follow the focused field (`ContextualNavigation`, new defaulted `animatorTrack` parameter).

Editing a keyed field writes a key at the frame-snapped playhead. Editing a static field changes its static value. Both use the reducer's own arithmetic.

### Actions (no new types)

The existing `EditorAction.EditTextAnimator` + `AnimatorEdit` set covers everything:

- Add / delete / rename / enable: `Put` / `Remove`.
- Reorder: `Reorder`, `ReorderSelector`.
- Selectors: `PutSelector` / `RemoveSelector`.
- Properties: `PutProperty` / `RemoveProperty`.
- Keyframe operations: `Key`.
- Duplicate is `Put` + `Reorder` in one `Batch` (one undo).

`TextAnimatorActions.payload(layer, rate, edit)` was extracted from `apply` (pure refactor; the reducer calls it). The page uses it to build live previews.

- **One undo per edit:** typed values, choices, toggles and structure edits are single actions.
- **One undo per drag:** drags run through the existing `onValueBegin` → `onPayloadPreview` → `onFinish`/`onCancel` text draft, so a drag is one undo entry and a cancel leaves nothing. The preview updates live while dragging.
- **Selection is not history:** animator, selector and focused-field selection is transient (`rememberSaveable`). It survives rotation and switching Character / Paragraph / Animate, and creates no undo entry. Stale ids (after undo or delete) fall back to the first item.

### New icons (`InspectorIcons`)

All new icons share the family rules: 24-unit grid, 1.5 stroke, round caps/joins, rounded closed shapes, hollow. `InspectorIconFamilyTest` checks them automatically (no shared glyphs, no sharp closed polygons).

- **Page:** `ANIMATOR`, `ADD` (named ADD: the test forbids a PLUS stepper), `PROPERTIES`, `SELECTORS`, `RANGE`, `WIGGLY`, `ENABLED`, `GROUPING`, `GROUPING_ALL`.
- **Shapes:** `SHAPE_SQUARE`, `SHAPE_RAMP_UP`, `SHAPE_RAMP_DOWN`, `SHAPE_TRIANGLE`, `SHAPE_ROUND`, `SHAPE_SMOOTH`.
- **Based on:** `BASIS_CHARACTERS`, `BASIS_NON_SPACE`, `BASIS_WORDS`, `BASIS_LINES`.
- **Modes:** region glyphs. Add, Subtract, Intersect and Difference share a hollow outline of two overlapping circles and fill the union, A minus B, the lens, or both crescents. Min and Max share a tall and a short rounded bar and fill the shorter or the taller one. The filled region is a second path in the same stroke, caps and joins (`InspectorIcons.REGIONS`), checked by `InspectorIconFamilyTest`.
- **Selector values:** `UNITS_PERCENT`, `UNITS_INDEX`, `RANGE_START`, `RANGE_END`, `RANGE_OFFSET`, `SMOOTHNESS`, `EASE_HIGH`, `EASE_LOW`, `AMOUNT`, `MIN_AMOUNT`, `MAX_AMOUNT`, `RANDOMIZE`, `SEED`, `WIGGLES`, `CORRELATION`, `TEMPORAL_PHASE`, `SPATIAL_PHASE`, `LOCK`.
- **Properties:** `OPACITY`, `SKEW`, `SKEW_AXIS`, `LINE_ANCHOR`, `BLUR`, `FILL_OPACITY`, `STROKE_OPACITY`, `FILL_HUE`, `FILL_SATURATION`, `FILL_BRIGHTNESS`, the three `STROKE_*` colour variants (base glyph plus a small stroke box), `GROUPING_ALIGNMENT`, `CHARACTER_OFFSET`, `CHARACTER_VALUE`.
- **Reused:** Position, Scale, Rotation, Anchor, Fill, Stroke, Stroke width, Tracking, and Leading (for Line spacing).

### Deviations

1. **Value tiles open the shared slider popover** (the Character tiles' `SliderPopoverContent`) instead of editing inside the tile. `InlineValueField` is integer-only (hex/alpha/RGB), so it can't hold float values or 64-bit seeds. Typed entry in the popover uses `PropertyLimits.parseTyped`, as on the other text tiles. The faded-previous-value placeholder is therefore not shown for animator values.
2. **Position, Rotation and Scale use an in-sheet sub-page, not a popover.** Their Transform controls type through their own popovers, and a popover inside a popover loses focus and closes (the TEXT UI 7 hex finding). The position pad spans one composition width/height around the glyph's own place.
3. **A new animator starts with one Range selector (0–100%),** as in After Effects. With no selectors, coverage is zero and the animator does nothing. It can be deleted.
4. **The Range face keeps five primary tiles** next to the handle strip at the normal sizes; the rest sit behind **More**, a popover of the same tiles. Wiggly fits whole.
5. **Anchor point grouping and Animator on are tiles in the switch row.** The prompt didn't name grouping, but the backend has it (keyframeable Hold). Enable/Disable is also in the long-press menu.
6. **Mode glyphs fill their result region.** This is an exception to "hollow unless state": the filled area shows the combination result. Intersect has the smallest fill, a lens about 7 units wide (about 6dp at the 22dp tile icon). All six were checked only by geometry and tests, not yet by eye on the A16.
7. **New Fill/Stroke colour properties start at the backend reset values** (white / black), not After Effects' red.
8. **The animator colour popover has no eyedropper.** The panel's eyedropper is wired to layer paint.
9. **Index units: the strip's scale is an estimate** from the source text (characters, non-space characters, words, explicit lines; wrapped lines are not known to the UI). Only the strip scale is estimated; stored values are exact.
10. **Delete has no confirmation;** undo restores it.

### Follow-ups

- **Eyedropper in the animator colour popover.** The COMPACT picker supports it; wire it to Fill/Stroke colour animator properties. The current eyedropper path is specific to layer paint (deviation 8).
11. **Shared-component changes, all additive:**
    - `InspectorTile` gained `filled` / `hollow` styles.
    - `PositionTrackpad`, `RotationDialController`, `ScaleController` and `CompactValueBox` changed from `private` to `internal` (no behaviour change).
    - `TimelineView`, `LayerKeyMarkers`, `ContextualNavigation`, `EditorViewModel.navigateBoundary`, `ElementInspectorSheet` and `TextInspectorPanel` gained defaulted animator-focus parameters.
    - Edit Text tabs gained test tags (`text-tab-…`).
12. **Character offset/value previews wait on the backend's catalogue preparation** while dragging (TEXT PERF 11: about 20 s cold for a 720-glyph fixture). This is unchanged backend cost, reported rather than changed.

### Verification

Run by the agent on this branch, separately from your phone tests:

- **Compile:** `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin` passed (`tools/text/results/f3-compile*.log`).
- **JVM:** 421 tests, 0 failures, 0 errors, 0 skipped (`f3-jvm2.log`). New: `TextAnimatorEditsTest` (9), `TextAnimateStateTest` (8) and the mode-icon test in `InspectorIconFamilyTest`:
  - add/remove/reorder/duplicate/rename and the 16/8 caps;
  - static vs keyed edits and frame snapping;
  - rail toggle/freeze/easing;
  - one undo per edit and per drag, and cancel;
  - payload = reducer;
  - timeline MOVE of one field, and Prev/Next;
  - layout at 268/328 × 1/1.3;
  - full inventory, enum mirrors and limits at hard caps;
  - paging, stale selection and saved focus.
- **Host:** native sources are unchanged.
  - `text-proof selftest`: PASS.
  - `shaping_parity.py`: exit 0.
  - `verify_inputs.py`: 1,447 checked, passed.
- **Instrumented (A16, SM-A165F, debug APKs installed with `adb install -r`; nothing uninstalled, no data cleared):**
  - `TextAnimateLayoutTest` (new) passed, 5 of 5:
    - the page fits at 268 and 328 at font scale 1 and 1.3, with tiles at 40/48dp targets;
    - add animator / properties / Wiggly through the real reducer;
    - the Position sub-page and Back.
  - `TextInspectorLayoutTest` (existing) passed, 3 of 3.
  - **App suite** (`a16-f3-suite`, `notClass` F2/perf as in TEXT PERF 11): 44 run, 37 passed, 7 failed. All 7 failures are environmental, the same set as PERF 10/11:
    - `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
    - `TextF1AcceptanceTest#fiveHundredGlyphs…` failed only at its final `validationEnabled` assertion. Its zero-allocation and counter assertions passed.
    - `E1aGoldenExportTest` is missing its local source asset.
  - The stay-awake setting was set for the run and restored to 0. No other device setting changed.

### A16 test list

Debug build, inspector open on a text layer, Edit Text → Animate.

1. **First animator.** Tap the add tile in the empty strip. Then:
   - Properties → add → Position, Opacity and Fill colour.
   - Set Opacity to 0 and Fill to red; open Position and drag the pad. The preview updates live while you drag.
   - Undo after each step removes exactly that step; Redo restores it.
2. **Range handles.**
   - Selectors → drag the end handle from 100% towards 0: characters change one by one, live.
   - Drag the start handle, then drag the band (Offset).
   - Switch Units to # and repeat.
   - Each drag is one undo.
3. **Reveal.**
   - Tap the band (Offset focuses).
   - At 0 s set Offset to −100 and tap the rail diamond; at 2 s set Offset to 0.
   - Play: the reveal runs. The Offset keys are bright on the timeline and the others are dimmed. Prev/Next jump between the Offset keys.
   - Drag an Offset key on the timeline: only Offset moves.
4. **Shapes.** Add a second animator (Scale 200%); in its selector choose Ramp up, then Triangle, then Smooth. Each looks different, and both animators combine.
5. **Wiggly.**
   - Selectors → add → Wiggly, with a Position property set to 40, 40.
   - Play: the glyphs wiggle. Change Wiggles/second, Correlation (0 and 100) and Lock dimensions.
   - Scrub backwards: the same frame looks identical at the same time.
6. **Character offset / value.** Add Character offset 5 (letters shift), then Character value 65 (the caption shows A). Remove both via long press.
7. **Structure.**
   - Long press an animator: Rename, Disable (the strip shows "off" and the preview loses that effect), Enable, Duplicate (the copy follows the source), Move earlier/later, Delete.
   - Do the same for selectors.
   - Undo and redo each.
8. **Persistence.** Save, close and reopen the project: animators, selectors, keys, names and the seed survive.
9. **Export.** Export a 3 s clip and compare it to the preview at 0, 1 and 2 s.
10. **Performance.**
    - A 30-line text layer with 3 animators: play and scrub; it should be smooth.
    - Duplicate an animated layer to 30 layers, then play and scrub (compare with TEXT PERF 11).
11. **Lifecycle.** With the Animate page open (a Position sub-page or popover open too):
    - Rotate to landscape and back.
    - Use split screen and drag the divider.
    - The project stays open, the same animator/selector/tab is selected, and no edit is lost or half applied.
12. **Fit.** Repeat 1–2 with the expanded preview (268) and standard (328), and at font size 1.3:
    - nothing is clipped;
    - every tile is tappable as itself;
    - the strip scrolls sideways at 16 animators.

---

<a id="rec-text-f3-1"></a>

## TEXT F3.1: Animate tab rework, crash fix, limits, global Prev/Next

*Formerly `docs/checkpoints/TEXT_F3_1.md`.*

Branch `text-f3-1`, built on master after TEXT F3 (`7993799`). Merged to master together with TEXT F3.2 (`c8109d9`). It addresses the first A16 test of the Animate tab:
- the layout was hard to use;
- adding Character offset or Character value crashed the app;
- some options were duplicates;
- Prev/Next lost context.

The engine is unchanged. There are no changes to:
- PreviewController, the gesture protocol or the batch/draw contract;
- the document schema;
- animator evaluation or the native renderer;
- the EditorAction contract. No action types were added.

Two preparation boundaries were hardened (§6), and every hidden option stays in the backend.

Status: implemented and verified in source (§10). Phone acceptance on the A16 (§11) is done (user-confirmed, 2026-11-22).

### 1. Range drag strip removed

- `RangeHandleStrip` is deleted, along with the Index-units strip-scale estimate (`AnimateUnits`), which only existed for it. Nothing in the Animate tab is a drag strip.
- Start, End and Offset are ordinary tiles. Each opens the standard slider popover with typed entry, a keyframe badge and focus, like every other field.
- The Transform tab's pad, dial and rulers are untouched.

### 2. Position / Rotation / Scale: both control styles

- **Sliders (default):** anchored slider popovers with typed values and `PropertyLimits` caps.
  - Position: X and Y sliders, ±500px on the slider, ±100,000px typed.
  - Scale uses the Transform tab's link semantics (`ScaleRulerMath.linkedY`):
    - Linked shows one Scale slider and keeps the X:Y ratio.
    - Unlinked shows Scale X and Scale Y.
    - The link toggle is in the popover row.
  - Rotation: **the slider and the typed value are plain degrees.** The popover adds the dial's turns notation (`n x remainder`, the same `formatRotation`) as a read-out. Turns cannot be typed in the shared slider popover; its parser takes numbers only.
- **Pad, dial, rulers:** the existing in-sheet sub-page (the Transform tab's `PositionTrackpad`, `RotationDialController` and `ScaleController`), with ← and system Back.
- **Switch:** the rail ⋮ shows `Controls: Sliders → Pad, dial, rulers` (or the reverse), only while Position, Rotation or Scale is focused.
- **Persistence:** an app preference, not project state and not schema (`AnimateControlsPreference`, SharedPreferences `inspector_preferences` / `animate_transform_controls`). It survives restart.

### 3. Selector tiles

These were verified against `TextRangeSelector`, `SelectorField` and `TextSelectorEngine` (`rangeCoverage`, `wigglyCoverage`).

| | Primary (always visible) | Advanced (collapsed by default, transient) |
|---|---|---|
| Range | Start, End, Offset, Shape | Units, Based on, Mode, Amount, Smoothness (**only while Shape = Square**), Ease high, Ease low, Randomize order, Seed |
| Wiggly | Max amount, Min amount, Wiggles/second, Correlation | Based on, Mode, Temporal phase, Spatial phase, Lock dimensions, Seed |

- **Smoothness** is read only in the Square branch of `rangeCoverage`. When the Shape leaves Square the tile disappears, and the stored value is untouched (tested).
- Each selector's on/off is the eye on its bar.

#### Hidden backend fields (UI only; backend, defaults and schema untouched)

| Field | Hidden on | Why | Engine |
|---|---|---|---|
| Min amount, Max amount | Range | Redundant with Amount: coverage = Amount × (Min/Max blended by the shape). After Effects' Range selector has only Amount. | Still applied |
| Amount | Wiggly | It only scales Min/Max. After Effects' Wiggly has none. | Still applied |
| Enabled | both | On the bar (eye), not a tile | Still applied |
| Kind | both | Chosen when the selector is added | Still applied |

**Existing documents with non-default hidden values** keep rendering exactly as before, because the engine honours them. The only hint is one **WARNING tile** at the end of that selector's Advanced group. It appears only while a hidden field differs from its default or is keyed. Its popover lists the values ("Not shown for this selector, still applied: Min amount 20%") and has a Reset that restores them to defaults in one undo entry (`AnimateHidden`).

### 4. Structure: one collapsible bar per animator

```
            [+]                       ← add animator (centred; vertically centred when empty)
 [grip] [v] Animator 1   [dup] [eye]
    Property                    [+]   ← add-property popover (§5)
      [v] Transform  2                ← group header, collapsible; tiles below
          [Pos][Opac]
      [v] Colour / Text / Advanced    ← only groups that hold properties
    Selector                    [+]   ← Range / Wiggly popover
      [grip] [v] Range Selector 1  [dup] [eye]
          [Start][End][Offset][Shape]
          [>] Advanced
      [grip] [>] Wiggly Selector 2 [dup] [eye]
    Grouping                    [Grp] ← Anchor point grouping (choice popover)
 [grip] [>] Animator 2   [dup] [eye]
```

- **Bars:** grip, animated chevron, name, duplicate, eye.
  - The eye is the existing keyframeable Hold `enabled` flag. Off keeps every key and value. Tapping the eye focuses that field, so the rail diamond keys it, and a key badge shows on the eye.
  - Collapsed, only the bar shows. Expand and collapse animate (`animateItem` fade/placement plus chevron rotation).
- **One animator is expanded at a time.** Selectors, property groups and Advanced groups expand independently.
- **Transient state:** all of this is `AnimateBars`, saved with the panel, never in the document and never undone. Focus is held separately, so collapsing never loses it.
- **Reorder:** long-press-drag on the grip, for animators and for selectors. It follows the timeline hierarchy rule:
  - the target is the absolute pointer position against sibling slot centres captured at drag start, never animated positions;
  - the dragged row and its whole block follow the finger, and siblings animate with `animateItem`;
  - edges auto-scroll;
  - previews go through the text draft, so release is **one undo entry** and cancel restores.
  - The Move earlier/later menu entries are removed.
- **Delete:** swipe a bar sideways past 40% of its width. Undo restores, with no confirmation. A Delete accessibility action is on the bar. No menu has a Delete entry.
- **Long-press an animator bar:** the menu has Rename only. Selector bars have no menu.
- **Duplicate:** one tap on the bar icon, using the existing `duplicateAnimator`/`duplicateSelector`. It is one Batch (one undo), with fresh ids, the **same seeds**, the keys, properties and grouping.
- **Grouping alignment** stays an ordinary property in the Text group.
- New animators still start with one Range selector (0–100%). The new animator and that selector open expanded.
- **Persistent glyph:** the faded Animate glyph sits behind the page at all times. It is monochrome `TextPrimary` at 5% alpha with no semantics, takes no touches and moves no layout. Tiles and bars draw their own `SurfaceHigh` surfaces on top, so their contrast is unchanged. Row labels sit over at most a 5% tint.

#### Layout figures (360dp window, content 292dp = 6 cells of 48.67dp; every row is one pitch)

| Sheet | Body | Tile / pitch | Rows visible | Touch targets |
|---|---|---|---|---|
| 268, scale 1 | 212 | 36 / 40 | 5 | 40 |
| 328, scale 1 | 272 | 44 / 48 | 5 | 48 |
| 268, scale 1.3 | 200 | 36 / 40 | 5 | 40 |
| 328, scale 1.3 | 260 | 44 / 48 | 5 | 48 |

- An expanded animator with two properties and its Range selector open is 10 rows. That is 400dp at 268 and 480dp at 328, so the body scrolls.
- The `1/2` pager tile is gone. Tiles lay out in rows of the same cells.
- Bar controls (grip, duplicate, eye) are full-pitch touch targets.

#### Deviation

**The Animate body scrolls vertically.** This is the one deviation from the earlier no-scroll rule. The sheet height never changes (268/328), and the rail on the left stays fixed (tested). Nothing else scrolls.

### 5. Add-property popover, regrouped

Order:
1. **Transform:** Anchor point, Position, Scale, Skew, Skew axis, Rotation, Opacity.
2. **Colour:** Fill colour, Stroke colour, Fill opacity, Stroke opacity, Stroke width.
   - A collapsed **Colour shift** sub-group: Fill/Stroke hue, saturation, brightness. These are relative shifts of the existing colour, not duplicates of the colour properties.
3. **Text:** Tracking, Line anchor, Line spacing, Grouping alignment, Blur.
4. A collapsed **Advanced** group: Character offset, Character value.

Added properties show as selected.

**Animator Blur** is per-glyph edge softness: an SDF shader parameter (`writeRecords` encodes it next to the stroke distance), cheap, and not a Gaussian layer blur. It is independent of the future Effects-tab blur.

### 6. Character offset / value crash

#### Root cause (A16 crash buffer, read-only `adb logcat -b crash -d`; full log in `build/crash-logs/text-f3-1-crash.txt`)

Three identical crashes at 11:14:00, 11:14:15 and 11:16:00:

```
FATAL EXCEPTION: main   Process: com.fluxx.android.debug
java.lang.IllegalArgumentException: Character animation needs worker preparation; retry preparation or narrow the animated range
    at com.fluxx.android.model.PreparedTextAnimation.<init>(PreparedTextAnimation.kt:95)
    at com.fluxx.android.model.TextLayoutMetrics.prepareAnimation$app(TextLayout.kt:69)
    at com.fluxx.android.model.TextLayoutMetrics.animation(TextLayout.kt:73)
    at com.fluxx.android.model.TextLayoutMetrics.animatedBounds(TextLayout.kt:76)
    at com.fluxx.android.model.TextLayoutMetrics.bounds(TextLayout.kt:32)
    at com.fluxx.android.ui.editor.EditorScreenKt.EditorScreen(EditorScreen.kt:123)
    at androidx.compose.runtime.RecomposeScopeImpl.compose(...)
```

- `EditorScreen` computes `textCanFit` on the main thread from `selectedTextMetrics`, a `produceState` keyed on the layer text.
- In the recomposition right after the property is added, `produceState` still holds the **previous** metrics, which have no character catalogue. They are handed the **new** text.
- `PreparedTextAnimation` then hits `requireNotNull(characterCatalogue)` and throws out of composition.
- The other 23 properties need no catalogue. That is why only these two crashed.
- The other suspects were checked:
  - `codepoints` (4,096) and `TextNominalMetrics.index` ("codepoint was not prepared") are reachable in the same way from a stale catalogue after a value drag. The test reproduces this.
  - First-preparation cost was not the cause.

#### Fix

- **UI boundary (`TextLayoutMetrics`):** bounds, geometry, logical bounds and the stroke-clamp label never throw for Character animation. Without a matching catalogue (stale metrics, or a missing codepoint at evaluation) the layer is measured without those overrides (`TextCharacterPreparation.withoutCharacters`). This affects measurement only; the renderer is unchanged.
- **Renderer preparation boundary (`CompositionRenderer.prepareTextProject`):** a catalogue failure from the codepoint or catalogue caps no longer fails the frame.
  - **Interactive previews** draw the layer without its Character animation and publish the message (`TextLayerErrors.characters`). The inspector header shows it.
  - **Export** still fails explicitly, so a file is never silently degraded.
- **Error UI:** the offending Character offset/value tile shows the WARNING glyph with state "Error: …". Its popover has a short warning line (`TextCharacterPreparation.problem`).
- **Pre-check:** before a Character offset/value value is committed (typed, slider drag, reset, or adding the property), the page computes the reachable-codepoint count and fonts × codepoints for the layer's distinct characters and fonts. It refuses the change with the same inline message when either would exceed **4,096 codepoints** or **16,384 glyphs**.
- **Regression tests:**
  - `AnimatorPropertyCoverageTest` (JVM) adds all 25 properties through the real reducer and prepares/evaluates each on the UI path (no catalogue), the worker path (with catalogue) and a stale-catalogue path.
  - **With the fix reverted it fails with exactly the device exception, and the stale-catalogue case also fails ("codepoint was not prepared"). With the fix it passes.**
  - `AnimatorPropertyRenderTest` (A16) renders all 25 through the production preview and export renderers. It also checks that over-cap Character animation degrades in preview and fails in export.

### 7. Limits

- **Skew:** `TextSelectorEngine.affine` clamps the coverage-weighted skew to ±85°, so the typed cap is now **±85** (the slider is ±85).
  - Trade-off: at partial coverage, a typed 170° previously reached 85°. Now the cap applies before weighting.
- **Blur:** `PreparedTextAnimation.writeRecords` clamps the per-glyph blur to the SDF budget: **10% of the glyph's font size** (7.5% with faux bold), minus half the stroke width. At the 1,000px maximum layer size that is 100px, so the **typed cap is now 100px** (it was 200). The slider stays 0–50.
  - A run with relative size above 100% can exceed this budget. This is rare, and the cap follows the layer maximum.
- **Character offset/value:** slider ranges are unchanged. The pre-check (§6) replaces a cap, because the real limit depends on the text.

#### Limits table (asserted by `TextAnimateStateTest.limitsTable`; typed = hard)

| Field | Slider | Typed / hard |
|---|---|---|
| Anchor point, Position (px) | −500…500 | ±100,000 |
| Scale (%) | ruler range | ±50,000 |
| Skew (°) | −85…85 | **±85** |
| Skew axis (°) | −180…180 | ±3,600 |
| Rotation (°) | −360…360 | ±36,000 (turns read-out) |
| Opacity, Fill/Stroke opacity, Line anchor (%) | 0…100 | 0…100 |
| Stroke width (px) | −50…50 | ±200 |
| Fill/Stroke hue (°) | −180…180 | ±3,600 |
| Fill/Stroke saturation, brightness (%) | −100…100 | ±100 |
| Tracking (1/1000 em) | −100…100 | −500…500 typed, ±1,000 hard |
| Line spacing (px) | −100…100 | ±2,000 |
| Grouping alignment (%) | −100…100 | ±1,000 |
| Blur (px) | 0…50 | **0…100** |
| Character offset | −100…100 | ±1,114,111 + pre-check |
| Character value | 32…591 | 0…1,114,111 + pre-check |
| Fill / Stroke colour | colour picker | — |
| Start, End (%) | 0…100 | ±100,000 |
| Offset (%) | −100…100 | ±100,000 |
| Amount, Min/Max amount, Ease high/low (%) | −100…100 | ±100 |
| Smoothness, Correlation (%) | 0…100 | 0…100 |
| Wiggles/second | 0…10 | 0…1,000 |
| Temporal / Spatial phase (°) | −360…360 | ±36,000 |

**Structural caps:**
- 16 animators per layer;
- 8 selectors per animator;
- one of each property per animator;
- name 128 characters;
- 64 distinct seeds per selector;
- 4,096 reachable codepoints;
- 16,384 catalogue glyphs.

### 8. Contextual Previous / Next: one global rule

`ContextualNavigation` was rewritten; `EditorViewModel.navigateBoundary` is unchanged and delegates to it. The rule is defined in [AE parity §16](../architecture/AE_PARITY_VISION.md#16-contextual-previous--next-navigation), and [Timeline Layout](TIMELINE_LAYOUT.md#contextual-previous--next) and [Markers](MARKERS.md) were updated to match. There is no `PREVIEW_ACTION_BAR.md`; Timeline Layout holds that section.

Per direction, the first tier with a stop strictly beyond the playhead frame wins:
1. focused keys, only while keyed;
2. markers inside the layer span (layer and composition);
3. layer in/out;
4. markers outside the span;
5. timeline bounds.

With nothing selected: composition markers, then bounds.

**Deliberate behaviour changes:**
- a focused property no longer blocks markers;
- a selected layer now reaches composition markers.

The guards are unchanged.

### New icons (`InspectorIcons`, same stroke family)

| Icon | Shape |
|---|---|
| `EYE` | Almond and pupil, matching the timeline eye |
| `EYE_OFF` | Almond with a slash |
| `GRIP` | Three bars |
| `EXPANDED` | Down chevron, rotated to point right when collapsed |

Reused icons: `DUPLICATE`, `DELETE` (swipe reveal), `WARNING` (character error, hidden values).

### Follow-ups (also in the roadmap)

- On-preview highlight of the characters a selector covers. The strip that implied coverage is gone.
- Eyedropper in the animator colour popover. Still open.
- Known gaps against After Effects:
  - per-character 3D rotation;
  - an "All Transform Properties" shortcut;
  - the Character Range option;
  - the Expression selector.

### 10. Verification

- **Compile:** Kotlin debug and release, plus instrumentation sources: pass.
- **JVM:** 435 tests, 0 failures.
  - New or rewritten:
    - `ContextualNavigationTest` (10, every tier and edge);
    - `AnimatorPropertyCoverageTest` (4: all 25, stale catalogue, pre-check caps);
    - `TextAnimateStateTest` (14). It covers fit and scroll at 268/328 × 1/1.3, bar state with one expanded animator and focus kept, Range/Wiggly tiles, Smoothness only for Square, hidden fields absent in every shape state, the hidden-value hint and reset, the popover groups, the limits table and caps, grip reorder as one undo, swipe delete and undo, duplicate (seeds, keys, grouping), the eye keeping keys, and preference persistence.
  - `MarkersTest` was updated for the new rule: from frame 0 / 100 a selected layer now reaches composition markers 30 / 90.
- **Host:** native sources are unchanged.
  - `text-proof selftest`: PASS.
  - `shaping_parity.py`: exit 0.
  - `verify_inputs.py`: 1,447 checked, passed.
- **Instrumented (A16, SM-A165F; debug and test APKs installed with `adb install -r`; nothing uninstalled, no data cleared, no scripted UI on the app, no projects touched):**
  - `TextAnimateLayoutTest` (rewritten): 6 of 6.
    - Fits at 268/328 × 1/1.3 with an animator expanded: Property groups, Range expanded with Advanced open, then Wiggly with Advanced open, then the Grouping row.
    - Every tile scrolled into view sits inside the body at a full 40/48dp target. The body scrolls and the rail does not move. Hidden fields are absent.
    - The bar's duplicate and eye work, and both control styles work.
    - Character offset/value add without a crash.
    - The controls preference uses an in-memory store in tests, so no app file is written.
  - `AnimatorPropertyRenderTest` (new): 2 of 2.
  - `TextInspectorLayoutTest`: 3 of 3.
  - **App suite** (`a16-f3-1-suite`, `notClass` F2/perf as in PERF 11 and F3): 47 run, 40 passed, 7 failed. All 7 failures are environmental, the same set as before:
    - `TextGpuProofTest` and four `TextGpuAcceptanceTest` tests need `VK_LAYER_KHRONOS_validation`.
    - `TextF1AcceptanceTest#fiveHundredGlyphs…` fails at its final validation assertion.
    - `E1aGoldenExportTest` is missing its local asset.
  - The stay-awake setting was set to 7 for the suite and restored to 0.
  - A first full run that included F2/perf was stopped with `am force-stop com.fluxx.android.debug.test`. That stops processes only; no data was cleared.

### 11. A16 test list

Debug build, a text layer selected, Edit Text → Animate.

1. **Add an animator.** Tap the centred +. It opens expanded. Collapse it with the chevron and see only the bar; expand it again and see the Property, Selector and Grouping rows.
2. **Duplicate** with the bar icon. Animator 1 copy appears below, identical. Undo removes it in one step.
3. **Sliders.** Property + → Transform → Position and Opacity.
   - Tap Position: X/Y sliders, live while dragging, one undo per drag.
   - Type a value.
   - Set Opacity to 0.
4. **Control style.** Focus Position, then rail ⋮ → `Controls: Sliders → Pad, dial, rulers`. Tapping Position now opens the pad, and ← returns. Check Rotation (dial) and Scale (rulers), then switch back. Kill and reopen the app: the choice is kept.
5. **Reveal.** On Range Selector 1:
   - Start 0, End 100.
   - Offset: rail diamond at 0s = −100, move the playhead, Offset = 100 (key).
   - Shape: Ramp up.
   - Advanced: Ease high 25, Ease low 100.
   - Play: characters reveal left to right.
   - Check Smoothness is visible only with Shape = Square.
6. **Eye** off/on on the animator and on the selector. Keys and values are kept. Key the eye with the rail.
7. **Reorder.** Long-press a grip and drag two animators, then two selectors. Siblings slide, the dragged row follows the finger, and Undo restores in one step.
8. **Swipe delete.** Swipe a selector bar and an animator bar sideways. Undo restores each.
9. **Wiggly** selector with Position: check Max/Min amount, Wiggles/s, Correlation, and the Advanced phases, lock and seed.
10. **Character offset and value** (Property + → Advanced). No crash. Type Offset 5000 on short text: it is refused with the inline line. On very varied text the tile shows the warning, and the preview keeps drawing without the character animation.
11. **Prev/Next walk** on a layer with markers inside and outside its span, and composition markers inside and outside. Walk it:
    - with a keyed focused property (keys first, then inside markers, in/out, outside markers, bounds);
    - with an un-keyed focused property (same as unfocused);
    - with nothing selected.
    - Repeat on the Character, Paragraph and Animate pages and with the inspector closed. The behaviour must be identical.
12. **Rotate** the phone and use **split screen** with an animator expanded. The expansion, focus and scroll survive, and nothing clips.

---

<a id="rec-text-f3-2"></a>

## TEXT F3.2: AE parity fixes, seed flicker, export/playback, Animate polish

*Formerly `docs/checkpoints/TEXT_F3_2.md`.*

Branch `text-f3-2`, built on `text-f3-1` (`24cb2fd`). Merged to master, with F3.1, in `c8109d9`. One consolidated pass, organised by the sections of the brief.

Status:
- Implemented in source.
- The JVM suite passes, and the debug, release and instrumentation Kotlin sources compile.
- **A16:** the Animate layout tests pass on the A16 (7 of 7), after a scroll fix the first run exposed; adjacent inspector and render suites pass too. See §12.
- Phone acceptance (§13) is done (user-confirmed, 2026-11-22).

Hard rules kept:
- No changes to PreviewController, the batch/draw contract, the document schema or worker preparation.
- No new EditorAction types.
- Evaluation changed only in §1 (Ramp coverage), §2 (seed) and §3 (tracking alignment).

Two minimal boundary changes are stated where they occur:
- **§2:** the selector engine's own seed list, inside `PreparedSelectorEngine`. Worker preparation is untouched.
- **§4:** the gesture preview whitelist. The begin/preview/commit/cancel protocol is unchanged.

### 1. Selector coverage parity (Ramp Up / Ramp Down)

`PreparedSelectorEngine.coverage` is the Range branch.

**Before:** every non-Square shape had zero influence outside Start..End.

**Now (After Effects):**
- **Ramp Up:** rises 0→1 across the range. Every character at or past End is fully selected (v = 1).
- **Ramp Down:** every character before Start is fully selected. It falls 1→0 across the range and is 0 past End.
- **Square, Triangle, Round, Smooth:** unchanged.

Where the extension applies:
- It is applied after Offset, Units and Based On are resolved, so Offset animates it.
- Amount and Min/Max amount scale it like the ramp itself.
- Smoothness (Square only), Ease High/Low, Randomize Order, mode and the selector combine rules apply on top.
- A zero-width range (Start = End) stays empty for non-ramps and becomes a step for ramps.

Ease High/Low:
- They were already accepted in −100..100 by validation and the ease table.
- Ease High 25 / Ease Low 100 changes the curve, which stays monotonic (tested).

Tests (`SelectorParityF32Test`, engine level, 30 fps, frame by frame):
- **Fade up, Ramp Up:**
  - first frame (Offset −100): every weight is 1;
  - last frame: every weight is 0;
  - per-character weights fall monotonically;
  - characters release in reading order;
  - quarter and middle frames are partial.
- **Ramp Down:** the mirror (0 at the first frame, 1 at the last, rising monotonically).
- **Right-to-left** differs only in the Position vector, so the weights are identical (asserted).
- **Eased vs linear:** the eased curve differs and stays monotonic, with the same end states.
- **Units, Offset, Based On (Words), Amount and Subtract mode** all hold on the extended coverage.
- **Non-ramp shapes** are still 0 outside the range.

#### Goldens re-recorded (`PreparedTextAnimationGoldenTest`)

Every F2 golden stack contains a Ramp Up (s = 1) and a Ramp Down (s = 2) selector, so all four `GOLDEN` hashes changed:

| Stack | Old (TEXT FIX 9) | New | Why |
|---|---|---|---|
| plain | `c308f9b7…` | `bd69ccc5…` | Ramp extension; then the §3 tracking alignment (centred point text) |
| rich runs | `2c87f9bd…` | `644eadc0…` | Ramp extension; then §3 tracking alignment |
| wrapped paragraphs | `e1ed5d2c…` | `9e08d0fe…` | Ramp extension only (the paragraph path already aligned tracking) |
| wiggly/advanced | `1a028c14…` | `a36cdd93…` | Ramp extension, plus the seed fixture change below |

Details:
- **Seed fixture.** The advanced stack's second seed key moved from `0x76543210+i` to `0x12345678+40+i`. Interpolated, the old span would have been about 1.6 billion seeds, far over the cap. On the old engine this fixture change alone gives `2d392f52…`.
- **Intermediate step.** After §1 and §2, before §3, the plain and rich hashes were `ab1a726b…` and `d1f1f71d…`. Only §3 moved them again; the wrapped and advanced hashes did not move.
- **Guard for "no non-ramp change".** The new `NON_RAMP` test uses the same four stacks with Square/Triangle/Round/Smooth only and constant-seed keys. It was recorded on the F3.1 engine (`24cb2fd`) **before** any change, and it is unchanged after §1–§3:
  - `1153ee31…`, `0af1af37…`, `0d9282f6…`, `b7542135…`
  - It is left-aligned so the §3 rule is a no-op there. I verified that the left-aligned and centred variants hash identically on the old tracking formula: these stacks are single-line, so the old formula never shifted them.
- **`sharedTrackMemoIsBitIdenticalAcrossLayers`** is unchanged and passes.

### 2. Random Seed interpolation (flicker)

**Rounding.** Between keys k0 and k1 the seed is `k0 + Math.round((k1 − k0) · f)`, where f is the linear fraction:
- nearest integer, ties toward +∞;
- before the first key / after the last key, the end seed holds;
- a static (unkeyed) seed never moves;
- a segment whose signed difference overflows a Long holds k0.

This applies to Range and Wiggly selectors alike, since both read `seedAt`.

**Cap.**
- Distinct reachable integer seeds per selector: **min(256, what fits the 32 MiB permutation pool for that selector)**. One seed costs 4 × the sum of the four member counts, plus wrapped-line ranks when those are prepared.
- Typical layers: for 720 glyphs the pool cap is 2,912, so 256 applies. The pool only bites past about 8,000 characters.
- A 0→200 ramp reaches 201 seeds.
- The 4,096 seed-keyframe cap is unchanged.
- `TextAnimatorEdits.MAX_SEEDS` is now 256.

**Boundary change (minimal).**
- `PreparedSelectorEngine` now prepares every reachable seed (`TextSeedRange.prepared`), sorted, and finds a seed with `binarySearch`.
- The permutation pool and worker preparation are unchanged.

**Edit time.**
- A seed edit (typed, dice or reset) whose result would exceed 256 is refused.
- The refusal shows the inline line and the WARNING glyph on the Seed tile and in its popover, in the Character pre-check style (`TextSeedRange.problem`).
- Note: with keys present, the dice button's random 64-bit seed is almost always refused, because it is far from the other key.

**Loading an over-cap document** never throws:
- The engine keeps the keyed seeds first, fills the rest up to the cap, and evaluates with the nearest prepared seed (`seedsClamped`).
- The preview publishes the warning once in the layer error map, so the inspector header shows it.
- Export renders clamped. It can render, so it does not fail.

**Cost.**
- Permutations are cached per distinct seed. 600 flicker frames on 720 glyphs allocate **0 bytes** (asserted).
- JVM host, 0→200 on 720 glyphs: preparation 5–11 ms, and 75–475 µs per frame across runs (noisy, JIT-dependent).
- These are not device numbers. Device frame time is unmeasured.

**Tests.**
- Interpolation and rounding values.
- Reachable counts for 0→25, 0→150, 0→200 and overlapping segments.
- Edit-time problem at 256 vs 255.
- Over-cap clamping with the exact end key.
- The flicker recipe (Opacity 0%, Start 0→100%, Randomize, Seed 0→25 / 150 / 200, Smoothness 0, Square):
  - all hidden at the start and visible at the end;
  - the selected set differs between consecutive frames whenever the integer seed changes;
  - at least a third of the frames re-hide a revealed character.
- A static seed keeps a stable order: the selected set only shrinks.
- `WigglySelectorTest` now asserts interpolation, where it used to assert Hold.

### 3. Tracking animator follows alignment

**Static tracking rule.** Static edits compensate the layer position so the point at the alignment fraction stays fixed (`LayerGeometry.compensateTextPosition`, with the box anchored by `textMatrixInto`).

**Bug.** In point text, `positionClusters` re-aligned each line against `newMax`, the widest line *including* animated tracking. The layer box (logical bounds) uses the static width, so animated tracking grew the text from the left edge. Paragraph text already subtracted `lineTotals · align`.

**Fix (evaluation offset only).**
- Point-text lines align inside the static box: `offset = pen + lineShift + ((baseMax − advances[l]) − (rawMax − rawAdvances[l]))·align`.
- Every line's alignment point therefore stays at `baseMax·align`, using the animated, selector-weighted total at that time.
- Line anchor stays an extra shift on top.

**Tests (`PreparedTextAnimationTest`):**
- all three alignments × line anchor 0/50/100 (left grows right, centre ±total/2, right grows left);
- a symmetric centred spread for ± tracking, with the ink centre fixed;
- a keyed amount through a Range selector at 50% Amount stays aligned at every time.

**Checked and not the same bug:**
- **Line spacing:** it grows downward from line 0, exactly like static leading.
- **Grouping alignment:** it is centred on the group bounds.

### 4. Crash: dragging keyframes in the Animate tab

**Root cause.** A16 crash buffer, read-only `adb logcat -b crash -d`, saved to `build/crash-logs/text-f3-2-crash.txt`. Two crashes, at 17:47:23 and 17:47:59:

```
java.lang.IllegalArgumentException: Unsupported transient edit
    at com.fluxx.android.editor.EditorViewModel.previewGesture(EditorViewModel.kt:170)
    at ...EditorScreen... onPreviewGesture
    at com.fluxx.android.ui.timeline.TimelineViewKt$TimelineClipRow...(TimelineView.kt:964)   ← keyframe drag
```

- With an Animate field focused, the timeline keyframe drag previews `EditorAction.EditTextAnimator(AnimatorEdit.Key MOVE)`. That path was added in F3.
- `previewGesture`'s transient whitelist never included `EditTextAnimator`, so its `require` threw on the main thread on the first drag step.

**Fix.**
- `EditTextAnimator` joins the transient whitelist. It is an existing action, so the protocol is unchanged.
- Preview and commit never throw:
  - an unsupported or refused preview keeps the last valid preview;
  - a failed commit leaves the document unchanged;
  - both publish `EditorViewModel.gestureError`, which the editor shows as a toast.
- A drag stays one undo step.

**Test (`AnimatorKeyDragTest`).** It drags keys through the real gesture protocol on:
- an animator scalar property (Opacity);
- a vector property (Position);
- a selector field (Offset);
- seed keys;
- the animator enabled Hold flag.

For each it asserts the preview, the commit, one undo step and no undo left. A refused move (a deleted selector) and an unsupported action neither throw nor change the document.

### 5. Export and playback after export

All four root causes were confirmed in source:
1. `ExportService.state` is process-global and its terminal state was never reset.
2. The done view of `ExportDialog` had only Open and Close.
3. `EditorScreen` keyed its effect on `output`, so a stale URI reopened the done dialog on every project open.
4. `onExportStart` calls `stopPreview()`, and only `onResume` (with a null preview) rebuilt it, so playback stayed dead.

Fix (the export pipeline is unchanged):
- **State.** `ExportStateStore`, a plain JVM-testable store, now owns the state. Every terminal state (saved, failed, cancelled) gets a fresh non-zero `completion` id.
- **Reset.** `ExportService.reset()` runs when the dialog is dismissed and when a project is opened (`openProject`). It never resets a running export.
- **Done dialog.** It opens only when `ExportRules.showDone` sees a completion id the screen has not seen (`rememberSaveable`).
- **Export again.** The done view adds "Export again"; Open exported video and Close stay.
- **Preview.** `MainActivity` collects the export state and calls `refreshPreview()` when an export ends (`ExportRules.rebuildPreview`). This also covers a conflated short run.

Tests (`ExportStateStoreTest`):
- done → dismiss/reset → reopen shows no dialog;
- Export again gets a new completion;
- a saved, failed or cancelled end rebuilds the preview, and a reset does not;
- running is never reset.

Saved outputs are exercised through the primitive rule, because `android.net.Uri` cannot be built on the JVM.

### 6. Non-modal slider popovers

`AnchoredPopover(modal = false)` is used by the Animate slider and vector popovers and by the Character tab's numeric popovers.

**Mechanism.**
- The popup window is not focusable, so touches outside it reach the timeline, the transport and the rail.
- It turns focusable only while its value field types (`LocalPopoverTyping`). The field requests focus two frames later and asks the IME to show.

**Dismissal.**
- **Preview tap:** `CompositionPreview` observes taps on the Initial pass, without consuming them, and sends `PopoverDismissal.request()`.
- **Another tile:** the page swaps its single popover key.
- **Back:** `BackHandler`.
- Timeline, transport and rail touches never dismiss (`PopoverDismissal.dismisses` is tested).

**While open.**
- The rail diamond and Prev/Next do not touch popover state.
- The diamond state follows the focused field live.
- The value re-reads at the new time while not dragging.
- Edits use the current time.

**Tested:**
- JVM: the dismiss rule.
- Instrumented (not yet run): diamond keys Position with its popover still open, another tile replaces it, and a preview request closes it.

### 7. Caps, size, axis labels

#### Animate caps (slider = soft range; typed = hard, and the thumb pins at the end)

| Field | Slider | Typed / hard | Before |
|---|---|---|---|
| Position, Anchor point (px) | −500…500 | **±10,000** | ±100,000 |
| Scale (%) | **0…400** | **±2,000** | ruler range, ±50,000 |
| Rotation (°) | −360…360 | **±3,600** (10 turns, turns read-out kept) | ±36,000 |
| Tracking (1/1000 em) | −100…100 | **±1,000** | −500…500 typed, ±1,000 hard |
| Blur (px) | 0…50 | **0…250** | 0…100 |
| Skew (°) | −85…85 | ±85 (unchanged) | |
| Opacity family, Line anchor (%) | 0…100 | 0…100 (unchanged) | |

All other rows of the F3.1 limits table are unchanged. The F3.1 checkpoint's table is superseded by this one for these rows.

**Engine ranges.** Animator properties validate −MAX..MAX, so **no engine range changed** and there is no schema change. A test evaluates every new cap at once: all records stay finite and no NaN appears.

**Blur at 250.** `writeRecords` clamps per-glyph softness to the SDF budget: 10% of the glyph size (7.5% with faux bold), minus half the stroke width.
- 250 therefore can never produce an artefact, and the cost is unchanged (a per-glyph shader parameter).
- On a 100 px glyph it renders as 10 px (asserted).
- On a 1,000 px layer the visible blur still stops at 100 px. Values above 100 only show where the relative size takes a glyph past 1,000 px.
- I did not verify the visual result at 250 on the device.

**Scope.** These caps apply to the Animate slider popovers and to its pad/dial/ruler sub-page. The Transform tab has no slider popovers for these properties, and its dial, ruler and position pad keep their own limits: Rotation ±36,000, Scale ±50,000, Position ±10 × composition. Tell me if you want them aligned too.

#### Size

`SliderPopoverMetrics`, applied globally:
- 4dp track (M3 default 16dp);
- 4 × 22dp thumb (44dp);
- 240dp minimum width (280dp).

The row stays 48dp because it is the touch target, so the row height itself is not tighter.

#### Axis labels

Two-axis rows lead with an `AXIS_X` / `AXIS_Y` glyph (new monochrome `InspectorIcons`). This covers Position, Anchor point, Grouping alignment and unlinked Scale.

### 8. Value entry keyboard

**Keyboard types.**
- The Compose UI here is **1.12.1**, which has `KeyboardType.DecimalSigned` and `NumberSigned` (checked in the jar), so there is no version bump.
- Every typed value field defaults to `DecimalSigned`: `InspectorValueField`, the Transform `CompactValueBox` and the Transform numeric dialog.
- Two fields keep their keyboard:
  - **Rotation dial box:** keeps the Text keyboard, because it accepts the turns notation `2x30`.
  - **Seed:** keeps `Number`. Seeds are unsigned, so the sign key has no use.

**Parsing (`ValueParsing.number`)** never throws. Malformed input returns null and the field keeps its last value. It accepts:
- a Unicode minus or dash;
- a leading "+";
- a sign after the digits (`50-`, the Samsung "." / "−" key order);
- comma decimals, or a thousands comma when a decimal point is present;
- spaces and unit suffixes.

It rejects:
- a lone sign or a lone separator;
- a sign inside the digits;
- two decimal points;
- letters, NaN and Infinity.

This is tested. Samsung keyboard behaviour itself is unverified on the device.

### 9. Animate tab layout

- **Empty state:** a full-width "+ Animator" bar at the top (hollow tile, ADD glyph and "Animator"). The faded glyph is centred and smaller (40% of min(body, width); it was 60%).
- **With animators:**
  - The glyph stays fixed in the page centre, does not scroll, and never moves with the list (tested).
  - Collapsed bars are opaque, as before.
  - An expanded animator's rows are one opaque block in the sheet colour (`Surface`). The expanded bar's lower half joins the block, so the glyph shows only in the half-gap margins between bars.
- **"+ Animator" placement:** it now follows the last animator at full width and scrolls with the list. After an add, the list scrolls to the new animator's bar (without that, the move of the add row dragged the scroll to the end).
- **Smaller "+" for property and selector:** a 28dp drawn tile with a 16dp glyph. The touch target is ≥ 48dp wide × one row pitch tall (40dp at 268, 48dp at 328, like every Animate tile).
- **Property tiles:**
  - One collapsible **Property bar**: chevron, count, trash and small +. The tiles below it are in **insertion order**, with no category headers.
  - The add-property popover keeps its Transform / Colour / Text / Advanced categories.
- **Deleting:**
  - **Property tile:** swipe it **sideways** with the bars' swipe component (`SwipeToDelete`), past 40% of the tile and at least 40dp. Removing one property is one undo step, and an accessibility Delete action is provided.
    - Why sideways, not swipe up: the tile row does not scroll horizontally, while the Animate body scrolls vertically, so a vertical swipe is the conflicting direction.
  - **Property bar trash:** removes all of that animator's properties in one action (one undo step). It is hidden when the animator has no properties.
  - **Selector bars:** delete by swipe, as before.
- **The hold-then-delete property menu is removed.** Reset value is still on the rail.
- **Kept from F3.1:** bar structure, eye, duplicate and grip reorder.

### 10. Prev/Next with the layer selected and nothing focused

Only tier 1 of `ContextualNavigation` changed:
- **(a)** A focused field with keyframes uses only its own keyframes.
- **(b)** Otherwise (nothing focused, inspector closed, a static focus or a stale field) tier 1 is every keyframe of the selected layer (`aggregateKeyTimes`): transform and text properties, animator properties, selector fields, seed keys and the animator enabled flag.

The fall-through tiers and the nothing-selected rule are unchanged.

`ContextualNavigationTest` was rewritten:
- an unfocused walk over keys on two properties;
- a text layer with keys on transform, text, animator, selector, seed and enabled tracks, walked in order both ways;
- focused-without-keys falling to layer keys;
- keyed focus ignoring other properties;
- fall-through on a keyless layer.

`TextAnimatorEditsTest`'s stale-field case now expects the layer key.

AE parity §16, Timeline Layout and Markers were updated.

### 11. Files

- **Engine:**
  - `TextSelectorEngine.kt`: Ramp coverage, seed set and lookup.
  - `TextAnimators.kt`: `seedAt` and `TextSeedRange`.
  - `PreparedTextAnimation.kt`: tracking alignment and `seedWarning`.
  - `CompositionRenderer.kt`: seed warning for the preview.
- **Editor:**
  - `EditorViewModel.kt`: transient whitelist and `gestureError`.
  - `ContextualNavigation.kt`: tier 1.
  - `TextAnimatorEdits.kt`: `MAX_SEEDS`.
- **Export:**
  - `ExportStateStore.kt` (new);
  - `ExportService.kt`;
  - `MainActivity.kt`;
  - `EditorScreen.kt`: dialog key, reset, error toast;
  - `EditorDialogs.kt`: Export again.
- **UI:**
  - `InspectorPopover.kt`: non-modal popovers, slider metrics, axis glyph, typing focus.
  - `CompositionPreview.kt`: dismiss on tap.
  - `PropertyLimits.kt`: caps and `ValueParsing`.
  - `ElementInspectorSheet.kt`: keyboards and parsing.
  - `InspectorIcons.kt`: `AXIS_X` / `AXIS_Y`.
  - `TextAnimatePage.kt`, `TextAnimateState.kt`: layout, swipe, trash, seed pre-check.
  - `TextInspectorPanel.kt`: non-modal popovers.

### 12. Verification

- **JVM:** 465 tests, 0 failures. 435 at F3.1.
  - New suites: `SelectorParityF32Test` (13), `AnimatorKeyDragTest` (2), `ExportStateStoreTest` (3), `TextF32UiStateTest` (7).
  - Rewritten: `ContextualNavigationTest` (12).
  - Updated: the golden, `PreparedTextAnimationTest`, `WigglySelectorTest`, `TextAnimateStateTest` and `TextAnimatorEditsTest`.
- **Compile:** `compileDebugKotlin`, `compileReleaseKotlin` and `compileDebugAndroidTestKotlin` all pass.
- **A16 instrumented (SM-A165F), run on request after the first commit.** Debug and test APKs were installed with `adb install -r`. Nothing was uninstalled, no data was cleared, no UI was scripted on the app and no projects were touched.
  - `TextAnimateLayoutTest`: **7 of 7 pass** (fit at 268/328 × 1/1.3; bar icons and both control styles; Character offset/value; the new non-modal popover, X/Y glyph, swipe and trash test). It covers:
    - add bar on top and full width;
    - glyph centred, smaller and fixed;
    - Property bar, trash and toggle;
    - insertion order;
    - add bar after the Grouping row.
  - The first run failed 2 of 7 and exposed a real bug: adding the first animator moved the add bar from the top to the bottom, and the LazyColumn kept its scroll anchored to that row's key, so the list jumped to the end and the new animator was off-screen. Fixed: the page scrolls to the new animator's bar after adding (`revealAnimator`). Then 7 of 7.
  - Regression: `TextInspectorLayoutTest` 3 of 3 (Character tab popovers are now non-modal) and `AnimatorPropertyRenderTest` 2 of 2 (all 25 properties through the preview and export renderers on the new engine).
  - Logs: `build/f32-animate-layout.txt` and the `build/f32-*.txt` files.
  - Not run: the long warm-up / F2 device suites (excluded by the brief).
- **Device:** besides the test runs, only the read-only crash buffer was read. No settings were changed.
### 13. A16 manual test list

1. **Fade up (bottom up):** Animate → Position + Opacity, Position Y 100, Opacity 0%. Offset −100% → 100% over 1 s. Advanced: Ramp Up, Ease High 25, Ease Low 100. The text is hidden at the start and rises letter by letter.
2. **Fade up (right to left):** the same with Position X 100, Y 0.
3. **Ramp Down:** repeat 1 and 2 with Ramp Down (the mirror behaviour).
4. **Flicker:** Opacity 0%, Range Selector Start 0% → 100% over 1 s, Randomize Order on, Seed 0 → 25, then 0 → 150 and 0 → 200, Smoothness 0. The letters flicker in. Then type a seed key 0 → 300: it is refused with the inline line.
5. **Key while open:** with a slider popover open, tap the rail diamond and scrub. The popover stays open and the diamond updates.
6. **Tracking:** a Tracking animator on centred text spreads from the centre. Right-aligned text grows left.
7. **Drag keys:** drag keyframes on the timeline with an Animate field focused (a property, Offset, Seed, the eye). No crash, and one undo step.
8. **Scrub while open:** open a slider popover and scrub the timeline. The popover stays open and the value follows. Tapping the preview closes it, and so do Back and another tile.
9. **Signed entry:** type −50 into a value field with the number keyboard (also try `50-` and the Samsung "." / "−" key).
10. **Axis glyphs:** Position and Anchor sliders show X and Y. So does unlinked Scale.
11. **Layout:** on an empty Animate tab the bar is on top and the glyph centred. Add animators: the + bar moves to the bottom, the glyph stays behind, and expanded blocks are opaque.
12. **Deleting:** swipe a property tile or a selector bar sideways to delete it. The Property bar trash deletes all tiles at once. Undo restores each in one step.
13. **Export:** export, dismiss and reopen the project: no dialog. Export again works, and playback works right after the export ends (saved, cancelled or failed).
14. **Layer-wide Prev/Next:** with a layer selected and nothing focused, Prev/Next hops between every keyframe of the layer, then the markers, in/out and bounds.

---

<a id="rec-text-f3-3"></a>

## TEXT F3.3: popover exit, Prev/Next fall-through, sheet swipe, small polish

*Formerly `docs/checkpoints/TEXT_F3_3.md`.*

Branch `text-f3-3`, built on `master` (`5ad9364`), merged to master in `67df5f3`. One consolidated pass, organised by the sections of the brief.

Status:
- Implemented in source.
- The JVM suite passes (478 tests, 0 failures).
- **A16:** the Animate, inspector-swipe and inspector layout tests pass, as does the render suite (§9).
- Phone acceptance (§10) is done (user-confirmed, 2026-11-22).

Hard rules kept:
- No changes to PreviewController, the gesture protocol, the EditorAction contract, the batch/draw contract, the document schema or preparation.
- No evaluation change: §5 found that box text already spreads from the alignment point and only adds tests.
- Popovers stay non-modal. No new visible buttons, no −/+ steppers, no Done/✓. Sheet heights are unchanged (268dp / 328dp).

### 1. Leaving a slider popover (still non-modal)

The slider popovers stay non-modal (F3.2): the timeline, the transport (play, Prev/Next) and the rail diamond keep working while one is open and do not close it. New ways to close one:

| Way to close | How |
|---|---|
| **Same tile again** | A toggle (`PopoverToggle.next`) on every Animate slider, vector, colour, choice and seed tile, and on the Character / Paragraph tiles (`openPopover`). The rail's "Manual spacing" item still only opens. |
| **Empty sheet-body space** | `Modifier.dismissPopoversOnEmptyTap()` on `text-inspector-body`: an invisible layer that watches the body's taps after its children. A tap that no tile, bar or list consumed requests dismissal (`PopoverDismissal.request`). Scrolls and drags never count. The rail, timeline and transport sit outside this box. |
| **Swipe the popover down** | The popover follows a downward swipe and closes past 48dp; a shorter drag springs back. A swipe that starts on a slider track stays a slider gesture, because an M3 slider moves its value on press (`PopoverSwipeGuard`). The axis glyph, value box and padding all start the swipe. |
| **Back** | Closes the popover first. Its `BackHandler` is registered after the sheet's and the editor's, so it wins; the sheet stays open. |

The existing triggers still work: a preview tap, another tile, a collapsible bar, Back. Closing never loses the value: slider drags commit on release and typed values on Done or focus loss. The toggle keeps the field focused, so the rail and Prev/Next still follow it.

### 2. Tracking slider range

`PropertyLimits.ANIM_TRACKING`: the slider now spans ±1000, equal to its hard limit, with detents every 100. Typed values stay capped at ±1000.

All other caps are unchanged, including the Character tab's Tracking (slider ±100, typed ±500, hard ±1000). I read "the Tracking slider" as the Animate one; say if you want the Character slider widened too. That would mean raising its typed cap to ±1000.

### 3. "+ Animator" bar visibility

`AnimateRows.build` drops the add bar while any animator bar is expanded. It returns at the bottom once every bar is collapsed. In the empty state it is still the top (only) row.

Adding an animator expands the new one, so the bar hides at once, and F3.2's scroll-to-new-animator (`revealAnimator`) still runs. The list keeps its anchor on expand and collapse: the bar you tap is always at or below the first visible item. The A16 test asserts that the tapped bar does not move in either direction.

### 4. Prev/Next: the new fall-through step

**Cause.** In F3.2 tier 1 was "the focused field's keys, or (only if it had none at all) every key of the layer". A focused field that had keys, but none in the direction of travel, skipped the layer's other keys and fell straight to markers and the out point. That is the reported case: Random Seed keyed behind the playhead, then Next jumped to the layer end instead of the Start / Tracking keys ahead.

**Fix (`ContextualNavigation.tiers`).** The layer-wide keys are now their own tier after the focused field.

Final tier list, per direction (the first tier with a strictly earlier / later stop wins; exhausted tiers fall through):
1. The focused property / animator field's keys.
2. **New:** every keyframe time of the selected layer (`layerKeys`: transform, text, animator properties, selector fields, seed keys, enabled).
3. Markers inside the layer span.
4. Layer in / out.
5. Markers outside the span.
6. Timeline bounds.

With nothing focused, an unkeyed focus or the inspector closed, tier 1 is empty and tier 2 gives the F3.2 behaviour. With nothing selected it is unchanged: composition markers, then the bounds.

**Engine-level reproduction (`ContextualNavigationTest`):**
- (a) Seed keyed behind the playhead. Next walks Start 1.5 s → animator Tracking 1.8 s → text Tracking 2.2 s → Start 2.5 s → out point. With the seed keyed ahead, Previous walks back through the same keys. The seed key itself is still the first stop when it lies in the direction of travel.
- (b) An unkeyed field focused while another property is keyed: the jump lands on that key. This runs for each focus kind, both directions, inspector open and closed:
  - Transform (Position, Opacity);
  - Text (Size);
  - Animate animator property (Opacity);
  - Animate selector fields (End, Offset);
  - nothing focused.
- A keyed Text-page focus (Tracking) and a keyed animator property (Tracking) hop their own key first, then the rest of the layer.

No case still skipped keys after the change. The focus plumbing (`TextFocus.owner` returns null on the Animate tab; animator focus clears when the tab or page changes) passes the right field on every page, so the tier structure was the only cause.

### 5. Tracking on box (paragraph) text

This already worked; no fix was needed. The paragraph branch of `PreparedTextAnimation.positionClusters` subtracts each line's animated tracking total times that line's own alignment factor (`paragraph.align[l]`). It is the same rule F3.2 gave point text.

New tests (`TextF33TrackingBoxTest`) on a box that wraps to several lines:
- Centred: each line's visible glyphs grow symmetrically about the line centre, for positive and negative tracking. A trailing space at a wrap stays outside the line.
- Left grows right; right grows left.
- Animated tracking moves the visible glyphs exactly as static tracking of the same amount does (at widths where static tracking does not re-wrap).
- Animated tracking never re-wraps.

### 6. Swipe down from the top of the sheet on every page

**Cause.** The gesture lived in each header row (`SectionHeader`, the landing handle), not in the sheet. Edit Text draws its own header (Character / Paragraph / Animate, and the Fonts sub-page), so it never had one.

**Page audit before F3.3:**

| Page | Had it? |
|---|---|
| Landing grid (handle) | yes |
| Transform, Timing, Blending & Opacity, Audio | yes |
| Edit Solid | yes (uses `SectionHeader`) |
| **Edit Text: Character, Paragraph, Animate, the Fonts sub-page, the Animate Pad sub-page** | **no** |

**Fix.** One sheet-level behaviour, `Modifier.inspectorSwipeDown` on the sheet (`InspectorSwipe.kt`), replaces both per-header detectors. It starts only inside the top band (the 8dp frame plus one header row, which grows with the font scale). So it never fights:
- the Animate list's vertical scroll;
- sliders and tiles in the body;
- text editing: it is off while the source text is being typed, when the top of the sheet is the text field and the keyboard is up.

It watches on the Initial pass and consumes only a downward vertical drag past the touch slop, so a swipe across a tab does not switch the page. The threshold is unchanged (48dp).

`InspectorSwipe.enabled` is an exhaustive `when` over every `InspectorSection`, so a new page must be listed. `TextF33UiStateTest` checks every page. The A16 test `InspectorSwipeLayoutTest` swipes on the real sheet: landing, Transform, Timing, Blending & Opacity, Edit Text (Character, Paragraph, Animate), Edit Solid and Audio, at three points across the header. It checks that each swipe closes, a body swipe does not, and the page tab stays selected.

### 7. Adding a marker must not close the inspector

**Cause.** The playhead marker tap creates a marker and then selects it through `onMarkerSelect`, the same path as tapping a marker. That handler called `dismissInspector()` and cleared property focus. The reason dated from before F3.1: it stopped the focus report reclaiming navigation from the marker. Since F3.1, Prev/Next no longer depends on marker selection, so that reason was obsolete.

**Fix (the cause, not a special case).** `onMarkerSelect` no longer touches the inspector. It seeks, selects the marker, and keeps the layer selection, page and focus. Only a marker owned by a different layer moves the selection there (`MarkerEditing.selectionAfterMarker`). A composition marker keeps the current selection.

Checked for the same side effect:
- **Marker edit and delete** (the playhead tap on an existing marker, and the marker popover) go through `onMarkerAction` and never dismissed the inspector.
- **Markers have no move action.**
- **Ruler and bar marker taps** used the same `onMarkerSelect` and are fixed with it.

The F3.3 state test runs the real playhead rule. It adds the marker, selects it, edits it and deletes it, and checks the selection stays on the layer throughout. `MARKERS.md` is updated.

### 8. Files

- `editor/ContextualNavigation.kt`: the new tier 2; doc comment.
- `ui/inspector/InspectorSwipe.kt` (new): the sheet swipe rule and modifier.
- `ui/inspector/ElementInspectorSheet.kt`: the sheet-level swipe; per-header detectors removed.
- `ui/inspector/InspectorPopover.kt`:
  - `PopoverDismissal` rule extended;
  - `PopoverToggle`, `dismissPopoversOnEmptyTap`;
  - the popover swipe with `PopoverSwipeGuard` / `blocksPopoverSwipe`.
- `ui/inspector/TextAnimatePage.kt`: the tile toggle.
- `ui/inspector/TextInspectorPanel.kt`: the tile toggle (`openPopover(toggle)`); the body's empty-tap dismiss.
- `ui/inspector/TextAnimateState.kt`: add bar visibility.
- `ui/inspector/PropertyLimits.kt`: the `ANIM_TRACKING` slider range.
- `ui/timeline/MarkerPopover.kt`: `MarkerEditing.selectionAfterMarker`.
- `ui/editor/EditorScreen.kt`: `onMarkerSelect` no longer dismisses the inspector.
- **Tests:**
  - New: `TextF33UiStateTest` (8), `TextF33TrackingBoxTest` (3), `InspectorSwipeLayoutTest` (androidTest, 2).
  - Updated: `ContextualNavigationTest` (14), `TextAnimateStateTest`, `TextF32UiStateTest` (the dismiss rule), `TextAnimateLayoutTest` (add bar, popover exits).
- **Docs:** this file, the roadmap, `README.md`, AE parity §16, `TIMELINE_LAYOUT.md`, `MARKERS.md`.

### 9. Verification

- **JVM:** 478 tests, 0 failures.
- **A16 instrumented** (Galaxy A16, installed with `adb install -r`, run with `am instrument`):

| Suite                        | Result                                                                                                                            |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `TextAnimateLayoutTest`      | 7 of 7 (now includes the add bar hide/return without a jump, and the same-tile, empty-space, swipe and short-drag popover checks) |
| `InspectorSwipeLayoutTest`   | 2 of 2 (the first run failed on a test fixture: a video layer needs an asset reference; fixed with a URI-less reference)          |
| `TextInspectorLayoutTest`    | 3 of 3                                                                                                                            |
| `AnimatorPropertyRenderTest` | 2 of 2                                                                                                                            |

- `adb logcat -b crash -d` (read-only): no new app crashes. The only Fluxx entries are the 17:47 "Unsupported transient edit" crashes F3.2 fixed.
- **Not verified on the device by script:** Back closing the popover before the sheet (covered by `BackHandler` order and the state rule; the instrumented harness has no system Back) and the Fonts sub-page swipe (the same header row as the Edit Text tabs, which are tested). §10 items 1 and 7 cover them.

### 10. A16 manual test list

1. Open a slider popover. Close it by: tapping the same tile, tapping empty sheet space, swiping it down, Back. Timeline, play, Prev/Next and the diamond keep working and do not close it.
2. Tracking slider reaches ±1000.
3. Expand an animator bar: "+ Animator" is hidden; collapse all: it returns.
4. Key Random Seed at 0, move the playhead after it, Next: jumps to the next Start/Tracking key, not the layer end.
5. Unkeyed property focused, another property keyed: Prev/Next jumps to that key.
6. Animated tracking on wrapped centred box text spreads from the centre.
7. Swipe down from the top of the sheet works on every page, including Edit Text.
8. With a layer in focus, add a marker: the inspector stays open on the same page.

---

<a id="rec-text-f3-4"></a>

## TEXT F3.4: Character Tracking slider cap and docs fixes

*Formerly `docs/checkpoints/TEXT_F3_4.md`.*

Branch `text` (the single consolidated text branch), built on `master` (`67df5f3`). A small pass that closes the text phase.

Status: implemented in source; the JVM suite passes (481 tests, 0 failures). No device run (none needed); the slider change is a limits change on the existing slider.

Hard rules kept: no PreviewController, gesture-protocol, EditorAction, batch/draw, schema or preparation change, and no evaluation change. No new buttons.

### 1. Character Tracking slider = hard limit

`PropertyLimits.TRACKING` (the Character tab):

|        | Before                     | Now                               |
|--------|----------------------------|-----------------------------------|
| Slider | −100…100, detents every 10 | **−1000…1000, detents every 100** |
| Typed  | −500…500                   | **−1000…1000**                    |
| Hard   | ±1000                      | ±1000 (unchanged)                 |

It now matches the Animate Tracking slider (F3.3); `ANIM_TRACKING` is a copy of it. **Manual spacing** used to share Tracking's ranges by copy; it now keeps the old ranges explicitly (slider ±100, typed ±500, hard ±1000), because no other cap changes. Every other cap is unchanged.

**Usability.** On a ~240dp track, ±1000 is about 8 units per dp. Fine values stay easy without −/+ buttons:
- Typed entry is exact (signed keyboard, lenient parsing).
- A detent every 100 gives a haptic tick and a resting point.
- The soft zero snap is a fixed 1.2% of the track (about 3dp, ±24 units around 0), so small values like 30 are still reachable by dragging.

The coarse part is the common range (−50…+200): it uses about a fifth of the track. A non-linear slider (finer near 0, coarser towards ±1000) would help there. I did not build it; say if you want one.

**Rendering and cost.** Static tracking at ±1000 evaluates to finite records, bounds and glyph boxes, for point and wrapped box text, with and without animator tracking on top (`TextF34TrackingCapTest`). Allocation per evaluated frame is identical at 0 and ±1000: the value is a multiplier in the same code path, so the cost does not change.

### 2. Docs fixes

- **Roadmap current status:** F3.3 is merged to master (`67df5f3`). Phone acceptance of F3.1–F3.3 is done. The text phase is complete and the graph editor is the next big item. The open decisions list is kept.
- **Stale "on branch" / "pending" wording about F3.1–F3.3**, fixed in: the roadmap's F3.x steps, the docs index, the root README, `ELEMENT_INSPECTOR.md`, and the F3, F3.1, F3.2 and F3.3 records.
- **The limits tables** in the F3.1 and F3.2 records keep their historical values; this section records the F3.4 change.
- **All text documents are consolidated into this file**, together with their plans and specs, in build order (see the contents at the top). Inbound links in other docs and in code comments point here.

### 3. Branches

All text and docs branches were already merged into master. They are replaced by one branch, `text`, which holds this pass:
- deleted locally: `text-f3`, `text-f3-1`, `text-f3-2`, `text-f3-3`, `text-fix-9`, `text-perf-10`, `text-perf-11`, `text-ui-5` … `text-ui-8`, `docs-cleanup`, `docs-f3-2-merged`;
- deleted on origin: `text-f3`, `text-f3-1`, `text-perf-11`.

Every one of their commits is in master's history.
