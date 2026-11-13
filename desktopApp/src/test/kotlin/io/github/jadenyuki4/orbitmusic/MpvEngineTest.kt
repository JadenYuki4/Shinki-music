// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import javax.sound.sampled.*
import java.io.ByteArrayInputStream
import kotlin.test.assertNull

class MpvEngineTest {
    @Test fun windowsMediaKeysReachPlayback() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("orbit.mediaKeysQA") == "true")
        val clip = File.createTempFile("shinki-control-", ".wav")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val format = AudioFormat(8000f, 16, 1, true, false)
        try {
            AudioInputStream(ByteArrayInputStream(ByteArray(16000 * 60)), format, 480000L).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, clip)
            }
            val source = object : MusicSource by io.github.jadenyuki4.orbitmusic.di.PreviewMusicSource {
                override suspend fun stream(songId: String) = SourceResult.Success(AudioStream(clip.absolutePath, "audio/wav"))
                override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) = stream(songId)
            }
            MpvEngine("null").use { engine ->
                val player = Playback(source, engine, scope)
                val first = MusicItem("qa1","Shinki QA one","QA",MusicKind.Song)
                val second = first.copy(id = "qa2", title = "Shinki QA two")
                val job = mediaKeys(player, scope)
                try {
                    withContext(Dispatchers.Main) { player.playFrom(first, listOf(first, second)) }
                    withTimeout(15000) { engine.state.first { it.position >= 1 } }
                    delay(1000)
                    val input = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("keybd_event")
                    fun press(key: Int) {
                        for (flags in listOf(0, 2)) input.invokeVoid(arrayOf(key.toByte(), 0.toByte(), flags, com.sun.jna.Pointer.NULL))
                    }
                    withTimeout(10000) {
                        press(0xB3)
                        engine.state.first { !it.playing }
                        press(0xB3)
                        engine.state.first { it.playing }
                        press(0xB0)
                        engine.state.first { it.track?.id == second.id }
                        press(0xB1)
                        engine.state.first { it.track?.id == first.id }
                    }
                } finally { job.cancelAndJoin(); scope.cancel() }
            }
        } finally { clip.delete() }
        Unit
    }
    @Test fun publicSearchResultDecodes() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("orbit.live") == "true")
        val source = io.github.jadenyuki4.orbitmusic.data.NewPipeMusicSource()
        val found = source.search("Daft Punk", MusicKind.Song)
        val track = kotlin.test.assertIs<SourceResult.Success<MusicPage>>(found).value.items.first()
        val resolved = source.stream(track.id)
        val stream = kotlin.test.assertIs<SourceResult.Success<AudioStream>>(resolved).value
        MpvEngine("null").use { engine ->
            engine.load(track, stream)
            val started = withTimeout(30000) { engine.state.first { it.error != null || it.position >= 1 } }
            assertNull(started.error)
        }
    }

    @Test fun nativeDecodePauseSeekAndEnd() = runBlocking {
        val clip = File.createTempFile("orbit-native-", ".wav")
        val format = AudioFormat(8000f, 16, 1, true, false)
        try {
            AudioInputStream(ByteArrayInputStream(ByteArray(8000 * 2 * 7)), format, 8000L * 7).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, clip)
            }
            MpvEngine("null").use { engine ->
                val track = MusicItem("test", "Sample", "", MusicKind.Song)
                engine.loadAt(track, AudioStream(clip.absolutePath, "audio/wav"), 2)
                withTimeout(15000) {
                    val started = engine.state.first { it.error != null || it.position >= 3 }
                    assertNull(started.error)
                    engine.toggle()
                    engine.state.first { !it.playing }
                    engine.seek(4)
                    engine.state.first { it.position >= 4 }
                    engine.toggle()
                    engine.state.first { it.playing }
                    assertNull(engine.state.first { !it.playing }.error)
                }
            }
        } finally { clip.delete() }
    }
}

