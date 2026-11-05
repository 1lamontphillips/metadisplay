package com.lamontphillips.metadisplay.transporttest

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
  private val model: TransportViewModel by viewModels()
  private var bluetoothGranted by mutableStateOf(false)
  private var cameraGranted by mutableStateOf(false)
  private var requestedPermission = false
  private val permissionRequest = registerForActivityResult(
      ActivityResultContracts.RequestPermission(),
  ) { granted ->
    bluetoothGranted = granted
    if (granted) model.initialize()
  }
  private val cameraPermissionRequest = registerForActivityResult(
      ActivityResultContracts.RequestPermission(),
  ) { granted -> cameraGranted = granted }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2457D6))) {
        val state by model.state.collectAsStateWithLifecycle()
        DisposableEffect(state.busy) {
          if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        TransportScreen(
            state = state,
            permissionGranted = bluetoothGranted,
            cameraPermissionGranted = cameraGranted,
            onPermission = { permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            onConnect = { model.connect(this) },
            onCameraPermission = { cameraPermissionRequest.launch(Manifest.permission.CAMERA) },
            onStart = { mode -> model.startTest(mode, display?.rotation ?: Surface.ROTATION_0) },
            onStop = model::stopTest,
            onCopy = {
              val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(ClipData.newPlainText("Display transport report", model.copyReport()))
              Toast.makeText(this, "Test report copied", Toast.LENGTH_SHORT).show()
            },
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    model.onForegroundChanged(true)
    bluetoothGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    if (bluetoothGranted) {
      model.initialize()
    } else if (!requestedPermission) {
      requestedPermission = true
      permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
  }

  override fun onStop() {
    model.onForegroundChanged(false)
    super.onStop()
  }
}

@Composable
private fun TransportScreen(
    state: TransportState,
    permissionGranted: Boolean,
    cameraPermissionGranted: Boolean,
    onPermission: () -> Unit,
    onConnect: () -> Unit,
    onCameraPermission: () -> Unit,
    onStart: (TestMode) -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
) {
  var showBaseline by rememberSaveable { mutableStateOf(false) }
  Scaffold(
      containerColor = Color(0xFFF5F6FA),
      bottomBar = {
        Surface(shadowElevation = 4.dp) {
          Row(
              modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedButton(onClick = onStop, enabled = state.busy, modifier = Modifier.weight(1f)) {
              Text("Stop test")
            }
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy report") }
          }
        }
      },
  ) { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Display Transport Test", style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold)
      Text("Bounded live camera experiment", style = MaterialTheme.typography.titleMedium)
      Text(
          "First check the test footage, then try your phone's rear camera. Each experiment ends after 20 video fragments, about 20 seconds. Keep this app open.",
          style = MaterialTheme.typography.bodyLarge,
      )
      Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Connection", style = MaterialTheme.typography.titleLarge)
          Text("Registration: ${state.registration}")
          Text(state.deviceStatus)
          Text(state.phase, fontWeight = FontWeight.SemiBold)
          if (!permissionGranted) {
            Button(onClick = onPermission) { Text("Allow Nearby devices") }
          } else if (state.registration != "REGISTERED") {
            Button(onClick = onConnect, enabled = state.sdkReady && !state.busy) {
              Text("Connect my glasses")
            }
          }
          Text("Keep the glasses on and this app open during each test.",
              style = MaterialTheme.typography.bodyMedium)
          state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      SourceCard(
          title = "1 · Bounded video control",
          description = "Play silent test footage with the same fixed byte budget planned for the camera. Check that the picture moves smoothly in your glasses.",
          button = "Play bounded control",
          enabled = state.canStart && !state.busy,
          onClick = { onStart(TestMode.PADDED_CONTROL) },
      )
      SourceCard(
          title = "2 · Live phone camera",
          description = if (cameraPermissionGranted) {
            "Point the rear camera at a clock or moving object. Start the test and check your glasses. Silent video; no recording file is saved."
          } else {
            "Allow Camera access, then tap Start live camera test. Capture runs while this app is open and stops when the test ends or you tap Stop."
          },
          button = if (cameraPermissionGranted) "Start live camera test" else "Allow camera for live test",
          enabled = state.canStart && !state.busy,
          onClick = { if (cameraPermissionGranted) onStart(TestMode.LIVE_CAMERA) else onCameraPermission() },
      )
      TextButton(onClick = { showBaseline = !showBaseline }) {
        Text(if (showBaseline) "Hide earlier transport tests" else "Show earlier transport tests")
      }
      if (showBaseline) {
        SourceCard(
            title = "Known length without ranges",
            description = "Compare the successful gradual clip while disabling byte ranges. This checks the remaining difference from the failed unknown-length tests.",
            button = "Play length control",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.KNOWN_LENGTH_NO_RANGES) },
        )
        SourceCard(
            title = "Direct stream",
            description = "Earlier gradual raw media without a declared file size.",
            button = "Play direct stream",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.PACED_CLOSE) },
        )
        SourceCard(
            title = "Compatibility stream",
            description = "Earlier gradual raw media with an HTTP/1.0 response.",
            button = "Play compatibility stream",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.PACED_CLOSE_HTTP10) },
        )
        SourceCard(
            title = "Chunked control",
            description = "Earlier immediate chunked delivery without a declared file size.",
            button = "Play chunked control",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.CHUNKED_FILE) },
        )
        SourceCard(
            title = "Direct control",
            description = "Send the raw media immediately without a declared length, then close the connection.",
            button = "Play direct control",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.CLOSE_FILE) },
        )
        SourceCard(
            title = "Known-length gradual delivery",
            description = "Repeat the successful gradual-delivery check with its known final file length.",
            button = "Play gradual delivery",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.PACED_FILE) },
        )
        SourceCard(
            title = "Gradual chunked delivery",
            description = "Repeat the earlier gradual chunked test. The report records the actual HTTP response framing.",
            button = "Play stream check",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.PACED_CHUNKED) },
        )
        SourceCard(
            title = "Silent MP4 control",
            description = "Play a normal 20-second MP4 from the phone.",
            button = "Play silent control",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.STREAM_CONTROL) },
        )
        SourceCard(
            title = "Fragmented MP4 control",
            description = "Play the same encoded pictures in fragmented MP4 format, delivered immediately.",
            button = "Play fragmented clip",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.FRAGMENTED_FILE) },
        )
        SourceCard(
            title = "1 · Internet reference",
            description = "Play the original clip from its internet URL. This checks the new app's connection and player.",
            button = "Play reference clip",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.REFERENCE) },
        )
        SourceCard(
            title = "2 · Phone local",
            description = "Play the same clip from a temporary endpoint inside this phone. We will record whether a request reaches it.",
            button = "Play phone-local clip",
            enabled = state.canStart && !state.busy,
            onClick = { onStart(TestMode.LOOPBACK) },
        )
        SourceCard(
            title = "3 · Phone Wi-Fi",
            description = if (state.wifiAvailable) {
              "Try the phone's Wi-Fi route if the local test cannot be reached. The temporary endpoint serves only the sample clip."
            } else {
              "Connect the phone to Wi-Fi to make this alternative route available."
            },
            button = "Play phone Wi-Fi clip",
            enabled = state.canStart && !state.busy && state.wifiAvailable,
            onClick = { onStart(TestMode.WIFI) },
        )
      }
      Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Latest test", style = MaterialTheme.typography.titleLarge)
          Text("Source: ${state.currentMode}")
          Text("Player: ${state.playerState}")
          Text("Phone clip requests: ${state.httpRequests} · bytes sent: ${state.httpBytes}")
          Text(state.cameraEvidence)
          if (state.boundedEvidence.isNotEmpty()) Text(state.boundedEvidence)
          Text(state.streamEvidence, fontWeight = FontWeight.SemiBold)
          Text("Confirm playback by looking in the glasses. A player status alone does not confirm what you saw.",
              style = MaterialTheme.typography.bodySmall)
          SelectionContainer {
            Text(state.events.takeLast(16).joinToString("\n").ifEmpty { "No test started yet." },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      Text(
          "The temporary phone endpoint stops when the test ends or this app goes into the background. Copying a report does not upload it automatically.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun SourceCard(title: String, description: String, button: String, enabled: Boolean, onClick: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      Text(description, style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(button) }
    }
  }
}
