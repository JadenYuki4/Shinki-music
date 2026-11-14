// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.media.audiofx.Equalizer
import kotlinx.coroutines.*
import io.github.jadenyuki4.orbitmusic.domain.ShinkiAppInfo
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var effect: Equalizer? = null

    private fun applyBands(value: EqualizerState) {
        val eq = effect ?: return
        runCatching {
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val index = band.toShort()
                val hz = eq.getCenterFreq(index) / 1000f
                val nearest = ShinkiAppInfo.EQ_FREQUENCIES_HZ.indices.minBy {
                    kotlin.math.abs(kotlin.math.ln(ShinkiAppInfo.EQ_FREQUENCIES_HZ[it] / hz))
                }
                eq.setBandLevel(index, (value.bands[nearest] * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = value.enabled
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (compatible; ShinkiMusic/${ShinkiAppInfo.VERSION})")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audio, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val settings = (application as OrbitApplication).playback.equalizerState
        fun attach(id: Int) {
            effect?.release()
            effect = if (id > 0) runCatching { Equalizer(0, id) }.getOrNull() else null
            applyBands(settings.value)
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = attach(audioSessionId)
        })
        attach(player.audioSessionId)
        scope.launch { settings.collect { applyBands(it) } }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val queuePlayer = object : ForwardingPlayer(player) {
            private val orbit get() = (application as OrbitApplication).playback
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun seekToNext() = orbit.next()
            override fun seekToNextMediaItem() = orbit.next()
            override fun seekToPrevious() = orbit.back()
            override fun seekToPreviousMediaItem() = orbit.back()
        }
        session = MediaSession.Builder(this, queuePlayer).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        effect?.release()
        effect = null
        session.player.release()
        session.release()
        super.onDestroy()
    }
}
