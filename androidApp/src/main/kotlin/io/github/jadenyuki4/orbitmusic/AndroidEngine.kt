// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AndroidEngine(private val context: Context, private val scope: CoroutineScope) : AudioEngine {
    override val state = MutableStateFlow(Transport())
    private var controller: MediaController? = null
    private var pending: Triple<MusicItem, AudioStream, Long>? = null
    private var lastLoadedTrack: MusicItem? = null
    private var lastLoadedStream: AudioStream? = null
    private var tick: Job? = null
    private var disconnected = false
    private var targetVolume: Float? = null
    private var reportingDeviceVolume = false

    internal fun reportDeviceVolume(value: Float, playback: Playback) {
        reportingDeviceVolume = true
        try {
            playback.setVolume(value)
        } finally {
            reportingDeviceVolume = false
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            if (!player.isPlaying) {
                tick?.cancel()
                tick = null
            } else if (tick?.isActive != true) tick = scope.launch {
                while (isActive) {
                    delay(1000)
                    refresh(player)
                }
            }
        }
    }
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    init { connect() }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connect() {
        val next = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setAllowDeviceVolumeCommandsForLocalPlayback(true)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (!disconnected && this@AndroidEngine.controller === controller) {
                        tick?.cancel()
                        this@AndroidEngine.controller = null
                        future = null
                        state.value = state.value.copy(playing = false, error = "La sesión de audio se desconectó. Reintentá para reconectar.")
                    }
                }
            }).buildAsync()
        future = next
        next.addListener({
            if (!disconnected) try {
                val player = next.get()
                controller = player
                targetVolume?.let { volume(it) }
                player.addListener(listener)
                refresh(player)
                pending?.let { (track, stream, pos) -> loadAt(track, stream, pos) }
                pending = null
            } catch (e: Exception) {
                future = null
                state.value = Transport(error = "No se pudo conectar con el servicio de reproducción de Android.")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    private fun refresh(player: Player) {
        val item = player.currentMediaItem
        val knownDuration = player.duration.takeIf { it > 0L }?.div(1000L)
            ?: lastLoadedTrack?.takeIf { it.id == item?.mediaId }?.durationSeconds
            ?: state.value.takeIf { it.track?.id == item?.mediaId }?.duration?.takeIf { it > 0L }
            ?: 0L
        val track = item?.let {
            MusicItem(
                id = it.mediaId,
                title = it.mediaMetadata.title?.toString().orEmpty(),
                subtitle = it.mediaMetadata.artist?.toString().orEmpty(),
                kind = MusicKind.Song,
                artworkUrl = it.mediaMetadata.artworkUri?.toString(),
                durationSeconds = knownDuration.takeIf { length -> length > 0L },
            )
        }
        player.playerError?.let { err ->
            android.util.Log.e("AndroidEngine", "ExoPlayer error [${err.errorCodeName}]: ${err.message}", err)
        }
        state.value = Transport(
            track = track,
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            position = player.currentPosition.coerceAtLeast(0L) / 1000L,
            duration = knownDuration,
            error = player.playerError?.let { "Audio no disponible. Reintentá para solicitar una transmisión nueva." },
            ended = player.playbackState == Player.STATE_ENDED,
        )
    }

    override fun load(track: MusicItem, stream: AudioStream) = loadAt(track, stream, 0)
    override fun loadAt(track: MusicItem, stream: AudioStream, seconds: Long) {
        lastLoadedTrack = track
        lastLoadedStream = stream
        val player = controller
        if (player == null) {
            pending = Triple(track, stream, seconds)
            if (future == null && !disconnected) connect()
            return
        }
        val mediaUri = if (stream.url.contains("://")) android.net.Uri.parse(stream.url)
        else android.net.Uri.fromFile(java.io.File(stream.url))
        val effectiveMime = if (stream.url.contains(".m3u8", ignoreCase = true)) {
            MimeTypes.APPLICATION_M3U8
        } else {
            stream.mimeType.takeIf { it.isNotBlank() }
        }
        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.subtitle)
                    .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse))
                    .build()
            )
        if (effectiveMime != null) builder.setMimeType(effectiveMime)
        player.setMediaItem(builder.build(), seconds.coerceAtLeast(0L) * 1000L)
        player.prepare()
        player.play()
    }
    override fun volume(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        targetVolume = clamped
        if (reportingDeviceVolume) return
        val player = controller ?: return
        val maximum = player.deviceInfo.maxVolume
        if (maximum <= 0) return
        val level = kotlin.math.round(clamped * maximum).toInt()
        if (player.deviceVolume != level) player.setDeviceVolume(level, 0)
        if (player.isDeviceMuted && level > 0) player.setDeviceMuted(false, 0)
    }
    override fun speed(value: Float) { controller?.setPlaybackSpeed(value) }
    override fun toggle() {
        controller?.run {
            if (playbackState == Player.STATE_ENDED) { seekTo(0); play() }
            else if (playWhenReady) pause() else play()
        }
    }
    override fun seek(seconds: Long) {
        val targetSeconds = seconds.coerceAtLeast(0L)
        val player = controller
        if (player == null) {
            pending = pending?.copy(third = targetSeconds)
            return
        }
        val wasEnded = player.playbackState == Player.STATE_ENDED
        val loadedTrack = lastLoadedTrack
        val loadedStream = lastLoadedStream
        player.seekTo(targetSeconds * 1000L)
        if (wasEnded) {
            player.prepare()
            player.play()
        } else if (player.playbackState == Player.STATE_IDLE && loadedTrack != null && loadedStream != null) {
            loadAt(loadedTrack, loadedStream, targetSeconds)
            return
        }
        state.value = state.value.copy(position = targetSeconds, ended = false)
    }
    fun close() {
        disconnected = true
        tick?.cancel()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
    }
}
