package com.fluxx.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.fluxx.android.editor.CompositionCreationViewModel
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.editor.ProjectSaveQueue
import com.fluxx.android.export.ExportService
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.PaletteRepository
import com.fluxx.android.render.PreviewController
import com.fluxx.android.render.ProjectThumbnails
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.home.CompositionCreationSheet
import com.fluxx.android.ui.navigation.MainShellScreen
import com.fluxx.android.ui.projects.ProjectOperation
import com.fluxx.android.ui.settings.SettingsScreen
import com.fluxx.android.ui.theme.FluxxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

sealed interface AppDestination {
    data object Shell : AppDestination
    data object Settings : AppDestination
    data class Editor(val projectFile: File? = null) : AppDestination
}

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    private val editor by lazy { ViewModelProvider(this)[EditorViewModel::class.java] }
    private val projectManager by lazy { ProjectManager(applicationContext) }
    private val mediaRepository by lazy { MediaRepository(applicationContext) }
    private val paletteRepository by lazy { PaletteRepository.getInstance(applicationContext) }
    private val projectThumbnails by lazy { ProjectThumbnails(applicationContext, projectManager) }
    private val creation by lazy { ViewModelProvider(this)[CompositionCreationViewModel::class.java] }
    private val compositionPresets by lazy { CompositionPresetStore(applicationContext) }

    private var currentDestination by mutableStateOf<AppDestination>(AppDestination.Shell)
    private var projectsList by mutableStateOf<List<ProjectMetadata>>(emptyList())
    private var preview by mutableStateOf<PreviewController?>(null)
    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var previewMessage by mutableStateOf<String?>(null)
    private var surfaceReady = false
    private var leavingEditor = false

    /** Called on main: settle the gesture before capturing the immutable committed document. */
    private fun queueEditorSave(): Deferred<Result<Unit>>? {
        val destination = currentDestination as? AppDestination.Editor ?: return null
        val file = destination.projectFile ?: return null
        editor.commitGesture()
        return ProjectSaveQueue.enqueue(projectManager, file, editor.state.project)
    }

    private fun autosaveEditor() {
        val save = queueEditorSave() ?: return
        // Only this UI observer belongs to the Activity; the disk write survives its destruction.
        lifecycleScope.launch {
            save.await().onFailure { toast("Autosave failed: ${it.message}") }
        }
    }

    private fun refreshProjects() {
        lifecycleScope.launch {
            ProjectSaveQueue.awaitPending()
            projectsList = projectManager.listProjects()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        checkPermissions()
    }

    // Document picker fallback route for files outside MediaStore library
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                android.util.Log.w("Fluxx", "Using session-only document access", e)
            }
            lifecycleScope.launch {
                try {
                    editor.addMedia(mediaRepository, uri, "Document", editor.state.playheadUs)
                    preview?.update(editor.state.project, editor.state.playheadUs, false)
                    toast("Added document media")
                } catch (e: Exception) {
                    toast("Cannot import: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try { paletteRepository.load() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { Toast.makeText(this@MainActivity,e.message ?: "Could not load palettes",Toast.LENGTH_LONG).show() }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(18, 18, 18))
        )
        currentDestination = when (savedInstanceState?.getString("appDestination")) {
            "settings" -> AppDestination.Settings
            else -> AppDestination.Shell
        }
        checkPermissions()
        refreshProjects()

        setContent {
            FluxxTheme {
                val shellPagerState = rememberPagerState(pageCount = { 2 })
                var openingCreatedProject by remember { mutableStateOf(false) }
                LaunchedEffect(creation.createdFile, creation.openAttempt) {
                    val path = creation.createdFile ?: return@LaunchedEffect
                    openingCreatedProject = true
                    try {
                        openProject(File(path)).onSuccess { creation.opened(); refreshProjects() }
                            .onFailure { creation.openFailed("Project saved, but could not open: ${it.message}. Tap Create Project to retry opening.") }
                    } finally { openingCreatedProject = false }
                }
                when (val destination = currentDestination) {
                    is AppDestination.Shell -> {
                        MainShellScreen(
                            pagerState = shellPagerState,
                            projects = projectsList,
                            blurred = creation.visible,
                            projectThumbnails = projectThumbnails,
                            onProjectOperation = { operation, file, name ->
                                val result = when (operation) {
                                    ProjectOperation.Rename -> projectManager.renameProject(file, name).map { Unit }
                                    ProjectOperation.Duplicate -> projectManager.duplicateProject(file).map { Unit }
                                    ProjectOperation.Delete -> projectManager.deleteProject(file)
                                }
                                if (result.isSuccess) projectsList = projectManager.listProjects()
                                result
                            },
                            onOpenProject = { file ->
                                lifecycleScope.launch {
                                    openProject(file)
                                        .onSuccess { creation.opened() }
                                        .onFailure { toast("Failed to open project: ${it.message}") }
                                }
                            },
                            onOpenCompositionSheet = {
                                creation.open(projectsList)
                            },
                            onOpenSettings = { currentDestination = AppDestination.Settings }
                        )
                        if (creation.visible) CompositionCreationSheet(
                            draft = creation.draft,
                            busy = creation.busy || openingCreatedProject,
                            creationError = creation.error,
                            presets = compositionPresets,
                            onUpdate = creation::update,
                            onCreate = { creation.create(projectManager) },
                            onDismiss = creation::dismiss
                        )
                    }
                    is AppDestination.Settings -> {
                        SettingsScreen(
                            onBackClick = { currentDestination = AppDestination.Shell }
                        )
                    }
                    is AppDestination.Editor -> {
                        val hasPerm = checkHasMediaPermission()
                        val isLimited = checkIsLimitedMediaAccess()

                        EditorScreen(
                            paletteRepository = paletteRepository,
                    editor = editor,
                    mediaRepository = mediaRepository,
                    previewController = preview,
                    previewMessage = previewMessage,
                    hasMediaPermission = hasPerm,
                    isLimitedAccess = isLimited,
                    onSurfaceAvailable = { surface, width, height ->
                        previewSurface = surface
                        if (width > 0 && height > 0) {
                            previewWidth = width
                            previewHeight = height
                        }
                        surfaceReady = true
                        refreshPreview()
                    },
                    onSurfaceDestroyed = {
                        surfaceReady = false
                        previewSurface = null
                        stopPreview()
                    },
                    onSurfaceResized = { width, height ->
                        if (width > 0 && height > 0) {
                            val sizeChanged = previewWidth != width || previewHeight != height
                            previewWidth = width
                            previewHeight = height
                            if (surfaceReady) {
                                if (preview == null) {
                                    refreshPreview()
                                } else if (sizeChanged) {
                                    preview?.resize(width, height)
                                }
                            }
                        }
                    },
                    onRequestMediaPermission = { requestMediaPermissions() },
                    onOpenDocumentPicker = {
                        pickDocument.launch(arrayOf("video/*", "image/*"))
                    },
                    onSaveProject = {
                        val save = queueEditorSave()
                        lifecycleScope.launch {
                            (save?.await() ?: Result.failure(IllegalStateException("No active project file")))
                                .onSuccess {
                                    refreshProjects()
                                    toast("Project saved successfully!")
                                }
                                .onFailure { toast("Save failed: ${it.message}") }
                        }
                    },
                    onLoadProject = {
                        val file = destination.projectFile
                        lifecycleScope.launch {
                            (file?.let { openProject(it) } ?: Result.failure(IllegalStateException("No active project file")))
                                .onSuccess { toast("Project loaded successfully!") }
                                .onFailure { toast("Load failed: ${it.message}") }
                        }
                    },
                    onExportStart = {
                        editor.commitGesture()
                        lifecycleScope.launch {
                            try {
                                stopPreview()
                                ExportService.start(this@MainActivity, editor.state.project)
                            } catch (e: Exception) {
                                toast("Export start failed: ${e.message}")
                                refreshPreview()
                            }
                        }
                    },
                    onExportCancel = {
                        ExportService.cancel(this@MainActivity)
                    },
                    onOpenExportedVideo = { uri ->
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        } catch (_: Exception) {
                            toast("Export saved in Movies/Fluxx")
                        }
                    },
                    onExitClick = {
                        if (!leavingEditor) lifecycleScope.launch {
                            leavingEditor = true
                            try {
                                // Edits may continue during IO. Save again if the committed document changed.
                                while (currentDestination == destination) {
                                    val save = queueEditorSave()
                                    val snapshot = editor.state.project
                                    val result = save?.await() ?: Result.failure(IllegalStateException("No active project file"))
                                    if (result.isFailure) {
                                        toast("Could not save before leaving: ${result.exceptionOrNull()?.message}")
                                        break
                                    }
                                    if (currentDestination != destination) break
                                    if (editor.transientState != null || editor.state.project != snapshot) continue
                                    currentDestination = AppDestination.Shell
                                    shellPagerState.scrollToPage(1)
                                    refreshProjects()
                                    break
                                }
                            } finally { leavingEditor = false }
                        }
                    }
                )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        refreshProjects()
        if (surfaceReady && !ExportService.state.value.running && preview == null) {
            refreshPreview()
        }
    }

    override fun onPause() {
        preview?.pause()
        autosaveEditor()
        super.onPause()
    }

    override fun onStop() {
        autosaveEditor()
        super.onStop()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    private fun refreshPreview() {
        if (!surfaceReady || ExportService.state.value.running) return
        val surface = previewSurface ?: return
        val w = if (previewWidth > 0) previewWidth else 1
        val h = if (previewHeight > 0) previewHeight else 1
        if (preview == null) {
            preview = PreviewController(
                context = applicationContext,
                surface = surface,
                width = w,
                height = h,
                onPosition = { editor.reportPosition(it) },
                onStatus = { previewMessage = it }
            )
        }
        preview?.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
    }

    private fun stopPreview() {
        val old = preview ?: return
        preview = null
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                old.close()
            } catch (_: Exception) {}
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        autosaveEditor()
        when (currentDestination) {
            AppDestination.Shell -> outState.putString("appDestination", "shell")
            AppDestination.Settings -> outState.putString("appDestination", "settings")
            is AppDestination.Editor -> {
                // Document process restoration belongs to the existing editor lifecycle.
                outState.putString("appDestination", "shell")
            }
        }
        super.onSaveInstanceState(outState)
    }

    private suspend fun openProject(file: File): Result<Unit> {
        ProjectSaveQueue.awaitPending()
        return projectManager.loadProject(file).map { doc ->
            editor.dispatch(EditorAction.Load(doc))
            preview?.update(editor.state.project, 0L, false)
            currentDestination = AppDestination.Editor(file)
        }
    }

    private fun checkHasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val video = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val images = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            video || images || (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkIsLimitedMediaAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            val fullVideo = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val userSelected = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            !fullVideo && userSelected
        } else false
    }

    private fun requestMediaPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 34) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkPermissions() {
        // Triggers recomposition of permission dependent UI
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
