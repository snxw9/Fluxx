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

## TEXT UI 6: fit layout, icon family, compact rail, colour popover picker, ruler fix, SDF tiers

Device feedback on TEXT UI 5 (A16): the keyboard, popovers, 10000% scale reach, rotation and the Fonts page are good; everything below still needed work. Implemented in source in one commit, **device-unverified**. Scope rules held: no change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract (`textLayer`/`flush`/`finish` arguments are unchanged; two tier calls were added before preparation) or the document schema. Renderer/atlas internals changed only for §8. The Fonts page is untouched apart from its three icons now coming from the shared family.

### 1. Sheet height: back to 268 / 328dp

- `InspectorSheetBudget` is 268dp (expanded preview) / 328dp (standard) for **every** page, font-scaled exactly as before TEXT UI 5. The `EditorScreen` clamp is again applied only to text layers, as originally, so preview and timeline sizing match pre-TEXT UI 5 behaviour.
- Edit Text budget: frame 16 + header 40 = 56, so the body is **212dp at 268** and **272dp at 328**. It fits by using smaller tiles, not by raising the height.

### 2. Edit Text layout (positions and heights)

Geometry comes from `TextInspectorBudget.forBody` (`TextFaceMetrics`), which is the same arithmetic the composable lays out.

**Vertical layout:** body = top + 8 + middle (3 tiles + 2 × 4) + 8 + bottom (1 tile). Each 8dp group gap holds a 1dp hairline at its centre. Half of each gap belongs to each neighbouring band, so every row is a full touch target.

**Horizontal layout:** the content is 6 equal cells: two 3-column blocks plus a 4dp spacer. Every touch target is a full cell (tile + gap). Tiles are drawn inset by 2dp, so the visual gap is 4dp inside a block and 8dp between blocks. Block edges, bottom-bar edges and the top row share the same columns.

| | Compact 268 | Standard 328 |
|---|---|---|
| Body | 212 | 272 |
| Tile height / in-grid gap / group gap | 36 / 4 / 8 | 44 / 4 / 8 |
| Top row | 44: Fill/Stroke tile 28 + switch 16 stacked, no gap; Explode pill 28, vertically centred | 72: tile 44 + 4 + switch 24 |
| Middle (two 3×3 blocks) | 3 × 36 + 2 × 4 = 116 (row touch targets 42 / 40 / 42) | 3 × 44 + 2 × 4 = 140 (50 / 48 / 50) |
| Bottom bar | 36 (touch 40) | 44 (touch 48) |
| Cell width at 360dp (content 292) | 48 (tile 44) | 48 (tile 44) |

If the top row cannot hold a 28dp tile with a 16dp switch under it, the switch moves to the right of the tile (`switchBeside`). That never happens at 268 or 328 with font scale 1. Above font scale 1.2 the page uses the labelled 3-column reflow: 48dp rows, whole rows paged by an `n/m` button above the same bottom bar, all inside the font-scaled 268/328 sheet.

**Character tab**

| Position | Content |
|---|---|
| Top, left block | **Explode Text pill** (rounded, `SurfaceHigh`, icon + "Explode Text", 28dp; opens the existing Explode dialog) |
| Top, right block | **Fill/Stroke dual-colour tile** (block width × 28/44dp) with the **Fill over stroke** switch directly under it (custom 28×16dp, `Role.Switch`, full contentDescription and state) |
| Left block | Stroke width · Tracking · Leading / Horizontal scale · Link (uniform H/V) · Vertical scale / Baseline shift · Kerning (Metrics/Off). The last row is centred, and the spare slot stays empty. |
| Right block | Bold · Italic · Underline / Strikethrough · Superscript · Subscript / All caps · Lowercase · Ligatures |
| Bottom bar | Font chip (2 cells; icon + truncated family; opens Fonts) · Size (ladder popover) · inline **text content** field (3 cells, the right block's edges; selection chip inside) |

**Paragraph tab** (same skeleton)

| Position | Content |
|---|---|
| Top row (full width, 4 cells) | Align left · centre · right · Justify (choice popover as before) |
| Left block | Left indent · Right indent · First-line indent / Space before · Space after (last row centred) |
| Right block | Point/Box toggle · Box width · Direction |
| Bottom bar | Same as Character |

**Removed:** the Small caps tile and control (`TextControl.SMALL_CAPS` is gone; the model and renderer keep `TextCaps.SMALL`), the header ⋮, the Colour page, the "Custom…" entry, and the Canvas dual-colour icon (it is now a Box swatch with real colours). The field above the preview is still the **layer name** and is not merged with the text field.

### 4. Colour popover (picker inside the popover)

- The footprint stays a ~288dp-wide anchored popover from the Fill/Stroke tile (placement clamps to the window with 8dp margins). It has no second page.
- **Row 1 (40dp):** Fill / Stroke target toggle (left) · eyedropper · Inherit (only with a selection) · Reset.
- **Row 2 (96dp):** the saturation/value square (96dp) and hue bar (16dp) are the global picker's own `SaturationValueBox` and `HueStrip` with the `FluxxColor` model. The colour maths is not forked, and hue/saturation survive greys and black. On the right:
  - preview swatch + **hex value** (tap to type in an anchored `InspectorValueField`; RRGGBB keeps the alpha, AARRGGBB replaces it, invalid text applies nothing);
  - **alpha slider** + tappable **%** value (same entry and IME-overlay rules).
- **Row 3 (40dp):** one strip of swatches (active palette or the 12 defaults; a swatch keeps the current alpha). It scrolls horizontally.
- R/G/B sliders are not in the popover (they stay in the global picker).
- Edits apply live, with **one undo per interaction** (`TextColourSession`): one SV/hue/alpha drag, one swatch, one typed hex/alpha, one eyedropper sample, or one reset. There is no Done; Back or an outside tap closes the popover, and an open drag is cancelled.

### 5–6. Rail and menus

- **`InspectorUtilityRail`** (every page) is a tight top-aligned stack of 40dp tiles with 6dp gaps: Keyframe diamond, Graph (disabled), 3D (disabled; only on pages that already showed it, now after Graph), property-options ⋮. Its top edge lines up with the first content row.
  - On Transform/Opacity at 268 the 4-item stack (178dp) does not fit the 158dp content area. Only the two disabled placeholders shrink (to 30dp); the diamond and ⋮ stay 40dp targets.
- **Header:** `←` | Character | Paragraph | Animate, plus ⚠ only when there is an error or message. There is no ⋮.
- **Rail ⋮ is the page's only overflow menu**, in this order:
  1. Reset {property} value
  2. Enable animation / Freeze at playhead
  3. Reset animation… (anchored confirmation)
  4. Hold (discrete) or easing presets
  5. Reset {Character|Paragraph} style · scope (one undo)
  6. Manual spacing… (slider popover anchored on the ⋮; Character tab)
- Relative size is **omitted**: with a selection the Size tile already edits relative run size, and resetting run factors is covered by Reset style.
- **Back** (`TextInspectorBack`): Explode → confirmation → popover (Layer base row → Justify first) → Source commit → Fonts page → landing grid.

### 7. Icon family (`InspectorIcons`)

**Spec:**
- Path-based `ImageVector`s on a 24×24 grid, built once and cached.
- **One stroke of 1.5 units** (it scales with the icon), `StrokeCap.Round` and `StrokeJoin.Round` everywhere.
- Boxes are rounded rectangles (radius ≥ 2 units, built with arcs), and polygons (diamonds, triangles, the cube, play) have rounded vertices.
- Arrowheads are open chevrons (polylines). Letters are simple geometric strokes.
- Only state glyphs are filled: `FILL`/`PAINT` swatch, `KEY_ON`, `HOLD_ON`.
- Drawn size is 22dp in tiles and buttons, 18dp beside text.
- `IconPath` builds the data; OUT/Redo/right/RTL variants are exact x-mirrors.

**Enforced by `InspectorIconFamilyTest`:**
- stroke width, cap and join on every path;
- hollow-unless-state;
- no Canvas-drawn `*Icon` function in `ui/inspector` or `EditorActionBar.kt`;
- no Z-closed shape without curves;
- pairwise distinct glyphs except the declared aliases;
- every face control, category, clip action and rail item resolves to a family icon.

| Icons | Old source | New |
|---|---|---|
| Text controls: FONT, AXIS, SIZE, FILL/PAINT, STROKE, STROKE_WIDTH, TRACKING, KERNING, LEADING, BASELINE, H_SCALE, V_SCALE, BOLD, ITALIC, UNDERLINE, STRIKE, SUPER, SUB, ALL_CAPS, LOWERCASE, LIGATURES, ORDER, SCALE_LINK/LINK_OFF, EXPLODE, SOURCE | `TextInspectorIcons` path strings: angular letterforms, sharp Z-closed boxes, filled A/B, 1.4 stroke | `InspectorIcons` (rounded letters and boxes, hollow; FILL is the only filled one) |
| Paragraph: alignment ×7, INDENT_LEFT/RIGHT/FIRST, BEFORE, AFTER, POINT, AREA, BOX, LTR/RTL | `TextInspectorIcons` (no round caps on alignments, sharp boxes) | `InspectorIcons` + `alignmentPath` |
| Popover and page chrome: BACK, NEXT, CLOSE/CANCEL, RESET, INHERIT, SEARCH, IMPORT, DELETE, WARNING/MISSING, EYEDROPPER (new) | `TextInspectorIcons` (sharp warning triangle and trash) and the global picker's Canvas pipette | `InspectorIcons` |
| Rail: KEY, KEY_ANIM, KEY_ON, HOLD_ANIM, HOLD_ON, GRAPH, CUBE (3D), OVERFLOW (⋮) | `KeyframeVectorIcon`/`GraphVectorIcon`/`OverflowDotsVectorIcon` (Canvas, horizontal dots), `InspectorCategoryIcon(cube=true)` (Canvas, sharp hexagon), `TextInspectorIcons` diamonds | `InspectorIcons` (rounded diamond/hexagon, vertical ⋮) |
| Landing grid: TRANSFORM, TIMING, BLENDING_OPACITY, EDIT_TEXT, EDIT_SOLID, EDIT_FOOTAGE (new), EFFECTS, LAYER_STYLES, TRACK_MATTES (sharp rect), MOTION_BLUR | `InspectorCategoryIcon` Canvas, 1.3dp | `InspectorCategoryIcon` → `Icon(InspectorIcons…)` |
| Transform: POSITION, ROTATION, SCALE (filled arrowheads when active), ANCHOR, LINK/LINK_OFF | `Position/Rotation/ScaleSubModeIcon`, `AnchorSubModeIcon`, `LinkVectorIcon` (Canvas) | `InspectorIcons`; active is shown by tint only. The anchor-cell marker is a Box frame + dot. |
| Sheet header: back chevron, DUPLICATE, DELETE_LAYER, AUDIO | Inline Canvas chevron, `DuplicateVectorIcon`, `DeleteVectorIcon`, `AudioVectorIcon` (Canvas) | `InspectorIcons` |
| Clip quick actions: MOVE_IN/OUT, EXTEND_IN/OUT, TRIM_IN/OUT, SPLIT | `ClipQuickVectorIcon` (Canvas, 1.3dp) | `InspectorIcons` (OUT = mirror) |
| Editor action bar: UNDO, REDO, PREV_KEY, NEXT_KEY, PLAY, PAUSE, LAYER, PASTE, SELECT_ALL | Canvas, 1.3–1.6dp, sharp triangles, alpha-faded back cards | `InspectorIcons` (open chevrons, rounded play) |
| Deleted (unused) | `CloseVectorIcon`, `TimingVectorIcon`, `TransformVectorIcon`, `ColorPaletteVectorIcon`, `OpacityVectorIcon`, `BackChevronVectorIcon`; glyphs DONE, MINUS, PLUS, SMALL, ACTIONS, PREV, RELATIVE_SIZE, MANUAL_KERNING | removed |

Deliberate aliases: FILL = PAINT, CANCEL = CLOSE, DIRECTION = LTR, MISSING = WARNING, LINK = SCALE_LINK, DELETE = DELETE_LAYER. Meanings are unchanged.

### 8. SDF atlas tiers

**Tiers and spread**

| Tier (ppem) | Spread | Encodable distance | Sharp up to (≤ 1.5× magnification) |
|---|---|---|---|
| 48 | 8 | 0.167 em | 72 px/em |
| 96 | 16 | 0.167 em | 144 px/em |
| 192 | 32 | 0.167 em | 288 px/em |
| 384 | 32 (FreeType's limit) | 0.083 em | 576 px/em |

The shader is unchanged and its `fwidth` smoothing adapts per texel. Base-encoded distances (half stroke, softness, faux bold, animator stroke and blur) are multiplied by `tiers::distanceScale(ppem)`: 1 for tiers up to 192, 2 for 384. Edges therefore look identical across tiers.

**Selection** (`SdfAtlasTiers.select`, per text layer, every frame):
- Input: pixels per em = font size × max run scale (relative size × max(H, V) scale) × largest per-glyph animator scale (from the modifier buffer) × layer scale (× canvas projection) × output scale.
- Preview uses the preview target/composition ratio; export uses 1, since it renders at composition size.
- **Hysteresis is 15%:** move up only above a tier's limit × 1.15, move down only below the lower tier's limit × 0.85.
- A tier is skipped if its spread cannot encode the layer's stroke + softness + bold + 0.01 em. For example, a 0.1 em half stroke caps the layer at 192.
- Inactive layers use 48.

**Generation:**
- One R8 texture array holds every tier, keyed by (font, glyph, ppem). Each `SdfGlyph` carries its ppem and spread, and the mesh sizes quads from them.
- Tier glyphs are rasterised by a second bundled-font `FontManager` (`acquireTierCatalog`) with its own FreeType library and lock, on a worker thread. The render thread never waits on them.
- **Preview fallback:** the highest tier whose glyphs are all ready is used (`tiers::resolve`; 48 is always available), so nothing flickers. When the sharper tier is ready, the next preparation re-collects (`needsCollect`).
- After a settled preview frame (not streaming playback), `renderPreview` waits up to 1.5s (cancellable by any new request) and redraws once.
- **Export** generates tier glyphs inline (deterministic).

**Memory cap (all tiers combined):**
- Device atlas: 6 × 4 MiB pages = **24 MiB** (low-RAM: 3 pages = 12 MiB).
- Host staging buffer: the same size, during uploads.
- CPU tier-glyph cache: **16 MiB** (8 MiB low-RAM).
- **When the atlas cannot pack:**
  1. Glyphs not needed this frame are evicted, highest tier first, then least recently used.
  2. If that is not enough, the layouts on the highest tier step down one tier until they fit (the cap holds until their wanted tier changes).
  3. Only a base-tier overflow raises the existing capacity error.
- Kotlin pre-degrades with the same order using a byte estimate.
- The CPU cache evicts in the same order and never drops a glyph touched this frame.

**Limits (documented, not hidden):**
- Above 576 px/em the 384 tier is magnified. Corner rounding is about em/384: 2.6 px at 1000 px, 13 px for 100 px text at 50× (5000 px/em). That is 0.26% of the em, versus 2% at 48 ppem. No MSDF in this pass.
- Imported-font text and per-character animation catalogue glyphs stay at 48 ppem, because the isolated font worker produces their bitmaps at the base tier.
- Tier choice is per layer (its largest glyph scale), not per glyph.

### 9. Scale ruler

- **Invisible ruler fixed:** the strip used `weight(1f, fill = false).heightIn(40, 56)` inside a column with no remaining height and measured to zero. Each ruler is now a fixed readout row (24dp × font scale), a 4dp gap and a fixed **48dp strip**, with no weights.
  - Linked: 76dp. Unlinked Width + Height: 156dp.
  - That fits the Transform content area: 158dp at 268, 200 at 328, 215.6 at 328 × 1.3, 392 at × 2.
  - `RailAndRulerLayoutTest` checks every combination; there are two `@Preview`s, including the unlinked pair in a 158dp box.
- The link toggle sits in the readout row (40 × 24dp).
- **Direction:** the tape follows the finger. `travel = −dx · gain / 96dp`, and inertia uses the same sign, so dragging right decreases the value and dragging left increases it.
- Unchanged: log mapping, gain, inertia, the 100% snap and detents, ±50000% typed, the 10000% ruler maximum, and Flip in the layer menu.

### 10. Other inspector pages

- Name row: 40dp in compact sheets, 48dp otherwise; duplicate/delete targets follow it (they were fixed at 48dp). This gives Transform/Opacity 158dp of content at 268.
- Section tab padding is now the `InspectorChromeBudget` constants, so content heights are arithmetic rather than estimates.
- Rail-to-content gap is 4dp on Transform, Opacity and Edit Text (Opacity used 8).
- The scale ruler fix above.
- Shared icons on Transform, the landing grid, the quick action bar, the action bar and the Edit Solid picker's eyedropper.
- Timing and Audio needed no change (no rail; 2–3 slider rows fit 158dp).

### Deviations

1. **One shared texture array for all tiers** instead of one lazily created array set per tier. It keeps a single descriptor, pipeline and draw call per layer, so the batch/draw contract is unchanged, and makes the combined memory cap a single number. Per-tier LRU is the eviction order (tier, then last use).
2. **384 ppem keeps spread 32** (FreeType's maximum), so its relative distance range is half that of the other tiers. Distances are rescaled ×2, and layers whose stroke/bold needs more range stay at 192.
3. **Imported fonts and per-character catalogue glyphs stay at 48 ppem**, and **tier selection is per layer**, using the largest animated glyph scale. See Limits.
4. **Preview upgrade without touching `PreviewController`:** a settled frame waits up to 1.5s (cancellable) and redraws once. During streaming playback the sharper glyphs appear in later frames instead of stalling playback.
5. Atlas pages rose from 4 to 6 (2 to 3 on low-RAM) so tiers have room. The cap is stated above.
6. **Paragraph middle:** both blocks have at most 2 rows, so the rows are centred vertically in the middle band rather than leaving an empty third row.
7. **Rail on Transform/Opacity at 268:** the disabled Graph/3D placeholders shrink to 30dp, because 4 × 40 + 18 = 178 > 158. Interactive items stay 40dp.
8. **Relative size** is not in the rail menu (see §5–6).
9. **Swatch strip:** 28dp swatches in a horizontally scrolling strip. Twelve swatches at a 40dp pitch do not fit 288dp, and the popover footprint was kept.
10. The **Fill over stroke** switch and the scale **link** toggle have a 16–24dp tall touch area (the spec allows a small switch). Everything else is ≥ 40dp.
11. Icons are drawn at 22dp in tiles and buttons and 18dp inline beside text (chips, pill, text field).
12. The colour **eyedropper closes the popover** first so the preview can be tapped. The sample applies as one undo.
13. `TextFontsPopup.kt`: only the icon object changed (`TextInspectorIcons` → `InspectorIcons`); its layout and behaviour are untouched.
14. Tests whose structure changed:
    - `TextInspectorChromeTest`: no Colour page; header menu → rail menu; Explode pill; heights 268/328.
    - `TextInspectorFaceTest`: new face structure and arithmetic.
    - `TextInspectorStateTest`: the Colour-mode codec case was removed.
    - `ScaleRulerMathTest`: drag/inertia sign.
    - androidTest `TextInspectorLayoutTest`: new tags, body 212/272.

### Gate

- Compiles: `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, `:core-engine:compileDebugKotlin`, and the native `:core-engine:externalNativeBuildDebug` and `externalNativeBuildRelease`.
- JVM: `:app:testDebugUnitTest` ran **316 tests (291 previous + 25 new)**, with 0 failures, errors or skips.
  - New suites: `InspectorIconFamilyTest` 5, `SdfAtlasTiersTest` 8, `TextColourPopoverTest` 6, `RailAndRulerLayoutTest` 4. `TextInspectorFaceTest` went from 8 to 10.
- No APK packaging and no device run. One pre-existing safe-call warning in `CompositionRenderer` remains.

### Exact A16 test list

1. **Sheet height identical to the original on every page.** Compare against TEXT UI 4 screenshots or measurements: the sheet top is the same on the landing grid, Transform, Timing, Blending & Opacity, Edit Text and Edit Solid. The preview and timeline match pre-TEXT UI 5 sizing in both preview sizes (268 / 328dp) and after toggling Preview Expansion.
2. **Character tab:**
   - The top row shows the Explode Text pill (left) and the Fill/Stroke tile with the Fill-over-stroke switch directly under it (right).
   - Two 3×3 blocks: Baseline and Kerning are centred in the last left row, and there is no filler tile.
   - The bottom bar has the font chip, Size and the text field on the same column edges.
   - There are no empty or crowded areas at 268 and 328, and every tile responds to a tap anywhere in its cell.
3. **Paragraph tab:** alignment row on top, indents/spacing (left) and Point/Box · Box width · Direction (right) centred in the middle band, then the same bottom bar. There is no Explode pill. Small caps appears on neither tab.
4. **Rail is compact on every page:**
   - Diamond, Graph, (3D), ⋮ are stacked at the top with 6dp gaps, and the top lines up with the first row of content.
   - At 268 on Transform/Opacity the Graph/3D placeholders are shorter but the diamond and ⋮ are full size.
5. **Rail ⋮ on Edit Text:**
   - Items: Reset {property} value, Enable/Freeze, Reset animation…, Hold/easing, Reset {tab} style · scope, Manual spacing…
   - Manual spacing opens a slider popover anchored on the ⋮.
   - There is no ⋮ in the header; ⚠ appears only with an error.
6. **Explode pill** opens the Explode dialog. Back closes it first.
7. **Colour popover, everything without leaving the popover:**
   - Switch Fill/Stroke.
   - Drag the SV square and the hue bar (hue survives dragging to grey or black).
   - Drag alpha, and tap the % to type a value.
   - Tap the hex to type RRGGBB and AARRGGBB; invalid text changes nothing.
   - Eyedropper: the popover closes, tap the preview, and the colour applies.
   - Tap swatches (scroll the strip), use Inherit with a selection, and Reset.
   - Each interaction is exactly one undo, there is no Done, and Back or an outside tap closes it.
   - At both screen edges it stays on screen with ~8dp margins.
8. **Scale ruler is visible at both heights** (268 and 328, font scale 1, 1.3 and 2), linked and unlinked: the strip has ticks, labels and the needle.
   - Dragging **right decreases** the value and dragging **left increases** it; flings continue in the same direction.
   - Unchanged: 100% snap and detents, 10000% reach, typed ±50000%, link toggle in the readout row.
9. **Icons are consistent** across Transform (Position, Rotation, Scale, Anchor, link), Opacity, Timing, the landing grid, Edit Text (both tabs, popovers, Fonts page chrome), the rail, the sheet header (back, duplicate, delete, audio), the clip quick action bar and the editor action bar:
   - same stroke weight, rounded ends and corners, open chevrons;
   - filled only for the active key/hold and the fill swatch;
   - every icon still recognisable at 22dp.
10. **Text sharp at 300, 500 and 1000px** (Inter and Noto Serif, bold, with and without stroke), in preview at Full and Half quality and in an exported video. Corners should be crisp up to about 576 px/em on screen and only slightly soft at 1000px.
    - Watch the moment the sharper tier arrives: there must be no flicker, only a sharpening.
11. **Scale 5× and 50×** on 100px text, in preview and export: sharp at 5×. At 50×, corners are rounded by about em/384 (expected, see Limits).
    - With a stroke ≥ 0.15 × size the layer stays on the 192 tier, and the stroke must not clip.
12. **Hysteresis:** slowly scale a text layer through 72/144/288 px per em and back. Tiers change once per crossing; nothing pumps or flickers while scrubbing near a threshold.
13. **Memory and frame time while scrubbing large text:** open several 1000px text layers. Check with `textStats` (`tierGlyphs`, `tierBytes`, `tierPending`, `tierDegrades`, `atlasPages` ≤ 6) and the Android Studio profiler.
    - Atlas ≤ 24 MiB; no capacity error until even the base tier overflows; scrubbing stays smooth (tier generation is off the render thread).
14. **Imported font and per-character animation text** still render; they stay on the 48 tier by design.
15. **Regression:**
    - keyboard overlay (preview never shrinks), popover typing (Done, outside tap, Back, Escape), Size ladder haptics;
    - Fonts page unchanged; Back chain; Prev/Next following the focused property;
    - large-font labelled reflow paging; selection chip; save/reopen;
    - Transform position pad and anchor mode, rotation dial, Opacity slider, Timing dialogs.

## TEXT UI 7: single-row section headers, keyframe badges and layer markers, colour picker family, inline hex entry

TEXT UI 6 was device-tested on the A16; the Edit Text layout was accepted ("perfect") and is unchanged here. Implemented in source in one commit, **device-unverified**. Scope rules held: no change to `PreviewController`, the gesture protocol, the `EditorAction` contract, the batch/draw contract or the document schema. Sheet heights stay 268 / 328dp. Edit Text's layout, the Fonts page and the compact colour popover's look are unchanged (apart from the hex/alpha entry fix, §4e).

### 1. Section-page header: one row

- Transform, Timing, Blending & Opacity and Edit Solid (and Audio for video layers) now have **one header row, exactly like Edit Text**: `←` | page tabs, `InspectorChromeBudget.header` = `TextInspectorBudget.header` = 40dp × font scale. The layer-name row, duplicate and delete are gone from these pages (`SectionHeader` in `ElementInspectorSheet.kt`).
- The **landing grid is unchanged**: drag handle, layer name with duplicate (hold: copy) and delete, audio toggle for video, quick actions, 4×2 tiles.
- `←` returns to the landing grid exactly as before. System Back is unchanged, because the sheet's back-stack code was not touched.
- Content height is now `sheet − 16 frame − header − 4`:

| Sheet × font scale | TEXT UI 6 content | TEXT UI 7 content | Grew by |
|---|---|---|---|
| 268 × 1 | 158 | **208** | 50 |
| 328 × 1 | 200 | **268** | 68 |
| 348.4 (268 × 1.3) | 215.6 | **276.4** | 60.8 |
| 426.4 (328 × 1.3) | 293.6 | **354.4** | 60.8 |
| 536 (268 × 2) | 392 | **436** | 44 |
| 656 (328 × 2) | 512 | **556** | 44 |

Every control size comes from `SectionLayout` arithmetic (in `TextInspectorState.kt`), not guesses. Touch rows are always ≥ 40dp.

- **Transform:**
  - X/Y value boxes are 40 / 48dp rows, with a larger monospace readout in standard sheets.
  - The position pad is `min(column, content − row − 8, 240)`: **160dp** at 268 (was about 118) and **212dp** at 328.
  - The rotation dial is `min(column, content, 240)`: **208dp** at 268 and **240dp** at 328.
  - The scale ruler uses a **32dp readout** (titleLarge) and a **64dp strip**, both linked and unlinked. Unlinked Width + Height takes 204 ≤ 208dp at 268.
  - The mode column is 48dp wide with 40 / 48dp tiles, top-aligned with the first content row. The rail is unchanged and now fits at full size at 268, so the Graph/3D placeholders no longer shrink.
- **Blending & Opacity:**
  - The blend-mode row is 40 / 48dp with 13sp text.
  - The opacity slider row is a 24dp label line (with the badge), then a **56dp touch row** holding an 8dp-thick track, a 24dp thumb and an 88 × 48dp value box.
  - The slider is `SectionSlider`: one undo per drag, as before.
- **Timing (and Audio):** each slider row is a label line plus a touch row of `(content − gaps) / rows − label`, clamped to 40–56dp.
  - Two rows give 56dp touch rows at both heights.
  - Video (three rows) gives 40dp at 268 and 56dp at 328.
  - Large rows get the thick track and a titleSmall value box.
  - Typed timing values still use their numeric dialog.
- **Edit Text:** it already had a single header, so it is unchanged (body 212 / 272dp).
- **Edit Solid:** the FULL colour picker (§4) gets the whole content area.

### 2. Keyframe badge on property tiles

- **Look:** about a 10dp drawn diamond with a 3dp inset in the top-right corner (`KeyBadgeGlyph`).
  - It uses the shared `InspectorIcons` key glyphs: **hollow `KEY` means the property has keyframes; filled `KEY_ON` means there is a key exactly at the playhead.**
  - There is no badge without keys. It is drawn above the icon and below the caption, and never overlaps the caption (the caption is bottom-right).
  - It is monochrome, in the tile's ink.
- **Accessibility:** the tile's state gains "keyframes active" / "keyframe at playhead".
- **Source of truth:** `KeyBadges.of(layer, property, local)`, which reads `propertyKeyTimes`, the same model the rail diamond uses. No state is stored. It is derived per property at the snapped playhead (`remember(track, playhead)` on Transform/Opacity).
- **Edit Text:** the badge appears on:
  - Size;
  - Fill · Stroke (the stronger of the two states);
  - Stroke width, Tracking, Leading, Baseline, Fill-over-stroke order and Ligatures (each keys its own layer property);
  - the bottom-bar text field (Source Text, which carries keyed run and paragraph styles).
- **Transform:** on the Position / Rotation / Scale mode icons. There is none in Anchor mode, because the anchor is not animatable.
- **Opacity:** beside the slider label.
- **Timing:** no badge, because clip timing is not animatable.

### 3. Keyframes on the layer (timeline)

- **Bug cause:** the timeline bar built its own key set from position/scale/rotation/opacity plus Source Text/Size/Fill only. Tracking, Leading, Baseline, Stroke colour, Stroke width, Fill-over-stroke, Ligatures and animator tracks never reached it.
- **Fix:** the bar now uses `LayerKeyMarkers.clipTimes` → `aggregateKeyTimes()`, which already covered every text property, Source Text (style-run and paragraph keys) and animators. All layer types and properties are covered.
- **Marker spec:**
  - Keys of the focused property on the selected layer are **full ink, 10dp**, and keep their existing tap / hold-drag behaviour.
  - Keys of every other property are **45% alpha, 7dp** and display only. Hit-testing is unchanged.
  - Keys at the same time collapse into one marker; if any belongs to the focused property it draws at full ink.
  - Markers sit along the bottom of the bar (2dp inset), below the centred layer name. The trim and move targets are unchanged.
- **Performance:** times are aggregated once per layer/keyframe/undo change (`remember(anim, text, offset)`). The draw reuses one `Path` and one `Stroke` per layer, so nothing is allocated per frame.

### 4. Colour picker family

- **Shared component:** `FluxxColourPicker(variant = COMPACT | FULL, …)` in `ui/common/colorpicker/FluxxColourPicker.kt`.
  - It is built on `SaturationValueBox`, `HueStrip` and `FluxxColor`; the colour maths is not forked.
  - Shared by both variants: rounded 10dp containers; a target toggle that shows only when there are several targets; eyedropper, inherit and reset from `InspectorIcons`; SV square and hue bar; alpha; the swatch/palette area below the controls; live apply.
  - Each interaction (a drag, a typed-value session, a swatch) is **one `ColourTransaction`**, which means one undo.
  - The old `FluxxColorPicker`, `HexColorInput`, `ColorChannelSlider` and `PaletteRow` were deleted.
- **COMPACT:** exactly the TEXT UI 6 text popover: 288dp wide; a 40dp header; a 96dp SV square, 16dp hue bar, hex row and alpha row; a 40dp strip of 28dp swatches.
- **FULL:** the same order and language, scaled up.
  - Header (40), then an SV square up to 200dp, a 20dp hue bar, and a right column of five rows: hex, then A / R / G / B gradient sliders with in-place values.
  - The palette area is below:
    - the palette browser, with all existing management (select, create, rename, delete, edit swatches);
    - the active palette's swatches;
    - save-to-palette (new `PALETTES` / `SAVE_SWATCH` icons);
    - a "Recent" row when there is room.
  - Sizes come from `ColourPickerLayout.full`. Edit Solid at 268: square 122 with 24dp rows. At 328: square 182 with 36dp rows.
- **Surface → variant:**

| Surface | Variant |
|---|---|
| Edit Text Fill / Stroke popover | COMPACT (targets Fill/Stroke, inherit with a selection, eyedropper) |
| Marker colour (composition and layer markers) | COMPACT, anchored popover, marker swatches |
| Palette browser swatch editor | COMPACT, no swatches or eyedropper (prevents recursion) |
| Edit Solid page | FULL (palette, eyedropper) |
| Add content → Solid tab (global picker page) | FULL (palette, no eyedropper while creating) |

- **Marker colour:** the colour is now a swatch + hex control in the marker editor. It opens the COMPACT picker as an `AnchoredPopover` from that control: it opens from the trigger, is clamped to the window with 8dp margins, and is never centred. The colour is still staged until Add/Save (one undo, same persistence). The strip is the marker palette: red `F44336` (composition default), orange `FF9800` (layer default), then ten more.

### 4e. In-place hex / alpha / RGB entry

- **Cause:** tapping hex or alpha opened an `InspectorValueField` inside a second popup nested in the picker popover. The nested popup lost focus and closed at once.
- **Fix:** `InlineValueField` (`ui/common/InlineValueField.kt`) edits the value **in place**, with no popup or dialog.
  - Focusing it selects all. Hex uses an ASCII IME (caps); alpha and R/G/B use a number IME.
  - When the field is cleared, the **previous value stays visible at 40% alpha** behind the empty input, and typing replaces it.
  - Leaving it empty (focus loss, IME Done, tap outside, Back after the keyboard hides) changes nothing.
  - Hex accepts `RRGGBB` (keeps alpha) or `AARRGGBB` (replaces alpha), with or without `#`, in any case. Alpha accepts 0–100 (an optional `%`); R/G/B accept 0–255.
  - A **valid complete** value applies live (begin on the first valid value, one preview per distinct value). Invalid or incomplete text applies nothing and reverts on blur. Escape cancels.
  - **One undo per edit session** (focus to blur), driven by `InlineEntrySession`.
- **IME:** inside a popover, the popover lifts above the keyboard as before. On a page (the Edit Solid FULL picker), the inspector sheet is translated above the keyboard while a field is focused (`ImeLiftHost`); nothing resizes.

### 5. Ruler label near the needle

Tick labels fade with a smoothstep of the gap between the label's nearest edge and the needle: hidden when touching, fully shown from **12dp** away (`ScaleRulerMath.needleFade`). The ruler maths and behaviour are unchanged.

### Deviations

1. **Swipe-to-dismiss on section pages** moved from the removed drag handle to the single header row (a vertical drag; taps still select tabs). The landing grid keeps its handle.
2. **Audio for video layers** is an extra tab in the section header, because its toggle lived in the removed name row. The toggle remains on the landing grid.
3. **Edit Text style and paragraph tiles** (bold, italic, case, alignment, indents and others) show no badge of their own. Their keys are Source Text keys, badged on the text field. Repeating one Source Text state on about 20 tiles would be noise.
4. Badges reflect the **layer property** (whole-layer owner) even while a selection is active, because run overrides are not separately keyed.
5. **Timing has no badges**: clip timing is not animatable.
6. **Recent colours are session-only** (in memory, newest first, 12), because there is no schema or persistence change. The Recent row appears only when FULL has ≥ 36dp value rows: yes on the Add Solid tab, no on Edit Solid at 268/328.
7. **Marker palette** is a fixed marker swatch list; no persisted marker palette existed. The marker editor itself (description, Add/Save/Delete) stays a dialog. Only the colour moved to an anchored popover.
8. The **Add content → Solid** page has no sheet-lift host. If the keyboard covers its hex field there, the field still edits in place. Only the inspector sheet lifts.
9. **Timing typed values** keep their numeric dialog; only colour values were required in place.
10. **FULL R/G/B/A tracks** draw real colours (the picker shows colours). All other picker chrome is monochrome.
11. **FULL at 268** uses 24dp value rows; touch targets are the full-width slider rows and the in-place fields. This was the trade-off for keeping the palette below the controls in the same order as COMPACT.
12. Opacity, Timing and Audio sliders are `SectionSlider` (thick track) instead of the M3 `Slider`. The begin / live preview / commit protocol and one undo per drag are unchanged.
13. **Tests whose structure changed:** only `RailAndRulerLayoutTest`.
    - Content 158/200 → 208/268.
    - The 4-item rail no longer shrinks at 268.
    - Ruler sizes now come from `SectionLayout`.
    - It gained pad/dial, slider-row and label-fade tests.
    - No other existing test was edited.

### Gate

- Compiles: `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`. Native code was not touched.
- JVM: `:app:testDebugUnitTest` ran **343 tests (316 previous + 27 new)**, with 0 failures, errors or skips.
  - New suites: `KeyframeBadgeTest` 4, `LayerKeyMarkersTest` 5, `ColourPickerFamilyTest` 8, `InlineValueFieldTest` 7. `RailAndRulerLayoutTest` went from 4 to 7.
- No APK packaging and no device run.

### Exact A16 test list

1. **Header row:** on Transform, Timing, Blending & Opacity, Edit Text and Edit Solid the header is a single row: `<` plus tabs only. There is no layer name, duplicate or delete.
   - `<` returns to the landing grid, and system Back behaves exactly as before.
   - Swiping down on the header row dismisses the sheet.
   - Video layers show an Audio tab.
2. **Landing grid unchanged:** handle, layer name (rename), duplicate (tap) / copy (hold), delete, audio toggle for video, quick actions, and the 4×2 tiles in the same arrangement.
3. **Freed height is used, at 268 and 328 and font scales 1 / 1.3 / 2:**
   - Transform: the position pad is visibly bigger, the rotation dial is bigger, the scale ruler has a 64dp strip and a large readout (linked, and unlinked Width + Height both fully visible), and the X/Y boxes are taller. The rail and mode column line up with the first content row, and the rail's Graph/3D are full size at 268.
   - Opacity: a large slider row (thick track, big thumb, big value box).
   - Timing: larger rows with no overflow (video has three rows).
   - Nothing is clipped.
4. **Badges:**
   - Add a keyframe to Size, Tracking, Leading, Baseline, Stroke width, Fill or Stroke, Fill-over-stroke, Ligatures and Source Text; and to Position, Rotation, Scale and Opacity.
   - The tile, mode icon or label shows a hollow diamond top-right, which is filled when the playhead is exactly on a key.
   - Move the playhead off and on a key: hollow ↔ filled. Remove the animation: the badge disappears.
   - The caption is never covered. TalkBack reads "keyframes active" / "keyframe at playhead".
5. **Timeline markers for Edit Text keys:** keying any Edit Text property (above, including Tracking / Leading / Stroke / Ligatures) shows a marker on that layer's timeline bar immediately. Undo removes it.
6. **Quiet markers:**
   - With several properties keyed, the focused property's keys are full size and bright and keep tap-to-seek and hold-drag.
   - Other properties' keys are visible but dimmed and smaller, and don't react to touch.
   - Shared times show one marker (bright if the focused property is among them).
   - Markers sit low on the bar and never cover the name or the trim handles. The same holds on solids, video and images.
7. **FULL picker matches the compact look:** on Edit Solid the header icons, rounded corners, SV + hue, hex row, A/R/G/B and the palette below match the compact picker's order and style.
   - Palette browser management, save swatch and eyedropper all work.
   - On Add content → Solid the Recent row shows recently applied colours.
8. **In-place hex and alpha** (text popover, marker popover, Edit Solid, Add Solid):
   - Tap hex: it edits in place and the popover does NOT close.
   - Clear it: the faded previous value shows. Leave it empty (outside tap, Done, Back): nothing changes.
   - Type `00FF00`: it applies live and keeps alpha. Type `80FF0000`: it replaces alpha. Invalid text reverts on blur.
   - The same applies to alpha % (0–100) and Edit Solid R/G/B (0–255).
   - Each edit session is exactly one undo.
   - The keyboard never hides the field: the popover or sheet lifts and the preview does not resize.
9. **Marker colour:** hold the playhead to add or edit a marker, then tap the colour control.
   - The compact picker opens anchored to the control, never centred, within 8dp of the screen edges. Try a marker near the left and right edges.
   - It shows the marker swatches. The colour is saved only with Add/Save, and undo/redo work as before.
10. **Edit Solid uses the FULL picker.** Dragging SV, hue or a channel is one undo per drag, and the eyedropper still samples.
11. **Ruler label fades near the needle:** drag the scale ruler slowly through 100%. The "100" label (and every other label) fades smoothly as it reaches the needle and reappears beyond about 12dp, with no pop. The value, detents and inertia are unchanged.
12. **Regression:**
    - Edit Text layout, Fonts page, rail menu, Explode, colour eyedropper;
    - keyboard overlay on Edit Text; scale 10000% reach and ±50000% typing; anchor mode;
    - Timing dialogs, Audio mute/gain; save and reopen.
