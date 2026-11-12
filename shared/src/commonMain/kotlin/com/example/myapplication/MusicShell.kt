// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.example.myapplication.domain.MusicKind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CutCornerShape
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.myapplication.theme.*
import com.example.myapplication.search.*
import androidx.compose.ui.input.key.*

private enum class Destination(val label: String, val symbol: String) {
    Home("Home", "⌂"), Search("Search", "⌕"), Library("Library", "▤"), Settings("Settings", "⚙")
}

/** Limusic-inspired layout foundation; empty states stay explicit until sources/playback exist. */
@Composable
fun MusicShell(search: SearchViewModel, playback: Playback?, appearance: @Composable () -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    var destination by remember { mutableStateOf(Destination.Home) }
    var panel by remember { mutableStateOf("Queue") }
    var theater by remember { mutableStateOf(false) }
    val trackFlow = remember(playback) { playback?.engine?.state?.map { it.track }?.distinctUntilChanged() }
    val track = trackFlow?.collectAsState(initial = playback?.engine?.state?.value?.track)?.value
    BoxWithConstraints(Modifier.fillMaxSize().background(p.background).safeDrawingPadding()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && theater) {
                theater = false
                true
            } else if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K) {
                theater = false
                destination = Destination.Search
                true
            } else false
        }) {
        val desktop = maxWidth >= t.wideBreakpoint
        val showPanel = maxWidth >= t.panesBreakpoint && destination != Destination.Settings
        Column(Modifier.fillMaxSize()) {
            Surface(color = p.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = t.small, vertical = t.unit),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("◈  Orbit", style = MaterialTheme.typography.titleMedium, color = p.primary)
                    Spacer(Modifier.weight(1f))
                    if (theater) TextButton(onClick = { theater = false }) { Text("Close player") }
                }
            }
            HorizontalDivider(color = p.elevated)
            Row(Modifier.weight(1f)) {
                if (desktop) Surface(Modifier.width(t.sidebar).fillMaxHeight(), color = p.surface) {
                    Column(Modifier.padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
                        Text("YOUR SPACE", Modifier.padding(t.unit), color = p.muted, style = MaterialTheme.typography.labelSmall)
                        Destination.entries.forEach { page ->
                            TextButton(onClick = { theater = false; destination = page },
                                modifier = Modifier.fillMaxWidth().heightIn(min = t.touch)
                                    .background(if (destination == page) p.elevated else p.surface, RoundedCornerShape(t.radius))) {
                                Text("${page.symbol}  ${page.label}", Modifier.fillMaxWidth(),
                                    color = if (destination == page) p.primary else p.muted)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = t.small), color = p.elevated)
                        Text("PLAYLISTS", Modifier.padding(t.unit), color = p.muted, style = MaterialTheme.typography.labelSmall)
                        Text("Your playlists will live here.", Modifier.padding(t.unit),
                            color = p.muted, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))

                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (theater && track != null) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.large),
                            verticalArrangement = Arrangement.spacedBy(t.small)) {
                            Text("Now playing", color = p.primary, style = MaterialTheme.typography.labelLarge)
                            Surface(shape = CutCornerShape(t.small), color = p.elevated) {
                                SignalSleeve(Modifier.fillMaxWidth().aspectRatio(1.6f))
                            }
                            Text(track.title, style = MaterialTheme.typography.headlineMedium)
                            Text(track.subtitle, color = p.muted, style = MaterialTheme.typography.titleMedium)
                        }
                    } else if (destination == Destination.Settings) appearance()
                    else if (destination == Destination.Search) SearchScreen(search) { playback?.select(it) }
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                        verticalArrangement = Arrangement.spacedBy(t.medium)) {
                        Text(destination.label, style = MaterialTheme.typography.headlineMedium)
                        if (destination == Destination.Home) {
                            Surface(shape = CutCornerShape(t.small), color = p.elevated) {
                                Column {
                                    SignalSleeve(Modifier.fillMaxWidth().height(t.artwork))
                                    Row(Modifier.fillMaxWidth().padding(t.small), horizontalArrangement = Arrangement.spacedBy(t.small), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Find your next track", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                        Button(onClick = { destination = Destination.Search }) { Text("Search") }
                                    }
                                }
                            }
                            Text("Browse by type", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                                MusicKind.entries.forEach { kind ->
                                    OutlinedButton(shape = CutCornerShape(t.unit), onClick = {
                                        search.controller.selectKind(kind)
                                        destination = Destination.Search
                                    }) { Text(kind.name + "s") }
                                }
                            }
                            val recent = playback?.recent?.collectAsState()?.value.orEmpty()
                            if (recent.isNotEmpty()) {
                                Text("Recently played", style = MaterialTheme.typography.titleLarge)
                                recent.forEach { item ->
                                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Play song") { playback?.select(item) }
                                        .padding(vertical = t.unit), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(t.small)) {
                                        Text("♪", color = p.primary)
                                        Column {
                                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                                            Text(item.subtitle, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        } else {
                            EmptyState("Your collection starts here", "Favorites, local playlists and history arrive with the library phase.")
                        }

                    }
                }
                if (showPanel) Surface(Modifier.width(t.detailPane).fillMaxHeight(), color = p.surface) {
                    Column(Modifier.padding(t.small), verticalArrangement = Arrangement.spacedBy(t.medium)) {
                        Row {
                            listOf("Queue", "Lyrics").forEach { name ->
                                TextButton(onClick = { panel = name }) {
                                    Text(name, color = if (name == panel) p.primary else p.muted)
                                }
                            }
                        }

                        if (panel == "Queue" && track != null) EmptyState(track.title, track.subtitle)
                        else EmptyState(if (panel == "Queue") "Your queue is empty" else "Lyrics unavailable",
                            if (panel == "Queue") "Select a song from Search." else "Lyrics are not available for this session.")
                    }
                }
            }
            HorizontalDivider(color = p.primary.copy(alpha = t.outlineAlpha))
            PlayerDock(playback) { theater = !theater }
            if (!desktop) NavigationBar(containerColor = p.surface) {
                Destination.entries.forEach { page ->
                    NavigationBarItem(selected = destination == page && !theater, onClick = { theater = false; destination = page },
                        icon = { Text(page.symbol) }, label = { Text(page.label) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, color = p.muted, style = MaterialTheme.typography.bodyMedium)
    }
}
