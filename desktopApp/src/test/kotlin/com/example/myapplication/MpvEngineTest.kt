// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import com.example.myapplication.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import javax.sound.sampled.*
import java.io.ByteArrayInputStream
import kotlin.test.assertNull

class MpvEngineTest {
    @Test fun publicSearchResultDecodes() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("orbit.live") == "true")
        val source = com.example.myapplication.data.NewPipeMusicSource()
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
                engine.load(track, AudioStream(clip.absolutePath, "audio/wav"))
                withTimeout(15000) {
                    val started = engine.state.first { it.error != null || it.position >= 1 }
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
