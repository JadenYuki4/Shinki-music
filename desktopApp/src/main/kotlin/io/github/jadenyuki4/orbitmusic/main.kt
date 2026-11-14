// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.*
import io.github.jadenyuki4.orbitmusic.data.NewPipeMusicSource
import io.github.jadenyuki4.orbitmusic.data.createDesktopHistoryStore
import io.github.jadenyuki4.orbitmusic.data.createDesktopTrackDownloader
import io.github.jadenyuki4.orbitmusic.theme.*
import java.awt.SystemTray

fun main() = application {
    val source = remember { NewPipeMusicSource() }
    val history = remember { createDesktopHistoryStore() }
    val downloader = remember { createDesktopTrackDownloader() }
    val engine = remember { MpvEngine() }
    val scope = rememberCoroutineScope()
    val sessions = remember { SessionStore() }
    val playback = remember { Playback(source, engine, scope, history, downloader).also { it.persistSession(scope, sessions) } }
    val trayAvailable = remember { SystemTray.isSupported() }
    var visible by remember { mutableStateOf(true) }
    DisposableEffect(engine) {
        val keys = mediaKeys(playback, scope)
        onDispose { runCatching { sessions.save(playback.snapshotSession()) }; keys.cancel(); engine.close() }
    }
    if (trayAvailable) {
        val icon = remember { object : Painter() {
            override val intrinsicSize = Size.Unspecified
            override fun DrawScope.onDraw() {
                val p = Palette()
                drawCircle(p.background)
                drawCircle(p.primary, size.minDimension * .38f)
                drawCircle(p.elevated, size.minDimension * .25f)
                drawCircle(p.highlight, size.minDimension * .12f)
            }
        } }
        Tray(icon, tooltip = "Shinki music", onAction = { visible = true }, menu = {
            Item("Abrir Shinki music", onClick = { visible = true })
            Item("Reproducir / pausar", onClick = engine::toggle)
            Item("Canción anterior", onClick = playback::back)
            Item("Siguiente canción", onClick = { playback.next() })
            Separator()
            Item("Salir", onClick = ::exitApplication)
        })
    }
    Window(visible = visible,
        onCloseRequest = { if (trayAvailable && engine.state.value.playing) visible = false else exitApplication() },
        title = "Shinki music · Música Hi-Res",
        state = rememberWindowState(width = LayoutTokens(Density.Comfortable, 24f).windowWidth,
            height = LayoutTokens(Density.Comfortable, 24f).windowHeight),
    ) {
        App(source = source, history = history, playback = playback)
    }
}
