package com.fluxx.android.ui.home

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.ProjectMetadata
import com.fluxx.android.render.ProjectThumbnails
import com.fluxx.android.model.FrameRate
import com.fluxx.android.ui.navigation.EmptyProjectGlyph
import com.fluxx.android.ui.navigation.depthHighlight
import com.fluxx.android.ui.theme.*
import java.io.File

/**
 * Section 4: Home Screen destination.
 * - Section 4.1: When at least one project exists, shows the Resume card centered in the upper-middle
 *   portion of the screen (80% width, 12dp corners, 16:9 letterboxed thumbnail, project name + "Continue editing"),
 *   with the Create-new mark centered below it.
 * - Section 4.2: When zero projects exist, shows the centered empty state with the Create-new mark
 *   and "Start your first project" (14sp TextSecondary) supporting text beneath it.
 */
@Composable
fun HomeScreen(
    recentProject: ProjectMetadata?,
    projectThumbnails: ProjectThumbnails?,
    onOpenProject: (File) -> Unit,
    onOpenCompositionSheet: () -> Unit,
    modifier: Modifier = Modifier,
    createMark: @Composable (Modifier) -> Unit = { CreateNewMark(onOpenCompositionSheet, it) }
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundGradient),
        contentAlignment = Alignment.Center
    ) {
        if (recentProject != null) {
            // Section 4.1: Populated state with Resume Card and Create-new mark below it
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Resume Card (80% of screen width, 12dp corner radius)
                ResumeProjectCard(
                    project = recentProject,
                    projectThumbnails = projectThumbnails,
                    onClick = { onOpenProject(recentProject.file) },
                    modifier = Modifier.fillMaxWidth(0.80f)
                )

                Spacer(Modifier.height(36.dp))

                // Create-new mark positioned below the resume card
                createMark(Modifier)
            }
        } else {
            // Section 4.2: Empty state with Create-new mark and single supporting label
            createMark(Modifier.align(Alignment.Center))
            Text(
                text = "Start your first project",
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = maxHeight / 2 + 48.dp, start = 24.dp, end = 24.dp),
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

/**
 * Section 4.1: Resume Card.
 * Shows most-recently-modified project's thumbnail in a 16:9 aspect-fit container
 * with surface background letterboxing, project name, and "Continue editing".
 */
@Composable
private fun ResumeProjectCard(
    project: ProjectMetadata,
    projectThumbnails: ProjectThumbnails?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var thumbnailBitmap by remember(project.file.absolutePath, project.lastModified, project.sizeBytes) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(project.file.absolutePath, project.lastModified, project.sizeBytes, projectThumbnails) {
        thumbnailBitmap = projectThumbnails?.firstFrame(project)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pressed) SurfaceElevated else Surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .depthHighlight(RoundedCornerShape(12.dp))
            .semantics {
                this.role = Role.Button
                this.contentDescription = "Resume project ${project.name}"
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = TextPrimary),
                onClick = onClick
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Upper thumbnail container: 16:9 aspect ratio, letterboxed in Surface (#1C1C1C)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Surface)
                    .depthHighlight(
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        topHighlightAlpha = 0.04f,
                        bottomShadowAlpha = 0.10f
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnailBitmap
                if (bmp != null) {
                    // Fit the rendered composition, including its own aspect and layer transforms.
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Plain fill with empty glyph if project has zero layers or is unrenderable
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Divider),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyProjectGlyph(color = TextSecondary)
                    }
                }
            }

            // Lower card metadata
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = project.name,
                    style = CardPrimaryStyle,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Continue editing",
                    style = CardSecondaryStyle,
                    color = TextSecondary
                )
            }
        }
    }
}

@Preview(name = "Populated State", showBackground = true)
@Preview(name = "Populated State Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeScreenPopulatedPreview() {
    FluxxTheme {
        HomeScreen(
            recentProject = ProjectMetadata(
                file = File("/tmp/sample.fluxx"),
                name = "Summer Montage",
                lastModified = System.currentTimeMillis(),
                sizeBytes = 2048L,
                width = 1080,
                height = 1920,
                durationUs = 15_000_000L,
                frameRate = FrameRate(30, 1),
                layerCount = 3,
                hasMissingMedia = false,
                primaryAssetUri = null
            ),
            projectThumbnails = null,
            onOpenProject = {},
            onOpenCompositionSheet = {}
        )
    }
}

@Preview(name = "Empty State", showBackground = true)
@Preview(name = "Empty State Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeScreenEmptyPreview() {
    FluxxTheme {
        HomeScreen(
            recentProject = null,
            projectThumbnails = null,
            onOpenProject = {},
            onOpenCompositionSheet = {}
        )
    }
}
