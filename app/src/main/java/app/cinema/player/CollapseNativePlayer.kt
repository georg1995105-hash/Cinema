package app.cinema.player

import android.net.Uri
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
internal fun CollapseNativePlayer(
    filmTitle: String,
    kinopoiskId: String,
    imdbId: String,
    startPositionMs: Long,
    seekSeconds: Int,
    holdSpeed: Int,
    savedSelection: PlaybackSelection?,
    onSaveSelection: (PlaybackSelection) -> Unit,
    onSave: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val userAgent = remember { android.webkit.WebSettings.getDefaultUserAgent(context) }
    var result by remember(kinopoiskId, imdbId) { mutableStateOf<CollapseResult?>(null) }
    var loading by remember(kinopoiskId, imdbId) { mutableStateOf(true) }
    var issue by remember(kinopoiskId, imdbId) { mutableStateOf("") }

    LaunchedEffect(kinopoiskId, imdbId) {
        loading = true
        issue = ""
        val fetched = CollapseSource.fetch(kinopoiskId, imdbId, userAgent)
        result = fetched
        issue = fetched.reason
        loading = false
    }

    val media = result?.media
    if (media == null) {
        BackHandler { onClose() }
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                if (loading) CircularProgressIndicator()
                Text(
                    if (loading) "Получаем поток Collapse"
                    else issue.ifBlank { "Collapse не предоставил поток." },
                    color = Color.White
                )
                OutlinedButton(onClick = onClose) { Text("Назад") }
            }
        }
        return
    }

    val allEpisodes = media.mirrorEpisodes()
    var season by remember(media.id) {
        mutableStateOf(savedSelection?.season.orEmpty())
    }
    var episode by remember(media.id) {
        mutableStateOf(savedSelection?.episode.orEmpty())
    }
    if (allEpisodes.isNotEmpty()) {
        val selected = allEpisodes.firstOrNull {
            it.season == season && it.id == episode
        } ?: allEpisodes.first()
        season = selected.season
        episode = selected.id
    }

    val stream = media.stream(season, episode)
    val voices = media.voices(season, episode)
    var voice by remember(media.id, season, episode) {
        mutableStateOf(
            savedSelection?.voice?.takeIf { saved ->
                voices.any { it.id == saved }
            } ?: voices.firstOrNull()?.id.orEmpty()
        )
    }
    LaunchedEffect(voices, season, episode) {
        if (voices.none { it.id == voice }) voice = voices.firstOrNull()?.id.orEmpty()
    }

    val urls = remember(stream) {
        listOfNotNull(
            stream?.hls?.takeIf(String::isNotBlank),
            stream?.dash?.takeIf(String::isNotBlank)
        ).distinct()
    }
    var urlIndex by remember(stream, season, episode) { mutableIntStateOf(0) }
    val streamUrl = urls.getOrNull(urlIndex)

    var resumeMs by remember(media.id, season, episode) {
        mutableLongStateOf(startPositionMs)
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(resumeMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var quickSpeed by remember { mutableStateOf(false) }
    var playerIssue by remember(streamUrl, season, episode) { mutableStateOf("") }

    fun selection(ms: Long) = PlaybackSelection(
        season = season,
        episode = episode,
        voice = voice,
        quality = "auto",
        positionMs = ms.coerceAtLeast(0L),
        provider = "collapse",
        kinopoiskId = kinopoiskId,
        imdbId = imdbId
    )

    fun applyAudioTrack(player: ExoPlayer, tracks: Tracks) {
        val wanted = voice.removePrefix("track:").toIntOrNull() ?: 0
        var ordinal = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (track in 0 until group.length) {
                if (!group.isTrackSupported(track)) continue
                if (ordinal == wanted) {
                    player.trackSelectionParameters =
                        player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, track)
                            ).build()
                    return
                }
                ordinal++
            }
        }
    }

    val player = remember(streamUrl, season, episode) {
        streamUrl?.let { address ->
            val http = OkHttpDataSource.Factory(MirrorHttp.client)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(
                    result?.endpoint?.takeIf(String::isNotBlank)
                        ?.let { mapOf("Referer" to it) }.orEmpty()
                )
            val data = DefaultDataSource.Factory(context, http)
            ExoPlayer.Builder(
                context,
                DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            ).setMediaSourceFactory(DefaultMediaSourceFactory(data))
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            applyAudioTrack(this@apply, tracks)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                if (resumeMs > 0) {
                                    val target = if (duration > 0)
                                        resumeMs.coerceAtMost(duration) else resumeMs
                                    seekTo(target)
                                    resumeMs = 0L
                                }
                                playWhenReady = true
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (urlIndex < urls.lastIndex) {
                                resumeMs = currentPosition.coerceAtLeast(0L)
                                urlIndex++
                            } else {
                                playerIssue = "Не удалось воспроизвести Collapse: " + error.errorCodeName
                            }
                        }
                    })

                    val subtitles = stream?.subtitles.orEmpty().map { sub ->
                        val mime = when {
                            sub.url.substringBefore('?').endsWith(".srt", true) ->
                                MimeTypes.APPLICATION_SUBRIP
                            else -> MimeTypes.TEXT_VTT
                        }
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                            .setMimeType(mime)
                            .setLabel(sub.name)
                            .build()
                    }
                    setMediaItem(
                        MediaItem.Builder().setUri(address)
                            .setSubtitleConfigurations(subtitles)
                            .apply {
                                if (address.contains(".m3u8", true))
                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                            }.build()
                    )
                    prepare()
                }
        }
    }

    LaunchedEffect(player, voice) {
        player?.let { applyAudioTrack(it, it.currentTracks) }
    }

    DisposableEffect(player, season, episode, voice) {
        onDispose {
            player?.let {
                val ms = it.currentPosition.coerceAtLeast(0L)
                if (ms > 0L) {
                    onSave(ms)
                    onSaveSelection(selection(ms))
                }
                it.release()
            }
        }
    }

    LaunchedEffect(player, season, episode, voice) {
        if (player == null) return@LaunchedEffect
        var checkpoint = -1L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            if (duration > 0L && position > 0L &&
                (checkpoint < 0L || kotlin.math.abs(position - checkpoint) >= 5000L)) {
                onSave(position)
                onSaveSelection(selection(position))
                checkpoint = position
            }
            delay(450)
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
            Text(
                filmTitle,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = Color(0xFF8B5CF6).copy(alpha = .25f),
                shape = RoundedCornerShape(11.dp)
            ) {
                Text(
                    "Collapse",
                    color = Color(0xFFC4B5FD),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            keepScreenOn = true
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().pointerInput(player, seekSeconds, holdSpeed) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { tap ->
                                val delta = seekSeconds * 1000L
                                val next = if (tap.x < size.width / 2f)
                                    (player.currentPosition - delta).coerceAtLeast(0L)
                                else player.currentPosition + delta
                                player.seekTo(
                                    if (player.duration > 0)
                                        next.coerceAtMost(player.duration) else next
                                )
                                controlsVisible = true
                            },
                            onPress = {
                                coroutineScope {
                                    var activated = false
                                    val original = player.playbackParameters.speed
                                    val job = launch {
                                        delay(420)
                                        activated = true
                                        player.setPlaybackSpeed(holdSpeed.toFloat())
                                        quickSpeed = true
                                    }
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        job.cancel()
                                        if (activated) {
                                            player.setPlaybackSpeed(original)
                                            quickSpeed = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
                if (controlsVisible) {
                    FilledIconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(58.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = .62f)
                        )
                    ) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(31.dp)
                        )
                    }
                }
                if (quickSpeed) {
                    Surface(
                        color = Color.Black.copy(alpha = .65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    ) {
                        Text(
                            holdSpeed.toString() + "×",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            } else {
                Text(
                    playerIssue.ifBlank { "Collapse не предоставил медиапоток." },
                    color = Color.White
                )
            }
        }

        if (voices.size > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text(voices.firstOrNull { it.id == voice }?.name ?: "Озвучка")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    voices.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                voice = item.id
                                expanded = false
                                onSaveSelection(selection(player?.currentPosition ?: position))
                            }
                        )
                    }
                }
            }
        }

        if (playerIssue.isNotBlank()) {
            Text(
                playerIssue,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                player?.seekTo((player.currentPosition - seekSeconds * 1000L).coerceAtLeast(0L))
            }) { Text("−" + seekSeconds + " с") }
            Slider(
                value = if (duration > 0)
                    (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { value ->
                    if (duration > 0)
                        player?.seekTo((value * duration).toLong())
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                player?.let {
                    val next = it.currentPosition + seekSeconds * 1000L
                    it.seekTo(if (it.duration > 0) next.coerceAtMost(it.duration) else next)
                }
            }) { Text("+" + seekSeconds + " с") }
        }

        Text(
            timeLabelCollapse(position) + " / " +
                (if (duration > 0) timeLabelCollapse(duration) else "—"),
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

private fun timeLabelCollapse(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds / 60L) % 60L
    val remainder = seconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}
