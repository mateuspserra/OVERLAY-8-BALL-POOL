package com.overlaypool.core

data class RuntimeStatus(
    val overlayActive: Boolean = false,
    val captureActive: Boolean = false,
    val aiConnected: Boolean = false,
    val aiBusy: Boolean = false,
    val markingsVisible: Boolean = true,
    val lastDetection: String = "Nenhuma deteccao",
    val systemState: String = "Leitura pausada",
    val lastApiLatencyMs: Long? = null,
    val lastError: String? = null
)
