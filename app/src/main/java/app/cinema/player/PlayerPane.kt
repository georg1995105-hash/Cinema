package app.cinema.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Native Media3 playback for stream URLs that a source actually provides.
 * All controls overlay video, and the Activity remains immersive landscape.
 * Website/detail URLs must never be passed to this player as media items.
 */
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
    PlayerImmersiveLifecycle(url)

    val player = remember(url) {
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
    }
    var controlsVisible by remember(url) { mutableStateOf(true) }
    var position by remember(url) { mutableLongStateOf(startPositionMs) }
    var duration by remember(url) { mutableLongStateOf(0L) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    var errorMessage by remember(url) { mutableStateOf("") }
    var speed by remember(url) { mutableFloatStateOf(1f) }
    var speedMenu by remember(url) { mutableStateOf(false) }
    var fastForward by remember(url) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) errorMessage = ""
                if (state == Player.STATE_ENDED) controlsVisible = true
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Ошибка видео (" + error.errorCodeName + ")"
                controlsVisible = true
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            val finalPosition = player.currentPosition.coerceAtLeast(0L)
            if (finalPosition > 0L) onSave(finalPosition)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(500L)
        }
    }

    // Controls disappear during playback, but remain visible on pause/error.
    LaunchedEffect(controlsVisible, buffering, isPlaying, errorMessage) {
        if (controlsVisible && isPlaying && !buffering && errorMessage.isEmpty()) {
            delay(3_800L)
            controlsVisible = false
            speedMenu = false
        }
    }

    // A stalled source must not leave an infinite spinner without recovery.
    LaunchedEffect(buffering, errorMessage) {
        if (buffering && errorMessage.isEmpty()) {
            delay(25_000L)
            if (buffering && errorMessage.isEmpty()) {
                errorMessage = "Видео не отвечает. Проверьте соединение или повторите."
                controlsVisible = true
            }
        }
    }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = player }
        )

        Box(
            Modifier.fillMaxSize().pointerInput(player, seekSeconds, holdSpeed) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { tap ->
                        val amount = seekSeconds.coerceAtLeast(1) * 1_000L
                        val target = if (tap.x < size.width / 2f)
                            (player.currentPosition - amount).coerceAtLeast(0L)
                        else player.currentPosition + amount
                        player.seekTo(if (player.duration > 0L)
                            target.coerceAtMost(player.duration) else target)
                        controlsVisible = true
                    },
                    onPress = {
                        coroutineScope {
                            var activated = false
                            val original = player.playbackParameters.speed
                            val job = launch {
                                delay(450L)
                                if (player.isPlaying) {
                                    activated = true
                                    player.setPlaybackSpeed(holdSpeed.coerceIn(2, 4).toFloat())
                                    fastForward = true
                                }
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                job.cancel()
                                if (activated) {
                                    player.setPlaybackSpeed(original)
                                    fastForward = false
                                }
                            }
                        }
                    }
                )
            }
        )

        if (buffering && errorMessage.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(36.dp),
                color = appBlue, strokeWidth = 3.dp
            )
        }

        if (errorMessage.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(errorMessage, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    errorMessage = ""
                    buffering = true
                    player.prepare()
                    player.play()
                }) { Text("Повторить") }
            }
        }

        if (controlsVisible) {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.ArrowBack,
                        contentDescription = "Закрыть плеер", tint = Color.White)
                }
                Text("Cinema", color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (fastForward) {
                    Text("Ускорение " + holdSpeed + "×", color = appBlue,
                        style = MaterialTheme.typography.labelMedium)
                }
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(speed.toString() + "×", color = Color.White)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.toString() + "×") },
                                onClick = {
                                    speed = option
                                    player.setPlaybackSpeed(option)
                                    speedMenu = false
                                    controlsVisible = true
                                }
                            )
                        }
                    }
                }
            }

            if (!buffering && errorMessage.isEmpty()) {
                FilledIconButton(
                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .62f))
                ) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .66f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Slider(
                    value = if (duration > 0L)
                        (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        if (duration > 0L) player.seekTo((fraction * duration).toLong())
                    },
                    enabled = duration > 0L,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeLabel(position) + " / " +
                            (if (duration > 0L) timeLabel(duration) else "—"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        player.seekTo((player.currentPosition -
                            seekSeconds.coerceAtLeast(1) * 1_000L).coerceAtLeast(0L))
                    }) {
                        Text("−" + seekSeconds + " с", color = Color.White)
                    }
                    TextButton(onClick = {
                        val target = player.currentPosition +
                            seekSeconds.coerceAtLeast(1) * 1_000L
                        player.seekTo(if (player.duration > 0L)
                            target.coerceAtMost(player.duration) else target)
                    }) {
                        Text("+" + seekSeconds + " с", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds / 60L) % 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
        else "%d:%02d".format(minutes, remainder)
}
