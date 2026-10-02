# Proposed fourth work item: Text editing UI improvements

Proposal for user review only. These improvements are not authorized or implemented as part of F1. F1 supplies fixed Text/Spacing/Stroke pages, property diamonds and a size-dependent stroke-limit message; F3 separately owns animator creation, selector controls and preview range handles.

1. **Precise numeric entry:** tap scalar values to enter exact size, tracking, leading, baseline shift and requested stroke width. Preserve units, negative values where valid, frame snapping, auto-keying and one undo entry. Numeric stroke entry must retain oversized requests and show the effective width.
2. **Clear property focus:** show which property owns previous/next-key navigation, whether animation is enabled and whether the playhead is on a key. Keep focus stable when opening a colour picker or returning from numeric entry.
3. **Comfortable keyframe controls:** give diamonds and overflow controls distinct accessible targets of at least 48dp where space permits. Label add/remove/enable actions for accessibility and avoid narrow adjacent hit regions.
4. **Consistent animation actions:** provide freeze at playhead, reset and interpolation options from the same property menu. Mark Source Text and paint order as Hold; do not offer meaningless easing controls for discrete values.
5. **Better colour context:** label Fill versus Stroke prominently in the existing picker and eyedropper mode. Preserve the selected property on return, and support cancellation without losing or creating keys.
6. **Compact limit feedback:** show requested/effective stroke values and the reason for clamping as font size changes. Use the same presentation for F2 softness once approved. Announce limit state changes accessibly without adding a modal dialog.
7. **Predictable keyboard and back behavior:** Done commits, Cancel restores the pre-edit value, and Back first leaves numeric/source editing or colour mode before leaving the text tools. Keep the preview visible above the keyboard and preserve the active page.
8. **Phone layout polish:** review fixed page height, typography, swatch contrast, touch targets and large-font settings on A16. Use fixed pages/drill-in instead of a scrolling sheet. Avoid crowding the preview with implementation details.

Acceptance: all eight workflows on A16, keyboard open/closed, portrait/landscape and large system text; verify key navigation, cancellation, persistence and exactly one undo entry per continuous edit. Do not change PreviewController API or the gesture protocol.
