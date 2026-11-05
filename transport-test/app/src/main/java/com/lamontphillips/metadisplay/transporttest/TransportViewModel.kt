package com.lamontphillips.metadisplay.transporttest

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.types.VideoCodec
import com.meta.wearable.dat.display.types.VideoPlayerState
import com.meta.wearable.dat.display.types.VideoSource
import com.meta.wearable.dat.display.views.VideoPlayer
import java.net.Inet4Address
import java.net.InetAddress
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Finite transport controls and an explicitly bounded, foreground-only live camera experiment. */
class TransportViewModel(application: Application) : AndroidViewModel(application) {
  private companion object {
    const val REFERENCE_URL =
        "https://github.com/facebook/meta-wearables-dat-android/raw/refs/heads/assets/video_266x150_faststart.mp4"
    const val MAX_EVENTS = 240
    const val TEST_TIMEOUT_MS = 30_000L
    const val STREAM_TEST_TIMEOUT_MS = 55_000L
    const val BOUNDED_TEST_TIMEOUT_MS = 75_000L
    const val SOURCE_INIT_TIMEOUT_MS = 5_000L
    const val STREAM_PACING_MS = 20_000L
    const val STREAM_EOF_HOLD_MS = 2_000L
    const val STOP_TIMEOUT_MS = 2_000L
    const val WORKER_CLEANUP_TIMEOUT_MS = 5_000L
  }

  private val app = application
  private val _state = MutableStateFlow(TransportState())
  val state: StateFlow<TransportState> = _state.asStateFlow()

  // All mutable SDK/session state belongs to Main. Server callbacks post onto Main and
  // compare the run identity, so an old request cannot alter a replacement test.
  private var foreground = false
  private var cleared = false
  private var cleanupBlocked = false
  private var cleanupBlockReason: String? = null
  private var monitoring = false
  private var networkCallbackRegistered = false
  private var registration = RegistrationState.UNAVAILABLE
  private val metadata = linkedMapOf<DeviceIdentifier, Device>()
  private val metadataJobs = mutableMapOf<DeviceIdentifier, Job>()
  private var nextRun = 0
  private var activeRun: TestRun? = null
  private var reportDeviceStatus = "No glasses found"
  private var firstPlaySnapshotReport = "No first SDK PLAYING snapshot captured."
  private var configuredTransportReport = "No test configured."
  private var actualTransportReport = "No stream HTTP response observed."
  private val reportStart = SystemClock.elapsedRealtime()
  private val connectivity = app.getSystemService(ConnectivityManager::class.java)

  private class TestRun(
      val number: Int,
      val mode: TestMode,
      val selectedDevice: DeviceIdentifier,
      val displayRotation: Int,
      val job: Job,
      val scope: CoroutineScope,
  ) {
    var server: ClipHttpServer? = null
    var streamServer: StreamProbeHttpServer? = null
    var liveServer: LiveClipHttpServer? = null
    var mediaBuffer: BoundedMediaBuffer? = null
    var cameraProducer: PhoneCameraProducer? = null
    var fixtureProducer: PaddedFixtureProducer? = null
    @Volatile var temporaryCleanupFailed = false
    var streamAssetBytes = 0L
    var firstPlayCaptured = false
    var transferCaveatRecorded = false
    var session: DeviceSession? = null
    var display: Display? = null
    var player: VideoPlayer? = null
    var attaching = false
    var sending = false
    var displayStarted = false
    var wifiAddress: Inet4Address? = null
  }

  private val monitorErrors = CoroutineExceptionHandler { _, _ ->
    viewModelScope.launch {
      record("SDK monitoring failed; no exception details retained")
      activeRun?.let { finish(it, "Monitoring stopped", "SDK monitoring failed") }
      _state.value = _state.value.copy(sdkReady = false, canStart = false,
          lastError = cleanupBlockReason ?: "SDK monitoring failed. Reopen the app.")
    }
  }

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = postNetworkRefresh()
    override fun onLost(network: Network) = postNetworkRefresh()
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
        postNetworkRefresh()
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
        postNetworkRefresh()
  }

  fun initialize() {
    viewModelScope.launch {
      if (_state.value.sdkReady || cleared) return@launch
      if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
          PackageManager.PERMISSION_GRANTED) {
        _state.value = _state.value.copy(
            lastError = cleanupBlockReason ?: "Allow Nearby devices access first.")
        return@launch
      }
      try {
        Wearables.initialize(app).fold(
            onSuccess = { initialized() },
            onFailure = { error, _ ->
              if (error == WearablesError.ALREADY_INITIALIZED) {
                initialized()
              } else {
                val message = "${error.name}: ${error.getLocalizedDescription(app)}"
                record("SDK initialization failed: $message")
                _state.value = _state.value.copy(lastError = cleanupBlockReason ?: message)
              }
            },
        )
      } catch (_: Exception) {
        record("SDK initialization threw; no exception details retained")
        _state.value = _state.value.copy(
            lastError = cleanupBlockReason ?: "Could not initialize the SDK.")
      }
    }
  }

  private fun initialized() {
    record("SDK 1.0.1 ready")
    _state.value = _state.value.copy(sdkReady = true, lastError = cleanupBlockReason,
        phase = "Connect this test app to your glasses")
    if (!monitoring) {
      monitoring = true
      viewModelScope.launch(monitorErrors) {
        Wearables.registrationState.collect { value ->
          registration = value
          record("Registration: ${value.name}")
          _state.value = _state.value.copy(registration = value.name)
          if (value != RegistrationState.REGISTERED) {
            activeRun?.let { finish(it, "Registration changed", "Registration is no longer ready") }
          }
          refreshReadiness()
        }
      }
      viewModelScope.launch(monitorErrors) {
        Wearables.registrationErrorStream.collect { error ->
          val message = "${error.name}: ${error.getLocalizedDescription(app)}"
          record("Registration error: $message")
          _state.value = _state.value.copy(lastError = cleanupBlockReason ?: message)
        }
      }
      viewModelScope.launch(monitorErrors) {
        Wearables.devices.collect { identifiers ->
          val removed = metadataJobs.keys - identifiers
          removed.forEach { id -> metadataJobs.remove(id)?.cancel(); metadata.remove(id) }
          metadata.keys.retainAll(identifiers)
          for (id in identifiers) {
            if (metadataJobs.containsKey(id)) continue
            val flow = Wearables.devicesMetadata[id] ?: continue
            val observer = launch(start = CoroutineStart.LAZY) {
              flow.collect { device ->
                val old = metadata[id]?.let(::safeMetadata)
                metadata[id] = device
                val summary = safeMetadata(device)
                if (summary != old) record("Device metadata: $summary")
                refreshReadiness()
              }
            }
            metadataJobs[id] = observer
            observer.start()
          }
          record("Available device count: ${identifiers.size}")
          refreshReadiness()
        }
      }
    }
    if (!networkCallbackRegistered) {
      try {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
      } catch (_: Exception) {
        record("Network monitoring unavailable")
      }
    }
    refreshNetwork()
    refreshReadiness()
  }

  fun connect(activity: Activity) {
    viewModelScope.launch {
      if (!_state.value.sdkReady || _state.value.busy || cleared) return@launch
      if (registration == RegistrationState.REGISTERED) {
        record("Registration already complete")
        return@launch
      }
      try {
        record("Registration requested")
        _state.value = _state.value.copy(lastError = cleanupBlockReason)
        Wearables.startRegistration(activity)
      } catch (_: Exception) {
        record("Registration request threw; no exception details retained")
        _state.value = _state.value.copy(
            lastError = cleanupBlockReason ?: "Could not open Meta registration.")
      }
    }
  }

  fun startTest(mode: TestMode, displayRotation: Int = 0) {
    viewModelScope.launch {
      refreshNetwork()
      refreshReadiness()
      if (!_state.value.canStart || cleared) return@launch
      if (mode == TestMode.LIVE_CAMERA && app.checkSelfPermission(Manifest.permission.CAMERA) !=
          PackageManager.PERMISSION_GRANTED) {
        record("Camera permission is required for the live test")
        _state.value = _state.value.copy(lastError = "Allow Camera access before the live test.")
        return@launch
      }
      if (mode == TestMode.WIFI && activePrivateAddress() == null) {
        _state.value = _state.value.copy(lastError = "Connect the phone to Wi-Fi first.")
        return@launch
      }
      val selected = metadata.entries.firstOrNull { ready(it.value) }?.key ?: return@launch
      val job = SupervisorJob(viewModelScope.coroutineContext[Job])
      lateinit var run: TestRun
      val failures = CoroutineExceptionHandler { _, _ ->
        viewModelScope.launch {
          if (isCurrent(run)) {
            record("Test operation threw; no exception details retained")
            finish(run, "Test stopped", "A test operation failed")
          }
        }
      }
      run = TestRun(++nextRun, mode, selected, displayRotation, job,
          CoroutineScope(Dispatchers.Main.immediate + job + failures))
      activeRun = run
      _state.value = _state.value.copy(busy = true, canStart = false,
          currentMode = mode.name, phase = if (isBoundedMode(mode)) "Preparing bounded source"
              else "Preparing finite clip", playerState = "IDLE",
          lastError = null, httpRequests = 0, httpBytes = 0,
          streamEvidence = if (isStreamMode(mode) || isBoundedMode(mode))
              "Awaiting first SDK PLAYING snapshot." else "File control; no stream transfer snapshot.",
          cameraEvidence = if (mode == TestMode.LIVE_CAMERA) "Camera starting" else "Camera inactive",
          boundedEvidence = if (isBoundedMode(mode))
              "Finite experiment: ${LiveVideoConfig.FRAGMENTS} slots, ${LiveVideoConfig.SLOT_BYTES} bytes each; awaiting initialization."
              else "")
      firstPlaySnapshotReport = "No first SDK PLAYING snapshot captured."
      configuredTransportReport = configuredTransport(mode)
      actualTransportReport = "No stream HTTP response observed."
      record("Test ${run.number}: ${mode.name}; " + if (mode == TestMode.LIVE_CAMERA)
          "bounded live camera, ${LiveVideoConfig.FRAGMENTS} fragments; foreground only"
          else "finite MP4 fixture only")
      record("Configured response: $configuredTransportReport")
      val timeoutMs = when {
        isBoundedMode(mode) -> BOUNDED_TEST_TIMEOUT_MS
        isExtendedMode(mode) -> STREAM_TEST_TIMEOUT_MS
        else -> TEST_TIMEOUT_MS
      }
      run.scope.launch {
        delay(timeoutMs)
        if (isCurrent(run)) finish(run, "Test reached ${timeoutMs / 1000}-second limit",
            "Test timed out")
      }
      run.scope.launch {
        val url = prepareSource(run) ?: return@launch
        if (isCurrent(run)) createSession(run, url)
      }
    }
  }

  private suspend fun prepareSource(run: TestRun): String? {
    if (isBoundedMode(run.mode)) return prepareBoundedSource(run)
    if (isStreamMode(run.mode)) return prepareStreamSource(run)
    if (run.mode == TestMode.REFERENCE) {
      record("Source: original public reference clip")
      return REFERENCE_URL
    }
    val loopbackMode = run.mode != TestMode.WIFI
    val host = if (loopbackMode) {
      InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    } else {
      activePrivateAddress() ?: kotlin.run {
        finish(run, "Wi-Fi unavailable", "No active private Wi-Fi IPv4 address")
        return null
      }
    }
    if (run.mode == TestMode.WIFI) run.wifiAddress = host as Inet4Address
    val bindAddress = if (loopbackMode) host else
        InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
    // Keep temporary ownership across withContext's cancellation boundary. A server
    // created just as Stop is pressed is still closed by finally, never orphaned.
    var temporary: ClipHttpServer? = null
    try {
      withContext(Dispatchers.IO) {
        val asset = if (run.mode == TestMode.STREAM_CONTROL) "stream-control.mp4" else "sample.mp4"
        val clip = app.assets.open(asset).use { it.readBytes() }
        ensureActive()
        val server = ClipHttpServer(clip, bindAddress) { event ->
          viewModelScope.launch {
            if (isCurrent(run)) {
              recordHttpEvent(run, event)
            }
          }
        }
        temporary = server
        server.start()
      }
      if (!isCurrent(run)) return null
      val server = temporary ?: return null
      run.server = server
      temporary = null
      updateHttpCounts(run)
      record("Phone server started: ${run.mode.name}; address and token omitted")
      return "http://${host.hostAddress}:${server.port}${server.path}"
    } finally {
      temporary?.let { server ->
        withContext(NonCancellable + Dispatchers.IO) { server.close() }
      }
    }
  }

  private fun isStreamMode(mode: TestMode): Boolean = when (mode) {
    TestMode.FRAGMENTED_FILE, TestMode.PACED_FILE, TestMode.PACED_CHUNKED,
    TestMode.CHUNKED_FILE, TestMode.CLOSE_FILE, TestMode.PACED_CLOSE,
    TestMode.PACED_CLOSE_HTTP10, TestMode.KNOWN_LENGTH_NO_RANGES -> true
    else -> false
  }

  private fun isExtendedMode(mode: TestMode): Boolean =
      mode == TestMode.STREAM_CONTROL || isStreamMode(mode)

  private fun isBoundedMode(mode: TestMode): Boolean =
      mode == TestMode.PADDED_CONTROL || mode == TestMode.LIVE_CAMERA

  private fun configuredTransport(mode: TestMode): String = when (mode) {
    TestMode.REFERENCE -> "Public reference URL; remote protocol/framing is not measured"
    TestMode.CHUNKED_FILE, TestMode.PACED_CHUNKED ->
        "HTTP/1.1 CHUNKED; HTTP/1.0 requests fall back to HTTP/1.0 CONNECTION_CLOSE"
    TestMode.CLOSE_FILE, TestMode.PACED_CLOSE ->
        "HTTP/1.1 CONNECTION_CLOSE; no Content-Length or Transfer-Encoding"
    TestMode.PACED_CLOSE_HTTP10 ->
        "HTTP/1.0 CONNECTION_CLOSE; no Content-Length or Transfer-Encoding"
    TestMode.KNOWN_LENGTH_NO_RANGES ->
        "HTTP/1.1 CONTENT_LENGTH; finite exact length with ranges disabled"
    TestMode.PADDED_CONTROL, TestMode.LIVE_CAMERA ->
        "CONTENT_LENGTH; exact initialization plus ${LiveVideoConfig.FRAGMENTS} fixed ${LiveVideoConfig.SLOT_BYTES}-byte MP4 fragment slots; ranges supported; loopback only"
    else -> "HTTP/1.1 CONTENT_LENGTH; finite body ends at its declared length"
  }

  private fun safeProtocol(protocol: String): String =
      if (protocol in setOf("HTTP/1.0", "HTTP/1.1")) protocol else "UNKNOWN"

  private fun safeFraming(framing: String): String =
      if (framing in setOf("CONTENT_LENGTH", "CHUNKED", "CONNECTION_CLOSE")) framing else "UNKNOWN"

  private suspend fun prepareStreamSource(run: TestRun): String? {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val delivery = when (run.mode) {
      TestMode.FRAGMENTED_FILE -> StreamProbeHttpServer.Delivery.FILE
      TestMode.PACED_FILE -> StreamProbeHttpServer.Delivery.PACED_FILE
      TestMode.PACED_CHUNKED -> StreamProbeHttpServer.Delivery.PACED_CHUNKED
      TestMode.CHUNKED_FILE -> StreamProbeHttpServer.Delivery.CHUNKED_FILE
      TestMode.CLOSE_FILE -> StreamProbeHttpServer.Delivery.CLOSE_FILE
      TestMode.PACED_CLOSE -> StreamProbeHttpServer.Delivery.PACED_CLOSE
      TestMode.PACED_CLOSE_HTTP10 -> StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10
      TestMode.KNOWN_LENGTH_NO_RANGES -> StreamProbeHttpServer.Delivery.PACED_FILE_NO_RANGES
      else -> return null
    }
    val paced = run.mode in setOf(TestMode.PACED_FILE, TestMode.PACED_CHUNKED,
        TestMode.PACED_CLOSE, TestMode.PACED_CLOSE_HTTP10, TestMode.KNOWN_LENGTH_NO_RANGES)
    val pacingMs = if (paced) STREAM_PACING_MS else 0L
    // With a known Content-Length, the client sees end-of-body at the last byte.
    // Unknown-length responses can hold the chunk terminator or socket EOF instead.
    val unknownLength = run.mode in setOf(TestMode.CHUNKED_FILE, TestMode.PACED_CHUNKED,
        TestMode.CLOSE_FILE, TestMode.PACED_CLOSE, TestMode.PACED_CLOSE_HTTP10)
    val eofHoldMs = if (unknownLength) STREAM_EOF_HOLD_MS else 0L
    var temporary: StreamProbeHttpServer? = null
    var assetBytes = 0L
    try {
      withContext(Dispatchers.IO) {
        val clip = app.assets.open("fragmented.mp4").use { it.readBytes() }
        assetBytes = clip.size.toLong()
        ensureActive()
        val server = StreamProbeHttpServer(clip, loopback, delivery, pacingMs, eofHoldMs) { event ->
          viewModelScope.launch {
            if (isCurrent(run)) recordStreamHttpEvent(run, event)
          }
        }
        temporary = server
        server.start()
      }
      if (!isCurrent(run)) return null
      val server = temporary ?: return null
      run.streamServer = server
      run.streamAssetBytes = assetBytes
      temporary = null
      updateHttpCounts(run)
      record("Stream source: finite fragmented MP4; bytes=$assetBytes, " +
          "delivery=${delivery.name}, pacingMs=$pacingMs, eofHoldMs=$eofHoldMs; loopback only")
      record("Phone stream server started; address and token omitted")
      return "http://${loopback.hostAddress}:${server.port}${server.path}"
    } finally {
      temporary?.let { server ->
        withContext(NonCancellable + Dispatchers.IO) { server.close() }
      }
    }
  }

  private suspend fun prepareBoundedSource(run: TestRun): String? {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    var temporaryBuffer: BoundedMediaBuffer? = null
    var temporaryCamera: PhoneCameraProducer? = null
    var temporaryFixture: PaddedFixtureProducer? = null
    var temporaryServer: LiveClipHttpServer? = null
    try {
      // Interrupt the bounded initialization wait when Stop/background cancels this run.
      // Locals retain ownership until Main accepts every resource together.
      runInterruptible(Dispatchers.IO) {
        val buffer = BoundedMediaBuffer(LiveVideoConfig.FRAGMENTS, LiveVideoConfig.SLOT_BYTES)
        temporaryBuffer = buffer
        if (run.mode == TestMode.LIVE_CAMERA) {
          val camera = PhoneCameraProducer(app, buffer, run.displayRotation) { event ->
            viewModelScope.launch {
              if (isCurrent(run)) recordCameraEvent(run, event)
            }
          }
          temporaryCamera = camera
          camera.start()
        } else {
          val clip = app.assets.open("fragmented.mp4").use { it.readBytes() }
          val fixture = PaddedFixtureProducer(clip, buffer, LiveVideoConfig.FRAGMENTS,
              LiveVideoConfig.SLOT_BYTES, LiveVideoConfig.FIXTURE_CADENCE_MS) { status ->
            viewModelScope.launch {
              if (isCurrent(run)) recordFixtureStatus(run, status)
            }
          }
          temporaryFixture = fixture
          fixture.start()
        }
        if (!buffer.awaitInitialized(SOURCE_INIT_TIMEOUT_MS)) {
          throw IOException("SOURCE_INIT_TIMEOUT")
        }
        if (!run.job.isActive) throw CancellationException("Run canceled")
        val server = LiveClipHttpServer(buffer, loopback) { event ->
          viewModelScope.launch {
            if (isCurrent(run)) recordLiveHttpEvent(run, event)
          }
        }
        temporaryServer = server
        server.start()
      }
      if (!isCurrent(run)) return null
      val buffer = temporaryBuffer ?: return null
      val server = temporaryServer ?: return null
      run.mediaBuffer = buffer
      run.cameraProducer = temporaryCamera
      run.fixtureProducer = temporaryFixture
      run.liveServer = server
      run.streamAssetBytes = buffer.contentLength
      temporaryBuffer = null
      temporaryCamera = null
      temporaryFixture = null
      temporaryServer = null
      record("Bounded source initialized: slots=${LiveVideoConfig.FRAGMENTS}, " +
          "slotBytes=${LiveVideoConfig.SLOT_BYTES}, exactContentLength=${run.streamAssetBytes}; " +
          "loopback only; no disk recording")
      updateHttpCounts(run)
      refreshBoundedEvidence(run)
      retainCameraSnapshot(run.cameraProducer)
      return "http://${loopback.hostAddress}:${server.port}${server.path}"
    } catch (canceled: CancellationException) {
      throw canceled
    } catch (_: Exception) {
      if (isCurrent(run)) {
        record("Bounded source initialization failed; no exception details retained")
        finish(run, "Source initialization failed", if (run.mode == TestMode.LIVE_CAMERA)
            "The camera source could not be prepared" else "The padded control could not be prepared")
      }
      return null
    } finally {
      if (temporaryBuffer != null || temporaryCamera != null || temporaryFixture != null ||
          temporaryServer != null) {
        val result = closeBoundedResources(temporaryBuffer, temporaryCamera,
            temporaryFixture, temporaryServer)
        if (!result.closed) run.temporaryCleanupFailed = true
        withContext(NonCancellable + Dispatchers.Main.immediate) {
          if (!cleared && nextRun == run.number && (activeRun == null || isCurrent(run))) {
            retainCameraSnapshot(result.camera)
            _state.value = _state.value.copy(boundedEvidence = boundedSummary(
                result.producedBytes, result.plannedBytes, result.fragments, result.complete))
          }
        }
      }
    }
  }

  private data class BoundedCleanupResult(
      val closed: Boolean,
      val producedBytes: Long,
      val plannedBytes: Long,
      val fragments: Int,
      val complete: Boolean,
      val camera: PhoneCameraProducer.Snapshot?,
  )

  private suspend fun closeBoundedResources(
      buffer: BoundedMediaBuffer?,
      camera: PhoneCameraProducer?,
      fixture: PaddedFixtureProducer?,
      server: LiveClipHttpServer?,
  ): BoundedCleanupResult = withContext(NonCancellable + Dispatchers.IO) {
    // Independent producers/server stop concurrently. None of these calls runs on Main.
    val stopCamera = async { closeSource { camera?.close() } }
    val stopFixture = async { closeSource { fixture?.close() } }
    val stopServer = async { closeSource { server?.close() } }
    val cameraClosed = stopCamera.await()
    val fixtureClosed = stopFixture.await()
    val serverClosed = stopServer.await()
    val snapshot = try { camera?.snapshot() } catch (_: Exception) { null }
    val produced = buffer?.producedBytes ?: 0L
    val planned = buffer?.contentLength ?: 0L
    val fragments = buffer?.completedSlots ?: 0
    val complete = buffer?.isComplete ?: false
    val bufferClosed = closeSource { buffer?.close() }
    BoundedCleanupResult(cameraClosed && fixtureClosed && serverClosed && bufferClosed,
        produced, planned, fragments, complete, snapshot)
  }

  private fun closeSource(close: () -> Unit): Boolean = try {
    close()
    true
  } catch (_: Exception) {
    false
  }

  private fun recordFixtureStatus(run: TestRun, status: String) {
    if (status !in setOf("STARTED", "COMPLETE", "FAILED")) return
    record("Padded public fixture producer: $status")
    refreshBoundedEvidence(run)
    if (status == "FAILED") {
      finish(run, "Padded control failed", "FIXTURE_FAILED")
    }
  }

  private fun safeCameraState(value: String): String =
      if (value in setOf("IDLE", "STARTING", "RUNNING", "COMPLETE", "STOPPED", "FAILED"))
          value else "UNKNOWN"

  private fun safeCameraCode(value: String): String {
    val codes = setOf("CAMERA_STARTING", "CAMERA_RUNNING", "CAMERA_PROGRESS",
        "CAMERA_COMPLETE", "CAMERA_STOPPED", "CAMERA_PERMISSION_REQUIRED", "NO_REAR_CAMERA",
        "NO_AVC_ENCODER", "CAMERA_OPEN_FAILED", "CAMERA_CONFIGURE_FAILED", "CAMERA_EGL_FAILED",
        "CAMERA_CODEC_FAILED", "CAMERA_MUXER_FAILED", "CAMERA_INIT_TIMEOUT",
        "CAMERA_FRAME_TIMEOUT", "CAMERA_FRAME_STALLED", "CAMERA_SLOT_TIMEOUT", "CAMERA_INTERRUPTED",
        "CAMERA_CLEANUP_FAILED", "CAMERA_FAILED")
    return if (value in codes) value else "CAMERA_FAILED"
  }

  private fun cameraSummary(state: String, captured: Long, encoded: Long,
      fragments: Int, width: Int, height: Int): String =
      "Camera state=${safeCameraState(state)}; capturedFrames=${captured.coerceAtLeast(0)}, " +
          "encodedFrames=${encoded.coerceAtLeast(0)}, fragments=${fragments.coerceAtLeast(0)}/" +
          "${LiveVideoConfig.FRAGMENTS}, size=${width.coerceIn(0, 65535)}x" +
          "${height.coerceIn(0, 65535)}; foreground-only finite experiment"

  private fun recordCameraEvent(run: TestRun, event: PhoneCameraProducer.Event) {
    val kind = if (event.kind in setOf("STATE", "PROGRESS", "FAILED", "COMPLETE"))
        event.kind else return
    val code = safeCameraCode(event.code)
    val state = when {
      kind == "FAILED" -> "FAILED"
      kind == "COMPLETE" -> "COMPLETE"
      code == "CAMERA_STARTING" -> "STARTING"
      code == "CAMERA_STOPPED" -> "STOPPED"
      else -> "RUNNING"
    }
    val summary = cameraSummary(state, event.capturedFrames, event.encodedFrames,
        event.completedFragments, event.width, event.height)
    _state.value = _state.value.copy(cameraEvidence = summary)
    record("Camera $kind: $code; $summary")
    refreshBoundedEvidence(run)
    if (kind == "FAILED") {
      finish(run, "Camera source failed", code)
    } else if (kind == "COMPLETE") {
      _state.value = _state.value.copy(
          phase = "Finite camera capture complete; waiting for video playback to finish")
    }
  }

  private fun retainCameraSnapshot(producer: PhoneCameraProducer?) {
    if (producer == null) return
    try {
      val snapshot = producer.snapshot()
      retainCameraSnapshot(snapshot)
    } catch (_: Exception) {
      record("Camera counters unavailable; no exception details retained")
    }
  }

  private fun retainCameraSnapshot(snapshot: PhoneCameraProducer.Snapshot?) {
    if (snapshot == null) return
    _state.value = _state.value.copy(cameraEvidence = cameraSummary(snapshot.state,
        snapshot.capturedFrames, snapshot.encodedFrames, snapshot.completedFragments,
        snapshot.width, snapshot.height))
  }

  private fun boundedSummary(produced: Long, planned: Long, fragments: Int,
      complete: Boolean): String =
      "Finite source: produced=${produced.coerceAtLeast(0)}/${planned.coerceAtLeast(0)} bytes, " +
          "slots=${fragments.coerceAtLeast(0)}/${LiveVideoConfig.FRAGMENTS}, " +
          "productionComplete=$complete; no indefinite playback claim"

  private fun refreshBoundedEvidence(run: TestRun) {
    val buffer = run.mediaBuffer ?: return
    _state.value = _state.value.copy(boundedEvidence = boundedSummary(buffer.producedBytes,
        buffer.contentLength, buffer.completedSlots, buffer.isComplete))
  }

  private fun createSession(run: TestRun, url: String) {
    if (!isCurrent(run)) return
    _state.value = _state.value.copy(phase = "Starting glasses session")
    record("Session creation requested")
    Wearables.createSession(SpecificDeviceSelector(run.selectedDevice)).fold(
        onSuccess = { session ->
          run.session = session
          record("Session created")
          run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.errors.collect { error ->
              if (isCurrent(run)) {
                val message = "${error.name}: ${error.getLocalizedDescription(app)}"
                record("Session error: $message")
                finish(run, "Session failed", message)
              }
            }
          }
          run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { value ->
              if (isCurrent(run)) {
                record("Session state: ${value.name}")
                when (value) {
                  DeviceSessionState.STARTED -> attachDisplay(run, url)
                  DeviceSessionState.STOPPED ->
                      finish(run, "Session stopped", "Session stopped before the test completed")
                  else -> Unit
                }
              }
            }
          }
          if (isCurrent(run)) {
            record("Session start requested")
            session.start()
            if (isCurrent(run)) record("Session start returned; awaiting SDK state")
          }
        },
        onFailure = { error, _ ->
          val message = "${error.name}: ${error.getLocalizedDescription(app)}"
          record("Session creation failed: $message")
          finish(run, "Session creation failed", message)
        },
    )
  }

  private fun attachDisplay(run: TestRun, url: String) {
    if (!isCurrent(run) || run.attaching) return
    val session = run.session ?: return
    run.attaching = true
    record("Display attachment requested")
    _state.value = _state.value.copy(phase = "Attaching glasses display")
    session.addDisplay().fold(
        onSuccess = { display ->
          run.display = display
          record("Display attached")
          run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            display.state.collect { value ->
              if (isCurrent(run)) {
                record("Display state: ${value.name}")
                when (value) {
                  DisplayState.STARTED -> {
                    run.displayStarted = true
                    sendVideo(run, url)
                  }
                  DisplayState.CLOSED -> finish(run, "Display closed", "Display closed during test")
                  DisplayState.STOPPED -> if (run.displayStarted) {
                    finish(run, "Display stopped", "Display stopped during test")
                  }
                  else -> Unit
                }
              }
            }
          }
        },
        onFailure = { error, _ ->
          val message = "${error.name}: ${error.getLocalizedDescription(app)}"
          record("Display attachment failed: $message")
          finish(run, "Display attachment failed", message)
        },
    )
  }

  private fun sendVideo(run: TestRun, url: String) {
    if (!isCurrent(run) || run.sending) return
    val display = run.display ?: return
    run.sending = true
    val player = VideoPlayer(source = VideoSource.Url(url), codec = VideoCodec.MP4)
    run.player = player
    run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
      player.state.collect { value ->
        if (isCurrent(run)) {
          // Read the server's atomic live snapshot before logging or yielding. Queued
          // HTTP progress events are deliberately not used for this timing evidence.
          if (value == VideoPlayerState.PLAYING && !run.firstPlayCaptured) {
            captureFirstPlaying(run)
          }
          record("Video state: ${value.name}")
          _state.value = _state.value.copy(playerState = value.name)
          when (value) {
            VideoPlayerState.PLAYING -> _state.value = _state.value.copy(
                phase = "SDK reports PLAYING. Confirm video in glasses.")
            VideoPlayerState.ENDED -> finish(run,
                "SDK reports ENDED. Confirm what you saw in glasses.", null)
            else -> Unit
          }
        }
      }
    }
    run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
      player.error.collect { error ->
        if (isCurrent(run)) {
          if (error == null) {
            record("Video error: none")
          } else {
            val message = "${error.name}: ${error.getLocalizedDescription(app)}"
            record("Video error: $message")
            finish(run, "Video failed", message)
          }
        }
      }
    }
    if (!isCurrent(run)) return
    _state.value = _state.value.copy(phase = "Sending finite video view")
    run.scope.launch {
      record("Video view send requested")
      val result = withContext(Dispatchers.IO) {
        display.sendContent { video(player = player) }
      }
      if (!isCurrent(run)) return@launch
      result.fold(
          onSuccess = {
            record("Video view send succeeded")
            _state.value = _state.value.copy(phase = "Awaiting SDK playback state")
            record("Video play requested")
            player.play()
            if (isCurrent(run)) record("Video play returned; awaiting SDK state")
          },
          onFailure = { error, _ ->
            val message = "${error.name}: ${error.getLocalizedDescription(app)}"
            record("Video view send failed: $message")
            finish(run, "Video view send failed", message)
          },
      )
    }
  }

  private data class TransferEvidence(
      val id: Long, val bytesSent: Long, val plannedBytes: Long,
      val payloadComplete: Boolean, val responseComplete: Boolean,
      val writeInProgress: Boolean, val activeGetCount: Int, val totalGetCount: Long,
      val status: Int, val start: Long, val end: Long, val rangeRequested: Boolean,
      val requestProtocol: String, val responseProtocol: String, val framing: String,
      val sourceBytesProduced: Long? = null,
  )

  private fun transferEvidence(
      streamServer: StreamProbeHttpServer?,
      liveServer: LiveClipHttpServer?,
  ): TransferEvidence? {
    if (liveServer != null) {
      val s = liveServer.transferSnapshot
      return TransferEvidence(s.id, s.bytesSent, s.plannedBytes, s.payloadComplete,
          s.responseComplete, s.writeInProgress, s.activeGetCount, s.totalGetCount, s.status,
          s.start, s.end, s.rangeRequested, s.requestProtocol, s.responseProtocol, s.framing,
          s.sourceBytesProduced)
    }
    val s = streamServer?.transferSnapshot ?: return null
    return TransferEvidence(s.id, s.bytesSent, s.plannedBytes, s.payloadComplete,
        s.responseComplete, s.writeInProgress, s.activeGetCount, s.totalGetCount, s.status,
        s.start, s.end, s.rangeRequested, s.requestProtocol, s.responseProtocol, s.framing)
  }

  private fun retainActualTransport(
      streamServer: StreamProbeHttpServer?,
      liveServer: LiveClipHttpServer?,
  ) {
    val s = transferEvidence(streamServer, liveServer) ?: return
    if (s.id > 0) {
      actualTransportReport = "requestProtocol=${safeProtocol(s.requestProtocol)}, " +
          "responseProtocol=${safeProtocol(s.responseProtocol)}, framing=${safeFraming(s.framing)}, " +
          "rangeRequested=${s.rangeRequested}"
    }
  }

  private fun captureFirstPlaying(run: TestRun) {
    run.firstPlayCaptured = true
    val snapshot = transferEvidence(run.streamServer, run.liveServer)
    if (snapshot == null) {
      firstPlaySnapshotReport = "First SDK PLAYING observed in ${run.mode.name}; " +
          "this file control has no stream transfer snapshot."
      _state.value = _state.value.copy(streamEvidence =
          "SDK reported PLAYING; transfer timing was not measured in this file control.")
      record(firstPlaySnapshotReport)
      return
    }
    val requestProtocol = safeProtocol(snapshot.requestProtocol)
    val responseProtocol = safeProtocol(snapshot.responseProtocol)
    val framing = safeFraming(snapshot.framing)
    if (snapshot.id > 0) {
      actualTransportReport = "requestProtocol=$requestProtocol, responseProtocol=$responseProtocol, " +
          "framing=$framing, rangeRequested=${snapshot.rangeRequested}"
    }
    val ignoredFullRange = snapshot.rangeRequested && snapshot.status == 200 &&
        (framing in setOf("CHUNKED", "CONNECTION_CLOSE") ||
            run.mode == TestMode.KNOWN_LENGTH_NO_RANGES)
    val singleFullGet = snapshot.id > 0 && snapshot.totalGetCount == 1L &&
        snapshot.activeGetCount <= 1 && snapshot.status == 200 &&
        (!snapshot.rangeRequested || ignoredFullRange) &&
        snapshot.start == 0L && snapshot.end == run.streamAssetBytes - 1L &&
        snapshot.plannedBytes == run.streamAssetBytes
    val summary = when {
      snapshot.id == 0L ->
          "No GET snapshot existed at first SDK PLAYING; transfer timing is inconclusive."
      !singleFullGet ->
          "Multiple GETs or a partial response make first-PLAYING timing inconclusive."
      !snapshot.payloadComplete && !snapshot.responseComplete &&
          snapshot.activeGetCount == 1 && !snapshot.writeInProgress &&
          snapshot.bytesSent < snapshot.plannedBytes ->
          if (isBoundedMode(run.mode)) {
            "At first SDK PLAYING, the single full GET still had unsent response bytes " +
                "(${snapshot.bytesSent}/${snapshot.plannedBytes} written). The response includes " +
                "padding; confirm live motion and latency in the glasses separately."
          } else {
            "At first SDK PLAYING, the single full-file GET still had unsent media bytes " +
                "(${snapshot.bytesSent}/${snapshot.plannedBytes} written). Confirm video in glasses."
          }
      snapshot.payloadComplete && !snapshot.responseComplete ->
          "All media bytes were already written at first SDK PLAYING; only the HTTP response " +
              "remained open. This does not establish playback during media transfer."
      snapshot.payloadComplete && snapshot.responseComplete ->
          "The media payload and HTTP response were complete at first SDK PLAYING. " +
              "This does not establish playback during transfer."
      else -> "The first-PLAYING transfer snapshot is inconclusive. Confirm video in glasses."
    }
    val exact = "First SDK PLAYING snapshot: transfer=${snapshot.id}, " +
        "requestProtocol=$requestProtocol, responseProtocol=$responseProtocol, framing=$framing, " +
        "bytesSent=${snapshot.bytesSent}, plannedBytes=${snapshot.plannedBytes}, " +
        "payloadComplete=${snapshot.payloadComplete}, responseComplete=${snapshot.responseComplete}, " +
        "writeInProgress=${snapshot.writeInProgress}, " +
        "activeGetCount=${snapshot.activeGetCount}, totalGetCount=${snapshot.totalGetCount}, " +
        "status=${snapshot.status}, range=${snapshot.start}-${snapshot.end}, " +
        "rangeRequested=${snapshot.rangeRequested}, fullAssetBytes=${run.streamAssetBytes}, " +
        "singleFullGet=$singleFullGet" + if (snapshot.sourceBytesProduced != null)
            ", sourceBytesProduced=${snapshot.sourceBytesProduced}" else ""
    firstPlaySnapshotReport = "$exact\n$summary\n" +
        "Bytes measure server socket writes, not client receipt, decoding or visible display. " +
        "SDK PLAYING alone does not confirm video in glasses."
    val rangeCaveat = if (ignoredFullRange) {
      " Range was ignored for the full 200 response; seeking/range support is not established."
    } else ""
    firstPlaySnapshotReport += rangeCaveat
    if (isBoundedMode(run.mode)) {
      val initializationBytes = run.streamAssetBytes -
          LiveVideoConfig.FRAGMENTS.toLong() * LiveVideoConfig.SLOT_BYTES
      val producedSlots = (((snapshot.sourceBytesProduced ?: 0L) - initializationBytes)
          .coerceAtLeast(0L) / LiveVideoConfig.SLOT_BYTES).coerceAtMost(LiveVideoConfig.FRAGMENTS.toLong())
      val production = "Bounded source at first SDK PLAYING: " +
          "produced=${snapshot.sourceBytesProduced ?: 0L}/${run.streamAssetBytes}, " +
          "completeSlotsProduced=$producedSlots/${LiveVideoConfig.FRAGMENTS}, " +
          "fixedSlots=${LiveVideoConfig.FRAGMENTS}, slotBytes=${LiveVideoConfig.SLOT_BYTES}. " +
          "This does not measure visible playback, live latency, or indefinite streaming."
      firstPlaySnapshotReport += "\n$production"
      record(production)
      retainCameraSnapshot(run.cameraProducer)
      refreshBoundedEvidence(run)
    }
    _state.value = _state.value.copy(streamEvidence = summary + rangeCaveat)
    record(exact)
    record(summary + rangeCaveat)
  }

  private fun retainTransferCaveat(
      run: TestRun,
      server: StreamProbeHttpServer? = run.streamServer,
      liveServer: LiveClipHttpServer? = run.liveServer,
  ) {
    if (!run.firstPlayCaptured || run.transferCaveatRecorded) return
    val snapshot = transferEvidence(server, liveServer) ?: return
    if (snapshot.totalGetCount > 1 || snapshot.status == 206 || snapshot.start > 0) {
      run.transferCaveatRecorded = true
      val caveat = "Multiple GETs or a partial response occurred in this test; first-PLAYING timing " +
          "is inconclusive. Confirm visible playback separately."
      firstPlaySnapshotReport += "\nLater transfer caveat: $caveat " +
          "Observed totalGetCount=${snapshot.totalGetCount}, rangeRequested=${snapshot.rangeRequested}, " +
          "range=${snapshot.start}-${snapshot.end}."
      _state.value = _state.value.copy(streamEvidence = caveat)
      record(caveat)
    }
  }

  fun stopTest() {
    viewModelScope.launch {
      activeRun?.let { finish(it, "Stopped by you", null) }
    }
  }

  fun onForegroundChanged(value: Boolean) {
    viewModelScope.launch {
      foreground = value
      if (!value) activeRun?.let { finish(it, "Stopped when app left foreground", null) }
      refreshNetwork()
      refreshReadiness()
    }
  }

  private fun finish(run: TestRun, phase: String, error: String?) {
    if (!isCurrent(run)) return
    // Capture live counters directly even if queued HTTP/producer callbacks have not run.
    retainActualTransport(run.streamServer, run.liveServer)
    refreshBoundedEvidence(run)
    retainCameraSnapshot(run.cameraProducer)
    retainTransferCaveat(run)
    if ((isStreamMode(run.mode) || isBoundedMode(run.mode)) && !run.firstPlayCaptured) {
      val missing = "SDK never reported PLAYING during this test; no first-PLAYING snapshot."
      firstPlaySnapshotReport = missing
      _state.value = _state.value.copy(streamEvidence = missing)
    }
    activeRun = null
    // Cancel every observer/operation before SDK cleanup, which can emit callbacks.
    // There are no locks held during any external call.
    run.job.cancel()
    record("Test ${run.number} finished: $phase")
    cleanupCall("Video player close") { run.player?.close() }
    cleanupCall("Display removal") { if (run.display != null) run.session?.removeDisplay() }
    cleanupCall("Session stop") { run.session?.stop() }
    updateHttpCounts(run)
    val server = run.server
    val streamServer = run.streamServer
    val liveServer = run.liveServer
    val mediaBuffer = run.mediaBuffer
    val cameraProducer = run.cameraProducer
    val fixtureProducer = run.fixtureProducer
    val session = run.session
    run.player = null
    run.display = null
    run.session = null
    run.server = null
    run.streamServer = null
    run.liveServer = null
    run.mediaBuffer = null
    run.cameraProducer = null
    run.fixtureProducer = null
    _state.value = _state.value.copy(busy = true, canStart = false,
        phase = "Closing test resources", lastError = error)
    refreshReadiness()
    // The cleanup gate prevents a new mode from racing an asynchronously stopping
    // session. This independent, bounded job survives onCleared cancellation.
    viewModelScope.launch(NonCancellable + Dispatchers.Main.immediate) {
      val serverClose = async(Dispatchers.IO) {
        var closed = true
        try {
          server?.close()
        } catch (_: Exception) {
          closed = false
        }
        try {
          streamServer?.close()
        } catch (_: Exception) {
          closed = false
        }
        closed
      }
      val boundedClose = async(Dispatchers.IO) {
        closeBoundedResources(mediaBuffer, cameraProducer, fixtureProducer, liveServer)
      }
      val observersStopped = async {
        withTimeoutOrNull(WORKER_CLEANUP_TIMEOUT_MS) {
          // Includes a temporary server still owned by prepareSource's finally block.
          run.job.join()
          true
        } == true
      }
      val stopped = if (session == null) true else {
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
          session.state.first { it == DeviceSessionState.STOPPED }
          true
        } == true
      }
      val ordinaryServersClosed = serverClose.await()
      val boundedResult = boundedClose.await()
      val workersStopped = observersStopped.await()
      val serverClosed = ordinaryServersClosed && boundedResult.closed && !run.temporaryCleanupFailed
      if (!cleared && activeRun == null && nextRun == run.number) {
        updateHttpCounts(server, streamServer, liveServer)
        retainActualTransport(streamServer, liveServer)
        retainTransferCaveat(run, streamServer, liveServer)
        retainCameraSnapshot(boundedResult.camera)
        if (isBoundedMode(run.mode) && mediaBuffer != null) {
          _state.value = _state.value.copy(boundedEvidence = boundedSummary(
              boundedResult.producedBytes, boundedResult.plannedBytes,
              boundedResult.fragments, boundedResult.complete))
        }
        if (!stopped) record("Cleanup: session STOPPED not observed within 2 seconds")
        if (!serverClosed) record("Source/server cleanup unconfirmed; no exception details retained")
        if (!workersStopped) record("Cleanup: canceled operations did not finish within 5 seconds")
        if (!stopped || !serverClosed || !workersStopped) {
          cleanupBlocked = true
          val reasons = buildList {
            if (!stopped) add("session STOPPED was not confirmed")
            if (!serverClosed) add("source or phone server cleanup was not confirmed")
            if (!workersStopped) add("canceled operations did not finish")
          }.joinToString("; ")
          cleanupBlockReason = listOfNotNull(error, "Cleanup unconfirmed: $reasons.",
              "Force-stop and reopen this app before another test.").joinToString("\n")
          record("Cleanup incomplete; further tests blocked until app restart")
          _state.value = _state.value.copy(busy = false, canStart = false,
              phase = "Force-stop and reopen this app before another test.",
              lastError = cleanupBlockReason)
        } else {
          record("Cleanup complete")
          _state.value = _state.value.copy(busy = false, phase = phase, lastError = error)
        }
        refreshReadiness()
      }
    }
  }

  private fun cleanupCall(label: String, block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {
      record("$label threw; no exception details retained")
    }
  }

  private fun isCurrent(run: TestRun): Boolean = !cleared && activeRun === run

  private fun ready(device: Device): Boolean = device.isDisplayCapable() &&
      device.linkState == LinkState.CONNECTED &&
      device.compatibility == DeviceCompatibility.COMPATIBLE && device.donState == DonState.DONNED

  private fun safeMetadata(device: Device): String =
      "type=${device.deviceType.name}, compatibility=${device.compatibility.name}, " +
          "link=${device.linkState.name}, displayCapable=${device.isDisplayCapable()}, " +
          "worn=${device.donState.name}, battery=${device.batteryLevel}, " +
          "hinge=${device.hingeState.name}, thermal=${device.thermalLevel.name}"

  private fun refreshReadiness() {
    val eligible = metadata.entries.firstOrNull { ready(it.value) }
    val representative = eligible?.value ?: metadata.values.firstOrNull { it.isDisplayCapable() }
        ?: metadata.values.firstOrNull()
    reportDeviceStatus = representative?.let(::safeMetadata) ?: "No glasses found"
    val initialPhase = when {
      cleanupBlocked -> "Force-stop and reopen this app before another test."
      nextRun != 0 || _state.value.busy || !_state.value.sdkReady -> _state.value.phase
      !foreground -> "Return to this app to test"
      registration != RegistrationState.REGISTERED -> "Connect this test app to your glasses"
      representative == null -> "Waiting for linked glasses"
      eligible != null -> "Ready. Choose a video source."
      representative.linkState != LinkState.CONNECTED -> "Connect your glasses in Meta AI"
      representative.donState != DonState.DONNED -> "Put on your display glasses to test"
      else -> "Waiting for compatible display glasses"
    }
    _state.value = _state.value.copy(
        deviceStatus = representative?.let(::friendlyMetadata) ?: "No glasses found",
        phase = initialPhase,
        lastError = cleanupBlockReason ?: _state.value.lastError,
        canStart = _state.value.sdkReady && foreground &&
            registration == RegistrationState.REGISTERED && eligible != null &&
            !_state.value.busy && !cleanupBlocked,
    )
    activeRun?.let { run ->
      if (metadata[run.selectedDevice]?.let(::ready) != true) {
        finish(run, "Glasses no longer ready", "Glasses must stay connected and worn")
      }
    }
  }

  private fun friendlyMetadata(device: Device): String {
    val link = if (device.linkState == LinkState.CONNECTED) "Connected" else
        device.linkState.name.lowercase().replaceFirstChar { it.uppercase() }
    val worn = when (device.donState) {
      DonState.DONNED -> "Worn"
      DonState.DOFFED -> "Not worn"
      else -> "Wear detection pending"
    }
    val compatibility = when (device.compatibility) {
      DeviceCompatibility.COMPATIBLE -> "Compatible"
      DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> "Glasses update required"
      DeviceCompatibility.SDK_UPDATE_REQUIRED -> "App SDK update required"
      else -> "Compatibility pending"
    }
    val display = if (device.isDisplayCapable()) "" else " · Display unavailable"
    return "$link · $worn · $compatibility$display"
  }

  private fun activePrivateAddress(): Inet4Address? {
    return try {
      val network = connectivity.activeNetwork ?: return null
      val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
      if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
          !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
      connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
          ?.filterIsInstance<Inet4Address>()?.firstOrNull {
            it.isSiteLocalAddress && !it.isLoopbackAddress
          }
    } catch (_: Exception) {
      null
    }
  }

  private fun postNetworkRefresh() {
    viewModelScope.launch { if (!cleared) refreshNetwork() }
  }

  private fun refreshNetwork() {
    val address = activePrivateAddress()
    val available = address != null
    if (_state.value.wifiAvailable != available) {
      record("Private Wi-Fi/Ethernet IPv4 available: $available")
    }
    _state.value = _state.value.copy(wifiAvailable = available)
    if (!available) activeRun?.takeIf { it.mode == TestMode.WIFI }?.let {
      finish(it, "Wi-Fi unavailable", "Phone Wi-Fi/Ethernet IPv4 became unavailable")
    }
    activeRun?.takeIf { it.mode == TestMode.WIFI && it.wifiAddress != null &&
        it.wifiAddress != address }?.let {
      finish(it, "Wi-Fi changed", "Phone Wi-Fi/Ethernet IPv4 changed during the test")
    }
  }

  private fun recordHttpEvent(run: TestRun, event: ClipHttpServer.Event) {
    // Only allow fixed method/outcome codes; never retain URL/path/headers or peer addresses.
    val method = if (event.method in setOf("GET", "HEAD")) event.method else "OTHER"
    val outcomes = setOf("accept_failed", "busy", "client_disconnected", "connection_failed",
        "headers_too_large", "malformed_request", "method_not_allowed", "not_found",
        "range_not_satisfiable", "read_timeout", "request_line_too_long", "served",
        "server_error", "stopped", "write_failed")
    val outcome = if (event.outcome in outcomes) event.outcome else "other"
    record("HTTP $method: status=${event.status}, bytes=${event.bytesSent}, " +
        "range=${event.start}-${event.end}, asset=${event.assetRequest}, outcome=$outcome")
    updateHttpCounts(run)
  }

  private fun recordStreamHttpEvent(run: TestRun, event: StreamProbeHttpServer.Event) {
    val method = if (event.method in setOf("GET", "HEAD")) event.method else "OTHER"
    val outcomes = setOf("accept_failed", "busy", "client_disconnected", "connection_failed",
        "headers_too_large", "malformed_request", "method_not_allowed", "not_found",
        "range_not_satisfiable", "read_timeout", "request_line_too_long", "served",
        "server_error", "stopped", "write_failed", "started", "progress", "range_ignored",
        "served_range_ignored", "http10_fallback", "served_http10_fallback")
    val outcome = if (event.outcome in outcomes) event.outcome else "other"
    val requestProtocol = safeProtocol(event.requestProtocol)
    val responseProtocol = safeProtocol(event.responseProtocol)
    val framing = safeFraming(event.framing)
    val transport = "requestProtocol=$requestProtocol, responseProtocol=$responseProtocol, " +
        "framing=$framing, rangeRequested=${event.rangeRequested}"
    if (event.assetRequest) actualTransportReport = transport
    record("Stream HTTP $method ${event.phase.name}: $transport, transfer=${event.transferId}, " +
        "status=${event.status}, bytes=${event.bytesSent}, range=${event.start}-${event.end}, " +
        "asset=${event.assetRequest}, outcome=$outcome")
    updateHttpCounts(run)
    retainTransferCaveat(run)
  }

  private fun recordLiveHttpEvent(run: TestRun, event: LiveClipHttpServer.Event) {
    val method = if (event.method in setOf("GET", "HEAD")) event.method else "OTHER"
    val outcomes = setOf("accept_failed", "busy", "client_disconnected", "connection_failed",
        "headers_too_large", "malformed_request", "method_not_allowed", "not_found",
        "range_not_satisfiable", "read_timeout", "request_line_too_long", "served",
        "server_error", "stopped", "write_failed", "started", "progress",
        "source_failed", "source_timeout", "source_unavailable", "initialization_failed")
    val outcome = if (event.outcome in outcomes) event.outcome else "other"
    val transport = "requestProtocol=${safeProtocol(event.requestProtocol)}, " +
        "responseProtocol=${safeProtocol(event.responseProtocol)}, " +
        "framing=${safeFraming(event.framing)}, rangeRequested=${event.rangeRequested}"
    if (event.assetRequest) actualTransportReport = transport
    record("Bounded HTTP $method ${event.phase.name}: $transport, transfer=${event.transferId}, " +
        "status=${event.status}, bytes=${event.bytesSent}, range=${event.start}-${event.end}, " +
        "asset=${event.assetRequest}, outcome=$outcome")
    updateHttpCounts(run)
    refreshBoundedEvidence(run)
    retainTransferCaveat(run)
  }

  private fun updateHttpCounts(run: TestRun) {
    updateHttpCounts(run.server, run.streamServer, run.liveServer)
  }

  private fun updateHttpCounts(server: ClipHttpServer?, streamServer: StreamProbeHttpServer?,
      liveServer: LiveClipHttpServer? = null) {
    if (server == null && streamServer == null && liveServer == null) return
    val requests = (server?.requestCount ?: 0L) + (streamServer?.requestCount ?: 0L) +
        (liveServer?.requestCount ?: 0L)
    val bytes = (server?.bytesServed ?: 0L) + (streamServer?.bytesServed ?: 0L) +
        (liveServer?.bytesServed ?: 0L)
    _state.value = _state.value.copy(
        httpRequests = requests.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), httpBytes = bytes)
  }

  private fun record(message: String) {
    val elapsed = SystemClock.elapsedRealtime() - reportStart
    _state.value = _state.value.copy(events =
        (_state.value.events + "+${elapsed}ms $message").takeLast(MAX_EVENTS))
  }

  fun copyReport(): String {
    val snapshot = _state.value
    return buildString {
      appendLine("MetaDisplay bounded live camera experiment 0.4; SDK 1.0.1")
      appendLine("LIVE_CAMERA uses the phone rear camera only while this app is visible; controls use public fixtures.")
      appendLine("Silent, finite ${LiveVideoConfig.FRAGMENTS}-fragment experiment (roughly 20 seconds), not indefinite streaming.")
      appendLine("Exact response budget: initialization plus ${LiveVideoConfig.FRAGMENTS} valid ${LiveVideoConfig.SLOT_BYTES}-byte fragment slots; no estimated Content-Length.")
      appendLine("Camera media stays in bounded temporary memory; no microphone or disk recording.")
      appendLine("SDK PLAYING/ENDED does not confirm visible playback in glasses.")
      appendLine("Addresses, URL tokens, device names/IDs and credentials are omitted.")
      appendLine("Registration: ${snapshot.registration}")
      appendLine("Device: $reportDeviceStatus")
      appendLine("Mode: ${snapshot.currentMode}; phase: ${snapshot.phase}")
      appendLine("Configured response: $configuredTransportReport")
      appendLine("Observed response: $actualTransportReport")
      appendLine("Player: ${snapshot.playerState}; error: ${snapshot.lastError ?: "none"}")
      appendLine("Cleanup blocked: $cleanupBlocked")
      cleanupBlockReason?.let { appendLine("Cleanup block details: $it") }
      appendLine("Phone HTTP requests: ${snapshot.httpRequests}; bytes: ${snapshot.httpBytes}")
      appendLine("Camera: ${snapshot.cameraEvidence}")
      if (snapshot.boundedEvidence.isNotEmpty()) appendLine(snapshot.boundedEvidence)
      appendLine("Stream evidence: ${snapshot.streamEvidence}")
      appendLine(firstPlaySnapshotReport)
      appendLine("Retained events (maximum $MAX_EVENTS):")
      snapshot.events.forEach { appendLine(it) }
    }
  }

  override fun onCleared() {
    activeRun?.let { finish(it, "ViewModel cleared", null) }
    cleared = true
    metadataJobs.values.forEach { it.cancel() }
    metadataJobs.clear()
    if (networkCallbackRegistered) {
      try {
        connectivity.unregisterNetworkCallback(networkCallback)
      } catch (_: Exception) {
        // No report outlives this ViewModel.
      }
      networkCallbackRegistered = false
    }
    super.onCleared()
  }
}
