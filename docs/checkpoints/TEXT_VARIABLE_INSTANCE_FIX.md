# Variable-font named-instance correction — 2026-10-09

Implemented in source, device-unverified. The user reports that a named style did not render until an axis slider moved; static font style switching worked.

## Cause and correction

`FontAxisControls` kept a separate local `instanceIndex`. The previous/next style buttons changed only that local index and the displayed name. They never called `apply`; applying the displayed preset required an additional, undisclosed tap on its central label. This was selection-event wiring, not a missing renderer cache key: `FontFaceMetadata.reference` already resolves every preset axis, `StyledTextPreparation.shapingDocument` retains font references, and imported worker identities already include SHA/face/instance/axis bits.

Previous/next now select immediately through `FontFaceMetadata.selectInstance`, replacing all custom coordinates with the preset's coordinates in one existing Source Text formatting transaction. Default replaces every axis with its default. The displayed instance derives from the persisted reference, so undo/redo and restored documents cannot leave an unrelated local style label. Axis pending work is keyed/cancelled by the full reference, preventing a queued old-reference update from surviving a preset change. The central label still reapplies/reset-coordinates for the currently selected preset. No layout or UI consolidation is included.

Preparation remains asynchronous and uses the shared preview/export font identity and packet path. No native renderer, schema, PreviewController API, gesture protocol, batch/draw contract or prepared evaluation loop changes. New production font/asset bytes: zero; APK code delta unmeasured without packaging. Older stored references remain unchanged; this corrects new selections, not historical documents whose local-only choice was never stored.

## Verification

The JVM regression `instanceChoiceReplacesCustomAxesPreparesImmediatelyAndIsOnePersistedUndo` uses two axes and a non-positional instance index (7). It selects a preset from custom coordinates, checks old/new geometry preparation without a slider edit, verifies changed shaping identity and all coordinates, codec round-trip, one-step undo/redo, and default restoration. Existing tests also cover coordinate validation/clamping and persisted display style. Actual preview/export pixels remain user-run acceptance; a JVM identity/transaction fixture is not a pixel proof.

Final gate passed: app Kotlin Debug/Release and app instrumentation-source compilation, plus all 247 JVM tests (zero failures/errors/skips), in `tools/text/results/variable-instance-final.log`. The first run passed Debug/Release compilation but exposed a fixture expectation error: anchor compensation measures both old and new geometry, not just the selected geometry. The fixture now explicitly checks both identities; the final full JVM run passed. The existing host CFF variable-font proof passed outline/advance changes, coordinate clamping, independent FreeType/HarfBuzz advance agreement and prepared-packet round-trip. Native production sources are unchanged. No APK assembly, device install, device launch or device test is authorized or performed.

The full host selftest also passed (dual ligature modes, cached switching, cluster metadata, 4,096-glyph modes, fallback, prepared transport/cache/lifetime and concurrent preparation). Protected contracts and the allocation-free prepared evaluation path are unchanged.

## Exact user-run manual list

1. Import a variable font with multiple named instances and at least two axes. In the existing Variable Axes page, use previous/next to select each preset, without touching a slider or the central label. Verify the rendered text and all axis values update immediately; check Default and returning from custom coordinates.
2. Repeat with a selected range, no selection, faux styles, another variable font and a collection face. Static bundled/imported style switching must still work. Start an axis drag, choose a preset immediately afterward, and confirm the final preset coordinates win.
3. Verify one undo/redo per preset choice, saved/restored instance name and coordinates, copy/paste, held Source Text keys at/between frames, missing-font deletion/re-import and no unwanted change to other ranges/keys.
4. Compare preview and pre-encode/export frames at the same playhead after preset selection, including cold/warm imported caches and rapid switching. Run existing imported-font/variable-font acceptance and parity fixtures. Record timing logs for responsiveness; compilation is not device acceptance.

## Part B boundary

The [Edit Text overhaul](../architecture/TEXT_UI_OVERHAUL_PLAN.md) is a separate approval draft only. Its tab/grid/popover changes, numeric-entry redesign, icon inventory and Fonts popup are not implemented or included in this bug-fix commit.
