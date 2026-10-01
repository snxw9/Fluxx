# Workspace workflow

- Implement requested code and documentation changes without running Gradle builds, APK packaging, or Android Studio builds unless the user explicitly asks for a build.
- The user builds in Android Studio and performs phone testing. Do not install or launch debug/test apps on their device unless explicitly requested.
- Read docs/architecture/FLUXX_ROADMAP.md for the active phase and docs/architecture/FLUXX_SDD.md for the target architecture. Distinguish implemented behavior, user-confirmed results, and planned work.
- Fluxx will have its own media picker. The current document-selection entry point is temporary; Android's photo picker UI is not a product requirement.

# Material Design 3 (M3) in Compose

- **Theming Subsystems**: Structure UI around `MaterialTheme` utilizing `ColorScheme`, `Typography`, and `Shapes`.
- **Dynamic Color**: Support dynamic color for Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) using `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`, with fallback to custom light/dark schemes.
- **Color Roles & Accessibility Contrast**:
  - Main colors: `primary`, `secondary`, `tertiary`, `surface`, `surfaceVariant`, `background`.
  - Always enforce accessible pairing: `onPrimary` on `primary`, `onPrimaryContainer` on `primaryContainer`, `onSurface` on `surface`, and `onSurfaceVariant` on `surfaceVariant`. Avoid arbitrary cross-role pairings (e.g. `primaryContainer` text inside `tertiaryContainer`).
  - Disabled states: use `on-*` colors with alpha.
- **Typography Scale**: Adhere to M3 type scale (`display`, `headline`, `title`, `body`, `label` in Large, Medium, Small). Unlike M2, M3 `Typography` does not have a `defaultFontFamily`; specify `fontFamily` directly on each `TextStyle`.
- **Shapes Scale**: Utilize M3 shapes (`extraSmall` 4dp, `small` 8dp, `medium` 12dp, `large` 16dp, `extraLarge` 24dp, `RectangleShape`, `CircleShape`).
- **Elevation**: Elevation in M3 relies primarily on tonal color overlays (derived from the primary color slot) alongside shadow elevation. Configure both via `Surface(tonalElevation, shadowElevation)` or component defaults.
- **Component Hierarchy & Customization**:
  - Buttons: `ExtendedFloatingActionButton` / `FloatingActionButton` > Filled `Button` > `FilledTonalButton` > `OutlinedButton` > `TextButton`.
  - Adaptive Navigation: Use `NavigationBar` for compact/mobile (<= 5 destinations), `NavigationRail` for tablets/foldables/landscape, and `PermanentNavigationDrawer` / `ModalNavigationDrawer` for expanded layouts.
  - Component customization: Use companion default objects (e.g. `CardDefaults.cardColors(...)`, `ButtonDefaults.buttonColors(...)`, `CardDefaults.cardElevation(...)`).

