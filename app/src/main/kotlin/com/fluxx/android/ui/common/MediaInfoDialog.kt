package com.fluxx.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.LayerType
import kotlinx.coroutines.CancellationException

@Composable
fun MediaInfoDialog(repository: MediaRepository, entry: MediaRepository.Entry, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Media Info") },
        text = { MediaInfoContent(repository, entry) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Shared with the browser's hold-to-inspect overlay. */
@Composable
fun MediaInfoContent(repository: MediaRepository, entry: MediaRepository.Entry) {
    var details by remember(entry) { mutableStateOf<MediaRepository.Details?>(null) }
    var failed by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(repository, entry) {
        try { details = repository.details(entry) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.name, style = MaterialTheme.typography.titleSmall)
        val info = details
        if (failed) Text("Media details unavailable")
        else if (info == null) Text("Reading details…")
        else {
            Text(if (info.width > 0 && info.height > 0)
                "${info.width} × ${info.height} · ${aspectLabel(info.width, info.height)}" else "Resolution unavailable")
            if (entry.type == LayerType.VIDEO) {
                Text(info.durationMs?.let { "Duration: ${durationLabel(it)}" } ?: "Duration unavailable")
                Text(info.frameRate?.let { "$it fps" } ?: "Frame rate unavailable")
            }
        }
    }
}

internal fun aspectLabel(width: Int, height: Int): String {
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val divisor = gcd(width, height).coerceAtLeast(1)
    return "${width / divisor}:${height / divisor}"
}

internal fun durationLabel(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
