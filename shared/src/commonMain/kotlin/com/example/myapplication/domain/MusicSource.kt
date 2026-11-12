// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.domain

/** Source-neutral models; IDs belong to the source and are not cached audio URLs. */
enum class MusicKind { Song, Album, Artist, Playlist }

data class MusicItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: MusicKind,
    val artworkUrl: String? = null,
    val durationSeconds: Long? = null,
)

data class MusicPage(val items: List<MusicItem>, val nextPage: String? = null)
data class AudioStream(val url: String, val mimeType: String, val expiresAtEpochSeconds: Long? = null)

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceError) : SourceResult<Nothing>
}
enum class SourceError { Network, RateLimited, Unavailable, Unsupported, SourceChanged, InvalidRequest, VerificationRequired }

/** Implementations must honor cancellation; a page is capped at [pageSize] results. */
interface MusicSource {
    suspend fun suggestions(query: String): SourceResult<List<String>>
    suspend fun search(query: String, kind: MusicKind?, page: String? = null,
        pageSize: Int = 30): SourceResult<MusicPage>
    suspend fun discover(page: String? = null, pageSize: Int = 30): SourceResult<MusicPage>
    suspend fun stream(songId: String): SourceResult<AudioStream>
}
