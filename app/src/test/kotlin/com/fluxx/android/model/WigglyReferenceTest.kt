package com.fluxx.android.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random

/**
 * TEXT PERF 10: the Wiggly noise (shared axis hashes, exact inline floor) and the character validity check must give
 * bit-identical results to the TEXT FIX 9 code, kept here verbatim as the reference.
 */
class WigglyReferenceTest {
    private object Reference {
        fun mix(value: Long): Long {
            var z=value
            z=(z xor (z ushr 30))*0xBF58476D1CE4E5B9uL.toLong()
            z=(z xor (z ushr 27))*0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
        fun lattice(seed: Long,x: Long,y: Long,dimension: Int): Double {
            val bits=mix(seed xor mix(x+0x9E3779B97F4A7C15uL.toLong()) xor
                mix(y+0xD1B54A32D192ED03uL.toLong()) xor mix(dimension.toLong()+0x632BE59BD9B4E019L))
            return (bits ushr 11).toDouble()/4503599627370496.0-1.0
        }
        fun noise(seed: Long,t: Double,s: Double,dimension: Int): Double {
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
        fun valid(value: Double): Boolean = value.isFinite() && value>=0 && value<=0x10ffff &&
            value.toInt() !in 0xd800..0xdfff && Character.getType(value.toInt())!=Character.CONTROL.toInt() &&
            Character.getType(value.toInt())!=Character.FORMAT.toInt()
    }

    private fun bits(x: Double)=java.lang.Double.doubleToRawLongBits(x)

    @Test fun wigglyValueIsBitIdenticalToReference() {
        val random=Random(0xF1C5)
        val seeds=longArrayOf(0,-1,42,Long.MAX_VALUE,Long.MIN_VALUE)
        repeat(300_000) { i ->
            val seed=if(i%7==0) seeds[i%seeds.size] else random.nextLong()
            val member=if(i%11==0) random.nextInt() else random.nextInt(-1,5000)
            val time=when(i%5) { 0->random.nextLong(); 1->random.nextLong(-10_000_000_000,10_000_000_000)
                else->random.nextLong(0,120_000_000) }
            val frequency=when(i%6) { 0->0.0; 1->random.nextDouble(-1e6,1e6); else->random.nextDouble(0.0,30.0) }
            val correlation=if(i%4==0) random.nextDouble(-50.0,150.0) else random.nextDouble(0.0,100.0)
            val temporal=random.nextDouble(-1e5,1e5);val spatial=if(i%9==0) random.nextDouble(-1e12,1e12) else random.nextDouble(-720.0,720.0)
            val dimension=random.nextInt(0,4);val locked=random.nextBoolean()
            assertEquals("case $i",bits(Reference.value(seed,member,time,frequency,correlation,temporal,spatial,dimension,locked)),
                bits(WigglySelectorMath.value(seed,member,time,frequency,correlation,temporal,spatial,dimension,locked)))
        }
    }

    @Test fun nonFiniteWigglyInputsMatchReference() {
        val odd=doubleArrayOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-0.0,0.0,1e300,-1e300,Double.MIN_VALUE)
        for(a in odd) for(b in odd) {
            val expected=Reference.value(7,3,1_000_000,a,40.0,b,13.0,1,false)
            val actual=WigglySelectorMath.value(7,3,1_000_000,a,40.0,b,13.0,1,false)
            if(expected.isNaN()) assertEquals(true,actual.isNaN()) else assertEquals(bits(expected),bits(actual))
            val e2=Reference.value(7,3,1_000_000,2.0,a,0.0,b,1,true);val a2=WigglySelectorMath.value(7,3,1_000_000,2.0,a,0.0,b,1,true)
            if(e2.isNaN()) assertEquals(true,a2.isNaN()) else assertEquals(bits(e2),bits(a2))
        }
    }

    @Test fun characterValidityMatchesReference() {
        val special=doubleArrayOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-0.0,0.0,-1.0,-0.5,
            0x10ffff.toDouble(),0x10ffff+.5,0x110000.toDouble(),0xd7ff.toDouble(),0xd800.toDouble(),0xdfff+.9,0xe000.toDouble(),
            Double.MAX_VALUE,1e20,-1e20)
        for(v in special) assertEquals("valid($v)",Reference.valid(v),TextCharacterPreparation.valid(v))
        for(code in -2..0x110001) { val v=code.toDouble();assertEquals("valid($v)",Reference.valid(v),TextCharacterPreparation.valid(v)) }
        val random=Random(9)
        repeat(200_000) { val v=random.nextDouble(-10.0,0x110010.toDouble());assertEquals("valid($v)",Reference.valid(v),TextCharacterPreparation.valid(v)) }
    }
}
