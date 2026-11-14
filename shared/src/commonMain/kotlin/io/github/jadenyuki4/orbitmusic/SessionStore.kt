// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.russhwolf.settings.Settings
import io.github.jadenyuki4.orbitmusic.domain.AudioFormatPreference
import io.github.jadenyuki4.orbitmusic.domain.AudioQuality
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.MusicKind
import io.github.jadenyuki4.orbitmusic.domain.SoundProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

data class SavedPlaybackSession(
    val queue: List<MusicItem>,
    val currentTrackId: String?,
    val positionSeconds: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val quality: AudioQuality,
    val format: AudioFormatPreference,
    val profile: SoundProfile,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val autoplay: Boolean = true,
)

class SessionStore(private val settings: Settings = Settings()) {

    fun save(session: SavedPlaybackSession) {
        val queueText = session.queue.take(MAX_QUEUE).joinToString("\n") { item ->
            listOf(
                clean(item.id),
                clean(item.title),
                clean(item.subtitle),
                clean(item.artworkUrl.orEmpty()),
                item.durationSeconds?.toString().orEmpty(),
            ).joinToString("\t")
        }
        val stateText = listOf(
            clean(session.currentTrackId.orEmpty()),
            session.positionSeconds.coerceAtLeast(0L).toString(),
            session.shuffle.toString(),
            session.repeat.name,
            session.quality.name,
            session.format.name,
            session.profile.name,
            session.volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.toString() ?: "1.0",
            session.muted.toString(),
            session.autoplay.toString(),
        ).joinToString("\t")
        settings.putString(QUEUE_KEY, queueText)
        settings.putString(STATE_KEY, stateText)
    }

    fun load(): SavedPlaybackSession? = runCatching {
        val hasState = settings.hasKey(STATE_KEY)
        val queue = settings.getString(QUEUE_KEY, "")
            .lineSequence()
            .mapNotNull { line -> runCatching { parseItem(line) }.getOrNull() }
            .take(MAX_QUEUE)
            .toList()
        val fields = settings.getString(STATE_KEY, "").split('\t')
        val currentTrackId = fields.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
        if (queue.isEmpty() && currentTrackId == null && !hasState) return@runCatching null
        SavedPlaybackSession(
            queue = queue,
            currentTrackId = currentTrackId,
            positionSeconds = fields.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            shuffle = fields.getOrNull(2)?.toBooleanStrictOrNull() ?: false,
            repeat = enumOrDefault(fields.getOrNull(3), RepeatMode.Off),
            quality = enumOrDefault(fields.getOrNull(4), AudioQuality.High),
            format = enumOrDefault(fields.getOrNull(5), AudioFormatPreference.Auto),
            profile = enumOrDefault(fields.getOrNull(6), SoundProfile.AnimeOST),
            volume = fields.getOrNull(7)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            muted = fields.getOrNull(8)?.toBooleanStrictOrNull() ?: false,
            autoplay = fields.getOrNull(9)?.toBooleanStrictOrNull() ?: true,
        )
    }.getOrNull()

    fun clear() {
        settings.remove(QUEUE_KEY)
        settings.remove(STATE_KEY)
    }

    private fun parseItem(line: String): MusicItem? {
        val fields = line.split('\t')
        if (fields.size < 3) return null
        val id = fields[0].trim()
        val title = fields[1].trim()
        if (id.isBlank() || title.isBlank()) return null
        return MusicItem(
            id = id,
            title = title,
            subtitle = fields[2].trim(),
            kind = MusicKind.Song,
            artworkUrl = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
            durationSeconds = fields.getOrNull(4)?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun clean(text: String): String =
        text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.trim()?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val QUEUE_KEY = "session.queue.v1"
        const val STATE_KEY = "session.state.v1"
        const val MAX_QUEUE = 100
    }
}

/** La apertura debe esperar la primera acción del usuario antes de iniciar el audio. */
fun Playback.restoreSession(session: SavedPlaybackSession) {
    shuffle.value = session.shuffle
    repeat.value = session.repeat
    autoplay.value = session.autoplay
    audioQuality.value = session.quality
    audioFormat.value = session.format
    soundProfile.value = session.profile
    restoreVolumeState(session.volume, session.muted)
    if (engine.state.value.track == null && queue.value.isEmpty()) {
        session.queue.find { it.id == session.currentTrackId }?.let { item ->
            restored.value = Transport(track = item, position = session.positionSeconds, duration = item.durationSeconds ?: 0)
        }
    }
    if (queue.value.isEmpty() && session.queue.isNotEmpty()) {
        queue.value = session.queue
    }
}

fun Playback.snapshotSession(): SavedPlaybackSession {
    val transport = engine.state.value.takeIf { it.track != null } ?: restored.value ?: Transport()
    return SavedPlaybackSession(
        queue = queue.value.let { items ->
            val current = transport.track
            if (current != null && items.take(SessionStore.MAX_QUEUE).none { it.id == current.id })
                items.take(SessionStore.MAX_QUEUE - 1) + current else items
        },
        currentTrackId = transport.track?.id,
        positionSeconds = transport.position,
        shuffle = shuffle.value,
        repeat = repeat.value,
        quality = audioQuality.value,
        format = audioFormat.value,
        profile = soundProfile.value,
        volume = savedVolumeLevel(),
        muted = muted.value,
        autoplay = autoplay.value,
    )
}

fun Playback.downloadBatch(items: List<MusicItem>) {
    items.filter { it.kind == MusicKind.Song }.take(50).forEach { download(it) }
}

private data class SessionSignal(
    val queue: List<MusicItem>,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val quality: AudioQuality,
    val format: AudioFormatPreference,
    val profile: SoundProfile,
    val trackId: String?,
    val position: Long,
)

@Composable
internal fun Playback?.displayTransport(): Transport {
    val live = this?.engine?.state?.collectAsState()?.value
    val saved = this?.restored?.collectAsState()?.value
    return live?.takeIf { it.track != null } ?: saved ?: live ?: Transport()
}

@Composable
internal fun Playback?.displayTrack(): MusicItem? {
    val playback = this ?: return null
    return remember(playback) {
        combine(playback.engine.state, playback.restored) { live, saved ->
            (live.takeIf { it.track != null } ?: saved ?: live).track
        }.distinctUntilChanged()
    }.collectAsState(initial = (playback.engine.state.value.takeIf { it.track != null } ?: playback.restored.value)?.track).value
}

@OptIn(FlowPreview::class)
fun Playback.persistSession(scope: CoroutineScope, store: SessionStore) {
    val playback = this
    store.load()?.let(playback::restoreSession)
    scope.launch {
        combine(playback.volume, playback.muted, playback.autoplay) { v, m, a -> Triple(v, m, a) }
            .distinctUntilChanged()
            .drop(1)
            .debounce(500L)
            .collect {
                runCatching { store.save(playback.snapshotSession()) }
            }
    }
    scope.launch {
        var lastPositionSaveMark: kotlin.time.TimeMark? = null
        var lastStructural: Any? = null
        val settingsFlow = combine(playback.queue, playback.shuffle, playback.repeat, playback.audioQuality, playback.audioFormat) { q, s, r, qu, f ->
            SessionSignal(q, s, r, qu, f, SoundProfile.AnimeOST, null, 0L)
        }
        combine(settingsFlow, playback.soundProfile, playback.engine.state) { base, profile, transport ->
            base.copy(profile = profile, trackId = transport.track?.id, position = transport.position) to transport.playing
        }
            .distinctUntilChanged()
            .drop(1) // skip the initial (just-restored) emission
            .conflate()
            .collect { (signal, playing) ->
                val structural = listOf(signal.queue, signal.shuffle, signal.repeat, signal.quality, signal.format, signal.profile, signal.trackId, playing)
                val positionIntervalElapsed = lastPositionSaveMark?.elapsedNow()?.inWholeMilliseconds?.let { it >= 5000L } ?: true
                if (structural == lastStructural && !positionIntervalElapsed) {
                    delay(1000)
                    return@collect
                }
                lastStructural = structural
                lastPositionSaveMark = kotlin.time.TimeSource.Monotonic.markNow()
                runCatching { store.save(playback.snapshotSession()) }
                delay(1000) // minimum cooldown between consecutive writes
            }
    }
}
