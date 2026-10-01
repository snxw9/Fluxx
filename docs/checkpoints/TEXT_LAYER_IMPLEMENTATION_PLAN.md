# Phase 2 Step E — Revised Native Proof Plan

**Later authorization/status:** E1d, E1e, E2 and E3 are implemented in source and compiled; all new text device behavior remains unverified. Native debug/release compilation passed for arm64-v8a and x86_64, Kotlin debug/release and instrumentation sources compiled, and the final JVM run passed 150/150 tests. No APK was installed or launched. See [stage evidence and exact user-run checklists](TEXT_LAYER.md). The approved overflow policy uses lazy texture-array growth; range selectors and text animators remain future work.

**Text animation scope:** size/fill and hold-keyed Source Text use the existing property framework; see [section 3.1](#31-universal-text-property-animation). This contract is now implemented under the later authorization above. Original planning and execution-gate wording below is historical; current evidence is in the checkpoint.

**Execution update, 2026-10-01:** the user confirmed E1a A16 render/playback/export smoke and explicitly authorized E1b/E1c in one pass. Both CPU passes are implemented; see [actual results](TEXT_LAYER.md). This overrides the individual stop between E1b and E1c in the original gate table. E1d remains unstarted and requires the user's E1a 16 KB environment run and fixed golden-project comparison. E2/E3 remain outlines; device typography verification is pending.

## 1. Decisions and audit findings

E1 proves native text rendering and resource ownership. It adds no persisted text fields or editor controls. E2 and E3 remain outlines and begin only after E1 device acceptance.

**Confirmed in the current source:**

| Finding | Consequence |
| --- | --- |
| `CompositionRenderer` flushes after four staged layers. Native code separately limits pending entries and resident hardware-buffer imports to four. | Glyphs must not become individual layer entries or hardware buffers. |
| `flushBatch()` currently issues one six-vertex draw per imported layer. | Add a dedicated text draw type within the existing ordered batch. |
| `submitWork()` waits for `mWorkFence` before returning. | E1 can safely use one fence-protected staging/mesh slot; there is currently no asynchronous multi-frame compositing ring. |
| Preview, export and thumbnails create independent renderer sessions. | GPU atlases remain per renderer/device. |
| `samplingRenderer` aliases the preview renderer. | Eyedropper sampling does not require another atlas or ownership domain. |
| `LayerGeometry` always applies aspect-fit sizing. | Text needs an explicit natural-pixel geometry path. |
| `LayerType.TEXT` is ordinal 3; the schema stores `type` as an integer. No text payload fields exist. | E2 adds fields without changing existing type ordinals. |
| Text currently reaches an asset-required raster branch; export validation explicitly rejects it. | Existing Text labels/icons are not evidence of rendering support. |
| `ClipGold = SurfaceElevated`, `ClipGoldText = TextPrimary`. | The existing text clip tokens already satisfy the monochrome design. |

**Selected decisions:** single-channel SDF; per-renderer GPU resources; native font ownership; shared native shaping/measurement; debug-only injection through the real composition path; NDK r28 upgrade, as selected.

## 2. E1 — Detailed implementation

### Mandatory internal gates

Each gate requires the user's explicit verification before implementation of the next gate begins. Implementation completion, source inspection, or an unrun test is not gate acceptance. Builds and phone testing remain user-run unless explicitly requested.

| Gate | Scope | Evidence required before proceeding |
| --- | --- | --- |
| **E1a** | NDK r28c upgrade and 16 KB compatibility; no text code or new native dependencies | Packaged-library alignment report, APK alignment, 4 KB/16 KB runtime checks, and existing Video/Image/Solid/audio regression results |
| **E1b** | Vendored FreeType/HarfBuzz and static fonts; CPU-only JNI shaping | Checksummed inputs and glyph IDs, clusters, offsets and advances matching `hb-shape` from the same HarfBuzz release/font binaries |
| **E1c** | CPU-only 48ppem SDF generation and PNG/contact-sheet dumps | Visual inspection of overlapping contours, 8/12/14/16px reconstructions, actual atlas packing capacity, and approved 1024/2048 allocation policy |
| **E1d** | Atlas upload and text pipeline render one string through the real composition path | Correct visible output with validation enabled; atlas repack performs zero rerasterization of retained glyphs |
| **E1e** | Scale/quality matrix, mixed batches, lifecycle and teardown stress | Visual matrix plus passing native-heap and live GPU-resource criteria below |

### E1a — Toolchain and compatibility plan

**Implementation checkpoint:** [TEXT_LAYER.md](TEXT_LAYER.md) contains E1a changes, input audits, the fixed golden workflow and verification status. Source builds of Oboe/graphics-path are retained, but the initial RELRO-only incompatibility claims were withdrawn after correcting the verifier. Final packaged-APK/runtime acceptance remains pending.

1. Preserve or record the existing r26 build's regression results before changing the toolchain. Record project revision, tested APK hash, device/OS/GPU and page size; distinguish pre-existing failures from new ones.
2. Change `ndkVersion` from `26.1.10909125` to `28.2.13676358` in both `app/build.gradle.kts` and `core-engine/build.gradle.kts`. Retain CMake 3.22.1, C++17, `c++_shared`, both existing ABIs, SDK levels, AGP, Oboe and other dependency versions.
3. Add `target_link_options(fluxxengine PRIVATE "-Wl,-z,max-page-size=16384")` in `core-engine/CMakeLists.txt`. Audit app-owned native code for fixed-page-size assumptions in mapping/alignment operations; only replace actual OS-page assumptions with runtime page-size queries, not unrelated 4096-sized buffers.
4. Supply a read-only PowerShell verification script accepting a user-built APK plus SDK/NDK locations. Extract into a unique temporary directory, enumerate every packaged `.so` in both ABIs, inspect `llvm-readelf -lW` output and fail if any `PT_LOAD` alignment is below `0x4000` or offset/virtual-address congruence is invalid. Inventory GNU_RELRO; its raw endpoints need not be page-aligned because the loader rounds them. Run `zipalign -c -P 16 -v 4` on the original APK. Report per-library hashes, alignment and origin; do not rewrite the APK, run Gradle or install anything.
5. Verify both user-built debug and release APKs. Check `libfluxxengine.so`, `libc++_shared.so`, any packaged Oboe library and every transitive native binary, rather than assuming the library inventory. A failing prebuilt blocks acceptance; identify its owner and propose a focused remediation rather than upgrading unrelated dependencies silently.
6. User verifies the existing compositor on the 4GB phone and a Vulkan-capable 16 KB environment, recording `adb shell getconf PAGE_SIZE`. A 4 KB phone alone cannot establish 16 KB runtime compatibility. In each environment check launch, layer rendering, playback/seek and shutdown without linker errors or crashes.
7. Regress Video (including audio), Image, Solid, a mixed composition with more than four visible layers, transform/opacity/keyframes, Full/Half/Quarter preview, surface recreation/backgrounding, thumbnails and headless export. Exercise both the unchanged single-video fast path and mixed-layer Vulkan export. Check sound, mute, play/pause/seek and no new A/V drift or doubled audio relative to the baseline.
8. Record changes and evidence in `TEXT_LAYER.md`, linking it from the documentation index and roadmap; keep status "E1a implemented in source — verification pending" until the user verifies all checks. Stop for that verification. No E1b code, font downloads, shaping, SDF, shaders, text harness, model or editor changes belong in E1a.

### A. Reproducible native build and fonts

Pin both Android modules to **NDK r28c, `28.2.13676358`**, retaining both existing ABIs and C++17. Keep CMake 3.22.1 unless an actual dependency configuration failure requires a separately documented change.

Vendor these sources rather than downloading during configuration:

- **FreeType 2.14.1**, built statically with PIC.
- **HarfBuzz 14.3.1**, built statically with PIC from the amalgamated `harfbuzz.cc` as one translation unit, with a repository-owned CMake wrapper and explicit `HB_NO_*` trimming. Preserve OpenType shaping, Unicode, kerning, ligatures and required font functions. Validate the selected trimming against the untrimmed pinned `hb-shape` reference in E1b.
- Disable unused external integrations, tools, tests and optional font-compression dependencies. HarfBuzz uses its OpenType font functions; FreeType rasterizes the same font bytes. Disable FreeType’s optional HarfBuzz auto-hinting dependency to avoid a circular build dependency.

Record upstream release, commit and SHA-256 checksums alongside each vendored dependency. Keep third-party compiler warnings separate from Fluxx’s `-Werror`. These are explicit version pins, not claims about the latest releases. [FreeType release](https://github.com/freetype/freetype/releases/tag/VER-2-14-1), [HarfBuzz release](https://github.com/harfbuzz/harfbuzz/releases/tag/14.3.1).

Direct remote verification on 2026-09-30 resolved HarfBuzz tag `14.3.1` to tag object `e4580ea56b5b3d01082c5d1f77b233d843438504` and peeled commit `ab5ecbb83985034a76214ac0b2b833dcd590d774`. This confirms the tag; it is not an archive checksum. E1b must record SHA-256 of the exact source archives, static font binaries and license files for FreeType 2.14.1, HarfBuzz 14.3.1, Inter 4.1, Noto Serif 2.014 and JetBrains Mono 2.304. Do not substitute versions merely because a download is temporarily unavailable.

Retain an explicit `-Wl,-z,max-page-size=16384` on Fluxx-owned shared targets. Verify **every packaged `.so`**, including `libc++_shared.so`, Oboe and any debug validation libraries, with `llvm-readelf -lW`: every `PT_LOAD` alignment must be at least `0x4000`. Also verify APK ZIP alignment independently. Static archives do not have ELF load segments; their code inherits the final shared library’s alignment. NDK r28 defaults help but do not certify third-party binaries. [Android 16 KB guidance](https://developer.android.com/guide/practices/page-sizes).

Bundle unmodified **static Regular TTFs**, not variable font builds. E1b verifies the actual binaries have no variation axes; do not rely on filenames alone:

| ID | Face pin | License |
| --- | --- | --- |
| `fluxx.sans` | Inter 4.1 | OFL 1.1 |
| `fluxx.serif` | Noto Serif 2.014 | OFL 1.1 |
| `fluxx.mono` | JetBrains Mono 2.304 | OFL 1.1 |

Ship each font’s original license and record the exact binary checksum. E1’s primary fixture uses Inter; additional fixtures verify the other faces. JetBrains Mono’s license is **OFL**, correcting the supplied draft. [Inter license](https://github.com/rsms/inter/blob/v4.1/LICENSE.txt), [Noto license](https://github.com/notofonts/noto-fonts/blob/main/LICENSE), [JetBrains Mono licensing](https://www.jetbrains.com/lp/mono/).

Documentation includes the required FreeType FTL attribution and HarfBuzz Old MIT notice; package the full notices too. [FreeType FTL](https://raw.githubusercontent.com/freetype/freetype/VER-2-14-1/docs/FTL.TXT), [HarfBuzz license](https://raw.githubusercontent.com/harfbuzz/harfbuzz/14.3.1/COPYING).

### B. Deliberate rendering choice

| Approach | Advantages | Costs |
| --- | --- | --- |
| Android bitmap run | Faster initial integration; platform layout support; existing raster upload path | Whole-run regeneration for content changes; scale-quality/memory tradeoff; future per-glyph animation requires replacing or subdividing the representation |
| Native glyph atlas | Shared glyph storage, native shaping, independently positioned glyph quads | New native dependencies, layout integration, upload synchronization and cache management |

**Choose the native atlas.** Bitmap runs are viable for static titles, but provide the wrong long-term representation for Text Animators. Position-only animation would not require bitmap regeneration, and GPU transforms are inexpensive rather than “zero-cost.”

### C. SDF policy and bounded cache

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

### D. Native ownership and cross-thread lifetime

Use a **native `FontManager`**, not Kotlin-owned FreeType pointers.

| Resource | Owner and access |
| --- | --- |
| Immutable font bytes | Native font catalog, reference-counted |
| `FT_Library`, `FT_Face`, HarfBuzz font/face handles | Native `FontManager`; all mutable face operations under its mutex |
| Shaped layouts and metrics | Immutable native records; bounded CPU cache; no Vulkan handles |
| Atlas image, descriptors, pipeline, staging and mesh buffers | Owning `VulkanRenderer` session only |

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

### E. Real layer path, preparation and synchronization

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

### F. Debug-only trigger and measurable proof

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

## 3. Shared geometry contract and E2 outline

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

## 3.1. Universal text-property animation

These revised contracts are planned, not implemented. They supersede the earlier static-only text-property scope; E1d gates remain unchanged.

### Deviations from the approved plan

1. E2's formerly static size, fill and source fields become Universal Properties immediately: static value plus optional typed animation track. Font size, fill colour and Source Text ship keyframeable in E2/E3, with inspector diamonds in E3. This expands the earlier transform-only animation scope.
2. The shared shaping cache key excludes font size and alignment. Cache design-unit shaped runs by source, resolved font/catalog revision and shaping configuration. Derive geometry from those runs and evaluated size/alignment; do not interpolate between differently shaped strings or populate a size-keyed shaping cache every frame.
3. Add colour and hold-string value types to the existing property/evaluator/persistence framework. Current code has only `AnimatableProperty1D`, `AnimatableProperty2D`, `EASY_EASE`/`LINEAR` and four transform property IDs. Extend these capabilities additively; do not make a separate text timeline, native animation evaluator or undo system.
4. Reserve a versioned, empty Text Animators vector in v9. Its schema envelopes are additive future-extension points, not a claim that selectors/animators execute.
5. Amend the future E1d mesh contract now: optional glyph-local transform, opacity and colour inputs, identity by default. E1d still does not implement animators.
6. Static-edit anchor compensation must not run during property evaluation or keyframe editing. Otherwise animated size/source would mutate Position or create implicit cross-property animation. Static and animated geometry changes have distinct, explicit rules below.

### Universal Property contract (E2)

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

### Animated size, Source Text and the SDF policy

E1b already shapes using font units-per-em. At time `t`, evaluate size with the existing Kotlin evaluator and derive every glyph position, bearing, advance and logical/ink bound using `evaluatedSize / unitsPerEm`. Apply static alignment from the scaled line advances. Geometry and the renderer consume the same immutable layout revision and evaluated size. This is **scaling one shaped layout**, not interpolating two layouts. No aspect-fit contain multiplier is introduced.

Size animation can rebuild/update glyph mesh positions and bounds each frame; it does not call HarfBuzz or rasterize glyphs. Keep runtime buffers reusable. Fill changes update submission colour only. Neither size nor colour belongs in the shaping/SDF cache key. Shared metrics are cached in design units; scalar conversion is permitted per frame and does not acquire the font mutex. Geometry JVM tests use the injected fake metrics provider.

The SDF remains 48ppem, 8px spread, R8, with overlap handling enabled and CPU bitmap retention. Font-size changes and layer scale both affect its final sampling footprint; future E1d coverage reconstruction must use screen-space derivatives. No size buckets or per-frame SDF regeneration. The small-size/extreme-magnification quality limits from E1c remain; do not promise unrestricted sharpness. The minimum allowed size remains subject to E1e quality acceptance; 500px maximum and existing input bounds are provisional planned limits, validated for both static values and keys.

Source Text keys select discrete layouts. Prepare unique strings on a worker when keys/content/font settings change, prefetch upcoming hold transitions, and retain immutable leases for the active/prepared working set. Repeated frames within a hold interval perform no shaping. Seeking to an uncached string may require preparation **before submitting that frame**; it must not put HarfBuzz in the render pass or on the UI thread. Preview retains its last complete frame while preparing the requested revision; export/thumbnails wait for the exact revision. Very large tracks use bounded caches and may re-shape after eviction. Thus the precise rule is *no shaping merely because time/size/colour advanced*, not an impossible promise that arbitrary uncached content switches never require shaping.

### Bounds, anchors and other operations

Retain anchor fractions. A purely static source/font/size/alignment edit may compensate static Position to preserve the first-baseline alignment origin, as previously planned. Do so only when layout-affecting text properties have no enabled tracks and Position is static; animated scale/rotation retain the previous edit-time-only guarantee.

For keyed size/source edits, playback, or static font/alignment edits on animated text, **do not compensate Position or rewrite any track**. The stored anchor fraction is evaluated against the current bounds; edges can move as size or source changes. If a fixed left/centre/right origin is needed, the user chooses a matching anchor or explicitly animates Position. This is a deliberate deviation from static-edit compensation and must be covered by tests, not presented as AE pixel-anchor equivalence.

Fit Width/Height/Stretch uses evaluated bounds at the playhead and the existing scale auto-key behavior; it does not rewrite font size. Empty evaluated text has zero bounds, draws nothing, retains selection and disables Fit/Stretch for that instant. Move/trim preserve temporal anchors; split rebases every text track under the existing keyframe rules, retaining neighboring keys needed for continuous evaluation. Duplicate/copy/paste, undo/autosave and thumbnails include the entire property payload. Timeline labels display the evaluated held string at the playhead. Text-property focus participates in the existing marker/keyframe navigation priority.

### Additive v9 persistence outline

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

### E3 editor and typing transaction outline

Edit Text exposes diamonds for **Source Text, Font Size and Fill Colour** alongside their controls. Use the same enable/disable/add/remove/auto-key semantics and frame grid as transform. Turning off a track freezes its evaluated value at the playhead using the existing policy. Scalar/colour keys expose supported easing; Source Text shows Hold and never offers Bezier controls. Fill uses the existing Global Colour Picker; picker gestures preview one colour value/key and commit through existing history.

On text focus, capture the selected layer/property, original snapshot and snapped, anchor-relative edit time. With Source Text animation disabled, drafts change the static string. With animation enabled, drafts update the existing key at that captured frame or insert **one** hold key there, preserving all others. An explicit first-key diamond is a separate intentional history action; typing does not silently enable animation.

All keystrokes preview against the original snapshot. One typing session commits one final source/key mutation on Done, focus loss or dismiss. Cancel restores the exact original source/track and any permitted static Position compensation, removing a draft-created key. An unchanged final draft produces no history entry. IME composition updates are drafts too, never individual undo entries.

Before seek/playback, layer/property change or a key-toggle operation, finish the current typing transaction at its captured time; never retarget an in-progress session as the playhead moves. Commit awaits the latest metrics revision asynchronously. Reject stale worker completions by session/content revision and publish string, layout and permitted compensation atomically. These rules fit the existing gesture transaction contract; they do not add a second undo mechanism.

Retain the approved compact non-scrolling controls, internally scrolling text field, global colour picker and workspace-level IME handling. Main-thread callbacks never acquire the shared font mutex. Size/colour drags and typing all retain their own existing gesture boundaries.

### Required acceptance additions and risks

- Static/animated size, colour and hold strings round-trip through v9 and undo; v1–8 fixtures load unchanged.
- Exact key boundaries, signed rebasing, trim/move/split, duplicate/copy/paste, empty-string keys and first/last endpoint holds behave consistently.
- Instrument shape/SDF miss counts: warm size/colour sweeps produce zero misses; repeated hold intervals do not shape; arbitrary seek waits for the correct layout revision.
- Size/fill/source preview, export and thumbnail results agree, including transparent colour keys and simultaneous transform animation.
- Inspector diamonds, common navigation and timeline keys include all three new properties; Source Text never exposes interpolation curves.
- Typing between keys inserts one hold key/undo entry; typing on a key edits it; cancel removes the draft; IME/seek/dismiss cannot commit at a different time or revive stale layouts.
- Static compensation and animated anchor behavior are tested separately, including empty bounds and animated Position.
- Empty animator vectors round-trip; unsupported nonempty vectors cannot be silently stripped. Optional glyph modifiers default to identity with unchanged draw ordering/batching.

### E1d interface amendment only

The future text mesh builder accepts an optional span/provider of glyph-local affine transforms, opacity multipliers and RGBA multipliers; absent input means identity transform, opacity 1 and colour (1,1,1,1). Compose the modifier in glyph-local space before the layer transform and multiply evaluated layer fill/opacity once. Retain per-glyph colour/opacity attributes in the draw representation even when uniform defaults are used, so later animators do not require a different pipeline layout. The callback/provider consumes prepared values; it performs no shaping, allocation or application-state mutation inside the render pass.

Preserve glyph ID, source cluster mapping and line metadata. A shaped ligature can cover multiple characters: this hook is a glyph mechanism, not a promise of one quad per Unicode character. Future range-selector semantics must resolve grapheme/cluster and ligature behavior explicitly. Modifier application and animator execution remain unimplemented now; E1d will only establish the optional identity-compatible mesh contract.

## 4. E3 outline and capability audit

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

**IME:** handle keyboard insets once at the editor workspace. While typing, hide the timeline/action strip, keep a compact non-scrolling text field/Done panel above the IME, and allocate the remaining upper area to a letterboxed preview. Restore the prior layout after keyboard dismissal. Do not stack `imePadding()` onto the existing fixed sheet and assume it fits.

**Undo:** begin one session on text focus, preview complete drafts relative to its original snapshot, commit on Done/focus loss/dismiss, and explicitly cancel to restore the original. Reject stale asynchronous layout results by session/revision. Additive text actions carry any static Position compensation; preserve the locked gesture protocol, existing action semantics and `PreviewController` API.

Detailed E3 planning follows the verified E2 pipeline.

## 5. Documentation, gates and remaining risks

- Create `TEXT_LAYER.md`, linked from the documentation index and roadmap, recording E1/E2/E3 separately.
- E1 adds native pipeline, atlas, dependency/license, ownership, alignment and measured device-result sections. Keep the Text creation icon marked nonfunctional.
- E2 records v9 compatibility, shared bounds, export/thumbnail integration and cache overflow handling.
- E3 updates `ELEMENT_INSPECTOR.md`, capability statuses and the Text icon only after editor integration. Mark Step E complete only after acceptance.
- Record bundled-font/SDF choices and deferred Text Animators, Range Selectors, text-on-path, auto-wrap, font import, stroke and tracking/leading controls in the parity vision.
- E1 excludes complex-script support, bidi/RTL layout, emoji and synthetic bold/italic. HarfBuzz’s presence alone does not establish those features.

Primary risks are SDF quality at small sizes/Quarter resolution, shared font-lock contention, atlas working-set overflow, NDK regression, and resource retirement on cancellation/device loss. E1 explicitly measures these; it does not claim general text support or leak freedom from a single successful screenshot.
