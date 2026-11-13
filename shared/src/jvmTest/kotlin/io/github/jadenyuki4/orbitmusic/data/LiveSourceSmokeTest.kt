// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit opt-in only. Normal tests never need YouTube or expose a user's query/history. */
class LiveSourceSmokeTest {
    @Test fun suggestionsAndMusicCategories() = runBlocking {
        assumeTrue(System.getProperty("orbit.live") == "true")
        val source = NewPipeMusicSource()
        val trend = source.discover(null, 8)
        assertTrue(assertIs<SourceResult.Success<MusicPage>>(trend).value.items.isNotEmpty())
        val lyrics = source.lyrics(MusicItem("unused", "Yellow", "Coldplay", MusicKind.Song))
        assertFalse(assertIs<SourceResult.Success<String?>>(lyrics).value.isNullOrBlank())
        val suggestions = source.suggestions("Daft Punk")
        assertIs<SourceResult.Success<List<String>>>(suggestions)
        assertTrue(suggestions.value.isNotEmpty())
        MusicKind.entries.forEach { kind ->
            val response = source.search("Daft Punk", kind, null, 30)
            assertIs<SourceResult.Success<MusicPage>>(response, "Live $kind returned $response")
            assertTrue(response.value.items.isNotEmpty(), "No live $kind results")
            assertTrue(response.value.items.all { it.kind == kind })
            if (kind == MusicKind.Song) {
                val audio = source.stream(response.value.items.first().id)
                if (audio is SourceResult.Failure) fail("Public stream resolution: ${audio.error}")
                assertTrue((audio as SourceResult.Success).value.url.startsWith("https://"))
                val next = assertNotNull(response.value.nextPage, "Song search should offer a continuation")
                val continuation = source.search("Daft Punk", kind, next, 30)
                assertIs<SourceResult.Success<MusicPage>>(continuation)
                assertTrue(continuation.value.items.isNotEmpty(), "Empty song continuation")
            }
        }
    }
}
