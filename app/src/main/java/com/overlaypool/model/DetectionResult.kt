package com.overlaypool.model

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val className: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val centerX: Float = x + width / 2f,
    val centerY: Float = y + height / 2f
) {
    val rect: RectF
        get() = RectF(x, y, x + width, y + height)

    val radius: Float
        get() = max(6f, min(width, height) / 2f)

    fun scaled(scaleX: Float, scaleY: Float): DetectionResult {
        return copy(
            x = x * scaleX,
            y = y * scaleY,
            width = width * scaleX,
            height = height * scaleY,
            centerX = centerX * scaleX,
            centerY = centerY * scaleY
        )
    }

    fun normalizedTo(widthPx: Int, heightPx: Int): DetectionResult {
        return copy(
            x = x * widthPx,
            y = y * heightPx,
            width = width * widthPx,
            height = height * heightPx,
            centerX = centerX * widthPx,
            centerY = centerY * heightPx
        )
    }
}
