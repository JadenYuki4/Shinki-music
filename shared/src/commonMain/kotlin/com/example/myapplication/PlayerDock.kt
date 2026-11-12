// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import com.example.myapplication.theme.*

/** Static record sleeve: drawn at the actual canvas size, with no bitmaps or frame loop. */
@Composable
fun SignalSleeve(modifier: Modifier) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val effects = LocalEffects.current
    Canvas(modifier.background(p.elevated)) {
        val center = Offset(size.width * .72f, size.height * .52f)
        val radius = size.minDimension * .43f
        if (effects.glow > 0) drawRect(Brush.linearGradient(listOf(p.elevated, p.secondary.copy(alpha = effects.glow))))
        val sleeve = Path().apply {
            moveTo(size.width * .12f, size.height)
            lineTo(size.width * .48f, 0f)
            lineTo(size.width * .74f, 0f)
            lineTo(size.width * .38f, size.height)
            close()
        }
        drawPath(sleeve, p.secondary.copy(alpha = t.outlineAlpha))
        drawCircle(p.background, radius, center)
        val discBounds = Offset(center.x - radius, center.y - radius)
        val discSize = Size(radius * 2, radius * 2)
        drawArc(p.elevated, 120f, 43f, true, discBounds, discSize)
        drawArc(p.elevated, 300f, 43f, true, discBounds, discSize)
        for (ring in 2..8) drawCircle(if (ring == 8) p.primary else p.muted.copy(alpha = t.outlineAlpha),
            radius * ring / 8, center, style = Stroke(t.hairline.toPx()))
        drawArc(p.primary, 210f, 82f, false,
            Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
            style = Stroke(t.micro.toPx()))
        drawCircle(p.highlight, radius * .19f, center)
        drawCircle(p.surface, radius * .045f, center)
        if (size.width >= t.artwork.toPx()) {
            val pivot = Offset(size.width * .93f, size.height * .14f)
            val joint = Offset(size.width * .91f, size.height * .56f)
            val stylus = Offset(center.x + radius * .54f, center.y + radius * .38f)
            drawLine(p.muted, pivot, joint, t.micro.toPx())
            drawLine(p.muted, joint, stylus, t.micro.toPx())
            drawCircle(p.primary, t.micro.toPx(), stylus)
            drawCircle(p.background, t.unit.toPx(), pivot)
            drawCircle(p.secondary, t.micro.toPx(), pivot)
        }
        for (index in 0..5) {
            val x = size.width * index / 22
            drawLine(p.primary.copy(alpha = t.outlineAlpha), Offset(x, size.height), Offset(x + size.width * .25f, 0f), t.hairline.toPx())
        }
    }
}

@Composable
fun PlayerDock(playback: Playback?, expand: () -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val state = playback?.engine?.state?.collectAsState()?.value ?: Transport()
    val pending = playback?.pending?.collectAsState()?.value
    val error = playback?.failure?.collectAsState()?.value ?: state.error
    var dragging by remember(state.track?.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = t.small, vertical = t.unit)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.small)) {
            SignalSleeve(Modifier.size(t.touch).clickable(enabled = state.track != null,
                onClickLabel = "Toggle full player", onClick = expand))
            Column(Modifier.weight(1f).clickable(enabled = state.track != null,
                onClickLabel = "Toggle full player", onClick = expand)) {
                Text(pending?.title ?: state.track?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(if (pending != null) "Loading audio…" else state.track?.subtitle ?: "Choose a song in Search",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.muted)
            }
            Button(onClick = { playback?.engine?.toggle() }, enabled = state.track != null && pending == null && state.error == null,
                shape = CutCornerShape(t.unit)) { Text(if (state.playing) "Pause" else "Play") }
        }
        if (state.duration > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            Text(clock((dragging ?: state.position.toFloat()).toLong()), color = p.muted)
            Slider(value = dragging ?: state.position.coerceIn(0, state.duration).toFloat(),
                onValueChange = { dragging = it }, onValueChangeFinished = {
                    dragging?.let { playback?.engine?.seek(it.toLong()) }
                    dragging = null
                }, valueRange = 0f..state.duration.toFloat(), modifier = Modifier.weight(1f))
            Text(clock(state.duration), color = p.muted)
        }
        if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = p.error)
            TextButton(onClick = { playback?.retry() }) { Text("Retry") }
        }
    }
}

private fun clock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
