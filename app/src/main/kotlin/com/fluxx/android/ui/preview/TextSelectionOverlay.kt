package com.fluxx.android.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import kotlin.math.*

/** Transient selection never enters the document/gesture undo transaction. Only handle
 * downs are consumed; the parent continues to own taps, long presses and layer gestures. */
@Composable
internal fun TextSelectionOverlay(project: ProjectDocument,layer: CompositionLayer,time: Long,
    metrics: TextLayoutMetrics,range: TextSelectionRange,change: (TextSelectionRange?)->Unit) {
    val local=time-layer.resolvedKeyframeAnchorUs
    val geometry=remember(metrics,layer.text,local) { metrics.geometry(layer.text,local) } ?: return
    val selected=geometry.starts.indices.filter { geometry.starts[it]<range.end && geometry.ends[it]>range.start }
    if(selected.isEmpty()) return
    val matrix=FloatArray(16)
    LayerGeometry.textMatrix(matrix,layer.evaluatedTransform(time),metrics.logical(layer.text,local),
        project.composition.width,project.composition.height,layer.referenceWidth.takeIf {it>0} ?: project.composition.width,
        layer.referenceHeight.takeIf {it>0} ?: project.composition.height,layer.anchorX,layer.anchorY)
    val first=selected.minBy {geometry.starts[it]};val last=selected.maxBy {geometry.ends[it]}
    val handles=arrayOf(Offset(geometry.boxes[first*4],geometry.boxes[first*4+3]),
        Offset(geometry.boxes[last*4+2],geometry.boxes[last*4+3]))
    val currentMatrix by rememberUpdatedState(matrix);val currentGeometry by rememberUpdatedState(geometry)
    val currentHandles by rememberUpdatedState(handles);val currentRange by rememberUpdatedState(range)
    val onChange by rememberUpdatedState(change)
    val colour=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().pointerInput(layer.id) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            val m=currentMatrix.copyOf();val h=currentHandles
            fun screen(p: Offset)=Offset((m[0]*p.x+m[4]*p.y+m[12]+1)*size.width/2,
                (m[1]*p.x+m[5]*p.y+m[13]+1)*size.height/2)
            val handle=h.indices.firstOrNull {(screen(h[it])-down.position).getDistance()<=24.dp.toPx()} ?: return@awaitEachGesture
            val determinant=m[0]*m[5]-m[1]*m[4];if(abs(determinant)<1e-12f) return@awaitEachGesture
            val original=currentRange;val snapshot=currentGeometry;down.consume()
            drag(down.id) { pointer ->
                pointer.consume()
                val x=pointer.position.x*2/size.width-1-m[12];val y=pointer.position.y*2/size.height-1-m[13]
                val hit=snapshot.hit((x*m[5]-y*m[4])/determinant,(y*m[0]-x*m[1])/determinant,true)
                if(hit>=0) {
                    val edge=if(handle==0) snapshot.starts[hit] else snapshot.ends[hit]
                    onChange(snapshot.safeRange(if(handle==0) edge else original.start,if(handle==1) edge else original.end))
                }
            }
        }
    }) {
        fun screen(x: Float,y: Float)=Offset((matrix[0]*x+matrix[4]*y+matrix[12]+1)*size.width/2,
            (matrix[1]*x+matrix[5]*y+matrix[13]+1)*size.height/2)
        // Union adjacent cluster bounds on each visual line, without touching glyph meshes.
        for(group in selected.groupBy {geometry.lines[it]}.values) {
            val x=group.minOf {geometry.boxes[it*4]};val y=group.minOf {geometry.boxes[it*4+1]}
            val r=group.maxOf {geometry.boxes[it*4+2]};val b=group.maxOf {geometry.boxes[it*4+3]}
            val path=Path();val a=screen(x,y);val c=screen(r,y);val d=screen(r,b);val e=screen(x,b)
            path.moveTo(a.x,a.y);path.lineTo(c.x,c.y);path.lineTo(d.x,d.y);path.lineTo(e.x,e.y);path.close()
            drawPath(path,colour.copy(alpha=.25f))
        }
        for(h in handles) { val p=screen(h.x,h.y);drawLine(colour,p,p+Offset(0f,10.dp.toPx()),2.dp.toPx());drawCircle(colour,6.dp.toPx(),p+Offset(0f,10.dp.toPx())) }
    }
}
