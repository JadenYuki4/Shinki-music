// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchState(
    val query: String = "",
    val kind: MusicKind = MusicKind.Song,
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
class SearchController(private val source: MusicSource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable.asStateFlow()
    private var revision = 0L
    private var suggestionsJob: Job? = null
    private var searchJob: Job? = null

    fun changeQuery(value: String) {
        val query = value.take(200)
        if (query == mutable.value.query) return
        invalidate()
        mutable.value = SearchState(query = query, kind = mutable.value.kind)
        suggest()
    }

    fun selectKind(kind: MusicKind) {
        if (kind == mutable.value.kind) return
        invalidate()
        mutable.value = SearchState(query = mutable.value.query, kind = kind)
        if (mutable.value.query.isNotBlank()) submit()
    }

    fun chooseSuggestion(value: String) { changeQuery(value); submit() }

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
        if (old.query.isBlank()) {
            mutable.value = SearchState(query = old.query, kind = old.kind)
            return
        }
        mutable.value = SearchState(query = old.query, kind = old.kind, submitted = true)
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
                    current.copy(items = merged.take(300), nextPage = if (capped) null else result.value.nextPage,
                        loading = false, submitted = true, error = null, reachedLimit = capped)
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

class SearchViewModel(source: MusicSource) : ViewModel() {
    val controller = SearchController(source, viewModelScope)
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
