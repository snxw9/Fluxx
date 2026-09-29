package com.fluxx.android.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.fluxx.android.MainActivity
import com.fluxx.android.ProjectManager
import com.fluxx.android.model.ProjectCodec
import com.fluxx.android.model.ProjectDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ExportState(val running: Boolean = false, val percent: Int? = null,
    val message: String = "", val output: Uri? = null)

@UnstableApi
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL = "fluxx_export_channel"
        private const val NOTIFICATION = 1001
        private const val START = "com.fluxx.android.action.START_EXPORT"
        private const val STOP = "com.fluxx.android.action.STOP_EXPORT"
        private val mutableState = MutableStateFlow(ExportState())
        private val requestGeneration = java.util.concurrent.atomic.AtomicLong()
        val state = mutableState.asStateFlow()

        suspend fun start(context: Context, project: ProjectDocument) = withContext(Dispatchers.Main.immediate) {
            if (mutableState.value.running) return@withContext
            val request = requestGeneration.incrementAndGet()
            mutableState.value = ExportState(running = true, message = "Preparing export")
            var snapshot: File? = null
            try {
                withContext(Dispatchers.IO) {
                    File.createTempFile("export_snapshot_", ".fluxx", context.cacheDir).also {
                        snapshot = it
                        it.writeBytes(ProjectCodec.encode(project))
                    }
                }
                if (requestGeneration.get() != request) {
                    withContext(NonCancellable + Dispatchers.IO) { snapshot?.delete() }
                    mutableState.value = ExportState(message = "Export cancelled")
                    return@withContext
                }
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)
                    .setAction(START).putExtra("snapshot", requireNotNull(snapshot).name))
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { snapshot?.delete() }
                mutableState.value = ExportState(message = "Export failed: "+e.message)
                throw e
            }
        }
        fun cancel(context: Context) {
            requestGeneration.incrementAndGet()
            context.startService(Intent(context, ExportService::class.java).setAction(STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || job?.isActive == true) {
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        var pendingSnapshot: File? = null
        try {
            val snapshotName = requireNotNull(intent.getStringExtra("snapshot"))
            require(snapshotName.startsWith("export_snapshot_") && snapshotName.endsWith(".fluxx") &&
                File(snapshotName).name == snapshotName) { "Invalid export snapshot" }
            val snapshot = File(cacheDir, snapshotName)
            pendingSnapshot = snapshot
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION, notification("Preparing export", null), type)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fluxx::Export").apply {
                    acquire(6 * 60 * 60 * 1000L)
                }
            job = scope.launch {
                var temporary: File? = null
                var terminal = ExportState(message = "Export cancelled")
                try {
                    val project = ProjectManager(this@ExportService).loadProject(snapshot).getOrThrow()
                    val output = withContext(Dispatchers.IO) {
                        File.createTempFile("export_", ".mp4", cacheDir).also { temporary = it }
                    }
                    var lastPercent = -1
                    CompositionExporter(this@ExportService).export(project, output) { percent ->
                        if (percent == lastPercent) return@export
                        lastPercent = percent ?: -1
                        mutableState.value = ExportState(true, percent, "Exporting composition")
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                            notification("Exporting video", percent))
                    }
                    mutableState.value = ExportState(true, 99, "Saving to Movies/Fluxx")
                    val uri = publish(output)
                    terminal = ExportState(message = "Saved to Movies/Fluxx", output = uri)
                    Log.i("ExportService", "Export saved: $uri")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("ExportService", "Export failed", e)
                    terminal = ExportState(message = "Export failed: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { temporary?.delete(); snapshot.delete() }
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    // Do not allow another start until cleanup and stopSelf have completed.
                    mutableState.value = terminal
                }
            }
        } catch (e: Exception) {
            Log.e("ExportService", "Could not start export", e)
            pendingSnapshot?.delete()
            mutableState.value = ExportState(message = "Export failed: ${e.message}")
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun publish(file: File): Uri {
        var createdUri: Uri? = null
        try {
            return withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "Fluxx_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Fluxx")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
                    "Could not create the exported video in Movies/Fluxx"
                }
                createdUri = uri
                requireNotNull(contentResolver.openOutputStream(uri, "w")).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                        }
                    }
                }
                ensureActive()
                check(contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null) == 1) { "Could not publish the exported video" }
                uri
            }
        } catch (e: Exception) {
            // Also catches prompt cancellation while returning from the IO dispatcher.
            withContext(NonCancellable + Dispatchers.IO) {
                createdUri?.let { uri ->
                    try { contentResolver.delete(uri, null, null) }
                    catch (cleanup: Exception) { Log.w("ExportService", "Could not remove incomplete export", cleanup) }
                }
            }
            throw e
        }
    }

    private fun notification(message: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting video").setContentText(message).setContentIntent(open)
            .setProgress(100, percent ?: 0, percent == null).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); releaseWakeLock(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
