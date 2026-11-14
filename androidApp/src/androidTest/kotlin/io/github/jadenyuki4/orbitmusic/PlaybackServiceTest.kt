// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.app.NotificationManager
import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.TimeUnit
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackServiceTest {
    private fun resumeClip(context: Context): File {
        val clip = File(context.cacheDir, "resume-test.wav")
        val length = 8000 * 2 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(length)
        clip.outputStream().use { it.write(header.array()); it.write(ByteArray(length)) }
        return clip
    }

    @Test fun resumeDownloadedSongFromSavedPosition() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrbitApplication
        val sessionStore = SessionStore().also { it.clear() }
        val item = MusicItem("qa-resume", "Pista de prueba de reanudación", "QA", MusicKind.Song, durationSeconds = 60)
        val clip = resumeClip(app)
        app.history.saveDownload(DownloadedTrack(item, clip.absolutePath, "audio/wav"))
        val player = withContext(Dispatchers.Main) { app.playback }
        withContext(Dispatchers.Main) {
            player.autoplay.value = false
            player.select(item, 15)
        }
        withTimeout(30000) { player.engine.state.first { it.track?.id == item.id && it.position in 15..25 } }
        withTimeout(30000) { player.engine.state.first { it.track?.id == item.id && it.position >= 20 } }
        withTimeout(10000) {
            while (sessionStore.load()?.let { it.currentTrackId == item.id && it.positionSeconds >= 20 } != true) delay(200)
        }
        assertTrue(player.engine.state.value.playing)
        withContext(Dispatchers.Main) { player.engine.toggle() }
        val paused = withTimeout(10000) { player.engine.state.first { !it.playing } }
        withTimeout(10000) {
            while (sessionStore.load()?.let { it.currentTrackId == item.id && it.positionSeconds == paused.position } != true) delay(200)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun controllerObservesQueueAndControlsPlayback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as OrbitApplication
        val clip = resumeClip(app)
        val tracks = (1..3).map { index ->
            MusicItem("qa-session-$index", "Pista $index", "Artista", MusicKind.Song,
                artworkUrl = "https://example.org/cover.png", durationSeconds = 60)
        }
        tracks.forEach { app.history.saveDownload(DownloadedTrack(it, clip.absolutePath, "audio/wav")) }
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setAllowDeviceVolumeCommandsForLocalPlayback(true)
                .buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
        try {
            withContext(Dispatchers.Main) {
                val playback = app.playback
                playback.autoplay.value = false
                playback.clearQueue()
                playback.enqueue(tracks[1])
                playback.playFromSearch(tracks[0])
                playback.enqueue(tracks[2])
                playback.enqueue(tracks[2])
                withTimeout(20000) {
                    while (controller.mediaItemCount != 3 || !controller.isPlaying) delay(100)
                }
                assertEquals(tracks.map { it.id }, (0..2).map { controller.getMediaItemAt(it).mediaId })
                val metadata = controller.getMediaItemAt(1).mediaMetadata
                assertEquals("Pista 2", metadata.title.toString())
                assertEquals("Artista", metadata.artist.toString())
                assertEquals(tracks[1].artworkUrl, metadata.artworkUri.toString())
                listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME).forEach {
                    assertTrue("Comando ausente: $it", controller.isCommandAvailable(it))
                }
                controller.seekToNextMediaItem()
                withTimeout(10000) { while (controller.currentMediaItem?.mediaId != tracks[1].id || playback.pending.value != null) delay(100) }
                controller.seekToPrevious()
                withTimeout(10000) { while (controller.currentMediaItem?.mediaId != tracks[0].id || playback.pending.value != null) delay(100) }
                playback.remove(tracks[2])
                withTimeout(5000) { while (controller.mediaItemCount != 2) delay(100) }
                val initial = controller.deviceVolume
                val target = if (initial > 0) initial - 1 else 1
                try {
                    controller.setDeviceVolume(target, 0)
                    withTimeout(5000) {
                        val fraction = target.toFloat() / controller.deviceInfo.maxVolume
                        while (controller.deviceVolume != target || kotlin.math.abs(playback.volume.value - fraction) > 0.001f) delay(100)
                    }
                } finally {
                    controller.setDeviceVolume(initial, 0)
                    controller.pause()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun skipDebouncePreventsDoubleNextSkipAndPreservesVolumeControl() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as OrbitApplication
        val clip = resumeClip(app)
        val tracks = (1..3).map { index ->
            MusicItem("qa-debounce-$index", "Debounce $index", "Artista", MusicKind.Song, durationSeconds = 60)
        }
        tracks.forEach { app.history.saveDownload(DownloadedTrack(it, clip.absolutePath, "audio/wav")) }
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setAllowDeviceVolumeCommandsForLocalPlayback(true)
                .buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
        try {
            withContext(Dispatchers.Main) {
                val playback = app.playback
                playback.autoplay.value = false
                playback.clearQueue()
                playback.enqueue(tracks[1])
                playback.enqueue(tracks[2])
                playback.playFromSearch(tracks[0])
                withTimeout(20000) {
                    while (controller.currentMediaItem?.mediaId != tracks[0].id || !controller.isPlaying) delay(100)
                }
                delay(300)
                // Fire two rapid Next requests inside the 250ms debounce window.
                controller.seekToNextMediaItem()
                controller.seekToNextMediaItem()
                delay(450)
                assertEquals(tracks[1].id, playback.engine.state.value.track?.id)
                val initial = controller.deviceVolume
                val max = controller.deviceInfo.maxVolume
                if (max > 0) {
                    if (initial < max) controller.increaseDeviceVolume(0) else controller.decreaseDeviceVolume(0)
                    delay(200)
                    assertTrue(playback.volume.value in 0f..1f)
                    controller.setDeviceVolume(initial, 0)
                }
                controller.pause()
            }
        } finally {
            withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
        }
    }

    @Test fun audioContinuesWithMediaNotificationAfterActivityLeaves() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val engine = withContext(Dispatchers.Main) { AndroidEngine(context, scope) }
        val clip = File(context.cacheDir, "orbit-service-smoke.wav")
        val bytes = 8000 * 2 * 20
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        clip.outputStream().use { it.write(header.array()); it.write(ByteArray(bytes)) }
        try {
            withTimeout(30000) {
                withContext(Dispatchers.Main) {
                    engine.load(MusicItem("test", "Orbit session test", "Test", MusicKind.Song),
                        AudioStream(android.net.Uri.fromFile(clip).toString(), "audio/wav"))
                }
                engine.state.first { it.position >= 1 }
                withContext(Dispatchers.Main) { activity.moveTaskToBack(true) }
                engine.state.first { it.position >= 3 }
                val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // Android posts the media notification asynchronously, particularly on a cold emulator.
                withTimeout(12_000) {
                    while (notifications.activeNotifications.none {
                        it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
                    }) delay(200)
                }
                withContext(Dispatchers.Main) { engine.toggle() }
                engine.state.first { !it.playing }
                withContext(Dispatchers.Main) { engine.seek(8) }
                engine.state.first { it.position >= 8 }
                withContext(Dispatchers.Main) {
                    engine.toggle()
                    engine.seek(19)
                }
                engine.state.first { it.ended }
                withContext(Dispatchers.Main) { engine.seek(5) }
                engine.state.first { !it.ended && it.position in 5..12 }
                Unit
            }
        } finally {
            withContext(Dispatchers.Main) { engine.close(); activity.finish() }
            scope.cancel()
            clip.delete()
        }
    }
}
