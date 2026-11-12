// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import com.example.myapplication.domain.*
import com.example.myapplication.search.userMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class Transport(val track: MusicItem? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val error: String? = null)

/** All control calls and native state notifications are confined to the platform main thread. */
interface AudioEngine {
    val state: StateFlow<Transport>
    fun load(track: MusicItem, stream: AudioStream)
    fun toggle()
    fun seek(seconds: Long)
}

class Playback(private val source: MusicSource, val engine: AudioEngine, private val scope: CoroutineScope) {
    val pending = MutableStateFlow<MusicItem?>(null)
    val failure = MutableStateFlow<String?>(null)
    private var resolve: Job? = null
    private var lastRequest: MusicItem? = null
    val recent = MutableStateFlow<List<MusicItem>>(emptyList())

    init {
        scope.launch {
            engine.state.filter { it.playing && it.error == null }.mapNotNull { it.track }
                .distinctUntilChangedBy { it.id }.collect { item ->
                    recent.value = (listOf(item) + recent.value.filterNot { it.id == item.id }).take(6)
                }
        }
    }

    fun select(track: MusicItem) {
        if (track.kind != MusicKind.Song) return
        resolve?.cancel()
        lastRequest = track
        pending.value = track
        failure.value = null
        resolve = scope.launch {
            try {
                val result = source.stream(track.id)
                ensureActive()
                when (result) {
                    is SourceResult.Success -> engine.load(track, result.value)
                    is SourceResult.Failure -> failure.value = result.error.userMessage()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failure.value = "Could not open the audio output. Check the playback device and retry."
            } finally {
                if (isActive) pending.value = null
            }
        }
    }

    fun retry() { (lastRequest ?: engine.state.value.track)?.let(::select) }
}
