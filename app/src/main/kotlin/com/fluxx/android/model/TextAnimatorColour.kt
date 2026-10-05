package com.fluxx.android.model

import kotlin.math.*

/** Straight sRGB HSV adjustments; opacity stays separate from RGB. No working objects. */
object TextAnimatorColour {
    fun opacity(argb: Int,percent: Double,weight: Double): Int {
        val multiplier=(1+weight*(percent/100-1)).coerceIn(0.0,1.0)
        val alpha=(((argb ushr 24)*multiplier)+.5).toInt().coerceIn(0,255)
        return (argb and 0x00ffffff) or (alpha shl 24)
    }
    fun hsb(argb: Int,hue: Double,saturation: Double,brightness: Double): Int {
        if(hue==0.0 && saturation==0.0 && brightness==0.0) return argb
        val r=((argb ushr 16) and 255)/255.0;val g=((argb ushr 8) and 255)/255.0;val b=(argb and 255)/255.0
        val high=max(r,max(g,b));val low=min(r,min(g,b));val delta=high-low
        var h=if(delta==0.0) 0.0 else if(high==r) 60*((g-b)/delta) else if(high==g) 60*((b-r)/delta+2) else 60*((r-g)/delta+4)
        h=((h+hue)%360+360)%360
        val s=((if(high==0.0) 0.0 else delta/high)+saturation/100).coerceIn(0.0,1.0)
        val v=(high+brightness/100).coerceIn(0.0,1.0)
        val c=v*s;val x=c*(1-abs((h/60)%2-1));val m=v-c
        val sector=(h/60).toInt()
        fun channel(a: Double)=((a+m)*255+.5).toInt().coerceIn(0,255)
        val rr=when(sector) { 0,5->c;1,4->x;else->0.0 }
        val gg=when(sector) { 1,2->c;0,3->x;else->0.0 }
        val bb=when(sector) { 3,4->c;2,5->x;else->0.0 }
        return (argb and 0xff000000.toInt()) or (channel(rr) shl 16) or (channel(gg) shl 8) or channel(bb)
    }
    private fun linear(v: Double)=if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)
    private fun srgb(v: Double)=if(v<=.0031308) v*12.92 else 1.055*v.pow(1/2.4)-.055
    /** Independent component weights, shared alpha weight; linear-light premultiplied blend. */
    fun blend(a: Int,b: Int,red: Double,green: Double,blue: Double): Int {
        val aa=(a ushr 24)/255.0;val ba=(b ushr 24)/255.0
        val t=red.coerceIn(0.0,1.0);val alpha=aa*(1-t)+ba*t
        var result=(alpha*255+.5).toInt().coerceIn(0,255) shl 24
        for(channel in 0..2) {
            val weight=(if(channel==0) red else if(channel==1) green else blue).coerceIn(0.0,1.0)
            val shift=16-channel*8
            val value=linear(((a ushr shift) and 255)/255.0)*aa*(1-weight)+linear(((b ushr shift) and 255)/255.0)*ba*weight
            val valueAlpha=aa*(1-weight)+ba*weight
            val byte=if(valueAlpha==0.0) 0 else (srgb((value/valueAlpha).coerceIn(0.0,1.0))*255+.5).toInt().coerceIn(0,255)
            result=result or (byte shl shift)
        }
        return result
    }
}
