package com.fluxx.android.ui.browser

import com.fluxx.android.ui.common.durationLabel

import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.LayerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** One bounded preview player for the current item; grid cells never own players. */
@Composable
internal fun MediaDetailPreview(
    repository: MediaRepository, entry: MediaRepository.Entry, onAdd: () -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier
) = key(entry.uri) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    fun seek(fraction: Float) {
        positionMs = fraction.coerceIn(0f, 1f) * durationMs
        player?.seekTo(positionMs.toInt())
    }
    fun finishScrub() {
        scrubbing = false
        if (resumeAfterScrub) { player?.start(); playing = true }
        resumeAfterScrub = false
    }
    LaunchedEffect(playing, scrubbing) {
        while (playing && !scrubbing) {
            positionMs = (player?.currentPosition ?: 0).toFloat()
            delay(100)
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { player?.pause(); playing = false; resumeAfterScrub = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player?.stopPlayback() }
    }
    val picture by produceState<Bitmap?>(null, entry.uri) {
        if (entry.type == LayerType.IMAGE) try {
            value = withContext(Dispatchers.IO) { repository.image(entry.uri, maxEdge = 720) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Image preview unavailable" }
    }
    val frames by produceState<List<MediaRepository.FilmstripFrame>>(emptyList(), entry.uri) {
        if (entry.type == LayerType.VIDEO) try { value = repository.filmstrip(entry) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Playback can still work when frame extraction is unsupported. */ }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            IconButton(enabled = enabled, onClick = onAdd,
                modifier = Modifier.semantics { contentDescription = "Add to timeline" }) {
                BrowserIcon("AddToTimeline", MaterialTheme.colorScheme.onSurface)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (entry.type == LayerType.VIDEO) AndroidView(
                factory = { context -> VideoView(context).also { view ->
                    player = view
                    view.setOnPreparedListener { ready = true; durationMs = view.duration.coerceAtLeast(0); view.seekTo(1) }
                    view.setOnCompletionListener { playing = false; positionMs = durationMs.toFloat() }
                    view.setOnErrorListener { _, _, _ -> ready = false; playing = false; error = "Video preview unavailable"; true }
                    view.setVideoURI(Uri.parse(entry.uri))
                } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopPlayback(); if (player === it) player = null }
            ) else picture?.let { Image(it.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (entry.type == LayerType.VIDEO) Box(Modifier.fillMaxSize()
                .clickable(enabled = enabled && ready, onClickLabel = if (playing) "Pause video" else "Play video") {
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                }.semantics { contentDescription = if (playing) "Pause video" else "Play video" })
            error?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall) }
        }
        if (entry.type == LayerType.VIDEO) {
            val fraction = if (durationMs > 0) (positionMs / durationMs).coerceIn(0f, 1f) else 0f
            val cursorColor = MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxWidth().height(48.dp).background(Color.Black)
                .semantics {
                    contentDescription = "Video filmstrip scrubber"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { if (enabled && ready) { seek(it); true } else false }
                }.pointerInput(enabled, ready, durationMs) {
                    if (enabled && ready && durationMs > 0) detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            resumeAfterScrub = playing
                            player?.pause(); playing = false; scrubbing = true
                            seek(offset.x / size.width.coerceAtLeast(1))
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            seek(change.position.x / size.width.coerceAtLeast(1))
                        },
                        onDragEnd = { finishScrub() }, onDragCancel = { finishScrub() }
                    )
                }) {
                Row(Modifier.fillMaxSize()) {
                    frames.forEach { frame ->
                        Image(frame.bitmap.asImageBitmap(), "Seek to ${durationLabel(frame.timeMs)}",
                            Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled && ready) {
                                if (durationMs > 0) seek(frame.timeMs.toFloat() / durationMs)
                            }, contentScale = ContentScale.Crop)
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val x = (size.width * fraction).coerceIn(1.dp.toPx(), (size.width - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    drawCircle(cursorColor, 4.dp.toPx(), Offset(x, 4.dp.toPx()))
                }
            }
            Text("${durationLabel(positionMs.toLong())} / ${durationLabel(durationMs.toLong())}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
