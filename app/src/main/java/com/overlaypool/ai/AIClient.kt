package com.overlaypool.ai

import android.graphics.Bitmap
import java.io.Closeable

data class AIResponse(
    val detections: List<com.overlaypool.model.DetectionResult>,
    val connected: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null
)

interface AIClient : Closeable {
    fun detectFrame(bitmap: Bitmap): AIResponse

    override fun close() = Unit
}
