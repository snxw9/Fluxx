# Text Animation: F1–F3 Plan

Status: F2a and F2b are implemented in source in two ordered commits, device-unverified. F1 is user-accepted; its performance harness, pre-encode parity and lifecycle checks remain unrun and deferred under the final instruction. F2a and F2b are implemented in source, device-unverified. F3 and UI improvements remain deferred. No PreviewController API or gesture-protocol changes, no new animator UI and no agent device runs.

The approved amendments take precedence over the original plan: requested stroke width is stored unchanged and clamped per evaluated font size; stroke half-width and F2 softness share a 0.10em distance budget; the host spread spike compares 48/8 with 64/12 without changing production defaults; centred strokes compose in both orders with translucent fill, round joins and padded bounds; the final v11 Ligatures rule supersedes F1 cluster-preservation behavior; RTL/complex scripts remain excluded; F2 may introduce additive v11 while permanently retaining v10 loading; native new and Kotlin worker allocation canaries/deltas measure 600 frames and repeat after three minutes load, with p99; and cluster/character/word/line metadata is prepared once with the layout for F2 reuse.

The user confirmed E1–E3 text behavior on device. Existing roadmap/SDD statements describing device acceptance as outstanding need reconciliation during F1, without claiming additional golden, performance or 16 KB evidence.

F1 ships v10. F2a writes additive v11 and permanently retains the v10 loader. F2a has no rendering integration: its animator data/evaluator are exercised through JVM fixtures until F2b; no animator UI is added.

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

Prepare cluster boundaries and line metadata once per source/font layout. Apply tracking between eligible character clusters, preserving combining marks; ligatures split when the final effective mode is off; spaces retain their advance. Reset accumulation at each line and avoid trailing tracking.

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

## F2 — Detailed animator engine plan

Status: F2a is model/codec/pure engine/edit actions, followed by F2b rendering after its commit. F1 performance results are no longer a gate. Per-glyph painting remains; no additional paint scope or two-pass path. PreviewController API and gesture protocol remain unchanged.

The formulas below define Fluxx behavior precisely; they do not claim numerical equivalence with AE's undocumented implementation. Adobe documents the selector controls but places Min/Max Amount under Wiggly selectors. Applying those controls to Fluxx range selectors is an explicit extension. Reference: [Adobe text animation](https://helpx.adobe.com/uk/after-effects/desktop/animating-text/text-animation/animating-text.html).

### 1. Selector math and membership

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

#### Square smoothness

Store s in [0,100]. At zero use the hard half-open interval. Otherwise set w=s/200 in member units:

```text
L = H(clamp((x-a+w)/(2w), 0, 1))
R = H(clamp((b+w-x)/(2w), 0, 1))
envelope = L × R
```

Each boundary feathers by up to half a member on either side. Narrow ranges have overlapping feathers and reduced peak coverage. Smoothness affects Square only; other shapes retain its stored value without applying it.

#### Ease Low/High

Both range from -100 to 100. Apply them to profile values, or to Square's envelope:

```text
p = 2^(easeLow/100)
q = 2^(easeHigh/100)
E(t) = t^p / (t^p + (1-t)^q)
E(0)=0; E(1)=1
```

Positive Ease Low softens departure from zero; positive Ease High softens arrival at one. Zero/zero is identity.

#### Amount and Min/Max Amount

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

#### Ligatures

Final rule: layer Ligatures is a default-on hold boolean persisted in v11; v10 defaults on. Effective mode is Ligatures on AND evaluated layer tracking exactly zero. Nonzero layer tracking splits ligatures; animated zero crossings pop by design. Prepare both source/font/ligature-mode layouts and all maps before playback. The cache key includes only those three inputs. Animator tracking and line anchor never affect shaping (an explicit assumption).

Evaluate and combine selectors on source members first, then project onto clusters. A cluster spanning multiple source characters receives their arithmetic mean. Selecting one character of a three-character ligature gives one-third influence without breaking the ligature. All glyphs belonging to the cluster receive the same influence; combining marks never separate from their base.

### 2. Deterministic randomized order

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

### 3. Animator properties and pivots

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

### 4. Blur and shared SDF budget

Keep production 48ppem/8px spread:

```text
requestedStroke = max(0, baseStroke + accumulatedStrokeDelta)
requestedBlur   = max(0, accumulatedBlurDelta)
effectiveStroke = min(requestedStroke, 0.20×fontSize)
effectiveBlur   = min(requestedBlur, max(0, 0.10×fontSize − effectiveStroke/2))
```

Use evaluated font size before per-character affine scale. Glyph scale scales stroke/softness visually; it does not rasterize or recover extra SDF detail.

Retain F1 AA and expand effective ink bounds accordingly. Inspector contracts expose requested/effective values, reason and effective range across affected glyphs; F3 provides full animator presentation. Blur is SDF edge softness, not Gaussian blur of the glyph interior.

### 5. Stroke order and overlapping glyphs

Confirmed F1: one indexed draw, fill/stroke compositing per glyph in the fragment shader. It does not draw all layer strokes before all fills.

Add F1 acceptance: thick contrasting stroke, translucent fill and negative tracking in both paint orders; AV, Noto Serif office, rotated text and animated overlap.

Final decision: retain per-glyph composition in both orders, including overlap behavior. Do not add an additional paint scope or two-pass rendering. Per-glyph fill/stroke/softness/opacity must be attributes or indexed GPU data, not layer push constants.

### 6. Evaluation, storage and performance

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

### 7. Additive v11 schema and validation

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

### 8. Editing and persistence

Address properties by (layerId, animatorId, selectorId?, propertyId), not list indices. Extend existing backend edit actions, auto-keying, diamond-state/key-time data, navigation, freeze/reset and timeline aggregation. `aggregateKeyTimes()` supplies the complete anchor-relative union; bulk `MoveKeyframe` moves animator keys atomically with layer keys. F3 exposes animator-specific controls and their diamonds; no new UI controls or timeline gesture protocol are introduced here. Group/selector creation, removal and reordering each form one undo transaction.

Copy/paste and duplicate preserve seeds, values and order, assigning new object IDs. Trim/extend retain established keyframe origins. Split/clipboard rebasing shifts all numeric/discrete tracks with checked signed arithmetic; seeds/permutations do not change with time rebasing.

Test empty/disabled groups, reordering and inactive tracks. F2 may use a debug fixture builder for acceptance; full Add Animator picker and preview handles remain F3.

### 9. Parity and unsupported features

Preview/export/thumbnails share prepared memberships, permutation algorithm, track samples, matrices, clamping and shaders. Compare equal-resolution RGBA before encoding at identical times/generations, including random access, reverse scrubbing, source changes and thermal windows. Proxy previews use identical composition-space parameters; unequal resolutions need not have identical bytes.

Wiggly selectors, per-character 3D, text on path and expressions remain unsupported. Do not ignore/flatten them. Reject documents requiring unsupported kinds with actionable messages; never save a degraded interpretation over the original.

### 10. Risks, deviations and exact user-run list

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

### F2a implementation and verification

Commit `c059f85` implements additive v11 typed tables/model/codec/validation, permanent v10 loading, pure selector/ease/mode/projection/permutation evaluation and backend edit/key/reorder/undo/copy/split integration. App debug Kotlin and 174 JVM tests passed, including 17 animator tests; zero failures/errors/skips. Existing host selftest passed. Log: `tools/text/results/f2a-final-decisions.log`. F2a did not change renderer, mesh, shaders or JNI.

The ease table is 21×21×257 doubles (about 0.91 MB) with exact zero/zero identity and endpoint paths. Sampled regression error is below 0.017; this is an approximation regression, not a formal global bound or an AE equivalence guarantee.

### F2b implementation and verification

F2b prepares both ligature modes and all source alternatives once, extends the native F1 layout metadata and emits reusable 96-byte direct cluster modifier records into 68-byte glyph vertices. The existing indexed mesh/draw order remains. All per-glyph paint values are attributes; only layer transform/order/opacity remain shared. Both layouts and maps remain pinned through playback. Coverage caches, four variant permutations and disabled execution use the F2a engine. All transform, colour, spacing, pivot, budget and shader logic is shared by preview/export/thumbnail. No PreviewController API or gesture protocol changed. Existing geometry consumers gained ligature-mode selection only; there are no new controls or flows.

Compile evidence: debug/release native arm64-v8a and x86_64, app/core Kotlin and both instrumentation source sets passed (`tools/text/results/f2b-native-instrumentation.log`). Final Kotlin/instrumentation/JVM results are in `tools/text/results/f2b-final-backend.log` and `f2b-final-tests.log`; native finite-geometry verification is in `f2b-complete.log`. Host Release build and selftest passed, including 600 cached dual-mode switches, combining marks, metadata layout and both 4096-glyph modes; all six independent shaping-reference cases passed. All 185 JVM tests passed, zero failures/errors/skips, including 18 animator tests, eight prepared-mesh/evaluation tests and the updated F1 spacing/bounds regression. Host prepared evaluation reported zero allocated bytes over its measured 600-frame window after 10,000 warm frames; this is a host JVM result only.

The evaluator has a host JVM allocation regression covering every property family, animated selector/ease and random membership, with 10,000 warm frames and a 600-frame zero-byte assertion. This does not replace ART and native device proof. Generic mixed-media scheduling retains its existing allocations outside the prepared animator evaluator. Device 60fps, pixel parity and native/ART allocation measurements remain unrun; no throughput claim or lowered limit is made.

### Exact F2 user-run acceptance list (priority order)

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

## F3 — Animator UI outline

Use an Add Animator picker and fixed inspector pages for properties and selectors, with paging or drill-in where needed and no scrolling sheet.

Provide selector controls, property diamonds and range handles over the preview. Handle dragging edits the same selector tracks as numeric controls and follows existing gesture/undo transactions. Wrapped lines and overlapping handles need explicit interaction behavior; RTL remains out of scope.

F3 gets its own detailed design and approval after F2 acceptance.

## Schema impact

The reserved vector has been extended additively in [`project.fbs`](../../app/src/main/fbs/project.fbs). Version-2 animator/selector/override tables now carry all listed fields; v11 adds layer Ligatures. The singular selector slot remains reserved for compatibility and populated singular data is rejected. No paint-scope field was added.

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
11. **F2 source/device acceptance:** selector boundaries and every supported shape/unit/mode; randomized seek/reopen parity; overlapping animators; all property tracks; 500+ glyph performance with declared animator/selector counts.
12. **F3 gate later:** picker, selector pages, preview handles, cancellation, key navigation and undo/redo on compact phone layouts.

No A16 performance result is claimed yet. F2a/F2b are implemented in source with compile/JVM/host verification; F3 remains deferred.

## Deferred scope

Per-character 3D, text on path, expressions and wiggly selectors remain out of scope.
