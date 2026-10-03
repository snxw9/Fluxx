# F1: Text property completion

F1 is approved with the user's eight amendments. Implemented source is subject to the compile/JVM results below and user-run acceptance. E1–E3 text behavior was confirmed on device by the user; this does not establish F1 device, thermal, golden or 16 KB acceptance.

## Properties and persistence

Tracking (thousandths of an em), additive leading (composition pixels), baseline shift (positive upward), stroke colour, requested stroke width and stroke/fill order use the existing static-plus-optional-track framework. Paint order stores only `0` (fill over stroke) or `1` (stroke over fill) and samples with hold evaluation, regardless of the scalar track's easing metadata.

The fixed Text, Spacing and Stroke inspector pages expose diamonds, animation freeze/easing menus where applicable, colour editing and gesture transactions. Continuous scalar edits use the existing text edit controller. New fields participate in key navigation, timeline diamonds, undo, copy/paste, duplicate, signed rebasing, split and persistence.

F1 writes **v10**. Fields append to `TextPayload`; existing fields/ordinals are unchanged. Absent fields use identity defaults, including v9 documents. The additive loader must permanently retain v10 support when F2 introduces v11. F2 is not blocked on finalizing its reserved tables now; its `text_animators` vector remains empty in F1.

## SDF budget and centred stroke

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

## Shared layout membership and ordering

HarfBuzz remains LTR/Latin. RTL and complex scripts are out of scope. Latin combining marks remain covered.

Preparation caches source-character/grapheme byte boundaries (combining marks share their base character), cluster byte boundaries, glyph-to-cluster membership, source-character spans inside ligatures, line cluster counts, non-space character ordinals, and whitespace-delimited word membership with the immutable layout. Newlines advance line membership; they do not create drawable characters. F2 selectors must consume this map rather than constructing another one and define how partial source-character selection projects onto an indivisible ligature cluster.

Tracking is applied between clusters at every value. Ligatures remain intact, including through zero crossings; this intentionally differs from AE/InDesign behavior that can disable ligatures for tracking. Spacing does not change the source/font shaping-cache key. Line advances include tracking before alignment; leading shifts line baselines; baseline shift moves ink.

F2's order remains: evaluate tracks → compute and combine selector coverage → resolve modifiers → accumulate selected tracking/line-anchor offsets → compute advances/alignment → apply glyph affine/paint/SDF properties → emit mesh/bounds. Randomized permutations use a persisted deterministic seed and fixed algorithm shared by preview/export/thumbnails.

## Per-frame work and instrumentation

Prepared glyph references eliminate string-key atlas lookups in mesh building. CPU mesh capacity and GPU vertex/index buffers are provisioned before animated evaluation. Unchanged handle sets reuse native text preparation through a scalar JNI entry point, avoiding frame-time JNI handle-array copies. Four visible-quad vertices and six indices remain in the single indexed mesh per layer.

Track sampling and mesh building perform O(glyphs + lines) work, without shaping or rasterizing. Prepared text-only compositions share a reusable production path across preview, export and thumbnails; all Source Text keys are prepared before playback. Mixed media compositing retains its existing scheduling path; the allocation fixture measures the complete prepared text-only worker render, not the entire application or decoder/UI threads.

Debug builds replace native `operator new` variants with thread-local counters bound within libfluxxengine. This counts C++ new calls, including aligned/array/nothrow forms, not malloc or Vulkan driver internals. The harness probes the counter with an intentional allocation. Kotlin worker allocation deltas use `Debug.getThreadAllocCount()` (Kotlin `threadAllocCount`) after `startAllocCounting`; an intentional Java array must increase it. Unsupported/inert ART counters fail explicitly and must not be reported as zero-allocation proof.

The fixture asserts 500+ shaped glyphs, warms 120 frames, then records 600 full-resolution frames. It reports mesh-build and total frame p50/p95/p99/max, both allocation deltas, shape/rasterization/atlas-upload deltas and atlas generation. Both allocation deltas must be zero. It repeats after approximately three minutes of continuous render load. A16 measurements remain user-run; no numbers are invented from host timings.

## Spread spike

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

## Exact user-run acceptance list

1. Build debug app and instrumentation targets in Android Studio. No device installation/run was performed by the agent.
2. Run `TextF1Test` and the existing JVM suite: requested/effective width, shared softness budget, scalar/colour/hold tracks, v9 defaults/v10 round trip and malformed values, key navigation, move/freeze/reset, gesture undo/redo, duplicate/clipboard/split and prepared cluster-spacing bounds.
3. On A16, open Text → Spacing → Stroke. Edit every property statically, enable its diamond, set two keys, scrub, navigate previous/next keys, freeze, undo/redo. Each continuous scalar/colour edit should create one undo entry. Test cancel, colour picker, eyedropper and IME return.
4. Use multiline Latin, spaces, NBSP, empty lines, Noto Serif `office` ligatures and `A` plus U+0301 combining mark. Animate tracking through negative/zero/positive values; verify no ligature pop. The bundled Inter shapes `office` as separate glyphs, so use Noto Serif for the ligature check. Test additive leading and baseline shift under left/centre/right alignment. Exclude RTL/complex scripts.
5. At sizes 8/72/500, test requested stroke 0, half the size-dependent cap, the cap and an oversized request. Animate size 8→500 with requested width 20px: inspector must report 1.6px effective at size 8 while keeping the 20px request. Save/reopen and verify the request remains 20px.
6. Test both paint orders using translucent contrasting fill/stroke, thin and thick glyphs, rotated/flipped/scaled text, and Full/Half/Quarter preview. Explicitly test thick stroke plus tight/negative tracking in both orders: F1 composites per glyph, so a later glyph's stroke can cover an earlier glyph's fill. Inspect round joins, clipping, overlap order and selection/fit/anchor bounds. Include 300%+ scale and enlarged `g` contour kinks. Compare host 48/8 and 64/12 masks at 8/72/500; production spread changes and optional all-glyph two-pass scope require separate approval.
7. Save/close/reopen a composition containing all six new animated properties. Repeat after duplicate, copy/paste, trim/extend and split; check key timing/navigation and undo.
8. Run `TextF1AcceptanceTest.equalResolutionPreviewExportAndThumbnailPixelsMatch` with unique `textRunLabel`. It compares equal-resolution pixels at 0/5/10 seconds before encoding. Separately export MP4 and review it; codec output is not required to equal compositor bytes.
9. Run `TextF1AcceptanceTest.fiveHundredGlyphsAllocateNothingColdAndAfterThreeMinutesLoad` on A16, with unique `textRunLabel`. The source is `"AV office gy 0123456789 \n".repeat(30)` in Noto Serif, exercising real ligatures and line spacing. Archive both JSON reports: 600 frames, worker/native allocation deltas zero, shape/rasterization/upload/generation unchanged, mesh/frame p50/p95/p99/max. The second window follows 180 seconds continuous load. Counter canaries must pass; unavailable counters are a failed proof, not a pass.
10. Run existing `TextGpuAcceptanceTest` / `TextGpuProofTest` lifecycle, mixed-order, repack, capacity recovery, quality and resource checks. Require Vulkan validation enabled with zero errors and teardown resource recovery.

F2 engine and F3 UI acceptance remain separately gated. Per-character 3D, text on path, expressions and wiggly selectors remain deferred.

## Validation results

Passed compile-only checks for app/engine debug Kotlin and both instrumentation source sets; native debug and release compiled for arm64-v8a and x86_64. All 157 JVM tests passed (including seven new F1 tests), with zero failures, errors or skips. The host text self-test passed after adding shared character/cluster membership and ligature-span checks.

Local logs are under the ignored `tools/text/results/` directory: `f1-acceptance-final.log` (Kotlin/JVM), `f1-native-membership-final.log` (both native configurations/ABIs), `f1-final.log` (instrumentation compilation), and `f1-fixture-final.log` (final Noto Serif acceptance fixture compilation). Spread data is in `f1-spread/`; generated results are deliberately excluded from the source commit and can be reproduced with the commands above.

No APK was packaged or installed, and no device tests were run. A16 mesh/frame timings, allocation assertions, thermal repeat, pre-encode parity and visual acceptance remain user-run gates; successful host compilation does not establish them.

### Authorized device execution, 2026-10-03

The user successfully launched and completed `TextF1AcceptanceTest.fiveHundredGlyphsAllocateNothingColdAndAfterThreeMinutesLoad()` on the connected Samsung SM-A165F (A16) via Android Studio (`success=true`).

Key verified results:
- **Corpus & Fixture:** 500+ shaped glyphs in Noto Serif (`"AV office gy 0123456789 \n".repeat(30)`).
- **Cold Window (Pass 0):** 600 full-resolution frames rendered with 0 Kotlin worker thread allocations and 0 C++ native allocations (`workerThreadAllocDelta = 0`, `nativeNewDelta = 0`).
- **3-Minute Thermal Load:** Rendered continuous preview load for 180,000 ms (3 minutes).
- **Thermal Window (Pass 1):** 600 full-resolution frames rendered with 0 Kotlin worker thread allocations and 0 C++ native allocations.
- **Vulkan & Counters:** Vulkan validation enabled with 0 validation errors (`validationErrors = 0`), and prepared text counters (rasterizations, atlas uploads, shapes, atlas generation) remained completely unchanged across measurement windows.
