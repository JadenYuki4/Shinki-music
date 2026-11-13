// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.russhwolf.settings.Settings
import io.github.jadenyuki4.orbitmusic.domain.AudioFormatPreference
import io.github.jadenyuki4.orbitmusic.domain.AudioQuality
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.MusicKind
import io.github.jadenyuki4.orbitmusic.domain.SoundProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

data class SavedPlaybackSession(
    val queue: List<MusicItem>,
    val currentTrackId: String?,
    val positionSeconds: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val quality: AudioQuality,
    val format: AudioFormatPreference,
    val profile: SoundProfile,
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
        )
    }.getOrNull()

    fun clear() {
        settings.remove(QUEUE_KEY)
        settings.remove(STATE_KEY)
    }

    private fun parseItem(line: String): MusicItem? {
        val cols = line.split('\t')
        if (cols.size < 3) return null
        val id = cols[0].trim()
        val title = cols[1].trim()
        if (id.isBlank() || title.isBlank()) return null
        return MusicItem(
            id = id,
            title = title,
            subtitle = cols[2].trim(),
            kind = MusicKind.Song,
            artworkUrl = cols.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
            durationSeconds = cols.getOrNull(4)?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
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

/** Restores queue and preferences only; never starts audio playback. */
fun Playback.restoreSession(session: SavedPlaybackSession) {
    shuffle.value = session.shuffle
    repeat.value = session.repeat
    audioQuality.value = session.quality
    audioFormat.value = session.format
    soundProfile.value = session.profile
    if (queue.value.isEmpty() && session.queue.isNotEmpty()) {
        queue.value = session.queue
    }
}

fun Playback.snapshotSession(): SavedPlaybackSession {
    val transport = engine.state.value
    return SavedPlaybackSession(
        queue = queue.value,
        currentTrackId = transport.track?.id,
        positionSeconds = transport.position,
        shuffle = shuffle.value,
        repeat = repeat.value,
        quality = audioQuality.value,
        format = audioFormat.value,
        profile = soundProfile.value,
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
fun BindPlaybackSessionPersistence(
    playback: Playback?,
    store: SessionStore = remember { SessionStore() },
) {
    LaunchedEffect(playback, store) {
        val pb = playback ?: return@LaunchedEffect
        // Restore first, then start persisting, so the saved session is never overwritten by an empty one.
        runCatching { store.load()?.let { pb.restoreSession(it) } }
        val settingsFlow = combine(pb.queue, pb.shuffle, pb.repeat, pb.audioQuality, pb.audioFormat) { q, s, r, qu, f ->
            SessionSignal(q, s, r, qu, f, SoundProfile.AnimeOST, null, 0L)
        }
        combine(settingsFlow, pb.soundProfile, pb.engine.state) { base, profile, transport ->
            base.copy(profile = profile, trackId = transport.track?.id, position = transport.position)
        }
            .distinctUntilChanged()
            .drop(1) // skip the initial (just-restored) emission
            .conflate()
            .collect {
                runCatching { store.save(pb.snapshotSession()) }
                delay(1000) // throttle: at most one save per second, latest state wins
            }
    }
}
