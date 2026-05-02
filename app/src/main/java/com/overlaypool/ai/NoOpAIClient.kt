package com.overlaypool.ai

import android.graphics.Bitmap

object NoOpAIClient : AIClient {
    override fun detectFrame(bitmap: Bitmap): AIResponse {
        return AIResponse(
            detections = emptyList(),
            connected = false,
            error = "AI_ENDPOINT nao configurado"
        )
    }
}
