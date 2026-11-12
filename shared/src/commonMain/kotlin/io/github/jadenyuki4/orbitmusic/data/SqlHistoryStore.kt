// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.db.SqlDriver
import com.russhwolf.settings.Settings
import io.github.jadenyuki4.orbitmusic.db.OrbitDatabase
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * SQLDelight + Multiplatform Settings implementation of [HistoryStore].
 * Keeps search history bounded at [maxSearchEntries] and play history at [maxPlayEntries].
 */
class SqlHistoryStore(
    driver: SqlDriver,
    private val settings: Settings,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val maxSearchEntries: Long = 200L,
    private val maxPlayEntries: Long = 1000L,
) : HistoryStore {
    private val database = OrbitDatabase(driver)
    private val queries = database.orbitDatabaseQueries

    private val incognitoState = MutableStateFlow(settings.getBoolean(KEY_INCOGNITO, false))
    override val incognito: StateFlow<Boolean> = incognitoState.asStateFlow()

    override fun setIncognito(enabled: Boolean) {
        settings.putBoolean(KEY_INCOGNITO, enabled)
        incognitoState.value = enabled
    }

    override fun observeRecentSearches(limit: Int): Flow<List<SearchHistoryEntry>> =
        queries.recentSearches(limit.coerceIn(1, 200).toLong()) { query, kind, searchedAt ->
            SearchHistoryEntry(
                query = query,
                kind = parseKind(kind),
                searchedAtEpochMillis = searchedAt,
            )
        }.asFlow().mapToList(ioDispatcher)

    override suspend fun recordSearch(query: String, kind: MusicKind) {
        if (incognitoState.value) return
        val clean = query.trim().take(200)
        if (clean.isEmpty()) return
        val now = clockMillis()
        withContext(ioDispatcher) {
            queries.transaction {
                queries.upsertSearch(clean, kind.name, now)
                queries.trimSearches(maxSearchEntries)
            }
        }
    }

    override suspend fun removeSearch(query: String, kind: MusicKind) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        withContext(ioDispatcher) {
            queries.deleteSearch(clean, kind.name)
        }
    }

    override suspend fun clearSearchHistory() {
        withContext(ioDispatcher) {
            queries.clearSearches()
        }
    }

    override fun observeRecentPlays(limit: Int): Flow<List<PlayEventRecord>> =
        queries.recentPlays(limit.coerceIn(1, 500).toLong()) { id, songId, title, subtitle, artworkUrl, durationSeconds, playedSeconds, completionRatio, skipped, playedAt ->
            PlayEventRecord(
                id = id,
                item = MusicItem(
                    id = songId,
                    title = title,
                    subtitle = subtitle,
                    kind = MusicKind.Song,
                    artworkUrl = artworkUrl,
                    durationSeconds = durationSeconds,
                ),
                playedSeconds = playedSeconds,
                completionRatio = completionRatio,
                skipped = skipped != 0L,
                playedAtEpochMillis = playedAt,
            )
        }.asFlow().mapToList(ioDispatcher)

    override suspend fun recordPlay(
        item: MusicItem,
        playedSeconds: Long,
        completionRatio: Double,
        skipped: Boolean,
    ) {
        if (incognitoState.value) return
        if (item.id.isBlank() || item.title.isBlank()) return
        val now = clockMillis()
        val safePlayed = playedSeconds.coerceAtLeast(0L)
        val safeRatio = completionRatio.coerceIn(0.0, 1.0)
        withContext(ioDispatcher) {
            queries.transaction {
                queries.insertPlayEvent(
                    songId = item.id,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    durationSeconds = item.durationSeconds,
                    playedSeconds = safePlayed,
                    completionRatio = safeRatio,
                    skipped = if (skipped) 1L else 0L,
                    playedAt = now,
                )
                queries.trimPlays(maxPlayEntries)
            }
        }
    }

    override suspend fun clearPlayHistory() {
        withContext(ioDispatcher) {
            queries.clearPlays()
        }
    }

    override fun observeFavorites(): Flow<List<MusicItem>> =
        queries.allFavorites { songId, title, subtitle, artworkUrl, durationSeconds, _ ->
            MusicItem(
                id = songId,
                title = title,
                subtitle = subtitle,
                kind = MusicKind.Song,
                artworkUrl = artworkUrl,
                durationSeconds = durationSeconds,
            )
        }.asFlow().mapToList(ioDispatcher)

    override fun observeIsFavorite(songId: String): Flow<Boolean> =
        queries.isFavorite(songId).asFlow().mapToOne(ioDispatcher)

    override suspend fun toggleFavorite(item: MusicItem): Boolean = withContext(ioDispatcher) {
        var added = false
        val now = clockMillis()
        queries.transaction {
            val exists = queries.isFavorite(item.id).executeAsOne()
            if (exists) {
                queries.deleteFavorite(item.id)
                added = false
            } else {
                queries.upsertFavorite(
                    songId = item.id,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    durationSeconds = item.durationSeconds,
                    addedAt = now,
                )
                added = true
            }
        }
        added
    }

    override suspend fun clearFavorites() {
        withContext(ioDispatcher) {
            queries.clearFavorites()
        }
    }

    private fun parseKind(raw: String): MusicKind =
        MusicKind.entries.firstOrNull { it.name == raw } ?: MusicKind.Song

    companion object {
        const val KEY_INCOGNITO = "orbit.history.incognito"
    }
}

/** In-memory no-op store for IDE previews and tests that do not supply a database driver. */
class InMemoryHistoryStore : HistoryStore {
    private val incognitoState = MutableStateFlow(false)
    private val searches = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    private val plays = MutableStateFlow<List<PlayEventRecord>>(emptyList())
    private val favorites = MutableStateFlow<List<MusicItem>>(emptyList())
    private var clock = 1L

    override val incognito: StateFlow<Boolean> = incognitoState.asStateFlow()

    override fun setIncognito(enabled: Boolean) {
        incognitoState.value = enabled
    }

    override fun observeRecentSearches(limit: Int): Flow<List<SearchHistoryEntry>> =
        searches.map { it.take(limit) }

    override suspend fun recordSearch(query: String, kind: MusicKind) {
        if (incognitoState.value) return
        val clean = query.trim().take(200)
        if (clean.isEmpty()) return
        val entry = SearchHistoryEntry(clean, kind, clock++)
        searches.update { current ->
            (listOf(entry) + current.filterNot { it.query == clean && it.kind == kind }).take(200)
        }
    }

    override suspend fun removeSearch(query: String, kind: MusicKind) {
        val clean = query.trim()
        searches.update { current -> current.filterNot { it.query == clean && it.kind == kind } }
    }

    override suspend fun clearSearchHistory() {
        searches.value = emptyList()
    }

    override fun observeRecentPlays(limit: Int): Flow<List<PlayEventRecord>> =
        plays.map { it.take(limit) }

    override suspend fun recordPlay(
        item: MusicItem,
        playedSeconds: Long,
        completionRatio: Double,
        skipped: Boolean,
    ) {
        if (incognitoState.value) return
        val id = clock++
        val record = PlayEventRecord(
            id = id,
            item = item,
            playedSeconds = playedSeconds.coerceAtLeast(0L),
            completionRatio = completionRatio.coerceIn(0.0, 1.0),
            skipped = skipped,
            playedAtEpochMillis = id,
        )
        plays.update { current -> (listOf(record) + current).take(1000) }
    }

    override suspend fun clearPlayHistory() {
        plays.value = emptyList()
    }

    override fun observeFavorites(): Flow<List<MusicItem>> = favorites.asStateFlow()

    override fun observeIsFavorite(songId: String): Flow<Boolean> =
        favorites.map { list -> list.any { it.id == songId } }

    override suspend fun toggleFavorite(item: MusicItem): Boolean {
        var added = false
        favorites.update { current ->
            if (current.any { it.id == item.id }) {
                added = false
                current.filterNot { it.id == item.id }
            } else {
                added = true
                listOf(item) + current
            }
        }
        return added
    }

    override suspend fun clearFavorites() {
        favorites.value = emptyList()
    }
}
