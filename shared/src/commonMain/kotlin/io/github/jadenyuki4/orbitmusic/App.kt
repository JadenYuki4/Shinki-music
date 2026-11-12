// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.jadenyuki4.orbitmusic.theme.*
import io.github.jadenyuki4.orbitmusic.domain.MusicSource
import io.github.jadenyuki4.orbitmusic.di.*
import io.github.jadenyuki4.orbitmusic.search.SearchViewModel

/** Shared host: isolated DI container, lifecycle-owned state, and token-only UI. */
@Composable
fun App(systemReduceMotion: Boolean = false, source: MusicSource = PreviewMusicSource, playback: Playback? = null) {
    val graph = remember(source) { createAppGraph(source) }
    DisposableEffect(graph) { onDispose { graph.close() } }
    val model = viewModel { graph.koin.get<ThemeViewModel>() }
    val search = viewModel { graph.koin.get<SearchViewModel>() }
    val settings = model.state
    OrbitTheme(settings, systemReduceMotion) {
        val t = LocalLayout.current
        val p = LocalPalette.current
        MusicShell(search, playback) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                verticalArrangement = Arrangement.spacedBy(t.medium)) {
                Text("Appearance", style = MaterialTheme.typography.headlineMedium)
                Text("Make this space yours.", color = p.muted)
                ThemeControls(settings, model::dispatch, Modifier.fillMaxWidth())
                TokenStrip()
                Text("Theme changes last for this session. Saved themes arrive with the customization phase.",
                    style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val effects = LocalEffects.current
    Surface(modifier, shape = RoundedCornerShape(t.radius), color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha + effects.glow * t.glowAlpha))) {
        Column(Modifier.padding(t.medium), verticalArrangement = Arrangement.spacedBy(t.small), content = content)
    }
}

@Composable
private fun ThemeControls(settings: ThemeSettings, dispatch: (ThemeAction) -> Unit, modifier: Modifier) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val warnings = remember(p) { p.contrastWarnings() }
    Panel(modifier) {
        Text("TUNE YOUR SPACE", style = MaterialTheme.typography.labelSmall, color = p.primary)
        Text("Theme studio", style = MaterialTheme.typography.titleLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = t.touch)) {
                Text(settings.preset.label + "  ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemePreset.entries.filter { it != ThemePreset.Custom }.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.label) }, onClick = {
                        dispatch(ThemeAction.SelectPreset(preset)); expanded = false
                    })
                }
            }
        }
        Toggle("Low power / reduce effects", settings.lowPower) { dispatch(ThemeAction.SetLowPower(it)) }
        Toggle("Reduce motion", settings.reduceMotion) { dispatch(ThemeAction.SetReduceMotion(it)) }
        Text("Corner radius · ${settings.roundness.toInt()}", color = p.muted, style = MaterialTheme.typography.bodyMedium)
        Slider(value = settings.roundness, onValueChange = { dispatch(ThemeAction.SetRoundness(it)) },
            valueRange = t.minRoundness..t.maxRoundness,
            modifier = Modifier.semantics { contentDescription = "Corner radius" })
        Text(if (warnings.isEmpty()) "AA contrast · all checked text pairs pass"
            else warnings.joinToString("\n"), color = if (warnings.isEmpty()) p.success else p.warning,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalLayout.current
    Row(Modifier.fillMaxWidth().heightIn(min = t.touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.unit)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun TokenStrip() {
    val t = LocalLayout.current
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        Text("PALETTE / LIVE TOKENS", color = p.muted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            listOf("Accent" to p.primary, "Secondary" to p.secondary, "Highlight" to p.highlight,
                "Text" to p.text, "Muted" to p.muted).forEach { (label, color) ->
                Box(Modifier.size(t.swatch).background(color, RoundedCornerShape(t.unit))
                    .semantics { contentDescription = "$label color" })
            }
        }
    }
}
