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
    private var pending: Pair<MusicItem, AudioStream>? = null
    private var tick: Job? = null
    private var disconnected = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            tick?.cancel()
            if (player.isPlaying) tick = scope.launch {
                while (isActive) {
                    delay(1000)
                    refresh(player)
                }
            }
        }
    }
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    init { connect() }

    private fun connect() {
        val next = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
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
                player.addListener(listener)
                refresh(player)
                pending?.let { (track, stream) -> load(track, stream) }
                pending = null
            } catch (e: Exception) {
                future = null
                state.value = Transport(error = "No se pudo conectar con el servicio de reproducción de Android.")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    private fun refresh(player: Player) {
        val item = player.currentMediaItem
        val track = item?.let { MusicItem(it.mediaId, it.mediaMetadata.title.toString(),
            it.mediaMetadata.artist?.toString().orEmpty(), MusicKind.Song, it.mediaMetadata.artworkUri?.toString(),
            player.duration.takeIf { length -> length > 0 }?.div(1000)) }
        state.value = Transport(track, player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            player.currentPosition.coerceAtLeast(0) / 1000, player.duration.coerceAtLeast(0) / 1000,
            player.playerError?.let { "Audio no disponible. Reintentá para solicitar una transmisión nueva." }, player.playbackState == Player.STATE_ENDED)
    }

    override fun load(track: MusicItem, stream: AudioStream) {
        val player = controller
        if (player == null) {
            pending = track to stream
            if (future == null && !disconnected) connect()
            return
        }
        val mediaUri = if (stream.url.contains("://")) android.net.Uri.parse(stream.url)
        else android.net.Uri.fromFile(java.io.File(stream.url))
        player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(mediaUri).setMimeType(stream.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.subtitle)
                .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse)).build()).build())
        player.prepare()
        player.play()
    }
    override fun volume(value: Float) { controller?.volume = value }
    override fun speed(value: Float) { controller?.setPlaybackSpeed(value) }
    override fun toggle() {
        controller?.run {
            if (playbackState == Player.STATE_ENDED) { seekTo(0); play() }
            else if (playWhenReady) pause() else play()
        }
    }
    override fun seek(seconds: Long) { controller?.seekTo(seconds * 1000) }
    fun close() {
        disconnected = true
        tick?.cancel()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
    }
}
