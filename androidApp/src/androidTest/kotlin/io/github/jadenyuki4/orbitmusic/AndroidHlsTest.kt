// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.test.platform.app.InstrumentationRegistry
import io.github.jadenyuki4.orbitmusic.domain.AudioStream
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.MusicKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidHlsTest {
    @Test
    fun publicHlsStreamPlaysThroughTheAndroidEngine() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHls") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val engine = withContext(Dispatchers.Main) { AndroidEngine(context, scope) }
        try {
            withContext(Dispatchers.Main) {
                engine.volume(0f)
                engine.load(
                    MusicItem("qa-hls", "Prueba HLS", "Apple", MusicKind.Song),
                    AudioStream(
                        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/gear0/prog_index.m3u8",
                        "application/x-mpegURL",
                    ),
                )
            }
            val transport = withTimeout(45_000) {
                engine.state.first { it.error != null || it.position >= 2 }
            }
            assertNull(transport.error)
            assertTrue(transport.position >= 2)
        } finally {
            withContext(Dispatchers.Main) { engine.close() }
            scope.cancel()
        }
    }
}
