package com.fluxx.android.model

enum class SelectorUnits { PERCENTAGE, INDEX }
enum class SelectorBasedOn { CHARACTERS, NON_SPACE, WORDS, LINES }
enum class SelectorShape { SQUARE, RAMP_UP, RAMP_DOWN, TRIANGLE, ROUND, SMOOTH }
enum class SelectorMode { ADD, SUBTRACT, INTERSECT, MIN, MAX, DIFFERENCE }
enum class AnchorGrouping { CHARACTERS, WORDS, LINES, ALL }
enum class AnimatorProperty(val kind: Int) {
    POSITION(2), SCALE(2), ROTATION(1), OPACITY(1), SKEW(1), SKEW_AXIS(1),
    ANCHOR(2), FILL(3), STROKE_COLOUR(3), STROKE_WIDTH(1), TRACKING(1), LINE_ANCHOR(1), BLUR(1)
}

internal fun scalar(value: Float) = AnimatableProperty1D(staticValue = value)
internal fun AnimatableProperty1D.validateRange(low: Float, high: Float, discrete: Boolean = false) {
    fun check(v: Float) { require(v.isFinite() && v in low..high && (!discrete || v == v.toInt().toFloat())) }
    check(staticValue); keyframes.forEach { check(it.value) }
}
internal fun AnimatableProperty1D.shift(delta: Long) = copy(keyframes = FrozenList.of(keyframes.map {
    it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) }))
internal fun AnimatableProperty2D.shift(delta: Long) = copy(keyframes = FrozenList.of(keyframes.map {
    it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) }))
internal fun AnimatableColour.shift(delta: Long) = copy(keyframes = FrozenList.of(keyframes.map {
    it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) }))

data class TextRangeSelector(
    val id: Long,
    val enabled: AnimatableProperty1D = scalar(1f),
    val start: AnimatableProperty1D = scalar(0f), val end: AnimatableProperty1D = scalar(100f),
    val offset: AnimatableProperty1D = scalar(0f), val units: AnimatableProperty1D = scalar(0f),
    val basedOn: AnimatableProperty1D = scalar(0f), val shape: AnimatableProperty1D = scalar(0f),
    val smoothness: AnimatableProperty1D = scalar(100f), val easeHigh: AnimatableProperty1D = scalar(0f),
    val easeLow: AnimatableProperty1D = scalar(0f), val mode: AnimatableProperty1D = scalar(0f),
    val amount: AnimatableProperty1D = scalar(100f), val minAmount: AnimatableProperty1D = scalar(0f),
    val maxAmount: AnimatableProperty1D = scalar(100f), val randomizeOrder: AnimatableProperty1D = scalar(0f),
    // Long stores all 64 seed bits, including the unsigned high half. Zero is ordinary.
    val seed: Long = 0L
) {
    val tracks get() = listOf(enabled,start,end,offset,units,basedOn,shape,smoothness,easeHigh,easeLow,mode,amount,minAmount,maxAmount,randomizeOrder)
    init {
        require(id != 0L)
        enabled.validateRange(0f,1f,true); randomizeOrder.validateRange(0f,1f,true)
        units.validateRange(0f,1f,true); basedOn.validateRange(0f,3f,true)
        shape.validateRange(0f,5f,true); mode.validateRange(0f,5f,true)
        smoothness.validateRange(0f,100f)
        listOf(easeHigh,easeLow,amount,minAmount,maxAmount).forEach { it.validateRange(-100f,100f) }
        listOf(start,end,offset).forEach { it.validateRange(-Float.MAX_VALUE,Float.MAX_VALUE) }
    }
    fun rebase(delta: Long) = copy(enabled=enabled.shift(delta),start=start.shift(delta),end=end.shift(delta),
        offset=offset.shift(delta),units=units.shift(delta),basedOn=basedOn.shift(delta),shape=shape.shift(delta),
        smoothness=smoothness.shift(delta),easeHigh=easeHigh.shift(delta),easeLow=easeLow.shift(delta),mode=mode.shift(delta),
        amount=amount.shift(delta),minAmount=minAmount.shift(delta),maxAmount=maxAmount.shift(delta),randomizeOrder=randomizeOrder.shift(delta))
}

data class TextPropertyOverride(val property: AnimatorProperty,
    val scalar: AnimatableProperty1D? = null, val vector: AnimatableProperty2D? = null, val colour: AnimatableColour? = null) {
    init {
        require(listOfNotNull(scalar,vector,colour).size == 1)
        require(when(property.kind) { 1 -> scalar != null; 2 -> vector != null; else -> colour != null })
        val percent = property==AnimatorProperty.OPACITY || property==AnimatorProperty.LINE_ANCHOR
        scalar?.validateRange(if(percent) 0f else -Float.MAX_VALUE, if(percent) 100f else Float.MAX_VALUE)
    }
    fun rebase(delta: Long) = copy(scalar=scalar?.shift(delta),vector=vector?.shift(delta),colour=colour?.shift(delta))
    fun keyTimes() = when { scalar != null -> scalar.keyframes.map { it.timeUs }
        vector != null -> vector.keyframes.map { it.timeUs }; else -> colour!!.keyframes.map { it.timeUs } }
    fun activeKeyTimes() = when { scalar != null -> if(scalar.isAnimated) keyTimes() else emptyList()
        vector != null -> if(vector.isAnimated) keyTimes() else emptyList()
        else -> if(colour!!.isAnimated) keyTimes() else emptyList() }
}

data class TextAnimator(val id: Long, val name: String = "Animator",
    val enabled: AnimatableProperty1D = scalar(1f), val anchorGrouping: AnimatableProperty1D = scalar(0f),
    val selectors: FrozenList<TextRangeSelector> = FrozenList.empty(),
    val properties: FrozenList<TextPropertyOverride> = FrozenList.empty()) {
    init {
        require(id != 0L && name.length <= 128 && '\u0000' !in name)
        enabled.validateRange(0f,1f,true); anchorGrouping.validateRange(0f,3f,true)
        require(selectors.size <= 8 && selectors.map { it.id }.distinct().size == selectors.size)
        require(properties.map { it.property }.distinct().size == properties.size)
    }
    fun rebase(delta: Long) = copy(enabled=enabled.shift(delta),anchorGrouping=anchorGrouping.shift(delta),
        selectors=FrozenList.of(selectors.map { it.rebase(delta) }),properties=FrozenList.of(properties.map { it.rebase(delta) }))
    fun allKeyTimes(): List<List<Long>> = listOf(enabled.keyframes.map { it.timeUs },anchorGrouping.keyframes.map { it.timeUs }) +
        selectors.flatMap { it.tracks.map { p -> p.keyframes.map { k -> k.timeUs } } } + properties.map { it.keyTimes() }
    fun activeKeyTimes() = (listOf(enabled,anchorGrouping).filter { it.isAnimated }.flatMap { it.keyframes.map { k -> k.timeUs } } +
        selectors.flatMap { it.tracks.filter { p -> p.isAnimated }.flatMap { p -> p.keyframes.map { k -> k.timeUs } } } +
        properties.flatMap { it.activeKeyTimes() }).distinct().sorted()
}

/** Copy identity changes must never change seeded order. Runs only during editing. */
fun TextProperties.withFreshAnimatorIds(): TextProperties {
    if(animators.isEmpty()) return this
    val used=animators.flatMap { listOf(it.id) + it.selectors.map { s -> s.id } }.toMutableSet()
    fun next(): Long {
        var id: Long
        do { id=java.util.UUID.randomUUID().mostSignificantBits } while(id==0L || !used.add(id))
        return id
    }
    return copy(animators=FrozenList.of(animators.map { a -> a.copy(id=next(),
        selectors=FrozenList.of(a.selectors.map { it.copy(id=next()) })) }))
}
