package com.fluxx.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.navigation.*
import com.fluxx.android.ui.theme.*

/** Content-width CTA. The shell owns the flight clock. */
@Composable
fun CreateNewMark(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.height(56.dp).clip(CircleShape).background(Highlight)
        .depthHighlight(CircleShape, topHighlightAlpha = 0.15f, bottomShadowAlpha = 0.12f, upperRimAlpha = 0.25f)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Create new project" }
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectVectorIcon(ProjectGlyph.Plus, Color.Black)
        Text("New project", color = Color.Black, style = CardPrimaryStyle)
    }
}
