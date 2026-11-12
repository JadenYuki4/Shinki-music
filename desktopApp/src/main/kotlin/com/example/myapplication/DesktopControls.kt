// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import java.io.File

private interface Hotkeys : StdCallLibrary {
    fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Boolean
    fun UnregisterHotKey(window: Pointer?, id: Int): Boolean
    fun GetMessageW(msg: Pointer, window: Pointer?, min: Int, max: Int): Int
    fun PostThreadMessageW(thread: Int, message: Int, wParam: Pointer?, lParam: Pointer?): Boolean
}

private interface ThreadIds : StdCallLibrary {
    fun GetCurrentThreadId(): Int
}

/** Windows owns the hotkey registration on one message-queue thread. Linux delegates MPRIS to GIO. */
internal fun mediaKeys(engine: AudioEngine, scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
    engine.state.first { it.track != null && it.error == null }
    if (com.sun.jna.Platform.isWindows()) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val api = Native.load("user32", Hotkeys::class.java)
            if (api.RegisterHotKey(null, 1, 0x4000, 0xB3)) try {
                val thread = Native.load("kernel32", ThreadIds::class.java).GetCurrentThreadId()
                continuation.invokeOnCancellation { api.PostThreadMessageW(thread, 0x12, null, null) }
                Memory(64).use { msg ->
                    while (continuation.isActive && api.GetMessageW(msg, null, 0x312, 0x312) > 0)
                        engine.toggle()
                }
            } finally { api.UnregisterHotKey(null, 1) }
            if (continuation.isActive) continuation.resume(Unit)
        }
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
