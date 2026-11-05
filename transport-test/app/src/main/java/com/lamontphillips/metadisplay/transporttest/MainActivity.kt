package com.lamontphillips.metadisplay.transporttest

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
  private var requestedPermission = false
  private val permissionRequest = registerForActivityResult(
      ActivityResultContracts.RequestPermission(),
  ) { granted ->
    bluetoothGranted = granted
    if (granted) model.initialize()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2457D6))) {
        val state by model.state.collectAsStateWithLifecycle()
        TransportScreen(
            state = state,
            permissionGranted = bluetoothGranted,
            onPermission = { permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            onConnect = { model.connect(this) },
            onStart = model::startTest,
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
    onPermission: () -> Unit,
    onConnect: () -> Unit,
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
      Text("Streaming compatibility checks", style = MaterialTheme.typography.titleMedium)
      Text(
          "These silent video tests compare MP4 formats and gradual delivery from your phone. Camera capture comes after these checks.",
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
          title = "1 · Silent MP4 control",
          description = "Play a normal 20-second MP4 from the phone. This checks the new test pictures before changing their container.",
          button = "Play silent control",
          enabled = state.canStart && !state.busy,
          onClick = { onStart(TestMode.STREAM_CONTROL) },
      )
      SourceCard(
          title = "2 · Fragmented MP4",
          description = "Play the same encoded pictures in fragmented MP4 format, delivered immediately.",
          button = "Play fragmented clip",
          enabled = state.canStart && !state.busy,
          onClick = { onStart(TestMode.FRAGMENTED_FILE) },
      )
      SourceCard(
          title = "3 · Gradual delivery",
          description = "Deliver the fragments gradually over about 20 seconds. The report checks whether playback starts while bytes are still arriving.",
          button = "Play gradual delivery",
          enabled = state.canStart && !state.busy,
          onClick = { onStart(TestMode.PACED_FILE) },
      )
      SourceCard(
          title = "4 · Unknown-length stream",
          description = "Test gradual delivery without announcing a final file length, as a continuous source would require.",
          button = "Play stream check",
          enabled = state.canStart && !state.busy,
          onClick = { onStart(TestMode.PACED_CHUNKED) },
      )
      TextButton(onClick = { showBaseline = !showBaseline }) {
        Text(if (showBaseline) "Hide earlier transport tests" else "Show earlier transport tests")
      }
      if (showBaseline) {
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
