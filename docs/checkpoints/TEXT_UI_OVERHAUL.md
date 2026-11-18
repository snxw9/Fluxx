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
