package com.fluxx.android.export

import android.content.Context
import android.opengl.Matrix
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.*
import com.fluxx.android.Layer
import com.fluxx.android.model.CompositionDefaults
import kotlinx.coroutines.*
import java.io.File

/** Independent decode/effect/encode pipeline: never touches the preview engine. */
@UnstableApi
class VideoExporter(private val context: Context) {
    suspend fun export(layer: Layer, output: File, progress: (Int?) -> Unit): ExportResult =
        withContext(Dispatchers.Main.immediate) {
            val source = requireNotNull(layer.assetUri) { "Select a video before exporting" }
            val completion = CompletableDeferred<ExportResult>()
            val transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        completion.complete(result)
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        completion.completeExceptionally(exception)
                    }
                }).build()
            val effects = listOf(
                LayerTransformation(layer.copy()),
                RgbAdjustment.Builder().setRedScale(layer.opacity)
                    .setGreenScale(layer.opacity).setBlueScale(layer.opacity).build()
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), effects)).build()
            try {
                // No frame-rate override: retain source timing and keep audio synchronized.
                transformer.start(item, output.absolutePath)
                coroutineScope {
                    val poller = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            progress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE)
                                holder.progress.coerceIn(0, 99) else null)
                            delay(500)
                        }
                    }
                    try {
                        completion.await().also {
                            check(it.videoFrameCount > 0 && output.length() > 0) { "Encoder produced no video frames" }
                        }
                    } finally { poller.cancelAndJoin() }
                }
            } finally { transformer.cancel() }
        }
}

/** Preview uses downward Y; GL uses upward Y. Keep rotation and translation visually identical. */
@UnstableApi
internal class LayerTransformation(private val layer: Layer) : GlMatrixTransformation {
    private val matrix = FloatArray(16)
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val width = CompositionDefaults.WIDTH.toFloat()
        val height = CompositionDefaults.HEIGHT.toFloat()
        val fit = minOf(width / inputWidth, height / inputHeight)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, layer.positionX, -layer.positionY, 0f)
        Matrix.scaleM(matrix, 0, 2f / width, 2f / height, 1f)
        Matrix.rotateM(matrix, 0, -layer.rotationDegrees, 0f, 0f, 1f)
        Matrix.scaleM(matrix, 0, layer.scaleX * inputWidth * fit / 2f,
            layer.scaleY * inputHeight * fit / 2f, 1f)
        return Size(width.toInt(), height.toInt())
    }
    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray = matrix
}
