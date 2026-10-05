package com.fluxx.android.model

import kotlin.math.floor

/** Stateless SplitMix64 lattice value noise. No generator objects or allocations. */
object WigglySelectorMath {
    private fun mix(value: Long): Long {
        var z=value
        z=(z xor (z ushr 30))*0xBF58476D1CE4E5B9uL.toLong()
        z=(z xor (z ushr 27))*0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    private fun lattice(seed: Long,x: Long,y: Long,dimension: Int): Double {
        val bits=mix(seed xor mix(x+0x9E3779B97F4A7C15uL.toLong()) xor
            mix(y+0xD1B54A32D192ED03uL.toLong()) xor mix(dimension.toLong()+0x632BE59BD9B4E019L))
        return (bits ushr 11).toDouble()/4503599627370496.0-1.0
    }
    private fun noise(seed: Long,t: Double,s: Double,dimension: Int): Double {
        // Modular coordinates avoid saturating Long conversion for large finite phase values.
        val tt=t%1_000_000_000.0;val ss=s%1_000_000_000.0
        val x=floor(tt).toLong();val y=floor(ss).toLong()
        val u=SelectorMath.smooth(tt-floor(tt));val v=SelectorMath.smooth(ss-floor(ss))
        val a=lattice(seed,x,y,dimension);val b=lattice(seed,x+1,y,dimension)
        val c=lattice(seed,x,y+1,dimension);val d=lattice(seed,x+1,y+1,dimension)
        return (a+(b-a)*u)*(1-v)+(c+(d-c)*u)*v
    }
    fun value(seed: Long,member: Int,timeUs: Long,frequency: Double,correlation: Double,
        temporalPhase: Double,spatialPhase: Double,dimension: Int,lockDimensions: Boolean): Double {
        val t=timeUs/1_000_000.0*frequency+temporalPhase/360.0
        val s=spatialPhase/360.0;val dim=if(lockDimensions) 0 else dimension
        val rho=correlation/100.0
        return noise(seed,t,member+s,dim)*(1-rho)+noise(seed,t,s,dim)*rho
    }
}
