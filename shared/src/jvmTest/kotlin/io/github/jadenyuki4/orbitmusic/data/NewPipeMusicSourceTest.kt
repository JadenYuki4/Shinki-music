// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import kotlin.test.*

class NewPipeMusicSourceTest {
    private class Gateway : ExtractorGateway {
        override fun trending() = items
        var queries = mutableListOf<String>()
        var pages = mutableListOf<Page?>()
        override fun stream(songId: String) = AudioStream("https://example.com/audio", "audio/webm")
        var suggestionsCalls = 0
        var error: Exception? = null
        var failuresLeft = 0
        var items = (1..7).map { MusicItem("$it", "Track $it", "Artist", MusicKind.Song) }
        override fun suggestions(query: String): List<String> {
            suggestionsCalls++
            return listOf(" alpha ", "alpha", "", "beta")
        }
        override fun search(query: String, kind: MusicKind, page: Page?): ExtractedPage {
            queries.add(query)
            pages.add(page)
            error?.let { if (failuresLeft-- > 0) throw it }
            return if (page == null) ExtractedPage(items, Page("https://music.youtube.com/next"))
            else ExtractedPage(listOf(MusicItem("8", "Last track", "Artist", kind)))
        }
    }

    private fun SourceResult<MusicPage>.page(): MusicPage = assertIs<SourceResult.Success<MusicPage>>(this).value

    @Test fun overflowIsPreservedAcrossAppPagesAndThenUpstreamLoads() = runTest {
        val gateway = Gateway()
        val source = NewPipeMusicSource(gateway)
        val one = source.search("  query  ", MusicKind.Song, null, 3).page()
        val two = source.search("query", MusicKind.Song, one.nextPage, 3).page()
        val three = source.search("query", MusicKind.Song, two.nextPage, 3).page()
        val four = source.search("query", MusicKind.Song, three.nextPage, 3).page()
        assertEquals((1..8).map(Int::toString), (one.items + two.items + three.items + four.items).map { it.id })
        assertEquals(2, gateway.queries.size)
        assertTrue(gateway.queries.all { it == "query" })
        assertNotNull(gateway.pages.last())
        assertNull(four.nextPage)
    }

    @Test fun cursorsAreBoundToQueryCategoryAndPageSize() = runTest {
        val source = NewPipeMusicSource(Gateway())
        val token = source.search("a", MusicKind.Song, null, 3).page().nextPage
        listOf(source.search("b", MusicKind.Song, token, 3),
            source.search("a", MusicKind.Album, token, 3), source.search("a", MusicKind.Song, token, 5),
            source.search("a", MusicKind.Song, "expired", 3)).forEach {
            assertEquals(SourceResult.Failure(SourceError.InvalidRequest), it)
        }
    }

    @Test fun cursorStorageEvictsOldEntries() = runTest {
        val source = NewPipeMusicSource(Gateway())
        val first = source.search("first", MusicKind.Song, null, 3).page().nextPage
        repeat(17) { source.search("query$it", MusicKind.Song, null, 3) }
        assertEquals(SourceResult.Failure(SourceError.InvalidRequest), source.search("first", MusicKind.Song, first, 3))
    }

    @Test fun validationAndBlankQueriesAvoidNetwork() = runTest {
        val gateway = Gateway()
        val source = NewPipeMusicSource(gateway)
        assertTrue(source.search(" ", null, null, 30).page().items.isEmpty())
        assertEquals(SourceResult.Failure(SourceError.InvalidRequest), source.search("a", null, null, 0))
        assertEquals(SourceResult.Failure(SourceError.InvalidRequest), source.search("a".repeat(201), null, null, 30))
        assertTrue(gateway.queries.isEmpty())
    }

    @Test fun suggestionsAreNormalizedDeduplicatedAndBounded() = runTest {
        val gateway = Gateway()
        val source = NewPipeMusicSource(gateway)
        assertEquals(SourceResult.Success(emptyList()), source.suggestions("a"))
        assertEquals(0, gateway.suggestionsCalls)
        assertEquals(SourceResult.Success(listOf("alpha", "beta")), source.suggestions("al"))
    }

    @Test fun transientIoRetriesOnceAndRateLimitsDoNotRetry() = runTest {
        val gateway = Gateway().apply { error = IOException(); failuresLeft = 1 }
        assertIs<SourceResult.Success<*>>(NewPipeMusicSource(gateway).search("q", null, null, 30))
        assertEquals(2, gateway.queries.size)
        val limited = Gateway().apply { error = TransportFailure(429); failuresLeft = 10 }
        assertEquals(SourceResult.Failure(SourceError.RateLimited), NewPipeMusicSource(limited).search("q", null, null, 30))
        assertEquals(1, limited.queries.size)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAnError() = runTest {
        val gateway = Gateway().apply { error = CancellationException("cancelled"); failuresLeft = 1 }
        assertFailsWith<CancellationException> { NewPipeMusicSource(gateway).search("q", null, null, 30) }
        assertEquals(1, gateway.queries.size)
    }

    @Test fun errorsMapWithoutLeakingUpstreamMessages() {
        assertEquals(SourceError.VerificationRequired, sourceError(TransportFailure(403)))
        assertEquals(SourceError.Unavailable, sourceError(TransportFailure(404)))
        assertEquals(SourceError.SourceChanged, sourceError(TransportFailure(400)))
        assertEquals(SourceError.SourceChanged, sourceError(IllegalStateException("private parser details")))
        assertEquals(SourceError.Network, sourceError(Exception("wrapper", IOException())))
    }

    @Test fun mapsSongAndDistinguishesAlbumsFromPlaylists() {
        val song = StreamInfoItem(0, "https://www.youtube.com/watch?v=example", "Song", StreamType.AUDIO_STREAM).apply {
            uploaderName = "Performer"; duration = 123
        }
        assertEquals(123L, mapItem(song, MusicKind.Song)?.durationSeconds)
        assertEquals("Performer", mapItem(song, MusicKind.Song)?.subtitle)
        val collection = PlaylistInfoItem(0, "https://music.youtube.com/playlist?list=test", "Collection")
        assertEquals(MusicKind.Album, mapItem(collection, MusicKind.Album)?.kind)
        assertEquals(MusicKind.Playlist, mapItem(collection, MusicKind.Playlist)?.kind)
    }

    @Test fun unsupportedFeaturesStayExplicit() = runTest {
        val source = NewPipeMusicSource(Gateway())
        assertIs<SourceResult.Success<MusicPage>>(source.discover(null, 30))
        assertEquals(SourceResult.Failure(SourceError.InvalidRequest), source.stream("id"))
        assertIs<SourceResult.Success<AudioStream>>(source.stream("https://www.youtube.com/watch?v=example"))
    }

    @Test fun multiRepositoryBadgesAndInterleavingWork() {
        val sc = MusicItem("https://soundcloud.com/artist/track", "SC Track", "Producer", MusicKind.Song)
        val bc = MusicItem("https://artist.bandcamp.com/track/song", "BC Track", "Indie", MusicKind.Song)
        val ytm = MusicItem("https://www.youtube.com/watch?v=1", "Official", "Artist - Topic", MusicKind.Song)
        val yt = MusicItem("https://www.youtube.com/watch?v=2", "Anime OP", "Channel", MusicKind.Song)
        assertEquals("SOUNDCLOUD", sc.repositoryBadge)
        assertEquals("BANDCAMP", bc.repositoryBadge)
        assertEquals("YT MUSIC", ytm.repositoryBadge)
        assertEquals("YOUTUBE", yt.repositoryBadge)
        assertEquals(
            listOf("a1", "b1", "c1", "a2", "b2"),
            interleaveLists(listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1"))),
        )
    }

    @Test fun selectStreamMarksHlsPlaylistAsApplicationMpegUrl() {
        val hlsStream = org.schabi.newpipe.extractor.stream.AudioStream.Builder()
            .setId("sc-hls")
            .setContent("https://cf-hls-media.sndcdn.com/playlist/test.128.mp3/playlist.m3u8?Policy=abc", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MP3)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS)
            .setAverageBitrate(128)
            .build()
        val chosen = selectStream(listOf(hlsStream), AudioQuality.High, AudioFormatPreference.Auto)
        assertNotNull(chosen)
        assertEquals("application/x-mpegURL", chosen.mimeType)
        assertEquals("HLS", chosen.formatLabel)
    }
}
