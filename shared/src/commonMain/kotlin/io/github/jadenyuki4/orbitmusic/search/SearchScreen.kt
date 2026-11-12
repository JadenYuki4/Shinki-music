// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*

@Composable
fun SearchScreen(model: SearchViewModel, play: (MusicItem) -> Unit) {
    val controller = model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val t = LocalLayout.current
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.query, state.kind) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(t.medium), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.query,
            onValueChange = controller::changeQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            label = { Text("Search YouTube Music") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.submit() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        TextButton(
                            onClick = { controller.changeQuery("") },
                            modifier = Modifier.semantics { contentDescription = "Clear search query" },
                        ) { Text("Clear") }
                    }
                    TextButton(
                        onClick = controller::submit,
                        enabled = state.query.isNotBlank() && !state.loading,
                    ) { Text("Search") }
                }
            },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            items(MusicKind.entries, key = { it.name }) { kind ->
                FilterChip(
                    selected = state.kind == kind,
                    onClick = { controller.selectKind(kind) },
                    label = { Text(kind.pluralLabel()) },
                )
            }
        }
        // Exactly one lazy scroll surface; at most 300 result rows are retained by the state owner.
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(t.unit)) {
            if (!state.submitted && state.query.isBlank() && state.history.isNotEmpty()) {
                item("history-header") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium, color = p.primary)
                        TextButton(
                            onClick = controller::clearHistory,
                            modifier = Modifier.semantics { contentDescription = "Clear search history" },
                        ) { Text("Clear history") }
                    }
                }
                items(
                    items = state.history,
                    key = { "history:${it.kind.name}:${it.query}" },
                    contentType = { "search-history" },
                ) { entry ->
                    Surface(color = p.surface, shape = RoundedCornerShape(t.radius)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "Search again") { controller.chooseHistory(entry) }
                                .padding(horizontal = t.small, vertical = t.unit),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(t.small),
                        ) {
                            Text("↺", color = p.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.query,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    entry.kind.pluralLabel(),
                                    color = p.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(
                                onClick = { controller.removeHistory(entry) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Remove ${entry.query} from history"
                                },
                            ) { Text("Remove") }
                        }
                    }
                }
            }
            items(state.suggestions, key = { "suggestion:$it" }) { suggestion ->
                TextButton(onClick = { controller.chooseSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                    Text(suggestion, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            state.suggestionError?.let { error ->
                item("suggestions-error") {
                    ErrorNotice(error, controller::suggest)
                }
            }
            if (!state.submitted && state.suggestions.isEmpty() && state.suggestionError == null && state.history.isEmpty()) {
                item("hint") {
                    Text(
                        "Find a song, album, artist or playlist. Suggestions appear as you type.",
                        color = p.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.items, key = { it.id }, contentType = { "music-result" }) { item ->
                Surface(color = p.surface, shape = RoundedCornerShape(t.radius)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.kind == MusicKind.Song, onClickLabel = "Play song") { play(item) }
                            .padding(t.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.small),
                    ) {
                        // Artwork requests are deferred until the bounded image cache in d.
                        Box(
                            Modifier.size(t.touch)
                                .background(p.elevated, RoundedCornerShape(t.unit))
                                .semantics { contentDescription = item.kind.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (item.kind == MusicKind.Song) "♪" else "◈", color = p.primary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                item.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = p.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item.durationSeconds?.let { duration ->
                            Text(
                                "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                                color = p.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            state.error?.let { error -> item("search-error") { ErrorNotice(error, controller::retry) } }
            if (state.loading) item("loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Searching" })
            }
            if (state.submitted && !state.loading && state.error == null && state.items.isEmpty()) item("empty") {
                Text("No results. Try another search or category.", color = p.muted)
            }
            if (state.nextPage != null && !state.loading && state.error == null) item("more") {
                OutlinedButton(onClick = controller::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
            }
            if (state.reachedLimit) item("limit") {
                Text("Showing up to 300 results. Refine your search to find more.", color = p.muted)
            }
            if (state.items.any { it.kind == MusicKind.Song }) item("playback-note") {
                Text(
                    "Select a song to play it.",
                    color = p.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun MusicKind.pluralLabel(): String = when (this) {
    MusicKind.Song -> "Songs"
    MusicKind.Album -> "Albums"
    MusicKind.Artist -> "Artists"
    MusicKind.Playlist -> "Playlists"
}

@Composable
private fun ErrorNotice(error: SourceError, retry: () -> Unit) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Column(verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Text(error.userMessage(), color = p.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = retry) { Text(if (error == SourceError.InvalidRequest) "Search again" else "Retry") }
    }
}
