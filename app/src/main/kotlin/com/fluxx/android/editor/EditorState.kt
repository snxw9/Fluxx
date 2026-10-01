package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry

data class EditorState(
    val project: ProjectDocument = ProjectDocument(),
    val selectedLayerId: Long? = null,
    val playheadUs: Long = 0,
    val selectedMarkerId: Long? = null
) {
    val selectedLayer: CompositionLayer? get() = project.composition.layers.firstOrNull { it.id == selectedLayerId }
}

enum class AnimPropertyType { POSITION, SCALE, ROTATION, OPACITY, FONT_SIZE, FILL_COLOUR, SOURCE_TEXT }

sealed interface EditorAction {
    data class SelectMarker(val markerId: Long?) : EditorAction
    data class AddCompositionMarker(val marker: Marker) : EditorAction
    data class EditCompositionMarker(val id: Long, val colorArgb: Int, val description: String) : EditorAction
    data class DeleteCompositionMarker(val id: Long) : EditorAction
    data class AddLayerMarker(val layerId: Long, val marker: Marker) : EditorAction
    data class EditLayerMarker(val layerId: Long, val id: Long, val colorArgb: Int, val description: String) : EditorAction
    data class DeleteLayerMarker(val layerId: Long, val id: Long) : EditorAction
    data class FlipHorizontal(val id: Long, val playheadUs: Long) : EditorAction
    data class FlipVertical(val id: Long, val playheadUs: Long) : EditorAction
    data class FitToWidth(val id: Long, val playheadUs: Long,
        val sourceWidth: Int, val sourceHeight: Int, val sourceRotation: Int = 0,
        val pixelAspect: Float = 1f) : EditorAction
    data class FitToHeight(val id: Long, val playheadUs: Long,
        val sourceWidth: Int, val sourceHeight: Int, val sourceRotation: Int = 0,
        val pixelAspect: Float = 1f) : EditorAction
    data class StretchToComposition(val id: Long, val playheadUs: Long,
        val sourceWidth: Int, val sourceHeight: Int, val sourceRotation: Int = 0,
        val pixelAspect: Float = 1f) : EditorAction
    data class FitText(val id: Long, val playheadUs: Long, val bounds: TextBounds, val mode: Int) : EditorAction

    /** One undoable edit, including gestures that change several animated properties. */
    data class Batch(val actions: List<EditorAction>) : EditorAction
    data class Load(val project: ProjectDocument) : EditorAction
    data class Select(val id: Long?) : EditorAction
    data class Seek(val timeUs: Long, val snap: Boolean = false) : EditorAction
    data class Add(val layer: CompositionLayer) : EditorAction
    data class Remove(val id: Long) : EditorAction
    data class SetTransform(val id: Long, val transform: Transform) : EditorAction
    data class SetTextPayload(val id: Long, val text: TextProperties, val newPosition: Transform? = null) : EditorAction
    data class SetStringKeyframe(val id: Long, val localTimeUs: Long, val value: String) : EditorAction
    data class SetColourKeyframe(val id: Long, val localTimeUs: Long, val argb: Int,
        val easing: EasingPreset = EasingPreset.EASY_EASE) : EditorAction
    data class SetAnchorPoint(val id: Long, val anchorX: Float, val anchorY: Float,
        val newPosition: Transform? = null) : EditorAction
    data class SetTiming(val id: Long, val timing: ClipTiming) : EditorAction
    data class Move(val id: Long, val startUs: Long) : EditorAction
    /** Trim edges in composition time, retaining the source mapping at every surviving timestamp. */
    data class Trim(val id: Long, val startUs: Long, val endUs: Long) : EditorAction
    data class Split(val id: Long, val atTimeUs: Long) : EditorAction
    data class SetVisibility(val id: Long, val visible: Boolean) : EditorAction
    data class SetOrder(val id: Long, val zOrder: Int) : EditorAction
    data class Reorder(val id: Long, val towardFront: Boolean) : EditorAction
    data class ReorderLayer(val id: Long, val targetId: Long) : EditorAction
    data class SetAudio(val id: Long, val muted: Boolean, val gain: Float) : EditorAction
    data class Rename(val id: Long, val name: String) : EditorAction
    data class SetColor(val id: Long, val argb: Int) : EditorAction
    data class SetComposition(val width: Int, val height: Int, val durationUs: Long?, val frameRate: FrameRate,
        val startTimecodeUs: Long? = null) : EditorAction
    /** Replaces the URI for every clip referencing this asset while retaining timing and edits. */
    data class Relink(val assetId: String, val replacement: AssetReference) : EditorAction
    /** Async metadata may update an asset only if its URI has not changed since the request. */
    data class ResolveAsset(val expectedUri: String?, val asset: AssetReference) : EditorAction
    // Phase 2 Step D: Additive keyframe actions
    data class ToggleAnimated(val id: Long, val property: AnimPropertyType, val enable: Boolean, val playheadUs: Long) : EditorAction
    data class SetKeyframe(val id: Long, val property: AnimPropertyType, val localTimeUs: Long, val value: Float, val easing: EasingPreset = EasingPreset.EASY_EASE) : EditorAction
    data class SetKeyframe2D(val id: Long, val property: AnimPropertyType, val localTimeUs: Long, val x: Float, val y: Float, val easing: EasingPreset = EasingPreset.EASY_EASE) : EditorAction
    data class RemoveKeyframe(val id: Long, val property: AnimPropertyType, val localTimeUs: Long) : EditorAction
    data class MoveKeyframe(val id: Long, val property: AnimPropertyType? = null, val oldTimeUs: Long, val newTimeUs: Long) : EditorAction
    data class ResetPropertyAnimation(val id: Long, val property: AnimPropertyType) : EditorAction
}

object EditorReducer {
    fun reduce(state: EditorState, action: EditorAction): EditorState {
        TextPropertyActions.apply(state, action)?.let { return reduce(state, it) }
        val comp = state.project.composition
        fun replaceLayers(layers: List<CompositionLayer>): EditorState {
            val maxEnd = layers.mapNotNull { it.timing.endUs }.maxOrNull() ?: 0L
            val computedDuration = comp.durationUs?.let { maxOf(it, maxEnd) }
            val next = state.copy(
                project = state.project.copy(
                    composition = comp.copy(
                        layers = FrozenList.of(layers),
                        durationUs = computedDuration
                    )
                )
            )
            val time = next.playheadUs.coerceAtMost(next.project.composition.resolvedDurationUs)
            return next.copy(playheadUs = time, selectedMarkerId = next.selectedMarkerId.takeIf { time == next.playheadUs })
        }
        fun update(id: Long, block: (CompositionLayer) -> CompositionLayer): EditorState {
            val original = requireNotNull(comp.layers.firstOrNull { it.id == id }) { "Layer not found: $id" }
            val updated = block(original)
            if (updated == original) return state
            return replaceLayers(comp.layers.map { if (it.id == id) updated else it })
        }
        fun setScale(id: Long, playheadUs: Long, scales: (CompositionLayer) -> LayerGeometry.FitScales): EditorState = update(id) { layer ->
            val target = scales(layer)
            require(target.scaleX.isFinite() && target.scaleY.isFinite())
            val track = layer.animTransform.scale
            if (track.isAnimated) {
                val local = Math.subtractExact(playheadUs, layer.resolvedKeyframeAnchorUs)
                val existing = track.keyframes.firstOrNull { it.timeUs == local }
                val key = existing?.copy(x = target.scaleX, y = target.scaleY)
                    ?: Keyframe2D(local, target.scaleX, target.scaleY)
                layer.copy(animTransform = layer.animTransform.copy(scale = track.copy(
                    keyframes = FrozenList.of((track.keyframes.filterNot { it.timeUs == local } + key).sortedBy { it.timeUs }))))
            } else layer.copy(transform = layer.transform.copy(scaleX = target.scaleX, scaleY = target.scaleY),
                animTransform = layer.animTransform.copy(scale = track.copy(staticX = target.scaleX, staticY = target.scaleY)))
        }
        fun compositionMarkers(markers: FrozenList<Marker>) = state.copy(
            project = state.project.copy(composition = comp.copy(markers = markers)))
        return when (action) {
            is EditorAction.FitText -> setScale(action.id, action.playheadUs) { layer ->
                require(layer.type == LayerType.TEXT && !action.bounds.empty)
                val x = comp.width / action.bounds.width; val y = comp.height / action.bounds.height
                when (action.mode) { 0 -> LayerGeometry.FitScales(x, x); 1 -> LayerGeometry.FitScales(y, y)
                    else -> LayerGeometry.FitScales(x, y) }
            }
            is EditorAction.SelectMarker -> {
                require(action.markerId == null || (state.selectedLayer?.markers ?: comp.markers).any { it.id == action.markerId })
                state.copy(selectedMarkerId = action.markerId)
            }
            is EditorAction.AddCompositionMarker -> {
                require(action.marker.timeUs >= 0)
                val markers = Markers.upsert(comp.markers, action.marker, 0, comp.frameRate)
                compositionMarkers(markers).copy(selectedLayerId = null,
                    selectedMarkerId = Markers.atFrame(markers, 0, action.marker.timeUs, comp.frameRate)?.id)
            }
            is EditorAction.EditCompositionMarker -> compositionMarkers(FrozenList.of(comp.markers.map {
                if (it.id == action.id) it.copy(colorArgb = action.colorArgb, description = action.description) else it
            }))
            is EditorAction.DeleteCompositionMarker -> compositionMarkers(FrozenList.of(comp.markers.filterNot { it.id == action.id }))
                .let { if (state.selectedLayerId == null && state.selectedMarkerId == action.id) it.copy(selectedMarkerId = null) else it }
            is EditorAction.AddLayerMarker -> update(action.layerId) {
                it.copy(markers = Markers.upsert(it.markers, action.marker, it.resolvedKeyframeAnchorUs, comp.frameRate))
            }.let { updated ->
                val layer = updated.project.composition.layers.first { it.id == action.layerId }
                updated.copy(selectedLayerId = layer.id, selectedMarkerId = layer.markers.first {
                    Markers.signedFrame(comp.frameRate, Math.addExact(layer.resolvedKeyframeAnchorUs, it.timeUs)) ==
                        Markers.signedFrame(comp.frameRate, Math.addExact(layer.resolvedKeyframeAnchorUs, action.marker.timeUs))
                }.id)
            }
            is EditorAction.EditLayerMarker -> update(action.layerId) { layer ->
                layer.copy(markers = FrozenList.of(layer.markers.map {
                    if (it.id == action.id) it.copy(colorArgb = action.colorArgb, description = action.description) else it
                }))
            }
            is EditorAction.DeleteLayerMarker -> update(action.layerId) { layer ->
                layer.copy(markers = FrozenList.of(layer.markers.filterNot { it.id == action.id }))
            }.let { if (state.selectedLayerId == action.layerId && state.selectedMarkerId == action.id) it.copy(selectedMarkerId = null) else it }
            is EditorAction.FlipHorizontal -> setScale(action.id, action.playheadUs) {
                val t = it.evaluatedTransform(action.playheadUs)
                LayerGeometry.FitScales(-t.scaleX, t.scaleY)
            }
            is EditorAction.FlipVertical -> setScale(action.id, action.playheadUs) {
                val t = it.evaluatedTransform(action.playheadUs)
                LayerGeometry.FitScales(t.scaleX, -t.scaleY)
            }
            is EditorAction.FitToWidth -> setScale(action.id, action.playheadUs) {
                require(it.type == LayerType.VIDEO || it.type == LayerType.IMAGE)
                LayerGeometry.computeFitToWidth(comp.width, comp.height,
                    it.referenceWidth.takeIf { size -> size > 0 } ?: comp.width,
                    it.referenceHeight.takeIf { size -> size > 0 } ?: comp.height,
                    action.sourceWidth, action.sourceHeight, action.sourceRotation, action.pixelAspect)
            }
            is EditorAction.FitToHeight -> setScale(action.id, action.playheadUs) {
                require(it.type == LayerType.VIDEO || it.type == LayerType.IMAGE)
                LayerGeometry.computeFitToHeight(comp.width, comp.height,
                    it.referenceWidth.takeIf { size -> size > 0 } ?: comp.width,
                    it.referenceHeight.takeIf { size -> size > 0 } ?: comp.height,
                    action.sourceWidth, action.sourceHeight, action.sourceRotation, action.pixelAspect)
            }
            is EditorAction.StretchToComposition -> setScale(action.id, action.playheadUs) {
                require(it.type == LayerType.VIDEO || it.type == LayerType.IMAGE)
                LayerGeometry.computeStretch(comp.width, comp.height,
                    it.referenceWidth.takeIf { size -> size > 0 } ?: comp.width,
                    it.referenceHeight.takeIf { size -> size > 0 } ?: comp.height,
                    action.sourceWidth, action.sourceHeight, action.sourceRotation, action.pixelAspect)
            }
            is EditorAction.Batch -> action.actions.fold(state) { current, edit -> reduce(current, edit) }
            is EditorAction.Load -> EditorState(action.project, null)
            is EditorAction.Select -> {
                require(action.id == null || comp.layers.any { it.id == action.id })
                state.copy(selectedLayerId = action.id, selectedMarkerId = null)
            }
            is EditorAction.Seek -> {
                require(action.timeUs >= 0)
                val time = if (comp.layers.isEmpty() && comp.durationUs == null) 0L
                    else if (action.snap) comp.frameRate.snap(action.timeUs) else action.timeUs
                val layer = state.selectedLayer
                val marker = Markers.atFrame(layer?.markers ?: comp.markers, layer?.resolvedKeyframeAnchorUs ?: 0, time, comp.frameRate)
                state.copy(playheadUs = time, selectedMarkerId = state.selectedMarkerId.takeIf { it == marker?.id })
            }
            is EditorAction.Add -> replaceLayers(comp.layers + action.layer.withReferenceCanvas(comp.width, comp.height))
                .copy(selectedLayerId = action.layer.id, selectedMarkerId = null)
            is EditorAction.Remove -> replaceLayers(comp.layers.filterNot { it.id == action.id }).let {
                if (state.selectedLayerId == action.id) it.copy(selectedLayerId = null, selectedMarkerId = null) else it
            }
            is EditorAction.SetTransform -> update(action.id) { it.copy(transform = action.transform) }
            is EditorAction.SetTextPayload -> update(action.id) {
                require(it.type == LayerType.TEXT)
                val position = action.newPosition.takeUnless { _ -> it.text.layoutAnimated || action.text.layoutAnimated || it.animTransform.position.isAnimated }
                it.copy(text = action.text, transform = if (position == null) it.transform else
                    it.transform.copy(positionX = position.positionX, positionY = position.positionY))
            }
            is EditorAction.SetStringKeyframe, is EditorAction.SetColourKeyframe -> error("Text action was not normalized")
            is EditorAction.SetAnchorPoint -> update(action.id) {
                require(action.anchorX.isFinite() && action.anchorY.isFinite())
                // Compensation changes only static Position, never another channel or keyframe curve.
                val position = action.newPosition.takeUnless { _ -> it.animTransform.position.isAnimated }
                it.copy(anchorX = action.anchorX.coerceIn(0f, 1f), anchorY = action.anchorY.coerceIn(0f, 1f),
                    transform = if (position == null) it.transform else it.transform.copy(
                        positionX = position.positionX, positionY = position.positionY))
            }
            is EditorAction.SetTiming -> update(action.id) {
                it.copy(timing = action.timing, keyframeAnchorUs = it.keyframeAnchorUs?.let { anchor ->
                    Math.addExact(anchor, Math.subtractExact(action.timing.startUs, it.timing.startUs))
                })
            }
            is EditorAction.Move -> update(action.id) { it.movedTo(action.startUs) }
            is EditorAction.Trim -> update(action.id) {
                require(action.startUs >= 0 && action.endUs >= action.startUs)
                val delta = action.startUs - it.timing.startUs
                val sourceIn = if (it.type == LayerType.VIDEO) Math.addExact(it.timing.sourceInUs, delta) else 0L
                val timing = ClipTiming(action.startUs, sourceIn, action.endUs - action.startUs)
                if (timing == it.timing) it else it.copy(timing = timing, keyframeAnchorUs = it.resolvedKeyframeAnchorUs)
            }
            is EditorAction.Split -> {
                val original = comp.layers.firstOrNull { it.id == action.id } ?: return state
                val end = original.timing.endUs ?: return state
                val p = action.atTimeUs
                if (p <= original.timing.startUs || p >= end) return state
                val newId = nextLayerId(state.project)
                val left = original.copy(timing = original.timing.copy(durationUs = p - original.timing.startUs),
                    keyframeAnchorUs = original.resolvedKeyframeAnchorUs,
                    markers = FrozenList.of(original.markers.filter { Math.addExact(original.resolvedKeyframeAnchorUs, it.timeUs) < p }))
                val sourceIn = if (original.type == LayerType.VIDEO)
                    Math.addExact(original.timing.sourceInUs, p - original.timing.startUs) else 0L
                val right = original.copy(id = newId, timing = ClipTiming(p, sourceIn, end - p),
                    keyframeAnchorUs = p,
                    markers = FrozenList.of(original.markers.filter { Math.addExact(original.resolvedKeyframeAnchorUs, it.timeUs) >= p }
                        .map { it.copy(timeUs = Math.subtractExact(it.timeUs, Math.subtractExact(p, original.resolvedKeyframeAnchorUs))) }),
                    animTransform = original.animTransform.rebaseKeyframes(Math.subtractExact(p, original.resolvedKeyframeAnchorUs)),
                    text = original.text.rebase(Math.subtractExact(p, original.resolvedKeyframeAnchorUs)))
                // Keep both segments beside the original in render order, even when zOrder values tie.
                val ordered = comp.layers.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id }).toMutableList()
                val index = ordered.indexOfFirst { it.id == original.id }
                ordered[index] = left
                ordered.add(index + 1, right)
                replaceLayers(ordered.mapIndexed { order, layer -> layer.copy(zOrder = order) })
                    .copy(selectedLayerId = newId, selectedMarkerId = null)
            }
            is EditorAction.SetVisibility -> update(action.id) { it.copy(visible = action.visible) }
            is EditorAction.SetOrder -> update(action.id) { it.copy(zOrder = action.zOrder) }
            is EditorAction.SetAudio -> update(action.id) { it.copy(muted = action.muted, audioGain = action.gain) }
            is EditorAction.Rename -> update(action.id) { it.copy(name = action.name) }
            is EditorAction.SetColor -> update(action.id) { it.copy(solidColorArgb = action.argb) }
            is EditorAction.Reorder -> {
                val ordered = comp.layers.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id }).toMutableList()
                val from = ordered.indexOfFirst { it.id == action.id }
                require(from >= 0)
                val to = (from + if (action.towardFront) 1 else -1).coerceIn(0, ordered.lastIndex)
                java.util.Collections.swap(ordered, from, to)
                replaceLayers(ordered.mapIndexed { index, layer -> layer.copy(zOrder = index) })
            }
            is EditorAction.ReorderLayer -> {
                val ordered = comp.layers.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id }).toMutableList()
                val from = ordered.indexOfFirst { it.id == action.id }
                val to = ordered.indexOfFirst { it.id == action.targetId }
                if (from < 0 || to < 0 || from == to) state else {
                    ordered.add(to, ordered.removeAt(from))
                    replaceLayers(ordered.mapIndexed { index, layer -> layer.copy(zOrder = ordered.lastIndex - index) })
                }
            }
            is EditorAction.SetComposition -> state.copy(project = state.project.copy(composition = comp.copy(
                width = action.width, height = action.height, durationUs = action.durationUs, frameRate = action.frameRate,
                startTimecodeUs = action.startTimecodeUs ?: comp.startTimecodeUs,
                markers = Markers.normalize(comp.markers, 0, action.frameRate),
                layers = FrozenList.of(comp.layers.map {
                    (if (action.width == comp.width && action.height == comp.height) it
                        else it.withReferenceCanvas(comp.width, comp.height)).copy(
                        markers = Markers.normalize(it.markers, it.resolvedKeyframeAnchorUs, action.frameRate))
                }))), selectedMarkerId = null,
                playheadUs = state.playheadUs.coerceAtMost(action.durationUs ?: Long.MAX_VALUE)).let {
                    it.copy(playheadUs = it.playheadUs.coerceAtMost(it.project.composition.resolvedDurationUs))
                }
            is EditorAction.Relink -> {
                require(action.replacement.id == action.assetId)
                replaceLayers(comp.layers.map {
                    if (it.asset?.id == action.assetId) it.copy(asset = action.replacement,
                        timing = it.timing.copy(durationUs = it.timing.durationUs ?: action.replacement.durationUs?.let { duration ->
                            (duration - it.timing.sourceInUs).coerceAtLeast(0)
                        })) else it
                })
            }
            is EditorAction.ResolveAsset -> {
                val layers = comp.layers.map {
                    if (it.asset?.id != action.asset.id || it.asset.uri != action.expectedUri) it
                    else {
                        val duration = it.timing.durationUs ?: action.asset.durationUs?.takeIf { _ -> it.type == LayerType.VIDEO }?.let { sourceDuration ->
                            (sourceDuration - it.timing.sourceInUs).coerceAtLeast(0)
                        }
                        it.copy(asset = action.asset, timing = it.timing.copy(durationUs = duration))
                    }
                }
                replaceLayers(layers)
            }
            is EditorAction.ToggleAnimated -> update(action.id) { layer ->
                val alreadyEnabled = when (action.property) {
                    AnimPropertyType.POSITION -> layer.animTransform.position.isAnimated
                    AnimPropertyType.SCALE -> layer.animTransform.scale.isAnimated
                    AnimPropertyType.ROTATION -> layer.animTransform.rotation.isAnimated
                    AnimPropertyType.OPACITY -> layer.animTransform.opacity.isAnimated
                    else -> error("Text property was not normalized")
                }
                if (alreadyEnabled == action.enable) return@update layer
                val evaluated = layer.evaluatedTransform(action.playheadUs)
                val localTimeUs = Math.subtractExact(action.playheadUs, layer.resolvedKeyframeAnchorUs)
                val at = layer.animTransform
                val newAnimTransform = when (action.property) {
                    AnimPropertyType.POSITION -> {
                        if (action.enable) {
                            val curX = evaluated.positionX
                            val curY = evaluated.positionY
                            val kf = Keyframe2D(localTimeUs, curX, curY)
                            at.copy(position = AnimatableProperty2D(isAnimated = true, staticX = curX, staticY = curY, keyframes = FrozenList.of(listOf(kf))))
                        } else {
                            val curX = evaluated.positionX
                            val curY = evaluated.positionY
                            at.copy(position = AnimatableProperty2D(isAnimated = false, staticX = curX, staticY = curY, keyframes = FrozenList.empty()))
                        }
                    }
                    AnimPropertyType.SCALE -> {
                        if (action.enable) {
                            val curX = evaluated.scaleX
                            val curY = evaluated.scaleY
                            val kf = Keyframe2D(localTimeUs, curX, curY)
                            at.copy(scale = AnimatableProperty2D(isAnimated = true, staticX = curX, staticY = curY, keyframes = FrozenList.of(listOf(kf))))
                        } else {
                            val curX = evaluated.scaleX
                            val curY = evaluated.scaleY
                            at.copy(scale = AnimatableProperty2D(isAnimated = false, staticX = curX, staticY = curY, keyframes = FrozenList.empty()))
                        }
                    }
                    AnimPropertyType.ROTATION -> {
                        if (action.enable) {
                            val curVal = evaluated.rotationDegrees
                            val kf = Keyframe1D(localTimeUs, curVal)
                            at.copy(rotation = AnimatableProperty1D(isAnimated = true, staticValue = curVal, keyframes = FrozenList.of(listOf(kf))))
                        } else {
                            val curVal = evaluated.rotationDegrees
                            at.copy(rotation = AnimatableProperty1D(isAnimated = false, staticValue = curVal, keyframes = FrozenList.empty()))
                        }
                    }
                    AnimPropertyType.OPACITY -> {
                        if (action.enable) {
                            val curVal = evaluated.opacity
                            val kf = Keyframe1D(localTimeUs, curVal)
                            at.copy(opacity = AnimatableProperty1D(isAnimated = true, staticValue = curVal, keyframes = FrozenList.of(listOf(kf))))
                        } else {
                            val curVal = evaluated.opacity
                            at.copy(opacity = AnimatableProperty1D(isAnimated = false, staticValue = curVal, keyframes = FrozenList.empty()))
                        }
                    }
                }
                val newTransform = if (!action.enable) {
                    when (action.property) {
                        AnimPropertyType.POSITION -> layer.transform.copy(positionX = newAnimTransform.position.staticX, positionY = newAnimTransform.position.staticY)
                        AnimPropertyType.SCALE -> layer.transform.copy(scaleX = newAnimTransform.scale.staticX, scaleY = newAnimTransform.scale.staticY)
                        AnimPropertyType.ROTATION -> layer.transform.copy(rotationDegrees = newAnimTransform.rotation.staticValue)
                        AnimPropertyType.OPACITY -> layer.transform.copy(opacity = newAnimTransform.opacity.staticValue)
                    }
                } else layer.transform
                layer.copy(animTransform = newAnimTransform, transform = newTransform)
            }
            is EditorAction.SetKeyframe -> update(action.id) { layer ->
                val at = layer.animTransform
                val newAnimTransform = when (action.property) {
                    AnimPropertyType.ROTATION -> {
                        val filtered = at.rotation.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        val updated = (filtered + Keyframe1D(action.localTimeUs, action.value, action.easing)).sortedBy { it.timeUs }
                        at.copy(rotation = at.rotation.copy(isAnimated = true, staticValue = layer.transform.rotationDegrees, keyframes = FrozenList.of(updated)))
                    }
                    AnimPropertyType.OPACITY -> {
                        val filtered = at.opacity.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        val updated = (filtered + Keyframe1D(action.localTimeUs, action.value, action.easing)).sortedBy { it.timeUs }
                        at.copy(opacity = at.opacity.copy(isAnimated = true, staticValue = layer.transform.opacity, keyframes = FrozenList.of(updated)))
                    }
                    else -> at
                }
                layer.copy(animTransform = newAnimTransform)
            }
            is EditorAction.SetKeyframe2D -> update(action.id) { layer ->
                val at = layer.animTransform
                val newAnimTransform = when (action.property) {
                    AnimPropertyType.POSITION -> {
                        val filtered = at.position.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        val updated = (filtered + Keyframe2D(action.localTimeUs, action.x, action.y, action.easing)).sortedBy { it.timeUs }
                        at.copy(position = at.position.copy(isAnimated = true, staticX = layer.transform.positionX, staticY = layer.transform.positionY, keyframes = FrozenList.of(updated)))
                    }
                    AnimPropertyType.SCALE -> {
                        val filtered = at.scale.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        val updated = (filtered + Keyframe2D(action.localTimeUs, action.x, action.y, action.easing)).sortedBy { it.timeUs }
                        at.copy(scale = at.scale.copy(isAnimated = true, staticX = layer.transform.scaleX, staticY = layer.transform.scaleY, keyframes = FrozenList.of(updated)))
                    }
                    else -> at
                }
                layer.copy(animTransform = newAnimTransform)
            }
            is EditorAction.RemoveKeyframe -> update(action.id) { layer ->
                val at = layer.animTransform
                val sampled = layer.evaluatedTransform(Math.addExact(layer.resolvedKeyframeAnchorUs, action.localTimeUs))
                val newAnimTransform = when (action.property) {
                    AnimPropertyType.POSITION -> {
                        val remaining = at.position.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        at.copy(position = at.position.copy(isAnimated = remaining.isNotEmpty(), keyframes = FrozenList.of(remaining)))
                    }
                    AnimPropertyType.SCALE -> {
                        val remaining = at.scale.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        at.copy(scale = at.scale.copy(isAnimated = remaining.isNotEmpty(), keyframes = FrozenList.of(remaining)))
                    }
                    AnimPropertyType.ROTATION -> {
                        val remaining = at.rotation.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        at.copy(rotation = at.rotation.copy(isAnimated = remaining.isNotEmpty(), keyframes = FrozenList.of(remaining)))
                    }
                    AnimPropertyType.OPACITY -> {
                        val remaining = at.opacity.keyframes.filterNot { it.timeUs == action.localTimeUs }
                        at.copy(opacity = at.opacity.copy(isAnimated = remaining.isNotEmpty(), keyframes = FrozenList.of(remaining)))
                    }
                    else -> error("Text property was not normalized")
                }
                val static = when (action.property) {
                    AnimPropertyType.POSITION -> if (!newAnimTransform.position.isAnimated)
                        layer.transform.copy(positionX = sampled.positionX, positionY = sampled.positionY) else layer.transform
                    AnimPropertyType.SCALE -> if (!newAnimTransform.scale.isAnimated)
                        layer.transform.copy(scaleX = sampled.scaleX, scaleY = sampled.scaleY) else layer.transform
                    AnimPropertyType.ROTATION -> if (!newAnimTransform.rotation.isAnimated)
                        layer.transform.copy(rotationDegrees = sampled.rotationDegrees) else layer.transform
                    AnimPropertyType.OPACITY -> if (!newAnimTransform.opacity.isAnimated)
                        layer.transform.copy(opacity = sampled.opacity) else layer.transform
                }
                layer.copy(animTransform = newAnimTransform, transform = static)
            }
            is EditorAction.MoveKeyframe -> update(action.id) { layer ->
                val newTime = action.newTimeUs
                var at = layer.animTransform
                val props = if (action.property != null) {
                    listOf(action.property)
                } else {
                    listOf(AnimPropertyType.POSITION, AnimPropertyType.SCALE, AnimPropertyType.ROTATION, AnimPropertyType.OPACITY)
                }
                for (prop in props) {
                    when (prop) {
                        AnimPropertyType.POSITION -> {
                            val kf = at.position.keyframes.firstOrNull { it.timeUs == action.oldTimeUs } ?: continue
                            val filtered = at.position.keyframes.filterNot { it.timeUs == action.oldTimeUs || it.timeUs == newTime }
                            val updated = (filtered + kf.copy(timeUs = newTime)).sortedBy { it.timeUs }
                            at = at.copy(position = at.position.copy(keyframes = FrozenList.of(updated)))
                        }
                        AnimPropertyType.SCALE -> {
                            val kf = at.scale.keyframes.firstOrNull { it.timeUs == action.oldTimeUs } ?: continue
                            val filtered = at.scale.keyframes.filterNot { it.timeUs == action.oldTimeUs || it.timeUs == newTime }
                            val updated = (filtered + kf.copy(timeUs = newTime)).sortedBy { it.timeUs }
                            at = at.copy(scale = at.scale.copy(keyframes = FrozenList.of(updated)))
                        }
                        AnimPropertyType.ROTATION -> {
                            val kf = at.rotation.keyframes.firstOrNull { it.timeUs == action.oldTimeUs } ?: continue
                            val filtered = at.rotation.keyframes.filterNot { it.timeUs == action.oldTimeUs || it.timeUs == newTime }
                            val updated = (filtered + kf.copy(timeUs = newTime)).sortedBy { it.timeUs }
                            at = at.copy(rotation = at.rotation.copy(keyframes = FrozenList.of(updated)))
                        }
                        AnimPropertyType.OPACITY -> {
                            val kf = at.opacity.keyframes.firstOrNull { it.timeUs == action.oldTimeUs } ?: continue
                            val filtered = at.opacity.keyframes.filterNot { it.timeUs == action.oldTimeUs || it.timeUs == newTime }
                            val updated = (filtered + kf.copy(timeUs = newTime)).sortedBy { it.timeUs }
                            at = at.copy(opacity = at.opacity.copy(keyframes = FrozenList.of(updated)))
                        }
                        else -> error("Text property was not normalized")
                }
                }
                layer.copy(animTransform = at)
            }
            is EditorAction.ResetPropertyAnimation -> update(action.id) { layer ->
                val at = layer.animTransform
                val (newAnimTransform, newTransform) = when (action.property) {
                    AnimPropertyType.POSITION -> Pair(
                        at.copy(position = AnimatableProperty2D(isAnimated = false, staticX = 0f, staticY = 0f, keyframes = FrozenList.empty())),
                        layer.transform.copy(positionX = 0f, positionY = 0f)
                    )
                    AnimPropertyType.SCALE -> Pair(
                        at.copy(scale = AnimatableProperty2D(isAnimated = false, staticX = 1f, staticY = 1f, keyframes = FrozenList.empty())),
                        layer.transform.copy(scaleX = 1f, scaleY = 1f)
                    )
                    AnimPropertyType.ROTATION -> Pair(
                        at.copy(rotation = AnimatableProperty1D(isAnimated = false, staticValue = 0f, keyframes = FrozenList.empty())),
                        layer.transform.copy(rotationDegrees = 0f)
                    )
                    AnimPropertyType.OPACITY -> Pair(
                        at.copy(opacity = AnimatableProperty1D(isAnimated = false, staticValue = 1f, keyframes = FrozenList.empty())),
                        layer.transform.copy(opacity = 1f)
                    )
                    else -> error("Text property was not normalized")
                }
                layer.copy(animTransform = newAnimTransform, transform = newTransform)
            }
        }
    }

    fun nextLayerId(project: ProjectDocument): Long {
        val used = project.composition.layers.map { it.id }.toSet()
        var id = 1L
        while (id in used) id = Math.addExact(id, 1)
        return id
    }
}
