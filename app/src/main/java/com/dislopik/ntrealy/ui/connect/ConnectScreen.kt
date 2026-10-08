package com.dislopik.ntrealy.ui.connect

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dislopik.ntrealy.network.ConnectionState
import com.dislopik.ntrealy.network.hasLocalNetworkAccess
import com.dislopik.ntrealy.network.localAreaNetwork
import com.dislopik.ntrealy.protocol.NTRCommands
import com.dislopik.ntrealy.protocol.ScreenId
import com.dislopik.ntrealy.render.ColorMode
import com.dislopik.ntrealy.service.StreamSession
import com.dislopik.ntrealy.ui.SettingsStore
import com.dislopik.ntrealy.ui.StreamConfig
import com.dislopik.ntrealy.ui.settings.SettingsScreen

@Composable
fun ConnectScreen(
    onConnect: (StreamConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val initial = remember { store.load() }

    var host by remember { mutableStateOf(initial.host) }
    var portText by remember { mutableStateOf(initial.viewerPort.toString()) }
    var quality by remember { mutableStateOf(initial.quality) }
    var priority by remember { mutableStateOf(initial.priority) }
    var qos by remember { mutableStateOf(initial.qos) }
    var priorityScreen by remember { mutableStateOf(initial.priorityScreen) }
    var colorMode by remember { mutableStateOf(initial.colorMode) }
    var showSettings by remember { mutableStateOf(false) }

    // Without this grant Android 17+ drops all LAN traffic, which looks like an unreachable 3DS
    var pendingNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var localNetworkDenied by remember { mutableStateOf(false) }
    val localNetworkPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        localNetworkDenied = !granted
        if (granted) pendingNetworkAction?.invoke()
        pendingNetworkAction = null
    }

    fun withLocalNetworkAccess(action: () -> Unit) {
        if (context.hasLocalNetworkAccess()) {
            localNetworkDenied = false
            action()
        } else {
            pendingNetworkAction = action
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    if (showSettings) {
        SettingsScreen(
            colorMode = colorMode,
            onColorModeChange = { colorMode = it },
            onBack = { showSettings = false },
        )
        return
    }

    val state by StreamSession.state.collectAsState()
    val debugLog by StreamSession.debugLog.collectAsState()
    val error = if (localNetworkDenied) LOCAL_NETWORK_DENIED else (state as? ConnectionState.Error)?.message
    val ntrTail = if (localNetworkDenied) emptyList() else debugLog.filter { it.startsWith("NTR:") }.takeLast(2)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "NTRelay",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = { showSettings = true }) {
                Text("Settings")
            }
        }
        Text(
            text = "Stream your 3DS screen over NTR remote play",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error != null) {
            Card(colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    ntrTail.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("3DS IP address") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Stream port (default ${NTRCommands.UDP_PORT})") },
            supportingText = { Text("UDP port the 3DS streams to. Classic NTR only supports ${NTRCommands.UDP_PORT}.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsSlider(
            label = "Quality",
            value = quality,
            valueRange = 10f..100f,
            steps = 8,
            display = { "${it.toInt()}" },
            onValueChange = { quality = it.toInt() },
        )
        SettingsSlider(
            label = "Priority factor",
            value = priority,
            valueRange = 1f..9f,
            steps = 7,
            display = { "${it.toInt()}" },
            onValueChange = { priority = it.toInt() },
        )
        SettingsSlider(
            label = "QoS (101 = off)",
            value = qos,
            valueRange = 1f..101f,
            steps = 19,
            display = { "${it.toInt()}" },
            onValueChange = { qos = it.toInt() },
        )

        Text("Priority screen", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = priorityScreen == ScreenId.TOP,
                onClick = { priorityScreen = ScreenId.TOP },
                label = { Text("Top") },
            )
            FilterChip(
                selected = priorityScreen == ScreenId.BOTTOM,
                onClick = { priorityScreen = ScreenId.BOTTOM },
                label = { Text("Bottom") },
            )
        }

        val scope = rememberCoroutineScope()
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }

        fun probe() {
            val h = host.trim()
            val p = NTRCommands.TCP_PORT
            if (h.isBlank() || testing) return
            testing = true
            testResult = null
            scope.launch {
                testResult = withContext(Dispatchers.IO) {
                    val started = System.currentTimeMillis()
                    try {
                        Socket().use { s ->
                            context.getSystemService(android.net.ConnectivityManager::class.java)
                                ?.localAreaNetwork()
                                ?.let { runCatching { it.bindSocket(s) } }
                            s.connect(InetSocketAddress(h, p), 3000)
                        }
                        val ms = System.currentTimeMillis() - started
                        "$PROBE_OK $h:$p answers (${ms} ms) - the 3DS is listening"
                    } catch (e: ConnectException) {
                        "Refused - nothing is listening on $h:$p"
                    } catch (e: SocketTimeoutException) {
                        "Timed out - no reply from $h:$p. Check the IP, that no VPN is active, " +
                            "and that the router doesn't isolate wireless clients"
                    } catch (e: Exception) {
                        e.message ?: "Probe failed"
                    }
                }
                testing = false
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val config = StreamConfig(
                        host = host.trim(),
                        viewerPort = portText.toIntOrNull()
                            ?.coerceIn(NTRCommands.UDP_PORT_MIN, NTRCommands.UDP_PORT_MAX)
                            ?: NTRCommands.UDP_PORT,
                        quality = quality,
                        priority = priority,
                        qos = qos,
                        priorityScreen = priorityScreen,
                        colorMode = colorMode,
                    )
                    store.save(config)
                    withLocalNetworkAccess { onConnect(config) }
                },
                enabled = host.isNotBlank() && state !is ConnectionState.Connecting,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text(if (state is ConnectionState.Connecting) "Connecting..." else "Connect")
            }
            OutlinedButton(
                onClick = { withLocalNetworkAccess(::probe) },
                enabled = host.isNotBlank() && !testing && state !is ConnectionState.Connecting,
                modifier = Modifier.height(52.dp),
            ) {
                Text(if (testing) "Testing..." else "Test")
            }
        }
        if (testResult != null) {
            Text(
                text = testResult!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (testResult!!.startsWith(PROBE_OK)) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Target: classic NTR 3.6/3.6.1 and NTR-HR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val localIp = remember { localIpAddress(context) }
        if (localIp != null) {
            Text(
                text = "This phone's IP: $localIp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

private const val PROBE_OK = "OK:"

private const val LOCAL_NETWORK_DENIED =
    "NTRelay needs local network access to reach the 3DS. Allow it when prompted, or " +
        "enable it under Settings > Apps > NTRelay > Permissions (Nearby devices)."

private fun localIpAddress(context: android.content.Context): String? {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return null
    val network = cm.localAreaNetwork() ?: cm.activeNetwork ?: return null
    val linkProps = cm.getLinkProperties(network) ?: return null
    return linkProps.linkAddresses
        .mapNotNull { it.address }
        .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                display(value),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}
