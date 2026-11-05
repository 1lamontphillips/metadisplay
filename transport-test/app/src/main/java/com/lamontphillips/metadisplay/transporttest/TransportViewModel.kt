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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Finite-clip transport experiment. It does not capture or transmit camera images. */
class TransportViewModel(application: Application) : AndroidViewModel(application) {
  private companion object {
    const val REFERENCE_URL =
        "https://github.com/facebook/meta-wearables-dat-android/raw/refs/heads/assets/video_266x150_faststart.mp4"
    const val MAX_EVENTS = 120
    const val TEST_TIMEOUT_MS = 30_000L
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
  private val reportStart = SystemClock.elapsedRealtime()
  private val connectivity = app.getSystemService(ConnectivityManager::class.java)

  private class TestRun(
      val number: Int,
      val mode: TestMode,
      val selectedDevice: DeviceIdentifier,
      val job: Job,
      val scope: CoroutineScope,
  ) {
    var server: ClipHttpServer? = null
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

  fun startTest(mode: TestMode) {
    viewModelScope.launch {
      refreshNetwork()
      refreshReadiness()
      if (!_state.value.canStart || cleared) return@launch
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
      run = TestRun(++nextRun, mode, selected, job,
          CoroutineScope(Dispatchers.Main.immediate + job + failures))
      activeRun = run
      _state.value = _state.value.copy(busy = true, canStart = false,
          currentMode = mode.name, phase = "Preparing finite clip", playerState = "IDLE",
          lastError = null, httpRequests = 0, httpBytes = 0)
      record("Test ${run.number}: ${mode.name}; finite MP4 only")
      run.scope.launch {
        delay(TEST_TIMEOUT_MS)
        if (isCurrent(run)) finish(run, "Test reached 30-second limit", "Test timed out")
      }
      run.scope.launch {
        val url = prepareSource(run) ?: return@launch
        if (isCurrent(run)) createSession(run, url)
      }
    }
  }

  private suspend fun prepareSource(run: TestRun): String? {
    if (run.mode == TestMode.REFERENCE) {
      record("Source: original public reference clip")
      return REFERENCE_URL
    }
    val host = if (run.mode == TestMode.LOOPBACK) {
      InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    } else {
      activePrivateAddress() ?: kotlin.run {
        finish(run, "Wi-Fi unavailable", "No active private Wi-Fi IPv4 address")
        return null
      }
    }
    if (run.mode == TestMode.WIFI) run.wifiAddress = host as Inet4Address
    val bindAddress = if (run.mode == TestMode.LOOPBACK) host else
        InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
    // Keep temporary ownership across withContext's cancellation boundary. A server
    // created just as Stop is pressed is still closed by finally, never orphaned.
    var temporary: ClipHttpServer? = null
    try {
      withContext(Dispatchers.IO) {
        val clip = app.assets.open("sample.mp4").use { it.readBytes() }
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
    val session = run.session
    run.player = null
    run.display = null
    run.session = null
    run.server = null
    _state.value = _state.value.copy(busy = true, canStart = false,
        phase = "Closing test resources", lastError = error)
    refreshReadiness()
    // The cleanup gate prevents a new mode from racing an asynchronously stopping
    // session. This independent, bounded job survives onCleared cancellation.
    viewModelScope.launch(NonCancellable + Dispatchers.Main.immediate) {
      val serverClose = async(Dispatchers.IO) {
        try {
          server?.close()
          true
        } catch (_: Exception) {
          false
        }
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
      val serverClosed = serverClose.await()
      val workersStopped = observersStopped.await()
      if (!cleared && activeRun == null && nextRun == run.number) {
        server?.let {
          _state.value = _state.value.copy(
              httpRequests = it.requestCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
              httpBytes = it.bytesServed,
          )
        }
        if (!stopped) record("Cleanup: session STOPPED not observed within 2 seconds")
        if (!serverClosed) record("Phone server close threw; no exception details retained")
        if (!workersStopped) record("Cleanup: canceled operations did not finish within 5 seconds")
        if (!stopped || !serverClosed || !workersStopped) {
          cleanupBlocked = true
          val reasons = buildList {
            if (!stopped) add("session STOPPED was not confirmed")
            if (!serverClosed) add("phone server close failed")
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

  private fun updateHttpCounts(run: TestRun) {
    run.server?.let { server ->
      _state.value = _state.value.copy(
          httpRequests = server.requestCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          httpBytes = server.bytesServed)
    }
  }

  private fun record(message: String) {
    val elapsed = SystemClock.elapsedRealtime() - reportStart
    _state.value = _state.value.copy(events =
        (_state.value.events + "+${elapsed}ms $message").takeLast(MAX_EVENTS))
  }

  fun copyReport(): String {
    val snapshot = _state.value
    return buildString {
      appendLine("MetaDisplay transport probe 0.1; SDK 1.0.1")
      appendLine("Finite public sample MP4 only; no camera capture.")
      appendLine("SDK PLAYING/ENDED does not confirm visible playback in glasses.")
      appendLine("Addresses, URL tokens, device names/IDs and credentials are omitted.")
      appendLine("Registration: ${snapshot.registration}")
      appendLine("Device: $reportDeviceStatus")
      appendLine("Mode: ${snapshot.currentMode}; phase: ${snapshot.phase}")
      appendLine("Player: ${snapshot.playerState}; error: ${snapshot.lastError ?: "none"}")
      appendLine("Cleanup blocked: $cleanupBlocked")
      cleanupBlockReason?.let { appendLine("Cleanup block details: $it") }
      appendLine("Phone HTTP requests: ${snapshot.httpRequests}; bytes: ${snapshot.httpBytes}")
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
