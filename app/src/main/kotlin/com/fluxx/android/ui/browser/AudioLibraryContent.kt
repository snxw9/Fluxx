package com.fluxx.android.ui.browser

import com.fluxx.android.ui.common.durationLabel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.ui.theme.TextPrimary
import com.fluxx.android.ui.theme.TextSecondary
import com.fluxx.android.ui.theme.Surface
import kotlinx.coroutines.CancellationException

@Composable
internal fun ColumnScope.AudioLibraryContent(repository: MediaRepository, onBrowse: (() -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var permitted by remember { mutableStateOf(granted()) }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it; refresh++ }
    DisposableEffect(owner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { permitted = granted(); refresh++ }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var mode by remember { mutableStateOf("Songs") }
    var group by remember(mode) { mutableStateOf<MediaRepository.AudioGroup?>(null) }
    Box(Modifier.fillMaxWidth()) {
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(end = 56.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Songs", "Albums", "Artists")) { name ->
                FilterChip(mode == name, { mode = name }, label = { Text(name) })
            }
        }
        if (onBrowse != null) Box(Modifier.align(Alignment.CenterEnd).width(56.dp).height(48.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Surface, Surface))), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = onBrowse, modifier = Modifier.semantics { contentDescription = "Browse media" }) {
                BrowserIcon("Browse", TextPrimary)
            }
        }
    }
    Text("Audio browsing only · Audio layers are not yet available", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    if (!permitted) {
        Button(onClick = { launcher.launch(permission) }) { Text("Allow audio access") }
        return
    }
    key(mode, group?.id, refresh) {
        var songs by remember { mutableStateOf(emptyList<MediaRepository.AudioEntry>()) }
        var groups by remember { mutableStateOf(emptyList<MediaRepository.AudioGroup>()) }
        var offset by remember { mutableIntStateOf(0) }
        var page by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var more by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(page) {
            loading = true
            error = null
            try {
                if (mode != "Songs" && group == null) { groups = repository.audioGroups(mode == "Artists"); more = false }
                else {
                    val result = repository.audio(offset, albumId = group?.id.takeIf { mode == "Albums" }, artistId = group?.id.takeIf { mode == "Artists" })
                    val fresh = result.filterNot { entry -> songs.any { it.uri == entry.uri } }
                    songs = songs + fresh
                    offset += result.size
                    more = result.size == 50 && fresh.isNotEmpty()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not read audio library" }
            finally { loading = false }
        }
        group?.let { TextButton(onClick = { group = null }) { Text("‹ ${it.name}") } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(groups, key = { it.id }) { item ->
                ListItem(headlineContent = { Text(item.name) }, modifier = Modifier.clickable { group = item })
            }
            items(songs, key = { it.uri }) { song ->
                ListItem(headlineContent = { Text(song.name) }, supportingContent = { Text("${song.artist} · ${song.album}") },
                    trailingContent = { Text(durationLabel(song.durationMs)) })
            }
            item {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                else if (error != null) { Text(error!!); TextButton(onClick = { page++ }) { Text("Retry") } }
                else if (more) TextButton(onClick = { page++ }) { Text("Load more") }
                else if (groups.isEmpty() && songs.isEmpty()) Text("No accessible audio")
            }
        }
    }
}
