package com.fluxx.android

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.fluxx.android.engine.FluxxEngine
import com.fluxx.android.engine.VideoDecoder
import kotlinx.coroutines.*
import java.io.FileDescriptor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.opengl.Matrix

data class Layer(
    val id: Long = 0L,
    var positionX: Float = 0f,
    var positionY: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var rotationDegrees: Float = 0f,
    var opacity: Float = 1f,
    var assetUri: android.net.Uri? = null
)

class MainActivity : ComponentActivity() {

    private var videoDecoder: VideoDecoder? = null
    private var renderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private val renderDispatcher = kotlinx.coroutines.newSingleThreadContext("VulkanRenderThread")

    private val layerState = mutableStateOf(Layer())
    private val isExporting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val exportProgress = mutableStateOf<com.fluxx.android.export.VideoExporter.Progress?>(null)

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                // Attempt to make the URI grant durable across app restarts
                contentResolver.takePersistableUriPermission(
                    uri, 
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                
                // Only save the URI to the project state if the persistent grant succeeded
                updateLayer(layerState.value.copy(assetUri = uri))
            } catch (e: SecurityException) {
                // Known issue on some OEM skins or unsupported content providers
                android.util.Log.w(
                    "MainActivity", 
                    "Failed to get persistable permission for URI. Video will play now, but won't be saved to the .fluxx project.", 
                    e
                )
            }

            // Proceed with opening and playing the file for the current session regardless
            val fd = contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
            if (fd != null) {
                startVideo(fd)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frameLayout = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        frameLayout.addView(surfaceView)
        
        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    val layer by layerState
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(onClick = {
                                val currentUri = layer.assetUri
                                if (currentUri != null && !isExporting.get()) {
                                    scope.launch {
                                        isExporting.set(true)
                                        stopVideo()
                                        
                                        val fd = contentResolver.openFileDescriptor(currentUri, "r")?.fileDescriptor
                                        if (fd != null) {
                                            val exportDecoder = VideoDecoder(fd).apply {
                                                exportMode = true
                                            }
                                            exportDecoder.start()
                                            
                                            val exporter = com.fluxx.android.export.VideoExporter(renderDispatcher, exportDecoder)
                                            exporter.onProgress = { progress ->
                                                exportProgress.value = progress
                                            }
                                            
                                            val outputFile = java.io.File(getExternalFilesDir(null), "export_${System.currentTimeMillis()}.mp4")
                                            val result = exporter.export(outputFile)
                                            
                                            if (result.isSuccess) {
                                                android.widget.Toast.makeText(this@MainActivity, "Export Saved: ${outputFile.name}", android.widget.Toast.LENGTH_LONG).show()
                                            } else {
                                                android.widget.Toast.makeText(this@MainActivity, "Export Failed", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                            
                                            exportDecoder.stop()
                                        }
                                        
                                        isExporting.set(false)
                                        exportProgress.value = null
                                        
                                        // Restart normal video preview
                                        val restartFd = contentResolver.openFileDescriptor(currentUri, "r")?.fileDescriptor
                                        if (restartFd != null) {
                                            startVideo(restartFd)
                                        }
                                    }
                                }
                            }) {
                                Text("Export")
                            }
                            
                            val projectManager = remember { ProjectManager(this@MainActivity) }
                            val projectFile = remember { java.io.File(filesDir, "project.fluxx") }
                            
                            Button(onClick = {
                                scope.launch {
                                    val result = projectManager.saveProject(projectFile, listOf(layer))
                                    if (result.isSuccess) {
                                        android.widget.Toast.makeText(this@MainActivity, "Saved!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity, "Save Failed", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Save")
                            }
                            
                            Button(onClick = {
                                scope.launch {
                                    val result = projectManager.loadProject(projectFile)
                                    result.onSuccess { layers ->
                                        if (layers.isNotEmpty()) {
                                            val loadedLayer = layers.first()
                                            updateLayer(loadedLayer)
                                            android.widget.Toast.makeText(this@MainActivity, "Loaded!", android.widget.Toast.LENGTH_SHORT).show()
                                            
                                            // Re-start video if URI is valid
                                            loadedLayer.assetUri?.let { uri ->
                                                try {
                                                    val fd = contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
                                                    if (fd != null) {
                                                        startVideo(fd)
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("MainActivity", "Failed to reopen video after load", e)
                                                }
                                            }
                                        }
                                    }.onFailure {
                                        android.widget.Toast.makeText(this@MainActivity, "Load Failed", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Load")
                            }
                        }
                        
                        val progress = exportProgress.value
                        if (progress != null && !progress.isComplete) {
                            val progressValue = if (progress.totalFrames > 0) progress.currentFrame.toFloat() / progress.totalFrames else 0f
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                            Text("Exporting: ${progress.currentFrame} / ${progress.totalFrames}", color = androidx.compose.ui.graphics.Color.White)
                        }
                        
                        Text("Position X: ${layer.positionX}", color = androidx.compose.ui.graphics.Color.White)
                        Slider(value = layer.positionX, onValueChange = { updateLayer(layer.copy(positionX = it)) }, valueRange = -2f..2f)
                        
                        Text("Position Y: ${layer.positionY}", color = androidx.compose.ui.graphics.Color.White)
                        Slider(value = layer.positionY, onValueChange = { updateLayer(layer.copy(positionY = it)) }, valueRange = -2f..2f)
                        
                        Text("Scale: ${layer.scaleX}", color = androidx.compose.ui.graphics.Color.White)
                        Slider(value = layer.scaleX, onValueChange = { updateLayer(layer.copy(scaleX = it, scaleY = it)) }, valueRange = 0.1f..3f)
                        
                        Text("Rotation: ${layer.rotationDegrees}", color = androidx.compose.ui.graphics.Color.White)
                        Slider(value = layer.rotationDegrees, onValueChange = { updateLayer(layer.copy(rotationDegrees = it)) }, valueRange = 0f..360f)
                        
                        Text("Opacity: ${layer.opacity}", color = androidx.compose.ui.graphics.Color.White)
                        Slider(value = layer.opacity, onValueChange = { updateLayer(layer.copy(opacity = it)) }, valueRange = 0f..1f)
                    }
                }
            }
        }
        frameLayout.addView(composeView)

        setContentView(frameLayout)

        // Request POST_NOTIFICATIONS permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                val w = surfaceView.width
                val h = surfaceView.height
                val am = assets
                
                scope.launch(renderDispatcher) {
                    FluxxEngine.init(surface, w, h, am)
                    
                    withContext(Dispatchers.Main) {
                        // Initialize transform
                        updateLayer(layerState.value)
                        
                        // Prompt user for video on main thread
                        pickVideo.launch("video/*")
                    }
                    
                    val targetFrameTimeNanos = 16_666_666L
                    var lastTime = System.nanoTime()
                    
                    // We run render loop within the same coroutine after init
                    renderJob = scope.launch(renderDispatcher) {
                        while(isActive) {
                            if (!isExporting.get()) {
                                FluxxEngine.renderFrame()
                            }
                            val now = System.nanoTime()
                            val elapsed = now - lastTime
                            val slackMs = (targetFrameTimeNanos - elapsed) / 1_000_000L
                            if (slackMs > 0) {
                                delay(slackMs)
                            }
                            lastTime = System.nanoTime()
                        }
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopVideo()
                runBlocking {
                    renderJob?.cancelAndJoin()
                    renderJob = null
                    withContext(renderDispatcher) {
                        FluxxEngine.cleanup()
                    }
                }
            }
        })
    }

    private fun updateLayer(newLayer: Layer) {
        layerState.value = newLayer
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        
        // 5. USER TRANSLATE
        Matrix.translateM(matrix, 0, newLayer.positionX, newLayer.positionY, 0f)
        
        // 4. COMP-SPACE CORRECTION (Ortho/Aspect mapping)
        val compWidth = FluxxEngine.getCompWidth().toFloat()
        val compHeight = FluxxEngine.getCompHeight().toFloat()
        
        // Handle uninitialized engine state smoothly
        val finalCompW = if (compWidth > 0f) compWidth else 1080f
        val finalCompH = if (compHeight > 0f) compHeight else 1920f
        
        Matrix.scaleM(matrix, 0, 1f / (finalCompW / 2f), 1f / (finalCompH / 2f), 1f)
        
        // 3. USER ROTATE
        Matrix.rotateM(matrix, 0, newLayer.rotationDegrees, 0f, 0f, 1f)
        
        // 2. USER SCALE
        Matrix.scaleM(matrix, 0, newLayer.scaleX, newLayer.scaleY, 1f)
        
        // 1. BASE QUAD SHAPING (Dynamic Video Aspect)
        val videoW = videoDecoder?.videoWidth?.toFloat()?.takeIf { it > 0 } ?: 1080f
        val videoH = videoDecoder?.videoHeight?.toFloat()?.takeIf { it > 0 } ?: 1920f
        val videoAspect = videoW / videoH
        
        val compAspect = finalCompW / finalCompH
        val quadWidthPixels: Float
        val quadHeightPixels: Float
        if (videoAspect > compAspect) {
            // video is proportionally wider than the comp -> constrain by width, letterbox top/bottom
            quadWidthPixels = finalCompW / 2f
            quadHeightPixels = quadWidthPixels / videoAspect
        } else {
            // video is proportionally narrower/taller than the comp -> constrain by height, pillarbox left/right
            quadHeightPixels = finalCompH / 2f
            quadWidthPixels = quadHeightPixels * videoAspect
        }
        Matrix.scaleM(matrix, 0, quadWidthPixels, quadHeightPixels, 1f)
        
        scope.launch(renderDispatcher) {
            FluxxEngine.setLayerTransform(matrix, newLayer.opacity)
        }
    }

    private fun startVideo(fd: FileDescriptor) {
        stopVideo()
        videoDecoder = VideoDecoder(fd).apply {
            onFormatReady = {
                scope.launch(Dispatchers.Main) {
                    updateLayer(layerState.value)
                }
            }
        }
        videoDecoder?.start()
    }

    private fun stopVideo() {
        videoDecoder?.stop()
        videoDecoder = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        renderDispatcher.close()
    }
}
