package com.fluxx.android.render

import com.fluxx.android.model.Transform
import com.fluxx.android.model.TextBounds
import kotlin.math.*

/** Normalized positions, clockwise rotation, aspect-fit in composition pixels, column-major matrices. */
object LayerGeometry {
    /** Text mesh uses baseline-relative pixels; text is never implicitly aspect-fit. */
    fun textMatrix(out: FloatArray, t: Transform, bounds: TextBounds, cw: Int, ch: Int,
        referenceWidth: Int = cw, referenceHeight: Int = ch, anchorX: Float = .5f, anchorY: Float = .5f) {
        val angle = Math.toRadians(t.rotationDegrees.toDouble())
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        out.fill(0f); out[10] = 1f; out[15] = 1f
        out[0] = 2f * c * t.scaleX / cw; out[1] = 2f * s * t.scaleX / ch
        out[4] = -2f * s * t.scaleY / cw; out[5] = 2f * c * t.scaleY / ch
        val x = bounds.left + anchorX * bounds.width; val y = bounds.top + anchorY * bounds.height
        out[12] = t.positionX * referenceWidth / cw - out[0] * x - out[4] * y
        out[13] = t.positionY * referenceHeight / ch - out[1] * x - out[5] * y
    }
    fun compensateTextPosition(current: Transform, old: TextBounds, next: TextBounds,
        anchorX: Float, anchorY: Float, referenceWidth: Int, referenceHeight: Int,
        oldAlignment: com.fluxx.android.model.TextAlignment = com.fluxx.android.model.TextAlignment.LEFT,
        nextAlignment: com.fluxx.android.model.TextAlignment = oldAlignment): Transform {
        val matrix = FloatArray(16)
        textMatrix(matrix, current, old, referenceWidth, referenceHeight)
        val dx = next.left + next.width * anchorX - old.left - old.width * anchorX -
            (next.width * nextAlignment.ordinal / 2f - old.width * oldAlignment.ordinal / 2f)
        val dy = next.top + next.height * anchorY - old.top - old.height * anchorY
        return current.copy(positionX = current.positionX + matrix[0] * dx + matrix[4] * dy,
            positionY = current.positionY + matrix[1] * dx + matrix[5] * dy)
    }
    data class FitScales(val scaleX: Float, val scaleY: Float)

    fun calculateFit(referenceWidth: Int, referenceHeight: Int, sourceWidth: Int, sourceHeight: Int,
        sourceRotation: Int = 0, pixelAspect: Float = 1f): Float {
        require(referenceWidth > 0 && referenceHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
        require(pixelAspect.isFinite() && pixelAspect > 0f)
        val sw = sourceWidth * pixelAspect
        val sh = sourceHeight.toFloat()
        val quarterTurn = ((sourceRotation % 180) + 180) % 180 != 0
        return minOf(referenceWidth / (if (quarterTurn) sh else sw), referenceHeight / (if (quarterTurn) sw else sh))
    }

    fun computeStretch(compWidth: Int, compHeight: Int, referenceWidth: Int, referenceHeight: Int,
        sourceWidth: Int, sourceHeight: Int, sourceRotation: Int = 0, pixelAspect: Float = 1f): FitScales {
        require(compWidth > 0 && compHeight > 0)
        val fit = calculateFit(referenceWidth.takeIf { it > 0 } ?: compWidth,
            referenceHeight.takeIf { it > 0 } ?: compHeight, sourceWidth, sourceHeight, sourceRotation, pixelAspect)
        val quarterTurn = ((sourceRotation % 180) + 180) % 180 != 0
        val width = if (quarterTurn) sourceHeight.toFloat() else sourceWidth * pixelAspect
        val height = if (quarterTurn) sourceWidth * pixelAspect else sourceHeight.toFloat()
        return FitScales(compWidth / (width * fit), compHeight / (height * fit))
    }

    fun computeFitToWidth(compWidth: Int, compHeight: Int, referenceWidth: Int, referenceHeight: Int,
        sourceWidth: Int, sourceHeight: Int, sourceRotation: Int = 0, pixelAspect: Float = 1f): FitScales {
        val scale = computeStretch(compWidth, compHeight, referenceWidth, referenceHeight,
            sourceWidth, sourceHeight, sourceRotation, pixelAspect).scaleX
        return FitScales(scale, scale)
    }

    fun computeFitToHeight(compWidth: Int, compHeight: Int, referenceWidth: Int, referenceHeight: Int,
        sourceWidth: Int, sourceHeight: Int, sourceRotation: Int = 0, pixelAspect: Float = 1f): FitScales {
        val scale = computeStretch(compWidth, compHeight, referenceWidth, referenceHeight,
            sourceWidth, sourceHeight, sourceRotation, pixelAspect).scaleY
        return FitScales(scale, scale)
    }

    fun matrix(out: FloatArray, t: Transform, cw: Int, ch: Int, sourceWidth: Int, sourceHeight: Int,
        sourceRotation: Int = 0, pixelAspect: Float = 1f, yDown: Boolean = true,
        referenceWidth: Int = cw, referenceHeight: Int = ch,
        anchorX: Float = .5f, anchorY: Float = .5f) {
        matrix(out, t.positionX, t.positionY, t.scaleX, t.scaleY, t.rotationDegrees,
            cw, ch, sourceWidth, sourceHeight, sourceRotation, pixelAspect, yDown, referenceWidth, referenceHeight, anchorX, anchorY)
    }

    fun matrix(out: FloatArray, posX: Float, posY: Float, scaleX: Float, scaleY: Float, rotationDegrees: Float,
        cw: Int, ch: Int, sourceWidth: Int, sourceHeight: Int,
        sourceRotation: Int = 0, pixelAspect: Float = 1f, yDown: Boolean = true,
        referenceWidth: Int = cw, referenceHeight: Int = ch,
        anchorX: Float = .5f, anchorY: Float = .5f) {
        val sw = sourceWidth * pixelAspect
        val sh = sourceHeight.toFloat()
        // Fit in the layer's original coordinate space. Only projection uses the current canvas.
        val fit = calculateFit(referenceWidth, referenceHeight, sourceWidth, sourceHeight, sourceRotation, pixelAspect)
        val angle = Math.toRadians(rotationDegrees.toDouble())
        val sourceAngle = Math.toRadians(sourceRotation.toDouble())
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        val sc = cos(sourceAngle).toFloat(); val ss = sin(sourceAngle).toFloat()
        val xx = (c*scaleX*sc - s*scaleY*ss)*sw*fit/cw
        val xy = (-c*scaleX*ss - s*scaleY*sc)*sh*fit/cw
        val yx = (s*scaleX*sc + c*scaleY*ss)*sw*fit/ch
        val yy = (-s*scaleX*ss + c*scaleY*sc)*sh*fit/ch
        out.fill(0f)
        out[0]=xx; out[5]=yy; out[10]=1f; out[15]=1f
        out[4]=if(yDown) xy else -xy
        out[1]=if(yDown) yx else -yx
        out[12]=posX*referenceWidth/cw
        out[13]=(if(yDown) posY else -posY)*referenceHeight/ch
        // Preserve the old centre-anchor path exactly, including signed zero.
        if (anchorX != .5f || anchorY != .5f) {
            val ax = 2f * anchorX - 1f
            val ay = 2f * anchorY - 1f
            out[12] -= out[0] * ax + out[4] * ay
            out[13] -= out[1] * ax + out[5] * ay
        }
    }

    /** UI edit helper: reuse the actual matrix coefficients, not a second geometry formula. */
    fun compensatePosition(out: FloatArray, current: Transform,
        oldAnchorX: Float, oldAnchorY: Float, newAnchorX: Float, newAnchorY: Float,
        sourceWidth: Int, sourceHeight: Int, sourceRotation: Int = 0, pixelAspect: Float = 1f,
        referenceWidth: Int, referenceHeight: Int): Transform {
        matrix(out, current, referenceWidth, referenceHeight, sourceWidth, sourceHeight,
            sourceRotation, pixelAspect, referenceWidth = referenceWidth, referenceHeight = referenceHeight)
        val dx = 2f * (newAnchorX - oldAnchorX)
        val dy = 2f * (newAnchorY - oldAnchorY)
        return current.copy(positionX = current.positionX + out[0] * dx + out[4] * dy,
            positionY = current.positionY + out[1] * dx + out[5] * dy)
    }
}
