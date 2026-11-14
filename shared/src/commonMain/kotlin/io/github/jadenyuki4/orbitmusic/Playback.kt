// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.search.userMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class Transport(val track: MusicItem? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val error: String? = null, val ended: Boolean = false)

/** All control calls and native state notifications are confined to the platform main thread. */
interface AudioEngine {
    val state: StateFlow<Transport>
    fun load(track: MusicItem, stream: AudioStream)
    fun loadAt(track: MusicItem, stream: AudioStream, seconds: Long) {
        load(track, stream)
        if (seconds > 0) seek(seconds)
    }
    fun toggle()
    fun seek(seconds: Long)
    fun volume(value: Float)
    fun speed(value: Float)
    fun soundProfile(profile: SoundProfile) {}
    fun equalizer(bands: List<Float>, enabled: Boolean) {}
}

data class EqualizerState(
    val preset: SpotiFlacEqPreset = SpotiFlacEqPreset.Vocal,
    val bands: List<Float> = preset.bandsDb,
    val enabled: Boolean = true,
)

enum class RepeatMode { Off, All, One }

class Playback(
    val source: MusicSource,
    val engine: AudioEngine,
    private val scope: CoroutineScope,
    val history: HistoryStore = io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore(),
    val downloader: TrackDownloader = NoOpTrackDownloader,
) {
    internal val taste by lazy { TasteStore(runCatching { com.russhwolf.settings.Settings() }.getOrNull()) }
    internal val restored = MutableStateFlow<Transport?>(null)
    val queue = MutableStateFlow<List<MusicItem>>(emptyList())
    val shuffle = MutableStateFlow(false)
    val autoplay = MutableStateFlow(true)
    private var radio: Job? = null
    private var refillJob: Job? = null
    private var autoQueueActive = false
    private var autoQueueSeed: MusicItem? = null
    private val manualQueueIds = mutableSetOf<String>()
    private var trackedPlayTrack: MusicItem? = null
    private var trackedPlayMaxPosition: Long = 0L
    private var trackedPlayDuration: Long = 0L
    private var trackedPlayEnded: Boolean = false
    val repeat = MutableStateFlow(RepeatMode.Off)
    val sleepMinutes = MutableStateFlow(0)
    val volume = MutableStateFlow(1f)
    val muted = MutableStateFlow(false)
    private var preMuteVolume = 1f
    val speed = MutableStateFlow(1f)
    val audioQuality = MutableStateFlow(AudioQuality.High)
    val audioFormat = MutableStateFlow(AudioFormatPreference.Auto)
    val soundProfile = MutableStateFlow(SoundProfile.AnimeOST)
    val equalizerState = MutableStateFlow(EqualizerState())
    fun setEqualizer(value: EqualizerState) {
        equalizerState.value = value
        engine.equalizer(value.bands, value.enabled)
    }
    val currentStream = MutableStateFlow<AudioStream?>(null)

    internal fun savedVolumeLevel(): Float =
        if (muted.value || volume.value <= 0f) {
            preMuteVolume.takeIf { it.isFinite() && it in 0.01f..1f } ?: 0.7f
        } else {
            volume.value.takeIf { it.isFinite() }?.coerceIn(0.01f, 1f) ?: 1f
        }

    internal fun restoreVolumeState(savedVolume: Float, isMuted: Boolean) {
        val valid = savedVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        if (isMuted || valid <= 0f) {
            preMuteVolume = valid.takeIf { it > 0.01f } ?: 0.7f
            muted.value = true
            volume.value = 0f
            engine.volume(0f)
        } else {
            preMuteVolume = valid
            muted.value = false
            volume.value = valid
            engine.volume(valid)
        }
    }

    fun setVolume(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        volume.value = clamped
        if (clamped > 0f) {
            muted.value = false
            preMuteVolume = clamped
        } else {
            muted.value = true
        }
        engine.volume(clamped)
    }

    fun volumeUp(step: Float = 0.1f) {
        val base = if (muted.value) 0f else volume.value
        setVolume((base + step).coerceIn(0f, 1f))
    }

    fun volumeDown(step: Float = 0.1f) {
        val base = if (muted.value) 0f else volume.value
        setVolume((base - step).coerceIn(0f, 1f))
    }

    fun toggleMute() {
        if (muted.value || volume.value == 0f) {
            val restored = preMuteVolume.takeIf { it > 0.05f } ?: 0.7f
            muted.value = false
            volume.value = restored
            engine.volume(restored)
        } else {
            preMuteVolume = volume.value
            muted.value = true
            volume.value = 0f
            engine.volume(0f)
        }
    }

    fun setSpeed(value: Float) {
        speed.value = value.coerceIn(.5f, 2f)
        engine.speed(speed.value)
    }

    fun setAudioQuality(quality: AudioQuality) {
        if (audioQuality.value == quality) return
        audioQuality.value = quality
        reloadCurrentStream()
    }

    fun setAudioFormat(format: AudioFormatPreference) {
        if (audioFormat.value == format) return
        audioFormat.value = format
        reloadCurrentStream()
    }

    fun setSoundProfile(profile: SoundProfile) {
        soundProfile.value = profile
        engine.soundProfile(profile)
    }

    private var pendingSeekSeconds: Long? = null
    private var seekAnchorMark: kotlin.time.TimeMark? = null
    private var seekAnchorTarget: Long = 0L

    private fun currentTransport(): Transport {
        val cur = engine.state.value
        if (cur.track != null) return cur
        val saved = restored.value
        if (saved?.track != null) return saved
        return cur
    }

    fun seekTo(seconds: Long) {
        val pendingTrack = pending.value
        val cur = currentTransport()
        val track = cur.track ?: pendingTrack ?: return
        val maxPos = (cur.duration.takeIf { it > 0L } ?: track.durationSeconds)?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = seconds.coerceIn(0L, maxPos)
        seekAnchorTarget = target
        seekAnchorMark = kotlin.time.TimeSource.Monotonic.markNow()

        if (pendingTrack?.id == track.id && engine.state.value.track?.id != track.id) {
            pendingSeekSeconds = target
            if (restored.value?.track?.id == track.id) {
                restored.value = restored.value?.copy(position = target)
            }
            return
        }
        if (engine.state.value.track == null && restored.value?.track?.id == track.id) {
            restored.value = restored.value?.copy(position = target)
            select(track, target)
            return
        }
        engine.seek(target)
    }

    fun seekBy(deltaSeconds: Long) {
        val cur = currentTransport()
        val track = cur.track ?: pending.value ?: return
        val basePos = if (seekAnchorMark?.elapsedNow()?.inWholeMilliseconds?.let { it < 800 } == true) {
            seekAnchorTarget
        } else {
            cur.position
        }
        val maxPos = (cur.duration.takeIf { it > 0L } ?: track.durationSeconds)?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (basePos + deltaSeconds).coerceIn(0L, maxPos)
        seekTo(target)
    }

    fun playOrToggle() {
        val state = engine.state.value
        if (state.track != null && pending.value == null && state.error == null) {
            engine.toggle()
        } else if (state.error != null) {
            retry()
        } else if (restored.value?.track != null) {
            restored.value?.let { resume -> resume.track?.let { select(it, resume.position) } }
        } else if (queue.value.isNotEmpty()) {
            select(queue.value.first())
        } else {
            radio?.cancel()
            radio = scope.launch {
                val recommendationResponse = recommendations(
                    source,
                    history.observeRecentSearches(8).first(),
                    history.observeRecentPlays(30).first(),
                    history.observeFavorites().first(), rules = taste.rules.value,
                )
                ensureActive()
                when (recommendationResponse) {
                    is SourceResult.Success -> {
                        val songs = recommendationResponse.value.map { it.item }
                        if (songs.isNotEmpty()) playFrom(songs.first(), songs)
                    }
                    is SourceResult.Failure -> failure.value = recommendationResponse.error.userMessage()
                }
            }
        }
    }

    private fun reloadCurrentStream() {
        val current = engine.state.value.track ?: lastRequest ?: return
        val resumePosition = engine.state.value.position
        select(current, resumePositionSeconds = resumePosition)
    }

    private var timer: Job? = null
    private val visited = mutableSetOf<String>()
    private val previous = ArrayDeque<MusicItem>()

    fun enqueue(item: MusicItem) {
        if (item.kind == MusicKind.Song && queue.value.none { it.id == item.id } && queue.value.size < 100) {
            manualQueueIds += item.id
            queue.value += item
        }
    }

    fun playNext(item: MusicItem) {
        if (item.kind != MusicKind.Song) return
        val list = queue.value.filterNot { it.id == item.id }.toMutableList()
        if (list.size >= 100) return
        manualQueueIds += item.id
        val currentId = engine.state.value.track?.id ?: lastRequest?.id
        val currentIdx = list.indexOfFirst { it.id == currentId }
        if (currentIdx >= 0 && currentIdx + 1 <= list.size) {
            list.add(currentIdx + 1, item)
        } else {
            list.add(item)
        }
        queue.value = list
    }

    fun clearQueue() {
        refillJob?.cancel()
        manualQueueIds.clear()
        autoQueueActive = false
        autoQueueSeed = null
        val active = engine.state.value.track
        queue.value = if (active != null) listOf(active) else emptyList()
    }

    fun remove(item: MusicItem) {
        manualQueueIds -= item.id
        queue.value = queue.value.filterNot { it.id == item.id }
    }

    fun move(item: MusicItem, offset: Int) {
        val list = queue.value.toMutableList()
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0 || index + offset !in list.indices) return
        list.add(index + offset, list.removeAt(index))
        queue.value = list
    }

    fun playFromSearch(item: MusicItem) {
        if (item.kind != MusicKind.Song) return
        refillJob?.cancel()
        manualQueueIds.clear()
        autoQueueActive = true
        autoQueueSeed = item
        queue.value = listOf(item)
        visited.clear()
        previous.clear()
        select(item)
        scheduleAutoQueueRefill(item)
    }

    fun playFrom(item: MusicItem, list: List<MusicItem>) {
        refillJob?.cancel()
        manualQueueIds.clear()
        val filtered = (list.filter { it.kind == MusicKind.Song }.take(99) + item).distinctBy { it.id }
        if (filtered.size > 1) {
            filtered.forEach { manualQueueIds += it.id }
            autoQueueActive = false
            autoQueueSeed = null
        } else {
            autoQueueActive = true
            autoQueueSeed = item
        }
        queue.value = filtered
        visited.clear()
        previous.clear()
        select(item)
    }

    fun next(automatic: Boolean = false) {
        val list = queue.value
        val current = engine.state.value.track ?: lastRequest
        val index = list.indexOfFirst { it.id == current?.id }
        val target = when {
            automatic && repeat.value == RepeatMode.One -> current
            shuffle.value -> {
                val fresh = list.filterNot { it.id in visited || it.id == current?.id }
                fresh.randomOrNull() ?: if (repeat.value == RepeatMode.All) {
                    visited.clear()
                    list.filterNot { it.id == current?.id }.randomOrNull() ?: list.firstOrNull()
                } else null
            }
            index + 1 in list.indices -> list[index + 1]
            repeat.value == RepeatMode.All -> list.firstOrNull()
            else -> null
        }
        if (target == null) {
            if (automatic && autoplay.value && !history.incognito.value) continueWithRecommendations(current)
            return
        }
        if (current != null && current.id != target.id) {
            if (!automatic) finalizeTrackedPlay(skipped = !trackedPlayEnded)
            previous.addLast(current)
            if (previous.size > 100) previous.removeFirst()
        }
        select(target)
        val targetIndex = list.indexOfFirst { it.id == target.id }
        val remainingAfterTarget = if (targetIndex >= 0) (list.lastIndex - targetIndex).coerceAtLeast(0) else 0
        if (autoQueueActive && autoplay.value && !history.incognito.value &&
            remainingAfterTarget <= RecommendationWeights.REFILL_THRESHOLD
        ) {
            scheduleAutoQueueRefill(target)
        }
    }

    private fun scheduleAutoQueueRefill(seed: MusicItem?) {
        refillJob?.cancel()
        refillJob = scope.launch {
            if (!autoplay.value || history.incognito.value || !autoQueueActive) return@launch
            val recommendationBatch = autoQueueBatch(
                source = source,
                seedTrack = seed ?: autoQueueSeed,
                queueItems = queue.value,
                searches = history.observeRecentSearches(10).first(),
                plays = history.observeRecentPlays(40).first(),
                favorites = history.observeFavorites().first(),
                batchSize = RecommendationWeights.BATCH_DEFAULT_SIZE,
                rules = taste.rules.value,
            )
            ensureActive()
            if (!autoQueueActive || !autoplay.value || history.incognito.value) return@launch
            if (seed != null && autoQueueSeed != null && autoQueueSeed?.id != seed.id) return@launch
            if (recommendationBatch is SourceResult.Success && recommendationBatch.value.isNotEmpty()) {
                val existing = queue.value
                val available = (100 - existing.size).coerceAtLeast(0)
                if (available == 0) return@launch
                val existingIds = existing.mapTo(mutableSetOf()) { it.id }
                val toAdd = recommendationBatch.value.filter { cand ->
                    cand.id !in existingIds && existing.none { isSameTrackVariant(it, cand) }
                }.take(available)
                if (toAdd.isNotEmpty()) {
                    queue.value = existing + toAdd
                }
            }
        }
    }

    private fun continueWithRecommendations(current: MusicItem?) {
        radio?.cancel()
        radio = scope.launch {
            val recommendationBatch = autoQueueBatch(
                source = source,
                seedTrack = current ?: autoQueueSeed,
                queueItems = queue.value,
                searches = history.observeRecentSearches(10).first(),
                plays = history.observeRecentPlays(40).first(),
                favorites = history.observeFavorites().first(),
                batchSize = RecommendationWeights.BATCH_DEFAULT_SIZE,
                rules = taste.rules.value,
            )
            ensureActive()
            if (!autoplay.value || history.incognito.value || !engine.state.value.ended || engine.state.value.track?.id != current?.id) return@launch
            when (recommendationBatch) {
                is SourceResult.Success -> {
                    val existing = queue.value
                    val ids = existing.mapTo(mutableSetOf()) { it.id }
                    val fresh = recommendationBatch.value.filter { cand ->
                        cand.id !in ids && existing.none { isSameTrackVariant(it, cand) }
                    }
                    if (fresh.isNotEmpty()) {
                        autoQueueActive = true
                        autoQueueSeed = fresh.first()
                        val available = (100 - existing.size).coerceAtLeast(0)
                        queue.value = (existing + fresh.take(available)).distinctBy { it.id }
                        visited.clear()
                        previous.clear()
                        select(fresh.first())
                    }
                }
                is SourceResult.Failure -> failure.value = recommendationBatch.error.userMessage()
            }
        }
    }

    fun back() {
        val cur = currentTransport()
        if (cur.position > 3) seekTo(0)
        else {
            val activeId = cur.track?.id
            val target = previous.removeLastOrNull() ?: queue.value.getOrNull(queue.value.indexOfFirst { it.id == activeId } - 1)
            if (target != null) select(target) else seekTo(0)
        }
    }

    fun sleep(minutes: Int) {
        timer?.cancel()
        sleepMinutes.value = minutes.coerceIn(0, 120)
        if (sleepMinutes.value == 0) return
        timer = scope.launch {
            while (sleepMinutes.value > 0) {
                delay(60_000)
                sleepMinutes.value--
            }
            radio?.cancel()
            refillJob?.cancel()
            resolve?.cancel()
            pending.value = null
            if (engine.state.value.playing) engine.toggle()
        }
    }
    val pending = MutableStateFlow<MusicItem?>(null)
    val failure = MutableStateFlow<String?>(null)
    val activeDownloads = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val downloadErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val downloadJobs = mutableMapOf<String, Job>()
    private var resolve: Job? = null
    private var lastRequest: MusicItem? = null

    private fun finalizeTrackedPlay(skipped: Boolean) {
        val previous = trackedPlayTrack ?: return
        val maxPos = trackedPlayMaxPosition.coerceAtLeast(0L)
        val duration = trackedPlayDuration.coerceAtLeast(1L)
        val ratio = (maxPos.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        val effectiveEnded = trackedPlayEnded
        val isSkipped = skipped && !effectiveEnded && ratio < 0.75
        trackedPlayTrack = null
        trackedPlayMaxPosition = 0L
        trackedPlayDuration = 0L
        trackedPlayEnded = false
        if (maxPos > 0L || isSkipped || effectiveEnded) {
            val finalRatio = if (effectiveEnded) maxOf(ratio, 0.95) else ratio
            val playedSecs = if (effectiveEnded) maxOf(maxPos, duration) else maxPos
            scope.launch {
                history.recordPlay(previous, playedSecs, finalRatio, isSkipped)
            }
        }
    }

    init {
        scope.launch {
            engine.state.map { it.track?.id to it.ended }.distinctUntilChanged().collect { (id, ended) ->
                if (ended && id != null && pending.value == null && (lastRequest == null || lastRequest?.id == id)) next(true)
            }
        }
        scope.launch {
            engine.state.collect { transport ->
                val active = transport.track
                val currentTracked = trackedPlayTrack
                if (active != null && transport.error == null && active.id != currentTracked?.id && transport.playing) {
                    finalizeTrackedPlay(skipped = !trackedPlayEnded)
                    trackedPlayTrack = active
                    trackedPlayMaxPosition = transport.position
                    trackedPlayDuration = maxOf(transport.duration, active.durationSeconds ?: 0L)
                    trackedPlayEnded = transport.ended
                    history.recordPlay(active, transport.position, 0.0, false)
                } else if (active != null && active.id == currentTracked?.id) {
                    trackedPlayMaxPosition = maxOf(trackedPlayMaxPosition, transport.position)
                    trackedPlayDuration = maxOf(trackedPlayDuration, transport.duration, active.durationSeconds ?: 0L)
                    if (transport.ended && !trackedPlayEnded) {
                        trackedPlayEnded = true
                        finalizeTrackedPlay(skipped = false)
                    }
                }
            }
        }
    }

    fun select(track: MusicItem, resumePositionSeconds: Long = 0L) {
        if (track.kind != MusicKind.Song) return
        if (trackedPlayTrack != null && trackedPlayTrack?.id != track.id && !trackedPlayEnded) {
            finalizeTrackedPlay(skipped = true)
        }
        radio?.cancel()
        resolve?.cancel()
        if (autoQueueActive && track.id != autoQueueSeed?.id) {
            autoQueueSeed = track
        }
        enqueue(track)
        visited.add(track.id)
        if (track.id != restored.value?.track?.id) restored.value = null
        lastRequest = track
        pending.value = track
        pendingSeekSeconds = if (resumePositionSeconds > 0L) resumePositionSeconds else null
        failure.value = null
        val reqQuality = audioQuality.value
        val reqFormat = audioFormat.value
        resolve = scope.launch {
            try {
                val offline = history.getDownload(track.id)
                if (offline != null && downloader.exists(offline.localPath)) {
                    val localStream = AudioStream(
                        url = offline.localPath,
                        mimeType = offline.mimeType,
                        bitrateKbps = offline.bitrateKbps,
                        formatLabel = offline.formatLabel?.let { "$it · OFFLINE" } ?: "OFFLINE",
                    )
                    val startAt = pendingSeekSeconds ?: resumePositionSeconds
                    pendingSeekSeconds = null
                    currentStream.value = localStream
                    engine.volume(if (muted.value) 0f else volume.value)
                    engine.loadAt(track, localStream, startAt)
                    engine.soundProfile(soundProfile.value)

                    return@launch
                } else if (offline != null) {
                    history.removeDownload(track.id)
                }

                val streamResponse = source.stream(track.id, reqQuality, reqFormat)
                ensureActive()
                when (streamResponse) {
                    is SourceResult.Success -> {
                        val startAt = pendingSeekSeconds ?: resumePositionSeconds
                        pendingSeekSeconds = null
                        currentStream.value = streamResponse.value
                        engine.volume(if (muted.value) 0f else volume.value)
                        engine.loadAt(track, streamResponse.value, startAt)
                        engine.soundProfile(soundProfile.value)

                    }
                    is SourceResult.Failure -> failure.value = streamResponse.error.userMessage()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failure.value = "No se pudo abrir la salida de audio. Verificá el dispositivo de reproducción y reintentá."
            } finally {
                if (isActive) pending.value = null
            }
        }
    }

    fun retry() {
        val target = lastRequest ?: engine.state.value.track ?: restored.value?.track ?: return
        val resumePos = engine.state.value.takeIf { it.track?.id == target.id && it.position > 0L }?.position
            ?: restored.value?.takeIf { it.track?.id == target.id }?.position
            ?: 0L
        select(target, resumePos)
    }

    fun download(track: MusicItem) {
        if (track.kind != MusicKind.Song) return
        if (downloadJobs[track.id]?.isActive == true) return
        downloadErrors.update { it - track.id }
        activeDownloads.update { it + (track.id to null) }
        val reqQuality = audioQuality.value
        val reqFormat = audioFormat.value
        lateinit var job: Job
        job = scope.launch {
            try {
                val existing = history.getDownload(track.id)
                if (existing != null && downloader.exists(existing.localPath)) {
                    return@launch
                }
                when (val streamResponse = source.stream(track.id, reqQuality, reqFormat)) {
                    is SourceResult.Failure -> {
                        downloadErrors.update { it + (track.id to streamResponse.error.userMessage()) }
                    }
                    is SourceResult.Success -> {
                        val stream = streamResponse.value
                        val saved = downloader.downloadToLocal(track, stream) { progress ->
                            activeDownloads.update { it + (track.id to progress) }
                        }
                        history.saveDownload(
                            DownloadedTrack(
                                item = track,
                                localPath = saved.localPath,
                                mimeType = saved.mimeType,
                                formatLabel = stream.formatLabel ?: reqFormat.shortLabel,
                                bitrateKbps = stream.bitrateKbps ?: reqQuality.targetBitrateKbps,
                                fileSizeBytes = saved.fileSizeBytes,
                                artworkLocalPath = saved.artworkLocalPath,
                                downloadedAtEpochMillis = 0L,
                            )
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                downloadErrors.update {
                    it + (track.id to (e.message?.takeIf { msg -> msg.isNotBlank() } ?: "No se pudo descargar la canción."))
                }
            } finally {
                if (downloadJobs[track.id] === job) downloadJobs.remove(track.id)
                activeDownloads.update { it - track.id }
            }
        }
        if (job.isActive) downloadJobs[track.id] = job
    }

    fun cancelDownload(songId: String) {
        downloadJobs.remove(songId)?.cancel()
        activeDownloads.update { it - songId }
    }

    fun dismissDownloadError(songId: String) {
        downloadErrors.update { it - songId }
    }

    fun removeDownload(songId: String) {
        scope.launch {
            val existing = history.getDownload(songId)
            history.removeDownload(songId)
            existing?.let {
                downloader.delete(it.localPath)
                it.artworkLocalPath?.let(downloader::delete)
            }
        }
    }

    fun clearDownloads() {
        scope.launch {
            val all = history.observeDownloads().first()
            history.clearDownloads()
            all.forEach {
                downloader.delete(it.localPath)
                it.artworkLocalPath?.let(downloader::delete)
            }
        }
    }

    fun downloadFolderPath(): String = downloader.downloadsDirectoryPath
    fun canOpenDownloadsFolder(): Boolean = downloader.supportsOpenFolder
    fun openDownloadsFolder(): Boolean = downloader.openFolder()
}
