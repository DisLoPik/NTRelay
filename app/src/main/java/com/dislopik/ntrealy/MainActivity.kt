package com.dislopik.ntrealy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dislopik.ntrealy.network.ConnectionState
import com.dislopik.ntrealy.service.StreamForegroundService
import com.dislopik.ntrealy.service.StreamSession
import com.dislopik.ntrealy.ui.StreamConfig
import com.dislopik.ntrealy.ui.connect.ConnectScreen
import com.dislopik.ntrealy.ui.stream.StreamScreen
import com.dislopik.ntrealy.ui.theme.NTRelayTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NTRelayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        onConnect = ::startStream,
                        onDisconnect = { StreamForegroundService.stop(this) },
                    )
                }
            }
        }
    }

    private fun startStream(config: StreamConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        StreamForegroundService.start(this, config)
    }
}

@Composable
private fun AppRoot(
    onConnect: (StreamConfig) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val state by StreamSession.state.collectAsState()

    LaunchedEffect(state) {
        if (state is ConnectionState.Error) {
            StreamForegroundService.stop(context)
        }
    }

    if (state is ConnectionState.Connecting || state is ConnectionState.Connected) {
        StreamScreen(onDisconnect = onDisconnect)
    } else {
        ConnectScreen(onConnect = onConnect)
    }
}
