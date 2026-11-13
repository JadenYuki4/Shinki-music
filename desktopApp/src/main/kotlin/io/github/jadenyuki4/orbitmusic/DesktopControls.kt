// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import com.sun.jna.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import java.io.File

/** Windows owns the hotkey registration on one message-queue thread. Linux delegates MPRIS to GIO. */
internal fun mediaKeys(playback: Playback, scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
    val engine = playback.engine
    engine.state.first { it.track != null && it.error == null }
    if (com.sun.jna.Platform.isWindows()) {
        val helper = listOfNotNull(System.getProperty("compose.application.resources.dir"),
            "desktopApp/resources/windows-x64", "resources/windows-x64").map { File(it, "ShinkiTransport.exe") }.first { it.isFile }
        val process = ProcessBuilder(helper.absolutePath).start()
        try {
            coroutineScope {
                val writer = launch {
                    fun encode(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
                    process.outputStream.bufferedWriter().use { output ->
                        engine.state.map { state ->
                            val status = if (state.playing) "Playing" else "Paused"
                            listOf(status, encode(state.track?.title.orEmpty()), encode(state.track?.subtitle.orEmpty())).joinToString("\t")
                        }.distinctUntilChanged().collect { output.write(it); output.newLine(); output.flush() }
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { process.destroy() }
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { command ->
                            scope.launch {
                                when (command) {
                                    "PlayPause" -> engine.toggle()
                                    "Play" -> if (!engine.state.value.playing) engine.toggle()
                                    "Pause" -> if (engine.state.value.playing) engine.toggle()
                                    "Next" -> playback.next()
                                    "Previous" -> playback.back()
                                }
                            }
                        } }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                } finally { writer.cancel() }
            }
        } catch (_: java.io.IOException) {
            // Closing the native session can close the pipe before the last status write.
        } finally { process.destroy() }
    } else if (com.sun.jna.Platform.isLinux()) {
        val script = listOfNotNull(System.getProperty("compose.application.resources.dir"),
            "desktopApp/resources/linux", "resources/linux").map { File(it, "media_keys.py") }.firstOrNull { it.isFile } ?: return@launch
        val process = try { ProcessBuilder("/usr/bin/python3", script.absolutePath).start() } catch (_: java.io.IOException) { return@launch }
        try {
            coroutineScope {
                val status = launch {
                    process.outputStream.bufferedWriter().use { out ->
                        engine.state.map { if (it.track == null) "Stopped" else if (it.playing) "Playing" else "Paused" }
                            .distinctUntilChanged().collect { out.write(it); out.newLine(); out.flush() }
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { process.destroy() }
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { method ->
                            when (method) {
                                "Next" -> scope.launch { playback.next() }
                                "Previous" -> scope.launch { playback.back() }
                                "PlayPause" -> engine.toggle()
                                "Play" -> if (!engine.state.value.playing) engine.toggle()
                                "Pause" -> if (engine.state.value.playing) engine.toggle()
                            }
                        } }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                } finally { status.cancel() }
            }
        } catch (_: java.io.IOException) {
            // A desktop without a session bus can still use the tray and in-window controls.
        } finally { process.destroy() }
    }
}

