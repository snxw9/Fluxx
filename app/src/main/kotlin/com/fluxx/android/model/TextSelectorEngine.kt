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
    fun smooth(t: Double) = t*t*(3-2*t)
    fun shape(shape: SelectorShape,u: Double): Double = when(shape) {
        SelectorShape.SQUARE -> 1.0
        SelectorShape.RAMP_UP -> u
        SelectorShape.RAMP_DOWN -> 1-u
        SelectorShape.TRIANGLE -> 1-abs(2*u-1)
        SelectorShape.ROUND -> (2*u-1).let { x -> sqrt(max(0.0,1-x*x)) }
        SelectorShape.SMOOTH -> smooth(1-abs(2*u-1))
    }
    fun combine(c: Double,v: Double,mode: SelectorMode): Double = when(mode) {
        SelectorMode.ADD -> c+v;SelectorMode.SUBTRACT -> c-v;SelectorMode.INTERSECT -> c*v
        SelectorMode.MIN -> min(c,v);SelectorMode.MAX -> max(c,v);SelectorMode.DIFFERENCE -> abs(c-v)
    }.coerceIn(-1.0,1.0)
    fun initial(mode: SelectorMode) = if(mode==SelectorMode.MIN || mode==SelectorMode.INTERSECT) 1.0 else 0.0
    fun lineShift(lineAnchor: Double, selectedTrackingSum: Double) = -lineAnchor/100*selectedTrackingSum
    /** Anchor-only animation moves by -c*anchor even when R/Skew/S are identity. */
    fun affine(out: DoubleArray,c: Double,posX: Double,posY: Double,pivotX: Double,pivotY: Double,
        anchorX: Double,anchorY: Double,rotation: Double,scaleX: Double,scaleY: Double,skew: Double=0.0,axis: Double=0.0) {
        require(out.size>=6)
        val r=Math.toRadians(rotation*c);val a=Math.toRadians(axis)
        val k=tan(Math.toRadians((skew*c).coerceIn(-85.0,85.0)))
        val ca=cos(a);val sa=sin(a);val cr=cos(r);val sr=sin(r)
        val sx=1+c*(scaleX/100-1);val sy=1+c*(scaleY/100-1)
        val h00=1-ca*sa*k; val h01=ca*ca*k;val h10=-sa*sa*k;val h11=1+ca*sa*k
        val m00=(cr*h00-sr*h10)*sx;val m01=(cr*h01-sr*h11)*sy
        val m10=(sr*h00+cr*h10)*sx;val m11=(sr*h01+cr*h11)*sy
        out[0]=m00;out[1]=m10;out[2]=m01;out[3]=m11
        out[4]=posX*c+pivotX-m00*(pivotX+c*anchorX)-m01*(pivotY+c*anchorY)
        out[5]=posY*c+pivotY-m10*(pivotX+c*anchorX)-m11*(pivotY+c*anchorY)
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
        val l=lp.toInt().coerceIn(0,19);val h=hp.toInt().coerceIn(0,19);val i=tp.toInt()
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
    private val ease: SelectorEaseTable,linePool: LinePermutationPool?=null,lineMinimum: Int=0,lineMaximum: Int=membership.counts[3]) {
    private val weights=DoubleArray(membership.characterCount)
    private val ranks=Array(animator.selectors.size) { s -> Array(4) { v -> SelectorMath.permutation(membership.counts[v],animator.selectors[s].seed) } }
    private val cache=Array(animator.selectors.size) { DoubleArray(membership.characterCount) }
    private val static=BooleanArray(animator.selectors.size) { i -> animator.selectors[i].tracks.none { it.isAnimated && it.keyframes.isNotEmpty() } }
    private val ready=BooleanArray(animator.selectors.size)
    private val revisions=LongArray(animator.selectors.size) { -1 }
    private val lineRanks=Array(animator.selectors.size) { s ->
        val selector=animator.selectors[s]
        val random=selector.randomizeOrder.staticValue!=0f || selector.randomizeOrder.keyframes.any { it.value!=0f }
        val lines=selector.basedOn.staticValue==3f || selector.basedOn.keyframes.any { it.value==3f }
        if(linePool!=null && random && lines) linePool.prepare(selector.seed,lineMinimum,lineMaximum) else null
    }
    var coverageBuilds: Long = 0; private set
    fun evaluate(time: Long,out: DoubleArray) {
        require(out.size==membership.starts.size)
        out.fill(0.0)
        if(animator.enabled.hold(time)==0f) return
        var first=true
        for(s in animator.selectors.indices) {
            val selector=animator.selectors[s]
            if(selector.enabled.hold(time)==0f) continue
            val mode=SelectorMode.entries[selector.mode.hold(time).toInt()]
            if(first) { weights.fill(SelectorMath.initial(mode));first=false }
            if(!static[s] || !ready[s] || revisions[s]!=membership.revision) {
                coverage(selector,s,time,cache[s]);ready[s]=true;revisions[s]=membership.revision;coverageBuilds++
            }
            for(i in weights.indices) weights[i]=SelectorMath.combine(weights[i],cache[s][i],mode)
        }
        if(first) return
        for(c in out.indices) {
            var sum=0.0;for(i in membership.starts[c] until membership.ends[c]) sum+=weights[i]
            out[c]=sum/(membership.ends[c]-membership.starts[c])
        }
    }
    private fun coverage(p: TextRangeSelector,index: Int,time: Long,out: DoubleArray) {
        val variant=p.basedOn.hold(time).toInt();val n=membership.counts[variant]
        val factor=if(p.units.hold(time)==0f) n/100.0 else 1.0
        val a=(p.start.evaluate(time).toDouble()+p.offset.evaluate(time))*factor
        val b=(p.end.evaluate(time).toDouble()+p.offset.evaluate(time))*factor
        if(n==0 || b<=a) { out.fill(0.0);return }
        val shape=SelectorShape.entries[p.shape.hold(time).toInt()]
        val smooth=p.smoothness.evaluate(time)/200.0
        val low=p.easeLow.evaluate(time).toDouble();val high=p.easeHigh.evaluate(time).toDouble()
        val av=p.minAmount.evaluate(time).toDouble();val bv=p.maxAmount.evaluate(time).toDouble()
        val lo=min(av,bv);val hi=max(av,bv);val amount=p.amount.evaluate(time)/100.0
        val random=p.randomizeOrder.hold(time)!=0f
        for(i in out.indices) {
            val member=membership.maps[variant][i]
            if(member<0) { out[i]=0.0;continue }
            val permutation=if(variant==3 && lineRanks[index]!=null) requireNotNull(lineRanks[index]!![n]) {
                "Wrapped line count has no prepared permutation"
            } else ranks[index][variant]
            val x=(if(random) permutation[member] else member)+0.5
            var e=1.0;var v=1.0
            if(shape==SelectorShape.SQUARE) {
                e=if(smooth==0.0) { if(x>=a && x<b) 1.0 else 0.0 } else
                    SelectorMath.smooth(((x-a+smooth)/(2*smooth)).coerceIn(0.0,1.0))*
                    SelectorMath.smooth(((b+smooth-x)/(2*smooth)).coerceIn(0.0,1.0))
                e=ease.evaluate(e,low,high)
            } else if(x<a || x>=b) e=0.0
            else v=ease.evaluate(SelectorMath.shape(shape,(x-a)/(b-a)),low,high)
            out[i]=(amount*e*(lo/100+(hi-lo)/100*v)).coerceIn(-1.0,1.0)
        }
    }
}
