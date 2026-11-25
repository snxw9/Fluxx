<a id="rec-graph-g3-1"></a>

# GRAPH G3.1: finish the G3 sweep, ruler tiers, Speed view usability

Branch `graph-g3-1`, created from `graph-g3` at `e6fc0e2` (`graph-g3` is not merged into `master`). Not merged.

**Status:** implemented in source.
- **JVM:** 587 tests, 0 failures, in one full run.
- **A16 (SM-A165F):** the final sweep, one class per invocation, is all green.
- **Crash buffer:** empty.
- **Phone acceptance:** pending (§Phone test list).

## Hard rules kept

- **Frozen contracts:** no change to `PreviewController`, the gesture protocol, the `EditorAction` contract (still `SetKeyEasing` / `Batch` only), the batch/draw contract, the schema (v13) or preparation, the evaluator, or `SetKeyEasing`'s semantics.
- **Sheet heights** are constant.
- **Playhead:** the Graph never moves it, except the key double tap.
- **Icons:** no new icon (`InspectorIconFamilyTest` passes).
- **Test runs:** instrumented suites ran one class per invocation, in sequence, never alongside a Gradle job; `gradle.properties` is untouched.

## §1. G3 verification (unchanged `graph-g3` code)

| Class | Wall time | Result |
|---|---|---|
| `GraphValueTest` (the updated Speed-tab assertion) | 38 s | **9 / 9** |
| `GraphLibraryTest` | 90 s | 11 / 12: `railReorderLiftsFollowsAndWritesOnce` failed (slot 7), see below |
| `GraphPageLayoutTest` | 24 s | **5 / 5** |
| `GraphPlayheadTest` (incl. the never-run `speedGraphEditsKeepThePlayhead`) | 95 s | **8 / 8** |
| `GraphCurveRenderTest` | 8 s | **1 / 1** |
| `GraphSpeedTest` | 26 s | **7 / 7** |
| `InspectorSwipeLayoutTest` | 31 s | **2 / 2** |
| `TextInspectorLayoutTest` | 10 s | **3 / 3** |
| `TextAnimateLayoutTest` | 52 s | **7 / 7** |

### The rail test's cause

The investigation was bounded; the cause is partly proven and partly suspected.
- **Ruled out:** keyboard state left by the preceding test (`aSecondTypedEntry…`, the test that always ran right before it). A debug run logged IME = 0 and a stationary rail.
- **Proven (harness):** the test lifted the **first** rail item, whose centre is 20dp from the rail's top, **inside** the 56dp auto-scroll zone. Auto-scroll was active for the whole drag (logged at −305 dp/s). The test's comment said "outside both zones", but it only checked the target. In the failing run, the rail's scroll ran 0 → 2500 → 3000 under a still finger, and the slot followed to 7.
- **Suspected (product, hardened):** at the top of the list that auto-scroll can't move. Even so, the loop called `scrollBy` every frame, which most likely competed with the slot change's `requestScrollToItem` anchor, so the lazy list fell back to following the dragged item's key.
  - **Fix:** auto-scroll never calls `scrollBy` towards an end the list can't move to (`canScrollBackward` / `canScrollForward`). That's a no-op change in behaviour.
  - **Not proven:** a regression test that fails without the fix couldn't be made deterministic (the race reproduced in about 1 of 2 full-class runs).
- **Test made deterministic:** it now drags p1 → p2, both asserted outside the zones, and asserts the rail never scrolls.
- **Result:** `GraphLibraryTest` passed **3 / 3** full-class runs after the change (90 s each), and again in the final sweep. The user's report that rail reorders land perfectly on the phone fits this: a finger rarely rests in an edge zone while the list is at that end.

## §2. Ruler tiers: always three tiers, mids stay visible

The TL1 thresholds (minors only at a frame pitch ≥ 6dp, mids when half a step ≥ 8dp) are replaced. Everything else from TL1 is unchanged: one `frameX`, whole-pixel ticks, majors on `:00`, mids on `floor(period/2)`, no labels, heights 34 / 20 / 12 px, the 0.00 px line, no ghost columns.
- **Minimum pitches:** major 12dp, mid 5dp, minor 3dp.
- **1 s step:**
  - Minors on every frame at a pitch ≥ 3dp. Otherwise every Nth frame, N the smallest of {2, 3, 4, 5, 6, 10, 15, 30} that reaches 3dp.
  - Minors skip majors and mids.
  - Mids always show, since half a second is ≥ 6dp whenever a second is ≥ 12dp.
- **Zoomed out (2 s, 5 s, 10 s, 30 s, 1 min …):** the mid at half the step; minors at tenths of the step if ≥ 3dp, else fifths, else none. Each tick sits on frame `floor(k·period/divisions)` (the mid rule's rounding).
- **Major step:** grows only when the 1 s pitch is under 12dp.
- **Default zoom:** `TIMELINE_DEFAULT_DP_PER_SECOND = 120`, now a named constant used by `TimelineView`. JVM-checked: all three tiers at the default zoom for 23.976, 24, 25, 29.97, 30, 50, 59.94 and 60 fps × three start offsets, with majors on `:00`. Mids show whenever their pitch is ≥ 5dp at every zoom.

Pitches (dp) at the default zoom and four zoomed-out levels (minor pitch, with "every N" where it isn't every frame; mid = half a second; major = 1 s):

| dp/s | 24 fps minor | 30 fps minor | 60 fps minor | mid | major |
|---|---|---|---|---|---|
| **120 (default)** | 5.0 (every frame) | 4.0 (every frame) | 4.0 (every 2nd) | 60 | 120 |
| 60 | 5.0 (2nd) | 4.0 (2nd) | 3.0 (3rd) | 30 | 60 |
| 24 | 3.0 (3rd) | 3.2 (4th) | 4.0 (10th) | 12 | 24 |
| 14.4 | 3.0 (5th) | 4.8 (10th) | 3.6 (15th) | 7.2 | 14.4 |
| 12 (the timeline's minimum) | 3.0 (6th) | 4.0 (10th) | 3.0 (15th) | 6 | 12 |

- **Finding:** the timeline's pinch zoom stops at 12dp/s (`pxPerSecond.coerceIn(12f, 2400f)`), exactly the major minimum. So **in the app the major step never grows past 1 s**. The 2 s and larger rules are implemented and JVM-tested (e.g. 10dp/s at 30 fps: 2 s majors at 20dp, a 1 s mid, fifths at 4dp) but unreachable until a lower zoom is allowed.
- **A16 (`TimeAuditTest` at 24, 30 and 60 fps):** at the default zoom and two zoomed-out levels (×0.12 ≈ 14dp/s, and the 12dp/s floor), the column scans found **three distinct tick heights**, **0 ghost columns** and **0.00 px** line-to-tick offsets. The final sweep's full `TimeAuditTest` (7 tests) logged 0.00 px in all 63 checks.

## §3. Speed view: stable, segment-framed, Ease-style navigation

**Cause of the movement (confirmed in code):**
1. With no stored Speed viewport, the canvas used the auto-fit of the **current** scene (`session.viewport ?: scene.fit`). The fit is recomputed from the curve, so the window rescaled as the curve changed during a drag.
2. Handle drags grew the window (`vpNow.include(…)`).
3. One-finger drags panned (the shared pan path).

**Framing** (`SpeedFrame.frame`):
- **Time:** the selected segment `[t0, t1]` plus 30% of each neighbour's duration on that side, or 8% of the segment where there's no neighbour.
- **Speed:** the speed curve over that time window, 0, the average speed and both handles' speeds, plus **35% of the span above and below**. A flat segment gets a minimum span of 1 unit/s.
- **Example:** Rotation 0° → 90° in 1 s with an Easy Ease next segment. Segment 1 frames −0.08 s … 1.6 s and −47 … 181 °/s.

**Stability:**
- The window is computed on entering the view, when the selected segment changes, on double-tap Fit and on pinch.
- It is **never recomputed while the curve changes** (a drag, a typed value, a library, strip or preset tap).
- During a drag the window is **frozen**, and the dragged handle is clamped inside it (`SpeedFrame.clamp`).
- After release, if a handle is within 10% of an edge, the window expands **once** (animated 150ms). A16: −47 … 181 → −114 … 280 °/s after a hard drag, then still.

**Navigation (Speed view only):**
- No one-finger pan.
- A tap on a handle selects it. A drag elsewhere (empty canvas or a key) moves the selected handle relatively at ×0.35: `RELATIVE_DRAG`, the Ease view's constant. With no handle selected, it does nothing.
- Direct drag on a handle (48dp target), pinch zoom and double-tap Fit (re-frames) are kept.
- Tapping a key selects it; a key in a neighbour's sliver changes the segment, which re-frames once.

**The Value view** keeps its scrolling canvas and viewport. The Speed branch lives in the same `ValueCanvas` (no duplicated canvas code); only its drag path differs.

## §4. Speed view: handles cannot cross

- **Crossing condition:** with influences i0 (out) and i1 (in), the handles cross when i0 + i1 > 1.
- **Stop (`SpeedCross.maxInfluence`, from the curve at the drag's start):**
  - **In order:** i ≤ 1 − i_other − gap. The gap is **1% of the segment or 4dp on screen, whichever is larger**. At the A16's framed zoom it was 0.037 of a 1 s segment (the 4dp floor).
  - **Started crossed** (Back eases, x1 > x2): the dragged influence may only decrease (uncross), never grow.
- **Haptic:** a light tick once on reaching the stop (`InspectorHaptics.tick`).
- **Typed influence entries** are clamped the same way, not discarded.
- **Order with Smooth through key:** the clamp is applied before the neighbour match.
- **Ease and Value views are unchanged:** their handles may still cross, so the standard Back / overshoot presets stay editable there.

## §5. Speed view: magnetic landmarks

All under the Snap toggle (on by default); typed values are never snapped.
- **Speed landmarks:** 0, the average speed D/Δt, the other handle's speed, and the neighbouring segment's speed at the same key (when a neighbour exists). A flat segment has none.
- **Influence landmarks:** 33.3%, 50% and the other handle's influence.
- **Mechanics** (`SpeedSnap`, on the existing `Magnet`):
  - Each landmark locks within **10dp** on screen, converted at the drag's start, and never more than **35% of the distance to the nearest other landmark**, so zones never merge.
  - It releases beyond the zone, with a light haptic on lock.
  - Outside every zone, the existing snaps apply: influence 5%, speed to the visible grid step.
  - Landmarks take priority over the grid.
- **Guides:** while dragging, faint dashed lines at every speed landmark level; the locked one is solid.
- **A16:** a drag that ends 4dp below the average-speed line locks to it, and the stored curve's speed is 90.000 °/s. A typed 88 stays 88.

## Tests and run times

| Run | Wall time | Result |
|---|---|---|
| §1 classes (above) | 374 s total | 1 failure (rail) |
| Rail investigation: 2 debug class runs, then 3 class runs after the change | 94 s, 100 s; 92 s, 91 s, 90 s | fail / pass; **3 / 3 pass** |
| JVM `TL1Test`, `GraphG31Test`, `GraphG3Test` (iteration) | 58 s, 17 s, 108 s, 54 s | test expectations fixed, then green |
| A16 `TimeAuditTest` 24 / 30 / 60 (§2) | 81 s | **3 / 3** |
| A16 `GraphSpeedTest` | 60 s, 41 s, 15 s | first run: 10 failures; 7 were transient "no compose hierarchy" launch errors that didn't recur, and the framing-dependent tests were updated. Then 9 / 11, then the 2 re-run green |
| JVM full suite (once) | 52 s | **587 / 0** |
| A16 final sweep, one class at a time | 38 + 88 + 23 + 90 + 9 + 22 + 9 + 51 + 11 + 6 + 9 + 140 s | all green (below) |

- **Final sweep:**
  - **Graph:** `GraphValueTest` 9/9, `GraphLibraryTest` 12/12, `GraphPageLayoutTest` 5/5, `GraphPlayheadTest` 8/8, `GraphCurveRenderTest` 1/1.
  - **Layout:** `InspectorSwipeLayoutTest` 2/2, `TextInspectorLayoutTest` 3/3, `TextAnimateLayoutTest` 7/7.
  - **Other:** `TransformDetentsTest` 3/3, `PaletteBrowserTest` 1/1, `ColourHexEntryTest` 1/1, `TimeAuditTest` 7/7.
  - **`GraphSpeedTest`:** 11/11 on the final product code (see above).
- **JVM `GraphG31Test` (6):**
  - framing (slivers, no neighbour, headroom, zero line, flat, overshoot);
  - expand once only near an edge, and the clamp;
  - the crossing stop (in order, crossed, gap, minimum, typed / flat);
  - the landmark list (including no-neighbour and flat);
  - snapping (lock, release, priority over the grid, never skips, the zone rule, influence, the crossing interplay).
- **`TL1Test`:** the new density rules and three tiers at the default zoom for every rate.
- **A16 `GraphSpeedTest` (+4):**
  - the window is frozen during a hard drag, with at most one expansion after, and a typed value doesn't move it;
  - no pan, the relative drag works, and a key tap re-frames once to `SpeedFrame.frame`;
  - the stop with a gap, and a crossed preset can uncross but not cross further;
  - the drag locks to the average speed exactly, and a typed value stays exact.
- **Framing-dependent tests updated:** the existing Speed tests (the suite's playhead moved to 0.5 s; key 3 is now outside the frame) and `GraphPlayheadTest`'s Speed case (drags the framed segment's out handle).

## Not verified

- Haptic feel at the stop and on landmark locks.
- The real-finger feel of the relative drag.
- Ruler majors beyond 1 s on the phone (unreachable at the 12dp/s zoom floor).
- The suspected `requestScrollToItem` race itself (hardened, not proven).
- TalkBack.

## Phone test list

1. At the default timeline zoom, every rate shows three tick heights; zooming out keeps mids and thinner minors, never a solid wall.
2. Speed view: dragging a handle hard never moves the graph. After letting go near an edge, the graph eases out once.
3. A one-finger drag on empty canvas doesn't scroll. Tap a handle, then drag anywhere: it follows at a third of the speed.
4. Double tap empty space: back to the segment's frame. Tap a key in the sliver: the next segment is framed.
5. Drag the out handle towards the in handle: it stops just short of it with a light tick. On a Back preset, the handles uncross but don't cross further.
6. Drag near 0, the average speed or the other handle's speed: it locks (light tick, solid guide) and releases as you keep moving. Typed values stay exact.

## Deviations

- **Speed headroom:** 35% of the span is added both above and below.
- **Guides:** every speed landmark's level is drawn faint while dragging (not only near candidates); the locked one is solid.
- **Major steps beyond 1 s** are unreachable at the current 12dp/s zoom floor (the rules exist and are tested).
- **Library / strip / preset taps** don't re-frame the Speed view (per the brief); new handles outside the window need double-tap Fit.
- **The rail fix** is a hardening of a suspected race plus a deterministic test, not a proven product bug.
- **Test hook:** `TimelineZoomKey` stays from TL1.

## Questions for G4

1. Should a library or preset tap re-frame the Speed view when it would put a handle outside the window?
2. Allow zooming the timeline out below 12dp/s, so the 2 s, 5 s, … major steps become reachable?
3. Do you want Smooth through key in the Value and Ease views, and a "Make continuous" action?
4. Convert Bounce / Elastic to keys (G4): one key per apex?
5. Should the stop gap be larger (it is 4dp minimum)?
