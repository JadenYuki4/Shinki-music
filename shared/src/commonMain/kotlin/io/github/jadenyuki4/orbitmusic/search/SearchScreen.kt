// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.search

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jadenyuki4.orbitmusic.*
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*

@Composable
fun SearchScreen(
    model: SearchViewModel,
    enqueue: (MusicItem) -> Unit,
    playNext: (MusicItem) -> Unit = enqueue,
    playback: Playback? = null,
    play: (MusicItem) -> Unit,
) {
    val rules = playback?.taste?.rules?.collectAsState()?.value.orEmpty()
    val controller = model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val plays by remember(controller) { controller.historyStore.observeRecentPlays(20) }.collectAsState(emptyList())
    val favorites by remember(controller) { controller.historyStore.observeFavorites() }.collectAsState(emptyList())
    val p = LocalPalette.current
    val t = LocalLayout.current
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.query, state.kind, state.repository) { list.scrollToItem(0) }

    var contextualPicks by remember(state.submitted, state.query, state.items, favorites, plays, rules, state.incognito) {
        mutableStateOf<List<Pick>>(emptyList())
    }
    LaunchedEffect(state.submitted, state.query, state.items, favorites, plays, rules, state.incognito) {
        if (state.submitted && state.query.isNotBlank() && !state.incognito) {
            val res = searchContextualRecommendations(
                source = controller.source,
                query = state.query,
                currentItems = state.items,
                searches = state.history,
                plays = plays,
                favorites = favorites, rules = rules,
            )
            if (res is SourceResult.Success) {
                contextualPicks = res.value
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(t.medium), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShinkiBrandBadge(size = 26.dp)
                Text("${ShinkiAppInfo.APP_NAME} · Búsqueda y Fuentes", style = MaterialTheme.typography.headlineMedium)
            }
            SpotiFlacQualityBadge(
                text = "✦ ${state.repository.shortLabel} · Audio 24-bit",
                highlighted = true,
            )
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = p.surface,
            border = BorderStroke(1.dp, p.primary.copy(alpha = 0.28f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = t.small, vertical = 6.dp)) {
                TimingTransportBar(playback = playback, compact = false)
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = controller::changeQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            shape = RoundedCornerShape(percent = 50),
            label = { Text("Buscá en Shinki music, YT Music, SoundCloud, Bandcamp o pegá un enlace…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.submit() }),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    if (state.query.isNotEmpty()) {
                        TextButton(
                            onClick = { controller.changeQuery("") },
                            modifier = Modifier.semantics { contentDescription = "Limpiar búsqueda" },
                        ) { Text("Limpiar") }
                    }
                    VividPillButton(
                        text = "⌕ Buscar",
                        active = state.query.isNotBlank() && !state.loading,
                        accentColor = p.primary,
                        onClick = { if (state.query.isNotBlank() && !state.loading) controller.submit() },
                    )
                }
            },
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(t.unit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item("repo-label") {
                Text("Fuentes:", style = MaterialTheme.typography.labelSmall, color = p.primary)
            }
            items(MusicRepository.entries, key = { "repo:${it.name}" }) { repo ->
                VividPillButton(
                    text = repo.shortLabel,
                    active = state.repository == repo,
                    accentColor = p.primary,
                    onClick = { controller.selectRepository(repo) },
                )
            }
            items(SpotiFlacServiceInfo.entries.take(4), key = { "svc:${it.id}" }) { svc ->
                SpotiFlacQualityBadge(
                    text = "● ${svc.label}",
                    modifier = Modifier.clickable { controller.selectRepository(svc.linkedRepo) },
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            items(MusicKind.entries, key = { it.name }) { kind ->
                VividPillButton(
                    text = kind.pluralLabel(),
                    active = state.kind == kind,
                    accentColor = p.primary,
                    onClick = { controller.selectKind(kind) },
                )
            }
            val quickTags = listOf("Anime Openings", "Sakura Lo-Fi", "J-Pop", "Vocaloid", "Synthwave", "Bandas Sonoras")
            items(quickTags, key = { "tag:$it" }) { tag ->
                VividPillButton(
                    text = "✦ $tag",
                    accentColor = p.highlight,
                    onClick = { controller.chooseSuggestion(tag) },
                )
            }
        }

        // Exactly one lazy scroll surface; at most 300 result rows are retained by the state owner.
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(t.unit)) {
            if (!state.incognito && !state.submitted && state.query.isBlank() && state.history.isNotEmpty()) {
                item("history-header") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Búsquedas recientes", style = MaterialTheme.typography.titleMedium, color = p.primary)
                        TextButton(
                            onClick = controller::clearHistory,
                            modifier = Modifier.semantics { contentDescription = "Borrar historial de búsqueda" },
                        ) { Text("Borrar historial") }
                    }
                }
                items(
                    items = state.history,
                    key = { "history:${it.kind.name}:${it.query}" },
                    contentType = { "search-history" },
                ) { entry ->
                    Surface(
                        color = p.surface,
                        shape = AestheticPetalShape,
                        border = BorderStroke(t.hairline, p.primary.copy(alpha = 0.25f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "Buscar de nuevo") { controller.chooseHistory(entry) }
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
                                    contentDescription = "Quitar ${entry.query} del historial"
                                },
                            ) { Text("Quitar") }
                        }
                    }
                }
            }
            items(state.suggestions, key = { "suggestion:$it" }) { suggestion ->
                TextButton(onClick = { controller.chooseSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                    Text("⌕  $suggestion", Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        "Buscá cualquier canción, opening, remix o álbum. El buscador consulta múltiples repositorios (YT Music, YouTube Completo, SoundCloud y Bandcamp) para encontrar todos los temas.",
                        color = p.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (contextualPicks.isNotEmpty() && state.submitted) {
                item("contextual-recommendations") {
                    Surface(
                        color = p.elevated,
                        shape = AestheticGemShape,
                        border = BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(p.primary, p.secondary))),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
                            Text(
                                "✦ Recomendado en base a tu búsqueda «${state.query.trim()}»",
                                style = MaterialTheme.typography.titleMedium,
                                color = p.primary,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                                items(contextualPicks, key = { "ctx:${it.item.id}" }) { pick ->
                                    Surface(
                                        color = p.surface,
                                        shape = AestheticPetalShape,
                                        border = BorderStroke(t.hairline, p.secondary.copy(alpha = 0.45f)),
                                    ) {
                                        Row(
                                            Modifier.width(248.dp)
                                                .clickable { play(pick.item) }
                                                .padding(t.unit),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(t.unit),
                                        ) {
                                            TrackArt(pick.item, Modifier.size(44.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(pick.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                Text(pick.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = p.muted)
                                                Text("Afinidad: ${pick.reason}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = p.secondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            items(state.items, key = { it.id }, contentType = { "music-result" }) { item ->
                Surface(
                    color = p.surface,
                    shape = AestheticGemShape,
                    border = BorderStroke(t.hairline, p.primary.copy(alpha = 0.25f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.kind == MusicKind.Song, onClickLabel = "Reproducir canción") { play(item) }
                            .padding(t.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.small),
                    ) {
                        if (item.artworkUrl != null) TrackArt(item, Modifier.size(t.touch)) else
                        Box(
                            Modifier.size(t.touch)
                                .background(p.elevated, AestheticGemShape)
                                .semantics { contentDescription = item.kind.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (item.kind == MusicKind.Song) "♪" else "◈", color = p.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SpotiFlacQualityBadge(
                                    text = "24-bit · ${item.repositoryBadge}",
                                    highlighted = true,
                                )
                                Text(
                                    item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                item.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = p.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (item.kind == MusicKind.Song) {
                            VividPillButton(
                                text = "▶ Reproducir",
                                active = true,
                                accentColor = p.primary,
                                onClick = { play(item) },
                            )
                            VividPillButton(
                                text = "+ Cola",
                                accentColor = p.secondary,
                                onClick = { enqueue(item) },
                            )
                            TrackDownloadButton(
                                playback = playback,
                                item = item,
                                compact = true,
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
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Buscando" })
            }
            if (state.submitted && !state.loading && state.error == null && state.items.isEmpty()) item("empty") {
                Text("Sin resultados. Probá otra búsqueda o categoría.", color = p.muted)
            }
            if (state.nextPage != null && !state.loading && state.error == null) item("more") {
                OutlinedButton(onClick = controller::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Cargar más") }
            }
            if (state.reachedLimit) item("limit") {
                Text("Mostrando hasta 300 resultados. Refiná tu búsqueda para ver más.", color = p.muted)
            }
            if (state.items.any { it.kind == MusicKind.Song }) item("playback-note") {
                Text(
                    "Seleccioná una canción para reproducirla o descargarla offline.",
                    color = p.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun MusicKind.pluralLabel(): String = when (this) {
    MusicKind.Song -> "Canciones"
    MusicKind.Album -> "Álbumes"
    MusicKind.Artist -> "Artistas"
    MusicKind.Playlist -> "Listas"
}

@Composable
private fun ErrorNotice(error: SourceError, retry: () -> Unit) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Column(verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Text(error.userMessage(), color = p.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = retry) { Text(if (error == SourceError.InvalidRequest) "Buscar de nuevo" else "Reintentar") }
    }
}
