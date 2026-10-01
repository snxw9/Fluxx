package com.fluxx.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AtomicFile
import com.fluxx.android.model.AssetReference
import com.fluxx.android.model.FrozenList
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.MediaAccess
import com.fluxx.android.model.ProjectCodec
import com.fluxx.android.model.ProjectDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.fluxx.android.model.FrameRate
import com.fluxx.android.model.CompositionCreation
import com.fluxx.android.model.Composition
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ClipTiming
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

data class ProjectMetadata(
    val file: File,
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationUs: Long?,
    val frameRate: FrameRate,
    val layerCount: Int,
    val hasMissingMedia: Boolean,
    val primaryAssetUri: String?,
    val createdAt: Long = lastModified
) {
    val aspectRatioLabel: String get() {
        val gcd = gcd(width, height)
        val wNorm = width / gcd
        val hNorm = height / gcd
        return when {
            width == 1080 && height == 1920 -> "9:16"
            width == 1920 && height == 1080 -> "16:9"
            width == height -> "1:1"
            wNorm == 9 && hNorm == 16 -> "9:16"
            wNorm == 16 && hNorm == 9 -> "16:9"
            wNorm == 4 && hNorm == 3 -> "4:3"
            wNorm == 4 && hNorm == 5 -> "4:5"
            else -> "$wNorm:$hNorm"
        }
    }

    val resolutionLabel: String get() {
        val minDim = minOf(width, height)
        return when {
            minDim >= 2160 -> "2160p"
            minDim >= 1080 -> "1080p"
            minDim >= 720 -> "720p"
            minDim >= 540 -> "540p"
            minDim >= 480 -> "480p"
            else -> "${minDim}p"
        }
    }

    val fpsLabel: String get() {
        val fps = frameRate.numerator.toDouble() / frameRate.denominator
        val value = if (frameRate.numerator % frameRate.denominator == 0) fps.toInt().toString()
            else "%.2f".format(java.util.Locale.getDefault(), fps)
        return "${value}fps"
    }

    val durationLabel: String get() {
        val totalUs = durationUs ?: 0L
        val totalSec = totalUs / 1_000_000L
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    val formattedSize: String get() {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
    }

    companion object {
        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

class ProjectManager(private val context: Context) {
    companion object { private val fileLock = Mutex() }

    val projectsDir: File
        get() = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }

    suspend fun listProjects(): List<ProjectMetadata> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val projectFiles = mutableListOf<File>()

            // 1. Scan projects/ directory
            val inProjectsDir = projectsDir.listFiles { _, name -> name.endsWith(".fluxx") }
            if (inProjectsDir != null) {
                projectFiles.addAll(inProjectsDir)
            }

            // 2. Scan filesDir root for legacy or stray .fluxx files and migrate to projects/
            val inRootDir = context.filesDir.listFiles { _, name -> name.endsWith(".fluxx") }
            if (inRootDir != null) {
                for (rootFile in inRootDir) {
                    val target = File(projectsDir, rootFile.name)
                    if (!target.exists()) {
                        if (rootFile.renameTo(target)) {
                            projectFiles.add(target)
                        } else {
                            projectFiles.add(rootFile)
                        }
                    } else if (!projectFiles.contains(target)) {
                        projectFiles.add(target)
                    }
                }
            }

            // Refresh access once per distinct asset during this scan, using the relink check.
            val inspected = HashMap<AssetReference, AssetReference>()
            // Parse project files safely via ProjectCodec
            projectFiles.distinctBy { it.absolutePath }.mapNotNull { file ->
                runCatching {
                    val bytes = AtomicFile(file).readFully()
                    val doc = resolveReferences(ProjectCodec.decode(bytes), inspected)
                    val comp = doc.composition
                    ensureActive()
                    val name = file.nameWithoutExtension
                    val primaryAsset = comp.layers.firstOrNull { it.asset?.uri != null }?.asset?.uri
                    val missing = comp.layers.any { layer ->
                        val asset = layer.asset
                        asset != null && asset.access == MediaAccess.MISSING
                    }
                    ProjectMetadata(
                        file = file,
                        name = name,
                        lastModified = file.lastModified(),
                        sizeBytes = file.length(),
                        width = comp.width,
                        height = comp.height,
                        durationUs = comp.resolvedDurationUs,
                        frameRate = comp.frameRate,
                        layerCount = comp.layers.size,
                        hasMissingMedia = missing,
                        primaryAssetUri = primaryAsset,
                        // Android filesystems may not expose birth time; modified date is the fallback.
                        createdAt = runCatching {
                            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                                .creationTime().toMillis().takeIf { it > 0 }
                        }.getOrNull() ?: file.lastModified()
                    )
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }.sortedByDescending { it.lastModified }
        }
    }

    suspend fun deleteProject(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching {
                AtomicFile(file).delete()
                if (file.exists() || File(file.path + ".bak").exists()) {
                    throw IllegalStateException("Failed to delete project file: ${file.name}")
                }
            }
        }
    }

    suspend fun renameProject(file: File, newName: String): Result<File> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching {
                val cleanName = newName.trim()
                require(cleanName.isNotEmpty() && cleanName !in listOf(".", "..") &&
                    cleanName.none { it == '/' || it == '\\' || it.isISOControl() }) {
                    "Enter a name without slashes or control characters"
                }
                val target = File(file.parentFile ?: projectsDir, "$cleanName.fluxx")
                if (target.absolutePath == file.absolutePath) return@runCatching file
                if (target.exists() || File(target.path + ".bak").exists() || File(target.path + ".new").exists()) {
                    throw IllegalArgumentException("A project named '$cleanName' already exists")
                }
                // Recover any interrupted atomic save before moving the file.
                AtomicFile(file).openRead().close()
                if (file.renameTo(target)) {
                    target
                } else {
                    error("Could not rename project; the original file was kept")
                }
            }
        }
    }

    suspend fun duplicateProject(file: File): Result<File> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching {
                val baseName = file.nameWithoutExtension
                var copyIndex = 2
                var target = File(file.parentFile ?: projectsDir, "$baseName Copy.fluxx")
                while (target.exists() || File(target.path + ".bak").exists() || File(target.path + ".new").exists()) {
                    target = File(file.parentFile ?: projectsDir, "$baseName Copy $copyIndex.fluxx")
                    copyIndex++
                }
                val bytes = AtomicFile(file).readFully()
                val atomic = AtomicFile(target)
                val stream = atomic.startWrite()
                try { stream.write(bytes); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
                target
            }
        }
    }

    suspend fun saveProject(file: File, project: ProjectDocument): Result<Unit> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching { writeProject(file, project) }
        }
    }

    /** Canonical creation entry; persists through the same atomic writer as Save. */
    suspend fun createProject(settings: CompositionCreation): Result<File> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching {
                settings.validate()
                val file = File(projectsDir, "${settings.name.trim()}.fluxx")
                require(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                    "A project with that name already exists"
                }
                // Black is the compositor's clear color. Other backgrounds reuse a solid layer.
                val layers = if (settings.durationUs == null || settings.backgroundArgb == 0xff000000.toInt()) emptyList() else listOf(
                    CompositionLayer(1L, type = LayerType.SOLID, timing = ClipTiming(durationUs = settings.durationUs),
                        name = "Background", solidColorArgb = settings.backgroundArgb,
                        referenceWidth = settings.width, referenceHeight = settings.height))
                val project = ProjectDocument(Composition(settings.width, settings.height, settings.durationUs,
                    FrameRate(settings.fps), FrozenList.of(layers)))
                writeProject(file, project)
                file
            }
        }
    }

    private fun writeProject(file: File, project: ProjectDocument) {
        val bytes = ProjectCodec.encode(project)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    suspend fun loadProject(file: File): Result<ProjectDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = fileLock.withLock { AtomicFile(file).readFully() }
            resolveReferences(ProjectCodec.decode(bytes))
        }.also { it.exceptionOrNull()?.let { error -> if (error is CancellationException) throw error } }
    }

    /** Listing and opening share media-access and legacy-duration resolution. */
    private suspend fun resolveReferences(
        project: ProjectDocument,
        inspected: MutableMap<AssetReference, AssetReference> = HashMap()
    ): ProjectDocument {
        val resolved = HashMap<String, AssetReference>()
        project.composition.layers.mapNotNull { it.asset }.distinctBy { it.id }.forEach { asset ->
            currentCoroutineContext().ensureActive()
            resolved[asset.id] = inspected.getOrPut(asset) { inspect(asset) }
        }
        val layers = project.composition.layers.map { layer ->
            val asset = layer.asset?.let { resolved.getValue(it.id) }
            val duration = layer.timing.durationUs ?: asset?.durationUs?.takeIf { layer.type == LayerType.VIDEO }
                ?.let { (it - layer.timing.sourceInUs).coerceAtLeast(0) }
            layer.copy(asset = asset, timing = layer.timing.copy(durationUs = duration))
        }
        return project.copy(composition = project.composition.copy(layers = FrozenList.of(layers)))
    }

    /** Opening the URI is authoritative; session-only grants are valid too. */
    suspend fun inspect(asset: AssetReference): AssetReference = withContext(Dispatchers.IO) {
        val uri = asset.uri?.let(Uri::parse) ?: return@withContext asset.copy(access = MediaAccess.MISSING)
        val available = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }
            catch (_: Exception) { false }
        if (!available) return@withContext asset.copy(access = MediaAccess.MISSING)
        var duration = asset.durationUs
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            if (duration == null) {
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                        format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = format.getLong(MediaFormat.KEY_DURATION).takeIf { it >= 0 }
                        break
                    }
                }
            }
        } catch (_: Exception) { /* Accessible media can still have unresolved metadata. */ }
        finally { extractor.release() }
        asset.copy(access = MediaAccess.AVAILABLE, durationUs = duration)
    }
}
