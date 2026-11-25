package com.fluxx.android.model

enum class SelectorUnits { PERCENTAGE, INDEX }
enum class SelectorBasedOn { CHARACTERS, NON_SPACE, WORDS, LINES }
enum class SelectorShape { SQUARE, RAMP_UP, RAMP_DOWN, TRIANGLE, ROUND, SMOOTH }
enum class SelectorMode { ADD, SUBTRACT, INTERSECT, MIN, MAX, DIFFERENCE }
enum class TextSelectorKind { RANGE, WIGGLY }
data class TextSeedKeyframe(val timeUs: Long,val seed: Long)
enum class AnchorGrouping { CHARACTERS, WORDS, LINES, ALL }
enum class AnimatorProperty(val kind: Int) {
    POSITION(2), SCALE(2), ROTATION(1), OPACITY(1), SKEW(1), SKEW_AXIS(1),
    ANCHOR(2), FILL(3), STROKE_COLOUR(3), STROKE_WIDTH(1), TRACKING(1), LINE_ANCHOR(1), BLUR(1),
    FILL_OPACITY(1), STROKE_OPACITY(1), FILL_HUE(1), FILL_SATURATION(1), FILL_BRIGHTNESS(1),
    STROKE_HUE(1), STROKE_SATURATION(1), STROKE_BRIGHTNESS(1), LINE_SPACING(1),
    GROUPING_ALIGNMENT(2), CHARACTER_OFFSET(1), CHARACTER_VALUE(1)
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
    val seed: Long = 0L,
    val kind: AnimatableProperty1D = scalar(0f),
    val wigglesPerSecond: AnimatableProperty1D = scalar(2f),
    val correlation: AnimatableProperty1D = scalar(50f),
    val temporalPhase: AnimatableProperty1D = scalar(0f),
    val spatialPhase: AnimatableProperty1D = scalar(0f),
    val lockDimensions: AnimatableProperty1D = scalar(1f),
    val seedKeyframes: FrozenList<TextSeedKeyframe> = FrozenList.empty()
) {
    val tracks get() = listOf(enabled,start,end,offset,units,basedOn,shape,smoothness,easeHigh,easeLow,mode,amount,minAmount,maxAmount,randomizeOrder,
        kind,wigglesPerSecond,correlation,temporalPhase,spatialPhase,lockDimensions)
    init {
        require(id != 0L)
        enabled.validateRange(0f,1f,true); randomizeOrder.validateRange(0f,1f,true)
        units.validateRange(0f,1f,true); basedOn.validateRange(0f,3f,true)
        shape.validateRange(0f,5f,true); mode.validateRange(0f,5f,true)
        smoothness.validateRange(0f,100f)
        listOf(easeHigh,easeLow,amount,minAmount,maxAmount).forEach { it.validateRange(-100f,100f) }
        listOf(start,end,offset).forEach { it.validateRange(-Float.MAX_VALUE,Float.MAX_VALUE) }
        kind.validateRange(0f,1f,true);lockDimensions.validateRange(0f,1f,true)
        wigglesPerSecond.validateRange(0f,1000f);correlation.validateRange(0f,100f)
        temporalPhase.validateRange(-Float.MAX_VALUE,Float.MAX_VALUE);spatialPhase.validateRange(-Float.MAX_VALUE,Float.MAX_VALUE)
        require(seedKeyframes.size<=4096 && seedKeyframes.zipWithNext().all { (a,b)->a.timeUs<b.timeUs })
    }
    /**
     * TEXT F3.2 (AE parity): the seed interpolates linearly between keys and rounds to the nearest integer seed
     * (k0 + Math.round((k1-k0)·f), ties toward +∞), so a keyed 0→25 seed reshuffles on every frame (the flicker).
     * Before the first / after the last key the end seed holds. A segment whose signed difference does not fit a Long
     * holds its first seed (such a span is far over [TextSeedRange.MAX_SEEDS] and is refused or clamped anyway).
     */
    fun seedAt(time: Long): Long {
        if(seedKeyframes.isEmpty()) return seed
        var lo=0;var hi=seedKeyframes.size
        while(lo<hi) { val mid=(lo+hi) ushr 1;if(seedKeyframes[mid].timeUs<=time) lo=mid+1 else hi=mid }
        if(lo==0) return seedKeyframes[0].seed
        if(lo==seedKeyframes.size) return seedKeyframes[lo-1].seed
        return TextSeedRange.between(seedKeyframes[lo-1],seedKeyframes[lo],time)
    }
    fun rebase(delta: Long) = copy(enabled=enabled.shift(delta),start=start.shift(delta),end=end.shift(delta),
        offset=offset.shift(delta),units=units.shift(delta),basedOn=basedOn.shift(delta),shape=shape.shift(delta),
        smoothness=smoothness.shift(delta),easeHigh=easeHigh.shift(delta),easeLow=easeLow.shift(delta),mode=mode.shift(delta),
        amount=amount.shift(delta),minAmount=minAmount.shift(delta),maxAmount=maxAmount.shift(delta),randomizeOrder=randomizeOrder.shift(delta),
        kind=kind.shift(delta),wigglesPerSecond=wigglesPerSecond.shift(delta),correlation=correlation.shift(delta),
        temporalPhase=temporalPhase.shift(delta),spatialPhase=spatialPhase.shift(delta),lockDimensions=lockDimensions.shift(delta),
        seedKeyframes=FrozenList.of(seedKeyframes.map { it.copy(timeUs=Math.subtractExact(it.timeUs,delta)) }))
}

/**
 * TEXT F3.2: interpolated Random Seed. One selector prepares one cached permutation set per distinct integer seed it
 * can reach, so evaluation never allocates; the reachable set is bounded by [MAX_SEEDS] and the 32 MiB permutation pool.
 */
object TextSeedRange {
    /** Distinct prepared seeds per selector (a 0→200 ramp reaches 201). */
    const val MAX_SEEDS=256
    fun between(k0: TextSeedKeyframe,k1: TextSeedKeyframe,time: Long): Long {
        val d=try { Math.subtractExact(k1.seed,k0.seed) } catch(_: ArithmeticException) { return k0.seed }
        val f=(time-k0.timeUs).toDouble()/(k1.timeUs-k0.timeUs).toDouble()
        return k0.seed+Math.round(d.toDouble()*f).coerceIn(minOf(0L,d),maxOf(0L,d))
    }
    /** Inclusive seed spans the selector reaches, merged and ascending. */
    private fun spans(s: TextRangeSelector): List<LongArray> {
        val keys=s.seedKeyframes
        if(keys.isEmpty()) return listOf(longArrayOf(s.seed,s.seed))
        val raw=ArrayList<LongArray>()
        raw.add(longArrayOf(keys[0].seed,keys[0].seed))
        for(i in 1 until keys.size) {
            val a=keys[i-1].seed;val b=keys[i].seed
            val overflow=try { Math.subtractExact(b,a);false } catch(_: ArithmeticException) { true }
            if(overflow) { raw.add(longArrayOf(a,a));raw.add(longArrayOf(b,b)) } else raw.add(longArrayOf(minOf(a,b),maxOf(a,b)))
        }
        raw.sortBy { it[0] }
        val merged=ArrayList<LongArray>()
        for(r in raw) { val last=merged.lastOrNull()
            if(last!=null && (r[0]<=last[1] || r[0]==last[1]+1)) last[1]=maxOf(last[1],r[1]) else merged.add(r.copyOf()) }
        return merged
    }
    /** Distinct integer seeds the selector can reach (saturating at Long.MAX_VALUE). */
    fun reachable(s: TextRangeSelector): Long {
        var total=0L
        for(r in spans(s)) {
            val n=try { Math.addExact(Math.subtractExact(r[1],r[0]),1L) } catch(_: ArithmeticException) { return Long.MAX_VALUE }
            total=try { Math.addExact(total,n) } catch(_: ArithmeticException) { return Long.MAX_VALUE }
        }
        return total
    }
    /** Per-selector cap: [MAX_SEEDS], or fewer when one seed's permutations ([bytesPerSeed]) would not fit the pool. */
    fun cap(bytesPerSeed: Long)=if(bytesPerSeed<=0) MAX_SEEDS else minOf(MAX_SEEDS.toLong(),maxOf(1L,LinePermutationPool.MAX_BYTES/bytesPerSeed)).toInt()
    /**
     * The seeds to prepare, ascending, at most [cap]. Over the cap (only an old or external document; edits are refused
     * first) the keyed seeds are kept first and evaluation uses the nearest prepared seed ([nearest]).
     */
    fun prepared(s: TextRangeSelector,cap: Int): LongArray {
        require(cap>=1)
        if(reachable(s)<=cap) {
            val out=LongArray(reachable(s).toInt());var i=0
            for(r in spans(s)) { var v=r[0];while(true) { out[i++]=v;if(v==r[1]) break;v++ } }
            return out
        }
        val chosen=java.util.TreeSet<Long>()
        for(k in s.seedKeyframes) { if(chosen.size>=cap) break;chosen.add(k.seed) }
        outer@ for(r in spans(s)) { var v=r[0];while(chosen.size<cap) { chosen.add(v);if(v==r[1]) continue@outer;v++ };break }
        return chosen.toLongArray()
    }
    /** Index in sorted [seeds] of [seed], or of the nearest prepared seed below it (the first when none is below). No allocation. */
    fun nearest(seeds: LongArray,seed: Long): Int {
        val i=java.util.Arrays.binarySearch(seeds,seed)
        return if(i>=0) i else maxOf(0,-i-2)
    }
    fun message(cap: Int)="Random seed animation reaches more than $cap seeds per selector; keep keyed seeds within $cap of each other"
    /** Edit-time pre-check (the Animate page refuses the edit with this line), like TextCharacterPreparation.problem. */
    fun problem(text: TextProperties): String? =
        if(text.animators.any { a -> a.selectors.any { reachable(it)>MAX_SEEDS } }) message(MAX_SEEDS) else null
}

data class TextPropertyOverride(val property: AnimatorProperty,
    val scalar: AnimatableProperty1D? = null, val vector: AnimatableProperty2D? = null, val colour: AnimatableColour? = null) {
    init {
        require(listOfNotNull(scalar,vector,colour).size == 1)
        require(when(property.kind) { 1 -> scalar != null; 2 -> vector != null; else -> colour != null })
        val percent = property==AnimatorProperty.OPACITY || property==AnimatorProperty.LINE_ANCHOR ||
            property==AnimatorProperty.FILL_OPACITY || property==AnimatorProperty.STROKE_OPACITY
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
        selectors.flatMap { it.tracks.map { p -> p.keyframes.map { k -> k.timeUs } } + listOf(it.seedKeyframes.map { k->k.timeUs }) } + properties.map { it.keyTimes() }
    fun activeKeyTimes() = (listOf(enabled,anchorGrouping).filter { it.isAnimated }.flatMap { it.keyframes.map { k -> k.timeUs } } +
        selectors.flatMap { it.tracks.filter { p -> p.isAnimated }.flatMap { p -> p.keyframes.map { k -> k.timeUs } } } +
        selectors.flatMap { it.seedKeyframes.map { k->k.timeUs } } + properties.flatMap { it.activeKeyTimes() }).distinct().sorted()
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

/**
 * GRAPH G1: legal ranges of evaluated animator values (the same ranges [TextRangeSelector] / [TextPropertyOverride]
 * validate for stored values). A Custom/Elastic segment can evaluate outside its keys, so consumers clamp here; stored
 * data is never changed. Identity for every in-range value.
 */
object AnimatorLimits {
    const val SMOOTHNESS_MAX = 100.0
    const val AMOUNT = 100.0
    const val WIGGLES_MAX = 1000.0
    const val CORRELATION_MAX = 100.0
    fun percent(p: AnimatorProperty) = p == AnimatorProperty.OPACITY || p == AnimatorProperty.LINE_ANCHOR ||
        p == AnimatorProperty.FILL_OPACITY || p == AnimatorProperty.STROKE_OPACITY
    fun clamp(p: AnimatorProperty, value: Float): Float = if (percent(p)) value.coerceIn(0f, 100f) else value
    fun amount(value: Float): Double = SelectorMath.clamp(value.toDouble(), -AMOUNT, AMOUNT)
    fun smoothness(value: Float): Double = SelectorMath.clamp(value.toDouble(), 0.0, SMOOTHNESS_MAX)
    fun wiggles(value: Float): Double = SelectorMath.clamp(value.toDouble(), 0.0, WIGGLES_MAX)
    fun correlation(value: Float): Double = SelectorMath.clamp(value.toDouble(), 0.0, CORRELATION_MAX)
}
