package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

class FluxxColorTest {
    @Test fun packedChannelsRoundTripIncludingTransparentRgb() {
        for (channel in 0..255) for (shift in listOf(0,8,16,24)) {
            val packed=(0xAA3377BB.toInt() and (255 shl shift).inv()) or (channel shl shift)
            assertEquals(packed,FluxxColor.fromArgb(packed).toArgb())
        }
        assertEquals(0x00123456,FluxxColor.fromArgb(0x00123456).toArgb())
    }
    @Test fun hsvRoundTripAndHueWrap() {
        for (h in 0..360 step 15) for (s in listOf(0f,.2f,1f)) for(v in listOf(0f,.4f,1f)) {
            val original=FluxxColor.fromHsv(h.toFloat(),s,v,.3f)
            val hsv=original.toHsv()
            val restored=FluxxColor.fromHsv(hsv.hue,hsv.saturation,hsv.value,original.a)
            assertEquals(original.r,restored.r,1e-5f)
            assertEquals(original.g,restored.g,1e-5f)
            assertEquals(original.b,restored.b,1e-5f)
            assertEquals(.3f,restored.a,0f)
        }
        assertEquals(FluxxColor.fromHsv(300f,1f,1f),FluxxColor.fromHsv(-60f,1f,1f))
    }
    @Test fun hexUsesArgbForEightDigitsAndPreservesAlphaForSix() {
        assertEquals(0x80336699.toInt(),FluxxColor.parseHex("#80336699")!!.toArgb())
        assertEquals(.25f,FluxxColor.parseHex("336699",.25f)!!.a,0f)
        for(text in listOf("", "123", "GG0011", "123456789", "-11223", "+11223")) assertNull(FluxxColor.parseHex(text))
        val color=FluxxColor.fromArgb(0x80336699.toInt())
        assertEquals("80336699",color.toHexString(true))
        assertEquals("336699",color.toHexString(false))
    }
    @Test fun colorGestureIsOneUndoAndAlphaSurvivesPersistenceIndependentlyOfOpacity() {
        val layer=CompositionLayer(1,LayerType.SOLID,timing=ClipTiming(0,0,5_000_000),
            transform=Transform(opacity=.4f))
        val project=ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))
        val vm=EditorViewModel()
        vm.dispatch(EditorAction.Load(project))
        vm.beginGesture()
        vm.previewGesture(EditorAction.SetColor(1,0x40336699))
        vm.previewGesture(EditorAction.SetColor(1,0x80336699.toInt()))
        assertEquals(project,vm.state.project)
        vm.commitGesture()
        val decoded=ProjectCodec.decode(ProjectCodec.encode(vm.state.project)).composition.layers.single()
        assertEquals(0x80336699.toInt(),decoded.solidColorArgb)
        assertEquals(.4f,decoded.transform.opacity,0f)
        vm.undo(); assertEquals(project,vm.state.project); assertFalse(vm.canUndo)
        vm.beginGesture(); vm.previewGesture(EditorAction.SetColor(1,0)); vm.cancelGesture()
        assertEquals(project,vm.state.project)
    }
}
