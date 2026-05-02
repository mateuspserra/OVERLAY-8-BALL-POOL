package com.overlaypool.ai

import com.overlaypool.BuildConfig

object AIClientFactory {
    fun create(): AIClient {
        val endpoint = BuildConfig.AI_ENDPOINT.trim()
        val provider = BuildConfig.AI_PROVIDER.trim()
            .ifEmpty { if (endpoint.isEmpty()) "local_heuristic" else "generic_json" }

        return when (provider.lowercase()) {
            "local", "local_heuristic", "offline" -> LocalHeuristicAIClient
            "disabled", "none", "noop" -> NoOpAIClient
            else -> {
                if (endpoint.isEmpty()) {
                    LocalHeuristicAIClient
                } else {
                    HttpAIClient(
                        endpoint = endpoint,
                        apiKey = BuildConfig.AI_API_KEY.trim(),
                        provider = provider
                    )
                }
            }
        }
    }
}
