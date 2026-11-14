// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.search

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    private open class Source : MusicSource {
        val suggested = mutableListOf<String>()
        val searched = mutableListOf<Triple<String, MusicKind?, String?>>()
        var failure: SourceError? = null
        var slow = false
        override suspend fun suggestions(query: String): SourceResult<List<String>> {
            suggested.add(query)
            if (slow && query == "old") withContext(NonCancellable) { delay(1000) }
            return SourceResult.Success(listOf(query))
        }
        override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
            searched.add(Triple(query, kind, page))
            failure?.let { return SourceResult.Failure(it) }
            return SourceResult.Success(MusicPage(listOf(MusicItem(page ?: "first", query, "Artist", kind ?: MusicKind.Song)),
                if (page == null) "next" else null))
        }
        override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Failure(SourceError.Unsupported)
        override suspend fun stream(songId: String) = SourceResult.Failure(SourceError.Unsupported)
    }

    @Test fun suggestionsDebounceAndLatestQueryWins() = runTest {
        val source = Source()
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("da")
        advanceTimeBy(200)
        controller.changeQuery("daft")
        advanceTimeBy(299)
        assertTrue(source.suggested.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("daft"), source.suggested)
    }

    @Test fun ignoresLateSuggestionFromNonCooperativeSource() = runTest {
        val source = Source().apply { slow = true }
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("old")
        advanceTimeBy(301)
        controller.changeQuery("new")
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf("new"), controller.state.value.suggestions)
    }

    @Test fun paginationRetryPreservesExistingResultsAndCategoryChangeClearsThem() = runTest {
        val source = Source()
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery(" q "); controller.submit(); runCurrent()
        source.failure = SourceError.Network
        controller.loadMore(); runCurrent()
        assertEquals(1, controller.state.value.items.size)
        assertEquals(SourceError.Network, controller.state.value.error)
        source.failure = null
        controller.retry(); runCurrent()
        assertEquals(2, controller.state.value.items.size)
        assertEquals("next", source.searched.last().third)
        controller.selectKind(MusicKind.Album); runCurrent()
        assertEquals(1, controller.state.value.items.size)
        assertEquals(MusicKind.Album, controller.state.value.items.single().kind)
    }

    @Test fun expiredCursorRetriesFromFirstPage() = runTest {
        val source = Source()
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("q"); controller.submit(); runCurrent()
        source.failure = SourceError.InvalidRequest
        controller.loadMore(); runCurrent()
        source.failure = null
        controller.retry(); runCurrent()
        assertNull(source.searched.last().third)
        assertEquals(1, controller.state.value.items.size)
    }

    @Test fun clearingSearchCancelsAndResetsResults() = runTest {
        val source = Source()
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("query"); controller.submit(); runCurrent()
        controller.changeQuery("")
        advanceTimeBy(1000); runCurrent()
        assertTrue(controller.state.value.items.isEmpty())
        assertFalse(controller.state.value.submitted)
        assertTrue(source.suggested.isEmpty())
    }

    @Test fun lateSearchCannotReplaceANewerQuery() = runTest {
        val source = object : Source() {
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                if (query == "old") withContext(NonCancellable) { delay(1000) }
                return super.search(query, kind, page, pageSize)
            }
        }
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("old"); controller.submit(); runCurrent()
        controller.changeQuery("new"); controller.submit(); runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals("new", controller.state.value.items.single().title)
    }

    @Test fun resultRetentionStopsAtThreeHundred() = runTest {
        val source = object : Source() {
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                val start = page?.toInt() ?: 0
                return SourceResult.Success(MusicPage((start until start + 30).map {
                    MusicItem("$it", "Track", "Artist", MusicKind.Song)
                }, "${start + 30}"))
            }
        }
        val controller = SearchController(source, backgroundScope)
        controller.changeQuery("q"); controller.submit(); runCurrent()
        repeat(12) { controller.loadMore(); runCurrent() }
        assertEquals(300, controller.state.value.items.size)
        assertNull(controller.state.value.nextPage)
        assertTrue(controller.state.value.reachedLimit)
    }

    @Test fun submitRecordsSearchHistoryAndChooseHistoryReSubmits() = runTest {
        val source = Source()
        val history = io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore()
        val controller = SearchController(source, backgroundScope, history)
        runCurrent()
        controller.changeQuery("  synthwave  ")
        controller.submit()
        runCurrent()
        assertEquals(1, controller.state.value.history.size)
        val entry = controller.state.value.history.single()
        assertEquals("synthwave", entry.query)
        assertEquals(MusicKind.Song, entry.kind)

        controller.changeQuery("")
        runCurrent()
        assertFalse(controller.state.value.submitted)
        assertEquals(1, controller.state.value.history.size)

        controller.chooseHistory(entry)
        runCurrent()
        assertTrue(controller.state.value.submitted)
        assertEquals("synthwave", controller.state.value.query)
        assertEquals(1, controller.state.value.items.size)

        controller.removeHistory(entry)
        runCurrent()
        assertTrue(controller.state.value.history.isEmpty())
    }

    @Test fun incognitoDoesNotRecordSubmittedSearches() = runTest {
        val source = Source()
        val history = io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore()
        history.setIncognito(true)
        val controller = SearchController(source, backgroundScope, history)
        runCurrent()
        assertTrue(controller.state.value.incognito)
        controller.changeQuery("secret query")
        controller.submit()
        runCurrent()
        assertTrue(controller.state.value.history.isEmpty())
    }

    @Test fun allCategoriesModePassesNullKindAndKeepsMixedResults() = runTest {
        val mixedItems = listOf(
            MusicItem("s1", "Si no es muy tarde", "Luciano Pereyra", MusicKind.Song, sourceName = "YT Music"),
            MusicItem("a1", "Si no es muy tarde", "Luciano Pereyra", MusicKind.Album, sourceName = "YT Music"),
            MusicItem("ar1", "Luciano Pereyra", "Artist", MusicKind.Artist, sourceName = "YouTube"),
            MusicItem("p1", "Éxitos latinos", "Shinki", MusicKind.Playlist, sourceName = "SoundCloud"),
        )
        val source = object : Source() {
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                searched.add(Triple(query, kind, page))
                val items = if (kind == null) mixedItems else mixedItems.filter { it.kind == kind }
                return SourceResult.Success(MusicPage(items))
            }
        }
        val controller = SearchController(source, backgroundScope, defaultAllCategories = true)
        assertTrue(controller.state.value.showAllCategories)
        controller.changeQuery("si no es muy tarde")
        controller.submit()
        runCurrent()

        assertNull(source.searched.last().second)
        assertEquals(4, controller.state.value.items.size)

        controller.selectKind(MusicKind.Album)
        runCurrent()
        assertFalse(controller.state.value.showAllCategories)
        assertEquals(listOf("a1"), controller.state.value.items.map { it.id })

        controller.selectAllCategories()
        runCurrent()
        assertTrue(controller.state.value.showAllCategories)
        assertEquals(4, controller.state.value.items.size)
    }

    @Test fun partialSourceWarningIsPreservedAlongsideResults() = runTest {
        var attempt = 0
        val source = object : Source() {
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                attempt++
                val warning = if (attempt == 1) "No respondieron: SoundCloud" else null
                return SourceResult.Success(
                    MusicPage(
                        items = listOf(MusicItem("s1", query, "Artista", MusicKind.Song, sourceName = "YT Music")),
                        partialWarning = warning,
                    )
                )
            }
        }
        val controller = SearchController(source, backgroundScope, defaultAllCategories = true)
        controller.changeQuery("yoasobi")
        controller.submit()
        runCurrent()

        assertEquals(1, controller.state.value.items.size)
        assertEquals("No respondieron: SoundCloud", controller.state.value.partialSourceWarning)

        controller.retrySources()
        runCurrent()
        assertNull(controller.state.value.partialSourceWarning)
    }

    @Test fun openAndCloseCollectionPreservesQueryFiltersAndResults() = runTest {
        val album = MusicItem("https://example.org/playlist?list=1", "LIVE", "Kenshi", MusicKind.Album)
        val song = MusicItem("https://example.org/watch?v=1", "Kick Back", "Kenshi", MusicKind.Song)
        val colTrack = MusicItem("https://example.org/watch?v=2", "Lady", "Kenshi", MusicKind.Song)
        val source = object : Source() {
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                return SourceResult.Success(MusicPage(listOf(song, album)))
            }

            override suspend fun collectionItems(item: MusicItem): SourceResult<List<MusicItem>> {
                return SourceResult.Success(listOf(colTrack))
            }
        }
        val controller = SearchController(source, backgroundScope, defaultAllCategories = true)
        controller.changeQuery("kenshi")
        controller.submit()
        runCurrent()

        controller.openCollection(album)
        runCurrent()
        assertEquals(album, controller.state.value.selectedCollection)
        assertEquals(listOf(colTrack), controller.state.value.collectionItems)
        assertEquals("kenshi", controller.state.value.query)

        controller.closeCollection()
        assertNull(controller.state.value.selectedCollection)
        assertEquals("kenshi", controller.state.value.query)
        assertEquals(listOf(song, album), controller.state.value.items)
        assertTrue(controller.state.value.showAllCategories)
    }
}
