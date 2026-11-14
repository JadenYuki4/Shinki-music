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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
            .setDeviceVolumeControlEnabled(true)
            .setAudioAttributes(audio, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val playback = (application as OrbitApplication).playback
        val settings = playback.equalizerState
        fun attach(id: Int) {
            effect?.release()
            effect = if (id > 0) runCatching { Equalizer(0, id) }.getOrNull() else null
            applyBands(settings.value)
        }
        player.addListener(object : Player.Listener {
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                val max = player.deviceInfo.maxVolume
                if (max > 0) (playback.engine as AndroidEngine).reportDeviceVolume(if (muted) 0f else volume.toFloat() / max, playback)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = attach(audioSessionId)
        })
        attach(player.audioSessionId)
        scope.launch { settings.collect { applyBands(it) } }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(android.R.drawable.ic_media_play)
            }
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_player", true)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val queuePlayer = SessionQueuePlayer(player, playback, scope)
        scope.launch { playback.queue.collect { queuePlayer.queueChanged() } }
        scope.launch { playback.pending.collect { queuePlayer.queueChanged() } }
        scope.launch { playback.volume.collect { queuePlayer.queueChanged() } }
        scope.launch {
            playback.engine.state
                .map { Triple(it.track?.id, it.playing, it.ended) }
                .distinctUntilChanged()
                .collect { queuePlayer.queueChanged() }
        }
        val callback = object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Accept external system, lock-screen, Bluetooth AVRCP, and Wear OS controllers.
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().addAll(
                    Player.COMMAND_SET_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                ).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(commands)
                    .build()
            }
        }
        session = MediaSession.Builder(this, queuePlayer).setSessionActivity(open).setCallback(callback).build()
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
