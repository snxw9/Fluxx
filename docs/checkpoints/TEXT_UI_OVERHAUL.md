# Edit Text overhaul implementation — 2026-10-09

The plan is approved with the user's amendments: velocity-accelerated scrubbing with centralized tuning; Character row 2 Tracking/Leading/Case/Format and standard Stroke/H/V/Baseline; Paragraph row 2 Box/Indents/Spacing/Direction; virtualized independently scrolling Fonts lists; bounded accessibility popovers; inert Animate and disabled Graph/3D. No inspector-sheet scrolling. Source implementation is device-unverified throughout. T1–T4 device reports belong to the user, not these UI changes. T5 remains unsupported.

## Commit 1: foundations

Typed tab/focus/mode/scope and storage ownership, captured exact numeric draft, original logical character counting, range-relative values and delta edits, signed/snapped key origins and existing-key interpolation, model-bound validation, centralized scrub acceleration/fine/detent/haptic tuning and source-level layout budget are implemented. No renderer or protected API changes. UI integration follows in commits 2–4; this commit alone does not change the old surface.

Source budget audit: existing text parent consumes 16dp vertical padding + 36dp text header + 4dp top content padding = 56dp. All are removable in text mode. Commit 2 removes them only in text mode: final non-removable parent chrome **0dp**, 8dp horizontal margins, 344dp usable width at 360dp. Budget: Source 48 + scope 32×fontScale + tabs 40 + body remainder + separator 4 + rail 48; at scale 1 and 268dp body=96dp, at 328dp body=156dp. JVM layout assertions cover both heights × font scales 1/1.3/2; bounded window fallback uses overlays if body<96dp. This is source arithmetic, not measured device layout or visual acceptance.

Foundation checks: app Kotlin Debug/Release and all 253 JVM tests passed, zero failures/errors/skips (`tools/text/results/text-ui-1.log`). Host production is unchanged; the full host selftest passed again at this gate. No APK packaging/device run. Asset delta zero; code-size delta unmeasured. Prepared evaluation is untouched.

Exact user-run list for commit 1: run the new `TextInspectorStateTest` and existing text edit/paragraph/font tests in Android Studio; existing UI should retain behavior because foundations are not wired yet. Check previous named-instance fix, one-step text undo, source-key round-trip and preview/export parity. Numeric interaction and layout acceptance must wait for the integrated surface and the full list below; compilation is not device acceptance.

## Shared exact user-run list for integrated commits

The numbered list in [the approved plan](../architecture/TEXT_UI_OVERHAUL_PLAN.md#10-exact-user-run-manual-acceptance-list-future-implementation) is the complete acceptance list. Per-commit applicable subsets and deviations will be recorded here, with compile/JVM/host results after each gate. No Android performance, accessibility or pixel-parity claim is made from source-only checks.

## Commit 2: monochrome three-tab shell

Character/Paragraph/Animate replace the inspector's top-level text pages. Source/scope/focus rail, amended primary grids, standard quick rows, bounded property popovers, physical alignment icons, Case and other cached monochrome ImageVectors are wired. Parent text-mode chrome is removed (0dp remaining, 344dp inner width at 360dp); other layer-tool layouts are unchanged. Graph and 3D are visibly disabled with reasons; Animate has no animator controls. Old page source files remain until the final inventory audit/removal in commit 4. Fonts temporarily uses the existing imported controls; the unified bundled/imported popup lands in commit 3. Numeric/colour/scrub/IME behavior is integrated and polished in commit 4; this intermediate shell is not final acceptance.

Gate passed: Kotlin Debug/Release, app instrumentation-source compilation and all 253 JVM tests (`text-ui-2-final.log`); full host selftest passed. First compile found incorrect selection range accessor names, fixed before the passing gate. `TextInspectorLayoutTest` compiles real Compose bounds assertions for 360dp, both heights and scales 1/1.3/2; it has NOT run on device. Existing Compose-rule deprecation and an existing unnecessary-safe-call warning remain. No native/product font assets changed; APK asset delta zero, code delta unmeasured. No protected APIs or prepared evaluation changes.

Exact commit-2 user-run list: (1) run `TextInspectorLayoutTest` in Android Studio on the user's device; (2) compare 268/328dp portrait and landscape at font scales 1/1.3/2, verify three tabs and amended grids; (3) open every property popover and check icons/long-press labels/physical alignments, Case and standard shortcuts; (4) verify the scope header, logical selection count and shared focus rail, disabled Graph/3D and inert Animate; (5) confirm no inspector scrolling, no duplicate parent header, layer-tools/Done exit routes, and unchanged preview move/box gestures. Use the complete integrated list after commit 4; these are source-only checks until the user runs them.

## Commit 3: unified Fonts popup

One searchable popup contains bundled/imported families, collection faces, real styles/named instances, complete preset/default coordinates, modified-instance feedback, inherited font, import/delete confirmation and missing-request feedback. Family and axis lists are separately virtualized/scrollable. Static imported faces explain that alternate styles are separate faces. Axes have icon scrubbing with bounded velocity acceleration/default detents, frame-conflated previews, one captured Source Text transaction per drag, exact entry and default reset. The font chip marks missing identities. Font name previews use ordinary UI text: optional font-rendered thumbnails are deliberately omitted to avoid foreground work; no font parsing/shaping occurs in UI drawing. The original worker, cache and fallback paths are unchanged.

Gate passed: Kotlin Debug/Release, app instrumentation-source compilation and 253 JVM tests (`text-ui-3.log`). Host CFF variable-font proof passed outline/advance changes, clamping, independent FT/HB agreement and prepared transport. Existing named-instance JVM regression remains green. Asset delta zero; no APK packaging or devices. Commit 4 finishes transaction-ready gating, restoration and interaction/accessibility polish before acceptance.

Exact commit-3 user-run list: (1) search and choose each bundled family/style and imported static/variable/collection face; (2) choose named presets and Default without moving an axis, verify all coordinates, modified preset labeling and inheritance; (3) scrub axes slowly/quickly, exact-enter/reset values, cancel gestures and verify one undo/redo plus save/reopen/copy/paste; (4) import, cancel import, delete with confirmation, reopen missing identities and re-import; (5) verify independent virtualized family/axis scrolling, large-font and short-window bounds, no main-sheet scroll, source-scope preservation, cold/warm preview/export parity and timing logs. These checks are device-unverified; finish the full acceptance list after commit 4.

## Final inventory audit (performed before removing old pages)

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

## Commit 4: interaction, restoration and inventory completion

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

## Rework commit 1: tab faces (follow-up to the overhaul) — 2026-11-18

User review on the A16 found the overhaul cluttered: the same controls appeared on the tab face, in Character controls and in Character formatting; the Paragraph popup repeated the whole tab; popups covered the edited layer. Implemented in source, device-unverified:

- Deleted the aggregate popups (Character controls, Character formatting/Format, Paint, Paragraph controls, Indents/Spacing launchers). Every common control sits directly on its tab as a 4-column grid of 48dp cells. Character: Font chip · Size · Fill/Stroke swatch pair / Tracking · Leading · Baseline · Stroke width / H scale · V scale · Kerning (tap toggles Metrics/Off) · Paint order (toggle) / Bold · Italic · Case · Superscript · Subscript · Ligatures. Paragraph: Left · Centre · Right · Justify / Left · Right · First-line indent · Direction (storage only) / Space before · Space after · Box width. Animate stays an inert placeholder.
- Remaining overlays are only real choices: Case (one row of 4), Justify (one row: four justify variants, Inherit, Layer base ▸ which swaps the row to base L/C/R), Box width (one row: Auto / Fill composition; tap the value for exact entry), the Fonts sheet and the Global Colour Picker. Exact numeric entry is a two-row strip beside its cell.
- No overlay can hide the edited layer: the Edit Text sheet is docked below the action bar and never overlaps the preview, and every overlay is laid out inside the sheet's bounds (no Dialog windows, no viewport pan). Fonts is capped at max(40% of the window, 240dp) and never taller than the sheet; its family, style and axis lists scroll internally. Exact axis entry and font deletion confirm inline. Reset animation confirms in a one-row strip above the rail.
- One fixed Edit Text height sized for the 4-row Character face, independent of Preview Expansion. At font scale >1.2 the same cells reflow in order into a labelled 3-column grid (6 Character rows, 4 Paragraph rows). If the window cannot grant the height, whole rows page from a `n/m` button in the tab row; the sheet never scrolls.
- Stroke clamp feedback is non-modal and shown only when clamped, in the feedback strip: `Requested X px | Effective Y px | SDF limit` (shorter forms at 1.3/2.0; the full text is the accessibility description).

Gate: app Kotlin Debug/Release, instrumentation-source compilation and all 264 JVM tests, zero failures/errors/skips (`text-ui-rework-1-gate.log`). Host build and selftest passed. No APK packaging or device run. Commit 2 removes the status line (sheet becomes 344dp) and finishes the menu, Back, status and icons; the complete inventory and user-run list are recorded there.

## Rework commit 2: menu, Back, status and icons

Implemented in source, device-unverified.

- **Menus.** The ⋮ inside the grid is gone (it was the Format cell). Two remain: the rail's animation-actions menu (glyph now dots + diamond, so it no longer matches the header ⋮) and one header ⋮ containing exactly **Explode Text | Reset {Character|Paragraph} style · {scope} | Manual kerning | Relative size**. Reset resets the current tab's formatting for the current scope (selection, or whole layer) as one format/paragraph transaction (one undo entry); it is disabled on Animate. Removed: Character controls, Paragraph controls, "Animate · F3 unavailable", Done editing text, Clear selection, Layer tools and the separate paragraph reset.
- **Status.** The status line and the always-visible Hold label are deleted. The rail diamond shows state by glyph (hollow = off, centre mark = animated between keys, filled = on key; square glyphs for Hold owners Source Text / paint order / ligatures), with a full state description, a polite live region for announcements and the state in its long-press label. A scope chip ("N characters" ×) appears only while a selection exists; tapping it clears the selection.
- **Back.** One state machine (`TextInspectorBack`) serves system Back, Escape and the editor's top-left arrow (which, while Edit Text is open, dispatches system Back and announces "Back: close the open text control, or return to layer tools"). Order: Explode dialog → cancel numeric/source/colour draft → reset-animation confirmation → base-alignment row (returns to the Justify row) → choice row → Fonts → otherwise return directly to the layer inspector grid (Transform, Blending & Opacity, Edit Text…), clearing any selection. Previously the arrow deselected the layer and closed the inspector. The ✓ keeps its Done behaviour.
- **Icons.** Size = small T + large T; vertical scale = tall T + vertical arrows; horizontal scale = wide T + horizontal arrows (all three were "A + arrow" variants). Relative size is a text menu item. Audit fixes: Source (was ragged lines = Align left) is now T + text cursor; Delete font (was ✕ = Cancel) is a bin; Inherit (was the Reset arrow) is a chain link; Stroke colour (was the Stroke width glyph) is a hollow A; animation actions (was ⋮ = header menu) is dots + diamond. A JVM test asserts that no two visible glyphs are identical except deliberate aliases (Case/Normal, Direction/LTR, Fill/Paint, Cancel/Close, Key/Animate). Remaining look-alikes, reported not changed: Tracking vs Kerning (both A V pairs; arrows out vs in; Kerning cell also shows Metrics/Off text); Align left vs Justify last-left (differ only in the upper lines; labelled choice row); Leading vs Space before/after (lines + arrows; different arrow placement). Long-press and accessibility labels are kept on every control.

### Gate

`text-ui-rework-2-gate.log`: app Kotlin Debug/Release, instrumentation-source compilation and all **270** JVM tests, zero failures/errors/skips. The first attempt failed only on a missing test import (`AnimPropertyType`), fixed before the passing run. Host build and selftest passed. Existing Gradle-option, pre-existing safe-call and Compose test-rule deprecation warnings remain. No APK packaging or device run; `TextInspectorLayoutTest` (layout bounds at 360dp × font scales 1/1.3/2, selection chip, no status/Hold text, measured stroke-clamp width) compiles but has not executed.

### Deviations

1. **Sheet height 344dp, not 328dp.** Source 48 + tabs 40 + 4 × 48 rows + feedback 16 + rail 48 = 344 at font scale 1 (no parent chrome). This is the "one fixed height sized for the 4-row face"; it is 16dp taller than the old standard and 76dp taller than the old expanded 268dp. At 1.3 / 2.0 the labelled 3-column face needs 6 rows: 444.8 / 456dp. The editor clamps the sheet to leave the minimum preview (96dp, or 48dp in very short windows); when that clamp bites, whole rows page from the tab row (`n/m`) rather than scrolling.
2. **Font chip is 1.8 columns, Fill/Stroke 1.2** (spec: 2 + 1). With two swatches in one 86dp cell each target would be 43 × 48dp, below the project's 48dp standard. At 1.8 / 1.2 each swatch is 51.6 × 48dp (57 × 48dp in the 3-column fallback); the font chip is 154.8dp.
3. **Numeric exact entry is two rows** (field row + Fine / − / + / [Reset spacing] / Cancel / Done), because those 48dp targets plus a usable field cannot fit one 344dp row. It sits beside its cell, never on it when the page has room.
4. **Colour picker and Fonts are taller than one row** (Fonts was an allowed exception; the picker needs about 290dp). Both are drawn inside the sheet and cover the face while open; they never cover the preview.
5. **Box width choices** open from the Box icon zone (the value still opens exact entry). **Direction** is a two-state toggle on the face instead of a popover; RTL shows "stored only; RTL rendering unsupported" in the feedback strip.
6. **Justify row** has six segments (four justify variants, Inherit, Layer base ▸); Layer base swaps the same row to ‹ / base L / C / R. This keeps Inherit layer alignment and the layer base alignment, which had no other home.
7. **Stroke clamp text** is `Requested X px | Effective Y px | SDF limit` at scale 1 (the full "SDF spread / size limit" is the spoken text). Shorter visible forms are used at 1.3 (`Req X | Eff Y px | SDF limit`) and 2.0 (`Req X | Eff Y`) to stay on one line. Proven envelope: requested ≤ 999.99, effective ≤ 99.99–99.99; estimated widths 332 / 320 / 320dp of 344dp (JVM, 0.52em advance); the instrumented test measures real glyph widths. Other feedback messages ellipsize to one line, with the full text as the accessibility description.
8. **Explode Text stays a centred dialog** (existing compact dialog, not changed by this request). Reset-animation, axis-entry and font-delete confirmations moved into in-sheet rows.
9. The old 6-column wide-window variant was dropped: the 4-column face stretches, since nothing needs extra columns any more.

### Assumptions

- "Doesn't hide the layer" is met by containment: the Edit Text sheet sits below the action bar and timeline anchor and never overlaps the preview, so overlays bounded by the sheet cannot intersect the layer's on-screen bounds. No viewport pan was added and the document is never changed.
- **Preview Expansion conflict:** while Edit Text is open the preview expand toggle still flips its saved state but the sheet height doesn't change, so the toggle appears to do nothing until Edit Text closes. Nothing else depends on it in this mode.
- Reset {tab} style keeps the existing semantics: Character resets run formatting only (font inheritance, size factor, scales, overrides, deltas, manual spacing, faux, case, script, kerning); layer base values, animation, Source Text, fonts and transforms are untouched. Paragraph resets paragraph attributes of the scope.
- Hardware Escape does nothing when no transient state is open (only the arrow / system Back leave Edit Text).
- Inside Fonts, Back closes the whole Fonts sheet, discarding an open inline axis-entry or delete-confirmation row with it.

### Exact A16 user-run list

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

## TEXT UI 5: single-header page, slider popovers, limits, log scale ruler

The pass-2 surface was rejected on the A16 (cluttered, flat, typing-first, and the numeric IME resized the window and shrank the preview). This pass replaces it in one commit. Implemented in source, device-unverified. No `PreviewController`, gesture-protocol, `EditorAction`, batch/draw or schema change. The only model constant touched is `TextLimits.MAX_SIZE` (500 → 1000 px; see Deviations).

### Frame and navigation

- **Edit Text is an ordinary inspector page.** Same `ElementInspectorSheet` frame (12dp sides, 8dp top/bottom), same height and same font scaling as Transform. The fixed 344dp text sheet, its separate height path in `EditorScreen` and the "Preview Expansion ignored" deviation are gone: every page uses `InspectorSheetBudget.height(expanded, fontScale)`.
- **Shared heights rose to 308 / 344dp** (expanded / standard), for all pages. Numbers: the Character tab needs 6 tile rows (row 1, row 2, 3 style rows, bottom bar). Frame 16 + header 40 = 56dp of chrome. Compact (sheet < 320dp, 40dp targets): 6 × 42dp pitch = 252dp, total 308. Standard (48dp targets): 6 × 48 = 288dp, total 344. The old budgets leave 268 − 56 = 212 < 252 and 328 − 56 = 272 < 288, so they provably cannot hold the tab; both rise by the minimum (+40 / +16dp). Preview/timeline arithmetic is otherwise unchanged (the same clamp leaving a 96/48dp minimum preview, now applied to every layer type).
- **One header row:** `←` | Character | Paragraph | Animate | (⚠ only while there is an error or message; tap shows it) | ⋮. Removed: second chrome row, Layer tools text, duplicate/delete icons, ✓/Done everywhere (rail, numeric entry, colour picker, Fonts, and the dead fill-colour "Done" row in `ElementInspectorSheet`), per-tab ⋮ and the feedback strip. The one ⋮ holds exactly Explode Text, Reset {tab} style · scope, Manual kerning, Relative size (the last two open the slider popover anchored on ⋮).
- **Back** (`TextInspectorBack`): Explode dialog → reset-animation confirmation → popover (the Layer base row first returns to Justify) → Source typing commits → sub-page (Fonts / Colour) returns to the tab → landing grid (selection cleared). System Back, Escape and the editor's top-left arrow share it. Popovers are focusable popups, so system Back closes them before the page sees it.
- **Rail:** the same `InspectorUtilityRail` as Transform/Opacity (now `internal`, with optional key glyph/state, extra menu items and an anchored confirmation). The diamond keeps hollow / centre-mark / filled and the Hold squares and acts on the focused text property; Graph disabled; property-options ⋮ = Reset {property} value, Enable/Freeze, Reset animation… (anchored confirmation), Hold or easing. Prev/Next-key buttons, ✓ and the 3D icon are removed from the text rail (`show3D=false`).
- **Prev/Next follow focus:** `TextFocus.owner` = the open popover's property, otherwise the last touched control (the colour popover uses its Fill/Stroke target). The panel reports it through the existing `onPropertyFocus`; the action bar's Prev/Next already route through `ContextualNavigation` with that property. The only fix needed was making an open popover count as focus; `TextFocusNavigationTest` covers it.

### Final tile inventory

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

### Slider popovers and typing

- Tapping a numeric tile opens a small popover anchored directly above it (below if there is no room), scaled out from the anchor and clamped horizontally with 8dp margins (`PopoverPlacer`). It is never centred and never a sheet. Contents: `[↺]  slider ——●  [value]`. Tap outside or Back closes it.
- The value is tappable: it becomes an inline field (select-all, numeric IME). IME Done or focus loss / tap outside commits; Escape cancels; Back (which first hides the keyboard) cancels (`TypedEntry`). Typed values clamp to the typed range; every value clamps to the hard cap.
- Reset returns to the property default and respects inheritance: run values return to neutral (relative size 100%, deltas 0) and layer values to the model default (e.g. 72px).
- Font size snaps to the 23-rung ladder (8 … 300); Leading follows the same ladder plus 0. Other sliders are continuous with a soft snap (1.2% of span) at 0 and the default; detents every 10 px / 10 thousandths of an em / 50% / 5 px of stroke.
- Haptics go through the View, so the system touch-feedback setting is respected: a press when the thumb is grabbed, and a tick per ladder rung or detent bucket and on crossing 0/default (≥ 35ms apart).
- One undo per drag (begin on first move, finish on release) or per typed commit/reset; live preview is conflated to one publish per frame through the existing `onPayloadPreview` path.
- Icon drag-scrubbing and every −/+ button are gone (`TextNumericGesture.kt` deleted).

### Keyboard

`EditorScreen` no longer applies `imePadding()` and no longer re-lays out the editor while typing: the action bar and timeline stay and the preview height is unchanged. With the existing edge-to-edge window this is the adjustNothing equivalent, scoped to the editor. Other screens keep their own inset handling and the manifest is unchanged. Only the focused popover or the inline text editor lifts above the keyboard: popovers are positioned using the host window's IME height (`rememberVisibleBottom`, `LocalHostImeBottom`). The Transform/Opacity value boxes now open the same anchored entry popover.

### Fonts page and colour

- **Fonts** is a page in the same frame (`←` returns to the tab). Left column: search, import, delete (imported families, inline confirmation), and the families, with bundled names drawn in their own face (Sans/Serif/Mono; imported names use UI text). "Default" merges Inherit and Layer base: choosing it inherits, and its right column lists the layer base fonts. Right column: styles / collection faces / named instances (a modified preset is labelled). Variable faces add one row of axis tiles; each opens the slider popover (frame-conflated preview, one transaction per drag, typed entry, reset to default). Library hashing, isolated-process import and missing-identity handling are unchanged.
- **Colour** opens from the Fill/Stroke tile as an anchored popover: Fill/Stroke target, palette swatches (the active palette or 12 defaults; a swatch keeps the current alpha), an alpha slider, Inherit (with a selection) and "Custom…", which swaps to the Colour page (global picker incl. eyedropper) in the same frame. There is no Done: edits apply live, leaving the page commits once, and an untouched page cancels.

### Limits as implemented (`PropertyLimits`)

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

### Scale ruler (Transform)

`LogScaleRuler` replaces the linear ruler (0.8dp per 1%, clamped 5%–1000%).

- **Look:** a rounded `SurfaceHigh` strip with a hairline border and a fixed bright centre needle with a notch; ticks scroll under it. Majors at 1/2/5 × 10ⁿ % are labelled (0.05 … 10k); minors sit at the other integer multiples; 100% is taller and brighter; ticks fade and shrink towards both edges. A large monospace readout sits above (`175%`): tap to type (anchored entry, ±50000%), double-tap to reset to 100%. The link toggle sits at its right.
- **Mapping:** `scale = base · 2^(dx / 96dp)`, relative to the value at drag start, with a velocity gain from 1× (≤ 400dp/s) to 2.5× (≥ 2400dp/s). Range 0.05%–10000%; typed values beyond it pin the needle to the end, and a negative scale keeps its sign while the ruler moves its magnitude.
- **Detents:** a light tick crossing 25/50/100/200/400…, a strong tick crossing or landing on 100%, and a soft snap of ±0.03 doublings (≈ ±2%) around 100% that a continued drag passes through.
- **Inertia:** flings above 600dp/s continue (τ = 300ms) and stop on touch; one undo per drag, inertia included.
- **Link:** linked mode keeps the X:Y ratio captured at drag start (Y now within the ±500× hard cap); unlinked shows Width and Height rulers.
- **Drawing:** a single `Canvas`; tick geometry and label layouts are prepared once, so nothing is allocated per frame. A `@Preview` is included. The W/H typed boxes are replaced by the ruler readouts; Flip H/V stays in the layer menu.

### Deviations

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

### Tests

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

### Exact A16 user-run list

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
