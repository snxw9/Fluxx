# Edit Text consolidation — approved plan

**TEXT UI 7 (2026-11-18):** TEXT UI 6 was accepted on the A16 (Edit Text layout unchanged). Changes:

- Section pages (Transform, Timing, Blending & Opacity, Edit Solid) have one header row (← | tabs) like Edit Text, and their controls are resized to use the freed height. The landing grid is unchanged.
- Keyframe badges (hollow / filled diamond) on animatable property tiles.
- Every keyed property, all text properties included, now shows on the timeline layer bar. Unselected properties are drawn quiet.
- One shared colour picker family (COMPACT popover / FULL page). The marker colour is an anchored popover.
- Hex, alpha and RGB values are edited in place (fixes the hex popup closing).
- Ruler labels fade near the needle.

See [TEXT_UI_OVERHAUL.md](../checkpoints/TEXT_UI_OVERHAUL.md#text-ui-7-single-row-section-headers-keyframe-badges-and-layer-markers-colour-picker-family-inline-hex-entry). Device-unverified.

**TEXT UI 6 (2026-11-18):** device feedback on TEXT UI 5. Changes:

- Shared heights are back to the original 268/328dp on every page; Edit Text fits with smaller tiles (top row, two 3×3 blocks, bottom bar).
- One rounded icon family (`InspectorIcons`) across the inspector and action bar.
- The utility rail is a compact top-aligned stack, and its ⋮ is the only Edit Text overflow menu.
- The colour picker lives inside the colour popover.
- The scale ruler is visible again and follows the finger.
- SDF atlas tiers (48/96/192/384 ppem) keep large text sharp.

See [TEXT_UI_OVERHAUL.md](../checkpoints/TEXT_UI_OVERHAUL.md#text-ui-6-fit-layout-icon-family-compact-rail-colour-popover-picker-ruler-fix-sdf-tiers). The TEXT UI 5 note below is historical.

**TEXT UI 5 (2026-11-18):** the pass-2 rework was rejected on the A16 and is superseded by a single-header inspector page with anchored slider popovers, a shared limits table, keyboard-overlay handling and a log-scale Transform ruler. Shared inspector heights rose to 308/344dp. The current surface, limits, ruler spec, deviations and A16 list are in [TEXT_UI_OVERHAUL.md](../checkpoints/TEXT_UI_OVERHAUL.md#text-ui-5-single-header-page-slider-popovers-limits-log-scale-ruler); §11 below is historical.

**Rework status (2026-11-18):** After the user's A16 review ("too cluttered and confusing"), the follow-up rework is implemented in source, device-unverified, in two commits (tab faces; menu/Back/status/icons). It supersedes the surface sketches in §1, the popover/launcher model in §1–§3, the status header in §2 and the mapping in §4. The current surface and the **final old-to-new inventory** are in [§11](#11-rework-2026-11-18-final-surface-and-inventory). Gate results, deviations and the exact A16 user-run list are in [TEXT_UI_OVERHAUL.md](../checkpoints/TEXT_UI_OVERHAUL.md#rework-commit-2-menu-back-status-and-icons).

Implementation status: all four approved stages are implemented in source; device acceptance remains pending. The historical sketches below are superseded by the approved amendments and the measured final budget, complete inventory, gate results and exact manual lists in [TEXT_UI_OVERHAUL.md](../checkpoints/TEXT_UI_OVERHAUL.md). No F3 animator UI or RTL renderer is included.

Status: APPROVED for four ordered implementation commits, 2026-10-09. User amendments supersede the original sketches below: Character row 2 is Tracking/Leading/Case/Format (Baseline in Format); standard quick row is Stroke width/H scale/V scale/Baseline. Paragraph row 2 is Box width/Indents popover/Spacing popover/Direction. Independently scrolling virtualized Fonts family/axis lists are approved; accessibility-size popovers/choice lists may scroll, with searchable choice dialogs as needed. Wide-range scrubbing uses velocity acceleration; all tunables are centralized. Graph/3D remain visibly disabled. Parent chrome must be subtracted or removed, with source-budget/layout assertions at 360dp and font scales 1/1.3/2. See [implementation gates and inventory audit](../checkpoints/TEXT_UI_OVERHAUL.md). T1–T4 were tested on device by the user; all new UI remains device-unverified. T5 remains dropped and this plan does not implement F3.

Part A is commit `4dfcf02`: the instance arrows now apply the complete preset instead of only changing a local label. App Debug/Release and instrumentation-source compilation, 247 JVM tests, the host variable-font proof and host selftest passed. [Cause, correction and exact user-run checks](../checkpoints/TEXT_VARIABLE_INSTANCE_FIX.md). This approval draft is separate from that commit.

## 1. Proposed surface and measured space budget

Exactly three tabs: **Character / Paragraph / Animate**. Animate is an inert, accessible reservation for F3. It is selectable to show “Text animators — coming with F3”, but has no editing actions, diamonds, add button or empty animator list. Source, scope and the contextual rail remain stable when switching tabs. Existing library, axes, stroke, spacing and nested Character/Paragraph pages disappear only during a later approved implementation.

Treat 268dp expanded-preview / 328dp standard inspector height as the **entire** Edit Text surface, including header and rail; do not add the old Edit Text title/back row above this budget. Reuse its dismissal action in the source/overflow region. Confirm the parent sheet's padding/insets before implementation: any non-removable parent chrome must be subtracted, not hidden by overflow. Safe-area/IME insets are outside these content heights. At 360dp use 8dp side margins, leaving 344dp. Icons are 20–24dp monochrome vectors; density does not change hit target dimensions.

### Portrait, expanded preview: 360 × 268dp, font scale 1

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

### Portrait, standard preview: 360 × 328dp

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

### Landscape and large-font fallback

At landscape widths ≥600dp, retain three tabs and the fixed height; use six body columns and expose stroke/H/V/Case directly in the space saved by the font chip. Keep the same ordering and property identities. At a 360dp-wide landscape window use the portrait grid. Preview remains above the keyboard where usable height permits; do not promise a useful preview when the OS IME consumes almost all short-window height.

Continue the existing height formula: 268/328dp × max(1, fontScale), **capped by available window height after safe insets and a minimum preview region**. At 1.3–2.0 font scale, replace the four-column primary grid with three columns: Character [Font (2)][Size], [Paint][Spacing launcher][Format]; Paragraph [Alignment launcher (2)][Box], [Indents launcher][Spacing launcher][Direction]. Each category popover offers labelled 48dp rows instead of tiny glyph-only choices, with two choices per row when necessary. Font/style names ellipsize visually but expose their full accessible text. Header can wrap to two lines within the scaled budget. No reduced text scale and no overlapping touch targets.

Hard limit: arbitrary accessibility font sizes, every font/axis, the IME, useful preview and every control cannot all fit a fixed short surface without some overflow strategy. Recommended fallback: keep the main inspector fixed and non-scrolling, move the focused property's **bounded editor overlay** above it, and virtualize/scroll only long font/axis lists. If even a labelled choice overlay cannot fit, use a searchable choice dialog with a bounded scrolling list. Alternative for approval: allow scrolling inside a property popover only. Neither alternative introduces inspector sub-pages. Do not silently scroll the whole Edit Text sheet, shrink text or remove functions. Test the actual insets/font metrics before accepting this budget.

## 2. Scope, focus and property transactions

- Header begins with exactly “Whole layer” or “Selection: N characters”. Count original logical grapheme clusters, not UTF-16 units, display-case expansions or glyph count; a selected ligature can count multiple source characters. Then show the focused property, units and animation status. Paragraph edits add “N intersected paragraphs”; selection remains logical/cluster safe and preview positions remain visual. Mixed values show an em dash and “Mixed”, not the first run's value as though uniform.
- Single icons change meaning with scope: Size = layer px without selection, relative run size (%) with selection; Tracking/Baseline/Stroke width = layer base or selected-run delta; Fill/Stroke = layer paint or selected override. Header and exact-entry title spell out Base / Relative / Delta / Override. Selection overrides get an Inherit action; inheritance is distinct from transparent paint or a zero delta. Do not flatten unselected run styles when adjusting a layer base.
- The current model has **no layer tracks** for H/V glyph scale, faux styles, Case, script position, kerning mode, font identity/axes, paragraph attributes or box width. With no selection these edit all applicable runs/paragraphs inside the held Source Text document. Mark them “Whole layer · [property] · Source Text Hold”, with a Source Text key shortcut, rather than inventing independent animation. With selection these edit only the range (paragraph attributes: intersected paragraphs). Leading, Ligatures and paint order have no run equivalents: keep them layer-only and disable them with a selection, with “Clear selection to edit layer leading/ligatures/paint order”. Box width is document-wide: same explicit layer-only exception. This is a necessary scope deviation from a literal all-controls-have-run-values rule; no schema extension is proposed.
- Preserve relative run size access even in Whole layer mode: Size's property popover has “Relative size of all runs” (Source Text Hold), plus Inherit/reset; its primary scrub remains the animated layer px size. Manual kerning without selection addresses all original character boundaries as today; with selection it addresses selected source boundaries only. Explain “after each selected character”, including accumulated spacing at a ligature's end, rather than implying pairwise optical kerning.
- Focus is a stable editor value `(layerId, tab, propertyId, scope, range, storageOwner)` and survives popovers, numeric entry and colour mode. Previous/next key navigation always belongs to the focused **storage owner**. Header states Animation off / Animated, between keys / On key at [timecode], or Source Text Hold. Merely opening/focusing never enables animation or creates keys. Changing selection changes scope, not the document; re-resolve mixed state and disable incompatible layer-only actions.
- Diamonds operate only in Whole layer scope. A continuous layer property owns its diamond. A document-backed property points to Source Text and explicitly labels that ownership. With selection, hide diamond/add/remove/previous/next mutation actions and show “Range edits use the current Source Text value”; editing still updates the current held Source Text key atomically when that track is animated. This is not independent range animation.
- Freeze target layer, range, snapped playhead, reference coordinates and baseline document on transaction start. Lock tab/layer/key navigation until commit/cancel completes; a queued navigation first commits the current valid draft. Apply only one completed transaction to undo; asynchronous prepare keeps the last completed frame. Stale worker replies never replace newer drafts. No changes to PreviewController, gesture protocol or native batch/draw contracts are expected; stop if integration requires one.

## 3. Scrub, exact entry and animation actions

Drag horizontally on an icon after pointer slop to scrub; tap focuses; long press without movement shows its label and current scope. A category icon taps open its choices. Numeric values tap into exact entry, never require a long press. Cancel the tooltip if scrubbing begins. Use a local accumulator anchored at touch-down, not rounded displayed values; cancel/lost pointer restores the captured draft. Axis/typing/width updates remain last-wins once per frame, and all font/shaping/raster/IPC work stays off the UI thread. No new per-frame preparation or allocation is proposed.

Suggested starting sensitivity (to tune with manual tests, not a measured usability claim): size/stroke/baseline/leading/indents/spacing/width 1px per 4dp; tracking/manual kerning 1/1000 em per 2dp; percentages 1% per 4dp; relative size 1% per 4dp. Hold a second finger for ×0.1 precision (also offer a visible Fine toggle in exact entry for one-handed/accessibility use). Axes use 1% of axis span per 4dp, with metadata bounds and a fine multiplier; use stable numeric precision rather than assuming all axes are weight. Detents: zero, inherited/default, 100%, named-instance coordinate and every 10px/100 tracking units; selection haptic at crossings only, rate-limited, respects system haptic preferences. No haptic every frame.

Exact entry has property/scope title, explicit unit suffix, signed decimal keyboard where valid, full-value input, step −/+ actions, live preview, Cancel and Done. Units: layer geometry px, tracking/manual kerning 1/1000 em, run relative size % (`100%=factor 1`), H/V %. Leading is **additional leading px**, not absolute line height. Baseline sign matches the existing renderer. Stroke width has nonnegative layer base but signed run delta; zero is valid. Keep existing model validation, reject nonfinite/out-of-range input inline and disable Done; do not replace model bounds with current slider ranges. Preserve requested stroke width and expose effective width separately. Values are not frame-snapped: the **captured playhead time** is snapped using the composition's rational frame rate. Animated edits auto-key there using existing signed keyframe origins and preserve the existing key's interpolation. Static edits remain static. Done creates one undo entry; Cancel restores both value and original key existence.

The rail is shared, not repeated beside every control: Previous key, Diamond, Next key, Animation actions, Graph, 3D, Done. Diamond labels change to “Enable [property] animation at playhead”, “Add [property] keyframe at playhead”, or “Remove [property] keyframe at playhead”. Graph uses existing support only; otherwise visibly disabled with reason, never a working-looking no-op. 3D remains an inert reserved control. Animation-actions menu is the same for every owner: Enable animation if off; Freeze at playhead if on; Reset value; Reset animation… with confirmation when deleting multiple keys; Interpolation for an eligible key. Reset value targets current scope/current key and keeps other keys; Reset animation has explicit scope and removes the track only after confirmation. Source Text, paint order and Ligatures display **Hold** and never offer easing. Colours and continuous scalars offer existing supported interpolation only; no new curves are promised.

Colour opens the existing Global Colour Picker with prominent “Fill — Whole layer”, “Stroke — Whole layer”, or “Fill override — Selection: N characters”. Restore the same focus/tab/range after it closes. Picker draft previews use one transaction; Done commits once; Cancel/Back restores paint and key existence. Merely opening it does not auto-key. Changing Fill/Stroke while a picker draft is open must first commit/cancel explicitly; never retarget an uncommitted colour draft. Palette/eyedropper capabilities stay available. Inherit selected paint clears the override; transparent swatch remains a real ARGB value.

Stroke/softness feedback is one inline line under Paint, or the standard 12dp feedback strip: “Requested 24px · Effective 6px · SDF spread / size limit”. Where selection metrics vary, report an effective range and reason, not an invented single number. Preserve the requested value. A small information popover can explain centred stroke, round joins and imported-font spread limits. Softness is not currently an independent Edit Text property: show its applicable renderer limit as feedback only, do not add a new control or persist a new value. Zero-scale composition fill, source/run/cached-layout limits and missing-font reasons use the same nonmodal status area; blocking invalid operations stay actionable.

## 4. Complete old-to-new mapping

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

## 5. Monochrome ImageVector inventory

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

## 6. Fonts sheet, utility placement and performance

One popup sheet from the font chip, with current scope at top. Unified bundled/imported searchable family list, selected family/face section, style/named-instance choices, and variable axes only when present. Collection faces retain their index. “Default instance”, named presets and “Custom coordinates” are visually distinct; retain preset identity after axis adjustment but display “Bold · modified” if coordinates no longer match it. Choosing any instance replaces **all** axis coordinates in one undo transaction and prepares immediately. Choosing Default restores all defaults. Axis default/reset and precise entry use the same metadata validation and frame conflation as scrubs. Mixed-font selection explicitly shows Mixed until a choice is made.

Recommended bounded popup: header/scope 48dp, search/import toolbar 48dp, family list viewport at least 96dp, selected style control 48dp, axis viewport 96dp when variable, bottom Done 48dp, all capped by available height. On short windows use one visible family row and one axis row with next/previous controls or search, preserving current compact-axis functionality. **No scrolling of the inspector**; a large library and up to 16 axes require either independently scrolling virtualized list viewports in this Fonts popup (recommended exception) or explicit next/previous pagination. This exception must be approved; if “no scrolling sheet” includes every popup, choose pagination instead. Never grow the popup past the safe viewport or add Library/Variable Axes tabs.

Rendered family names are optional, worker-prepared, cached thumbnails for visible rows only, cancellable and lower priority than foreground edits. Fall back to normal UI text immediately; no Android Typeface parsing of untrusted fonts, no shaping during Compose drawing, no full-library eager rasterization. Missing fonts retain SHA/face/instance/coordinates, display fallback and allow re-import; deletion confirms all affected faces and leaves project requests intact. Existing cache/warm-worker limits remain. Font browsing without choosing never mutates the project. Font choice itself commits once; Done dismisses browsing, not a second Apply transaction.

Source-row overflow contains Explode Text (existing compact dialog), Reset text style…, Reset paragraph style…, clear selection and existing applicable utilities. Keep animation actions in the rail menu, not mixed among destructive font actions. Explode must first finish a valid active draft or require Cancel if invalid; preserve its freeze/drop summary, >200 confirmation, box-preserving pieces, cursor/playhead sampling and exactness refusals. It has no new screen.

## 7. State, IME, Back and accessibility

Use a single editor mode: Browsing / SourceEditing / NumericEditing(property) / ColourEditing(property) / Fonts / PropertyPopover / ExplodeDialog. Carry saved tab and focus independently. Only one transaction draft and one overlay may be active. Scope/range and captured time belong to the draft, not a later recomposition. No persisted document fields are needed for mode, tab, focus or selection.

Source bar expands only while typing, replacing tab body with the existing multiline editor (internal text-field scrolling allowed), Cancel/Done and a compact scope/Source Text Hold header. Preview stays visible in remaining height above IME. Numeric mode uses a single exact-entry field instead of expanding every grid cell. Keyboard Done commits valid input once and returns to the original tab; explicit Cancel restores. Back first leaves numeric/source/colour mode by cancelling its draft and hiding IME, then dismisses a popover/Fonts/Explode, then clears selection, then closes the inspector. Outside tap on a modal numeric/colour draft cancels; source outside tap finishes valid text under the existing typing policy, with this distinction documented in UI tests. Predictive Back must execute exactly one transition, never both cancel and dismiss. App background/layer deletion cancels transient drafts; rotation preserves draft/tab/focus in editor state without creating undo entries. Navigation to another layer waits for valid commit or explicit cancellation, never writes to the wrong layer.

Use MaterialTheme roles and existing dynamic-colour/fallback scheme: onSurface on surface, onSurfaceVariant on surfaceVariant, onPrimaryContainer on primaryContainer. Preserve the project's strict monochrome icon language; selected states also use outline/check/state semantics, not hue alone. Show focused-property underline/border and header text. Keep value text readable, strong contrast for limits, disabled controls exposed with reasons. Long-press labels supplement, never replace, TalkBack labels. Each numeric value has accessible Set value / Increase / Decrease / Reset actions; category controls expose labelled choices. TalkBack users never need a drag gesture. Announce scope changes, validation, key creation/removal and missing fonts without announcing every scrub frame. Traversal order: source, scope, tabs, property grid, rail. Hardware keyboard supports Tab, Enter to edit/commit, Escape to cancel and arrow stepping only in focused controls; preserve source-field cursor navigation.

Future F3 plugs into the reserved Animate tab through a separate animator editing model and spec. Do not overload current layer property focus with animator selector focus or add a dummy animator. Until approved, no F3 controls or persisted animator UI state are implemented. Existing animators still render and Explode still reports dropping them.

## 8. Risks, deviations and approval choices

1. A 268dp surface cannot display the entire inventory as direct 48dp controls. The proposal uses eight primary grid cells, short property popovers and the single Fonts popup. Secondary controls cost one or two extra taps. Standard preview exposes four shortcuts; both heights retain all functionality.
2. Main sheet never scrolls. Recommended exception is bounded font/axis list scrolling in the Fonts popup, plus a bounded choice list only at extreme accessibility size. Pagination is the strict no-scroll alternative. This decision is part of approval, not silently implemented.
3. Run-only properties remain held Source Text formatting; layer-only properties have no fabricated run equivalent. No independent axis animation, paragraph property tracks, softness field or new schema is proposed. The header explicitly states ownership. Selection diamonds stay hidden even if Source Text is animated.
4. Scope switching can surprise users; labels, inherited/mixed indicators and captured draft scope are mandatory. No automatic conversion of existing base values into overrides or flattening of all runs.
5. Icon ambiguity is highest for tracking/kerning/manual spacing, Small Caps, last-line justification, leading/before/after and glyph-vs-layer scale. Full labels in popovers, long-press hints and accessible names are mandatory.
6. Portrait/landscape + IME + very large fonts cannot guarantee useful preview and all labels at once. Use the explicit overlay fallback; do not claim conformance until tested with actual parent chrome and safe insets.
7. Part A fixes the local instance-selection event wiring; no UI overhaul is required for that bug. This plan does not claim Android responsiveness, pixel parity or device accessibility acceptance. No new font assets are required; APK asset estimate zero, code-size change unmeasured until implementation.

## 9. Suggested implementation commits, after approval only

1. **State/focus and exact-entry foundations:** typed scope/storage owner, mixed-state presentation, single draft mode, transaction/time capture, consistent actions menu and cancellation. JVM tests for one undo, auto-key/signed origins, key-existence rollback, discrete Hold and range isolation. Preserve current UI while validating behavior.
2. **Monochrome icons and fixed three-tab shell:** measured 268/328 budgets, source bar, scope header, shared 48dp rail, inert Animate slot; consolidate Character/Paragraph into primary grid/popovers, remove old pages only once inventory coverage is checked. Source-only UI layout/semantics fixtures at 360dp and font scales 1/1.3/2.
3. **Fonts popup consolidation:** unified family/style/instance/axes/import/delete/missing state, bounded list/pagination decision, low-priority optional previews; remove Library/Axes pages. Regression tests for immediate preset application, custom-axis cancellation and missing identity persistence.
4. **Paint/numeric/IME integration and utilities:** labelled Global Colour Picker for base/overrides, requested/effective stroke feedback, overflow Explode/reset, deterministic Back, large-font/landscape fallback; remove old Spacing/Stroke controls and duplicated diamonds. Finish inventory audit and docs.

Each commit: compile/JVM/host checks only, no APK/device runs by the agent; list deviations and exact user-run checks. Run appropriate Kotlin Debug/Release and affected instrumentation-source compilation, JVM behavior tests, native host selftest/parity only where shared preparation changes. No per-frame shaping/rasterization/permutation work or new evaluation allocation. Stop and report if a protected API change is necessary. Device tests below remain user-run and no visual/performance claim precedes measurement.

## 10. Exact user-run manual acceptance list (future implementation)

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

## 11. Rework 2026-11-18: final surface and inventory

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

| Old control / popup action / menu item (TEXT UI 4) | New location (rework) |
|---|---|
| Source bar excerpt; tap to type | Source bar; unchanged typing mode (Cancel / Done header now reads "Editing Source Text · scope") |
| Source bar "Hold" label | Removed; Hold is shown by the rail diamond's square glyph and its accessibility state |
| Status line "Whole layer · property · Source Text Hold · Animation off" | Removed. Key/animation state: diamond glyph (hollow / centre mark / filled; squares for Hold owners) + state description + polite live region. Scope: selection chip. Property name/unit: long-press label and numeric-entry title. Errors/limits: feedback strip |
| Font chip → Fonts popup (Dialog) | Character R1 Font chip → in-sheet Fonts |
| Fonts: search, import, delete, close | Fonts toolbar row (delete uses a bin glyph) |
| Fonts: delete confirmation dialog | Inline confirmation row inside Fonts |
| Fonts: missing / error lines | Same, one line each inside Fonts |
| Fonts: bundled + imported family list | Fonts family list (internal scroll) |
| Fonts: style dropdown (Default / named instances / bundled styles / static note) | Fonts horizontally scrolling style row; current choice ✓, modified preset labelled |
| Fonts: axis rows (scrub, exact, reset) | Fonts axis list; exact entry is an inline row (Default / Cancel / Done) instead of a dialog |
| Fonts: Inherit layer font; Layer base font menu | Fonts bottom row (unchanged actions) |
| Size cell | Character R1 Size (layer px; relative % with a selection) |
| Paint launcher → Paint popup | Character R1 Fill / Stroke swatch pair (solid chip vs ring, labelled "Fill"/"Stroke") |
| Paint: Fill / Stroke colour → picker (Dialog) | Swatch → in-sheet Global Colour Picker, Fill vs Stroke and scope in its title |
| Paint: Inherit fill / Inherit stroke (selection) | "Inherit" button in the colour picker title row (selection scope); also rail ⋮ → Reset value |
| Paint: Stroke width | Character R2 Stroke width |
| Paint: Paint order | Character R3 Paint order two-state toggle (layer-only; selection explains) |
| Paint: requested/effective stroke line (always shown) | Feedback strip, only while clamped: `Requested X px | Effective Y px | SDF limit` |
| Tracking cell | Character R2 Tracking |
| Leading cell / Format → Leading | Character R2 Leading |
| Quick row / Format → Baseline | Character R2 Baseline shift |
| Quick row / Format → H scale, V scale | Character R3 H scale, V scale (three distinct glyphs: size, V scale, H scale) |
| Format → Kerning launcher → Metrics / Off | Character R3 Kerning; tap toggles Metrics ↔ Off |
| Format → Bold, Italic | Character R4 toggles |
| Case cell / Format → Case → four choices | Character R4 Case → one-row choice of four |
| Format → Superscript, Subscript | Character R4 toggles (tap active returns Normal) |
| Format → Ligatures | Character R4 toggle (layer-only) |
| Format / Character controls → Relative size | Header ⋮ → Relative size (existing numeric editor) |
| Format / Character controls → Manual kerning; "Reset selected manual spacing" | Header ⋮ → Manual kerning (existing numeric editor; its action row has Reset selected manual spacing) |
| Character controls popup (all of the above + Fonts/Paint links) | Deleted: every item is on the face or in the menu |
| Format popup | Deleted |
| L / C / R alignment | Paragraph R1 |
| Justify launcher → four justify choices | Paragraph R1 Justify → one-row choice |
| Justify popup: Inherit layer alignment | Same choice row: Inherit |
| Justify popup: Layer base alignment menu | Same choice row: Layer base ▸ → ‹ / base L / C / R (layer-only) |
| Paragraph controls popup (7 alignments, launchers, Inherit, More/base) | Deleted: Paragraph R1 + Justify row |
| Indents launcher → three indent fields | Paragraph R2 Left / Right / First-line indent |
| Spacing launcher → before / after | Paragraph R3 Space before / after |
| Box launcher / cell → numeric, Auto, Fill composition | Paragraph R3 Box width: value = exact entry; icon tap = one-row Auto (0) / Fill composition |
| Direction launcher → LTR / RTL + note | Paragraph R2 Direction toggle; RTL shows "stored only; RTL rendering unsupported" in the feedback strip |
| Numeric exact-entry dialog (field, Fine, −, +, Cancel, Done, validation, stroke note) | In-sheet two-row strip beside the cell; validation in the feedback strip |
| Large-font "Open … controls" fallback | Labelled 3-column grid of the same cells |
| Header ⋮: Character controls / Paragraph controls | Removed (everything is on the face) |
| Header ⋮: Animate · F3 unavailable | Removed (Animate tab remains visibly inert) |
| Header ⋮: Explode Text | Header ⋮ → Explode Text (existing dialog unchanged) |
| Header ⋮: Reset text style; Reset paragraph style | Header ⋮ → one "Reset {Character|Paragraph} style · {scope}" for the current tab; one undo entry; disabled on Animate |
| Header ⋮: Clear selection | Selection chip × (and Back) |
| Header ⋮: Layer tools | Back (top-left arrow or system Back) returns to the layer inspector grid |
| Header ⋮: Done editing text | Rail ✓ (unchanged Done behaviour) |
| Format cell ⋮ glyph (third ⋮) | Removed with Format; rail animation-actions glyph is now dots + diamond |
| Rail Prev / Diamond / Next / Graph / 3D / Done | Unchanged positions; diamond shows key state |
| Rail ⋮: Reset value, Enable / Freeze, Reset animation…, Hold, easing | Unchanged; Reset animation confirms in a one-row strip above the rail instead of a dialog |
| Back: cancel mode, then clear selection, then the parent | Back leaves the innermost state (Explode → draft → confirm → base row → choice row → Fonts); otherwise returns to the layer inspector grid and clears the selection. The top-left arrow dispatches the same Back |
| Animate tab | Unchanged inert placeholder with reason |

Nothing in the TEXT UI 4 inventory was removed without a destination above.
