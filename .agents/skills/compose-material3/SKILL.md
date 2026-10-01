---
name: compose-material3
description: Comprehensive guide and best practices for implementing Material Design 3 (M3 / Material You / M3 Expressive) in Jetpack Compose, covering color schemes, dynamic theming, typography, shapes, tonal elevation, component customization, and accessibility.
---

# Material Design 3 (M3) in Jetpack Compose

Jetpack Compose provides a first-class implementation of Material Design 3 (M3), Material You, and M3 Expressive. M3 features research-backed updates to theming, components, motion, typography, dynamic color personalization (Android 12+), and complements the modern Android system UI.

---

## 1. Dependency & Experimental APIs

Add the Material 3 Compose artifact to your `build.gradle.kts`:

```kotlin
implementation("androidx.compose.material3:material3:$material3_version")
```

For experimental M3 APIs, annotate composables or files:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppComposable() {
    // M3 experimental composables
}
```

---

## 2. Theming Architecture (`MaterialTheme`)

An M3 theme consists of three core subsystems: **color scheme**, **typography**, and **shapes**.

```kotlin
MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = AppShapes,
    content = content
)
```

---

## 3. Color Scheme & Dynamic Color

### 3.1 Color Roles & Foundations
M3 color schemes are built around five key colors mapped across 13 tonal palettes:
- **Primary**: Main base color for prominent components (e.g., primary buttons, active states, elevated surface tint).
- **Secondary**: For less prominent UI elements (e.g., filter chips, secondary actions).
- **Tertiary**: Contrasting accent color to balance primary/secondary or direct attention (e.g., FAB accents).
- **Surface / Surface Variant**: Background for components and structural elements.
- **Background**: Screen background behind scrollable/container elements.
- **On-* Colors**: Legible foreground colors (`onPrimary`, `onSecondary`, `onTertiary`, `onSurface`, `onSurfaceVariant`, `onPrimaryContainer`, etc.).

### 3.2 Dynamic Color (Android 12+)
Dynamic color extracts a personalized tonal palette from the user's wallpaper:

```kotlin
val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
val colorScheme = when {
    dynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
    dynamicColor && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}
```

### 3.3 Accessing Colors
```kotlin
Text(
    text = "Material 3",
    color = MaterialTheme.colorScheme.primary
)
```

---

## 4. Typography Scale

M3 standardizes typography into 5 groups across 3 sizes (**15 styles total**):
- **Display**: `displayLarge` (57/64), `displayMedium` (45/52), `displaySmall` (36/44)
- **Headline**: `headlineLarge` (32/40), `headlineMedium` (28/36), `headlineSmall` (24/32)
- **Title**: `titleLarge` (22/28), `titleMedium` (16/24), `titleSmall` (14/20)
- **Body**: `bodyLarge` (16/24), `bodyMedium` (14/20), `bodySmall` (12/16)
- **Label**: `labelLarge` (14/20), `labelMedium` (12/16), `labelSmall` (11/16)

> [!NOTE]
> Unlike M2, M3 `Typography` does **not** take a `defaultFontFamily` parameter. Specify `fontFamily` directly on individual `TextStyle` definitions when customizing fonts.

```kotlin
val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )
)
```

Usage:
```kotlin
Text(
    text = "Title Text",
    style = MaterialTheme.typography.titleLarge
)
```

---

## 5. Shape Scale

M3 provides an expanded shape scale representing corner roundedness:
- `extraSmall`: `RoundedCornerShape(4.dp)`
- `small`: `RoundedCornerShape(8.dp)`
- `medium`: `RoundedCornerShape(12.dp)`
- `large`: `RoundedCornerShape(16.dp)`
- `extraLarge`: `RoundedCornerShape(24.dp)`
- `RectangleShape` (0.dp) and `CircleShape` (fully circular)

```kotlin
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// Usage:
Card(shape = MaterialTheme.shapes.medium) { /* ... */ }
FloatingActionButton(shape = MaterialTheme.shapes.large, onClick = { /* ... */ }) { /* ... */ }
```

---

## 6. Elevation: Tonal Overlays vs Shadows

In M3, elevation is primarily expressed via **tonal color overlays** (a subtle primary color tint that increases in prominence with elevation level), rather than heavy drop shadows:
- Dark theme elevation uses primary tonal overlays.
- M3 `Surface` provides both `tonalElevation` and `shadowElevation`.

```kotlin
Surface(
    modifier = Modifier,
    tonalElevation = 4.dp,
    shadowElevation = 2.dp
) {
    // Content
}
```

---

## 7. Emphasis & Contrast Hierarchy

- **Color Emphasis**: Use paired container and on-container roles:
  - Selected/focused items: `primaryContainer` with `onPrimaryContainer`.
  - Neutral contrast: `surface` with `onSurfaceVariant`, or `surfaceVariant` with `onSurface`.
- **Typography Emphasis**: Vary font weights (e.g., `FontWeight.Bold` vs `FontWeight.Normal`) rather than arbitrary colors.
- **Disabled State**: Use `on-*` colors with alpha.

---

## 8. Components & Adaptive Navigation

### 8.1 Button Hierarchy
Use button variants to match semantic action weight:
1. `ExtendedFloatingActionButton` / `FloatingActionButton` — Top primary action.
2. `Button` (Filled) — High emphasis.
3. `FilledTonalButton` — Medium-high emphasis.
4. `OutlinedButton` — Medium emphasis.
5. `TextButton` — Low emphasis.

### 8.2 Responsive Navigation
Choose the right navigation element according to screen size:
- **Compact (Phones)**: `NavigationBar` with `NavigationBarItem` (<= 5 destinations).
- **Medium (Tablets / Foldables / Landscape)**: `NavigationRail` with `NavigationRailItem`.
- **Expanded (Large tablets / Desktops)**: `PermanentNavigationDrawer` or `ModalNavigationDrawer`.

### 8.3 Customizing Component Defaults
Override component colors and elevations cleanly using companion `*Defaults`:

```kotlin
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
) {
    // Card content
}
```

---

## 9. Accessibility Rules

- **Contrast Pairing Guarantee**:
  - Always place `onPrimary` on `primary`.
  - Always place `onPrimaryContainer` on `primaryContainer`.
  - Never place cross-role accent containers directly over one another without verifying WCAG contrast (e.g. `primaryContainer` text inside `tertiaryContainer`).
- **Dynamic Type**: Text styles scale dynamically across phone, tablet, and foldable contexts.

---

## 10. Platform Behaviors (Android 12+)

- **Sparkle Ripple**: Handled automatically via `PlatformRipple` / `RippleDrawable` on API 31+.
- **Stretch Overscroll**: Enabled automatically in `LazyColumn`, `LazyRow`, and scrolling containers.
