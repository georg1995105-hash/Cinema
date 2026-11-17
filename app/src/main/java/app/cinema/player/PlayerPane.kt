package app.cinema.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun PlayerPane(
    url: String,
    startPositionMs: Long,
    seekSeconds: Int,
    onClose: () -> Unit,
    onSave: (Long) -> Unit
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            onSave(player.currentPosition.coerceAtLeast(0))
            player.release()
        }
    }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                player.seekTo((player.currentPosition - seekSeconds * 1000L).coerceAtLeast(0))
            }) { Text("−$seekSeconds с") }
            TextButton(onClick = {
                player.seekTo(player.currentPosition + seekSeconds * 1000L)
            }) { Text("+$seekSeconds с") }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Text("Встроенный плеер. Доступность видео зависит от источника.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
