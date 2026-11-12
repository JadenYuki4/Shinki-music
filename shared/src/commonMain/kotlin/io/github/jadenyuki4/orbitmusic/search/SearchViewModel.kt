// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchState(
    val query: String = "",
    val kind: MusicKind = MusicKind.Song,
    val history: List<SearchHistoryEntry> = emptyList(),
    val incognito: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val suggestionError: SourceError? = null,
    val items: List<MusicItem> = emptyList(),
    val nextPage: String? = null,
    val loading: Boolean = false,
    val submitted: Boolean = false,
    val error: SourceError? = null,
    val reachedLimit: Boolean = false,
)

/** Testable state owner. Generation checks also reject sources that ignore cancellation. */
class SearchController(
    private val source: MusicSource,
    private val scope: CoroutineScope,
    private val historyStore: HistoryStore = InMemoryHistoryStore(),
) {
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable.asStateFlow()
    private var revision = 0L
    private var suggestionsJob: Job? = null
    private var searchJob: Job? = null

    init {
        scope.launch {
            historyStore.observeRecentSearches(limit = 20).collect { recent ->
                mutable.update { it.copy(history = recent) }
            }
        }
        scope.launch {
            historyStore.incognito.collect { enabled ->
                mutable.update { it.copy(incognito = enabled) }
            }
        }
    }

    fun changeQuery(value: String) {
        val query = value.take(200)
        if (query == mutable.value.query) return
        invalidate()
        val current = mutable.value
        mutable.value = SearchState(
            query = query,
            kind = current.kind,
            history = current.history,
            incognito = current.incognito,
        )
        suggest()
    }

    fun selectKind(kind: MusicKind) {
        if (kind == mutable.value.kind) return
        invalidate()
        val current = mutable.value
        mutable.value = SearchState(
            query = current.query,
            kind = kind,
            history = current.history,
            incognito = current.incognito,
        )
        if (mutable.value.query.isNotBlank()) submit()
    }

    fun chooseSuggestion(value: String) {
        changeQuery(value)
        submit()
    }

    fun chooseHistory(entry: SearchHistoryEntry) {
        invalidate()
        val current = mutable.value
        val query = entry.query.take(200)
        mutable.value = SearchState(
            query = query,
            kind = entry.kind,
            history = current.history,
            incognito = current.incognito,
            submitted = true,
        )
        scope.launch { historyStore.recordSearch(query, entry.kind) }
        fetch(append = false)
    }

    fun removeHistory(entry: SearchHistoryEntry) {
        scope.launch { historyStore.removeSearch(entry.query, entry.kind) }
    }

    fun clearHistory() {
        scope.launch { historyStore.clearSearchHistory() }
    }

    fun suggest() {
        suggestionsJob?.cancel()
        val query = mutable.value.query.trim()
        if (query.length < 2) return
        val generation = revision
        suggestionsJob = scope.launch {
            delay(300)
            when (val result = source.suggestions(query)) {
                is SourceResult.Success -> if (generation == revision) mutable.update {
                    it.copy(suggestions = result.value.distinct().take(8), suggestionError = null)
                }
                is SourceResult.Failure -> if (generation == revision) mutable.update {
                    it.copy(suggestions = emptyList(), suggestionError = result.error)
                }
            }
        }
    }

    fun submit() {
        invalidate()
        val old = mutable.value
        val trimmed = old.query.trim()
        if (trimmed.isEmpty()) {
            mutable.value = SearchState(
                query = old.query,
                kind = old.kind,
                history = old.history,
                incognito = old.incognito,
            )
            return
        }
        mutable.value = SearchState(
            query = old.query,
            kind = old.kind,
            history = old.history,
            incognito = old.incognito,
            submitted = true,
        )
        scope.launch { historyStore.recordSearch(trimmed, old.kind) }
        fetch(append = false)
    }

    fun loadMore() {
        if (mutable.value.nextPage != null && !mutable.value.loading) fetch(append = true)
    }

    fun retry() {
        if (mutable.value.loading) return
        if (mutable.value.error == SourceError.InvalidRequest) submit()
        else fetch(append = mutable.value.nextPage != null)
    }

    private fun fetch(append: Boolean) {
        val before = mutable.value
        if (before.query.isBlank()) return
        val generation = revision
        mutable.update { it.copy(loading = true, error = null, suggestionError = null, suggestions = emptyList()) }
        searchJob = scope.launch {
            val result = source.search(before.query.trim(), before.kind, if (append) before.nextPage else null, 30)
            if (generation != revision) return@launch
            when (result) {
                is SourceResult.Failure -> mutable.update { it.copy(loading = false, error = result.error) }
                is SourceResult.Success -> mutable.update { current ->
                    val merged = ((if (append) current.items else emptyList()) + result.value.items).distinctBy { it.id }
                    val capped = merged.size >= 300
                    current.copy(
                        items = merged.take(300),
                        nextPage = if (capped) null else result.value.nextPage,
                        loading = false,
                        submitted = true,
                        error = null,
                        reachedLimit = capped,
                    )
                }
            }
        }
    }

    private fun invalidate() {
        revision++
        suggestionsJob?.cancel()
        searchJob?.cancel()
    }
}

class SearchViewModel(
    source: MusicSource,
    historyStore: HistoryStore = InMemoryHistoryStore(),
) : ViewModel() {
    val controller = SearchController(source, viewModelScope, historyStore)
}

fun SourceError.userMessage(): String = when (this) {
    SourceError.Network -> "Connection failed. Check your network and try again."
    SourceError.RateLimited -> "YouTube is limiting requests. Wait a moment before retrying."
    SourceError.Unavailable -> "This content is unavailable in your region or has been removed."
    SourceError.Unsupported -> "This feature is not available from the current music source yet."
    SourceError.SourceChanged -> "YouTube returned an unexpected response. Retry or update the app if it continues."
    SourceError.InvalidRequest -> "This search page expired. Search again to refresh the results."
    SourceError.VerificationRequired -> "YouTube requires verification. This app cannot complete it; try again later."
}
