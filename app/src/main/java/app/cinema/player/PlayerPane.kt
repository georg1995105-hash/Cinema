package app.cinema.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun PlayerPane(
    url: String,
    startPositionMs: Long,
    seekSeconds: Int,
    holdSpeed: Int,
    onClose: () -> Unit,
    onSave: (Long) -> Unit
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
        }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(startPositionMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var quickSpeed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose {
            onSave(player.currentPosition.coerceAtLeast(0))
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            delay(400)
        }
    }
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Назад")
            }
            Spacer(Modifier.weight(1f))
            if (quickSpeed) {
                Surface(color = appBlue.copy(alpha = .2f),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("Ускорение " + holdSpeed + "×",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        color = appBlue)
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(player, seekSeconds, holdSpeed) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { tap ->
                            val amount = seekSeconds * 1000L
                            val next = if (tap.x < size.width / 2f)
                                (player.currentPosition - amount).coerceAtLeast(0)
                                else player.currentPosition + amount
                            player.seekTo(if (player.duration > 0)
                                next.coerceAtMost(player.duration) else next)
                            controlsVisible = true
                        },
                        onPress = {
                            coroutineScope {
                                var activated = false
                                val originalSpeed = player.playbackParameters.speed
                                val pressJob = launch {
                                    delay(420)
                                    activated = true
                                    player.setPlaybackSpeed(holdSpeed.toFloat())
                                    quickSpeed = true
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    pressJob.cancel()
                                    if (activated) {
                                        player.setPlaybackSpeed(originalSpeed)
                                        quickSpeed = false
                                    }
                                }
                            }
                        }
                    )
                }
            )
            if (controlsVisible) {
                FilledIconButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                }, modifier = Modifier.size(57.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .6f))) {
                    Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        tint = Color.White, modifier = Modifier.size(31.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                player.seekTo((player.currentPosition - seekSeconds * 1000L).coerceAtLeast(0))
            }) { Text("−" + seekSeconds + " с") }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    if (duration > 0) player.seekTo((it * duration).toLong())
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val next = player.currentPosition + seekSeconds * 1000L
                player.seekTo(if (player.duration > 0)
                    next.coerceAtMost(player.duration) else next)
            }) { Text("+" + seekSeconds + " с") }
        }
        Text(
            text = timeLabel(position) + " / " + (if (duration > 0) timeLabel(duration) else "—"),
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
           else "%d:%02d".format(minutes, remainder)
}
