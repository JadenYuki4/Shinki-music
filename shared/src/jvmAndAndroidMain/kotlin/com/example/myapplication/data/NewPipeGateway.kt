// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.data

import com.example.myapplication.domain.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Filters

internal data class ExtractedPage(val items: List<MusicItem>, val next: Page? = null)

/** Blocking upstream boundary, injectable without live HTTP in data tests. */
internal interface ExtractorGateway {
    fun stream(songId: String): AudioStream?
    fun suggestions(query: String): List<String>
    fun search(query: String, kind: MusicKind, page: Page?): ExtractedPage
}

private object ExtractorRuntime {
    // NewPipe's downloader is process-global. Initialize once, lazily on the first request.
    val ready: Unit by lazy { NewPipe.init(UrlConnectionDownloader()) }
}

internal class NewPipeGateway : ExtractorGateway {
    override fun stream(songId: String): AudioStream? {
        ExtractorRuntime.ready
        val extractor = ServiceList.YouTube.getStreamExtractor(songId)
        extractor.fetchPage()
        return extractor.audioStreams.orEmpty().filter { it.isUrl && it.content.startsWith("https://") &&
            it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }?.let { AudioStream(it.content, it.format?.mimeType ?: "audio/webm") }
    }

    override fun suggestions(query: String): List<String> {
        ExtractorRuntime.ready
        return ServiceList.YouTube.suggestionExtractor.suggestionList(query)
    }

    override fun search(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        ExtractorRuntime.ready
        val filter = when (kind) {
            MusicKind.Song -> Filters.MUSIC_SONGS
            MusicKind.Album -> Filters.MUSIC_ALBUMS
            MusicKind.Artist -> Filters.MUSIC_ARTISTS
            MusicKind.Playlist -> Filters.MUSIC_PLAYLISTS
        }
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        val result = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else extractor.getPage(page)
        // A parser returning only errors must not masquerade as an empty successful search.
        if (result.items.isEmpty() && result.errors.isNotEmpty()) throw result.errors.first()
        return ExtractedPage(result.items.mapNotNull { mapItem(it, kind) },
            result.nextPage.takeIf { Page.isValid(it) })
    }
}

internal fun mapItem(item: InfoItem, requestedKind: MusicKind): MusicItem? {
    val kind = when (item) {
        is StreamInfoItem -> MusicKind.Song
        is ChannelInfoItem -> MusicKind.Artist
        is PlaylistInfoItem -> if (requestedKind == MusicKind.Album) MusicKind.Album else MusicKind.Playlist
        else -> return null
    }
    if (item.url.isNullOrBlank() || item.name.isNullOrBlank()) return null
    val subtitle = when (item) {
        is StreamInfoItem -> item.uploaderName.orEmpty()
        is PlaylistInfoItem -> item.uploaderName.orEmpty()
        else -> "Artist"
    }
    return MusicItem(item.url, item.name, subtitle, kind,
        item.thumbnails.minByOrNull { if (it.width > 0) kotlin.math.abs(it.width - 160) else Int.MAX_VALUE }?.url,
        (item as? StreamInfoItem)?.duration?.takeIf { it >= 0 })
}
