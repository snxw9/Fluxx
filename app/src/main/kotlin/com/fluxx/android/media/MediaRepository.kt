package com.fluxx.android.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.fluxx.android.ProjectManager
import com.fluxx.android.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Provider access, metadata, thumbnails and grant handling stay outside the picker UI. */
class MediaRepository(private val context: Context) {
    data class Entry(val uri: String, val name: String, val type: LayerType,
        val width: Int = 0, val height: Int = 0, val durationMs: Long? = null)
    data class Directory(val id: String, val name: String)
    data class AudioEntry(val uri: String, val name: String, val album: String, val artist: String, val durationMs: Long)
    data class AudioGroup(val id: String, val name: String)

    suspend fun audioGroups(artists: Boolean): List<AudioGroup> = withContext(Dispatchers.IO) {
        val collection = if (artists) MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI else MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
        val column = if (artists) MediaStore.Audio.Artists.ARTIST else MediaStore.Audio.Albums.ALBUM
        val result = mutableListOf<AudioGroup>()
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, column), null, null, "$column ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                ensureActive()
                result.add(AudioGroup(cursor.getString(0), cursor.getString(1).orEmpty()))
            }
        }
        result
    }

    suspend fun audio(offset: Int = 0, albumId: String? = null, artistId: String? = null): List<AudioEntry> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val args = android.os.Bundle().apply {
            putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 50)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Audio.Media.TITLE} ASC, ${MediaStore.MediaColumns._ID} ASC")
            val groupId = albumId ?: artistId
            if (groupId != null) {
                val column = if (albumId != null) MediaStore.Audio.Media.ALBUM_ID else MediaStore.Audio.Media.ARTIST_ID
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "$column = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(groupId))
            }
        }
        val result = mutableListOf<AudioEntry>()
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION), args, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 50) {
                ensureActive()
                result.add(AudioEntry(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(),
                    cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getLong(4)))
            }
        }
        result
    }
    data class Details(val width: Int, val height: Int, val durationMs: Long?, val frameRate: Float?)
    data class FilmstripFrame(val timeMs: Long, val bitmap: Bitmap)
    data class VideoInfo(val mime: String, val width: Int, val height: Int, val hdr: Boolean, val maxInstances: Int,
        val frameRate: Float? = null, val durationUs: Long? = null,
        val rotation: Int = 0, val pixelAspect: Float = 1f)
    private val thumbnailPermits = Semaphore(3)
    private val filmstripPermit = Semaphore(1)
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val videoInfo = mutableMapOf<String, VideoInfo>()

    suspend fun browse(type: LayerType, offset: Int = 0, pageSize: Int = 100, directoryId: String? = null): List<Entry> = withContext(Dispatchers.IO) {
        require(type == LayerType.VIDEO || type == LayerType.IMAGE)
        require(offset >= 0 && pageSize in 1..200)
        val collection = if(type == LayerType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val results = ArrayList<Entry>(pageSize)
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_ADDED+" DESC, "+MediaStore.MediaColumns._ID+" DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, pageSize)
            if (directoryId != null) {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.BUCKET_ID} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(directoryId))
            }
        }
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT) +
                if (type == LayerType.VIDEO) arrayOf(MediaStore.Video.VideoColumns.DURATION) else emptyArray()
            context.contentResolver.query(collection, projection,args,null)?.use { cursor ->
                while(cursor.moveToNext() && results.size < pageSize) {
                    ensureActive()
                    results.add(Entry(ContentUris.withAppendedId(collection,cursor.getLong(0)).toString(),cursor.getString(1).orEmpty(),type,
                        cursor.getInt(2), cursor.getInt(3), if (type == LayerType.VIDEO && !cursor.isNull(4)) cursor.getLong(4) else null))
                }
            }
        } catch(_: SecurityException) { /* Limited/denied library permission does not invalidate document imports. */ }
        results
    }

    suspend fun directories(type: LayerType): List<Directory> = withContext(Dispatchers.IO) {
        require(type == LayerType.VIDEO || type == LayerType.IMAGE)
        val collection = if (type == LayerType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val buckets = linkedMapOf<String, Directory>()
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                ensureActive()
                val id = cursor.getString(0) ?: continue
                buckets.putIfAbsent(id, Directory(id, cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Unnamed folder"))
            }
        }
        buckets.values.sortedBy { it.name.lowercase() }
    }

    /** Reuse the same extractor/cache as render capability inspection for video details. */
    suspend fun details(entry: Entry): Details = withContext(Dispatchers.IO) {
        val info = if (entry.type == LayerType.VIDEO) {
            try { video(entry.uri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        } else null
        val imageSize = if (entry.type == LayerType.IMAGE && (entry.width <= 0 || entry.height <= 0)) {
            var dimensions = Size(0, 0)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(entry.uri))) { decoder, imageInfo, _ ->
                dimensions = imageInfo.size
                decoder.setTargetSize(1, 1)
            }.recycle()
            dimensions
        } else null
        Details(info?.width ?: imageSize?.width ?: entry.width, info?.height ?: imageSize?.height ?: entry.height,
            info?.durationUs?.div(1000) ?: entry.durationMs, info?.frameRate)
    }

    /** A bounded preview strip, never full-resolution frame decoding in grid cells. */
    suspend fun filmstrip(entry: Entry): List<FilmstripFrame> = filmstripPermit.withPermit {
        withContext(Dispatchers.IO) {
            val duration = entry.durationMs ?: details(entry).durationMs ?: return@withContext emptyList()
            if (duration <= 0) return@withContext emptyList()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(entry.uri))
                (0 until 8).mapNotNull { index ->
                    ensureActive()
                    val timeMs = (duration - 1) * index / 7
                    retriever.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                        ?.let { FilmstripFrame(timeMs, it) }
                }
            } finally { retriever.release() }
        }
    }

    suspend fun thumbnail(uri: String): Bitmap? = thumbnailPermits.withPermit { withContext(Dispatchers.IO) {
        thumbnails.get(uri) ?: try {
            context.contentResolver.loadThumbnail(Uri.parse(uri), Size(160,160), null).also { thumbnails.put(uri,it) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    } }

    suspend fun reference(uri: Uri, identity: String = UUID.randomUUID().toString()): Pair<AssetReference, LayerType> = withContext(Dispatchers.IO) {
        try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* A temporary grant is enough for the current session. */ }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val type = when {
            mime.startsWith("video/") -> LayerType.VIDEO
            mime.startsWith("image/") -> LayerType.IMAGE
            else -> error("Choose a video or image")
        }
        val asset = ProjectManager(context).inspect(AssetReference(identity,uri.toString()))
        check(asset.access == MediaAccess.AVAILABLE) { "This media cannot be opened. Select it again." }
        if (type == LayerType.VIDEO) check(asset.durationUs != null && asset.durationUs > 0) { "Video duration could not be read" }
        asset to type
    }

    fun hasPersistentAccess(uri: String): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }

    fun image(uri: String, maxEdge: Int = 1920): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(uri))) { decoder, info, _ ->
        val ratio = minOf(1f, maxEdge.toFloat() / maxOf(info.size.width,info.size.height))
        decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    @Synchronized fun video(uri: String): VideoInfo = videoInfo.getOrPut(uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: error("No decoder supports this video")
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == codec }
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            val geometry = com.fluxx.android.engine.VideoGeometry.from(format)
            VideoInfo(mime,format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT),
                transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
                info.getCapabilitiesForType(mime).maxSupportedInstances,
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() }.getOrNull() else null,
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null,
                geometry.rotation, geometry.pixelAspect)
        } finally { extractor.release() }
    }
}

object CompositionSupport {
    fun check(context: Context, repository: MediaRepository, project: ProjectDocument) {
        val comp = project.composition
        require(comp.width % 2 == 0 && comp.height % 2 == 0 && comp.width.toLong()*comp.height <= 1920L*1080) { "Choose an even-sized canvas up to 1080p" }
        require(comp.resolvedDurationUs > 0) { "Add media or set a composition duration" }
        require(comp.frameRate.numerator.toDouble()/comp.frameRate.denominator in 1.0..120.0) { "Choose a frame rate from 1 to 120 fps" }
        val layers = comp.layers.filter { it.visible && it.timing.startUs < comp.resolvedDurationUs && it.timing.durationUs != 0L }

        layers.forEach {
            require(it.type != LayerType.TEXT) { "Text layers are not supported yet" }
            require(it.timing.durationUs != null) { "Relink media with an unresolved duration" }
            require(it.asset == null || it.asset.access == MediaAccess.AVAILABLE) { "Relink missing media or hide its layer" }
        }
        val videos = layers.asSequence().filter { it.type == LayerType.VIDEO }
            .map { requireNotNull(it.asset?.uri) }.distinct().map { repository.video(it) }
        require(videos.none { it.hdr }) { "HDR layers are not supported in layered preview yet. Use SDR media." }
    }
}
