// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.app.NotificationManager
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
    @Test fun resumeDownloadedSongFromSavedPosition() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrbitApplication
        val item = MusicItem("qa-resume", "Pista de prueba de reanudación", "QA", MusicKind.Song, durationSeconds = 60)
        val clip = File(app.cacheDir, "resume-test.wav")
        val length = 8000 * 2 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(length)
        clip.outputStream().use { it.write(header.array()); it.write(ByteArray(length)) }
        app.history.saveDownload(DownloadedTrack(item, clip.absolutePath, "audio/wav"))
        val player = withContext(Dispatchers.Main) { app.playback }
        withContext(Dispatchers.Main) { player.select(item, 15) }
        withTimeout(30000) { player.engine.state.first { it.track?.id == item.id && it.position in 15..25 } }
        withContext(Dispatchers.Main) { player.engine.toggle() }
        withTimeout(10000) { player.engine.state.first { !it.playing } }
        withTimeout(10000) {
            while (SessionStore().load()?.let { it.currentTrackId == item.id && it.positionSeconds >= 15 } != true) delay(200)
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
