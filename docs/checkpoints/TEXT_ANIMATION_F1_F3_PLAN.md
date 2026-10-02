# Text Animation: F1–F3 Plan

Status: F1 approved with eight amendments and implemented in source; compile/JVM and host evidence are tracked in [TEXT_F1_IMPLEMENTATION.md](TEXT_F1_IMPLEMENTATION.md). F1 device acceptance remains user-run. F2 and F3 remain proposed and separately gated.

The approved amendments take precedence over the original plan: requested stroke width is stored unchanged and clamped per evaluated font size; stroke half-width and F2 softness share a 0.10em distance budget; the host spread spike compares 48/8 with 64/12 without changing production defaults; centred strokes compose in both orders with translucent fill, round joins and padded bounds; tracking always preserves cluster ligatures; RTL/complex scripts remain excluded; F2 may introduce additive v11 while permanently retaining v10 loading; native new and Kotlin worker allocation canaries/deltas measure 600 frames and repeat after three minutes load, with p99; and cluster/character/word/line metadata is prepared once with the layout for F2 reuse.

The user confirmed E1–E3 text behavior on device. Existing roadmap/SDD statements describing device acceptance as outstanding need reconciliation during F1, without claiming additional golden, performance or 16 KB evidence.

F1 is an additive extension with a **v10 schema bump**. F2 may use v11 additively; the v10 loader must remain permanently.

## F1 — Text property completion

### 1. Extend the existing property model

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

### 2. Complete property integration

Extend property IDs, text actions, colour-action targeting, key-time discovery, contextual navigation, diamonds, auto-keying, reset and animation toggling.

Carry all new tracks through copy/paste, duplicate, trim/extend/split, signed key rebasing, undo/redo, save/load and autosave. Continuous edits retain the existing gesture transaction boundary and produce one undo entry.

Keep `PreviewController` and the gesture protocol unchanged.

### 3. Apply spacing in mesh construction

Preserve cached HarfBuzz layouts and glyph SDFs. Tracking, leading and baseline shift must not enter the shaping-cache key.

Prepare cluster boundaries and line metadata once per source/font layout. Apply tracking between eligible character clusters, preserving ligatures and combining marks; spaces retain their advance. Reset accumulation at each line and avoid trailing tracking.

Recompute adjusted line advances before alignment, so centre/right alignment uses the new spacing. Apply leading to line baselines and baseline shift to glyph positions.

Update shared text bounds alongside mesh geometry so preview selection, pivots and hit testing remain consistent.

### 4. Extend the SDF shader

Derive stroke coverage by offsetting the existing distance threshold. Composite fill and stroke coverage in the selected order, with correct alpha handling, within the existing indexed layer mesh.

Prepare shader inputs for SDF softness, which F2 can use for per-glyph blur. Softness is a bounded blur-like effect, not a full Gaussian blur.

The current SDF uses an eight-texel spread at 48 ppem. Store requested stroke width unchanged, clamp its evaluated full width to 0.20 × evaluated font size, and show clamping in the inspector. Half-stroke plus future F2 softness shares a 0.10em budget. Run the approved 48/8 versus 64/12 host spread/capacity spike before finalization; production spread changes require approval. See the implementation checkpoint for exact centred-stroke compositing and padding.

### 5. Prepare and measure the hot path

The current mesh builder appends to vectors and performs glyph-placement lookups using string-bearing keys. Replace frame-time lookup with prepared glyph references and reserve CPU/GPU capacity before animation begins.

For a prepared layout, F1 costs:

- Track evaluation once per layer.
- Linear spacing, alignment and bounds work over glyphs/lines.
- Four vertices and six indices per visible glyph.
- Mesh upload and the existing text draw.

Target zero allocations, shaping calls, rasterizations and atlas uploads during warmed property-only animation. Source/font changes remain preparation events; animated Source Text requires preparation of its keyed layouts before measured playback.

## F2 — Animator engine outline

Use the existing `text_animators` vector and extend its placeholder tables with stable animator/property identities, typed tracks and selector data.

Selectors include start, end, offset, units, shape, ease high/low, combination mode, amount and randomized order. Support characters, characters excluding spaces, words and lines; square, ramp up/down, triangle, round and smooth shapes. Specify formulas and boundary behavior for add, subtract, intersect and any further supported modes before coding.

Persist a deterministic seed and define the permutation algorithm. Regenerate permutations during preparation when membership changes; never draw fresh random values per frame.

Animator properties: position, scale, rotation, opacity, skew, anchor, fill colour, stroke colour, stroke width, tracking amount and blur. Every property and selector numeric field—including start/end/offset—uses the existing keyframe framework; discrete choices use hold evaluation.

### Required evaluation order

1. Evaluate animator and selector tracks.
2. Compute selector coverage using cached character/word/line membership.
3. Combine selectors and resolve per-glyph modifiers.
4. Accumulate selected tracking and any line-anchor spacing contributions.
5. Compute adjusted line advances and alignment.
6. Apply glyph anchor, affine transform, colour, opacity and SDF parameters.
7. Emit the mesh and matching bounds.

Coverage must precede accumulated positions. A glyph ordinal cannot be treated as a Unicode character index: ligatures, Latin combining marks and whitespace need the shared cluster-based mapping. RTL/complex scripts remain out of scope.

Expected warmed cost is O(glyphs × selectors + glyphs × animator properties), using reusable coverage/modifier storage. Preview, export and thumbnails share the same evaluator, seed, time convention and geometry path.

No re-shaping, SDF re-rasterization or hot-path allocation during animator evaluation.

## F3 — Animator UI outline

Use an Add Animator picker and fixed inspector pages for properties and selectors, with paging or drill-in where needed and no scrolling sheet.

Provide selector controls, property diamonds and range handles over the preview. Handle dragging edits the same selector tracks as numeric controls and follows existing gesture/undo transactions. Wrapped lines and overlapping handles need explicit interaction behavior; RTL remains out of scope.

F3 gets its own detailed design and approval after F2 acceptance.

## Schema impact

The inspected [`project.fbs`](../../app/src/main/fbs/project.fbs) reserves the vector, but its selector and override tables cannot yet store the requested behavior. `TextRangeSelector` currently contains only a version field; `TextPropertyOverride` contains version and property ID.

- F1: write v10; append typed text fields, retain old field positions and supply identity defaults when reading v9 or older documents.
- F2: retain the reserved vector and extend its tables. F1 does not wait on F2 table design. F2 may bump additively to v11; permanently retain the v10 loader.
- Verify Kotlin/native codecs, malformed-value validation and unsupported-version behavior together.

## Principal risks

- SDF spread limits wide strokes and softness.
- Character-to-glyph mapping affects tracking and selectors in complex scripts.
- Animated spacing/stroke can expose bounds and pivot inconsistencies.
- Heap stability alone does not prove zero allocation; explicit hot-path instrumentation is needed.
- Encoded MP4 pixels cannot be expected to equal compositor pixels exactly. Require identical evaluated geometry/render output before encoding, then assess codec output separately.

## Exact user-run acceptance list

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
11. **F2 gate later:** selector boundaries and every supported shape/unit/mode; randomized seek/reopen parity; overlapping animators; all property tracks; 500+ glyph performance with declared animator/selector counts.
12. **F3 gate later:** picker, selector pages, preview handles, cancellation, key navigation and undo/redo on compact phone layouts.

No A16 performance result is claimed yet. F1 implementation is authorized; F2 and F3 remain separately gated.

## Deferred scope

Per-character 3D, text on path, expressions and wiggly selectors remain out of scope.
