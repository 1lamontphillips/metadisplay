package com.lamontphillips.metadisplay.transporttest

enum class TestMode {
  REFERENCE,
  LOOPBACK,
  WIFI,
}

data class TransportState(
    val sdkReady: Boolean = false,
    val registration: String = "UNAVAILABLE",
    val deviceStatus: String = "No glasses found",
    val canStart: Boolean = false,
    val busy: Boolean = false,
    val wifiAvailable: Boolean = false,
    val phase: String = "Request Bluetooth access to initialize",
    val currentMode: String = "None",
    val playerState: String = "IDLE",
    val lastError: String? = null,
    val httpRequests: Int = 0,
    val httpBytes: Long = 0,
    val events: List<String> = emptyList(),
)
