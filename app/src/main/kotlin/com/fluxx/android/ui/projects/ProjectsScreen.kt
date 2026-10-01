package com.fluxx.android.ui.projects

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.ProjectMetadata
import com.fluxx.android.render.ProjectThumbnails
import com.fluxx.android.ui.common.TimeFormat
import com.fluxx.android.ui.navigation.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

enum class ProjectOperation { Rename, Duplicate, Delete }

@Composable
fun ProjectsScreen(
    projects: List<ProjectMetadata>,
    thumbnails: ProjectThumbnails?,
    onOpenProject: (File) -> Unit,
    onOperation: suspend (ProjectOperation, File, String) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    var sort by rememberSaveable { mutableStateOf(ProjectSort.Modified) }
    var menu by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOperation by rememberSaveable { mutableStateOf(ProjectOperation.Rename) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000) } }
    val zone = ZoneId.systemDefault()
    val groups = remember(projects, sort, zone) { groupProjects(projects, sort, zone) }
    val listState = rememberLazyListState()
    LaunchedEffect(sort) { listState.scrollToItem(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun operate(operation: ProjectOperation, file: File, value: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                onOperation(operation, file, value).fold(
                    onSuccess = { pendingPath = null; error = null },
                    onFailure = {
                        if (pendingPath != null) error = it.message ?: "Could not update project"
                        else snackbar.showSnackbar(it.message ?: "Could not update project")
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (pendingPath != null) error = failure.message ?: "Could not update project"
                else snackbar.showSnackbar(failure.message ?: "Could not update project")
            } finally { busy = false }
        }
    }

    Box(modifier.fillMaxSize().background(BackgroundGradient)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Your projects", Modifier.weight(1f), style = ScreenTitleStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Sort projects by ${sort.label}" }) {
                        Text(sort.shortLabel, color = TextSecondary, style = CardSecondaryStyle)
                        Spacer(Modifier.width(4.dp))
                        ChevronRightIcon(TextSecondary, Modifier.rotate(90f))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
                        containerColor = Surface, modifier = Modifier.depthHighlight(RoundedCornerShape(8.dp))) {
                        ProjectSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label,
                                color = if (sort == option) Highlight else TextSecondary) },
                                onClick = { sort = option; menu = false })
                        }
                        Text("Creation date uses the file date when available; otherwise modified date.",
                            Modifier.widthIn(max = 240.dp).padding(12.dp),
                            color = TextSecondary, style = CardSecondaryStyle)
                    }
                }
            }
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No projects yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                    groups.forEach { group ->
                        item(key = "header:${sort.name}:${group.key}", contentType = "header") {
                            Text(group.label, Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
                                style = DateGroupHeaderStyle)
                        }
                        items(group.projects, key = { it.file.absolutePath }, contentType = { "project" }) { project ->
                            ProjectCard(project, thumbnails, now, !busy,
                                onOpen = { onOpenProject(project.file) },
                                onAction = { operation ->
                                    if (operation == ProjectOperation.Duplicate) operate(operation, project.file, "")
                                    else {
                                        pendingOperation = operation; pendingPath = project.file.absolutePath
                                        name = project.name; error = null
                                    }
                                })
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
    pendingPath?.let { path ->
        AlertDialog(onDismissRequest = { if (!busy) pendingPath = null },
            containerColor = Surface,
            modifier = Modifier.depthHighlight(RoundedCornerShape(28.dp)),
            title = { Text(if (pendingOperation == ProjectOperation.Rename) "Rename project" else "Delete project?",
                style = SheetHeaderStyle) },
            text = {
                Column {
                    if (pendingOperation == ProjectOperation.Rename) {
                        TextField(value = name, onValueChange = { name = it; error = null },
                            enabled = !busy, singleLine = true, isError = error != null,
                            label = { Text("Project name") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Surface,
                                unfocusedContainerColor = Surface, disabledContainerColor = Surface),
                            textStyle = SheetFieldValueStyle)
                    } else Text("Delete \"$name\"? Source media will be kept.", color = TextPrimary)
                    error?.let { Text(it, color = TextPrimary, style = CardSecondaryStyle) }
                }
            },
            confirmButton = { Button(enabled = !busy && (pendingOperation != ProjectOperation.Rename || name.isNotBlank()),
                onClick = { operate(pendingOperation, File(path), name) }) {
                Text(if (busy) "Working" else pendingOperation.name)
            } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingPath = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectCard(
    project: ProjectMetadata, thumbnails: ProjectThumbnails?, now: Long, enabled: Boolean,
    onOpen: () -> Unit, onAction: (ProjectOperation) -> Unit
) {
    var bitmap by remember(project.file.path, project.lastModified, project.sizeBytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project.file.path, project.lastModified, project.sizeBytes, thumbnails) {
        bitmap = thumbnails?.firstFrame(project)
    }
    var overflow by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 96dp normally; grow when wrapping/font scaling needs more room instead of clipping values.
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(10.dp))
        .background(if (pressed) SurfaceElevated else Surface)
        .depthHighlight(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            indication = ripple(), onClick = onOpen)) {
        Box(Modifier.width(96.dp).fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            .background(Divider)
            .depthHighlight(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp), topHighlightAlpha = 0.04f, bottomShadowAlpha = 0.10f),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).align(Alignment.TopCenter)) } ?: EmptyProjectGlyph(TextSecondary)
            if (project.hasMissingMedia) MissingMediaWarningBadge(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = 4.dp))
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                Text(project.name, Modifier.fillMaxWidth().padding(end = 36.dp), style = CardPrimaryStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth().padding(end = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Metadata(ProjectGlyph.Aspect, project.aspectRatioLabel)
                    Metadata(ProjectGlyph.Resolution, project.resolutionLabel)
                    Metadata(ProjectGlyph.Duration, project.durationLabel)
                    Metadata(ProjectGlyph.FrameRate, project.fpsLabel)
                    Metadata(ProjectGlyph.FileSize, project.formattedSize)
                }
                Text(TimeFormat.lastEdited(project.lastModified, now), color = TextSecondary, fontSize = 11.sp)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { overflow = true }, enabled = enabled, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "More options for ${project.name}" }) {
                    ProjectVectorIcon(ProjectGlyph.More, TextSecondary, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false },
                    containerColor = Surface, modifier = Modifier.depthHighlight(RoundedCornerShape(8.dp))) {
                    ProjectOperation.entries.forEach { operation ->
                        DropdownMenuItem(text = { Text(operation.name) },
                            onClick = { overflow = false; onAction(operation) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Metadata(glyph: ProjectGlyph, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ProjectVectorIcon(glyph, TextSecondary, Modifier.size(12.dp))
        Text(value, style = CardSecondaryStyle, softWrap = false)
    }
}
