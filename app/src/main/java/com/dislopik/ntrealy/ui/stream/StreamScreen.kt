package com.dislopik.ntrealy.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dislopik.ntrealy.R
import com.dislopik.ntrealy.network.ConnectionState
import com.dislopik.ntrealy.render.StreamSurfaceView
import com.dislopik.ntrealy.service.StreamSession

@Composable
fun StreamScreen(
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by StreamSession.state.collectAsState()
    val fps by StreamSession.fps.collectAsState()
    val debugLog by StreamSession.debugLog.collectAsState()

    val context = LocalContext.current
    val surfaceView = remember { StreamSurfaceView(context) }
    val lastNtrLine = debugLog.lastOrNull { it.startsWith("NTR:") }

    LaunchedEffect(Unit) {
        StreamSession.frames.collect { frame ->
            surfaceView.renderer.submit(frame)
        }
    }

    DisposableEffect(surfaceView) {
        onDispose {
            surfaceView.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            color = Color(0x8C000000),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText(state),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    if (state is ConnectionState.Connected) {
                        Text(
                            text = "  |  $fps fps",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
                if (lastNtrLine != null && state !is ConnectionState.Error) {
                    Text(
                        text = lastNtrLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        }

        TextButton(
            onClick = onDisconnect,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                contentDescription = null,
                tint = Color.White,
            )
            Text("Disconnect", color = Color.White)
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Connecting -> "Connecting..."
    is ConnectionState.Connected -> "Streaming"
    is ConnectionState.Error -> state.message
    else -> "Idle"
}
