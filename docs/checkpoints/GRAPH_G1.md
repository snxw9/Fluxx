<a id="rec-graph-g1"></a>

# GRAPH G1: curve backend + Ease view (Graph Editor, pass 1 of 4)

Branch `graph-g1`, built on `master` at `d0d27f8` (TEXT F3.4 merged; the brief named `67df5f3`, which `d0d27f8` contains). Not committed: the changes are in the working tree of `graph-g1`. One pass, organised by the sections of the brief.

Status:
- Implemented in source.
- JVM: **518 tests** (`:app:testDebugUnitTest`; 481 before this pass, all unchanged, plus 37 new). Two full runs passed 518/518. A third full run failed only `PreparedTextAnimationTest.hostPreparedEvaluationAllocatesZeroAfterWarmup` (112 bytes): the host-JVM allocation proof already recorded as intermittent in the [text record](TEXT.md) (a HotSpot deoptimisation in the measured window). It passed every isolated rerun (§10).
- **A16 (SM-A165F), instrumented, targeted:** `GraphPageLayoutTest` (3) and `GraphCurveRenderTest` (1) pass; `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest` and `TextAnimateLayoutTest` pass (12). Crash buffer empty. Warm-up and F2 suites not run.
- Phone acceptance: not done (list in §11).

Hard rules kept:
- No change to `PreviewController`, the gesture protocol, the batch/draw contract or preparation (`PreparedTextAnimation`'s constructor and every `prepare*` path are untouched).
- Changes limited to the allowed areas: the schema (additive v13), the keyframe evaluator, and one additive `EditorAction` (`SetKeyEasing`). `EditorViewModel.transientAllowed` lists the new action, so a Graph drag can preview through the existing begin / preview / commit / cancel; the protocol itself is unchanged.
- Evaluation-time clamps were added at consumers (§3), as the brief asks. They are identity for every in-range value: the 481 existing tests, including the text golden hashes, pass unchanged.
- Icon-first and monochrome, one icon family. No −/+ steppers, no Done/✓. Sheet heights are unchanged (268dp / 328dp). Popovers stay non-modal.

## 1. What changed, in brief

| Area | Change |
|---|---|
| Model | `EasingPreset` gains `HOLD`, `CUSTOM`, `BOUNCE`, `ELASTIC`. `Keyframe1D`, `Keyframe2D` and `ColourKeyframe` gain `curveA..curveD`. New `EasingCurve` value type, `EasingLimits` (ranges, defaults, validation, decoder sanitising) and `EasingMath` (bezier solve, bounce, elastic) in `model/KeyEasing.kt`. |
| Schema | v13: `EasingType` values 2–5, four `curve_*` floats on the three key tables. `PROJECT_VERSION = 13`. |
| Evaluator | `KeyframeEvaluator.ease(...)`; Linear / Easy Ease keep their fast path and LUT. |
| Action | `EditorAction.SetKeyEasing` and its reducer (`editor/KeyEasingActions.kt`). |
| Pure Graph model | `GraphTarget`, `GraphModel` (enable rule, segment rule, toggle key), `EasePresets`, `EaseChoice`, `EaseMath`, `CubicBezierText` (`editor/EaseTools.kt`). |
| UI | `InspectorSection.GRAPH`, the Graph page (`ui/inspector/GraphPage.kt`), the rail Graph icon enabled from each rail's focused property, 17 new family icons. |

## 2. Data model and schema (v13)

**Before (verified in code):** `enum EasingPreset { EASY_EASE, LINEAR }` on `Keyframe1D`, `Keyframe2D` and `ColourKeyframe`; the departing key owns the easing; wire `LINEAR = 0, EASY_EASE = 1`; `PROJECT_VERSION = 12`. Easy Ease was already the bezier (0.33, 0, 0.67, 1) baked into a 1024-entry LUT.

**Kotlin.** `enum EasingPreset { EASY_EASE, LINEAR, HOLD, CUSTOM, BOUNCE, ELASTIC }`. Wire values are mapped explicitly in `EasingWire` (never by ordinal): LINEAR 0, EASY_EASE 1, HOLD 2, CUSTOM 3, BOUNCE 4, ELASTIC 5.

Each key class gains `curveA, curveB, curveC, curveD`:

| Type | a | b | c | d | Ranges |
|---|---|---|---|---|---|
| CUSTOM | x1 | y1 | x2 | y2 | x ∈ [0, 1], y ∈ [−2, 3] |
| BOUNCE | bounce count | restitution | – | – | count 1–8 (rounded), restitution 0.1–0.9 |
| ELASTIC | oscillations | damping | – | – | oscillations 1–10, damping 0.1–1 |
| LINEAR, EASY_EASE, HOLD | ignored (stored, round-trip unchanged) | | | | finite |

- The defaults depend on the type: a key built with only a type gets that type's defaults (`Keyframe1D(t, v, BOUNCE)` is a 3-bounce, 0.5 key). Every existing constructor call still compiles, and existing keys equal what they were.
- Validation sits in each `init` (`EasingLimits.validate`): all four finite, and the type's ranges.
- Data-class equality includes the floats. `TrackMemo` interns by that equality: equal curves share a slot, different curves do not (`trackMemoSharesEqualCurvesAndSeparatesDifferentOnes`).
- Each class has `curve` (an `EasingCurve`) and `withCurve(c)`.

**Schema diff (`project.fbs`):**
```
enum EasingType : byte { LINEAR = 0, EASY_EASE = 1, HOLD = 2, CUSTOM = 3, BOUNCE = 4, ELASTIC = 5 }
table Keyframe1D     { …; easing: EasingType = EASY_EASE; curve_a: float = 0.33; curve_b: float = 0; curve_c: float = 0.67; curve_d: float = 1; }
table Keyframe2D     { …; easing: EasingType = EASY_EASE; curve_a … curve_d (same defaults) }
table ColourKeyframe { …; easing: EasingType = EASY_EASE; curve_a … curve_d (same defaults) }
```
The Kotlin schema was regenerated with `tools/text/generate_schema.py`. Only `EasingType.kt`, `Keyframe1D.kt`, `Keyframe2D.kt` and `ColourKeyframe.kt` changed.

**Wire cost.** The FlatBuffers builder compares a float field against a double default (0.33f ≠ 0.33), so it would always write 0.33 and 0.67. `EasingWire` therefore writes each curve field only when it differs from its default as a Float. Keys that do not use the floats cost nothing. A project with only Linear / Easy Ease keys produces the same bytes as before apart from the version (`v12FixturesReadUnchanged` relabels such bytes as v12 and decodes the same document).

**Reading.** v1–v12 stay permanently readable; absent curve fields use the defaults. The version number is the only gate.

| Input | Behaviour |
|---|---|
| v1–v12, transform tracks, any easing byte | As before: 0 = Linear, any other byte = Easy Ease (the historic leniency, kept). |
| v1–v12, text / animator tracks | As before: only 0/1; anything else is refused. |
| v13, easing byte 0–5 | The six types. |
| v13, any other easing byte | Corrupt data, refused (`IllegalArgumentException`), like the text decoder's unknown bytes. The load fails with the existing "could not open" path; never a crash. |
| Curve floats, non-finite | Refused, as `TextLimits.decodeSize` refuses a non-finite size. |
| Curve floats, finite but out of range | Clamped into the type's range (`EasingLimits.sanitize`). |

**Every key copy carries the curve.** I grepped every construction of the three key classes and every `easing =`. Copies via `.copy(timeUs = …)` (rebase, split, Key MOVE, animator MOVE, moveAll, `TextProperties.rebase`, `TextAnimator.rebase`), whole-track copies (duplicate, copy/paste layer, explode's transform and paint tracks) and the codecs keep the curve by construction or through `EasingWire`.

The paths that rebuilt a key from its value and easing did reset a curve. They now go through `rekey1D` / `rekey2D` / `rekeyColour`: same type, keep the existing key's curve; new key or changed type, the type's defaults. The paths:
- `EditorReducer` `SetKeyframe` / `SetKeyframe2D`
- `TextPropertyActions` (scalar and colour)
- `TextAnimatorActions.editKey` (scalar, vector, colour)
- `TextEdits.size` / `TextEdits.scalar`
- `textColourPayload`

Regression tests: `rebaseSplitDuplicatePasteAndEncodeCarryTheCurve`, `valueEditsAndKeyMovesKeepAnExistingCurve`, `explodeCarriesTransformAndPaintCurves`.

**Insertion behaviour (found, unchanged).** A newly inserted key gets **Easy Ease** with its default floats:
- the diamond's first key (`ToggleAnimated`) and its add-key (`SetKeyframe` / `SetKeyframe2D` / `SetColourKeyframe` with the default easing)
- auto-key from a value edit with no key at the playhead (`editTransform`, `TextEdits`)
- animator ENABLE / SET (`existingEasing` falls back to Easy Ease)

An existing key keeps its type on a value edit. Inserting a key inside a Custom segment leaves the departing key's curve in place, now spanning the shorter first half (re-normalised to that half). The new key starts an Easy Ease second half. The last key's easing is stored but unused.

**The rail ⋮ easing items** (Edit Text and Animate) still list exactly Easy Ease and Linear (`RAIL_EASINGS`); the larger enum does not leak into them. Choosing one on a Custom key resets the parameters to that type's defaults.

## 3. Evaluator

- **Linear / Easy Ease:** `easeProgress` is unchanged. Keyed evaluation branches on the easing first and calls it directly, so their output is bit-for-bit identical (`linearAndEasyEaseKeepTheirFastPathBitForBit`) and there is no extra per-frame work beyond one enum comparison.
- **Hold:** progress < 1 gives 0, so the value is exactly the departing key's until the next key's time. At that time the search selects the next segment, so the value is the next key's. Holds on 1D, 2D and colour.
- **Custom:** `EasingMath.bezier` in double precision. It solves x(s) = progress with Newton–Raphson (up to 8 steps), then falls back to bisection (60 steps) if Newton leaves [0, 1] or does not converge, which covers extreme handles. It returns y(s), unclamped. Accuracy: within 5e-4 of an independent 80-step double bisection across 408 curves (random, plus x1 = x2 = 0, x1 = x2 = 1, crossed handles and y at both limits), 401 samples each.
- **Thread safety and allocation:** closed form, primitives only, no shared mutable state, no cache, no LUT built in `render()`. UI and render threads can both evaluate. `TrackMemo` still memoises per frame time.
- **Colour:** the Custom / Bounce / Elastic progress is clamped to [0, 1] before `interpolateColour`, so channels never overshoot; the curve still drives the timing.

**Bounce (ease-out).** A ball dropped from 0 onto the target 1, with velocity restitution e and n bounces:
- Total time D = 1 + 2·Σₖ₌₁ⁿ eᵏ, and τ = progress·D.
- First fall, τ ≤ 1: p = τ².
- Bounce k lasts 2eᵏ from startₖ: p = 1 − e²ᵏ·(1 − u²), with u = (τ − startₖ − eᵏ)/eᵏ ∈ [−1, 1].

Each arc starts and ends on the target, so p is continuous; p ≤ 1 always; p(0) = 0; p ≥ 1 returns exactly 1f.

**Elastic (ease-out).** p = 1 − (1 − t)·e^(−λt)·cos(2π·n·t), with λ = 10·damping and n = oscillations.
- p(0) = 0. The envelope (1 − t)·e^(−λt) is exactly 0 at t = 1, so p(1) = 1 (and progress ≥ 1 returns exactly 1f).
- p overshoots and undershoots 1 within [0, 2), with |p − 1| ≤ 1 − t.

Tests: `bounceAndElasticEndpointsContinuityDeterminism` checks exact endpoints, continuity over 20,000 steps, determinism (raw bits), bounce never above 1 and the elastic envelope.

**Overshoot consumers, clamped at the consumer (identity in range):**

| Consumer | Clamp |
|---|---|
| Layer opacity | Already `[0, 1]` (unchanged). |
| Text size (render, tier selection, `TextLayout`, preview overlays, `PreparedTextAnimation` evaluate stage, `TextExplode` freeze) | `TextProperties.sizeAt` / `TextLimits.clampSize`: 8–1000. |
| Text stroke width (render, tier distance, `effectiveStrokeWidth`, evaluate stage, explode freeze, the stroke note) | `strokeWidthAt`: ≥ 0. |
| Stroke-over-fill, ligatures | Already hold (`hold()` ignores easing). |
| Tracking, leading, baseline | Unbounded finite; no clamp needed. |
| Animator percent properties (Opacity, Line Anchor, Fill / Stroke Opacity) | `AnimatorLimits.clamp`: 0–100. |
| Selector Smoothness, Correlation | 0–100. |
| Selector Ease High / Low, Amount, Min / Max Amount | −100–100. |
| Wiggles/sec | 0–1000. |
| Selector start / end / offset / phases, animator offsets / scale / rotation etc. | Unbounded finite; no clamp needed. |
| Discrete selector fields, enabled, grouping, seed | Hold; unaffected. |
| **Edit-time sampling** (a sampled value written back into a validated key or static: diamond add-key, Key SET without a value, Freeze, disable animation) | `scalarValue` clamps size / stroke width; `TextAnimatorEdits.clamp` clamps animator fields. Before this, an overshoot sample would have failed validation in the reducer. |

`GraphG1OvershootTest` drives three overshooting curves (bezier y ∈ [−2, 3], back-out, elastic) through every text property, every selector field (Range and Wiggly), the first 23 animator properties and the transform. It asserts that every evaluated consumer value passes the same validation as stored data, that the per-frame text evaluation never throws and writes only finite records, and that every sampling edit path succeeds.

The 30-animated-layer case: the Linear / Easy Ease path adds one enum comparison; the selector clamps are a few comparisons per selector per frame (not per glyph). Not re-measured on device (see §10).

## 4. `EditorAction.SetKeyEasing` (additive)

`SetKeyEasing(id, property: AnimPropertyType? = null, animator: AnimatorTrackAddress? = null, keyTimes: List<Long>? = null, curve: EasingCurve)`.

- **Addressing:** exactly the existing keyframe addressing. `AnimPropertyType` covers transform Position / Scale / Rotation / Opacity and the text-layer tracks (size, tracking, leading, baseline, stroke width, fill and stroke colour). `AnimatorTrackAddress` covers animator properties (scalar, vector, colour), selector fields, enabled and grouping. Exactly one is set. `GraphTarget` in the UI only carries one of the same two addresses.
- **Keys:** `keyTimes` are local key times. `null` means every segment: every key except the last, whose easing is unused. Missing times are ignored. Keys outside the clip range stay stored and hidden.
- **Position / Scale:** one curve for X and Y (no separate dimensions).
- **Undo:** one dispatch is one undo entry; a Graph drag is begin / preview / commit (one entry), and cancel discards. Tested: `setKeyEasingIsOneUndoEntryWithRedo`, `gestureBeginPreviewCommitIsOneUndoAndCancelDiscards`, `applyToAllWritesEverySegmentAndKeepsHiddenKeysStored`, `everyPropertyAddressIsReachable` (17 addresses).
- Source Text and the random seed have no easing; addressing them throws, the same as other invalid actions, and no UI can send it.

## 5. UI: the Graph page (Ease view)

**Entry.** Every rail with the diamond passes its focused property to `InspectorUtilityRail(graphTarget = …)`:
- Transform: the current mode (none in Anchor mode).
- Blending & Opacity: Opacity.
- Edit Text pages: `TextFocus.owner`, layer scope only.
- Animate tab: the focused animator field.

The Graph icon is **enabled** when that property is continuous and animated with at least two keys (`GraphModel.enabled`). Otherwise it is visible and disabled, including for Source Text, stroke-over-fill, ligatures, hold selector fields and the seed. Tapping it opens `InspectorSection.GRAPH` in the same sheet; on the Graph page the icon is selected and tapping it returns. The sheet provides a small `GraphHost` (CompositionLocal) that rails read; the rail's layout is unchanged.

**Returning.** The page it came from comes back as it was (Edit Text tab and focused control, Animate focus, Transform mode). The section content sits in a `SaveableStateHolder` and the Transform mode / link toggles are now `rememberSaveable`. Any other navigation forgets that state, as before.

**Non-modal.** The preview, timeline, transport, rail and diamond stay live. While the page is open, the timeline focus and Prev/Next follow the edited property. Back (and the editor's top-left arrow, when opened from Edit Text) returns to the previous page; an open numeric popover takes Back first. The sheet's swipe-down closes the sheet from the Graph page too (`InspectorSwipe.enabled` lists `GRAPH`). If the property loses its keys, or its animator disappears, the page returns by itself. Deselecting the layer closes the sheet.

**Layout (`GraphLayout`, JVM tested at 360 / 384 / 411dp × 268 / 328 × font scale 1 / 1.3, and on the A16).** The page does not scroll; only the preset strip scrolls sideways. The rail (diamond, Graph, ⋮) sits on the left, and the rest stacks top to bottom:

| Region | Contents |
|---|---|
| Header, 36dp × font scale | Value \| Speed \| Ease mode icons (Value and Speed visible, disabled and inert), then "Key 2 to 3 · 2.00s" and "90° → 45°". |
| Body | A square Ease canvas beside a grid of 8 type tiles and 7 tool tiles. Tiles shrink 40 → 36 → 32dp to fit the height. On the A16 the canvas is ≥ 150dp at 268 and ≥ 180dp at 328. |
| Preset strip, 40dp | The built-in presets, as curve glyphs. |

**Edited segment.** The segment rule, `GraphModel.segmentIndex` (tested):

| Playhead | Segment |
|---|---|
| Strictly between keys | That segment. |
| On key k | k → k+1. |
| On the last key | The previous segment. |
| Before the first key | The first segment. |
| After the last key | The last segment. |

Transform pages use the raw local playhead and text / animator pages the frame-snapped one, like their own diamonds. The header label states the segment.

**Canvas.**
- X is 0–100% of the segment's time, Y 0–100% of the way from start to end value. Grid every 0.1, with 0 and 1 emphasised.
- The Y range is [−0.25, 1.25], grown to fit the curve's extremes and handles (and frozen-then-grown during a drag, so the mapping does not move under the finger).
- Every type is drawn: Hold as a step, beziers parametrically, Bounce / Elastic by adaptive subdivision. The curve layer is cached (`drawWithCache`) and rebuilt only when the curve, ghost or range changes. The playhead dot is a separate layer, so playback does not rebuild the curve.
- Bezier-equivalent types show P1 and P2 (6dp dots, 48dp targets) with lines to the corners. Dragging a Linear or Easy Ease handle makes the curve Custom.
- Ghost: a dashed copy of the curve as it was before the last edit or gesture.
- **Direct drag:** grab a handle; a loupe bubble sits away from the finger (above, or below near the top) with x, y and a 3× crop.
- **Relative drag:** tap a handle to select it; a drag that starts on empty canvas moves it at 0.35×. **Implemented locally.** AE_PARITY §20's "trackpad" is `PositionTrackpad`, a complete Position control with its own grid and crosshair and no reusable drag core. The Graph canvas uses the same origin + delta / size principle.
- x clamps to [0, 1] and y to [−2, 3]. A drag commits once on release.
- Snap (0.05 grid, off by default), Mirror (P2 = (1 − x1, 1 − y1) and back, off by default) and Invert ((1 − x2, 1 − y2, 1 − x1, 1 − y1); one action; disabled for Linear, Hold, Bounce and Elastic).

**Type chooser.** Hold, Linear, Easy Ease, Ease In (0.42, 0, 1, 1), Ease Out (0, 0, 0.58, 1), Custom, Bounce, Elastic. Custom starts from the current type's bezier (Linear (0, 0, 1, 1), Easy Ease (0.33, 0, 0.67, 1)); from Hold, Bounce or Elastic it starts from the Easy Ease bezier. Bounce starts at 3 bounces / 0.5, Elastic at 3 oscillations / 0.5 damping. One undo per tap.

**Apply to all keys:** a tool tile and a rail ⋮ item, one undo. The rail ⋮ also has "Reset curve to Easy Ease".

**Presets (`EasePresets`).** 32 built-ins, from published standards only:
- CSS ease, ease-in, ease-out, ease-in-out.
- The published cubic-bezier fits of Penner's sine, quad, cubic, quart, quint, expo and circ (each in, out, in-out), and back in, out, in-out.
- Material standard, decelerate, accelerate, emphasized.

Code constants only, never stored by id: applying one writes CUSTOM with its four floats (one undo). The matching preset (ε 0.005) is highlighted. The glyphs are the curves themselves in the family stroke (`InspectorIcons.curve`).

**Numeric popover.** Non-modal; the existing `AnchoredPopover` with its toggle, empty-tap dismissal and swipe guard. It holds `SliderPopoverContent` rows: x1, y1, x2, y2 (bezier types), count and bounce height (Bounce) or oscillations and damping (Elastic). Typed values commit on Done or focus loss. A slider drag is one undo; reset and typed values are one undo each.

**Clipboard (`CubicBezierText`, tested).**
- Copy writes `cubic-bezier(x1, y1, x2, y2)` for Custom, Linear, Easy Ease and Ease In / Out. It is disabled for Hold, Bounce and Elastic.
- Paste accepts `cubic-bezier(…)` or bare numbers (commas or spaces, leading dots, negatives, an optional `;`). It rejects x outside [0, 1], clamps y to [−2, 3], and anything else is a silent no-op.

**Icons.** 17 new `InspectorIcons` in the shared stroke and round caps/joins: `GRAPH_VALUE/SPEED/EASE`, `EASE_HOLD/LINEAR/EASY/IN/OUT/CUSTOM/BOUNCE/ELASTIC`, `EASE_SNAP/MIRROR/INVERT/APPLY_ALL/COPY/NUMERIC`. Paste reuses `PASTE`. `InspectorIconFamilyTest` passes unchanged.

## 6. Interaction with existing systems

Prev/Next, markers and the rail diamond behave as before; the graph reads the one playhead. The timeline's key drag (Key MOVE) and snapping are unchanged and keep curves. A Graph drag hoists only its own draft curve; the editor recomposes through the existing transient state, as other drags do.

## 7. Out of scope (not built)

Value graph, Speed graph (icons visible, disabled), key selection or dragging in the graph, zoom/pan, separate X/Y, preset libraries, convert-to-keys, smooth-through-key, multi-select, motion paths, expressions, landscape.

## 8. Deviations from the brief

1. Base `d0d27f8` instead of `67df5f3` (master had moved on with F3.4). Not committed.
2. 32 presets rather than about 24: the brief's own list names 32.
3. The Graph page carries its own rail (diamond on the edited property, Graph icon to return, ⋮ with Reset curve / Apply to all) instead of keeping the source page's rail. That is what makes "tap the icon again to return" and a live diamond possible on the page.
4. The header grows with font scale, as every inspector header does.
5. Changing type through the old rail ⋮ items resets the curve parameters to that type's defaults (value edits keep them).
6. Small changes outside the three listed areas, all in service of them:
   - consumer clamps in `CompositionRenderer`, `TextLayout`, `PreparedTextAnimation.evaluateSelectors/evaluateAnimator` (evaluate stage only), `TextSelectorEngine.coverage`, `TextExplode` and two preview overlays (§3)
   - `transientAllowed` (§ hard rules)
   - `rememberSaveable` for the Transform mode / link toggles plus a `SaveableStateHolder` in the sheet (§5)

## 9. Files

New:
- `model/KeyEasing.kt`
- `editor/KeyEasingActions.kt`, `editor/EaseTools.kt`
- `ui/inspector/GraphPage.kt`
- Tests: `GraphG1ModelTest`, `GraphG1OvershootTest`, `GraphG1UiStateTest`, `GraphPageLayoutTest` (androidTest), `GraphCurveRenderTest` (androidTest).

Changed:

| Area | Files |
|---|---|
| Schema | `project.fbs`; generated `EasingType`, `Keyframe1D`, `Keyframe2D`, `ColourKeyframe`. |
| Model | `ProjectDocument.kt`, `TextProperties.kt`, `ProjectCodec.kt`, `TextAnimatorCodec.kt`, `TextAnimators.kt`, `TextSelectorEngine.kt`, `PreparedTextAnimation.kt`, `TextLayout.kt`, `TextExplode.kt`. |
| Editor | `EditorState.kt`, `EditorViewModel.kt`, `TextPropertyActions.kt`, `TextAnimatorActions.kt`, `TextAnimatorEdits.kt`, `TextEditController.kt`. |
| Render | `CompositionRenderer.kt`. |
| UI | `ElementInspectorSheet.kt`, `InspectorIcons.kt`, `InspectorLandingGrid.kt`, `InspectorSwipe.kt`, `TextInspectorPanel.kt`, `TextInspectorState.kt`, `EditorScreen.kt`, `CompositionPreview.kt`, `PreviewLayerOverlay.kt`. |
| Docs | This file, `KEYFRAME_ANIMATION.md`, `FLUXX_SDD.md` §7, `AE_PARITY_VISION.md` §20, `ELEMENT_INSPECTOR.md`, `PHASE_2_STATUS_AND_BACKEND.md`, `FLUXX_ROADMAP.md`, `README.md`, `docs/README.md`. |

## 10. Verification

**JVM:** `:app:testDebugUnitTest` → **518 tests**: 518/518 in two full runs. In a third full run only the known-intermittent host allocation proof failed (112 bytes; it passed in isolated reruns). New:

| Test class | Covers |
|---|---|
| `GraphG1ModelTest` (22) | Evaluator, bounce / elastic, validation, v13 round trip of every type on every key kind, wire cost, v12 fixtures, sanitising, carry paths, `TrackMemo`, the action. |
| `GraphG1OvershootTest` (3) | Overshoot safety (§3). |
| `GraphG1UiStateTest` (12) | Segment rule, labels, parse/format, presets, chooser, invert/mirror/snap, rail enable rules, Graph diamond, numeric rows, page geometry, Y range and sampling, section rules and icons. |

**A16 instrumented** (`adb install -r` debug and test APKs, `am instrument`; no uninstall, no data clearing, no settings changed):

| Test | What it checks | Result |
|---|---|---|
| `GraphPageLayoutTest` (3) | Real sheet at 360 / 384dp × 268 / 328dp: every Graph region and tile inside the sheet, tiles ≥ 32dp, square canvas ≥ 120dp, no overlaps; Graph icon selected and returning to Transform in the same mode; a real handle drag on P1 → Custom with y1 raised, one undo; Bounce / Hold / preset / apply-to-all taps; numeric popover toggles; Edit Text returns to Character and swipe-down still closes. | Pass |
| `GraphCurveRenderTest` (1) | Production offscreen renderer: a Custom (back-out) and a Bounce Position segment render pixel-identical to a static layer at the evaluator's value on 21 frames each, and differ from Linear mid-segment. | Pass |
| `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest`, `TextAnimateLayoutTest` | The existing targeted layout suites. | 12/12 pass |
| `adb logcat -b crash -d` | Crash buffer after the runs. | Empty |

**Found and fixed on device:** the numeric tile did not close its popover on a second tap. `PopoverDismissal.request()` resumes the open popover's collector immediately (Main.immediate), which closed it, and the toggle then read the closed state and reopened it. The toggle now decides first and only requests dismissal of other popovers when opening.

**Not verified:**
- Phone acceptance (§11).
- Playback smoothness of the playhead dot, and the loupe and relative-drag feel by hand.
- Clipboard copy/paste on the device (the parser and formatter are JVM tested).
- Font scale 1.3 and larger on the device (JVM geometry only).
- TalkBack.
- The 30-animated-layer timing (no measurement run; the fast path is structurally unchanged).
- Warm-up / F2 suites (skipped as instructed).

## 11. A16 manual test list

1. Rotation with three keys → Transform → Rotation. The rail Graph icon is enabled (one key: disabled). Open it: "Key 1 to 2", start → end values and duration; Value and Speed are dim.
2. Scrub and play: the dot follows the curve; on key 2 the label reads "Key 2 to 3"; after the last key, the last segment. Prev/Next move it.
3. Drag P1 directly (loupe above or below your finger with x, y); release; one Undo reverts it. Tap P2, then drag on empty canvas: P2 moves slowly. Snap and Mirror on, drag again.
4. Each type tile: Hold steps; Bounce bounces under the target; Elastic wobbles past it and settles; check the layer in the preview each time.
5. Tap presets (the strip scrolls): the matching one highlights; Back In-Out overshoots and the canvas grows to fit.
6. Copy (Custom), paste into a notes app; copy `cubic-bezier(.68,-.6,.32,1.6)` from notes and Paste: it applies. Paste junk: nothing happens.
7. Numeric tile: sliders and typed x1…y2, count/height, oscillations/damping; tap the tile again or empty sheet space to close; the timeline still scrubs with it open.
8. Apply to all keys; Invert (ease-in ↔ ease-out); Reset curve (rail ⋮).
9. Graph icon again → back on Rotation; Back from the Graph page does the same. Edit Text → Size (two keys) → Graph → back on the same tab; Animate → a keyed Start field → Graph → back with the same field focused.
10. Both preview sizes (expanded 268 / standard 328): nothing clipped, no page scrolling. Swipe down from the top closes the sheet on the Graph page.
11. Save, close and reopen the project: the curves are kept. Undo/redo through a few Graph edits.
12. A fill colour with Elastic: the colour never goes past its end colour.

## 12. Questions for G2

- Key selection: should the Value graph's selected key replace the playhead rule on the Ease view, or should the playhead rule stay as the default?
- Apply to all: include the last key too (its easing is unused today, but becomes the departing key if a key is added after it)?
- Should the old rail ⋮ easing items (Linear / Easy Ease) stay, now that the Graph page edits every type?
- Bounce and Elastic are ease-out only. Do you want in and in-out variants? That would be additive wire values (a further schema bump).
- Separate X/Y curves for Position/Scale (G4+) will need a further schema bump; is that still wanted?
