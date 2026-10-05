package com.fluxx.android.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import kotlin.math.abs

/** Reuses begin/preview/commit/cancel; pointer input stays alive while document drafts change. */
@Composable
internal fun TextBoxWidthHandle(project: ProjectDocument,layer: CompositionLayer,time: Long,metrics: TextLayoutMetrics,
    begin: ()->Unit,preview: (Float)->Unit,commit: ()->Unit,cancel: ()->Unit) {
    val matrix=remember { FloatArray(16) }
    val local=time-layer.resolvedKeyframeAnchorUs
    // Renderer/controller report preparation/evaluation errors; invalid loaded boxes
    // must not crash Compose while the user repairs the document.
    val bounds=try { metrics.logical(layer.text,local) } catch(_: IllegalArgumentException) { return }
    val document=layer.text.source.evaluateDocument(local)
    LayerGeometry.textMatrix(matrix,layer.evaluatedTransform(time),bounds,project.composition.width,project.composition.height,
        layer.referenceWidth.takeIf { it>0 } ?: project.composition.width,layer.referenceHeight.takeIf { it>0 } ?: project.composition.height,
        layer.anchorX,layer.anchorY)
    val width=if(document.boxWidth>0) document.boxWidth else bounds.left+bounds.width
    val y=bounds.top+bounds.height/2
    val handle=Offset(matrix[0]*width+matrix[4]*y+matrix[12],matrix[1]*width+matrix[5]*y+matrix[13])
    val currentHandle by rememberUpdatedState(handle)
    val currentMatrix by rememberUpdatedState(matrix)
    val onBegin by rememberUpdatedState(begin);val onPreview by rememberUpdatedState(preview)
    val onCommit by rememberUpdatedState(commit);val onCancel by rememberUpdatedState(cancel)
    val colour=MaterialTheme.colorScheme.primary
    val outline=MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.fillMaxSize().pointerInput(layer.id) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            val h=currentHandle
            val point=Offset((h.x+1)*size.width/2,(h.y+1)*size.height/2)
            if((down.position-point).getDistance()>24.dp.toPx()) return@awaitEachGesture
            val m=currentMatrix.copyOf()
            val determinant=m[0]*m[5]-m[1]*m[4]
            if(abs(determinant)<1e-12f) return@awaitEachGesture
            down.consume();var completed=false
            onBegin()
            try {
                val released=drag(down.id) { change ->
                    change.consume()
                    val x=change.position.x*2/size.width-1-m[12]
                    val yy=change.position.y*2/size.height-1-m[13]
                    val requested=((x*m[5]-yy*m[4])/determinant).coerceAtLeast(0f)
                    if(requested.isFinite()) onPreview(requested)
                }
                completed=true;if(released) onCommit() else onCancel()
            } finally { if(!completed) onCancel() }
        }
    }) {
        val p=Offset((handle.x+1)*size.width/2,(handle.y+1)*size.height/2)
        drawCircle(outline,8.dp.toPx(),p);drawCircle(colour,6.dp.toPx(),p)
    }
}
