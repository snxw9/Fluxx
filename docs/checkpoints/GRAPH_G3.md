<a id="rec-graph-g3"></a>

# GRAPH G3: the Speed graph (with Smooth through key)

> **Next: [GRAPH G3.1](GRAPH_G3_1.md).** It finished this pass's interrupted A16 sweep and hardened the rail auto-scroll. It also made the Speed view stable and segment-framed, with no pan and Ease-style relative drags, stopped its handles from crossing, and added magnetic landmarks.

Branch `graph-g3`, on top of TL1 (`4eae6ac`), from `graph-g2-1` (not merged into `master`). Part 2 of the TL1 + G3 run, committed separately. Not merged.

**Status:** implemented in source.
- **JVM:** 581 tests, 0 failures, in one full run.
- **A16 (SM-A165F):** `GraphSpeedTest` passes (7 tests). The final sweep was **stopped part-way** (host low on memory), with 13 passed and 2 failed (§Tests), so `GraphPlayheadTest`'s new Speed test has not run yet.
- **Phone acceptance:** pending (§Phone test list).

## Hard rules kept

- **No new action:** every edit is `SetKeyEasing`; two keys join in one `Batch`, through the existing begin/preview/commit/cancel.
- **Frozen contracts:** no change to `PreviewController`, the gesture protocol, the batch/draw contract, the schema (v13) or preparation, the evaluator, or `SetKeyEasing`'s semantics.
- **Sheet height:** constant (asserted in every Speed test).
- **Playhead:** the Graph never moves it, except the key double tap (`GraphSpeedTest`, `GraphPlayheadTest.speedGraphEditsKeepThePlayhead`).
- **Icons:** one new monochrome family glyph (`GRAPH_SMOOTH`); `InspectorIconFamilyTest` passes.

## G3.0 What it is

- **Tab:** Speed is live in the header (Value | Speed | Ease) wherever the Value tab is (`GraphValues.available`: continuous, ≥ 2 keys, not colour).
- **One canvas, parametrised:** `ValueCanvas` / `ValueScene` take `speed = true` rather than a copy of the canvas. Pan, pinch zoom, fit, double tap, clip, selection, the playhead line and dot, and semantics all come from the same code. The scene supplies the plotted quantity (`curveY`), key positions (`keyY`), handle points and anchors, the handle drag mapping (`dragHandle`) and the tick labels.
- **Keys:** tap selects; double tap seeks; no key dragging. A drag on a key or on empty canvas pans.
- **Shared layout:** strip, pinned rail, library and left rail are shared with Value and Ease and act on the selected segment.
- **Viewport:** the Speed view keeps its own viewport in the session (`speedViewport`), reset when the page or property changes.

## G3.1 Speed: definition, units, sampling

The evaluator interpolates a segment as `v0·(1 − e) + v1·e` (e = easing progress). In display units (the Value graph's `ValueAxis`):
- **Speed:** **S(t) = (D/Δt)·p′(τ)**, with τ = (t − t0)/Δt, Δt in seconds, and D = |v1 − v0|. For 2-D properties, D is the length of the display-unit displacement `hypot(ΔX, ΔY)`, so Position (px) and Scale (%) plot **one combined curve**.
- **Sign:** S ≥ 0 for monotone easing and negative where an overshoot reverses.
- **Outside the keys:** 0. On a key, the departing segment's speed; the last key shows the arriving one. The curve draws the jump at a key vertically.
- **p′ for the bezier types** (Linear, Easy Ease, Ease In/Out, Custom): `(dy/ds)/(dx/ds)` on the evaluator's own cubic. `SpeedMath.bezierSlope` uses the same Newton → bisection solve as `EasingMath.bezier`. A zero dx/ds at an end handle (x1 = 0) takes the limit a hair inside.
- **p′ for Bounce and Elastic:** a central difference (h = 10⁻³, clamped to [0, 1] at the segment ends) of the real evaluator (`EasingCurve.ease`).
- **Hold:** speed 0 and no handles.
- **JVM checks:**
  - The slope matches a fine numeric derivative of the evaluator, within 3%, at 37 points for Linear, Easy Ease, three Customs (including overshoot), Bounce and Elastic. Points on a bounce kink are skipped.
  - **∫S dt over a segment equals its travel D** within 0.5% for Easy Ease, an overshooting Custom, Bounce and Elastic. The average speed is exactly D/Δt.
- **Units and format:** the page's display units per second (`GraphSpeed.unit` = axis unit + "/s": px/s, °/s, %/s, …) with the Value graph's formatting (`PropertyUnits.text`). Rotation is the exception: it reads as a plain number with one decimal, because the dial's turns notation is meaningless for a rate. Value-axis ticks use the same formatter, and the zero line is emphasised. The fit always includes 0, plus the sampled extent and every handle's speed.
- **Drawing:** cached in `drawWithCache` (one path per viewport, scene or size change) and allocation-free per sample.

## G3.2 Handles (speed and influence)

For the selected bezier segment `(x1, y1, x2, y2)` (`SpeedHandles`):

| Handle | Influence | Speed | Drawn at |
|---|---|---|---|
| Out (start key) | `i0 = x1` | `S0 = (D/Δt)·y1/x1` | `(t0 + i0·Δt, S0)` |
| In (end key) | `i1 = 1 − x2` | `S1 = (D/Δt)·(1 − y2)/(1 − x2)` | `(t1 − i1·Δt, S1)` |

- **Handle lines:** each is drawn level from its key's point on the curve, since the curve's speed at the key equals the handle's speed.
- **Dragging:**
  - x sets the influence (clamped to 0.01–1).
  - y sets the speed: `y1 = x1·S0/(D/Δt)` and `y2 = 1 − i1·S1/(D/Δt)`, clamped to [−2, 3]. x2 is clamped to [0, 1].
  - A named type becomes Custom.
  - Snap (on by default since G2.1) snaps the influence to 5% and the speed to the visible grid step. Mirror doesn't apply in the Speed view.
- **Edits:** one undo per drag (begin/preview/commit/cancel; `SetKeyEasing`, or a `Batch` with Smooth through key). The draft curve drives the handle and bar.
- **Flat segment** (D below the Value graph's flat threshold): speeds are 0, the handles sit on the zero line, and only x (influence) moves.
- **Bounce, Elastic, Hold:** no handles. The bar's tiles are disabled with the description "Edit in Ease".
- **Hit rule:** the nearest target within 24dp; a key wins a tie (shared with Value).
- **JVM:** a round trip `(x1, y1, x2, y2) → (influence, speed) → (x1, y1, x2, y2)` over 6 × 7 × 4 × 7 bezier points × 2 handles, including negative speeds, overshoot, x1 → 0 (the limit) and a flat segment. Also the y clamp, the influence clamp, Snap 5% plus the grid, and level handles.

## G3.3 Smooth through key

- **Toggle:** in the Tools popover (`GRAPH_SMOOTH` glyph, "Smooth through key (Speed graph)"). Off by default; persisted app-level next to `graphSnap` (`smoothThroughKey`).
- **When on:** an edit of a speed at a key with a segment on both sides (not the first or last key) also sets the **neighbouring segment's handle at that key** (`SmoothThroughKey.apply`, pure). Influences stay independent.
  - **Same direction** (chord directions' dot product > 0): the neighbour's speed becomes the edited speed, in display units/s, converted through its own D/Δt.
  - **Opposite directions** (a peak or valley): both sides become **0**, and the edited handle's y is pinned to 0 (its influence is still free).
  - **Position / Scale:** the magnitude along each chord matches; spatial tangents are out of scope.
  - **No change:** when the neighbour is flat or not a bezier (Bounce / Elastic / Hold), or when the toggle is off.
- **One undo:** a single `Batch` of `SetKeyEasing`s (`GraphPage.editAction`) through the gesture protocol for drags, or dispatched directly for typed speeds.
- **Scope (deviation from "also Value and Ease if contained"):** it applies to the **Speed view only**, for handle drags and typed speeds. The Value and Ease handle drags edit a normalised curve that has no speed at the key without the neighbour geometry the Speed scene computes. Routing them through it would mean computing Speed segments in two more editors, so I left them alone. The toggle's description names the Speed graph.
- **Not retroactive:** turning it on changes no key until an edit touches one, and the link isn't stored.
- **Clamps:** if the neighbour's y would leave [−2, 3], it's clamped and the speeds no longer match exactly.

## G3.4 Values bar (Speed view)

- **Tiles:** Speed out | Influence out | Speed in | Influence in, for the selected segment (the segment at the playhead if no key is selected). Same tile, size, corner label, centred value and opaque fill as Ease and Value.
- **Formats:** speeds in the page's units/s and decimals; influences in % (one decimal; typed 0.1–100).
- **Typed entry:** `AnchoredPopover("value-entry")` + `InspectorValueField`; commit on Done or focus loss; invalid input discarded.
  - **One undo** per entry.
  - **A typed speed** keeps that handle's influence; Smooth through key applies.
  - **A typed influence** keeps that handle's speed.
- **Disabled:** Bounce / Elastic / Hold, and a flat segment's speeds (its influence stays editable).

## G3.5 Header, tabs, rail, library

- **Last-used tab:** the page opens on it (`graphTab` in the curve library file, Ease the first time). `GraphTabs.effective` falls back to Ease when the stored tab is unavailable for the property (colour, < 2 keys). A G2.1 file without the key reads as Ease.
- **Header:** the label (Key n to n+1 · duration; curve name) is unchanged.
- **Tools:** Invert, Apply to all keys, Copy / Paste cubic-bezier, Reset, Snap, library save, strip, pinned rail and library all act on the selected segment.
- **Zoom:** limits and Fit behave as in Value.

## Tests and run times

| Run | Wall time | Result |
|---|---|---|
| JVM `GraphG3Test` (+ icon family, G2, G2.1; iteration) | 101 s, then 15 s | 3 test tolerances / format, then green |
| JVM full suite (once) + installs | 127 s | **581 / 0** |
| A16 `GraphSpeedTest` | 33 s | 6/7. The failing test needed to scroll the type strip to Bounce; re-run alone 14 s, green |
| A16 final sweep (Graph, layout, render suites) | stopped part-way | **incomplete**: the host ran low on memory and stopped the adb process. The run kept going on the phone until stopped with `am force-stop` (no data touched). Before that: 13 passed, 2 failed (below). |

- **JVM `GraphG3Test` (10):**
  - speed = D/Δt × slope, and Position's combined length;
  - derivative vs the evaluator;
  - integral = travel;
  - handle round trip and clamps;
  - drag Snap;
  - Smooth through key: same direction, opposite (both 0), first / last key, toggle off, non-bezier neighbour;
  - one Batch, one undo;
  - the Speed scene (fit includes 0, key and handle positions, drag);
  - the unit table (px/s, °/s, %/s, %/s, px/s);
  - last-used tab persistence and fallback.
- **A16 `GraphSpeedTest` (7):**
  - **Opening:** the Speed tab opens on Rotation with three keys, curve and handles (tags); the bounds match the Ease canvas; the sheet is constant.
  - **Handle drags:** out and in drags change the easing, and the Ease view shows the same stored y1. Each is one undo.
  - **Smooth through key:** with the toggle on, an in-handle drag at key 2 changes both keys and the speeds match across the key. One undo restores both.
  - **Typed entry:** Speed out = 60 °/s and Influence in = 40% keep each other.
  - **Position:** one combined curve with no X | Y toggle, shown in px/s.
  - **Navigation and types:** pinch zoom and double-tap fit; Bounce and Hold remove the handles and disable the tiles ("Edit in Ease").
  - **Tab memory:** remembered across closing and reopening the page.
- **The partial sweep's two failures:**
  - `GraphValueTest.valueTabOpensWithKeysAndKeepsEveryBound` still asserted the G2 rule "Speed tab disabled". G3 enables it, so the assertion is updated. Not re-run.
  - `GraphLibraryTest.railReorderLiftsFollowsAndWritesOnce` is the known G2.1 flaky test, with the same signature (index 7 in a full-suite run).

  Not reached, so **not verified** in this run: the rest of `GraphLibraryTest`, `GraphPageLayoutTest`, `GraphPlayheadTest` (including the new Speed test below), `GraphCurveRenderTest`, `InspectorSwipeLayoutTest`, `TextInspectorLayoutTest` and `TextAnimateLayoutTest`.
- **`GraphPlayheadTest.speedGraphEditsKeepThePlayhead` (not run yet):** on the real editor, Smooth on, a handle drag that changes two keys, a typed speed, pan, pinch and fit. The playhead, the clip and the selection stay unchanged.

## Not verified

- Real-finger feel of the speed handles (small speeds near the zero line).
- Smooth through key on Position / Scale on the device (JVM only).
- Bounce / Elastic speed curves drawn on the device (the model is JVM-checked).
- TalkBack.

## Phone test list

1. Open a Rotation property with three keys and go to Speed. The curve starts and ends at 0 for Easy Ease, and the keys and two handles show.
2. Drag the out handle up and right: a faster, longer start. Switch to Ease: the same curve. Undo once restores it.
3. Turn on Tools → Smooth through key. Drag the handle at the middle key: the other side follows, and the speed curve is continuous across the key. One undo restores both sides.
4. On a peak (values up, then down), Smooth pins both sides to 0.
5. Type Speed out = 60 and Influence in = 40%.
6. Position shows one curve in px/s.
7. Bounce or Hold: no handles; the bar says "Edit in Ease".
8. Close and reopen the Graph page: it opens on Speed.
9. Nothing in the Speed view moves the playhead, except a double tap on a key.

## Deviations

- **Smooth through key** applies in the Speed view only (§G3.3); the toggle says so.
- **Rotation speeds** read as plain numbers (°/s), not turns notation.
- **Mirror** doesn't act in the Speed view (influences are independent by design).
- **Key markers** sit at the departing segment's speed (the last key: the arriving one); the jump at a key is drawn vertically.

## Questions for G4

1. Should Smooth through key also act in the Value and Ease views (it needs the Speed segments there)?
2. Spatial (tangent) continuity for Position paths: with separate X/Y easing (schema v14)?
3. A "Make continuous" action that matches all keys at once (retroactive), alongside the live toggle?
4. Convert Bounce / Elastic to keys (G4): one key per bounce apex, keeping the stored curve as the source?
5. Show both in and out speeds at a key (two dots) instead of the departing one?
