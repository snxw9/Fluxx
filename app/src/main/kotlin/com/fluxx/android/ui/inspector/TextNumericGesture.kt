package com.fluxx.android.ui.inspector

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import kotlin.math.*

internal class TextNumericBindings(val begin: (TextControl)->TextNumericDraft,val preview: (TextProperties)->Unit,
    val finish: ()->Unit,val cancel: ()->Unit,val focus: (TextControl)->Unit,val enabled: Boolean=true)
internal val LocalTextNumericBindings=staticCompositionLocalOf<TextNumericBindings?> {null}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextScrubCell(draft: TextNumericDraft,modifier: Modifier,selected: Boolean,iconClick: (()->Unit)?=null,open: ()->Unit) {
    val bindings=LocalTextNumericBindings.current
    val c=draft.control;val label="${draft.scope.title(c)}, ${draft.scope.unit(c)}"
    val available=draft.scope.available(c) && bindings?.enabled!=false
    var tooltip by remember {mutableStateOf(false)};var dragging by remember {mutableStateOf(false)}
    var fine by remember {mutableStateOf(false)};var value by remember {mutableFloatStateOf(draft.initial)}
    var captured by remember {mutableStateOf<TextNumericDraft?>(null)};var pending by remember {mutableStateOf<Float?>(null)}
    var lastTime by remember {mutableLongStateOf(0)};var lastHaptic by remember {mutableLongStateOf(0)}
    val density=LocalDensity.current;val view=LocalView.current
    val currentBindings by rememberUpdatedState(bindings);val currentDraft by rememberUpdatedState(draft)
    LaunchedEffect(draft.initial) {if(!dragging) value=draft.initial}
    LaunchedEffect(dragging) {while(dragging) {withFrameNanos { };pending?.let {v->pending=null;captured?.let {d->runCatching {d.payload(v)}.onSuccess {currentBindings?.preview?.invoke(it)}}}}}
    DisposableEffect(c) {onDispose {if(dragging) currentBindings?.cancel?.invoke()}}
    fun step(direction: Float): Boolean {
        val binding=currentBindings ?: return false
        val d=binding.begin(c);runCatching {d.payload(d.scrubValue(d.initial+direction))}.onSuccess {binding.preview(it);binding.finish()}.onFailure {binding.cancel()}
        return true
    }
    Row(modifier.height(48.dp).background(if(selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).fillMaxHeight()
            .combinedClickable(enabled=available,onClick={iconClick?.invoke() ?: currentBindings?.focus?.invoke(c)},onLongClick={tooltip=true})
            .semantics {contentDescription=if(available) "Scrub $label"+(if(iconClick!=null) "; tap for choices" else "") else "$label; clear selection to edit layer property"
                customActions=if(available) listOfNotNull(CustomAccessibilityAction("Increase") {step(1f)},CustomAccessibilityAction("Decrease") {step(-1f)},CustomAccessibilityAction("Set exact value") {open();true},
                    iconClick?.let {CustomAccessibilityAction("Choices") {it();true}}) else emptyList()
                if(!available) disabled()
            }
            .pointerInput(c,available) {if(available) awaitEachGesture {
                do {val event=awaitPointerEvent(PointerEventPass.Initial);fine=event.changes.count {it.pressed}>1} while(event.changes.any {it.pressed})
                fine=false
            }}
            .pointerInput(c,available) {if(available) detectHorizontalDragGestures(
                onDragStart={val binding=currentBindings;if(binding!=null) {captured=binding.begin(c);value=captured!!.initial;dragging=true;lastTime=0}},
                onHorizontalDrag={change,dx->if(dragging) {
                    change.consume();val now=change.uptimeMillis;val dt=if(lastTime==0L) 16L else (now-lastTime).coerceAtLeast(1);lastTime=now
                    val dp=dx/density.density;val old=value
                    value=captured!!.scrubValue(value+TextScrubTuning.delta(c,dp,dp*1000/dt,fine));pending=value
                    val detent=if(c==TextControl.TRACKING || c==TextControl.MANUAL_KERNING) TextScrubTuning.DETENT_TRACKING else TextScrubTuning.DETENT_PX
                    if(floor(old/detent)!=floor(value/detent) && now-lastHaptic>=TextScrubTuning.HAPTIC_INTERVAL_MS) {view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);lastHaptic=now}
                }},onDragEnd={pending=null;captured?.let {d->runCatching {d.payload(value)}.onSuccess {currentBindings?.preview?.invoke(it)}};dragging=false;currentBindings?.finish?.invoke();captured=null},
                onDragCancel={pending=null;dragging=false;value=currentDraft.initial;currentBindings?.cancel?.invoke();captured=null})},contentAlignment=Alignment.Center) {
            Icon(TextInspectorIcons.control(c),null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurface.copy(alpha=if(available) 1f else .38f))
            DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(label)},onClick={tooltip=false})}
        }
        TextButton(onClick=open,enabled=available,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1f).fillMaxHeight().semantics {contentDescription="$label, ${if(draft.mixed) "Mixed" else value}; exact entry"}) {
            Text(if(draft.mixed && !dragging) "—" else "%.2f".format(java.util.Locale.ROOT,value),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
        }
    }
}
