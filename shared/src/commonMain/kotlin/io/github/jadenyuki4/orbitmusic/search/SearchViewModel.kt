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
    val repository: MusicRepository = MusicRepository.All,
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
    val showAllCategories: Boolean = false,
    val partialSourceWarning: String? = null,
    val selectedCollection: MusicItem? = null,
    val collectionItems: List<MusicItem> = emptyList(),
    val collectionLoading: Boolean = false,
    val collectionError: SourceError? = null,
)

/** Testable state owner. Generation checks also reject sources that ignore cancellation. */
class SearchController(
    internal val source: MusicSource,
    private val scope: CoroutineScope,
    internal val historyStore: HistoryStore = InMemoryHistoryStore(),
    defaultAllCategories: Boolean = false,
) {
    private val mutable = MutableStateFlow(
        SearchState(
            repository = source.repository,
            showAllCategories = defaultAllCategories,
        )
    )
    val state: StateFlow<SearchState> = mutable.asStateFlow()
    private var revision = 0L
    private var collectionRevision = 0L
    private var suggestionsJob: Job? = null
    private var searchJob: Job? = null
    private var collectionJob: Job? = null

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

    private fun resetForQueryOrFilter(
        query: String = mutable.value.query,
        kind: MusicKind = mutable.value.kind,
        repository: MusicRepository = mutable.value.repository,
        showAllCategories: Boolean = mutable.value.showAllCategories,
        submitted: Boolean = false,
    ) {
        val current = mutable.value
        mutable.value = SearchState(
            query = query,
            kind = kind,
            repository = repository,
            history = current.history,
            incognito = current.incognito,
            submitted = submitted,
            showAllCategories = showAllCategories,
        )
    }

    fun selectRepository(repository: MusicRepository) {
        if (repository == mutable.value.repository && source.repository == repository) return
        invalidate()
        closeCollectionInternal()
        source.repository = repository
        resetForQueryOrFilter(repository = repository)
        if (mutable.value.query.isNotBlank()) submit()
    }

    fun changeQuery(value: String) {
        val query = value.take(200)
        if (query == mutable.value.query) return
        invalidate()
        closeCollectionInternal()
        resetForQueryOrFilter(query = query)
        suggest()
    }

    fun selectAllCategories() {
        if (mutable.value.showAllCategories) return
        invalidate()
        closeCollectionInternal()
        resetForQueryOrFilter(showAllCategories = true)
        if (mutable.value.query.isNotBlank()) submit()
    }

    fun selectKind(kind: MusicKind) {
        if (!mutable.value.showAllCategories && kind == mutable.value.kind) return
        invalidate()
        closeCollectionInternal()
        resetForQueryOrFilter(kind = kind, showAllCategories = false)
        if (mutable.value.query.isNotBlank()) submit()
    }

    fun chooseSuggestion(value: String) {
        changeQuery(value)
        submit()
    }

    fun chooseHistory(entry: SearchHistoryEntry) {
        invalidate()
        closeCollectionInternal()
        val query = entry.query.take(200)
        resetForQueryOrFilter(
            query = query,
            kind = entry.kind,
            showAllCategories = false,
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
        if (query.length < 2 || query.startsWith("http://", true) || query.startsWith("https://", true)) {
            mutable.update { it.copy(suggestions = emptyList(), suggestionError = null) }
            return
        }
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
        closeCollectionInternal()
        val old = mutable.value
        val trimmed = old.query.trim()
        if (trimmed.isEmpty()) {
            resetForQueryOrFilter(query = old.query, submitted = false)
            return
        }
        resetForQueryOrFilter(query = old.query, submitted = true)
        val recordKind = if (old.showAllCategories) MusicKind.Song else old.kind
        scope.launch { historyStore.recordSearch(trimmed, recordKind) }
        fetch(append = false)
    }

    fun loadMore() {
        if (mutable.value.nextPage != null && !mutable.value.loading) fetch(append = true)
    }

    fun retry() {
        if (mutable.value.loading) return
        if (mutable.value.error == SourceError.InvalidRequest || mutable.value.items.isEmpty()) submit()
        else fetch(append = mutable.value.nextPage != null)
    }

    fun retrySources() {
        if (mutable.value.loading || mutable.value.query.isBlank()) return
        fetch(append = false)
    }

    fun openCollection(item: MusicItem) {
        if (item.kind == MusicKind.Song) return
        collectionRevision++
        collectionJob?.cancel()
        val generation = collectionRevision
        mutable.update {
            it.copy(
                selectedCollection = item,
                collectionItems = emptyList(),
                collectionLoading = true,
                collectionError = null,
            )
        }
        collectionJob = scope.launch {
            when (val result = source.collectionItems(item)) {
                is SourceResult.Success -> if (generation == collectionRevision) {
                    mutable.update {
                        it.copy(
                            collectionItems = result.value,
                            collectionLoading = false,
                            collectionError = null,
                        )
                    }
                }
                is SourceResult.Failure -> if (generation == collectionRevision) {
                    mutable.update {
                        it.copy(
                            collectionLoading = false,
                            collectionError = result.error,
                        )
                    }
                }
            }
        }
    }

    fun retryCollection() {
        mutable.value.selectedCollection?.let { openCollection(it) }
    }

    fun closeCollection() {
        closeCollectionInternal()
        mutable.update {
            it.copy(
                selectedCollection = null,
                collectionItems = emptyList(),
                collectionLoading = false,
                collectionError = null,
            )
        }
    }

    private fun fetch(append: Boolean) {
        val before = mutable.value
        if (before.query.isBlank()) return
        val generation = revision
        val requestedKind: MusicKind? = if (before.showAllCategories) null else before.kind
        mutable.update {
            it.copy(
                loading = true,
                error = null,
                suggestionError = null,
                suggestions = emptyList(),
                partialSourceWarning = if (append) it.partialSourceWarning else null,
            )
        }
        searchJob = scope.launch {
            val result = source.search(before.query.trim(), requestedKind, if (append) before.nextPage else null, 30)
            if (generation != revision) return@launch
            when (result) {
                is SourceResult.Failure -> mutable.update {
                    it.copy(loading = false, error = result.error)
                }
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
                        partialSourceWarning = result.value.partialWarning ?: if (append) current.partialSourceWarning else null,
                    )
                }
            }
        }
    }

    private fun closeCollectionInternal() {
        collectionRevision++
        collectionJob?.cancel()
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
    val controller = SearchController(source, viewModelScope, historyStore, defaultAllCategories = true)
}

fun submitSearchQuery(model: SearchViewModel, query: String) {
    model.controller.changeQuery(query)
    model.controller.submit()
}

fun SourceError.userMessage(): String = when (this) {
    SourceError.Network -> "Falló la conexión. Verificá tu red e intentá de nuevo."
    SourceError.RateLimited -> "YouTube está limitando las solicitudes. Esperá un momento antes de reintentar."
    SourceError.Unavailable -> "Este contenido no está disponible en tu región o fue eliminado."
    SourceError.Unsupported -> "Esta función aún no está disponible en la fuente de música actual."
    SourceError.SourceChanged -> "La fuente devolvió una respuesta inesperada. Reintentá o actualizá la app si continúa."
    SourceError.InvalidRequest -> "Esta página de búsqueda expiró. Volvé a buscar para actualizar los resultados."
    SourceError.VerificationRequired -> "YouTube requiere verificación. Esta app no puede completarla; intentá más tarde."
}
