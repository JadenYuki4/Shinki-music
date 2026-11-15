// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import javax.sound.sampled.*
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvEngineTest {
    private fun writeWave(file: File, seconds: Int) {
        val format = AudioFormat(8000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(16000 * seconds)), format, 8000L * seconds).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
    }

    @Test fun windowsMediaKeysReachPlayback() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("orbit.mediaKeysQA") == "true")
        val clip = File.createTempFile("shinki-control-", ".wav")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            writeWave(clip, 30)
            val source = object : MusicSource by io.github.jadenyuki4.orbitmusic.di.PreviewMusicSource {
                override suspend fun stream(songId: String) = SourceResult.Success(AudioStream(clip.absolutePath, "audio/wav"))
                override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) = stream(songId)
            }
            MpvEngine("null").use { engine ->
                val player = Playback(source, engine, scope)
                val first = MusicItem("qa1","Shinki QA one","QA",MusicKind.Song)
                val second = first.copy(id = "qa2", title = "Shinki QA two")
                val third = first.copy(id = "qa3", title = "Shinki QA three")
                val fourth = first.copy(id = "qa4", title = "Shinki QA four")
                val job = mediaKeys(player, scope)
                try {
                    withContext(Dispatchers.Main) { player.playFrom(first, listOf(first, second, third, fourth)) }
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
                        delay(300)
                        assertEquals(second.id, engine.state.value.track?.id)
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
                    engine.seek(2)
                    val restarted = engine.state.first { it.playing && !it.ended && it.position >= 2 }
                    assertNull(restarted.error)
                }
            }
        } finally { clip.delete() }
    }

    @Test fun mpvQueueSingleDoubleAndEofTransitionsDoNotSkipTracks() {
        val longA = File.createTempFile("mpv-a-", ".wav")
        val shortA = File.createTempFile("mpv-a-short-", ".wav")
        val clipB = File.createTempFile("mpv-b-", ".wav")
        val clipC = File.createTempFile("mpv-c-", ".wav")
        val clipD = File.createTempFile("mpv-d-", ".wav")
        writeWave(longA, 20)
        writeWave(shortA, 2)
        writeWave(clipB, 10)
        writeWave(clipC, 10)
        writeWave(clipD, 10)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) {
                val trackA = MusicItem("A", "Track A", "Artist", MusicKind.Song, durationSeconds = 20)
                val trackAShort = MusicItem("A-short", "Track A Short", "Artist", MusicKind.Song, durationSeconds = 2)
                val trackB = MusicItem("B", "Track B", "Artist", MusicKind.Song, durationSeconds = 10)
                val trackC = MusicItem("C", "Track C", "Artist", MusicKind.Song, durationSeconds = 10)
                val trackD = MusicItem("D", "Track D", "Artist", MusicKind.Song, durationSeconds = 10)
                val files = mapOf(
                    "A" to longA,
                    "A-short" to shortA,
                    "B" to clipB,
                    "C" to clipC,
                    "D" to clipD,
                )
                val source = object : MusicSource by io.github.jadenyuki4.orbitmusic.di.PreviewMusicSource {
                    override suspend fun stream(songId: String): SourceResult<AudioStream> {
                        val file = files[songId] ?: return SourceResult.Failure(SourceError.Network)
                        return SourceResult.Success(AudioStream(file.absolutePath, "audio/wav"))
                    }
                    override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) =
                        stream(songId)
                }
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                MpvEngine("null").use { engine ->
                    try {
                        val playback = Playback(source, engine, scope)
                        playback.autoplay.value = false
                        val fixedQueue = listOf(trackA, trackB, trackC, trackD)
                        playback.playFrom(trackA, fixedQueue)
                        withTimeout(15_000) {
                            engine.state.first { it.track?.id == "A" && it.playing && it.position >= 1 }
                        }

                        playback.seekBy(10)
                        val seeked = withTimeout(15_000) {
                            engine.state.first { it.track?.id == "A" && it.position >= 9 }
                        }
                        assertEquals("A", seeked.track?.id)

                        playback.next()
                        withTimeout(15_000) {
                            engine.state.first { it.track?.id == "B" && it.playing && it.position >= 1 }
                        }
                        delay(300)
                        assertEquals("B", engine.state.value.track?.id)
                        assertEquals(listOf("A", "B", "C", "D"), playback.queue.value.map { it.id })

                        playback.playFrom(trackA, fixedQueue)
                        withTimeout(15_000) {
                            engine.state.first { it.track?.id == "A" && it.playing && it.position >= 1 }
                        }
                        playback.next()
                        playback.next()
                        val onC = withTimeout(15_000) {
                            engine.state.first { it.track?.id == "C" && it.playing && it.position >= 1 }
                        }
                        delay(300)
                        assertEquals("C", engine.state.value.track?.id)
                        assertNull(onC.error)

                        val eofQueue = listOf(trackAShort, trackB, trackC, trackD)
                        playback.playFrom(trackAShort, eofQueue)
                        withTimeout(15_000) {
                            engine.state.first { it.track?.id == "B" && it.playing }
                        }
                        playback.next()
                        delay(500)
                        assertEquals("B", engine.state.value.track?.id)

                        playback.next()
                        withTimeout(15_000) {
                            engine.state.first { it.track?.id == "C" && it.playing }
                        }
                        assertEquals("C", engine.state.value.track?.id)
                    } finally {
                        scope.cancel()
                    }
                }
            }
        } finally {
            dispatcher.close()
            longA.delete()
            shortA.delete()
            clipB.delete()
            clipC.delete()
            clipD.delete()
        }
    }
}

