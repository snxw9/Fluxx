package com.fluxx.android.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Foreground Service for running video composition exports.
 * Declared with mediaProcessing type for Android 15 (API 35+) compatibility.
 */
class ExportService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var isExporting = false

    companion object {
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "fluxx_export_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_EXPORT = "com.fluxx.android.action.START_EXPORT"
        const val ACTION_STOP_EXPORT = "com.fluxx.android.action.STOP_EXPORT"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ExportService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "ExportService onStartCommand: action = $action")

        when (action) {
            ACTION_START_EXPORT -> {
                if (!isExporting) {
                    startExportForeground()
                }
            }
            ACTION_STOP_EXPORT -> {
                stopExport()
            }
        }
        return START_NOT_STICKY
    }

    private fun startExportForeground() {
        isExporting = true
        acquireWakeLock()

        val notification = createNotification(0)
        
        // Handle foreground type flags per Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= 35) { // API 35+ Media Processing
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Run dummy export simulation representing walking skeleton export loop
        executor.execute {
            try {
                for (progress in 0..100 step 5) {
                    if (!isExporting) break
                    Thread.sleep(500) // Simulating rendering work
                    Log.d(TAG, "Export progress: $progress%")
                    updateNotification(progress)
                }
                Log.i(TAG, "Export completed successfully")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Export interrupted")
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopExport() {
        Log.i(TAG, "Stopping export service")
        isExporting = false
        releaseWakeLock()
        stopSelf()
    }

    // Android 15+ Timeout handling to avoid ANR
    // Note: The method signature onTimeout(startId) is added in API 35
    override fun onTimeout(startId: Int) {
        Log.e(TAG, "Foreground service timed out (mediaProcessing 6-hour limit reached). Stopping export.")
        stopExport()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fluxx::ExportWakeLock").apply {
            acquire(10 * 60 * 1000L) // 10 minutes max timeout safeguard
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun createNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Video")
            .setContentText("Rendering composition... $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Video Export"
            val descriptionText = "Notifications for Fluxx export progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isExporting = false
        releaseWakeLock()
        executor.shutdown()
        Log.i(TAG, "ExportService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
