// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.*
import io.github.jadenyuki4.orbitmusic.data.NewPipeMusicSource
import io.github.jadenyuki4.orbitmusic.data.createDesktopHistoryStore
import io.github.jadenyuki4.orbitmusic.theme.*
import java.awt.SystemTray

fun main() = application {
    val source = remember { NewPipeMusicSource() }
    val history = remember { createDesktopHistoryStore() }
    val engine = remember { MpvEngine() }
    val scope = rememberCoroutineScope()
    val playback = remember { Playback(source, engine, scope) }
    val trayAvailable = remember { SystemTray.isSupported() }
    var visible by remember { mutableStateOf(true) }
    DisposableEffect(engine) {
        val keys = mediaKeys(engine, scope)
        onDispose { keys.cancel(); engine.close() }
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
        Tray(icon, tooltip = "Orbit Music", onAction = { visible = true }, menu = {
            Item("Open Orbit Music", onClick = { visible = true })
            Item("Play / pause", onClick = engine::toggle)
            Separator()
            Item("Quit", onClick = ::exitApplication)
        })
    }
    Window(visible = visible,
        onCloseRequest = { if (trayAvailable && engine.state.value.playing) visible = false else exitApplication() },
        title = "Orbit · Music",
        state = rememberWindowState(width = LayoutTokens(Density.Comfortable, 20f).windowWidth,
            height = LayoutTokens(Density.Comfortable, 20f).windowHeight),
    ) {
        App(source = source, history = history, playback = playback)
    }
}
