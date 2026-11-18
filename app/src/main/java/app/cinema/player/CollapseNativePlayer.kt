package app.cinema.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class CollapseStreamOption(val id: String, val label: String, val url: String)

private fun collapseStreamOptions(stream: CollapseStream?): List<CollapseStreamOption> = buildList {
    stream?.hls?.takeIf(String::isNotBlank)?.let {
        add(CollapseStreamOption("HLS", "Авто HLS", it))
    }
    stream?.dash?.takeIf(String::isNotBlank)?.let {
        add(CollapseStreamOption("DASH", "Авто DASH", it))
    }
}.distinctBy { it.url }

private fun collapseTrackOrdinal(stream: CollapseStream?, voiceId: String): Int {
    val displayIndex = voiceId.removePrefix("track:").toIntOrNull()?.coerceAtLeast(0) ?: 0
    return stream?.audioOrder?.getOrNull(displayIndex)?.coerceAtLeast(0) ?: displayIndex
}

private fun selectOrdinalTrack(player: ExoPlayer, tracks: Tracks, type: Int, ordinal: Int): Boolean {
    var current = 0
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (track in 0 until group.length) {
            if (!group.isTrackSupported(track)) continue
            if (current == ordinal) {
                player.trackSelectionParameters =
                    player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                        .build()
                return true
            }
            current++
        }
    }
    return false
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
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
    val preferences = remember(context) { LocalStore(context.applicationContext) }

    PlayerImmersiveLifecycle("collapse:$kinopoiskId:$imdbId")

    var showSettings by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf("Настройки") }
    BackHandler {
        if (showSettings) {
            if (settingsSection != "Настройки") settingsSection = "Настройки"
            else showSettings = false
        } else onClose()
    }

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
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                if (loading) CircularProgressIndicator(color = appBlue)
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

    val allEpisodes = remember(media) { media.mirrorEpisodes() }
    var season by remember(media.id) { mutableStateOf(savedSelection?.season.orEmpty()) }
    var episode by remember(media.id) { mutableStateOf(savedSelection?.episode.orEmpty()) }

    LaunchedEffect(media.id, allEpisodes) {
        if (allEpisodes.isNotEmpty() &&
            allEpisodes.none { it.season == season && it.id == episode }) {
            val preferred = allEpisodes.firstOrNull {
                it.season == savedSelection?.season && it.id == savedSelection.episode
            } ?: allEpisodes.first()
            season = preferred.season
            episode = preferred.id
        }
    }

    val stream = media.stream(season, episode)
    val voices = media.voices(season, episode)
    var voice by remember(media.id) {
        mutableStateOf(savedSelection?.voice.orEmpty())
    }
    LaunchedEffect(voices, season, episode) {
        if (voices.none { it.id == voice }) {
            voice = voices.firstOrNull()?.id.orEmpty()
        }
    }

    val streamOptions = remember(stream) { collapseStreamOptions(stream) }
    var quality by remember(media.id) {
        mutableStateOf(savedSelection?.quality.orEmpty())
    }
    LaunchedEffect(streamOptions, season, episode) {
        if (streamOptions.none { it.id == quality }) {
            quality = streamOptions.firstOrNull()?.id.orEmpty()
        }
    }
    val selectedStream = streamOptions.firstOrNull { it.id == quality }
        ?: streamOptions.firstOrNull()
    val streamUrl = selectedStream?.url

    var resumeMs by remember(media.id) { mutableLongStateOf(startPositionMs) }
    var acceptedStream by remember(streamUrl, season, episode) { mutableStateOf(false) }
    var position by remember(streamUrl, season, episode) { mutableLongStateOf(resumeMs) }
    var duration by remember(streamUrl, season, episode) { mutableLongStateOf(0L) }
    var paused by remember(streamUrl, season, episode) { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var accelerated by remember { mutableStateOf(false) }
    var playerIssue by remember(streamUrl, season, episode) { mutableStateOf("") }
    var endedCounter by remember(media.id) { mutableIntStateOf(0) }
    var episodeNotice by remember { mutableStateOf("") }
    var autoNextEpisode by remember {
        mutableStateOf(preferences.autoNextEpisode())
    }

    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    var speed by remember {
        mutableFloatStateOf(preferences.playbackSpeed().takeIf { it in speeds } ?: 1f)
    }
    val pictureModes = listOf("По размеру", "Заполнить", "Растянуть")
    var pictureMode by remember {
        mutableStateOf(preferences.pictureMode().takeIf { it in pictureModes }
            ?: pictureModes.first())
    }
    var subtitleIndex by remember(media.id, season, episode) { mutableIntStateOf(-1) }

    fun selection(
        ms: Long,
        selectedSeason: String = season,
        selectedEpisode: String = episode,
        selectedVoice: String = voice,
        selectedQuality: String = quality
    ) = PlaybackSelection(
        season = selectedSeason,
        episode = selectedEpisode,
        voice = selectedVoice,
        quality = selectedQuality,
        positionMs = ms.coerceAtLeast(0L),
        provider = "collapse",
        kinopoiskId = kinopoiskId,
        imdbId = imdbId
    )

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
                            selectOrdinalTrack(
                                this@apply,
                                tracks,
                                C.TRACK_TYPE_AUDIO,
                                collapseTrackOrdinal(stream, voice)
                            )
                            if (subtitleIndex >= 0) {
                                selectOrdinalTrack(
                                    this@apply,
                                    tracks,
                                    C.TRACK_TYPE_TEXT,
                                    subtitleIndex
                                )
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && !acceptedStream) {
                                acceptedStream = true
                                if (resumeMs > 0L) {
                                    val target = if (duration > 0L)
                                        resumeMs.coerceAtMost(duration) else resumeMs
                                    seekTo(target)
                                    resumeMs = 0L
                                }
                                playWhenReady = true
                            } else if (state == Player.STATE_ENDED && acceptedStream) {
                                endedCounter++
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            paused = !isPlaying
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val next = streamOptions.dropWhile { it.id != quality }
                                .drop(1).firstOrNull()
                            if (next != null) {
                                resumeMs = currentPosition.coerceAtLeast(resumeMs)
                                quality = next.id
                                playerIssue = ""
                            } else {
                                playerIssue =
                                    "Не удалось воспроизвести Collapse: " + error.errorCodeName
                            }
                        }
                    })

                    val subtitles = stream?.subtitles.orEmpty().mapIndexed { index, sub ->
                        val mime = if (sub.url.substringBefore('?').endsWith(".srt", true))
                            MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                            .setMimeType(mime)
                            .setLanguage("und")
                            .setLabel(sub.name.ifBlank { "Субтитры " + (index + 1) })
                            .build()
                    }
                    setMediaItem(
                        MediaItem.Builder().setUri(address)
                            .setSubtitleConfigurations(subtitles)
                            .apply {
                                when (selectedStream?.id) {
                                    "HLS" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                                    "DASH" -> setMimeType(MimeTypes.APPLICATION_MPD)
                                }
                            }.build()
                    )
                    prepare()
                }
        }
    }

    LaunchedEffect(player, voice, stream) {
        player?.let {
            selectOrdinalTrack(
                it,
                it.currentTracks,
                C.TRACK_TYPE_AUDIO,
                collapseTrackOrdinal(stream, voice)
            )
        }
    }

    LaunchedEffect(player, subtitleIndex) {
        player?.let { target ->
            if (subtitleIndex < 0) {
                target.trackSelectionParameters =
                    target.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
            } else {
                selectOrdinalTrack(
                    target,
                    target.currentTracks,
                    C.TRACK_TYPE_TEXT,
                    subtitleIndex
                )
            }
        }
    }

    LaunchedEffect(player, speed) {
        player?.setPlaybackSpeed(speed)
    }

    val latestVoice by rememberUpdatedState(voice)
    DisposableEffect(player) {
        val attachedSeason = season
        val attachedEpisode = episode
        val attachedQuality = quality
        onDispose {
            player?.let {
                val ms = it.currentPosition.coerceAtLeast(0L)
                if (acceptedStream && ms > 0L) {
                    onSave(ms)
                    onSaveSelection(
                        selection(
                            ms,
                            selectedSeason = attachedSeason,
                            selectedEpisode = attachedEpisode,
                            selectedVoice = latestVoice,
                            selectedQuality = attachedQuality
                        )
                    )
                }
                it.release()
            }
        }
    }

    LaunchedEffect(voice, season, episode, quality) {
        if (voice.isNotBlank()) {
            onSaveSelection(selection(player?.currentPosition ?: position))
        }
    }

    LaunchedEffect(player, season, episode, voice, quality) {
        if (player == null) return@LaunchedEffect
        var checkpoint = -1L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            if (acceptedStream && duration > 0L && position > 0L &&
                (checkpoint < 0L || kotlin.math.abs(position - checkpoint) >= 5000L)) {
                onSave(position)
                onSaveSelection(selection(position))
                checkpoint = position
            }
            delay(500)
        }
    }

    LaunchedEffect(controls, showSettings, paused, accelerated, acceptedStream) {
        if (acceptedStream && controls && !showSettings && !paused && !accelerated) {
            delay(4800L)
            controls = false
        }
    }

    LaunchedEffect(episodeNotice) {
        if (episodeNotice.isNotBlank()) {
            delay(2300L)
            episodeNotice = ""
        }
    }

    val nextEpisode = EpisodeNavigator.next(allEpisodes, season, episode)

    fun switchEpisode(target: MirrorEpisode) {
        if (target.season == season && target.id == episode) return
        val oldPosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (oldPosition > 0L) onSaveSelection(selection(oldPosition))
        resumeMs = 0L
        season = target.season
        episode = target.id
        quality = ""
        playerIssue = ""
        controls = true
        showSettings = false
        settingsSection = "Настройки"
        onSaveSelection(
            selection(
                0L,
                selectedSeason = target.season,
                selectedEpisode = target.id,
                selectedQuality = ""
            )
        )
        episodeNotice = "Сезон " + target.season + " · " + target.label
    }

    LaunchedEffect(endedCounter) {
        if (endedCounter <= 0 || allEpisodes.isEmpty() || playerIssue.isNotBlank()) {
            return@LaunchedEffect
        }
        val next = EpisodeNavigator.next(allEpisodes, season, episode)
        if (next == null) {
            episodeNotice = "Последняя доступная серия завершена"
        } else if (autoNextEpisode) {
            switchEpisode(next)
        } else {
            episodeNotice = "Серия окончена"
        }
    }

    var draggingSeek by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = when (pictureMode) {
                        "Заполнить" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        "Растянуть" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().pointerInput(player, seekSeconds, holdSpeed) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { tap ->
                            val jump = seekSeconds * 1000L
                            val next = if (tap.x < size.width / 2f)
                                (player.currentPosition - jump).coerceAtLeast(0L)
                            else player.currentPosition + jump
                            player.seekTo(
                                if (player.duration > 0L)
                                    next.coerceAtMost(player.duration) else next
                            )
                            controls = true
                        },
                        onPress = {
                            coroutineScope {
                                var activated = false
                                val previous = player.playbackParameters.speed
                                val speedJob = launch {
                                    delay(425L)
                                    activated = true
                                    player.setPlaybackSpeed(holdSpeed.toFloat())
                                    accelerated = true
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    speedJob.cancel()
                                    if (activated) {
                                        player.setPlaybackSpeed(previous)
                                        accelerated = false
                                    }
                                }
                            }
                        }
                    )
                }
            )
        }

        if (player == null || !acceptedStream) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (playerIssue.isBlank()) {
                    CircularProgressIndicator(color = appBlue, modifier = Modifier.size(34.dp))
                    Text("Получаем медиапоток Collapse", color = Color.White)
                } else {
                    Icon(
                        Icons.Outlined.VideocamOff,
                        contentDescription = null,
                        tint = appMuted,
                        modifier = Modifier.size(38.dp)
                    )
                    Text(playerIssue, color = Color.White)
                }
            }
        }

        if (episodeNotice.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 66.dp),
                color = Color.Black.copy(alpha = .75f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    episodeNotice,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }

        if (accelerated) {
            Surface(
                color = Color.Black.copy(alpha = .65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            ) {
                Text(
                    holdSpeed.toString() + "×",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }

        if (controls || player == null) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = .63f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color.White
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        filmTitle.ifBlank { "Воспроизведение" },
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        buildList {
                            add("Collapse")
                            selectedStream?.label?.let(::add)
                            if (allEpisodes.isNotEmpty() && season.isNotBlank()) {
                                add("S$season · E$episode")
                            }
                            if (speed != 1f) add(speed.toString() + "×")
                        }.joinToString(" · "),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = {
                    settingsSection = "Настройки"
                    showSettings = true
                    controls = true
                }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Меню плеера",
                        tint = Color.White
                    )
                }
            }
        }

        if (player != null && acceptedStream && controls && playerIssue.isBlank()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                IconButton(
                    onClick = {
                        player.seekTo(
                            (player.currentPosition - seekSeconds * 1000L).coerceAtLeast(0L)
                        )
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Outlined.Replay10,
                        contentDescription = "Назад",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        controls = true
                    },
                    modifier = Modifier.size(58.dp)
                        .background(Color.Black.copy(alpha = .58f), CircleShape)
                ) {
                    Icon(
                        if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val next = player.currentPosition + seekSeconds * 1000L
                        player.seekTo(
                            if (player.duration > 0L)
                                next.coerceAtMost(player.duration) else next
                        )
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Outlined.Forward10,
                        contentDescription = "Вперёд",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }

        if (player != null && playerIssue.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(18.dp).widthIn(max = 490.dp),
                color = Color.Black.copy(alpha = .88f),
                shape = RoundedCornerShape(15.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Видео сейчас недоступно",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        playerIssue,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (player != null && acceptedStream && controls) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = .64f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeLabelCollapse(position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = if (draggingSeek) dragFraction
                        else if (duration > 0L)
                            (position.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f,
                        onValueChange = {
                            dragFraction = it
                            draggingSeek = true
                        },
                        onValueChangeFinished = {
                            if (duration > 0L) {
                                player.seekTo((dragFraction * duration).toLong())
                            }
                            draggingSeek = false
                        },
                        modifier = Modifier.weight(1f).height(28.dp)
                            .padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = appBlue,
                            activeTrackColor = appBlue,
                            inactiveTrackColor = Color.White.copy(alpha = .35f)
                        ),
                        thumb = {
                            Box(
                                Modifier.size(12.dp).clip(CircleShape).background(appBlue)
                            )
                        }
                    )
                    Text(
                        if (duration > 0L) timeLabelCollapse(duration) else "—:—",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (nextEpisode != null) {
                        IconButton(onClick = { switchEpisode(nextEpisode) }) {
                            Icon(
                                Icons.Outlined.SkipNext,
                                contentDescription = "Следующая серия",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .12f))
                    .clickable {
                        showSettings = false
                        settingsSection = "Настройки"
                    }
            )
            Surface(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 12.dp, bottom = 8.dp)
                    .width(250.dp).fillMaxHeight(.72f),
                shape = RoundedCornerShape(15.dp),
                color = Color(0xF222272E),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settingsSection != "Настройки") {
                            IconButton(onClick = { settingsSection = "Настройки" }) {
                                Icon(
                                    Icons.Outlined.ArrowBack,
                                    contentDescription = "Назад"
                                )
                            }
                        }
                        Text(
                            settingsSection,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            settingsSection = "Настройки"
                            showSettings = false
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                        }
                    }

                    if (settingsSection == "Настройки") {
                        val entries = buildList {
                            if (voices.isNotEmpty()) add("Озвучка")
                            if (streamOptions.size > 1) add("Качество")
                            add("Скорость")
                            if (allEpisodes.isNotEmpty()) {
                                add("Сезон")
                                add("Серия")
                                add("Автопереход")
                            }
                            if (stream?.subtitles?.isNotEmpty() == true) add("Субтитры")
                            add("Изображение")
                        }
                        entries.forEach { label ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { settingsSection = label }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, modifier = Modifier.weight(1f))
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    tint = appMuted
                                )
                            }
                        }
                    } else {
                        val values: List<String>
                        val currentIndex: Int
                        when (settingsSection) {
                            "Озвучка" -> {
                                values = voices.map { it.name }
                                currentIndex = voices.indexOfFirst { it.id == voice }
                            }
                            "Качество" -> {
                                values = streamOptions.map { it.label }
                                currentIndex = streamOptions.indexOfFirst { it.id == quality }
                            }
                            "Скорость" -> {
                                values = speeds.map { it.toString() + "×" }
                                currentIndex = speeds.indexOf(speed)
                            }
                            "Сезон" -> {
                                val seasons = allEpisodes.map { it.season }.distinct()
                                values = seasons.map { "Сезон " + it }
                                currentIndex = seasons.indexOf(season)
                            }
                            "Серия" -> {
                                val items = allEpisodes.filter { it.season == season }
                                values = items.map { it.label }
                                currentIndex = items.indexOfFirst { it.id == episode }
                            }
                            "Автопереход" -> {
                                values = listOf("Включён", "Выключен")
                                currentIndex = if (autoNextEpisode) 0 else 1
                            }
                            "Субтитры" -> {
                                values = listOf("Выключены") +
                                    stream?.subtitles.orEmpty().map { it.name }
                                currentIndex = subtitleIndex + 1
                            }
                            "Изображение" -> {
                                values = pictureModes
                                currentIndex = pictureModes.indexOf(pictureMode)
                            }
                            else -> {
                                values = emptyList()
                                currentIndex = -1
                            }
                        }

                        values.forEachIndexed { index, label ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        when (settingsSection) {
                                            "Озвучка" -> {
                                                voices.getOrNull(index)?.let {
                                                    voice = it.id
                                                    onSaveSelection(
                                                        selection(
                                                            player?.currentPosition ?: position,
                                                            selectedVoice = it.id
                                                        )
                                                    )
                                                }
                                            }
                                            "Качество" -> {
                                                streamOptions.getOrNull(index)?.let {
                                                    resumeMs = player?.currentPosition
                                                        ?.coerceAtLeast(0L) ?: position
                                                    quality = it.id
                                                }
                                            }
                                            "Скорость" -> {
                                                speeds.getOrNull(index)?.let {
                                                    speed = it
                                                    preferences.savePlaybackSpeed(it)
                                                }
                                            }
                                            "Сезон" -> {
                                                val seasons =
                                                    allEpisodes.map { it.season }.distinct()
                                                seasons.getOrNull(index)?.let { targetSeason ->
                                                    allEpisodes.firstOrNull {
                                                        it.season == targetSeason
                                                    }?.let(::switchEpisode)
                                                }
                                            }
                                            "Серия" -> {
                                                allEpisodes.filter { it.season == season }
                                                    .getOrNull(index)?.let(::switchEpisode)
                                            }
                                            "Автопереход" -> {
                                                autoNextEpisode = index == 0
                                                preferences.saveAutoNextEpisode(autoNextEpisode)
                                            }
                                            "Субтитры" -> subtitleIndex = index - 1
                                            "Изображение" -> {
                                                pictureModes.getOrNull(index)?.let {
                                                    pictureMode = it
                                                    preferences.savePictureMode(it)
                                                }
                                            }
                                        }
                                        showSettings = false
                                        settingsSection = "Настройки"
                                        controls = true
                                    }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, modifier = Modifier.weight(1f))
                                if (index == currentIndex) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Выбрано",
                                        tint = appBlue
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

private fun timeLabelCollapse(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds / 60L) % 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}
