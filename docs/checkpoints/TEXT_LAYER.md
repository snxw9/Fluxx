# Text layer checkpoint

**Edit Text UI rework (2026-11-18):** Implemented in source, device-unverified; UI only. No text model, schema, preparation, native, PreviewController, gesture-protocol or batch/draw change. Aggregate popups are deleted; all common Character/Paragraph controls are on fixed tab faces; overlays stay inside the sheet; the header ⋮ has four items; the status line and Hold label are replaced by rail diamond states, a selection chip and the feedback strip; Back returns to the layer inspector grid; size / vertical scale / horizontal scale have distinct glyphs. Stroke clamp feedback still preserves the requested value and reports the effective range only while clamped. [Inventory](../architecture/TEXT_UI_OVERHAUL_PLAN.md#11-rework-2026-11-18-final-surface-and-inventory) and [gate, deviations and user-run list](TEXT_UI_OVERHAUL.md#rework-commit-2-menu-back-status-and-icons).

**Current T5 stop (2026-10-09):** FIX `85b9de4` and EXPLODE `59a54a0` passed their compile/JVM/host gates and are implemented in source, device-unverified. T5 is dropped under the approved droppable-stage rule. The user's indivisible cursive-word decision resolves the historical emergency-wrap finding, but a new host proof shows mandatory Arabic lam-alef is lost across separate style items even with full source context and optional ligatures off. Cross-style shaping requires a further style-ownership decision beyond the approved wrapping change. No partial RTL code or production fonts remain; stored RTL still receives the existing rendering error. The diagnostic and this report remain uncommitted after the two feature commits. [Evidence, limits and exact manual tests](TEXT_FIX_EXPLODE_T5.md). Earlier checkpoint statements below retain their historical context.

## FIX / EXPLODE / T5 continuation — 2026-10-09

EXPLODE implements the user's revised representation: Lines/Paragraphs/Style runs retain the original box; Words/Characters become point text. One compact top-level dialog, logical naming/order, shared prepared visual placement, Group/Each pivots, held layout sampling and one undoable creation action are implemented in source, device-unverified. No frozen layout data is persisted. Non-final justified lines map to justify-all; final lines retain their alignment. Geometry that cannot be reproduced with ordinary pieces is explicitly refused, including some partial style runs and animator shape changes. Characters preserve Unicode Joining_Type cursive words as indivisible spans; contextual forms for other character splits may change as accepted. Anchors extend additively to finite ±1,000,000 normalized units, without silent clamping. See the [current compile/JVM/host evidence, limits and exact manual list](TEXT_FIX_EXPLODE_T5.md). T5 rendering is still gated; its final indivisible-word decision supersedes the historical stop below.

The user has manually tested T1–T4 on a phone; the case/script and responsiveness findings in this continuation are user-reported results. FIX adds lowercase and corrected per-glyph Small Caps, uniform synthetic script positioning, shared transient preview/source selection, composition-fill justification and preparation/cache/idle-warming/timing changes. These changes are implemented in source and device-unverified. Native Debug/Release (both ABIs), Kotlin Debug/Release, both instrumentation source sets and 237 JVM tests passed; host selftest, six shaping references and 1,445 checksums passed. No new Character/Paragraph sub-screen, PreviewController API, gesture transaction protocol or batch/draw contract is introduced. EXPLODE is committed in source; T5 is not implemented. The user's Unicode Joining_Type indivisible-word decision supersedes the old T5 emergency-break decision below. See [the current implementation/evidence, deviations, limits and exact user-run list](TEXT_FIX_EXPLODE_T5.md). Historical entries retain their original evidence; F1 performance/parity/lifecycle remain unrun.

## T4c character substitution continuation — 2026-10-05

Character Offset/Value integration is implemented in source, with its commit gated on compile/JVM/host checks. Worker preparation builds standalone cmap/SDF catalogues for every reachable source alternative and requested font identity, including static variation coordinates and the existing fallback chain. Imported fonts stay in the isolated worker. Character replacement preserves logical selector membership, uses its own nominal advance, and retains original combining characters. It performs no GSUB/GPOS shaping; active replacement therefore does not form ligatures or reapply OpenType caps/script substitutions. The original prepared run scale, baseline and faux styling still apply. This feature interaction is an explicit assumption/deviation.

Offset evaluates `round(currentCodepoint + coverage × requestedOffset)` in animator/property order. Value is an absolute rounded codepoint when coverage is nonzero; zero coverage retains the current/original glyph. Rounding is `floor(x + 0.5)`. Invalid, control, format or surrogate results restore the original source character. Replacement, like animator tracking, changes advances/alignment/pivots after base wrapping and does not reflow or rejustify the line. All members of an original ligature cluster receive its projected coverage, even when nominal replacement emits separate glyphs.

Preparation conservatively bounds signed selector influence to [-1,1] and allows at most 4,096 distinct reachable codepoints per layer, 16,384 font/codepoint catalogue glyphs, 32 MiB character packets per layer and 64 MiB retained text/character packets per renderer project. Exceeding a bound fails explicitly. The unchanged 64-layout cap covers held documents × ligature modes; catalogues are resources, not wrapping variants. Native preparation pins reusable codepoint buffers alongside each modifier-buffer identity, so layers sharing a layout retain independent replacements. Existing 96-byte modifier records use a reserved flag; draw arguments, indexed-mesh batch contract, PreviewController and gesture protocol are unchanged. Per-frame lookup is bounded binary search over the prepared codepoint set, in addition to the existing selector/property work; no shaping, rasterization, upload, permutation generation or allocation is permitted in prepared evaluation.

The full gate in `t4c-final.log` passed native Debug/Release on arm64-v8a and x86_64, Kotlin Debug/Release, both instrumentation-source compilations and all 228 JVM tests (zero failures/errors/skips). The warmed character evaluation fixture asserts zero allocated bytes over 600 frames; host Release/selftest, all six shaping references and 1,445 checksum checks passed. The final baseline correction and final fixtures passed the same complete gate in `t4c-commit-gate.log`. New source-only fixtures cover all 25 property families at representative and maximum counts. No production font assets were added (asset delta zero; code/APK delta unmeasured, no packaging). F1 perf/parity/lifecycle remain deferred and unrun. No device results or A16 timing are claimed.

### T5 stopped under the approved droppable-stage rule

T5 cannot be completed by retaining the current fixed shaped-cluster advances through every possible emergency wrap. The host `rtlboundaryproof` uses the same trimmed HarfBuzz build, explicit Arabic script/RTL direction and the system Arial outline font. Joined Arabic U+0628 × 3 produced glyph/advance pairs `911:1461, 913:500, 913:500`; isolated U+0628 produced `911:1461`. Splitting that source word across lines therefore requires different joining forms and different advances. Mere visual bidi reordering of the paragraph's prepared glyphs would be degraded Arabic. Disallowing emergency breaks would change approved T3 behavior.

Correct support needs a design extension for boundary-dependent prepared shaping/advances and a wrapping solver that selects those variants without per-frame shaping or allocation, while preserving logical clusters/selector maps. Enumerating animated wrapping layouts or shaping at evaluation is forbidden. The existing HarfBuzz trims do not disable Arabic/Hebrew OT shapers, but that alone does not resolve this layout dependency. This is an architectural finding, not a device/performance gate. Stop after T4 as authorized; do not ship partial Hebrew-only/RTL support or add Arabic/Hebrew production fonts prematurely. Stored RTL attributes remain preserved and explicitly rejected for rendering with the existing actionable error.

Reproduce the diagnostic with `text-proof FONT_DIRECTORY rtlboundaryproof C:/Windows/Fonts/arial.ttf` (or another covering outline font); it is a host dependency, not a copied/bundled font. Future T5 acceptance must include joined Arabic words under animated emergency wrapping, marks, mixed Hebrew/Latin/numerals, logical selectors, physical alignment, mirroring and equal-resolution parity/performance. These are requirements for the future stage, not implemented fixtures or passing results.

Exact T4c user-run list:

1. Run `TextF2AcceptanceTest.characterRepresentativeColdAndThermal` and `characterMaximumColdAndThermal` with unique `textRunLabel` values. Archive both cold/thermal JSON reports from each; check p50/p95/p99/max, native-new/ART allocation deltas and shaping/rasterization/upload/permutation/font-worker canaries. Fixtures use 500+ and 4,096 glyphs, 8×4 and 16×8 animator/selectors, all 25 properties, rich runs, wrapping, mixed modes, wiggly dimensions and held seeds. A16 60 fps is unmeasured.
2. Run `TextF2AcceptanceTest.characterRandomAndReversePreEncodePixelsMatch` for equal-resolution preview/export/thumbnail parity under random access/reverse scrubbing and held source/width alternatives.
3. Inspect debug-authored Character Offset/Value documents: signed/fractional offsets, absolute values, original combining marks, ligature-cluster selection, fallback/missing glyphs, imported static variations, run/faux styling, invalid/control/surrogate restoration and explicit cap failures. Check unchanged membership, nominal advances, post-wrap spacing, bounds/anchors/Fit/Stretch/overlays/hit tests and independence of two layers sharing a prepared layout.
4. Save/reopen, copy/paste/duplicate, reorder, split/trim, undo/redo, freeze/reset and navigate keys for the new properties and keyed wiggly fields. No F3 animator controls are added.
5. Reproduce native Debug/Release both ABIs, Kotlin Debug/Release, both instrumentation-source compilations and the full JVM suite. Run host selftest, six shaping references and checksum verification. Device execution remains user-owned.

## T4b paint/spacing/dimension integration — 2026-10-05

T4a is committed as `adc922b`. T4b integrates property IDs 14–23 and unlocked wiggly dimensions through the existing 96-byte per-cluster modifier record and per-glyph RGBA/affine attributes. No shader, mesh ABI, PreviewController, gesture or batch/draw changes. Character offset/value (IDs 24/25) remain explicitly guarded pending T4c prepared cmap integration; T4 is not complete yet.

Unlocked wiggly uses dimension 0 for scalar/X influence, 1 for Y and 2 for blue/brightness influence. Locked selectors share dimension zero; range selectors are dimension-invariant. Position/scale/anchor use separate X/Y weights; rotation/skew use scalar influence. RGB target blends clamp each component weight in linear-light premultiplied space; shared alpha uses dimension zero. Colour never extrapolates. Fill/stroke opacity multiply their separate existing alpha with weighted requested opacity, independently of glyph/layer opacity. H/S/B adjustments use straight-sRGB HSV: additive hue degrees modulo 360, saturation/brightness in percentage points, clamped to their legal range, preserving alpha. This HSV space and bounded value-noise behavior are explicit AE assumptions/deviations.

Line spacing is an additive composition-pixel adjustment once after each visual line, with source-character-weighted mean selector influence (so ligatures do not bias the mean). It shifts subsequent baselines without reflowing, then updates logical/ink bounds and post-spacing pivots. Empty lines have no selected glyph influence. Grouping alignment offsets the per-animator group pivot from its centre by XY percentages of the post-spacing group size; it changes rotation/scale/skew pivots and is inert under identity transforms. Per-animator grouping remains the documented AE deviation.

Verification: `t4b-modifiers-initial.log` passed Kotlin Debug/Release, both instrumentation source sets and all 222 JVM tests, zero failures/errors/skips. The warmed 600-frame zero-allocation fixture now includes all 23 integrated property families and unlocked wiggly dimensions. Four added fixtures cover independent paint alpha/HSV, grouping pivots, unlocked position/random access and once-per-line spacing/bounds. Native/host sources are unchanged from T3b's passing Debug/Release both-ABI and host/reference checks. New instrumented performance/parity fixtures are compiled, source-only, device-unverified. No APK packaging or device execution; asset increment zero, code/APK delta unmeasured. F1 perf/parity/lifecycle remain deferred and unrun.

Exact T4b user-run list:

1. Run `TextF2AcceptanceTest.advancedModifierRepresentativeColdAndThermal` and `advancedModifierMaximumColdAndThermal` with unique `textRunLabel` values; archive both JSON reports from each. Fixtures use 500+ and 4,096 glyphs, 8×4 and 16×8 animator/selectors, mixed range/wiggly/modes, seed keys, unlocked dimensions, rich runs and wrapping. Check native-new/ART allocation and preparation canaries, cold/three-minute thermal p50/p95/p99/max, and zero permutation-build/font-worker deltas. A16 60 fps remains unmeasured.
2. Run `TextF2AcceptanceTest.advancedModifierRandomAndReversePreEncodePixelsMatch`: compare equal-resolution preview/export/thumbnail pixels under random access/reverse scrubbing, seed changes, held source/width alternatives and all integrated modifier families.
3. Use debug-authored animator documents to inspect fill/stroke opacity independently (translucent paints, both per-glyph paint orders, thick stroke/negative tracking), H/S/B, signed selector modes, locked/unlocked vectors, grouping pivots and line spacing in point/box text. Check anchors/Fit/overlays/hit tests and persistence/copy/split/undo/key navigation; no F3 controls are added.
4. Reproduce compile/instrumentation/JVM checks (222 tests), plus unchanged host selftest/shaping references. Character substitution acceptance follows T4c; supported RTL follows T5.

## T4a animator model/codec/selector foundation — 2026-10-05

T3b is committed as `0da4590`. T4a adds v12 selector table version 3 (v11 version 2 still loads), keyed selector kind/frequency/correlation/phases/dimension lock, exact unsigned 64-bit seed keys, and property IDs 14–25: fill/stroke opacity, fill H/S/B, stroke H/S/B, line spacing, grouping alignment, character offset/value. Typed universal property payloads, validation, reorder/copy/split/undo/key navigation remain shared. Seed keys are hold-evaluated with strictly increasing times, maximum 4,096 keys; all bits survive persistence. New rendering families and unlocked wiggly dimensions fail explicitly until T4b integration, preserving the requested document. No animator UI is added.

Wiggly is a pure deterministic SplitMix64 lattice value-noise function. Let `t = timeSeconds × frequency + temporalPhase/360`, `s = member + spatialPhase/360`; interpolate the four hashed lattice corners using `u²(3−2u)` on each axis. Correlation `rho = correlation/100` blends independent member noise with common noise at `spatialPhase/360`; 0% is independent and 100% identical for all members. Locked dimensions hash dimension zero, otherwise the supplied dimension. Influence is `amount/100 × (lo + (hi−lo) × (noise+1)/2)`, with sorted min/max divided by 100 and final signed clamp. Separator members receive zero. Modes and cluster-mean projection are unchanged. Noise coordinates wrap every 1,000,000,000 lattice units to keep finite huge phase values deterministic; this bounded, non-Gaussian noise is an explicit AE deviation.

Range selectors also support held seed keys. Preparation prepares all four membership permutations for every reachable seed; at most 64 distinct seeds per selector. The shared 32 MiB permutation payload budget now includes membership permutations, not just line counts, for point and box text alike. No seed/permutation generation occurs in evaluation. These are explicit preparation limits, not dropped keys. Frequency is bounded to 0–1,000 wiggles/second; correlation to 0–100%. T4a adds no assets; APK/code size remains unmeasured. Kotlin Debug/Release and both instrumentation source sets passed in `t4a-final.log`; the final known-vector/shared-budget JVM gate passed all 218 tests in `t4a-final-jvm.log`. Final Debug/Release and instrumentation compilation after the shared-budget correction passed in t4a-final-sources.log. Native/host sources are unchanged from passing T3b checks. T4b rendering/substitution and T5 RTL remain outstanding. F1 performance/parity/lifecycle remain unrun.

T4a user-run checks: reproduce the full JVM and Kotlin/instrumentation source compile gate; `WigglySelectorTest` covers random/reverse evaluation, continuity, correlation/dimension endpoints, exact seed bits/hold keys, codec/rebase/undo/navigation, malformed inputs, permanent v11 compatibility and zero allocated bytes over a warmed 600-frame window. Run the unchanged host selftest and shaping references. No new device acceptance or F3 controls are claimed at this foundation stage; T4b must add the complete paint/spacing/substitution parity/performance fixtures before T4 is complete.

## T3b paragraph/box integration — 2026-10-05

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

## T3a paragraph foundation — 2026-10-05

T2b is committed as `e4e6def`. T3a adds cluster-safe paragraph selection editing, preparation-only Android ICU/UAX14 break opportunities and a portable native greedy line builder over shaped clusters. The native builder supports all seven alignment choices, indents, hanging trailing spaces, word-space justification, explicit/empty paragraphs, first-paragraph space-before suppression and unclipped overflow. Oversized unbreakable sequences use emergency cluster breaks; an indivisible oversized cluster remains overflowing. This is a preparation foundation, not integrated box rendering: existing renderer guards remain active. No protected API, gesture or batch/draw changes.

The T3a gate passed native Debug/Release on both ABIs, Kotlin Debug/Release, both instrumentation source sets and all 208 JVM tests, zero failures/errors/skips (`tools/text/results/t3a-paragraph-foundation.log`). Host Release/selftest and six shaping references passed. Three new JVM tests cover selection endpoints, collapsed/all-text selection, empty paragraphs and atomic paragraph/box codec round trips. Host fixtures cover wrapping/hanging spaces, justification, physical alignment, indents, empty paragraphs, spacing, overflow and malformed coverage. Device-unverified. Asset/APK increment: zero assets; code delta unmeasured, no packaging.

T3b remains required: wire the ICU opportunities and styled cluster advances into native layout preparation; enumerate bounded wrapping/membership variants under animated layer size/tracking; prepare selectors/permutations for each; integrate paragraph positions, bounds/anchors/Fit/hit tests, the fixed Paragraph pages and box-width preview handle using the existing gesture protocol; add wrapping parity/performance instrumentation fixtures. The current 64-layout cap is not yet proven for animated wrapping. Do not claim T3 complete or relax the preparation/per-frame requirements. T4 and T5 follow T3; no device results are required to continue.

Exact T3a user-run tests: run the full compile/JVM tasks from `t3a-paragraph-foundation.log`, then `py -3 tools/text/build_host.py`, host `selftest`, and `shaping_parity.py`. There are no new T3 device controls/fixtures yet; the T2b/T1 lists remain valid. F1 performance/parity/lifecycle remain deferred and unrun.

## Current handoff: T2b font library complete in source

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

The original T1–T5 specification is preserved verbatim in [TEXT_COMPLETION_SPEC.md](../architecture/TEXT_COMPLETION_SPEC.md). The continuation authorizes gated sub-commits: T1a foundation, then T1b functional character formatting, followed by T2–T5 in order.

## T1c distance-budget follow-up — 2026-10-04

Faux bold reserves its 0.025 em outline expansion from the shared 0.10 em stroke/softness budget. Requested widths remain in the document; effective stroke is at most 0.15 em on faux-bold runs and blur uses the remainder. The Character spacing page reports clamped effective widths for the current selection. Identical-time evaluation reuses the prepared result. Synthetic small caps identify lowercase by Unicode category, including sharp-s whose simple uppercase mapping is unchanged.

The compile gate passed native Debug/Release on both ABIs, Kotlin Debug/Release, both instrumentation source sets and the full 197-test JVM suite (zero failures/errors/skips). Host Release/selftest, six shaping references and 1,443 checksum checks passed. Evidence: `tools/text/results/t1c-t2a-final.log`. No assets were added; actual APK code delta is unmeasured. Device verification remains outstanding. User checks: repeat T1b tests below, specifically faux bold plus thick stroke/blur at 8/72/500 px in both paint orders; selected-range clamp hints; requested-value persistence; synthetic small caps on sharp-s; and the rich cold/thermal and pre-encode parity fixtures. No protected API, gesture or draw contract changed.

## Current continuation handoff — T1 committed, T2 transport foundation

T1a is committed as `5772f10`; T1b as `df056c4`. The complete T1b compile/JVM/host gate passed. T2a adds a preparation-only binary transport for immutable styled layout/membership data and owned R8 SDF pixels, plus packet export/metric validation and a native renderer preparation entry point. The renderer can retain these results without parsing imported font bytes. This is infrastructure, **not completed T2 font support**: SAF import, isolated font inspection/shaping/rasterization, the private library, missing-font indicators, fallback, static variations/named instances and functional library/axis controls remain T2b work. T3/T4/T5 remain unimplemented. No approval or F1 device result is needed to continue the authorized stages.

T2a verification passed native Debug/Release for arm64-v8a and x86_64, Kotlin Debug/Release, app/core instrumentation-source compilation and the full 197-test JVM suite, with zero failures/errors/skips. Final log: `t1c-t2a-final.log`; instrumentation fixture log: `t2a-instrumentation.log`. The initial native gate rejected misleading indentation under `-Werror`; it was corrected. Host Release/selftest and six independent shaping references passed. Host transport fixtures prove exact layout/membership round trips, owned pixels and rejection of sampled truncated packets, unsupported versions, excessive font counts and nonfinite geometry. Portable font inspection validates all twelve bundled static faces and rejects invalid magic; metadata includes collection face identities and bounded variation axes/named instances. Inspection is not yet exposed to the app: T2b must provide isolated crash containment. The Android `preparedTransportPreservesRichMetrics` fixture is compiled but unrun. These checks do not prove imported-font crash containment or imported-font GPU parity.

Transport safety bounds: 32 MiB per packet, 16,384 physical glyphs (allowing substitutions to expand the 4,096 source units), 4,096 characters/clusters, 4,097 lines, 512 font identities and individual SDF dimensions no larger than a 2048² atlas page. Counts, arithmetic, finiteness, memberships, glyph/pixel identities, pixel lengths and trailing bytes are validated before renderer use. Rejection retains the document; no glyphs are dropped. This transport is ephemeral preparation data and does not change project v12, the 68-byte text vertex, 96-byte modifier, draw/batch contract, PreviewController or gesture protocol. T2b must bound aggregate preparation memory and isolate all untrusted font work; inspecting in a child and then reopening the font in the app would not satisfy the specification.

T2a adds no assets; its APK code delta is unmeasured (no packaging). T1 font assets remain approximately 1.86 MB compressed. All new functionality is source-implemented, device-unverified; F1 perf/parity/lifecycle remain deferred and unrun.

Exact T2a user-run list:

1. `py -3 tools/text/build_android.py --label t2a-user --task app:compileDebugKotlin --task app:compileReleaseKotlin --task app:compileDebugAndroidTestKotlin --task core-engine:compileDebugAndroidTestKotlin --task app:testDebugUnitTest --task core-engine:externalNativeBuildDebug --task core-engine:externalNativeBuildRelease`; expect the compile tasks and all 197 JVM tests to pass.
2. `py -3 tools/text/build_host.py`, then `tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest`; expect prepared transport and existing CPU assertions to pass. Run the six shaping references and 1,443 input checks as for T1a.
3. When performing user device checks, run `TextF2AcceptanceTest.preparedTransportPreservesRichMetrics`: both ligature modes must preserve exact rich metrics and reject unsupported packet versions. It is preparation-only; use the T1b rich parity/performance fixtures for the currently integrated renderer. Font import/variable-font acceptance is not ready until T2b.

The 64-layout bound currently covers held documents × ligature mode, including each document's width/direction fields. T3 must additionally resolve visual wrapping under animated layer size/tracking; the current combinatorial test does not prove bounds for those reflows. Do not silently freeze line membership or generate permutations on the evaluation path to avoid that work.

## T1a foundation gate — 2026-10-04

T1a is implemented in source, device-unverified. The continuation gate passed app Kotlin Debug/Release, instrumentation-source compilation and the full JVM suite (190 tests, zero failures/errors/skips; `tools/text/results/t1a-gate.log`). The earlier native Debug/Release checks for both ABIs, host Release/selftest, six shaping references and all 1,443 checksum checks cover the unchanged foundation sources. This commit contains the rich document/codec/validation, held-source editing preservation, bundled style faces/checksums/licenses and tests, plus explicit guards preventing unfinished rich rendering from degrading silently. T1b rendering and functional controls are next; no T2–T5 functionality is claimed.

Assumptions/limits and the relative-size deviation are recorded below and in the specification. Font assets add 3,646,029 bytes uncompressed; ZIP-deflate estimate 1,855,388 bytes. Actual APK delta remains unmeasured; no APK packaging or device execution occurred. PreviewController, gestures and batching/drawing are unchanged.

Exact T1a user-run checks (host/JVM; no new phone fixture yet):

1. `py -3 tools/text/build_android.py --label t1a-user --task :app:compileDebugKotlin --task :app:compileReleaseKotlin --task :app:compileDebugAndroidTestKotlin --task :app:testDebugUnitTest`; expect 190 tests, zero failures/errors/skips. `TextSourceDocumentTest` covers cluster-safe selections, inherited/merged runs, held document identity, rich codec round trips/unknown fonts and invalid models.
2. `py -3 tools/text/verify_inputs.py`; expect all 1,443 inputs to pass, including nine added font/OFL pairs.
3. `py -3 tools/text/build_host.py`, then `tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest`; expect all nine style faces to shape/rasterize without Regular fallback, plus the existing CPU checks.
4. `py -3 tools/text/shaping_parity.py --proof tools/text/results/host-build/Release/text-proof.exe`; expect all six shaping references to pass.
5. Existing F1 perf/pre-encode parity/lifecycle device runs remain deferred; T1b will supply the functional rich-text device list.

## T1b styled rendering and character editing — 2026-10-04

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

## Historical T1 foundation handoff — superseded by T1a/T1b above

F1 and F2 are user-accepted. The user's F1 performance, pre-encode parity and lifecycle runs remain deferred; they are not implementation gates. No agent device execution is authorized for this pass.

T1 is **incomplete and uncommitted**. Source Text now has an atomic held rich-document model and additive v12 codec: contiguous cluster-safe style runs (maximum 256), requested font references, run-relative attributes and manual kerning, paragraph records, and nonnegative box width. Legacy v10/v11 strings acquire a default run. Formatting survives string insertion/deletion and Source Text key freeze/reset; unknown requested font references are retained. The aggregate 1 MiB budget includes explicit rich-document metadata and the second on-wire string copy. Legacy preparation enforces a 64-layout limit (32 distinct source alternatives × two ligature modes), with an explicit error when exceeded. The rich-document/box/direction combination preparer is outstanding.

Nine real Bold/Italic/Bold Italic static faces for Inter, Noto Serif and JetBrains Mono have been added with per-face OFL files and pinned checksums in `TEXT_INPUTS.json`. The native catalog and host proof load all twelve bundled faces. Added assets: 3,646,029 bytes uncompressed; ZIP deflate-level-6 estimate: 1,855,388 bytes. Actual APK size delta is unmeasured because no APK was packaged.

Styled shaping, glyph geometry/paint integration, Character controls and the rest of T1 are outstanding. The renderer explicitly rejects rich formatting at preparation rather than silently showing an unformatted string. T2 font import/library/variations, T3 box/paragraph layout and controls, T4 animator gaps, and T5 Arabic/Hebrew direction are **not implemented** in this pass. Paragraph/box/font-axis schema reservations do not establish support. No T1–T5 gated commit has been created.

Preparatory verification: pinned-input checksum verification passed (1,443 inputs); the full JVM recheck passed 190/190 with zero failures/errors/skips. The initial 188/190 run exposed schema-generator nullable-accessor compatibility and an obsolete v12 future-version assertion; both were corrected. Host Release compilation and runtime selftest passed, including all nine added style faces; all six independent shaping references passed. Native Debug/Release compilation passed for arm64-v8a and x86_64; app/core Kotlin Debug/Release and instrumentation-source compilation passed. The final Debug/instrumentation compile recheck passed with the latest renderer guards. Logs: `tools/text/results/t1-model-recheck.log` and `t1-final-guards.log`. No APK packaging or device execution occurred. These checks do not establish complete T1 functionality.

Preparatory validation choices: size factors `(0,100]`; horizontal/vertical scales `[0,10000]%`; manual kerning `[-10000,10000]` thousandths em; font face indices `0..255`; named-instance indices `-1..65535`; at most 16 distinct four-ASCII-character axis tags; family/PostScript labels at most 128 UTF-16 units without NUL. Imported-font axes accept static tracks only in this checkpoint. Formatting selections expand to whole source-character clusters. Inserted text inherits the run at the caret (the last run at end-of-text); newly joined combining clusters inherit their initial character's style. Paragraph-record retention during string edits is provisional until T3; paragraph rendering currently fails explicitly.

No new user-run acceptance list is ready until the corresponding stage is implemented. Existing F1/F2 fixtures remain the applicable device list. Future acceptance must add selected-range rich-text edits and held keys; style-face/caps/small-caps/script/kerning/bounds parity; font import/deletion/missing-font/variable-axis cases; wrapping/justification/box gestures; deterministic wiggly and replacement-character parity; and Arabic/Hebrew logical-selector versus visual-layout parity, each with the existing allocation/counter/canary protocol.

## E1d implementation pass — 2026-10-01

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

## E1e instrumentation — implemented in source, unverified

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

## E2 implementation and approved array overflow

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

## E3 editor integration — implemented in source, device-unverified

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

## Historical E1a toolchain and packaging changes

- Both modules default to NDK r28c, `28.2.13676358`. CMake 3.22.1, C++17, `c++_shared`, arm64-v8a/x86_64, SDK levels and AGP 9.4.0 are retained.
- Build-tools is explicit at 37.0.0 (installed locally). `jniLibs.useLegacyPackaging = false` is explicit in the app. The source manifest has no conflicting `extractNativeLibs` declaration; AGP supplies it. The verifier requires **false in the compiled manifest**, rather than trusting the DSL.
- `fluxxengine` receives `-Wl,-z,max-page-size=16384` on the normal r28 path. This is intentionally explicit although r28 supplies the alignment default.
- Temporary Gradle property `fluxxNdkVersion=26.1.10909125` selects the r26 baseline in both modules, restores the original Maven Oboe and AndroidX graphics-path binaries, and omits the added engine linker flag. Remove the property for the r28 candidate. The APK records the selected NDK in `BuildConfig.NATIVE_NDK_VERSION`; this is configuration provenance, not a binary compiler attestation.
- E1a changed toolchain/packaging only; E1b/E1c CPU typography is recorded below. Text rendering, model and editor changes remain unimplemented.

The packaging checks follow [Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes). AGP is above 8.5.1 and build-tools above 35.0.0; versions alone are not acceptance evidence.

## Native dependency audit

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

### Page-size source audit

Searched native application code and Kotlin audio code for `sysconf(_SC_PAGESIZE)`, `getpagesize`, `mmap`, offsets/lengths, `4096`, `0x1000`, `MAP_FIXED`, `mprotect`, and `PAGE_SIZE`. No app-owned page-size dependencies were found. Vulkan allocations continue to use Vulkan memory requirements.

The current disk-backed `AudioMixer` uses `RandomAccessFile.seek/readFully/write`, **not `FileChannel.map`**. Its offsets describe PCM samples, not native memory mapping. No alignment workaround is needed. Revisit this audit if mapping is introduced later.

Vendored Oboe's `4096` in `AudioStreamAAudio.cpp` is an audio capacity in frames for the legacy FAST track, not a byte/page alignment. Its `aaudio.mmap_*` property names select AAudio policy, not direct application `mmap` calls. These were left unchanged.

The vendored graphics-path sources contain none of the searched page-size dependencies. SDK 37's `android.jar` contains the `FastNative` annotation used by the unchanged Kotlin sources; source compilation passed during E1b/E1c.

### Clang diagnostics

The [build checks and focused fixes](#build-checks-and-focused-fixes) below are the current diagnostics ledger. Fix genuine diagnostics individually; do not globally suppress warnings. Debug/release native compilation passed; this does not establish packaged/runtime acceptance.

## Read-only artifact verifier

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

## Fixed golden regression — user-run

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

## Runtime matrix and manual checklist

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

## Verification boundary

All 11 host artifact/golden-tool regression tests passed during E1b/E1c. These use synthetic fixtures, not the user's actual exported golden project. A16 E1a smoke is user-confirmed; the 16 KB environment and fixed golden comparison remain mandatory before E1d. Detailed CPU shaping/SDF evidence follows.

## Text E1b / E1c — CPU implementation and verification

Historical 2026-10-01 CPU pass: E1a render/playback/export smoke was **confirmed by the user on Galaxy A16**. E1b/E1c added CPU typography only; their pass did not add Vulkan text or editor integration. The earlier E1d implementation gate is superseded by the later authorization recorded above. A16 smoke still does not establish 16 KB compatibility or golden-project parity.

### Reproducible inputs

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

### Native ownership and shaping

The native `FontManager` owns copied font bytes, FreeType library/faces and HarfBuzz blob/face/font handles. Handles die before their backing bytes. JNI sessions lease a shared manager; in-flight calls retain ownership when another session closes. SDF/layout results are immutable leases and can outlive the manager. No GPU lifetime is introduced here.

Face operations and caches are protected by one mutex. Lock-wait time is measured. Worker-thread prewarming is required for later rendering; future E3 measurement must run off the main thread/debounce typing, since export can contend for this mutex. Font assets are available through each caller's AssetManager, including future thumbnail workers.

Shaping uses OpenType font functions, exact units-per-em scale, LTR/Latin/en and UTF-8 byte clusters. LF creates explicit lines; offsets in the flattened result retain global byte positions. Empty input has zero glyphs/lines; explicit blank lines survive. Missing font IDs resolve to `fluxx.sans`, without modifying the source document; missing codepoints retain glyph ID 0. Malformed UTF-8, NUL/CR and inputs over 16,384 UTF-8 bytes are rejected. These proof bounds do not establish the future editor's string limit.

Native output contains glyph IDs, advances, offsets, line advances and font ascent/descent/line gap in design units. A 64-entry layout cache avoids repeated shaping. The diagnostic JNI JSON API is **not** the future per-frame renderer submission API. E2 will keep layout handles keyed by layer/content revision and provide the shared geometry metrics interface.

### Independent shaping parity

Checked-in reference: `core-engine/src/androidTest/assets/text/shaping-reference.json`. It was generated by the official **hb-shape 14.3.1 Windows release executable**, independently of Fluxx's trimmed library. The file records tool/version/hash, font hashes, exact arguments and raw per-line output. Regeneration requires an explicit `--update-reference` argument; comparisons never rewrite expectations.

All six CPU host cases match glyph IDs, clusters, design-unit advances and X/Y offsets exactly:

- Noto Serif `office`: four glyphs, including the `ffi` ligature.
- Inter `AV`: kerning.
- Noto Serif `office\nAV gy`: explicit newline and global clusters.
- Inter `A` + U+10FFFF + `V`: missing-glyph preservation.
- Inter combining-mark fixture: nontrivial offsets.
- JetBrains Mono `AV office`: third bundled face.

Additional native self-tests passed for fallback, empty/blank lines, missing glyph, malformed UTF-8, pinned-cache eviction/lifetime and concurrent shaping/rasterization. Android `TextCpuProofTest` compares the same checked-in references through JNI and generates CPU diagnostics. Its sources compile; **instrumented tests have not been run on a device**.

### SDF policy, cache and capacity

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

### PNGs and artifact observations

Local output: `tools/text/results/sdf/` (**137 PNGs**) and `report.json`. This generated directory is ignored by Git and reproducible with the commands below.

- `fixture-atlas-1024.png` and `latin-atlas-1024.png` / `latin-atlas-2048.png`.
- `glyphs/`: individual raw SDF glyphs with overlap handling on/off.
- `samples/`: kerning, ligature, newline, missing-glyph, contour and monospace samples at each sample size.
- `synthetic-overlap-off.png` / `synthetic-overlap-on.png`: deliberately intersecting rectangles.

Actual synthetic-overlap result: property-off produced false internal distance edges (interior minimum **8**); overlap-aware output removed them (interior minimum **248**, center **255**; 752 changed texels). The full-interior probe passes with overlap handling enabled. FreeType's property setter takes `FT_Bool`, but its getter writes `FT_Int`; the wrapper respects that asymmetry.

Visual inspection of the overlap-aware 12/14/16px and 72px font samples found no obvious holes or contour corruption. Small SDF text has expected softness; 8px remains diagnostic, not a claim of acceptable editor minimum size. Inter's missing glyph intentionally appears as its font-specific boxed **NO GLYPH** symbol, rather than an unlabelled tofu box. The report records no rasterization errors. Phone rendering quality still needs later verification.

### Build checks and focused fixes

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

### Reproduction (CPU only; no device install)

```powershell
py -3 tools/text/verify_inputs.py
py -3 tools/text/build_host.py
py -3 tools/text/shaping_parity.py --proof tools/text/results/host-build/Release/text-proof.exe
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts selftest
./tools/text/results/host-build/Release/text-proof.exe core-engine/src/main/assets/fonts dump tools/text/results/sdf
```

The host script requires installed Visual Studio C++ Build Tools and SDK CMake 3.22.1. Optional compile-only Android checks: `py -3 tools/text/build_android.py` and `py -3 tools/text/build_android.py --release`. These never assemble/install APKs. Device diagnostics are explicitly user-run via `TextCpuProofTest`; output is `<test target external files>/text-e1bc/`, not the host directory.

### Remaining gates

- E1b/c: user review of CPU PNGs and Android instrumented parity/dump results.
- Before **E1d**: user's E1a 16 KB environment result and fixed r26/r28 golden-project video/audio comparison. If only an emulator runs, physical 16 KB AHardwareBuffer/MediaCodec behavior remains unverified.
- E1d/E1e: implemented in source and compiled; GPU quality, validation and heap/resource lifecycle acceptance remain user-run.
- E2/E3: v9, geometry, renderer integration, text creation and typography UI are implemented in source and compiled; device acceptance remains user-run.

### F2 implementation status (2026-10-04)

F1 is user-accepted; its performance harness, pre-encode parity and lifecycle checks remain unrun and deferred under the final instruction. F2a and F2b are implemented in source, device-unverified. F3 and UI improvements remain deferred. No PreviewController API or gesture-protocol changes, no new animator UI and no agent device runs.

V11 adds default-on hold-keyed Ligatures: effective on only when layer tracking evaluates to zero. Animator tracking does not split ligatures. Both source/font/mode layouts, shared membership maps and seeded permutations for every source alternative are prepared ahead of playback. V10 loading is retained permanently. Per-animator grouping, averaged cluster influence, lookup-table easing, signed spatial influence with clamped colour influence, round SDF joins and bounded SDF softness are documented deviations/semantics. Paint order stays per glyph; no additional paint scope or two-pass draw.

Detailed stage evidence and the exact user-run list are maintained in [the existing text animation plan](TEXT_ANIMATION_F1_F3_PLAN.md). No animator UI is added; data/edit actions exist for later F3 diamonds/controls.

F2b integrates native dual-mode HarfBuzz preparation, extended cached layout metadata, reusable Kotlin selector/spacing/pivot/modifier evaluation and per-glyph Vulkan attributes. Layer tracking alone selects the precomputed ligature mode; animator tracking remains cluster spacing. Source/font/mode are the only shaping-key inputs. Native preparation reserves CPU/GPU mesh storage and pins all source/mode handles; no shaping, SDF generation, atlas uploads or permutation construction occurs in prepared evaluation. Bounds use the same modifier evaluation, including baseline shift in post-spacing pivots. The exact production acceptance source is `TextF2AcceptanceTest`; compile/host checks are not device acceptance.
