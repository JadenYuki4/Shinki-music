// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Filters
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val searchExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable, "shinki-search-${searchThreadCounter.incrementAndGet()}").apply { isDaemon = true }
}
private val searchThreadCounter = AtomicInteger(0)

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
    fun related(songId: String): List<MusicItem> = emptyList()
}

private object ExtractorRuntime {
    // NewPipe's downloader is process-global. Initialize once, lazily on the first request.
    val ready: Unit by lazy { NewPipe.init(UrlConnectionDownloader()) }
}

internal class NewPipeGateway : ExtractorGateway {
    @Volatile
    override var repository: MusicRepository = MusicRepository.All
    private val relatedLock = Any()
    private val relatedCache = object : LinkedHashMap<String, List<MusicItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MusicItem>>?) = size > 32
    }

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
        cacheRelated(songId, extractor)
        val allHttps = extractor.audioStreams.orEmpty().filter {
            it.isUrl && it.content.startsWith("https://")
        }
        val progressive = allHttps.filter {
            it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP
        }.ifEmpty { allHttps }
        return selectStream(progressive, quality, format)
    }

    override fun related(songId: String): List<MusicItem> {
        synchronized(relatedLock) {
            relatedCache[songId]?.let { return it }
        }
        ExtractorRuntime.ready
        val service = runCatching { NewPipe.getServiceByUrl(songId) }.getOrDefault(ServiceList.YouTube)
        val extractor = service.getStreamExtractor(songId)
        extractor.fetchPage()
        return cacheRelated(songId, extractor)
    }

    private fun cacheRelated(
        songId: String,
        extractor: org.schabi.newpipe.extractor.stream.StreamExtractor,
    ): List<MusicItem> {
        val relatedTracks = runCatching {
            val tags = runCatching { extractor.tags.orEmpty().take(6) }.getOrDefault(emptyList())
            val genre = runCatching { extractor.category.takeIf { it.isNotBlank() } }.getOrNull()
            val genres = listOfNotNull(genre)
            extractor.relatedItems?.items.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { info -> mapItem(info, MusicKind.Song)?.copy(genres = genres, tags = tags) }
                .filter { it.kind == MusicKind.Song && it.id != songId }
                .distinctBy { it.id }
                .take(24)
        }.getOrDefault(emptyList())
        if (relatedTracks.isNotEmpty()) {
            synchronized(relatedLock) { relatedCache[songId] = relatedTracks }
        }
        return relatedTracks
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

        val searchRequests: List<Pair<Int, () -> ExtractedPage>> = listOf(
            20 to { searchYouTubeMusic(query, kind, null) },
            25 to { searchYouTubeFull(query, kind, null) },
            15 to { searchService(ServiceList.SoundCloud, query, kind, null) },
            10 to { searchService(ServiceList.Bandcamp, query, kind, null) },
        )
        val futures = searchRequests.map { (_, fetch) ->
            CompletableFuture.supplyAsync({ runCatching(fetch) }, searchExecutor)
        }

        val searchBatches = mutableListOf<List<MusicItem>>()
        var nextPage: Page? = null
        var lastError: Throwable? = null

        futures.forEachIndexed { idx, future ->
            val outcome = future.join()
            val limit = searchRequests[idx].first
            outcome.onSuccess {
                searchBatches += it.items.take(limit)
                if (idx < 2 && nextPage == null) nextPage = it.next
            }.onFailure { err ->
                if (idx == 0 || (idx == 1 && lastError == null)) lastError = err
            }
        }

        val merged = interleaveLists(searchBatches).distinctBy { it.id }.take(80)
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
        val extractedPage = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else extractor.getPage(page)
        if (extractedPage.items.isEmpty() && extractedPage.errors.isNotEmpty()) throw extractedPage.errors.first()
        return ExtractedPage(
            extractedPage.items.mapNotNull { mapItem(it, kind) },
            extractedPage.nextPage.takeIf { Page.isValid(it) },
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
    val isHls = chosen.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS ||
        chosen.content.contains(".m3u8", ignoreCase = true)
    val mime = if (isHls) {
        "application/x-mpegURL"
    } else {
        chosen.format?.mimeType ?: when (format) {
            AudioFormatPreference.M4A -> "audio/mp4"
            AudioFormatPreference.MP3 -> "audio/mpeg"
            else -> "audio/webm"
        }
    }
    val fmtLabel = if (isHls) "HLS" else chosen.format?.suffix?.uppercase() ?: format.shortLabel
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
