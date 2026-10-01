package com.fluxx.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.theme.*

@Composable
fun ShellBottomNav(scrollProgress: Float, onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier, onCreateSlotPositioned: (LayoutCoordinates) -> Unit = {},
    enabled: Boolean = true) {
    val progress = scrollProgress.coerceAtLeast(0f)
    // Keep the side margins constant as the destinations spread around the cradle.
    // Using the same progress preserves direct drag tracking and the shared spring rebound.
    val iconGap = 80.dp + 60.dp * progress
    val pillWidth = iconGap + 96.dp
    val shape = CradleShape(with(LocalDensity.current) { (32.dp * progress).toPx() })
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))
        .padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(pillWidth).height(64.dp)
            .background(Surface, shape).depthHighlight(shape).selectableGroup()) {
            Box(Modifier.align(Alignment.TopCenter).size(0.dp).onGloballyPositioned(onCreateSlotPositioned))
            val halfGap = iconGap / 2
            ShellNavItem("Home", progress < .5f, enabled, { onTabSelected(0) },
                Modifier.align(Alignment.Center).offset(x = -halfGap)) { active, color -> HomeNavIcon(active, color) }
            ShellNavItem("Projects", progress >= .5f, enabled, { onTabSelected(1) },
                Modifier.align(Alignment.Center).offset(x = halfGap)) { active, color -> ProjectsNavIcon(active, color) }
        }
    }
}

@Composable
private fun ShellNavItem(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier, icon: @Composable (Boolean, Color) -> Unit) {
    val color = if (active) Highlight else TextSecondary
    Column(modifier.size(width = 48.dp, height = 56.dp)
        .selectable(active, enabled = enabled, role = Role.Tab, onClick = onClick)
        .semantics { contentDescription = "$label tab" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon(active, color)
        Spacer(Modifier.height(4.dp))
        Text(label, style = if (active) BottomNavLabelActiveStyle else BottomNavLabelStyle,
            color = color, maxLines = 1)
    }
}
