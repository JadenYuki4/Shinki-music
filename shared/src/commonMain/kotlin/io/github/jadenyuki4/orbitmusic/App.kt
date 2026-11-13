// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.theme.*
import io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore
import io.github.jadenyuki4.orbitmusic.domain.HistoryStore
import io.github.jadenyuki4.orbitmusic.domain.MusicSource
import io.github.jadenyuki4.orbitmusic.domain.SpotiFlacAppInfo
import io.github.jadenyuki4.orbitmusic.di.*
import io.github.jadenyuki4.orbitmusic.search.SearchViewModel

/** Shared host: isolated DI container, lifecycle-owned state, and token-only UI. */
@Composable
fun App(
    systemReduceMotion: Boolean = false,
    source: MusicSource = PreviewMusicSource,
    history: HistoryStore = InMemoryHistoryStore(),
    playback: Playback? = null,
) {
    val graph = remember(source, history) { createAppGraph(source, history) }
    DisposableEffect(graph) { onDispose { graph.close() } }
    val model = viewModel { graph.koin.get<ThemeViewModel>() }
    val search = viewModel { graph.koin.get<SearchViewModel>() }
    val settings = model.state
    OrbitTheme(settings, systemReduceMotion) {
        val t = LocalLayout.current
        val p = LocalPalette.current
        MusicShell(search, playback) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                verticalArrangement = Arrangement.spacedBy(t.medium),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpotiFlacBrandBadge(size = 32.dp)
                    Column {
                        Text("${SpotiFlacAppInfo.APP_NAME} · Estudio de Apariencia y Tema", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${SpotiFlacAppInfo.APP_NAME} v${SpotiFlacAppInfo.VERSION} (compilación ${SpotiFlacAppInfo.BUILD_NUMBER})",
                            color = p.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ThemeControls(settings, model::dispatch, Modifier.fillMaxWidth())
                TokenStrip()
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val effects = LocalEffects.current
    Surface(
        modifier,
        shape = RoundedCornerShape(t.radius),
        color = p.surface,
        border = BorderStroke(
            1.5.dp,
            Brush.linearGradient(
                listOf(
                    p.primary.copy(alpha = t.outlineAlpha + effects.glow * 0.35f),
                    p.secondary.copy(alpha = t.outlineAlpha + effects.glow * 0.25f),
                )
            ),
        ),
    ) {
        Column(Modifier.padding(t.medium), verticalArrangement = Arrangement.spacedBy(t.small), content = content)
    }
}

@Composable
private fun ThemeControls(settings: ThemeSettings, dispatch: (ThemeAction) -> Unit, modifier: Modifier) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val warnings = remember(p) { p.contrastWarnings() }
    Panel(modifier) {
        Text("PERSONALIZÁ TU ESPACIO · PALETAS DE COLOR", style = MaterialTheme.typography.labelSmall, color = p.primary)
        Text("Seleccioná una apariencia visual", style = MaterialTheme.typography.titleLarge)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.small),
            verticalArrangement = Arrangement.spacedBy(t.small),
        ) {
            ThemePreset.entries.filter { it != ThemePreset.Custom }.forEach { preset ->
                val pal = remember(preset) { paletteFor(preset) }
                val selected = settings.preset == preset
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> 0.94f
                        hovered -> 1.05f
                        selected -> 1.02f
                        else -> 1f
                    },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected || hovered) pal.primary else pal.muted.copy(alpha = 0.3f),
                    animationSpec = tween(200),
                )
                Surface(
                    modifier = Modifier
                        .width(210.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { dispatch(ThemeAction.SelectPreset(preset)) },
                        ),
                    shape = CutCornerShape(10.dp),
                    color = pal.surface,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (selected) "◈ " else "") + preset.label,
                                color = pal.text,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(pal.primary, pal.secondary, pal.highlight, pal.elevated).forEach { swatch ->
                                Box(
                                    Modifier.size(20.dp)
                                        .background(swatch, RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = p.elevated)

        Text("Resplandor Neón (Glow) · ${(settings.glow * 100).toInt()}%", color = p.primary, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.glow,
            onValueChange = { dispatch(ThemeAction.SetGlow(it)) },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Neon glow" },
        )

        Text("Intensidad de Animaciones · ${(settings.animationIntensity * 100).toInt()}%", color = p.secondary, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.animationIntensity,
            onValueChange = { dispatch(ThemeAction.SetAnimationIntensity(it)) },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Animation intensity" },
        )

        Text("Radio de esquinas · ${settings.roundness.toInt()}", color = p.muted, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.roundness,
            onValueChange = { dispatch(ThemeAction.SetRoundness(it)) },
            valueRange = t.minRoundness..t.maxRoundness,
            modifier = Modifier.semantics { contentDescription = "Radio de esquinas" },
        )

        Toggle("Bajo consumo / reducir efectos", settings.lowPower) { dispatch(ThemeAction.SetLowPower(it)) }
        Toggle("Reducir animaciones", settings.reduceMotion) { dispatch(ThemeAction.SetReduceMotion(it)) }

        Text(
            if (warnings.isEmpty()) "✓ Contraste AA · todos los pares de texto cumplen"
            else warnings.joinToString("\n"),
            color = if (warnings.isEmpty()) p.success else p.warning,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalLayout.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = t.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.unit),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun TokenStrip() {
    val t = LocalLayout.current
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        Text("PALETA / COLORES ACTIVOS", color = p.muted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            listOf(
                "Principal" to p.primary,
                "Secundario" to p.secondary,
                "Resaltado" to p.highlight,
                "Texto" to p.text,
                "Atenuado" to p.muted,
            ).forEach { (label, color) ->
                Box(
                    Modifier.size(t.swatch).background(color, RoundedCornerShape(t.unit))
                        .semantics { contentDescription = "Color $label" }
                )
            }
        }
    }
}
