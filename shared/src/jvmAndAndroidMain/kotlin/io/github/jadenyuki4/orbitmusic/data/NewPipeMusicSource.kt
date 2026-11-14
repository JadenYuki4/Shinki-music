// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.ShinkiAppInfo
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.*
import java.io.IOException
import java.util.UUID

/** Bounded, process-local cursors preserve upstream overflow rather than skipping results. */
class NewPipeMusicSource internal constructor(private val gateway: ExtractorGateway) : MusicSource {
    constructor() : this(NewPipeGateway())

    override var repository: MusicRepository
        get() = gateway.repository
        set(value) {
            if (gateway.repository != value) {
                gateway.repository = value
                cursors.clear()
            }
        }

    private data class Cursor(val query: String, val kind: MusicKind, val size: Int,
        val pending: List<MusicItem>, val upstream: Page?)
    private val mutex = Mutex()
    private val cursors = linkedMapOf<String, Cursor>()

    override suspend fun suggestions(query: String): SourceResult<List<String>> {
        val clean = query.trim().take(200)
        if (clean.length < 2) return SourceResult.Success(emptyList())
        return guarded {
            gateway.suggestions(clean).asSequence().map(String::trim).filter(String::isNotEmpty)
                .distinct().take(8).toList()
        }
    }

    override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
        val clean = query.trim()
        if (clean.length > 200 || pageSize !in 1..50) return SourceResult.Failure(SourceError.InvalidRequest)
        if (clean.isEmpty()) return SourceResult.Success(MusicPage(emptyList()))
        val category = kind ?: MusicKind.Song
        return mutex.withLock {
            val previous = page?.let { cursors[it] }
            if (page != null && (previous == null || previous.query != clean || previous.kind != category || previous.size != pageSize)) {
                return@withLock SourceResult.Failure(SourceError.InvalidRequest)
            }
            val fetched = if (previous != null && previous.pending.isNotEmpty()) {
                SourceResult.Success(ExtractedPage(previous.pending, previous.upstream))
            } else guarded { gateway.search(clean, category, previous?.upstream) }
            when (fetched) {
                is SourceResult.Failure -> fetched
                is SourceResult.Success -> {
                    val batch = fetched.value
                    if (batch.items.size > 200) return@withLock SourceResult.Failure(SourceError.SourceChanged)
                    val items = batch.items.distinctBy { it.id }.filter { it.kind == category }
                    val remaining = items.drop(pageSize)
                    val next = if (remaining.isNotEmpty() || batch.next != null) {
                        UUID.randomUUID().toString().also { token ->
                            cursors[token] = Cursor(clean, category, pageSize, remaining, batch.next)
                            while (cursors.size > 16) cursors.remove(cursors.keys.first())
                        }
                    } else null
                    SourceResult.Success(MusicPage(items.take(pageSize), next))
                }
            }
        }
    }

    override suspend fun lyrics(item: MusicItem): SourceResult<String?> = guarded {
        val artist = item.subtitle.removeSuffix(" - Topic").trim()
        fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        val url = "https://lrclib.net/api/search?track_name=${encode(item.title)}&artist_name=${encode(artist)}"
        val request = org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(url)
            .headers(mapOf("User-Agent" to listOf("ShinkiMusic/${ShinkiAppInfo.VERSION} (io.github.jadenyuki4.orbitmusic)"))).build()
        val body = UrlConnectionDownloader().execute(request).responseBody()
        val records = com.grack.nanojson.JsonParser.array().from(body)
        val objects = records.filterIsInstance<com.grack.nanojson.JsonObject>()
        val match = objects.firstOrNull {
            it.getString("trackName").equals(item.title, true) && it.getString("artistName").equals(artist, true)
        } ?: objects.firstOrNull()
        (match?.getString("syncedLyrics")?.takeIf { it.isNotBlank() }
            ?: match?.getString("plainLyrics")?.takeIf { it.isNotBlank() })?.take(30000)
    }

    override suspend fun discover(page: String?, pageSize: Int): SourceResult<MusicPage> {
        if (page != null || pageSize !in 1..50) return SourceResult.Failure(SourceError.InvalidRequest)
        return guarded { MusicPage(gateway.trending().distinctBy { it.id }.take(pageSize)) }
    }

    override suspend fun related(songId: String): SourceResult<List<MusicItem>> {
        if (!songId.startsWith("https://")) return SourceResult.Failure(SourceError.InvalidRequest)
        return guarded(onFailure = { io.github.jadenyuki4.orbitmusic.logRelatedFailure(it) }) { gateway.related(songId).distinctBy { it.id } }
    }

    override suspend fun stream(songId: String): SourceResult<AudioStream> =
        stream(songId, AudioQuality.High, AudioFormatPreference.Auto)

    override suspend fun stream(
        songId: String,
        quality: AudioQuality,
        format: AudioFormatPreference,
    ): SourceResult<AudioStream> {
        if (!songId.startsWith("https://")) return SourceResult.Failure(SourceError.InvalidRequest)
        return when (val result = guarded { gateway.stream(songId, quality, format) }) {
            is SourceResult.Failure -> result
            is SourceResult.Success -> result.value?.let { SourceResult.Success(it) }
                ?: SourceResult.Failure(SourceError.Unavailable)
        }
    }
}

internal fun sourceError(error: Exception): SourceError {
    // Extractor parsing errors sometimes wrap the original HTTP/IO cause.
    val relevant = generateSequence<Throwable>(error) { it.cause }.take(8).firstOrNull {
        it is TransportFailure || it is ReCaptchaException || it is ContentNotAvailableException || it is IOException
    } ?: error
    return when (relevant) {
    is TransportFailure -> when (relevant.status) {
        429 -> SourceError.RateLimited
        401, 403 -> SourceError.VerificationRequired
        404, 410 -> SourceError.Unavailable
        408, in 500..599 -> SourceError.Network
        else -> SourceError.SourceChanged
    }
    is ReCaptchaException -> SourceError.VerificationRequired
    is ContentNotAvailableException -> SourceError.Unavailable
    is ResponseTooLarge -> SourceError.SourceChanged
    is IOException -> SourceError.Network
    else -> SourceError.SourceChanged
    }
}

/** One bounded retry for transient transport failures; cancellation never becomes an error. */
private val sourceIo = Dispatchers.IO.limitedParallelism(2)

private suspend fun <T> guarded(onFailure: (Exception) -> Unit = {}, block: () -> T): SourceResult<T> {
    repeat(2) { attempt ->
        try { return SourceResult.Success(runInterruptible(sourceIo) { block() }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val category = sourceError(error)
            val transient = category == SourceError.Network &&
                (error !is TransportFailure || error.status in 500..599)
            if (attempt == 0 && transient) delay(400)
            else {
                onFailure(error)
                return SourceResult.Failure(category)
            }
        }
    }
    error("Unreachable retry state")
}
