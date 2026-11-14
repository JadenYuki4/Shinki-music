// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Filters
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val searchExecutor = Executors.newFixedThreadPool(8) { runnable ->
    Thread(runnable, "shinki-search-${searchThreadCounter.incrementAndGet()}").apply { isDaemon = true }
}
private val categoryExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable, "shinki-cat-${searchThreadCounter.incrementAndGet()}").apply { isDaemon = true }
}
private val searchThreadCounter = AtomicInteger(0)

internal data class ExtractedPage(
    val items: List<MusicItem>,
    val next: Page? = null,
    val failedSources: List<String> = emptyList(),
)

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
    fun searchUnified(query: String, page: Page?): ExtractedPage = search(query, MusicKind.Song, page)
    fun collectionItems(item: MusicItem): List<MusicItem> = emptyList()
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
        }.onFailure { io.github.jadenyuki4.orbitmusic.logRelatedFailure(it) }.getOrDefault(emptyList())
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
        val trimmed = query.trim()
        if (page == null && isDirectUrl(trimmed)) {
            return resolveUrl(trimmed)
        }
        return when (repository) {
            MusicRepository.YouTubeMusic -> searchYouTubeMusic(trimmed, kind, page)
            MusicRepository.YouTubeVideos -> searchYouTubeFull(trimmed, kind, page)
            MusicRepository.SoundCloud -> searchService(ServiceList.SoundCloud, trimmed, kind, page)
            MusicRepository.Bandcamp -> searchService(ServiceList.Bandcamp, trimmed, kind, page)
            MusicRepository.All -> searchAllRepositories(trimmed, kind, page)
        }
    }

    override fun searchUnified(query: String, page: Page?): ExtractedPage {
        ExtractorRuntime.ready
        val trimmed = query.trim()
        if (page != null) return search(trimmed, MusicKind.Song, page)
        if (isDirectUrl(trimmed)) return resolveUrl(trimmed)

        val songFuture = CompletableFuture.supplyAsync({ runCatching { search(trimmed, MusicKind.Song, null) } }, categoryExecutor)
        val albumFuture = CompletableFuture.supplyAsync({ runCatching { search(trimmed, MusicKind.Album, null) } }, categoryExecutor)
        val artistFuture = CompletableFuture.supplyAsync({ runCatching { search(trimmed, MusicKind.Artist, null) } }, categoryExecutor)
        val playlistFuture = CompletableFuture.supplyAsync({ runCatching { search(trimmed, MusicKind.Playlist, null) } }, categoryExecutor)

        val songResult = songFuture.join()
        val albumResult = albumFuture.join()
        val artistResult = artistFuture.join()
        val playlistResult = playlistFuture.join()

        val songsPage = songResult.getOrNull()
        val albumsPage = albumResult.getOrNull()
        val artistsPage = artistResult.getOrNull()
        val playlistsPage = playlistResult.getOrNull()

        val combined = buildList {
            songsPage?.items?.let { addAll(it) }
            albumsPage?.items?.take(10)?.let { addAll(it) }
            artistsPage?.items?.take(8)?.let { addAll(it) }
            playlistsPage?.items?.take(8)?.let { addAll(it) }
        }.distinctBy { it.id }

        if (combined.isEmpty()) {
            songResult.exceptionOrNull()?.let { throw it }
            albumResult.exceptionOrNull()?.let { throw it }
            artistResult.exceptionOrNull()?.let { throw it }
            playlistResult.exceptionOrNull()?.let { throw it }
        }

        val failed = songsPage?.failedSources?.distinct()
            ?: (
                albumsPage?.failedSources.orEmpty() +
                    artistsPage?.failedSources.orEmpty() +
                    playlistsPage?.failedSources.orEmpty()
                ).distinct()

        return ExtractedPage(
            items = combined,
            next = songsPage?.next,
            failedSources = failed,
        )
    }

    override fun collectionItems(item: MusicItem): List<MusicItem> {
        ExtractorRuntime.ready
        val url = item.id
        val service = runCatching { NewPipe.getServiceByUrl(url) }.getOrDefault(ServiceList.YouTube)
        val sourceLabel = resolveSourceLabel(service, url, item.sourceName)
        return when (item.kind) {
            MusicKind.Album, MusicKind.Playlist -> {
                val info = PlaylistInfo.getInfo(service, url)
                val collector = mutableListOf<MusicItem>()
                info.relatedItems.orEmpty().mapNotNullTo(collector) {
                    mapItem(it, MusicKind.Song, sourceLabel)
                }
                collector.distinctBy { it.id }.take(80)
            }
            MusicKind.Artist -> {
                val info = ChannelInfo.getInfo(service, url)
                loadChannelItems(service, info, sourceLabel)
            }
            MusicKind.Song -> emptyList()
        }
    }

    private fun isDirectUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private fun resolveSourceLabel(service: StreamingService, url: String, fallback: String? = null): String =
        fallback ?: when (service.serviceId) {
            ServiceList.SoundCloud.serviceId -> "SoundCloud"
            ServiceList.Bandcamp.serviceId -> "Bandcamp"
            ServiceList.YouTube.serviceId -> if (url.contains("music.youtube.com", ignoreCase = true)) "YT Music" else "YouTube"
            else -> service.serviceInfo.name
        }

    private fun resolveUrl(url: String): ExtractedPage {
        val service = NewPipe.getServiceByUrl(url)
        val sourceLabel = resolveSourceLabel(service, url)
        val linkType = service.getLinkTypeByUrl(url)
        val items = when (linkType) {
            StreamingService.LinkType.STREAM -> {
                val info = StreamInfo.getInfo(service, url)
                val thumb = info.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0).coerceAtLeast(1) }?.url
                listOf(
                    MusicItem(
                        id = url,
                        title = info.name.orEmpty().ifBlank { url },
                        subtitle = info.uploaderName.orEmpty(),
                        kind = MusicKind.Song,
                        artworkUrl = thumb?.let { io.github.jadenyuki4.orbitmusic.upgradeArtworkUrl(it) },
                        durationSeconds = info.duration.takeIf { it >= 0 },
                        sourceName = sourceLabel,
                    )
                )
            }
            StreamingService.LinkType.PLAYLIST -> {
                val info = PlaylistInfo.getInfo(service, url)
                val thumb = info.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0).coerceAtLeast(1) }?.url
                val header = MusicItem(
                    id = url,
                    title = info.name.orEmpty().ifBlank { "Playlist" },
                    subtitle = info.uploaderName.orEmpty(),
                    kind = MusicKind.Playlist,
                    artworkUrl = thumb?.let { io.github.jadenyuki4.orbitmusic.upgradeArtworkUrl(it) },
                    sourceName = sourceLabel,
                )
                val tracks = info.relatedItems.orEmpty().mapNotNull { mapItem(it, MusicKind.Song, sourceLabel) }
                listOf(header) + tracks
            }
            StreamingService.LinkType.CHANNEL -> {
                val info = ChannelInfo.getInfo(service, url)
                val thumb = info.avatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0).coerceAtLeast(1) }?.url
                val header = MusicItem(
                    id = url,
                    title = info.name.orEmpty().ifBlank { "Artista" },
                    subtitle = "Artist",
                    kind = MusicKind.Artist,
                    artworkUrl = thumb?.let { io.github.jadenyuki4.orbitmusic.upgradeArtworkUrl(it) },
                    sourceName = sourceLabel,
                )
                val tracks = loadChannelItems(service, info, sourceLabel)
                listOf(header) + tracks
            }
            else -> emptyList()
        }
        return ExtractedPage(items.distinctBy { it.id })
    }

    private fun loadChannelItems(
        service: StreamingService,
        info: ChannelInfo,
        sourceLabel: String,
    ): List<MusicItem> {
        val tabs = info.tabs.orEmpty()
        if (tabs.isEmpty()) return emptyList()
        val orderedTabs = tabs.sortedByDescending { tab ->
            val name = tab.url.lowercase()
            when {
                name.contains("track") || name.contains("song") || name.contains("video") -> 3
                name.contains("release") || name.contains("album") || name.contains("playlist") -> 2
                else -> 1
            }
        }
        val items = mutableListOf<MusicItem>()
        for (tab in orderedTabs.take(2)) {
            runCatching {
                val tabInfo = ChannelTabInfo.getInfo(service, tab)
                tabInfo.relatedItems.orEmpty().mapNotNullTo(items) {
                    val itemKind = if (it is PlaylistInfoItem) MusicKind.Album else MusicKind.Song
                    mapItem(it, itemKind, sourceLabel)
                }
            }
            if (items.size >= 40) break
        }
        return items.distinctBy { it.id }.take(60)
    }

    private fun searchYouTubeMusic(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        val filter = when (kind) {
            MusicKind.Song -> Filters.MUSIC_SONGS
            MusicKind.Album -> Filters.MUSIC_ALBUMS
            MusicKind.Artist -> Filters.MUSIC_ARTISTS
            MusicKind.Playlist -> Filters.MUSIC_PLAYLISTS
        }
        return executeSearch(ServiceList.YouTube, query, listOf(filter), kind, page, "YT Music")
    }

    private fun searchYouTubeFull(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        val filter = when (kind) {
            MusicKind.Song -> Filters.VIDEOS
            MusicKind.Album, MusicKind.Playlist -> Filters.PLAYLISTS
            MusicKind.Artist -> Filters.CHANNELS
        }
        return executeSearch(ServiceList.YouTube, query, listOf(filter), kind, page, "YouTube")
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
        val label = resolveSourceLabel(service, "")
        return executeSearch(service, query, filters, kind, page, label)
    }

    private fun searchAllRepositories(query: String, kind: MusicKind, page: Page?): ExtractedPage {
        // When paginating with an upstream YouTube Page token, continue on YouTube Full/Music
        if (page != null) {
            return runCatching { searchYouTubeFull(query, kind, page) }
                .recoverCatching { searchYouTubeMusic(query, kind, page) }
                .getOrThrow()
        }

        val searchRequests: List<Triple<String, Int, () -> ExtractedPage>> = listOf(
            Triple("YT Music", 20) { searchYouTubeMusic(query, kind, null) },
            Triple("YouTube", 25) { searchYouTubeFull(query, kind, null) },
            Triple("SoundCloud", 15) { searchService(ServiceList.SoundCloud, query, kind, null) },
            Triple("Bandcamp", 10) { searchService(ServiceList.Bandcamp, query, kind, null) },
        )
        val futures = searchRequests.map { (_, _, fetch) ->
            CompletableFuture.supplyAsync({ runCatching(fetch) }, searchExecutor)
        }

        val searchBatches = mutableListOf<List<MusicItem>>()
        val failedSources = mutableListOf<String>()
        var nextPage: Page? = null
        var lastError: Throwable? = null

        futures.forEachIndexed { idx, future ->
            val outcome = future.join()
            val (sourceName, limit, _) = searchRequests[idx]
            outcome.onSuccess {
                searchBatches += it.items.take(limit)
                if (idx < 2 && nextPage == null) nextPage = it.next
            }.onFailure { err ->
                failedSources += sourceName
                if (idx == 0 || (idx == 1 && lastError == null)) lastError = err
            }
        }

        val merged = interleaveLists(searchBatches).distinctBy { it.id }.take(80)
        if (merged.isEmpty() && lastError != null) throw lastError
        return ExtractedPage(merged, nextPage, failedSources.distinct())
    }

    private fun executeSearch(
        service: StreamingService,
        query: String,
        filters: List<String>,
        kind: MusicKind,
        page: Page?,
        sourceLabel: String? = null,
    ): ExtractedPage {
        val extractor = service.getSearchExtractor(query, filters, "")
        val extractedPage = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else extractor.getPage(page)
        if (extractedPage.items.isEmpty() && extractedPage.errors.isNotEmpty()) throw extractedPage.errors.first()
        return ExtractedPage(
            extractedPage.items.mapNotNull { mapItem(it, kind, sourceLabel) },
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

internal fun mapItem(item: InfoItem, requestedKind: MusicKind, sourceName: String? = null): MusicItem? {
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
    val bestThumbnail = item.thumbnails
        .maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0).coerceAtLeast(1) }?.url
        ?: item.thumbnails.lastOrNull()?.url
    return MusicItem(
        id = item.url,
        title = item.name,
        subtitle = subtitle,
        kind = kind,
        artworkUrl = bestThumbnail?.let { io.github.jadenyuki4.orbitmusic.upgradeArtworkUrl(it) },
        durationSeconds = (item as? StreamInfoItem)?.duration?.takeIf { it >= 0 },
        sourceName = sourceName,
    )
}
