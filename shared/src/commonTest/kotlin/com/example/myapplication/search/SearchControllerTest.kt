// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.search

import com.example.myapplication.domain.*
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
}
