package com.fluxx.android.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

private val FluxxMaterial3BlackScheme = darkColorScheme(
    primary = Highlight,
    onPrimary = Color.Black,
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = Color.Black,
    secondaryContainer = Surface,
    onSecondaryContainer = TextPrimary,
    tertiary = TextSecondary,
    onTertiary = Color.Black,
    tertiaryContainer = SurfaceElevated,
    onTertiaryContainer = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = BorderSubtle,
    error = TextPrimary,
    onError = Color.Black,
    errorContainer = SurfaceElevated,
    onErrorContainer = TextPrimary,
    surfaceTint = Color.Transparent,
    inverseSurface = TextPrimary,
    inverseOnSurface = Background,
    inversePrimary = Background,
    surfaceBright = SurfaceElevated,
    surfaceDim = Background,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceElevated
)

@Composable
fun FluxxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FluxxMaterial3BlackScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FluxxThemePreview() {
    FluxxTheme {
        Text("Fluxx Theme Preview", color = TextPrimary)
    }
}
