// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*

val AestheticPetalShape: Shape = RoundedCornerShape(percent = 50)

val AestheticGemShape: Shape = RoundedCornerShape(22.dp)

@Composable
fun ShinkiBrandBadge(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val palette = LocalPalette.current
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        drawRoundRect(palette.elevated, cornerRadius = CornerRadius(side * 0.24f))
        val mark = Path().apply {
            moveTo(side * 0.73f, side * 0.26f)
            lineTo(side * 0.36f, side * 0.26f)
            lineTo(side * 0.25f, side * 0.44f)
            lineTo(side * 0.75f, side * 0.56f)
            lineTo(side * 0.64f, side * 0.74f)
            lineTo(side * 0.27f, side * 0.74f)
        }
        drawPath(mark, palette.primary, style = Stroke(width = side * 0.08f, join = StrokeJoin.Round))
    }
}

@Composable
fun SpotiFlacQualityBadge(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val p = LocalPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = if (highlighted) p.primary.copy(alpha = 0.18f) else Color(0xFF1E212B).copy(alpha = 0.90f),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) p.primary.copy(alpha = 0.45f) else p.muted.copy(alpha = 0.22f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) p.primary else p.text,
            maxLines = 1,
        )
    }
}

@Composable
fun SpotiFlacScallopPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.90f
            hovered -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val rOuter = this.size.minDimension / 2f
            val rCore = rOuter * 0.76f
            val lobeRadius = rOuter * 0.27f
            val lobeDist = rOuter * 0.72f
            val color = p.primary
            drawCircle(color = color, radius = rCore, center = Offset(cx, cy))
            val lobes = 10
            for (i in 0 until lobes) {
                val angle = (i * 2.0 * kotlin.math.PI / lobes)
                val lx = cx + (kotlin.math.cos(angle) * lobeDist).toFloat()
                val ly = cy + (kotlin.math.sin(angle) * lobeDist).toFloat()
                drawCircle(color = color, radius = lobeRadius, center = Offset(lx, ly))
            }
        }
        Canvas(Modifier.size(size * 0.36f)) {
            drawPlayerVectorIcon(
                kind = if (playing) PlayerIconKind.Pause else PlayerIconKind.Play,
                color = p.onAccent,
            )
        }
    }
}

enum class PlayerIconKind {
    Play, Pause, Previous, Next, Rewind10, Forward10, Shuffle, Repeat, RepeatOne, Stop, VolumeDown, VolumeUp, Speaker, Mute
}

@Composable
fun VividPlayerIconButton(
    icon: PlayerIconKind,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val targetScale = when {
        !enabled -> 1f
        pressed -> 0.88f
        hovered -> 1.10f
        active -> 1.03f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            pressed -> p.primary.copy(alpha = 0.32f)
            active -> p.primary.copy(alpha = 0.22f)
            hovered -> Color(0xFF343A4D)
            else -> Color(0xFF262A36)
        },
        animationSpec = tween(durationMillis = 160),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            pressed || active -> p.primary
            hovered -> p.primary.copy(alpha = 0.55f)
            else -> p.muted.copy(alpha = 0.18f)
        },
        animationSpec = tween(durationMillis = 160),
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> p.muted.copy(alpha = 0.45f)
            active || pressed -> p.primary
            hovered -> p.text
            else -> p.text.copy(alpha = 0.92f)
        },
        animationSpec = tween(durationMillis = 160),
    )

    Surface(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = contentDesc
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(
            width = if (active || hovered) 1.4.dp else 1.dp,
            color = borderColor,
        ),
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            drawPlayerVectorIcon(icon, iconColor)
        }
    }
}

@Composable
fun VividPlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.90f
            hovered -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )

    Surface(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = if (playing) "Pausar" else "Reproducir"
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(percent = 50),
        color = p.primary,
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = if (hovered) 0.35f else 0.15f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.40f)) {
                drawPlayerVectorIcon(
                    kind = if (playing) PlayerIconKind.Pause else PlayerIconKind.Play,
                    color = p.onAccent,
                )
            }
        }
    }
}

@Composable
fun VividPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accentColor: Color = LocalPalette.current.primary,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.94f
            hovered -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    val bg by animateColorAsState(
        targetValue = when {
            active -> accentColor
            pressed -> accentColor.copy(alpha = 0.28f)
            hovered -> Color(0xFF2B2F3C)
            else -> Color(0xFF20232D)
        },
        animationSpec = tween(160),
    )
    val border by animateColorAsState(
        targetValue = when {
            active -> accentColor
            hovered || pressed -> accentColor.copy(alpha = 0.65f)
            else -> p.muted.copy(alpha = 0.22f)
        },
        animationSpec = tween(160),
    )
    val fgColor = if (active) p.onAccent else if (hovered) accentColor else p.text

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(percent = 50),
        color = bg,
        border = BorderStroke(
            width = 1.dp,
            color = border,
        ),
    ) {
        Box {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = fgColor,
                maxLines = 1,
            )
        }
    }
}

private fun DrawScope.drawPlayerVectorIcon(kind: PlayerIconKind, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = (size.minDimension * 0.11f).coerceAtLeast(1.8f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        PlayerIconKind.Play -> {
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.50f)
                lineTo(w * 0.22f, h * 0.88f)
                close()
            }
            drawPath(path, color)
        }
        PlayerIconKind.Pause -> {
            val barW = w * 0.24f
            val barH = h * 0.76f
            val top = h * 0.12f
            drawRoundRect(color, Offset(w * 0.16f, top), Size(barW, barH), CornerRadius(barW * 0.35f))
            drawRoundRect(color, Offset(w * 0.60f, top), Size(barW, barH), CornerRadius(barW * 0.35f))
        }
        PlayerIconKind.Previous -> {
            drawRoundRect(color, Offset(w * 0.10f, h * 0.16f), Size(w * 0.10f, h * 0.68f), CornerRadius(2f))
            val tri1 = Path().apply {
                moveTo(w * 0.54f, h * 0.18f)
                lineTo(w * 0.22f, h * 0.50f)
                lineTo(w * 0.54f, h * 0.82f)
                close()
            }
            val tri2 = Path().apply {
                moveTo(w * 0.90f, h * 0.18f)
                lineTo(w * 0.54f, h * 0.50f)
                lineTo(w * 0.90f, h * 0.82f)
                close()
            }
            drawPath(tri1, color)
            drawPath(tri2, color)
        }
        PlayerIconKind.Next -> {
            val tri1 = Path().apply {
                moveTo(w * 0.10f, h * 0.18f)
                lineTo(w * 0.46f, h * 0.50f)
                lineTo(w * 0.10f, h * 0.82f)
                close()
            }
            val tri2 = Path().apply {
                moveTo(w * 0.46f, h * 0.18f)
                lineTo(w * 0.78f, h * 0.50f)
                lineTo(w * 0.46f, h * 0.82f)
                close()
            }
            drawPath(tri1, color)
            drawPath(tri2, color)
            drawRoundRect(color, Offset(w * 0.80f, h * 0.16f), Size(w * 0.10f, h * 0.68f), CornerRadius(2f))
        }
        PlayerIconKind.Rewind10 -> {
            val p1 = Path().apply {
                moveTo(w * 0.48f, h * 0.20f)
                lineTo(w * 0.16f, h * 0.50f)
                lineTo(w * 0.48f, h * 0.80f)
            }
            val p2 = Path().apply {
                moveTo(w * 0.84f, h * 0.20f)
                lineTo(w * 0.52f, h * 0.50f)
                lineTo(w * 0.84f, h * 0.80f)
            }
            drawPath(p1, color, style = stroke)
            drawPath(p2, color, style = stroke)
        }
        PlayerIconKind.Forward10 -> {
            val p1 = Path().apply {
                moveTo(w * 0.16f, h * 0.20f)
                lineTo(w * 0.48f, h * 0.50f)
                lineTo(w * 0.16f, h * 0.80f)
            }
            val p2 = Path().apply {
                moveTo(w * 0.52f, h * 0.20f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.52f, h * 0.80f)
            }
            drawPath(p1, color, style = stroke)
            drawPath(p2, color, style = stroke)
        }
        PlayerIconKind.Shuffle -> {
            val p1 = Path().apply {
                moveTo(w * 0.12f, h * 0.72f)
                lineTo(w * 0.36f, h * 0.72f)
                lineTo(w * 0.66f, h * 0.26f)
                lineTo(w * 0.88f, h * 0.26f)
            }
            val p2 = Path().apply {
                moveTo(w * 0.12f, h * 0.26f)
                lineTo(w * 0.36f, h * 0.26f)
                lineTo(w * 0.66f, h * 0.72f)
                lineTo(w * 0.88f, h * 0.72f)
            }
            drawPath(p1, color, style = stroke)
            drawPath(p2, color, style = stroke)
            drawLine(color, Offset(w * 0.74f, h * 0.14f), Offset(w * 0.88f, h * 0.26f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.74f, h * 0.38f), Offset(w * 0.88f, h * 0.26f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.74f, h * 0.60f), Offset(w * 0.88f, h * 0.72f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.74f, h * 0.84f), Offset(w * 0.88f, h * 0.72f), stroke.width, StrokeCap.Round)
        }
        PlayerIconKind.Repeat, PlayerIconKind.RepeatOne -> {
            val loop = Path().apply {
                moveTo(w * 0.20f, h * 0.42f)
                lineTo(w * 0.20f, h * 0.26f)
                lineTo(w * 0.80f, h * 0.26f)
                moveTo(w * 0.80f, h * 0.58f)
                lineTo(w * 0.80f, h * 0.74f)
                lineTo(w * 0.20f, h * 0.74f)
            }
            drawPath(loop, color, style = stroke)
            drawLine(color, Offset(w * 0.66f, h * 0.14f), Offset(w * 0.82f, h * 0.26f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.66f, h * 0.38f), Offset(w * 0.82f, h * 0.26f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.34f, h * 0.62f), Offset(w * 0.18f, h * 0.74f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.34f, h * 0.86f), Offset(w * 0.18f, h * 0.74f), stroke.width, StrokeCap.Round)
            if (kind == PlayerIconKind.RepeatOne) {
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.50f, h * 0.50f))
            }
        }
        PlayerIconKind.Stop -> {
            drawRoundRect(color, Offset(w * 0.20f, h * 0.20f), Size(w * 0.60f, h * 0.60f), CornerRadius(w * 0.12f))
        }
        PlayerIconKind.VolumeDown -> {
            drawLine(color, Offset(w * 0.22f, h * 0.50f), Offset(w * 0.78f, h * 0.50f), stroke.width * 1.15f, StrokeCap.Round)
        }
        PlayerIconKind.VolumeUp -> {
            drawLine(color, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), stroke.width * 1.15f, StrokeCap.Round)
            drawLine(color, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.80f), stroke.width * 1.15f, StrokeCap.Round)
        }
        PlayerIconKind.Speaker, PlayerIconKind.Mute -> {
            val cone = Path().apply {
                moveTo(w * 0.12f, h * 0.38f)
                lineTo(w * 0.32f, h * 0.38f)
                lineTo(w * 0.56f, h * 0.16f)
                lineTo(w * 0.56f, h * 0.84f)
                lineTo(w * 0.32f, h * 0.62f)
                lineTo(w * 0.12f, h * 0.62f)
                close()
            }
            drawPath(cone, color)
            if (kind == PlayerIconKind.Mute) {
                drawLine(color, Offset(w * 0.66f, h * 0.34f), Offset(w * 0.90f, h * 0.66f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.90f, h * 0.34f), Offset(w * 0.66f, h * 0.66f), stroke.width, StrokeCap.Round)
            } else {
                drawArc(
                    color = color,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * 0.46f, h * 0.24f),
                    size = Size(w * 0.40f, h * 0.52f),
                    style = stroke,
                )
            }
        }
    }
}

@Composable
fun AnimeEqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val heights = if (playing) listOf(0.95f, 0.55f, 0.85f, 0.45f, 0.75f) else listOf(0.35f, 0.60f, 0.40f, 0.70f, 0.30f)
    Canvas(modifier.size(width = 22.dp, height = 18.dp)) {
        val barWidth = size.width / (heights.size * 1.7f)
        val gap = barWidth * 0.7f
        heights.forEachIndexed { idx, ratio ->
            val bh = size.height * ratio
            val x = idx * (barWidth + gap)
            val y = size.height - bh
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(p.primary, p.secondary)),
                topLeft = Offset(x, y),
                size = Size(barWidth, bh),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
fun TimingTransportBar(
    playback: Playback?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val p = LocalPalette.current
    val state = playback.displayTransport()
    var localVolume by remember { mutableFloatStateOf(1f) }
    var localMuted by remember { mutableStateOf(false) }
    var localShuffle by remember { mutableStateOf(false) }
    var localRepeat by remember { mutableStateOf(RepeatMode.Off) }
    var localPlaying by remember { mutableStateOf(false) }

    val volume = playback?.volume?.collectAsState()?.value ?: localVolume
    val muted = playback?.muted?.collectAsState()?.value ?: localMuted
    val shuffle = playback?.shuffle?.collectAsState()?.value ?: localShuffle
    val repeat = playback?.repeat?.collectAsState()?.value ?: localRepeat
    val isPlaying = if (playback != null) state.playing else localPlaying
    var dragging by remember(state.track?.id) { mutableStateOf<Float?>(null) }

    val duration = (state.duration.takeIf { it > 0L } ?: state.track?.durationSeconds ?: 0L).coerceAtLeast(0L)
    val currentPos = (dragging ?: state.position.coerceAtLeast(0L).toFloat()).toLong().coerceAtLeast(0L)
    val btnSize = if (compact) 32.dp else 36.dp
    val playSize = if (compact) 40.dp else 46.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!compact) {
            VividPlayerIconButton(
                icon = PlayerIconKind.Shuffle,
                contentDesc = "Aleatorio",
                active = shuffle,
                size = btnSize,
                onClick = {
                    if (playback != null) playback.shuffle.value = !shuffle
                    else localShuffle = !localShuffle
                },
            )
        }
        VividPlayerIconButton(
            icon = PlayerIconKind.Previous,
            contentDesc = "Canción anterior",
            size = btnSize,
            onClick = { playback?.back() },
        )
        if (!compact) {
            VividPlayerIconButton(
                icon = PlayerIconKind.Rewind10,
                contentDesc = "Retroceder 10 segundos",
                size = btnSize,
                onClick = { playback?.seekBy(-10) },
            )
        }
        VividPlayPauseButton(
            playing = isPlaying,
            onClick = {
                if (playback != null) playback.playOrToggle()
                else localPlaying = !localPlaying
            },
            size = playSize,
        )
        if (!compact) {
            VividPlayerIconButton(
                icon = PlayerIconKind.Forward10,
                contentDesc = "Adelantar 10 segundos",
                size = btnSize,
                onClick = { playback?.seekBy(10) },
            )
        }
        VividPlayerIconButton(
            icon = PlayerIconKind.Next,
            contentDesc = "Siguiente canción",
            size = btnSize,
            onClick = {
                if (playback != null) {
                    if (state.track == null && playback.queue.value.isEmpty()) playback.playOrToggle()
                    else playback.next()
                }
            },
        )
        if (!compact) {
            VividPlayerIconButton(
                icon = if (repeat == RepeatMode.One) PlayerIconKind.RepeatOne else PlayerIconKind.Repeat,
                contentDesc = "Modo de repetición",
                active = repeat != RepeatMode.Off,
                size = btnSize,
                onClick = {
                    val nextMode = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]
                    if (playback != null) playback.repeat.value = nextMode
                    else localRepeat = nextMode
                },
            )
        }

        Text(
            clock(currentPos),
            color = p.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (duration > 0L) {
            Slider(
                value = dragging ?: state.position.coerceIn(0, duration).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { playback?.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f).height(22.dp),
            )
        } else {
            Canvas(Modifier.weight(1f).height(18.dp)) {
                val trackH = 5.dp.toPx()
                val cy = size.height / 2f
                val progress = 0f
                val thumbX = size.width * progress
                drawRoundRect(
                    color = Color(0xFF2B2F3D),
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
                drawRoundRect(
                    color = p.primary,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(thumbX, trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
                val barW = 4.dp.toPx()
                val barH = 16.dp.toPx()
                drawRoundRect(
                    color = p.primary,
                    topLeft = Offset(thumbX - barW / 2f, cy - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
        Text(
            if (duration > 0L) clock(duration) else "0:00",
            color = p.muted,
            style = MaterialTheme.typography.labelSmall,
        )

        VividPlayerIconButton(
            icon = PlayerIconKind.VolumeDown,
            contentDesc = "Bajar volumen",
            size = btnSize,
            onClick = {
                if (playback != null) playback.volumeDown(0.1f)
                else { localMuted = false; localVolume = (localVolume - 0.1f).coerceIn(0f, 1f) }
            },
        )
        if (!compact) {
            val t = LocalLayout.current
            var open by remember { mutableStateOf(false) }
            var touch by remember { mutableIntStateOf(0) }
            val interactions = remember { MutableInteractionSource() }
            val dragging by interactions.collectIsDraggedAsState()
            val pressed by interactions.collectIsPressedAsState()
            LaunchedEffect(open, touch, dragging, pressed) {
                if (open && !dragging && !pressed) {
                    kotlinx.coroutines.delay(4000)
                    open = false
                }
            }
            Box {
                TextButton(onClick = { open = true; touch++ }) {
                    Text("${kotlin.math.round(volume * 100).toInt()}%", color = if (muted) p.warning else p.primary)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Column(Modifier.width(t.sidebar).padding(horizontal = t.small)) {
                        Text("Volumen · ${kotlin.math.round(volume * 100).toInt()}%")
                        Slider(value = volume, onValueChange = {
                            touch++
                            if (playback != null) playback.setVolume(it)
                            else { localVolume = it; localMuted = it == 0f }
                        }, onValueChangeFinished = { touch++ }, interactionSource = interactions,
                            modifier = Modifier.semantics { contentDescription = "Ajustar volumen" })
                        TextButton(onClick = {
                            playback?.toggleMute() ?: run { localMuted = !localMuted }
                            touch++
                        }) { Text(if (muted) "Activar sonido" else "Silenciar") }
                    }
                }
            }
        }
        VividPlayerIconButton(
            icon = PlayerIconKind.VolumeUp,
            contentDesc = "Subir volumen",
            size = btnSize,
            onClick = {
                if (playback != null) playback.volumeUp(0.1f)
                else { localMuted = false; localVolume = (localVolume + 0.1f).coerceIn(0f, 1f) }
            },
        )
    }
}

@Composable
fun SignalSleeve(modifier: Modifier) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val effects = LocalEffects.current
    val backdrop = remember { loadAnimeBackdrop() }
    Box(modifier.background(Color(0xFF232734))) {
        if (backdrop != null) {
            Image(
                bitmap = backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val hasPhoto = backdrop != null
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        p.background.copy(alpha = if (hasPhoto) 0.10f else 0.70f),
                        p.surface.copy(alpha = if (hasPhoto) 0.22f else 0.55f),
                        p.background.copy(alpha = if (hasPhoto) 0.65f else 0.88f),
                    )
                )
            )
            if (effects.glow > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.primary.copy(alpha = 0.22f), Color.Transparent)
                    ),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.75f, size.height * 0.35f),
                )
            }
            if (!hasPhoto) {
                val center = Offset(size.width * 0.50f, size.height * 0.50f)
                val radius = (size.minDimension * 0.30f).coerceAtMost(64f)
                drawCircle(p.background.copy(alpha = 0.65f), radius, center)
                drawCircle(p.primary.copy(alpha = 0.65f), radius, center, style = Stroke(t.hairline.toPx()))
                drawCircle(p.secondary.copy(alpha = 0.85f), radius * 0.55f, center, style = Stroke(t.hairline.toPx()))
            }
        }
    }
}

@Composable
fun PlayerDock(playback: Playback?, expand: () -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val state = playback.displayTransport()
    val pending = playback?.pending?.collectAsState()?.value
    val error = playback?.failure?.collectAsState()?.value ?: state.error

    var localQuality by remember { mutableStateOf(AudioQuality.High) }
    var localFormat by remember { mutableStateOf(AudioFormatPreference.Auto) }

    val quality = playback?.audioQuality?.collectAsState()?.value ?: localQuality
    val format = playback?.audioFormat?.collectAsState()?.value ?: localFormat
    val streamInfo = playback?.currentStream?.collectAsState()?.value
    var qualityMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Color(0xFF16181F))
    ) {
        val compact = maxWidth < 760.dp
        Column(
            Modifier.fillMaxWidth().padding(horizontal = t.small, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimingTransportBar(
                playback = playback,
                compact = compact,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, p.primary.copy(alpha = 0.30f)),
                    color = p.elevated,
                ) {
                    TrackArt(
                        state.track ?: pending,
                        Modifier.size(44.dp).clickable(
                            enabled = state.track != null,
                            onClickLabel = "Alternar reproductor completo",
                            onClick = expand,
                        ),
                    )
                }
                Column(
                    Modifier.weight(1f).clickable(
                        enabled = state.track != null,
                        onClickLabel = "Alternar reproductor completo",
                        onClick = expand,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AnimeEqualizerBars(playing = state.playing)
                        Text(
                            pending?.title ?: state.track?.title ?: "Sin reproducción",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = p.text,
                        )
                        SpotiFlacQualityBadge(
                            text = if (streamInfo?.formatLabel?.contains("OFFLINE") == true) "OFFLINE" else quality.bitDepthLabel,
                            highlighted = true,
                        )
                    }
                    Text(
                        if (pending != null) "Cargando audio…" else state.track?.subtitle ?: "Elegí una canción",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = p.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Box {
                    val fmtBadge = streamInfo?.formatLabel ?: format.shortLabel
                    val badgeLabel = if (compact) {
                        "✦ ${quality.bitDepthLabel} · $fmtBadge ▾"
                    } else {
                        "✦ ${quality.spotiFlacBadge} ($fmtBadge) ▾"
                    }
                    VividPillButton(
                        text = badgeLabel,
                        active = false,
                        accentColor = p.primary,
                        onClick = { qualityMenu = true },
                    )
                    QualityFormatDropdown(
                        expanded = qualityMenu,
                        onDismiss = { qualityMenu = false },
                        quality = quality,
                        format = format,
                        onSelectQuality = { q ->
                            if (playback != null) playback.setAudioQuality(q) else localQuality = q
                            qualityMenu = false
                        },
                        onSelectFormat = { fmt ->
                            if (playback != null) playback.setAudioFormat(fmt) else localFormat = fmt
                            qualityMenu = false
                        },
                    )
                }
            }

            if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), color = p.error)
                TextButton(onClick = { playback?.retry() }) { Text("Reintentar") }
            }
        }
    }
}

@Composable
private fun QualityFormatDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    quality: AudioQuality,
    format: AudioFormatPreference,
    onSelectQuality: (AudioQuality) -> Unit,
    onSelectFormat: (AudioFormatPreference) -> Unit,
) {
    val p = LocalPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        AudioQuality.entries.forEach { q ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            (if (q == quality) "✓ " else "") + q.label,
                            color = if (q == quality) p.primary else p.text,
                        )
                        Text("${q.description} (${q.approxSizeLabel})", style = MaterialTheme.typography.labelSmall, color = p.muted)
                    }
                },
                onClick = { onSelectQuality(q) },
            )
        }
        HorizontalDivider(color = p.elevated)
        AudioFormatPreference.entries.forEach { fmt ->
            DropdownMenuItem(
                text = {
                    Text(
                        (if (fmt == format) "✓ Formato: " else "Formato: ") + fmt.label,
                        color = if (fmt == format) p.secondary else p.text,
                    )
                },
                onClick = { onSelectFormat(fmt) },
            )
        }
    }
}

internal fun clock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
