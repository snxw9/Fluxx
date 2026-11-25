package com.fluxx.android.editor

import com.fluxx.android.model.*

enum class SelectorField { ENABLED, START, END, OFFSET, UNITS, BASED_ON, SHAPE, SMOOTHNESS, EASE_HIGH, EASE_LOW, MODE, AMOUNT, MIN_AMOUNT, MAX_AMOUNT, RANDOMIZE_ORDER,
    KIND, WIGGLES_PER_SECOND, CORRELATION, TEMPORAL_PHASE, SPATIAL_PHASE, LOCK_DIMENSIONS }
data class AnimatorTrackAddress(val animatorId: Long,val selectorId: Long?=null,
    val field: SelectorField?=null,val property: AnimatorProperty?=null,val grouping: Boolean=false,val seed: Boolean=false)
enum class AnimatorKeyOperation { SET, REMOVE, MOVE, ENABLE, FREEZE, RESET }
sealed interface AnimatorEdit {
    data class Put(val animator: TextAnimator): AnimatorEdit
    data class Remove(val id: Long): AnimatorEdit
    data class Reorder(val id: Long,val targetIndex: Int): AnimatorEdit
    data class PutSelector(val animatorId: Long,val selector: TextRangeSelector): AnimatorEdit
    data class RemoveSelector(val animatorId: Long,val selectorId: Long): AnimatorEdit
    data class ReorderSelector(val animatorId: Long,val selectorId: Long,val targetIndex: Int): AnimatorEdit
    data class PutProperty(val animatorId: Long,val property: TextPropertyOverride): AnimatorEdit
    data class RemoveProperty(val animatorId: Long,val property: AnimatorProperty): AnimatorEdit
    data class Key(val address: AnimatorTrackAddress,val operation: AnimatorKeyOperation,val localTimeUs: Long,
        val newTimeUs: Long=localTimeUs,val value: Float?=null,val y: Float?=null,val argb: Int?=null,
        val easing: EasingPreset=EasingPreset.EASY_EASE,val seedValue: Long?=null): AnimatorEdit
}

object TextAnimatorActions {
    /** Bulk timeline-key moves join the existing batch/undo transaction. */
    fun moveAll(layer: CompositionLayer,from: Long,to: Long): List<EditorAction> {
        if(layer.type!=LayerType.TEXT) return emptyList()
        val result=mutableListOf<EditorAction>()
        fun move(address: AnimatorTrackAddress) {
            result.add(EditorAction.EditTextAnimator(layer.id,AnimatorEdit.Key(address,AnimatorKeyOperation.MOVE,from,to)))
        }
        fun scalar(address: AnimatorTrackAddress,p: AnimatableProperty1D) {
            if(p.isAnimated && p.keyframes.any { it.timeUs==from }) move(address)
        }
        for(a in layer.text.animators) {
            scalar(AnimatorTrackAddress(a.id),a.enabled)
            scalar(AnimatorTrackAddress(a.id,grouping=true),a.anchorGrouping)
            for(s in a.selectors) for(field in SelectorField.entries)
                scalar(AnimatorTrackAddress(a.id,s.id,field),s.tracks[field.ordinal])
            for(s in a.selectors) if(s.seedKeyframes.any { it.timeUs==from }) move(AnimatorTrackAddress(a.id,s.id,seed=true))
            for(p in a.properties) if(from in p.activeKeyTimes()) move(AnimatorTrackAddress(a.id,property=p.property))
        }
        return result
    }
    fun apply(state: EditorState,action: EditorAction.EditTextAnimator): EditorAction {
        val layer=requireNotNull(state.project.composition.layers.firstOrNull { it.id==action.id })
        return EditorAction.SetTextPayload(action.id,payload(layer,state.project.composition.frameRate,action.edit))
    }
    /**
     * The text payload one [AnimatorEdit] produces on [layer] (the same arithmetic the reducer applies). The Animate
     * page previews drags through the existing begin/preview/finish draft with this payload.
     */
    fun payload(layer: CompositionLayer,rate: FrameRate,edit: AnimatorEdit): TextProperties {
        require(layer.type==LayerType.TEXT)
        val groups=layer.text.animators.toMutableList()
        fun group(id: Long)=groups.indexOfFirst { it.id==id }.also { require(it>=0) }
        fun snap(t: Long): Long {
            val absolute=Math.addExact(layer.resolvedKeyframeAnchorUs,t)
            val frame=Markers.signedFrame(rate,absolute)
            val value=if(frame>=0) rate.frameTimeUs(frame) else Math.negateExact(rate.frameTimeUs(Math.negateExact(frame)))
            return Math.subtractExact(value,layer.resolvedKeyframeAnchorUs)
        }
        when(edit) {
            is AnimatorEdit.Put -> { val i=groups.indexOfFirst { it.id==edit.animator.id };if(i<0) groups.add(edit.animator) else groups[i]=edit.animator }
            is AnimatorEdit.Remove -> groups.removeAt(group(edit.id))
            is AnimatorEdit.Reorder -> { require(edit.targetIndex in groups.indices);val a=groups.removeAt(group(edit.id));groups.add(edit.targetIndex,a) }
            else -> {
                val id=when(edit) {
                    is AnimatorEdit.PutSelector -> edit.animatorId;is AnimatorEdit.RemoveSelector -> edit.animatorId
                    is AnimatorEdit.ReorderSelector -> edit.animatorId;is AnimatorEdit.PutProperty -> edit.animatorId
                    is AnimatorEdit.RemoveProperty -> edit.animatorId;is AnimatorEdit.Key -> edit.address.animatorId
                    else -> error("Structural group edit")
                }
                val index=group(id);val a=groups[index]
                groups[index]=when(edit) {
                    is AnimatorEdit.PutSelector -> a.copy(selectors=FrozenList.of(if(a.selectors.any { it.id==edit.selector.id })
                        a.selectors.map { if(it.id==edit.selector.id) edit.selector else it } else a.selectors + edit.selector))
                    is AnimatorEdit.RemoveSelector -> { require(a.selectors.any { it.id==edit.selectorId });a.copy(selectors=FrozenList.of(a.selectors.filterNot { it.id==edit.selectorId })) }
                    is AnimatorEdit.ReorderSelector -> {
                        val selectors=a.selectors.toMutableList();require(edit.targetIndex in selectors.indices)
                        val i=selectors.indexOfFirst { it.id==edit.selectorId };require(i>=0)
                        val s=selectors.removeAt(i);selectors.add(edit.targetIndex,s);a.copy(selectors=FrozenList.of(selectors))
                    }
                    is AnimatorEdit.PutProperty -> a.copy(properties=FrozenList.of(if(a.properties.any { it.property==edit.property.property })
                        a.properties.map { if(it.property==edit.property.property) edit.property else it } else a.properties + edit.property))
                    is AnimatorEdit.RemoveProperty -> a.copy(properties=FrozenList.of(a.properties.filterNot { it.property==edit.property }))
                    is AnimatorEdit.Key -> editKey(a,edit,
                        if(edit.operation==AnimatorKeyOperation.MOVE || edit.operation==AnimatorKeyOperation.REMOVE) edit.localTimeUs else snap(edit.localTimeUs),
                        snap(edit.newTimeUs))
                    else -> error("Unexpected edit")
                }
            }
        }
        return layer.text.copy(animators=FrozenList.of(groups))
    }
    private fun editKey(a: TextAnimator,e: AnimatorEdit.Key,time: Long,target: Long): TextAnimator {
        fun <T> keys(old: List<T>,get: (T)->Long,move: (T,Long)->T,value: ()->T): FrozenList<T> {
            val result=when(e.operation) {
                AnimatorKeyOperation.FREEZE,AnimatorKeyOperation.RESET -> emptyList()
                AnimatorKeyOperation.ENABLE -> listOf(value())
                AnimatorKeyOperation.SET -> old.filterNot { get(it)==time } + value()
                AnimatorKeyOperation.REMOVE -> old.filterNot { get(it)==time }
                AnimatorKeyOperation.MOVE -> {
                    val key=old.firstOrNull { get(it)==time } ?: return FrozenList.of(old)
                    old.filterNot { get(it)==time || get(it)==target } + move(key,target)
                }
            }
            return FrozenList.of(result.sortedBy(get))
        }
        fun scalar(p: AnimatableProperty1D,hold: Boolean): AnimatableProperty1D {
            // GRAPH G1: a sample from an overshooting curve is clamped to the field's legal range before it is stored.
            val sample=if(hold) p.hold(time) else TextAnimatorEdits.clamp(e.address,p.evaluate(time))
            val k=keys(p.keyframes,{ it.timeUs },{ key,t -> key.copy(timeUs=t) }) { rekey1D(p.keyframes.firstOrNull { it.timeUs==time },time,e.value ?: sample,e.easing) }
            val reset=when {
                e.address.selectorId!=null -> TextRangeSelector(1).tracks[requireNotNull(e.address.field).ordinal].staticValue
                e.address.property==AnimatorProperty.OPACITY || e.address.property==AnimatorProperty.FILL_OPACITY ||
                    e.address.property==AnimatorProperty.STROKE_OPACITY -> 100f
                e.address.property!=null || e.address.grouping -> 0f
                else -> 1f
            }
            return p.copy(isAnimated=k.isNotEmpty(),staticValue=if(e.operation==AnimatorKeyOperation.RESET) reset else sample,keyframes=k)
        }
        val address=e.address
        if(address.selectorId!=null) {
            require(address.property==null && !address.grouping)
            val s=requireNotNull(a.selectors.firstOrNull { it.id==address.selectorId })
            if(address.seed) {
                require(address.field==null)
                val sample=s.seedAt(time)
                val k=keys(s.seedKeyframes,{ it.timeUs },{ key,t->key.copy(timeUs=t) }) { TextSeedKeyframe(time,e.seedValue ?: sample) }
                val next=s.copy(seed=if(e.operation==AnimatorKeyOperation.RESET) 0L else sample,seedKeyframes=k)
                return a.copy(selectors=FrozenList.of(a.selectors.map { if(it.id==next.id) next else it }))
            }
            val field=requireNotNull(address.field)
            val hold=field in listOf(SelectorField.ENABLED,SelectorField.UNITS,SelectorField.BASED_ON,SelectorField.SHAPE,SelectorField.MODE,SelectorField.RANDOMIZE_ORDER,
                SelectorField.KIND,SelectorField.LOCK_DIMENSIONS)
            val p=scalar(s.tracks[field.ordinal],hold)
            val next=when(field) {
                SelectorField.ENABLED -> s.copy(enabled=p);SelectorField.START -> s.copy(start=p);SelectorField.END -> s.copy(end=p)
                SelectorField.OFFSET -> s.copy(offset=p);SelectorField.UNITS -> s.copy(units=p);SelectorField.BASED_ON -> s.copy(basedOn=p)
                SelectorField.SHAPE -> s.copy(shape=p);SelectorField.SMOOTHNESS -> s.copy(smoothness=p);SelectorField.EASE_HIGH -> s.copy(easeHigh=p)
                SelectorField.EASE_LOW -> s.copy(easeLow=p);SelectorField.MODE -> s.copy(mode=p);SelectorField.AMOUNT -> s.copy(amount=p)
                SelectorField.MIN_AMOUNT -> s.copy(minAmount=p);SelectorField.MAX_AMOUNT -> s.copy(maxAmount=p);SelectorField.RANDOMIZE_ORDER -> s.copy(randomizeOrder=p)
                SelectorField.KIND -> s.copy(kind=p);SelectorField.WIGGLES_PER_SECOND -> s.copy(wigglesPerSecond=p)
                SelectorField.CORRELATION -> s.copy(correlation=p);SelectorField.TEMPORAL_PHASE -> s.copy(temporalPhase=p)
                SelectorField.SPATIAL_PHASE -> s.copy(spatialPhase=p);SelectorField.LOCK_DIMENSIONS -> s.copy(lockDimensions=p)
            }
            return a.copy(selectors=FrozenList.of(a.selectors.map { if(it.id==next.id) next else it }))
        }
        require(address.field==null && !address.seed)
        val property=address.property
        if(property==null) return if(address.grouping) a.copy(anchorGrouping=scalar(a.anchorGrouping,true)) else a.copy(enabled=scalar(a.enabled,true))
        require(!address.grouping)
        val p=requireNotNull(a.properties.firstOrNull { it.property==property })
        val next=when {
            p.scalar!=null -> p.copy(scalar=scalar(p.scalar,false))
            p.vector!=null -> {
                val sample=FloatArray(2);p.vector.evaluate(time,sample,0)
                val k=keys(p.vector.keyframes,{ it.timeUs },{ key,t -> key.copy(timeUs=t) }) { rekey2D(p.vector.keyframes.firstOrNull { it.timeUs==time },time,e.value ?: sample[0],e.y ?: sample[1],e.easing) }
                val reset=if(property==AnimatorProperty.SCALE) 100f else 0f
                p.copy(vector=p.vector.copy(isAnimated=k.isNotEmpty(),staticX=if(e.operation==AnimatorKeyOperation.RESET) reset else sample[0],
                    staticY=if(e.operation==AnimatorKeyOperation.RESET) reset else sample[1],keyframes=k))
            }
            else -> {
                val track=requireNotNull(p.colour);val sample=track.evaluate(time)
                val k=keys(track.keyframes,{ it.timeUs },{ key,t -> key.copy(timeUs=t) }) { rekeyColour(track.keyframes.firstOrNull { it.timeUs==time },time,e.argb ?: sample,e.easing) }
                val reset=if(property==AnimatorProperty.STROKE_COLOUR) 0xff000000.toInt() else -1
                p.copy(colour=track.copy(isAnimated=k.isNotEmpty(),staticValue=if(e.operation==AnimatorKeyOperation.RESET) reset else sample,keyframes=k))
            }
        }
        return a.copy(properties=FrozenList.of(a.properties.map { if(it.property==property) next else it }))
    }
    fun keyTimes(layer: CompositionLayer,address: AnimatorTrackAddress?=null): List<Long> {
        val groups=if(address==null) layer.text.animators else layer.text.animators.filter { it.id==address.animatorId }
        val local=if(address==null) groups.flatMap { it.activeKeyTimes() } else groups.flatMap { a ->
            when {
                address.selectorId!=null -> a.selectors.filter { it.id==address.selectorId }.flatMap {
                    val tracks=address.field?.let { field -> listOf(it.tracks[field.ordinal]) } ?: it.tracks
                    (if(address.seed) emptyList() else tracks.filter { p -> p.isAnimated }.flatMap { p -> p.keyframes.map { k -> k.timeUs } }) +
                        (if(address.seed || address.field==null) it.seedKeyframes.map { k->k.timeUs } else emptyList())
                }
                address.property!=null -> a.properties.filter { it.property==address.property }.flatMap { it.activeKeyTimes() }
                else -> (if(address.grouping) a.anchorGrouping else a.enabled).let { if(it.isAnimated) it.keyframes.map { k -> k.timeUs } else emptyList() }
            }
        }
        return local.map { Math.addExact(layer.resolvedKeyframeAnchorUs,it) }.distinct().sorted()
    }
    fun nextKey(layer: CompositionLayer,address: AnimatorTrackAddress?,time: Long,forward: Boolean): Long? =
        keyTimes(layer,address).let { times -> if(forward) times.firstOrNull { it>time } else times.lastOrNull { it<time } }
}
