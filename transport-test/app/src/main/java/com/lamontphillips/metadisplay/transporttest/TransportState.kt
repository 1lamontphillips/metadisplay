package com.lamontphillips.metadisplay.transporttest

enum class TestMode {
  REFERENCE,
  LOOPBACK,
  WIFI,
  STREAM_CONTROL,
  FRAGMENTED_FILE,
  PACED_FILE,
  PACED_CHUNKED,
  CHUNKED_FILE,
  CLOSE_FILE,
  PACED_CLOSE,
  PACED_CLOSE_HTTP10,
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
    val streamEvidence: String = "No first-PLAYING evidence yet",
    val events: List<String> = emptyList(),
)
