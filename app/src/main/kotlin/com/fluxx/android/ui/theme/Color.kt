package com.fluxx.android.ui.theme

import androidx.compose.ui.graphics.Color

// Phase 1 (retry) — Monochrome Color Tokens
val Highlight = Color(0xFFFFFFFF)           // The only accent color: playhead, selection border, primary actions

val Background = Color(0xFF121212)          // Soft near-black base background
val Surface = Color(0xFF1C1C1C)             // Layered panel / toolbar / sheet / nav surface
val SurfaceElevated = Color(0xFF242424)     // Elevated container / card pressed state
val SurfaceHigh = SurfaceElevated           // Compatibility alias
val SurfaceHighest = SurfaceElevated      // Dialogs / menus
val SurfaceVariant = SurfaceElevated

val Divider = Color(0xFF2A2A2A)             // Hairlines, 1dp top divider, borders
val Border = Divider                        // Compatibility alias
val BorderSubtle = Divider                 // Inner dividers
val BorderHighlight = Color(0xFFFFFFFF)     // 1dp bright white selection border
val BorderFocused = BorderSubtle

val TextPrimary = Color(0xFFF5F5F5)         // Clean off-white primary text & active icons
val TextSecondary = Color(0xFF7A7A7A)       // Neutral secondary / disabled text & icons
val TextDisabled = Color(0xFF4D4D4D)        // Inactive actions

// Missing media warning glyph: deliberate muted amber exception
val MissingMediaWarning = Color(0xFFD9A441)

// Subtle vertical gradients to add depth across the application
val BackgroundGradient = androidx.compose.ui.graphics.Brush.verticalGradient(
    0f to Color(0xFF161616),
    0.45f to Color(0xFF121212),
    1f to Color(0xFF0C0C0C)
)

val SurfaceGradient = androidx.compose.ui.graphics.Brush.verticalGradient(
    0f to Color(0xFF222222),
    0.5f to Color(0xFF1C1C1C),
    1f to Color(0xFF161616)
)

// Timeline Clip Semantic Content Colors (content distinction only, not chrome)
val ClipGold = SurfaceElevated
val ClipGoldText = TextPrimary
val ClipPurple = SurfaceElevated
val ClipPurpleWaveform = TextSecondary
val ClipTeal = SurfaceElevated
val ClipTealText = TextPrimary
val ClipVideoDark = Surface
val ClipVideoBorder = Divider
