package io.github.jadenyuki4.orbitmusic

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.theme.LocalEffects
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.sin

internal expect suspend fun loadArt(url: String): ImageBitmap?
internal expect fun loadAnimeBackdrop(): ImageBitmap?
internal expect fun loadPlayerAnimeGifBackdrop(): ImageBitmap?

@Composable
fun TrackArt(
    item: MusicItem?,
    modifier: Modifier,
    localArtPath: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val url = localArtPath?.takeIf { it.isNotBlank() } ?: item?.artworkUrl
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url != null) image = try { loadArt(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val current = image
    if (current == null) SignalSleeve(modifier)
    else Image(current, null, modifier, contentScale = contentScale, filterQuality = FilterQuality.High)
}

@Composable
fun AnimeGifBackdropCanvas(
    playing: Boolean,
    modifier: Modifier = Modifier,
    focalBiasX: Float = 0.08f,
    compactCard: Boolean = false,
    cleanStage: Boolean = false,
) {
    val gifImage = remember { loadPlayerAnimeGifBackdrop() }
    val effects = LocalEffects.current
    val animated = effects.durationMillis > 0

    val zoom: Float
    val panX: Float
    val panY: Float
    val glowPulse: Float
    val beatPhase: Float

    if (animated) {
        val transition = rememberInfiniteTransition()
        zoom = transition.animateFloat(
            initialValue = 1.02f,
            targetValue = if (playing) 1.09f else 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        ).value
        panX = transition.animateFloat(
            initialValue = -8f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        ).value
        panY = transition.animateFloat(
            initialValue = -4f,
            targetValue = 4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        ).value
        glowPulse = transition.animateFloat(
            initialValue = 0.25f,
            targetValue = if (playing) 0.95f else 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        ).value
        beatPhase = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (playing) 1700 else 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        ).value
    } else {
        zoom = 1.02f
        panX = 0f
        panY = 0f
        glowPulse = 0.45f
        beatPhase = 0f
    }

    val fullCanvas = cleanStage || compactCard
    Box(modifier.clipToBounds().background(Color(0xFF0A0416))) {
        if (gifImage != null) {
            val imageModifier = if (fullCanvas) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().height(230.dp).align(Alignment.TopCenter)
            }
            Image(
                bitmap = gifImage,
                contentDescription = null,
                modifier = imageModifier.graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                },
                alignment = BiasAlignment(focalBiasX, if (cleanStage) 0f else -0.1f),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val neonPink = Color(0xFFFF4AE2)
            val neonViolet = Color(0xFF9D4EDD)
            val darkNight = Color(0xFF090314)

            if (!fullCanvas) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.46f to Color.Transparent,
                        0.66f to darkNight.copy(alpha = 0.85f),
                        1.0f to darkNight,
                    )
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        neonPink.copy(alpha = (if (cleanStage) 0.14f else 0.24f) * glowPulse),
                        neonViolet.copy(alpha = (if (cleanStage) 0.08f else 0.12f) * glowPulse),
                        Color.Transparent,
                    )
                ),
                radius = size.minDimension * 0.56f,
                center = Offset(size.width * 0.38f, size.height * (if (fullCanvas) 0.44f else 0.30f)),
            )

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color(0xFFFF7AF0).copy(alpha = (if (cleanStage) 0.12f else 0.20f) * glowPulse),
                        Color.Transparent,
                    )
                ),
                radius = size.minDimension * 0.34f,
                center = Offset(size.width * 0.72f, size.height * (if (fullCanvas) 0.45f else 0.34f)),
            )

            val starPositions = listOf(0.10f to 0.12f, 0.24f to 0.08f, 0.48f to 0.10f, 0.86f to 0.14f, 0.92f to 0.26f)
            starPositions.forEachIndexed { idx, (sx, sy) ->
                val twinkle = 0.35f + 0.65f * ((sin(beatPhase + idx * 1.4f) + 1f) / 2f)
                drawCircle(
                    color = Color(0xFFFFB8FA).copy(alpha = 0.55f * twinkle),
                    radius = (1.6.dp.toPx()) * (0.7f + 0.5f * twinkle),
                    center = Offset(size.width * sx, size.height * sy),
                )
            }

            if (!cleanStage) {
                val barCount = if (compactCard) 20 else 28
                val barSlot = size.width / barCount
                val barWidth = barSlot * 0.56f
                val baseLineY = if (compactCard) size.height * 0.96f else size.height * 0.62f
                val maxBarHeight = if (compactCard) size.height * 0.22f else size.height * 0.15f
                for (i in 0 until barCount) {
                    val wave = ((sin(beatPhase + i * 0.65f) + 1f) * 0.5f) *
                        ((sin(beatPhase * 0.7f - i * 0.3f) + 1f) * 0.5f)
                    val ratio = if (playing) (0.20f + 0.80f * wave) else (0.14f + 0.28f * wave)
                    val barH = maxBarHeight * ratio
                    val x = i * barSlot + (barSlot - barWidth) / 2f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                Color(0xFFFF75EE).copy(alpha = if (compactCard) 0.36f else 0.60f),
                                neonViolet.copy(alpha = if (compactCard) 0.18f else 0.30f),
                            )
                        ),
                        topLeft = Offset(x, baseLineY - barH),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                }
            }

            drawRect(
                brush = if (cleanStage) {
                    Brush.verticalGradient(
                        0.0f to Color(0x18090314),
                        0.75f to Color.Transparent,
                        1.0f to Color(0x4D080212),
                    )
                } else {
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.35f to Color(0x33120624),
                        0.72f to Color(0xCC0C041A),
                        1.0f to Color(0xF5080212),
                    )
                }
            )
        }
    }
}
