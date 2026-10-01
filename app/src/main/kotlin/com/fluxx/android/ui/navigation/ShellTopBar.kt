package com.fluxx.android.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.theme.*

/**
 * Section 3: Persistent top bar for Home and Projects.
 * - Height: 56dp, respects top status-bar insets.
 * - Background: #121212 (flush with page content, borderless).
 * - Left: "Fluxx" wordmark (20sp medium, TextPrimary).
 * - Right: 36dp circular user profile icon (outline person glyph, subtle ripple, 48dp minimum hit target).
 */
@Composable
fun ShellTopBar(
    onUserClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Background,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // "Fluxx" plain text wordmark
            Text(
                text = "Fluxx",
                style = TopBarWordmarkStyle,
                color = TextPrimary,
                modifier = Modifier.semantics {
                    contentDescription = "Fluxx"
                }
            )

            // Circular user profile button: 36dp visible, 48dp touch target
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = TextPrimary),
                        onClickLabel = "Open settings",
                        onClick = onUserClick
                    )
                    .semantics {
                        contentDescription = "User profile and settings"
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    UserOutlineIcon(color = TextPrimary)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ShellTopBarPreview() {
    FluxxTheme {
        ShellTopBar(onUserClick = {})
    }
}
