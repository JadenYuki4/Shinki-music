// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import com.sun.jna.*
import com.sun.jna.ptr.DoubleByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors

internal interface Mpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_command(ctx: Pointer, args: StringArray): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, value: DoubleByReference): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_terminate_destroy(ctx: Pointer)
}

/** A single worker owns the handle. Native loading is deferred until the first track. */
class MpvEngine(private val audioOutput: String? = null) : AudioEngine, AutoCloseable {
    override val state = MutableStateFlow(Transport())
    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Orbit audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var api: Mpv? = null
    private var handle: Pointer? = null
    private var polling: Job? = null
    private var lastStream: AudioStream? = null
    private var ended = false

    private fun open(): Pointer {
        handle?.let { return it }
        val name = if (com.sun.jna.Platform.isWindows()) {
            val resources = System.getProperty("compose.application.resources.dir")
            val dll = resources?.let { File(it, "libmpv-2.dll") }?.takeIf { it.isFile }
                ?: listOf("desktopApp/resources/windows-x64", "resources/windows-x64")
                    .map { File(it, "libmpv-2.dll") }.firstOrNull { it.isFile }
            requireNotNull(dll) { "Missing libmpv" }.absolutePath
        } else "libmpv.so.2"
        val lib = Native.load(name, Mpv::class.java)
        val ctx = checkNotNull(lib.mpv_create())
        try {
            val options = mapOf("video" to "no", "terminal" to "no", "ytdl" to "no", "load-scripts" to "no",
                "config" to "no", "cache-secs" to "8", "demuxer-max-bytes" to "4MiB")
            options.forEach { (key, value) -> check(lib.mpv_set_option_string(ctx, key, value) >= 0) }
            audioOutput?.let { check(lib.mpv_set_option_string(ctx, "ao", it) >= 0) }
            check(lib.mpv_initialize(ctx) >= 0)
        } catch (e: Exception) {
            lib.mpv_terminate_destroy(ctx)
            throw e
        }
        api = lib
        handle = ctx
        return ctx
    }

    private fun command(vararg args: String) {
        check(api!!.mpv_command(handle!!, StringArray(args, "UTF-8")) >= 0)
    }

    override fun load(track: MusicItem, stream: AudioStream) {
        scope.launch {
            polling?.cancel()
            try {
                open()
                drainEvents()
                lastStream = stream
                ended = false
                command("loadfile", stream.url, "replace")
                command("set", "pause", "no")
                state.value = Transport(track, playing = true, duration = track.durationSeconds ?: 0)
                polling = launch {
                    while (isActive && !ended) {
                        drainEvents()
                        if (state.value.playing) state.value = state.value.copy(
                            position = number("time-pos") ?: state.value.position,
                            duration = number("duration") ?: state.value.duration)
                        delay(if (state.value.playing) 500 else 1500)
                    }
                }
            } catch (e: LinkageError) {
                state.value = Transport(track, error = "libmpv is unavailable. Check the native audio library and retry.")
            } catch (e: Exception) {
                state.value = Transport(track, error = "Could not initialize libmpv or open this stream.")
            }
        }
    }

    private fun number(property: String): Long? {
        val value = DoubleByReference()
        return if (api!!.mpv_get_property(handle!!, property, 5, value) >= 0) value.value.toLong().coerceAtLeast(0) else null
    }

    private fun drainEvents() {
        while (true) {
            val event = api!!.mpv_wait_event(handle!!, 0.0)
            when (event.getInt(0)) {
                0 -> return // MPV_EVENT_NONE
                7 -> { // MPV_EVENT_END_FILE, data is mpv_event_end_file*
                    val reason = event.getPointer(16)?.getInt(0)
                    if (reason == 0 || reason == 4) {
                        ended = true
                        state.value = state.value.copy(playing = false,
                            error = if (reason == 4) "Stream ended with an error. Retry for a fresh URL." else null)
                    }
                }
            }
        }
    }

    override fun toggle() {
        scope.launch {
            val current = state.value
            val track = current.track ?: return@launch
            if (handle == null || current.error != null) return@launch
            if (ended) lastStream?.let { load(track, it) }
            else if (runCatching { command("set", "pause", if (current.playing) "yes" else "no") }.isSuccess)
                state.value = current.copy(playing = !current.playing)
        }
    }
    override fun seek(seconds: Long) {
        scope.launch {
            if (handle != null && runCatching { command("seek", seconds.toString(), "absolute") }.isSuccess)
                state.value = state.value.copy(position = seconds)
        }
    }

    override fun close() {
        scope.cancel()
        runBlocking(dispatcher) { handle?.let { api?.mpv_terminate_destroy(it) } }
        dispatcher.close()
    }
}
