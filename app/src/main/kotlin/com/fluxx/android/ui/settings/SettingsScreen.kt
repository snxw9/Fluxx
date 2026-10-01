package com.fluxx.android.ui.settings

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.navigation.ChevronRightIcon
import com.fluxx.android.ui.navigation.ShellBackArrowIcon
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Section 3: Settings destination stub screen.
 * Accessible from the persistent top bar user icon button.
 * Contains back navigation and placeholder rows for 7 categories.
 */
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val settingsCategories = remember {
        listOf(
            "Timeline/Editor settings",
            "Cache & Storage",
            "Render/Export",
            "Preview",
            "Theme editor",
            "Layer settings",
            "General effects"
        )
    }

    Scaffold(
        topBar = {
            Surface(
                color = Background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = TextPrimary),
                                onClick = onBackClick
                            )
                            .semantics {
                                contentDescription = "Navigate back"
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
                            ShellBackArrowIcon(color = TextPrimary)
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "Settings",
                        style = ScreenTitleStyle,
                        color = TextPrimary
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SurfaceElevated,
                    contentColor = TextPrimary,
                    shape = MaterialTheme.shapes.small
                )
            }
        },
        containerColor = Background,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .navigationBarsPadding()
        ) {
            itemsIndexed(settingsCategories) { index, category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    message = "$category is not yet available",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = category,
                        modifier = Modifier.weight(1f),
                        style = CardPrimaryStyle,
                        color = TextPrimary
                    )

                    ChevronRightIcon(color = TextSecondary)
                }

                if (index < settingsCategories.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(Divider)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsScreenPreview() {
    FluxxTheme {
        SettingsScreen(onBackClick = {})
    }
}

