package com.overlaypool.ai

import com.overlaypool.BuildConfig

object AIClientFactory {
    fun create(): AIClient {
        val endpoint = BuildConfig.AI_ENDPOINT.trim()
        if (endpoint.isEmpty()) {
            return NoOpAIClient
        }

        return HttpAIClient(
            endpoint = endpoint,
            apiKey = BuildConfig.AI_API_KEY.trim(),
            provider = BuildConfig.AI_PROVIDER.trim().ifEmpty { "generic_json" }
        )
    }
}
