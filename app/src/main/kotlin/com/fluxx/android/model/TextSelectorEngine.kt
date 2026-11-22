package com.fluxx.android.model

import kotlin.math.*

class SplitMix64(seed: Long) {
    private var state = seed.toULong()
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}

object SelectorMath {
    fun permutation(size: Int, seed: Long): IntArray {
        require(size >= 0)
        val p=IntArray(size) { it }; val rng=SplitMix64(seed)
        for(i in size-1 downTo 1) {
            val bound=(i+1).toULong(); val threshold=(0uL-bound)%bound
            var r=rng.next(); while(r<threshold) r=rng.next()
            val j=(r%bound).toInt(); val v=p[i];p[i]=p[j];p[j]=v
        }
        // Original membership -> selection rank, not reordered glyph drawing.
        return IntArray(size).also { inverse -> p.forEachIndexed { rank, original -> inverse[original]=rank } }
    }
    /** Same as coerceIn (including NaN passing through), inlined: kotlin.ranges.coerceIn is a real call per value. */
    @Suppress("NOTHING_TO_INLINE") inline fun clamp(x: Double,low: Double,high: Double)=if(x<low) low else if(x>high) high else x
    /** Same as abs() for every non-NaN input (abs(-0.0) is +0.0 here too), without doubleToRawLongBits. */
    @Suppress("NOTHING_TO_INLINE") inline fun magnitude(x: Double)=if(x<=0.0) 0.0-x else x
    /** Math.min / Math.max exactly (NaN first operand, signed zeros), without doubleToRawLongBits on two zeros. */
    @Suppress("NOTHING_TO_INLINE") inline fun least(a: Double,b: Double)=if(a!=a) a else if(a==0.0 && b==0.0) (if(1.0/b<0) b else a) else if(a<=b) a else b
    @Suppress("NOTHING_TO_INLINE") inline fun greatest(a: Double,b: Double)=if(a!=a) a else if(a==0.0 && b==0.0) (if(1.0/a<0) b else a) else if(a>=b) a else b
    /**
     * Exactly Math.floor for every double (signed zero, infinities and integral values of 2^52 and above pass
     * through; a NaN is quieted by an FPU add, as floor quiets it), inlined: in debuggable ART Math.floor is a
     * generic JNI call (TEXT PERF 10).
     */
    @Suppress("NOTHING_TO_INLINE") inline fun floor(x: Double): Double {
        if(x!=x) return x+0.0
        if(!(x>-4503599627370496.0 && x<4503599627370496.0) || x==0.0) return x
        val f=x.toLong().toDouble()
        return if(f>x) f-1.0 else f
    }
    fun smooth(t: Double) = t*t*(3-2*t)
    fun shape(shape: SelectorShape,u: Double): Double = when(shape) {
        SelectorShape.SQUARE -> 1.0
        SelectorShape.RAMP_UP -> u
        SelectorShape.RAMP_DOWN -> 1-u
        SelectorShape.TRIANGLE -> 1-magnitude(2*u-1)
        SelectorShape.ROUND -> (2*u-1).let { x -> sqrt(max(0.0,1-x*x)) }
        SelectorShape.SMOOTH -> smooth(1-magnitude(2*u-1))
    }
    fun combine(c: Double,v: Double,mode: SelectorMode): Double = when(mode) {
        SelectorMode.ADD -> c+v;SelectorMode.SUBTRACT -> c-v;SelectorMode.INTERSECT -> c*v
        SelectorMode.MIN -> least(c,v);SelectorMode.MAX -> greatest(c,v);SelectorMode.DIFFERENCE -> magnitude(c-v)
    }.let { clamp(it,-1.0,1.0) }
    fun initial(mode: SelectorMode) = if(mode==SelectorMode.MIN || mode==SelectorMode.INTERSECT) 1.0 else 0.0
    fun lineShift(lineAnchor: Double, selectedTrackingSum: Double) = -lineAnchor/100*selectedTrackingSum
    /** Anchor-only animation moves by -c*anchor even when R/Skew/S are identity. */
    fun affine(out: DoubleArray,c: Double,posX: Double,posY: Double,pivotX: Double,pivotY: Double,
        anchorX: Double,anchorY: Double,rotation: Double,scaleX: Double,scaleY: Double,skew: Double=0.0,axis: Double=0.0,weightY: Double=c) =
        affine(out,c,posX,posY,pivotX,pivotY,anchorX,anchorY,rotation,scaleX,scaleY,skew,axis,weightY,null)
    /**
     * [trig] (TEXT PERF 10) memoises sin/cos/tan on their exact radian inputs. Per-glyph calls repeat the axis every
     * time and rotation/skew whenever coverage weights repeat; each trig call is a JNI call in the debuggable JIT.
     * The values are the same as recomputing (same input bits, same function).
     */
    fun affine(out: DoubleArray,c: Double,posX: Double,posY: Double,pivotX: Double,pivotY: Double,
        anchorX: Double,anchorY: Double,rotation: Double,scaleX: Double,scaleY: Double,skew: Double,axis: Double,weightY: Double,trig: AffineTrig?) {
        require(out.size>=6)
        val r=Math.toRadians(rotation*c);val a=Math.toRadians(axis)
        val skewRadians=Math.toRadians(clamp(skew*c,-85.0,85.0))
        val k: Double;val ca: Double;val sa: Double;val cr: Double;val sr: Double
        if(trig==null) { k=tan(skewRadians);ca=cos(a);sa=sin(a);cr=cos(r);sr=sin(r) }
        else {
            if(!AffineTrig.same(trig.axis,a)) { trig.axis=a;trig.ca=cos(a);trig.sa=sin(a) }
            if(!AffineTrig.same(trig.rotation,r)) { trig.rotation=r;trig.cr=cos(r);trig.sr=sin(r) }
            if(!AffineTrig.same(trig.skew,skewRadians)) { trig.skew=skewRadians;trig.k=tan(skewRadians) }
            k=trig.k;ca=trig.ca;sa=trig.sa;cr=trig.cr;sr=trig.sr
        }
        val sx=1+c*(scaleX/100-1);val sy=1+weightY*(scaleY/100-1)
        val h00=1-ca*sa*k; val h01=ca*ca*k;val h10=-sa*sa*k;val h11=1+ca*sa*k
        val m00=(cr*h00-sr*h10)*sx;val m01=(cr*h01-sr*h11)*sy
        val m10=(sr*h00+cr*h10)*sx;val m11=(sr*h01+cr*h11)*sy
        out[0]=m00;out[1]=m10;out[2]=m01;out[3]=m11
        out[4]=posX*c+pivotX-m00*(pivotX+c*anchorX)-m01*(pivotY+weightY*anchorY)
        out[5]=posY*weightY+pivotY-m10*(pivotX+c*anchorX)-m11*(pivotY+weightY*anchorY)
    }
}

/** Last trigonometry inputs/results for [SelectorMath.affine]; one per evaluating animation (not thread-shared). */
class AffineTrig {
    var axis=Double.NaN;var ca=1.0;var sa=0.0
    var rotation=Double.NaN;var cr=1.0;var sr=0.0
    var skew=Double.NaN;var k=0.0
    companion object {
        /** Same bits without doubleToRawLongBits (a JNI call when not intrinsified): equal, and the same zero sign. NaN never matches. */
        fun same(a: Double,b: Double)=a==b && (a!=0.0 || 1.0/a==1.0/b)
    }
}

/** Prepared once: 21×21 ease pairs and 257 coverage samples. Trilinear interpolation
 * handles animated fractional ease without pow or rebuilding tables during evaluation. */
class SelectorEaseTable {
    companion object { val shared by lazy { SelectorEaseTable() } }
    private val samples=DoubleArray(21*21*257)
    init { for(l in 0..20) for(h in 0..20) for(i in 0..256) {
        val t=i/256.0; val p=2.0.pow((l*10-100)/100.0);val q=2.0.pow((h*10-100)/100.0)
        samples[(l*21+h)*257+i]=when(i) {0->0.0;256->1.0;else->t.pow(p)/(t.pow(p)+(1-t).pow(q))}
    } }
    private fun at(l: Int,h: Int,i: Int,f: Double): Double {
        val index=(l*21+h)*257+i
        return samples[index]+(samples[index+1]-samples[index])*f
    }
    fun evaluate(t: Double,low: Double,high: Double): Double {
        if(low==0.0 && high==0.0) return t // No lookup for identity.
        if(t<=0) return 0.0;if(t>=1) return 1.0
        val lp=(low+100)/10;val hp=(high+100)/10;val tp=t*256
        val li=lp.toInt();val hi=hp.toInt();val l=if(li<0) 0 else if(li>19) 19 else li;val h=if(hi<0) 0 else if(hi>19) 19 else hi;val i=tp.toInt()
        val lf=lp-l;val hf=hp-h;val tf=tp-i
        val a=at(l,h,i,tf)*(1-hf)+at(l,h+1,i,tf)*hf
        val b=at(l+1,h,i,tf)*(1-hf)+at(l+1,h+1,i,tf)*hf
        return a*(1-lf)+b*lf
    }
}

/** Inputs supplied from the F1 prepared map in F2b; never independently reshapes text.
 * Each map has one member index per source character, -1 for excluded whitespace. */
class SelectorMembership(maps: Array<IntArray>,counts: IntArray,starts: IntArray,ends: IntArray) {
    var revision=0L;private set
    val maps=Array(4) { maps[it].copyOf() }; val counts=counts.copyOf()
    val starts=starts.copyOf(); val ends=ends.copyOf()
    val characterCount=this.maps[0].size
    fun updateLines(clusterLines: IntArray,lineCount: Int) {
        require(clusterLines.size==starts.size && lineCount in 0..LinePermutationPool.MAX_LINES)
        var changed=counts[3]!=lineCount
        for(c in starts.indices) for(i in starts[c] until ends[c]) {
            if(this.maps[3][i]!=clusterLines[c]) { this.maps[3][i]=clusterLines[c];changed=true }
        }
        counts[3]=lineCount
        if(changed) revision++
    }
    init {
        require(maps.size==4 && counts.size==4 && starts.size==ends.size)
        require(this.maps.all { it.size==characterCount })
        for(v in 0..3) { require(this.counts[v]>=0);require(this.maps[v].all { it>=-1 && it<this.counts[v] }) }
        starts.indices.forEach { require(starts[it]>=0 && ends[it]>starts[it] && ends[it]<=characterCount) }
    }
}

/** Shared across both modes and source alternatives in a layer. Preparation only. */
class LinePermutationPool {
    companion object { const val MAX_LINES=256;const val MAX_BYTES=32L*1024*1024 }
    private val stored=HashMap<Pair<Long,Int>,IntArray>()
    var bytes=0L;private set
    var builds=0L;private set
    fun rank(seed: Long,count: Int): IntArray {
        require(count in 0..4096)
        return stored.getOrPut(seed to count) {
            val cost=count*4L
            require(bytes+cost<=MAX_BYTES) { "Text permutations exceed 32 MiB per layer; reduce seeds or randomized selectors" }
            bytes+=cost;builds++
            SelectorMath.permutation(count,seed)
        }
    }
    fun prepare(seed: Long,minimum: Int,maximum: Int): Array<IntArray?> {
        require(minimum in 0..maximum && maximum<=MAX_LINES)
        return arrayOfNulls<IntArray>(maximum+1).also { result ->
            for(count in minimum..maximum) result[count]=stored.getOrPut(seed to count) {
                require(bytes+count*4L<=MAX_BYTES) { "Randomized wrapped-line permutations exceed 32 MiB per layer; reduce distinct selector seeds" }
                bytes+=count*4L;builds++;SelectorMath.permutation(count,seed)
            }
        }
    }
}

/** Sparse execution; immutable animator snapshot makes invalidation explicit: rebuild on edits.
 * All four maps/permutations exist before evaluation. Units changes need no extra permutation. */
class PreparedSelectorEngine(private val animator: TextAnimator, private val membership: SelectorMembership,
    private val ease: SelectorEaseTable,private val linePool: LinePermutationPool?=null,private val lineMinimum: Int=0,private val lineMaximum: Int=membership.counts[3],
    memo: TrackMemo=TrackMemo()) {
    /** TEXT PERF 11: the selector tracks as shared memo slots (equal tracks across layers evaluate once per time). */
    private class Slots(memo: TrackMemo,p: TextRangeSelector) {
        val enabled=memo.scalar(p.enabled);val mode=memo.scalar(p.mode);val basedOn=memo.scalar(p.basedOn);val kind=memo.scalar(p.kind)
        val minAmount=memo.scalar(p.minAmount);val maxAmount=memo.scalar(p.maxAmount);val amount=memo.scalar(p.amount)
        val wiggles=memo.scalar(p.wigglesPerSecond);val correlation=memo.scalar(p.correlation);val temporal=memo.scalar(p.temporalPhase)
        val spatial=memo.scalar(p.spatialPhase);val lock=memo.scalar(p.lockDimensions);val units=memo.scalar(p.units)
        val start=memo.scalar(p.start);val end=memo.scalar(p.end);val offset=memo.scalar(p.offset);val shape=memo.scalar(p.shape)
        val smoothness=memo.scalar(p.smoothness);val easeLow=memo.scalar(p.easeLow);val easeHigh=memo.scalar(p.easeHigh)
        val randomize=memo.scalar(p.randomizeOrder)
    }
    private val animatorEnabled=memo.scalar(animator.enabled)
    private val slots=Array(animator.selectors.size) { Slots(memo,animator.selectors[it]) }
    private val weights=DoubleArray(membership.characterCount)
    private val pool=linePool ?: LinePermutationPool()
    private fun lineRandom(selector: TextRangeSelector)=linePool!=null &&
        (selector.randomizeOrder.staticValue!=0f || selector.randomizeOrder.keyframes.any { it.value!=0f }) &&
        (selector.basedOn.staticValue==3f || selector.basedOn.keyframes.any { it.value==3f })
    /** Permutation bytes one prepared seed costs this selector (all four variants, plus wrapped-line ranks). */
    private fun bytesPerSeed(selector: TextRangeSelector): Long {
        var bytes=4L*membership.counts.sum()
        if(lineRandom(selector)) for(c in lineMinimum..lineMaximum) bytes+=4L*c
        return bytes
    }
    /**
     * TEXT F3.2: every distinct integer seed the interpolated seed track reaches, ascending (TextSeedRange), capped
     * at min(256, what fits the 32 MiB pool). Over the cap the selector degrades (nearest prepared seed) and
     * [seedsClamped] reports it; it never throws, so a loaded document always prepares.
     */
    private val seeds=Array(animator.selectors.size) { s ->
        val p=animator.selectors[s]
        TextSeedRange.prepared(p,TextSeedRange.cap(bytesPerSeed(p)))
    }
    /** A selector's reachable seeds exceeded its cap; evaluation uses the nearest prepared seed. */
    val seedsClamped=animator.selectors.indices.any { s -> TextSeedRange.reachable(animator.selectors[s])>seeds[s].size }
    val seedCap get()=animator.selectors.minOfOrNull { TextSeedRange.cap(bytesPerSeed(it)) } ?: TextSeedRange.MAX_SEEDS
    private val ranks=Array(animator.selectors.size) { s -> Array(seeds[s].size) { k ->
        Array(4) { v -> pool.rank(seeds[s][k],membership.counts[v]) }
    } }
    private val cache=Array(animator.selectors.size) { DoubleArray(membership.characterCount) }
    private val static=BooleanArray(animator.selectors.size) { i ->
        val p=animator.selectors[i]
        p.kind.staticValue==0f && p.kind.keyframes.none { it.value!=0f } && p.seedKeyframes.isEmpty() &&
            p.tracks.none { it.isAnimated && it.keyframes.isNotEmpty() }
    }
    private val ready=BooleanArray(animator.selectors.size)
    private val revisions=LongArray(animator.selectors.size) { -1 }
    private val lineRanks=Array(animator.selectors.size) { s ->
        if(lineRandom(animator.selectors[s])) Array(seeds[s].size) { k -> pool.prepare(seeds[s][k],lineMinimum,lineMaximum) } else null
    }
    var coverageBuilds: Long = 0; private set
    fun evaluate(time: Long,out: DoubleArray,dimension: Int=0) {
        require(out.size==membership.starts.size)
        out.fill(0.0)
        if(animatorEnabled.hold(time)==0f) return
        var first=true
        for(s in animator.selectors.indices) {
            val selector=animator.selectors[s]
            if(slots[s].enabled.hold(time)==0f) continue
            val mode=SelectorMode.entries[slots[s].mode.hold(time).toInt()]
            if(first) { weights.fill(SelectorMath.initial(mode));first=false }
            if(!static[s] || !ready[s] || revisions[s]!=membership.revision) {
                coverage(selector,s,time,cache[s],dimension);ready[s]=true;revisions[s]=membership.revision;coverageBuilds++
            }
            for(i in weights.indices) weights[i]=SelectorMath.combine(weights[i],cache[s][i],mode)
        }
        if(first) return
        for(c in out.indices) {
            var sum=0.0;for(i in membership.starts[c] until membership.ends[c]) sum+=weights[i]
            out[c]=sum/(membership.ends[c]-membership.starts[c])
        }
    }
    private fun coverage(p: TextRangeSelector,index: Int,time: Long,out: DoubleArray,dimension: Int) {
        val t=slots[index]
        val variant=t.basedOn.hold(time).toInt();val n=membership.counts[variant]
        if(t.kind.hold(time)==1f) {
            val a=t.minAmount.evaluate(time).toDouble();val b=t.maxAmount.evaluate(time).toDouble()
            val lo=min(a,b)/100;val hi=max(a,b)/100;val amount=t.amount.evaluate(time)/100.0
            val seed=seeds[index][TextSeedRange.nearest(seeds[index],p.seedAt(time))];val frequency=t.wiggles.evaluate(time).toDouble()
            val correlation=t.correlation.evaluate(time).toDouble();val temporal=t.temporal.evaluate(time).toDouble()
            val spatial=t.spatial.evaluate(time).toDouble();val locked=t.lock.hold(time)!=0f
            for(i in out.indices) {
                val member=membership.maps[variant][i]
                out[i]=if(member<0) 0.0 else (amount*(lo+(hi-lo)*(WigglySelectorMath.value(seed,member,time,frequency,
                    correlation,temporal,spatial,dimension,locked)+1)/2)).let { SelectorMath.clamp(it,-1.0,1.0) }
            }
            return
        }
        val factor=if(t.units.hold(time)==0f) n/100.0 else 1.0
        val a=(t.start.evaluate(time).toDouble()+t.offset.evaluate(time))*factor
        val b=(t.end.evaluate(time).toDouble()+t.offset.evaluate(time))*factor
        val shape=SelectorShape.entries[t.shape.hold(time).toInt()]
        // TEXT F3.2 (AE parity): Ramp Up fully selects everything after End, Ramp Down everything before Start.
        val rampUp=shape==SelectorShape.RAMP_UP;val rampDown=shape==SelectorShape.RAMP_DOWN
        if(n==0 || (b<=a && !rampUp && !rampDown)) { out.fill(0.0);return }
        val smooth=t.smoothness.evaluate(time)/200.0
        val low=t.easeLow.evaluate(time).toDouble();val high=t.easeHigh.evaluate(time).toDouble()
        val av=t.minAmount.evaluate(time).toDouble();val bv=t.maxAmount.evaluate(time).toDouble()
        val lo=min(av,bv);val hi=max(av,bv);val amount=t.amount.evaluate(time)/100.0
        val random=t.randomize.hold(time)!=0f
        val seedIndex=TextSeedRange.nearest(seeds[index],p.seedAt(time))
        for(i in out.indices) {
            val member=membership.maps[variant][i]
            if(member<0) { out[i]=0.0;continue }
            val permutation=if(variant==3 && lineRanks[index]!=null) requireNotNull(lineRanks[index]!![seedIndex][n]) {
                "Wrapped line count has no prepared permutation"
            } else ranks[index][seedIndex][variant]
            val x=(if(random) permutation[member] else member)+0.5
            var e=1.0;var v=1.0
            if(shape==SelectorShape.SQUARE) {
                e=if(smooth==0.0) { if(x>=a && x<b) 1.0 else 0.0 } else
                    SelectorMath.smooth(SelectorMath.clamp((x-a+smooth)/(2*smooth),0.0,1.0))*
                    SelectorMath.smooth(SelectorMath.clamp((b+smooth-x)/(2*smooth),0.0,1.0))
                e=ease.evaluate(e,low,high)
            } else if(x>=b) { if(!rampUp) e=0.0 } // Ramp Up: v stays 1 (ease(1)=1) past End.
            else if(x<a) { if(!rampDown) e=0.0 } // Ramp Down: v stays 1 before Start.
            else v=ease.evaluate(SelectorMath.shape(shape,(x-a)/(b-a)),low,high)
            out[i]=SelectorMath.clamp(amount*e*(lo/100+(hi-lo)/100*v),-1.0,1.0)
        }
    }
}
