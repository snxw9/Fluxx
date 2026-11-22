package com.fluxx.android.editor

import com.fluxx.android.model.*

/**
 * Pure helpers behind the Animate page (TEXT F3). Every change is expressed with the existing [AnimatorEdit] actions
 * (Put/Remove/Reorder, PutSelector/RemoveSelector/ReorderSelector, PutProperty/RemoveProperty, Key); nothing here adds
 * an action type, a schema field or a second keyframe system. A static field is edited in place (Put*); a keyed field
 * gets a key at the playhead (Key SET), exactly like the layer text properties.
 */
object TextAnimatorEdits {
    /** Backend limits (TextProperties / TextAnimator validation). */
    const val MAX_ANIMATORS=16
    const val MAX_SELECTORS=8
    const val NAME_LIMIT=128
    /** Distinct seeds (static + held keys) one selector may prepare (TextSelectorEngine preparation). */
    const val MAX_SEEDS=64

    /** Discrete fields: Hold keys (the same set [TextAnimatorActions] samples with hold). */
    val HOLD_FIELDS=setOf(SelectorField.ENABLED,SelectorField.UNITS,SelectorField.BASED_ON,SelectorField.SHAPE,SelectorField.MODE,
        SelectorField.RANDOMIZE_ORDER,SelectorField.KIND,SelectorField.LOCK_DIMENSIONS)
    /** Fields the Range selector reads (TextSelectorEngine.coverage, kind 0), in face priority order. */
    val RANGE_FIELDS=listOf(SelectorField.START,SelectorField.END,SelectorField.OFFSET,SelectorField.SHAPE,SelectorField.BASED_ON,
        SelectorField.MODE,SelectorField.AMOUNT,SelectorField.UNITS,SelectorField.SMOOTHNESS,SelectorField.EASE_HIGH,SelectorField.EASE_LOW,
        SelectorField.MIN_AMOUNT,SelectorField.MAX_AMOUNT,SelectorField.RANDOMIZE_ORDER,SelectorField.ENABLED)
    /** Fields the Wiggly selector reads (kind 1). Start/end/offset, units, shape, smoothness, ease and randomize are unused by Wiggly. */
    val WIGGLY_FIELDS=listOf(SelectorField.MAX_AMOUNT,SelectorField.MIN_AMOUNT,SelectorField.BASED_ON,SelectorField.WIGGLES_PER_SECOND,
        SelectorField.CORRELATION,SelectorField.TEMPORAL_PHASE,SelectorField.SPATIAL_PHASE,SelectorField.LOCK_DIMENSIONS,SelectorField.MODE,
        SelectorField.AMOUNT,SelectorField.ENABLED)
    /** Both kinds read the (hold-keyed) seed: Range for randomized order, Wiggly for its noise. */
    fun fields(kind: TextSelectorKind)=if(kind==TextSelectorKind.WIGGLY) WIGGLY_FIELDS else RANGE_FIELDS

    // ---- Addresses --------------------------------------------------------------------------------------------
    fun animatorEnabled(animatorId: Long)=AnimatorTrackAddress(animatorId)
    fun grouping(animatorId: Long)=AnimatorTrackAddress(animatorId,grouping=true)
    fun property(animatorId: Long,p: AnimatorProperty)=AnimatorTrackAddress(animatorId,property=p)
    fun selectorField(animatorId: Long,selectorId: Long,field: SelectorField)=AnimatorTrackAddress(animatorId,selectorId,field)
    fun seed(animatorId: Long,selectorId: Long)=AnimatorTrackAddress(animatorId,selectorId,seed=true)

    fun animator(text: TextProperties,id: Long?)=text.animators.firstOrNull { it.id==id }
    fun selector(text: TextProperties,address: AnimatorTrackAddress)=
        animator(text,address.animatorId)?.selectors?.firstOrNull { it.id==address.selectorId }
    fun kind(s: TextRangeSelector)=if(s.kind.staticValue!=0f || s.kind.keyframes.any { it.value!=0f }) TextSelectorKind.WIGGLY else TextSelectorKind.RANGE
    /** The address still exists in [text] (animators/selectors/properties can disappear through undo or delete). */
    fun exists(text: TextProperties,address: AnimatorTrackAddress): Boolean {
        val a=animator(text,address.animatorId) ?: return false
        return when {
            address.selectorId!=null -> a.selectors.any { it.id==address.selectorId }
            address.property!=null -> a.properties.any { it.property==address.property }
            else -> true
        }
    }
    fun isHold(address: AnimatorTrackAddress)=when {
        address.seed -> true
        address.selectorId!=null -> address.field in HOLD_FIELDS
        address.property!=null -> false
        else -> true // animator enabled / anchor grouping
    }

    // ---- Reading ----------------------------------------------------------------------------------------------
    /** The scalar track behind a scalar/choice/toggle address (null for vector, colour and seed addresses). */
    fun track(text: TextProperties,address: AnimatorTrackAddress): AnimatableProperty1D? {
        val a=animator(text,address.animatorId) ?: return null
        return when {
            address.seed -> null
            address.selectorId!=null -> selector(text,address)?.tracks?.get(requireNotNull(address.field).ordinal)
            address.property!=null -> a.properties.firstOrNull { it.property==address.property }?.scalar
            address.grouping -> a.anchorGrouping
            else -> a.enabled
        }
    }
    fun animated(text: TextProperties,address: AnimatorTrackAddress): Boolean {
        if(address.seed) return selector(text,address)?.seedKeyframes?.isNotEmpty() ?: false
        val p=animator(text,address.animatorId)?.properties?.firstOrNull { it.property==address.property }
        if(address.property!=null) return p?.let { it.scalar?.isAnimated ?: it.vector?.isAnimated ?: it.colour!!.isAnimated } ?: false
        return track(text,address)?.isAnimated ?: false
    }
    /** Local (anchor-relative) key times of one field. */
    fun keyTimes(layer: CompositionLayer,address: AnimatorTrackAddress)=
        TextAnimatorActions.keyTimes(layer,address).map { it-layer.resolvedKeyframeAnchorUs }
    fun value(text: TextProperties,address: AnimatorTrackAddress,local: Long): Float {
        val t=requireNotNull(track(text,address)) { "Not a scalar animator field" }
        return if(isHold(address)) t.hold(local) else t.evaluate(local)
    }
    fun vector(text: TextProperties,address: AnimatorTrackAddress,local: Long): FloatArray {
        val v=requireNotNull(animator(text,address.animatorId)?.properties?.firstOrNull { it.property==address.property }?.vector)
        return FloatArray(2).also { v.evaluate(local,it,0) }
    }
    fun colour(text: TextProperties,address: AnimatorTrackAddress,local: Long): Int =
        requireNotNull(animator(text,address.animatorId)?.properties?.firstOrNull { it.property==address.property }?.colour).evaluate(local)
    fun seedValue(text: TextProperties,address: AnimatorTrackAddress,local: Long): Long = requireNotNull(selector(text,address)).seedAt(local)

    // ---- Defaults (the backend's own reset values) ------------------------------------------------------------
    fun defaultScalar(address: AnimatorTrackAddress): Float = when {
        address.selectorId!=null -> TextRangeSelector(1).tracks[requireNotNull(address.field).ordinal].staticValue
        address.property==AnimatorProperty.OPACITY || address.property==AnimatorProperty.FILL_OPACITY ||
            address.property==AnimatorProperty.STROKE_OPACITY -> 100f
        address.property!=null || address.grouping -> 0f
        else -> 1f
    }
    fun defaultVector(p: AnimatorProperty)=if(p==AnimatorProperty.SCALE) 100f else 0f
    fun defaultColour(p: AnimatorProperty)=if(p==AnimatorProperty.STROKE_COLOUR) 0xff000000.toInt() else -1
    /** A new property override at the backend's neutral/reset value. */
    fun newProperty(p: AnimatorProperty): TextPropertyOverride = when(p.kind) {
        1 -> TextPropertyOverride(p,scalar=AnimatableProperty1D(staticValue=defaultScalar(property(1,p))))
        2 -> TextPropertyOverride(p,vector=AnimatableProperty2D(staticX=defaultVector(p),staticY=defaultVector(p)))
        else -> TextPropertyOverride(p,colour=AnimatableColour(staticValue=defaultColour(p)))
    }

    // ---- Identity ---------------------------------------------------------------------------------------------
    /** Fresh non-zero ids, distinct from every animator and selector id already in [text]. */
    class Ids(private val source: ()->Long={ java.util.UUID.randomUUID().mostSignificantBits }) {
        fun next(text: TextProperties,taken: MutableSet<Long> =mutableSetOf()): Long {
            val used=text.animators.flatMap { listOf(it.id)+it.selectors.map { s->s.id } }.toSet()
            var id: Long
            do { id=source() } while(id==0L || id in used || !taken.add(id))
            return id
        }
    }
    fun nextName(text: TextProperties): String {
        val names=text.animators.map { it.name }.toSet()
        return (1..MAX_ANIMATORS+1).map { "Animator $it" }.first { it !in names }
    }

    // ---- Structure --------------------------------------------------------------------------------------------
    /** A new animator: enabled, Characters grouping, one default Range selector (0-100%) so it acts immediately. */
    fun addAnimator(text: TextProperties,ids: Ids=Ids()): AnimatorEdit.Put {
        require(text.animators.size<MAX_ANIMATORS) { "At most $MAX_ANIMATORS animators per layer" }
        val taken=mutableSetOf<Long>()
        val id=ids.next(text,taken)
        return AnimatorEdit.Put(TextAnimator(id,nextName(text),selectors=FrozenList.of(listOf(TextRangeSelector(ids.next(text,taken))))))
    }
    fun addSelector(text: TextProperties,animatorId: Long,kind: TextSelectorKind,ids: Ids=Ids()): AnimatorEdit.PutSelector {
        val a=requireNotNull(animator(text,animatorId))
        require(a.selectors.size<MAX_SELECTORS) { "At most $MAX_SELECTORS selectors per animator" }
        val s=TextRangeSelector(ids.next(text),kind=AnimatableProperty1D(staticValue=if(kind==TextSelectorKind.WIGGLY) 1f else 0f))
        return AnimatorEdit.PutSelector(animatorId,s)
    }
    fun addProperty(text: TextProperties,animatorId: Long,p: AnimatorProperty): AnimatorEdit.PutProperty? {
        val a=requireNotNull(animator(text,animatorId))
        if(a.properties.any { it.property==p }) return null
        return AnimatorEdit.PutProperty(animatorId,newProperty(p))
    }
    /** Duplicate directly after the source: a copy with fresh animator and selector ids (seeded order is unchanged). */
    fun duplicateAnimator(text: TextProperties,animatorId: Long,ids: Ids=Ids()): List<AnimatorEdit> {
        require(text.animators.size<MAX_ANIMATORS) { "At most $MAX_ANIMATORS animators per layer" }
        val index=text.animators.indexOfFirst { it.id==animatorId }.also { require(it>=0) }
        val a=text.animators[index];val taken=mutableSetOf<Long>()
        val copy=a.copy(id=ids.next(text,taken),name=copyName(a.name),selectors=FrozenList.of(a.selectors.map { it.copy(id=ids.next(text,taken)) }))
        return listOf(AnimatorEdit.Put(copy),AnimatorEdit.Reorder(copy.id,index+1))
    }
    fun duplicateSelector(text: TextProperties,animatorId: Long,selectorId: Long,ids: Ids=Ids()): List<AnimatorEdit> {
        val a=requireNotNull(animator(text,animatorId))
        require(a.selectors.size<MAX_SELECTORS) { "At most $MAX_SELECTORS selectors per animator" }
        val index=a.selectors.indexOfFirst { it.id==selectorId }.also { require(it>=0) }
        val copy=a.selectors[index].copy(id=ids.next(text))
        return listOf(AnimatorEdit.PutSelector(animatorId,copy),AnimatorEdit.ReorderSelector(animatorId,copy.id,index+1))
    }
    /** Move one place earlier (-1) or later (+1); null at the ends. */
    fun moveAnimator(text: TextProperties,animatorId: Long,delta: Int): AnimatorEdit.Reorder? {
        val index=text.animators.indexOfFirst { it.id==animatorId }
        val target=index+delta
        return if(index<0 || target !in text.animators.indices) null else AnimatorEdit.Reorder(animatorId,target)
    }
    fun moveSelector(text: TextProperties,animatorId: Long,selectorId: Long,delta: Int): AnimatorEdit.ReorderSelector? {
        val list=animator(text,animatorId)?.selectors ?: return null
        val index=list.indexOfFirst { it.id==selectorId };val target=index+delta
        return if(index<0 || target !in list.indices) null else AnimatorEdit.ReorderSelector(animatorId,selectorId,target)
    }
    /** Trimmed, NUL-free and at most [NAME_LIMIT] characters; blank keeps the old name. */
    fun rename(text: TextProperties,animatorId: Long,name: String): AnimatorEdit.Put? {
        val a=animator(text,animatorId) ?: return null
        val clean=name.replace("\u0000","").replace('\n',' ').trim().take(NAME_LIMIT)
        return if(clean.isEmpty() || clean==a.name) null else AnimatorEdit.Put(a.copy(name=clean))
    }
    private fun copyName(name: String)=(if(name.endsWith(" copy")) name else "$name copy").take(NAME_LIMIT)

    // ---- Values -----------------------------------------------------------------------------------------------
    /**
     * Set one field at [local]: a key at the playhead when the field is keyed (Key SET, frame-snapped by the reducer),
     * otherwise its static value (Put*). Scalars use [value]; vectors [value] and [y]; colours [argb]; seeds [seedValue].
     */
    fun set(text: TextProperties,address: AnimatorTrackAddress,local: Long,value: Float=0f,y: Float?=null,argb: Int?=null,
        seedValue: Long?=null,easing: EasingPreset?=null): AnimatorEdit {
        val a=requireNotNull(animator(text,address.animatorId)) { "Animator no longer exists" }
        if(animated(text,address)) {
            val keyEasing=easing ?: existingEasing(text,address,local) ?: EasingPreset.EASY_EASE
            return AnimatorEdit.Key(address,AnimatorKeyOperation.SET,local,value=if(address.seed || argb!=null) null else value,y=y,argb=argb,
                easing=keyEasing,seedValue=seedValue)
        }
        if(address.seed) {
            val s=requireNotNull(selector(text,address))
            return AnimatorEdit.PutSelector(a.id,s.copy(seed=requireNotNull(seedValue)))
        }
        if(address.selectorId!=null) {
            val s=requireNotNull(selector(text,address))
            val field=requireNotNull(address.field)
            val p=s.tracks[field.ordinal].copy(staticValue=value)
            return AnimatorEdit.PutSelector(a.id,withField(s,field,p))
        }
        val property=address.property
        if(property==null) return AnimatorEdit.Put(if(address.grouping) a.copy(anchorGrouping=a.anchorGrouping.copy(staticValue=value))
            else a.copy(enabled=a.enabled.copy(staticValue=value)))
        val o=requireNotNull(a.properties.firstOrNull { it.property==property })
        return AnimatorEdit.PutProperty(a.id,when {
            o.scalar!=null -> o.copy(scalar=o.scalar.copy(staticValue=value))
            o.vector!=null -> o.copy(vector=o.vector.copy(staticX=value,staticY=y ?: o.vector.staticY))
            else -> o.copy(colour=requireNotNull(o.colour).copy(staticValue=requireNotNull(argb)))
        })
    }
    /** Reset value: the backend default, keyed at the playhead when the field is animated (like the layer properties). */
    fun reset(text: TextProperties,address: AnimatorTrackAddress,local: Long): AnimatorEdit {
        if(address.seed) return set(text,address,local,seedValue=0L)
        val p=address.property
        return when {
            p!=null && p.kind==2 -> set(text,address,local,defaultVector(p),defaultVector(p))
            p!=null && p.kind==3 -> set(text,address,local,argb=defaultColour(p))
            else -> set(text,address,local,defaultScalar(address))
        }
    }
    private fun existingEasing(text: TextProperties,address: AnimatorTrackAddress,local: Long): EasingPreset? {
        if(address.seed) return null
        val o=animator(text,address.animatorId)?.properties?.firstOrNull { it.property==address.property }
        if(address.property!=null) return o?.scalar?.keyframes?.firstOrNull { it.timeUs==local }?.easing
            ?: o?.vector?.keyframes?.firstOrNull { it.timeUs==local }?.easing ?: o?.colour?.keyframes?.firstOrNull { it.timeUs==local }?.easing
        return track(text,address)?.keyframes?.firstOrNull { it.timeUs==local }?.easing
    }
    fun withField(s: TextRangeSelector,field: SelectorField,p: AnimatableProperty1D)=when(field) {
        SelectorField.ENABLED -> s.copy(enabled=p);SelectorField.START -> s.copy(start=p);SelectorField.END -> s.copy(end=p)
        SelectorField.OFFSET -> s.copy(offset=p);SelectorField.UNITS -> s.copy(units=p);SelectorField.BASED_ON -> s.copy(basedOn=p)
        SelectorField.SHAPE -> s.copy(shape=p);SelectorField.SMOOTHNESS -> s.copy(smoothness=p);SelectorField.EASE_HIGH -> s.copy(easeHigh=p)
        SelectorField.EASE_LOW -> s.copy(easeLow=p);SelectorField.MODE -> s.copy(mode=p);SelectorField.AMOUNT -> s.copy(amount=p)
        SelectorField.MIN_AMOUNT -> s.copy(minAmount=p);SelectorField.MAX_AMOUNT -> s.copy(maxAmount=p);SelectorField.RANDOMIZE_ORDER -> s.copy(randomizeOrder=p)
        SelectorField.KIND -> s.copy(kind=p);SelectorField.WIGGLES_PER_SECOND -> s.copy(wigglesPerSecond=p)
        SelectorField.CORRELATION -> s.copy(correlation=p);SelectorField.TEMPORAL_PHASE -> s.copy(temporalPhase=p)
        SelectorField.SPATIAL_PHASE -> s.copy(spatialPhase=p);SelectorField.LOCK_DIMENSIONS -> s.copy(lockDimensions=p)
    }

    // ---- Keyframe rail ----------------------------------------------------------------------------------------
    /** Rail diamond: enable (first key) when static, remove the key on it, otherwise add a key with the current value. */
    fun toggleKey(text: TextProperties,address: AnimatorTrackAddress,local: Long,onKey: Boolean): AnimatorEdit = when {
        !animated(text,address) -> AnimatorEdit.Key(address,AnimatorKeyOperation.ENABLE,local)
        onKey -> AnimatorEdit.Key(address,AnimatorKeyOperation.REMOVE,local)
        else -> AnimatorEdit.Key(address,AnimatorKeyOperation.SET,local)
    }
    fun enable(address: AnimatorTrackAddress,local: Long)=AnimatorEdit.Key(address,AnimatorKeyOperation.ENABLE,local)
    /** Freeze at playhead: remove every key and keep the value at the playhead. */
    fun freeze(address: AnimatorTrackAddress,local: Long)=AnimatorEdit.Key(address,AnimatorKeyOperation.FREEZE,local)
    /** Re-key the key at the playhead with [easing] (Linear / Easy Ease); hold fields have no easing. */
    fun easing(address: AnimatorTrackAddress,local: Long,easing: EasingPreset)=AnimatorEdit.Key(address,AnimatorKeyOperation.SET,local,easing=easing)

    // ---- Dispatch ---------------------------------------------------------------------------------------------
    /** One undo entry: a single edit, or a Batch for multi-step structure edits (duplicate = put + reorder). */
    fun action(layerId: Long,edits: List<AnimatorEdit>): EditorAction {
        require(edits.isNotEmpty())
        return if(edits.size==1) EditorAction.EditTextAnimator(layerId,edits[0]) else EditorAction.Batch(edits.map { EditorAction.EditTextAnimator(layerId,it) })
    }
    /** The payload [edits] produce on [layer], for a live draft preview. */
    fun payload(layer: CompositionLayer,rate: FrameRate,edits: List<AnimatorEdit>): TextProperties =
        edits.fold(layer.text) { text,edit -> TextAnimatorActions.payload(layer.copy(text=text),rate,edit) }
}
