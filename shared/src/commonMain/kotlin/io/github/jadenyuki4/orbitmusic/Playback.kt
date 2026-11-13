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
    internal val taste by lazy { TasteStore(com.russhwolf.settings.Settings()) }
    internal val restored = MutableStateFlow<Transport?>(null)
    val queue = MutableStateFlow<List<MusicItem>>(emptyList())
    val shuffle = MutableStateFlow(false)
    val autoplay = MutableStateFlow(false)
    private var radio: Job? = null
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

    fun setVolume(value: Float) {
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

    fun seekBy(deltaSeconds: Long) {
        val cur = engine.state.value
        if (cur.track == null) return
        val maxPos = if (cur.duration > 0) cur.duration else Long.MAX_VALUE
        val target = (cur.position + deltaSeconds).coerceIn(0L, maxPos)
        engine.seek(target)
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
                val result = recommendations(
                    source,
                    history.observeRecentSearches(8).first(),
                    history.observeRecentPlays(30).first(),
                    history.observeFavorites().first(), rules = taste.rules.value,
                )
                ensureActive()
                when (result) {
                    is SourceResult.Success -> {
                        val songs = result.value.map { it.item }
                        if (songs.isNotEmpty()) playFrom(songs.first(), songs)
                    }
                    is SourceResult.Failure -> failure.value = result.error.userMessage()
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
        if (item.kind == MusicKind.Song && queue.value.none { it.id == item.id } && queue.value.size < 100)
            queue.value += item
    }

    fun playNext(item: MusicItem) {
        if (item.kind != MusicKind.Song) return
        val list = queue.value.filterNot { it.id == item.id }.toMutableList()
        if (list.size >= 100) return
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
        val active = engine.state.value.track
        queue.value = if (active != null) listOf(active) else emptyList()
    }

    fun remove(item: MusicItem) { queue.value = queue.value.filterNot { it.id == item.id } }

    fun move(item: MusicItem, offset: Int) {
        val list = queue.value.toMutableList()
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0 || index + offset !in list.indices) return
        list.add(index + offset, list.removeAt(index))
        queue.value = list
    }

    fun playFrom(item: MusicItem, list: List<MusicItem>) {
        queue.value = (list.filter { it.kind == MusicKind.Song }.take(99) + item).distinctBy { it.id }
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
            previous.addLast(current)
            if (previous.size > 100) previous.removeFirst()
        }
        select(target)
    }

    private fun continueWithRecommendations(current: MusicItem?) {
        radio?.cancel()
        radio = scope.launch {
            val result = recommendations(source, history.observeRecentSearches(8).first(),
                history.observeRecentPlays(30).first(), history.observeFavorites().first(), rules = taste.rules.value)
            ensureActive()
            if (!autoplay.value || history.incognito.value || !engine.state.value.ended || engine.state.value.track?.id != current?.id) return@launch
            when (result) {
                is SourceResult.Success -> {
                    val ids = queue.value.mapTo(mutableSetOf()) { it.id }
                    val fresh = result.value.map { it.item }.filter { it.id !in ids }
                    if (fresh.isNotEmpty()) playFrom(fresh.first(), fresh)
                }
                is SourceResult.Failure -> failure.value = result.error.userMessage()
            }
        }
    }

    fun back() {
        if (engine.state.value.position > 3) engine.seek(0)
        else {
            val target = previous.removeLastOrNull() ?: queue.value.getOrNull(queue.value.indexOfFirst { it.id == engine.state.value.track?.id } - 1)
            if (target != null) select(target) else engine.seek(0)
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

    init {
        scope.launch {
            engine.state.map { it.track?.id to it.ended }.distinctUntilChanged().collect { (id, ended) ->
                if (ended && id != null && pending.value == null && (lastRequest == null || lastRequest?.id == id)) next(true)
            }
        }
        scope.launch {
            engine.state.filter { it.playing && it.error == null }.mapNotNull { it.track }
                .distinctUntilChangedBy { it.id }.collect { item ->
                    history.recordPlay(item, 0, 0.0, false)
                }
        }
    }

    fun select(track: MusicItem, resumePositionSeconds: Long = 0L) {
        if (track.kind != MusicKind.Song) return
        radio?.cancel()
        resolve?.cancel()
        enqueue(track)
        visited.add(track.id)
        if (track.id != restored.value?.track?.id) restored.value = null
        lastRequest = track
        pending.value = track
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
                    currentStream.value = localStream
                    engine.loadAt(track, localStream, resumePositionSeconds)
                    engine.soundProfile(soundProfile.value)

                    return@launch
                } else if (offline != null) {
                    history.removeDownload(track.id)
                }

                val result = source.stream(track.id, reqQuality, reqFormat)
                ensureActive()
                when (result) {
                    is SourceResult.Success -> {
                        currentStream.value = result.value
                        engine.loadAt(track, result.value, resumePositionSeconds)
                        engine.soundProfile(soundProfile.value)

                    }
                    is SourceResult.Failure -> failure.value = result.error.userMessage()
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

    fun retry() { (lastRequest ?: engine.state.value.track)?.let { select(it, restored.value?.takeIf { r -> r.track?.id == it.id }?.position ?: 0) } }

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
                when (val result = source.stream(track.id, reqQuality, reqFormat)) {
                    is SourceResult.Failure -> {
                        downloadErrors.update { it + (track.id to result.error.userMessage()) }
                    }
                    is SourceResult.Success -> {
                        val stream = result.value
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
