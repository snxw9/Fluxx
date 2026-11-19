package com.fluxx.android.model

/** Stateless SplitMix64 lattice value noise. No generator objects or allocations. */
object WigglySelectorMath {
    @Suppress("NOTHING_TO_INLINE") private inline fun mix(value: Long): Long {
        var z=value
        z=(z xor (z ushr 30))*0xBF58476D1CE4E5B9uL.toLong()
        z=(z xor (z ushr 27))*0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    private const val X_KEY=-0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val Y_KEY=-0x2e4ab5cd2e6d12fdL // 0xD1B54A32D192ED03
    private const val D_KEY=0x632BE59BD9B4E019L
    /**
     * Lattice value for one corner: mix(seed xor mix(x+X) xor mix(y+Y) xor mix(dimension+D)), from the per-axis hashes.
     * TEXT PERF 10: the axis hashes are computed once per value and shared by the corners (and, for the time axis, by both
     * noise samples), instead of 20 mix calls per sample. XOR and wrapping addition make this bit-identical.
     */
    @Suppress("NOTHING_TO_INLINE") private inline fun corner(seed: Long,mx: Long,my: Long,md: Long): Double =
        (mix(seed xor mx xor my xor md) ushr 11).toDouble()/4503599627370496.0-1.0
    private fun noise(seed: Long,mx0: Long,mx1: Long,u: Double,md: Long,s: Double): Double {
        val ss=s%1_000_000_000.0
        val fs=SelectorMath.floor(ss);val y=fs.toLong()
        val v=SelectorMath.smooth(ss-fs)
        val my0=mix(y+Y_KEY);val my1=mix(y+1+Y_KEY)
        val a=corner(seed,mx0,my0,md);val b=corner(seed,mx1,my0,md)
        val c=corner(seed,mx0,my1,md);val d=corner(seed,mx1,my1,md)
        return (a+(b-a)*u)*(1-v)+(c+(d-c)*u)*v
    }
    fun value(seed: Long,member: Int,timeUs: Long,frequency: Double,correlation: Double,
        temporalPhase: Double,spatialPhase: Double,dimension: Int,lockDimensions: Boolean): Double {
        val t=timeUs/1_000_000.0*frequency+temporalPhase/360.0
        val s=spatialPhase/360.0;val dim=if(lockDimensions) 0 else dimension
        val rho=correlation/100.0
        // Modular coordinates avoid saturating Long conversion for large finite phase values.
        // SelectorMath.floor is Math.floor exactly, without the JNI call (TEXT PERF 10); each floor is taken once.
        val tt=t%1_000_000_000.0;val ft=SelectorMath.floor(tt);val x=ft.toLong()
        val u=SelectorMath.smooth(tt-ft)
        val mx0=mix(x+X_KEY);val mx1=mix(x+1+X_KEY);val md=mix(dim.toLong()+D_KEY)
        return noise(seed,mx0,mx1,u,md,member+s)*(1-rho)+noise(seed,mx0,mx1,u,md,s)*rho
    }
}
