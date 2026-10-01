package com.fluxx.android.ui.browser

import com.fluxx.android.model.FluxxColor
import com.fluxx.android.model.PaletteRepository
import com.fluxx.android.ui.common.colorpicker.*

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.LayerType
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AddContentTab(val label: String, val available: Boolean = false) {
    MEDIA("Media", true), SOLID("Solid", true), SHAPE("Shape"), TEXT("Text"),
    ADJUSTMENT("Adjustment"), CAMERA("Camera"), NULL("Null")
}

@Composable
fun AddContentSheet(
    repository: MediaRepository,
    hasPermission: Boolean,
    isLimitedAccess: Boolean,
    errorMessage: String? = null,
    onRequestPermission: () -> Unit,
    onSelectMedia: suspend (Uri, String) -> Unit,
    onImportFinished: () -> Unit,
    onAddSolid: (Int) -> Unit,
    onDismiss: () -> Unit,
    allowMultiSelect: Boolean = true,
    paletteRepository: PaletteRepository? = null,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(AddContentTab.MEDIA) }
    var mediaType by remember { mutableStateOf(LayerType.VIDEO) }
    var audio by remember { mutableStateOf(false) }
    var browserScreenVisible by remember { mutableStateOf(false) }
    var bucket by remember(mediaType) { mutableStateOf<String?>(null) }
    var directories by remember(mediaType, hasPermission) { mutableStateOf(emptyList<MediaRepository.Directory>()) }
    var selected by remember { mutableStateOf<Map<String, MediaRepository.Entry>>(emptyMap()) }
    var selecting by remember { mutableStateOf(false) }
    var detail by remember(mediaType, bucket) { mutableStateOf<MediaRepository.Entry?>(null) }
    var held by remember { mutableStateOf<MediaRepository.Entry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(errorMessage) { mutableStateOf(errorMessage) }
    var showAddMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(mediaType, hasPermission) {
        directories = emptyList()
        if (hasPermission) try { directories = repository.directories(mediaType) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not read folders" }
    }
    fun toggle(entry: MediaRepository.Entry) {
        selected = if (entry.uri in selected) selected - entry.uri else selected + (entry.uri to entry)
    }
    fun add(items: List<MediaRepository.Entry>) {
        if (busy || items.isEmpty()) return
        busy = true
        showAddMenu = false
        error = null
        scope.launch {
            try {
                // Deliberately call the existing single-item flow sequentially, in selection order.
                for (entry in items) {
                    onSelectMedia(Uri.parse(entry.uri), entry.name)
                    selected = selected - entry.uri
                }
                onImportFinished()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not add media. Remaining items are still selected." }
            finally { busy = false }
        }
    }
    val content: @Composable (Modifier) -> Unit = { containerModifier ->
        Surface(color = Surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = containerModifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (browserScreenVisible) "Media browser" else "Add layer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    AnimatedVisibility(selecting && !audio && tab == AddContentTab.MEDIA) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !busy, onClick = { selecting = false; selected = emptyMap() }) { Text("Cancel") }
                            Box {
                                IconButton(enabled = !busy && selected.isNotEmpty(), onClick = { showAddMenu = true },
                                    modifier = Modifier.semantics { contentDescription = "Add ${selected.size} selected items" }) {
                                    BrowserIcon("Add", if (selected.isEmpty()) TextDisabled else Highlight)
                                }
                                DropdownMenu(showAddMenu, { showAddMenu = false }) {
                                    DropdownMenuItem(text = { Text("Add selected (${selected.size})") }, onClick = { add(selected.values.toList()) })
                                }
                            }
                        }
                    }
                    IconButton(enabled = !busy, onClick = { if (browserScreenVisible) browserScreenVisible = false else onDismiss() },
                        modifier = Modifier.semantics { contentDescription = if (browserScreenVisible) "Back to add layer" else "Close add layer" }) {
                        BrowserIcon(if (browserScreenVisible) "Back" else "Close", TextPrimary)
                    }
                }
                if (!browserScreenVisible) Row(Modifier.fillMaxWidth()) {
                    AddContentTab.entries.forEach { item ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = item.available && !busy, role = Role.Tab) { tab = item }
                            .semantics { this.selected = tab == item; if (!item.available) disabled() }
                            .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BrowserIcon(item.label, if (!item.available) TextDisabled else if (tab == item) Highlight else TextSecondary)
                            Text(item.label, color = if (item.available) TextSecondary else TextDisabled,
                                fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (tab == AddContentTab.SOLID) {
                    SolidTabContent(onAddSolid,paletteRepository)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        listOf("Video", "Image", "Audio").forEach { name ->
                            val active = if (name == "Audio") audio else !audio && (name == "Video") == (mediaType == LayerType.VIDEO)
                            FilterChip(selected = active, enabled = !busy, onClick = {
                                audio = name == "Audio"
                                if (!audio) mediaType = if (name == "Video") LayerType.VIDEO else LayerType.IMAGE
                            }, label = { Text(name) },
                                modifier = Modifier.semantics { contentDescription = name })
                        }
                    }
                    if (audio) {
                        Column(Modifier.weight(1f)) {
                            AudioLibraryContent(repository, onBrowse = if (browserScreenVisible) null else ({ browserScreenVisible = true }))
                        }
                    } else {
                        Box(Modifier.fillMaxWidth()) {
                            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(end = 56.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item { FilterChip(bucket == null, { bucket = null }, enabled = !busy, label = { Text("All") }) }
                                items(directories, key = { it.id }) { directory ->
                                    FilterChip(bucket == directory.id, { bucket = directory.id }, enabled = !busy, label = { Text(directory.name) })
                                }
                            }
                            if (!browserScreenVisible) Box(Modifier.align(Alignment.CenterEnd).width(56.dp).height(48.dp)
                                .background(Brush.horizontalGradient(listOf(Color.Transparent, Surface, Surface))), contentAlignment = Alignment.CenterEnd) {
                                IconButton(enabled = !busy, onClick = { browserScreenVisible = true },
                                    modifier = Modifier.semantics { contentDescription = "Browse media" }) {
                                    BrowserIcon("Browse", TextPrimary)
                                }
                            }
                        }
                        if (isLimitedAccess) Text("Showing media you allowed Fluxx to access.", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        if (!hasPermission) {
                            Text("Allow media access to browse your library.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onRequestPermission) { Text("Allow access") }
                        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize()) {
                                if (browserScreenVisible && detail != null && !selecting) {
                                    MediaDetailPreview(repository, detail!!, onAdd = { add(listOf(detail!!)) }, enabled = !busy,
                                        modifier = Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxHeight * .6f))
                                }
                                key(mediaType, bucket, hasPermission) {
                                    PagedMediaGrid(repository, mediaType, bucket, selected.keys, busy, Modifier.weight(1f),
                                        onFirstEntry = { if (detail == null) detail = it },
                                        onTap = { entry -> if (selecting) toggle(entry) else if (browserScreenVisible) detail = entry else add(listOf(entry)) },
                                        onHold = { held = it },
                                        onSelect = { entry -> if (allowMultiSelect) { selecting = true; toggle(entry) } })
                                }
                            }
                            held?.let { entry -> MediaInfoOverlay(repository, entry, Modifier.align(Alignment.TopCenter)) }
                        }
                    }
                }
            }
        }
    }
    if (browserScreenVisible) {
        Spacer(modifier.fillMaxSize())
        MediaBrowserScreen(onBack = { browserScreenVisible = false }) { content(Modifier.fillMaxSize()) }
    } else content(modifier)
}

@Composable
private fun PagedMediaGrid(
    repository: MediaRepository, type: LayerType, bucket: String?, selected: Set<String>, busy: Boolean,
    modifier: Modifier, onFirstEntry: (MediaRepository.Entry) -> Unit, onTap: (MediaRepository.Entry) -> Unit,
    onHold: (MediaRepository.Entry?) -> Unit, onSelect: (MediaRepository.Entry) -> Unit
) {
    var entries by remember { mutableStateOf(emptyList<MediaRepository.Entry>()) }
    var offset by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstEntry by rememberUpdatedState(onFirstEntry)
    LaunchedEffect(page) {
        loading = true
        error = null
        try {
            val result = repository.browse(type, offset, 50, bucket)
            val fresh = result.filterNot { candidate -> entries.any { it.uri == candidate.uri } }
            entries = entries + fresh
            offset += result.size
            more = result.size == 50 && fresh.isNotEmpty()
            entries.firstOrNull()?.let(firstEntry)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not read media" }
        finally { loading = false }
    }
    LazyVerticalGrid(GridCells.Adaptive(88.dp), modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(entries, key = { it.uri }) { entry ->
            MediaTile(repository, entry, entry.uri in selected, !busy, onTap, onHold, onSelect)
        }
        item {
            Column {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                else if (error != null) { Text(error!!); TextButton(onClick = { page++ }) { Text("Retry") } }
                else if (more) { LaunchedEffect(entries.size) { page++ } }
                else if (entries.isEmpty()) Text("No accessible media", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun MediaTile(
    repository: MediaRepository, entry: MediaRepository.Entry, checked: Boolean, enabled: Boolean,
    onTap: (MediaRepository.Entry) -> Unit, onHold: (MediaRepository.Entry?) -> Unit, onSelect: (MediaRepository.Entry) -> Unit
) {
    val thumb by produceState<Bitmap?>(null, entry.uri) { value = repository.thumbnail(entry.uri) }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val select by rememberUpdatedState(onSelect)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(SurfaceHigh)
        .border(if (checked) 2.dp else .5.dp, if (checked) Highlight else Border, RoundedCornerShape(6.dp))
        .semantics {
            contentDescription = entry.name
            selected = checked
            if (!enabled) disabled()
            onClick(label = "Open media") { if (enabled) tap(entry); enabled }
            onLongClick(label = "Select media") { if (enabled) select(entry); enabled }
        }.pointerInput(entry.uri, enabled) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown()
                val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() != null }
                if (quick == true) tap(entry)
                else if (quick == null) {
                    var moved = false
                    try {
                        hold(entry)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            change.consume()
                            if (!change.pressed) { if (!moved) select(entry); break }
                        }
                    } finally { hold(null) }
                }
            }
        }) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (checked) Text("✓", Modifier.align(Alignment.TopEnd).padding(4.dp).background(Highlight, CircleShape).padding(4.dp), color = Color.Black)
        Text(entry.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .6f)).padding(3.dp),
            color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaInfoOverlay(repository: MediaRepository, entry: MediaRepository.Entry, modifier: Modifier) {
    Surface(modifier.padding(8.dp), shape = RoundedCornerShape(8.dp), color = SurfaceElevated, shadowElevation = 6.dp) {
        Box(Modifier.padding(12.dp)) {
            com.fluxx.android.ui.common.MediaInfoContent(repository, entry)
        }
    }
}

@Composable
private fun SolidTabContent(onAddSolid: (Int) -> Unit,paletteRepository: PaletteRepository?) {
    var color by remember { mutableStateOf(FluxxColor.fromArgb(0xFFE53935.toInt())) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPreviewSwatch(color, Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                Text("#${color.toHexString()}", color = TextPrimary)
                Text("Duration: 5.0 seconds", color = TextSecondary, fontSize = 11.sp)
            }
            Button(onClick = { onAddSolid(color.toArgb()) }) { Text("Add") }
        }
        FluxxColorPicker(color, { color=it }, ColorPickerConfig(showEyedropper=false),
            modifier=Modifier.fillMaxWidth().weight(1f),paletteRepository=paletteRepository)
    }
}
