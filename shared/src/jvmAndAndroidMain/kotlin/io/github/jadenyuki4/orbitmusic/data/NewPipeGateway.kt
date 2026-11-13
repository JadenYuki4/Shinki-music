// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Filters

internal data class ExtractedPage(val items: List<MusicItem>, val next: Page? = null)

/** Blocking upstream boundary, injectable without live HTTP in data tests. */
internal interface ExtractorGateway {
    var repository: MusicRepository
        get() = MusicRepository.All
        set(_) {}

    fun trending(): List<MusicItem>
    fun stream(songId: String): AudioStream?
    fun stream(
        songId: String,
        quality: AudioQuality,
        format: AudioFormatPreference = AudioFormatPreference.Auto,
    ): AudioStream? = stream(songId)
    fun suggestions(query: String): List<String>
    fun search(query: String, kind: MusicKind, page: Page?): ExtractedPage
}

private object ExtractorRuntime {
    // NewPipe's downloader is process-global. Initialize once, lazily on the first request.
    val ready: Unit by lazy { NewPipe.init(UrlConnectionDownloader()) }
}

internal class NewPipeGateway : ExtractorGateway {
    @Volatile
    override var repository: MusicRepository = MusicRepository.All

    override fun trending(): List<MusicItem> {
        ExtractorRuntime.ready
        val ytItems = runCatching {
            val id = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID
            val extractor = ServiceList.YouTube.kioskList.getExtractorById(id, null)
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { mapItem(it, MusicKind.Song) }
        }.getOrDefault(emptyList())

        val scItems = runCatching {
            val extractor = ServiceList.SoundCloud.kioskList.defaultKioskExtractor
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { mapItem(it, MusicKind.Song) }.take(15)
        }.getOrDefault(emptyList())

        return interleaveLists(listOf(ytItems, scItems)).distinctBy { it.id }
    }

    override fun stream(songId: String): AudioStream? =
        stream(songId, AudioQuality.High, AudioFormatPreference.Auto)

    override fun stream(
        songId: String,
        quality: AudioQuality,
        format: AudioFormatPreference,
    ): AudioStream? {
        ExtractorRuntime.ready
        val service = runCatching { NewPipe.getServiceByUrl(songId) }.getOrDefault(ServiceList.YouTube)
        val extractor = service.getStreamExtractor(songId)
        extractor.fetchPage()
        val allHttps = extractor.audioStreams.orEmpty().filter {
            it.isUrl && it.content.startsWith("https://")
        }
        val progressive = allHttps.filter {
            it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP
        }.ifEmpty { allHttps }
        return selectStream(progressive, quality, format)
    }

    override fun suggestions(query: String): List<String> {
        ExtractorRuntime.ready
        val yt = runCatching { ServiceList.YouTube.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
        val sc = if (repository == MusicRepository.SoundCloud || repository == MusicRepository.All) {
            runCatching { ServiceList.SoundCloud.suggestionExtractor.suggestionList(query).take(4) }.getOrDefault(emptyList())
        } else emptyList()
        return (yt + sc).distinct()
    }

    override fun search(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        ExtractorRuntime.ready
        return when (repository) {
            MusicRepository.YouTubeMusic -> searchYouTubeMusic(query, kind, page)
            MusicRepository.YouTubeVideos -> searchYouTubeFull(query, kind, page)
            MusicRepository.SoundCloud -> searchService(ServiceList.SoundCloud, query, kind, page)
            MusicRepository.Bandcamp -> searchService(ServiceList.Bandcamp, query, kind, page)
            MusicRepository.All -> searchAllRepositories(query, kind, page)
        }
    }

    private fun searchYouTubeMusic(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        val filter = when (kind) {
            MusicKind.Song -> Filters.MUSIC_SONGS
            MusicKind.Album -> Filters.MUSIC_ALBUMS
            MusicKind.Artist -> Filters.MUSIC_ARTISTS
            MusicKind.Playlist -> Filters.MUSIC_PLAYLISTS
        }
        return executeSearch(ServiceList.YouTube, query, listOf(filter), kind, page)
    }

    private fun searchYouTubeFull(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        val filter = when (kind) {
            MusicKind.Song -> Filters.VIDEOS
            MusicKind.Album, MusicKind.Playlist -> Filters.PLAYLISTS
            MusicKind.Artist -> Filters.CHANNELS
        }
        return executeSearch(ServiceList.YouTube, query, listOf(filter), kind, page)
    }

    private fun searchService(service: StreamingService, query: String, kind: MusicKind, page: Page?): ExtractedPage {
        val availableFilters = service.searchQHFactory.availableContentFilter.orEmpty().toList()
        val chosenFilter = when (kind) {
            MusicKind.Song -> availableFilters.firstOrNull { it.contains("track", true) || it.contains("song", true) || it.contains("stream", true) }
            MusicKind.Album -> availableFilters.firstOrNull { it.contains("album", true) || it.contains("playlist", true) }
            MusicKind.Artist -> availableFilters.firstOrNull { it.contains("user", true) || it.contains("artist", true) || it.contains("channel", true) }
            MusicKind.Playlist -> availableFilters.firstOrNull { it.contains("playlist", true) || it.contains("album", true) }
        }
        val filters = listOfNotNull(chosenFilter)
        return executeSearch(service, query, filters, kind, page)
    }

    private fun searchAllRepositories(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        // When paginating with an upstream YouTube Page token, continue on YouTube Full/Music
        if (page != null) {
            return runCatching { searchYouTubeFull(query, kind, page) }
                .recoverCatching { searchYouTubeMusic(query, kind, page) }
                .getOrThrow()
        }

        val results = mutableListOf<List<MusicItem>>()
        var nextPage: Page? = null
        var lastError: Throwable? = null

        // 1. YouTube Music official catalog
        runCatching { searchYouTubeMusic(query, kind, null) }
            .onSuccess {
                results += it.items.take(20)
                if (nextPage == null) nextPage = it.next
            }
            .onFailure { lastError = it }

        // 2. YouTube Complete Video Catalog (captures all songs, anime OSTs, covers, remixes, live versions missing in YT Music)
        runCatching { searchYouTubeFull(query, kind, null) }
            .onSuccess {
                results += it.items.take(25)
                if (nextPage == null) nextPage = it.next
            }
            .onFailure { if (lastError == null) lastError = it }

        // 3. SoundCloud Repository
        runCatching { searchService(ServiceList.SoundCloud, query, kind, null) }
            .onSuccess { results += it.items.take(15) }

        // 4. Bandcamp Repository
        runCatching { searchService(ServiceList.Bandcamp, query, kind, null) }
            .onSuccess { results += it.items.take(10) }

        val merged = interleaveLists(results).distinctBy { it.id }.take(80)
        if (merged.isEmpty() && lastError != null) throw lastError
        return ExtractedPage(merged, nextPage)
    }

    private fun executeSearch(
        service: StreamingService,
        query: String,
        filters: List<String>,
        kind: MusicKind,
        page: Page?,
    ): ExtractedPage {
        val extractor = service.getSearchExtractor(query, filters, "")
        val result = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else extractor.getPage(page)
        if (result.items.isEmpty() && result.errors.isNotEmpty()) throw result.errors.first()
        return ExtractedPage(
            result.items.mapNotNull { mapItem(it, kind) },
            result.nextPage.takeIf { Page.isValid(it) },
        )
    }
}

internal fun <T> interleaveLists(batches: List<List<T>>): List<T> = buildList {
    val maxLen = batches.maxOfOrNull { it.size } ?: 0
    for (i in 0 until maxLen) {
        for (batch in batches) {
            if (i < batch.size) add(batch[i])
        }
    }
}

internal fun selectStream(
    streams: List<org.schabi.newpipe.extractor.stream.AudioStream>,
    quality: AudioQuality,
    format: AudioFormatPreference,
): AudioStream? {
    if (streams.isEmpty()) return null
    val formatFiltered = when (format) {
        AudioFormatPreference.Auto -> streams
        AudioFormatPreference.Opus -> streams.filter {
            val mime = it.format?.mimeType.orEmpty().lowercase()
            val codec = it.codec.orEmpty().lowercase()
            mime.contains("webm") || codec.contains("opus")
        }
        AudioFormatPreference.M4A -> streams.filter {
            val mime = it.format?.mimeType.orEmpty().lowercase()
            val codec = it.codec.orEmpty().lowercase()
            mime.contains("mp4") || mime.contains("m4a") || codec.contains("mp4a") || codec.contains("aac")
        }
        AudioFormatPreference.MP3 -> streams.filter {
            val mime = it.format?.mimeType.orEmpty().lowercase()
            val codec = it.codec.orEmpty().lowercase()
            mime.contains("mpeg") || mime.contains("mp3") || codec.contains("mp3")
        }
    }.ifEmpty { streams }

    val chosen = when (quality) {
        AudioQuality.High -> formatFiltered.maxByOrNull { it.averageBitrate }
        AudioQuality.Medium -> formatFiltered.minByOrNull {
            val br = if (it.averageBitrate > 0) it.averageBitrate else quality.targetBitrateKbps
            kotlin.math.abs(br - quality.targetBitrateKbps)
        }
        AudioQuality.Low -> formatFiltered.minByOrNull {
            if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE
        }
    } ?: return null

    val bitrate = chosen.averageBitrate.takeIf { it > 0 } ?: quality.targetBitrateKbps
    val mime = chosen.format?.mimeType ?: when (format) {
        AudioFormatPreference.M4A -> "audio/mp4"
        AudioFormatPreference.MP3 -> "audio/mpeg"
        else -> "audio/webm"
    }
    val fmtLabel = chosen.format?.suffix?.uppercase() ?: format.shortLabel
    val bytesPerMin = (bitrate.toLong() * 1000L / 8L) * 60L
    return AudioStream(
        url = chosen.content,
        mimeType = mime,
        bitrateKbps = bitrate,
        codec = chosen.codec,
        formatLabel = fmtLabel,
        approxBytesPerMinute = bytesPerMin,
    )
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
        item.thumbnails.minByOrNull { if (it.width > 0) kotlin.math.abs(it.width - 320) else Int.MAX_VALUE }?.url,
        (item as? StreamInfoItem)?.duration?.takeIf { it >= 0 })
}
