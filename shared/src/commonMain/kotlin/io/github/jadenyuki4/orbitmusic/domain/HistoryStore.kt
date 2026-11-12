// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SearchHistoryEntry(
    val query: String,
    val kind: MusicKind,
    val searchedAtEpochMillis: Long,
)

data class PlayEventRecord(
    val id: Long = 0L,
    val item: MusicItem,
    val playedSeconds: Long,
    val completionRatio: Double,
    val skipped: Boolean,
    val playedAtEpochMillis: Long,
)

/**
 * Local, privacy-first history and favorites store.
 * When [incognito] is true, [recordSearch] and [recordPlay] are no-ops.
 */
interface HistoryStore {
    val incognito: StateFlow<Boolean>
    fun setIncognito(enabled: Boolean)

    fun observeRecentSearches(limit: Int = 20): Flow<List<SearchHistoryEntry>>
    suspend fun recordSearch(query: String, kind: MusicKind)
    suspend fun removeSearch(query: String, kind: MusicKind)
    suspend fun clearSearchHistory()

    fun observeRecentPlays(limit: Int = 50): Flow<List<PlayEventRecord>>
    suspend fun recordPlay(
        item: MusicItem,
        playedSeconds: Long,
        completionRatio: Double,
        skipped: Boolean,
    )
    suspend fun clearPlayHistory()

    fun observeFavorites(): Flow<List<MusicItem>>
    fun observeIsFavorite(songId: String): Flow<Boolean>
    suspend fun toggleFavorite(item: MusicItem): Boolean
    suspend fun clearFavorites()
}
