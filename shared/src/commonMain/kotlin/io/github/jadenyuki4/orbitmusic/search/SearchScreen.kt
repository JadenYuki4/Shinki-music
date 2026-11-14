// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jadenyuki4.orbitmusic.*
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*
import kotlinx.coroutines.launch

private val searchQuickTags = listOf(
    "Anime Openings",
    "Sakura Lo-Fi",
    "J-Pop",
    "Vocaloid",
    "Synthwave",
    "Bandas Sonoras",
)

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
    val downloads by remember(controller) { controller.historyStore.observeDownloads() }.collectAsState(emptyList())
    val activeDownloads by (playback?.activeDownloads?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, Float?>()) })
    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.id } }
    val downloadedIds = remember(downloads) { downloads.mapTo(mutableSetOf()) { it.item.id } }

    val p = LocalPalette.current
    val t = LocalLayout.current
    val coroutineScope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val collectionList = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val editing = isAndroid && WindowInsets.ime.getBottom(LocalDensity.current) > 0

    var selectedServiceTag by remember { mutableStateOf<String?>(null) }
    var sourcesMenuOpen by remember { mutableStateOf(false) }
    var moreCategoriesOpen by remember { mutableStateOf(false) }

    fun dismissKeyboard() {
        if (isAndroid) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    fun submit() {
        dismissKeyboard()
        controller.submit()
    }

    val dismissOnScroll = remember(focusManager, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) dismissKeyboard()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) { if (!isAndroid) focus.requestFocus() }
    LaunchedEffect(state.query, state.kind, state.showAllCategories, state.repository) {
        list.scrollToItem(0)
    }

    var contextualPicks by remember(state.submitted, state.query, state.items, favorites, plays, rules, state.incognito) {
        mutableStateOf<List<Pick>>(emptyList())
    }
    LaunchedEffect(state.submitted, state.query, state.items, favorites, plays, rules, state.incognito) {
        if (state.submitted && state.query.isNotBlank() && !state.incognito && state.selectedCollection == null) {
            val res = searchContextualRecommendations(
                source = controller.source,
                query = state.query,
                currentItems = state.items,
                searches = state.history,
                plays = plays,
                favorites = favorites,
                rules = rules,
            )
            if (res is SourceResult.Success) {
                contextualPicks = res.value
            }
        }
    }

    fun openItem(item: MusicItem) {
        dismissKeyboard()
        if (item.kind == MusicKind.Song) play(item) else controller.openCollection(item)
    }

    fun toggleFavorite(item: MusicItem) {
        coroutineScope.launch { controller.historyStore.toggleFavorite(item) }
    }

    fun downloadTrack(item: MusicItem) {
        playback?.download(item)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = t.small, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = controller::changeQuery,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (isAndroid && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        if (event.type == KeyEventType.KeyUp) submit()
                        true
                    } else false
                },
            singleLine = true,
            shape = RoundedCornerShape(percent = 50),
            placeholder = {
                Text(
                    "Buscá canciones, artistas o pegá un enlace…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = p.muted,
                )
            },
            leadingIcon = {
                ChromeVectorIcon(
                    kind = PlayerChromeIcon.SearchGlass,
                    color = p.muted,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF343846),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(26.dp)
                            .semantics { contentDescription = "Limpiar búsqueda" }
                            .clickable { controller.changeQuery("") },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✕", color = p.text, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1F222C),
                unfocusedContainerColor = Color(0xFF1F222C),
                focusedBorderColor = p.primary.copy(alpha = 0.65f),
                unfocusedBorderColor = p.muted.copy(alpha = 0.18f),
                focusedTextColor = p.text,
                unfocusedTextColor = p.text,
                cursorColor = p.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )

        val selectedCollection = state.selectedCollection
        if (selectedCollection != null) {
            CollectionDetailView(
                collection = selectedCollection,
                items = state.collectionItems,
                loading = state.collectionLoading,
                error = state.collectionError,
                listState = collectionList,
                favoriteIds = favoriteIds,
                downloadedIds = downloadedIds,
                activeDownloads = activeDownloads,
                playback = playback,
                onBack = controller::closeCollection,
                onRetry = controller::retryCollection,
                onPlayAll = {
                    val playable = state.collectionItems.filter { it.kind == MusicKind.Song }
                    if (playable.isNotEmpty()) {
                        dismissKeyboard()
                        if (playback != null) playback.playFrom(playable.first(), playable)
                        else play(playable.first())
                    }
                },
                onOpenItem = ::openItem,
                onPlay = { dismissKeyboard(); play(it) },
                onPlayNext = { dismissKeyboard(); playNext(it) },
                onEnqueue = { dismissKeyboard(); enqueue(it) },
                onToggleFavorite = ::toggleFavorite,
                onDownload = ::downloadTrack,
            )
            return@Column
        }

        if (!editing) {
            val activeSourceLabel = selectedServiceTag ?: when (state.repository) {
                MusicRepository.All -> "Todas las fuentes"
                MusicRepository.YouTubeMusic -> "YT Music"
                MusicRepository.YouTubeVideos -> "YouTube"
                MusicRepository.SoundCloud -> "SoundCloud"
                MusicRepository.Bandcamp -> "Bandcamp"
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        shape = AestheticPetalShape,
                        color = Color(0xFF1B1E28),
                        border = BorderStroke(1.dp, p.muted.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .semantics { contentDescription = "Selector de fuentes" }
                            .clickable { sourcesMenuOpen = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "$activeSourceLabel ▾",
                                color = p.text,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = sourcesMenuOpen,
                        onDismissRequest = { sourcesMenuOpen = false },
                        containerColor = Color(0xFF1F222C),
                    ) {
                        MusicRepository.entries.forEach { repo ->
                            val repoDisplay = when (repo) {
                                MusicRepository.All -> "Todas las fuentes"
                                MusicRepository.YouTubeMusic -> "YT Music"
                                MusicRepository.YouTubeVideos -> "YouTube Completo"
                                MusicRepository.SoundCloud -> "SoundCloud"
                                MusicRepository.Bandcamp -> "Bandcamp"
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (selectedServiceTag == null && state.repository == repo) "✓ $repoDisplay" else repoDisplay,
                                        color = if (selectedServiceTag == null && state.repository == repo) p.primary else p.text,
                                    )
                                },
                                onClick = {
                                    sourcesMenuOpen = false
                                    selectedServiceTag = null
                                    controller.selectRepository(repo)
                                },
                            )
                        }
                        HorizontalDivider(color = p.muted.copy(alpha = 0.16f))
                        SpotiFlacServiceInfo.entries.forEach { svc ->
                            val activeSvc = selectedServiceTag == svc.label
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (activeSvc) "✓ ${svc.label}" else "● ${svc.label}",
                                        color = if (activeSvc) p.primary else p.text,
                                    )
                                },
                                onClick = {
                                    sourcesMenuOpen = false
                                    selectedServiceTag = svc.label
                                    controller.selectRepository(svc.linkedRepo)
                                },
                            )
                        }
                    }
                }

                if (state.submitted) {
                    Text(
                        text = "${state.items.size} resultados",
                        color = p.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wideFilters = maxWidth >= 560.dp
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterPill(
                        label = "Todo",
                        active = state.showAllCategories,
                        onClick = { dismissKeyboard(); controller.selectAllCategories() },
                    )
                    FilterPill(
                        label = "Canciones",
                        active = !state.showAllCategories && state.kind == MusicKind.Song,
                        onClick = { dismissKeyboard(); controller.selectKind(MusicKind.Song) },
                    )
                    FilterPill(
                        label = "Álbumes",
                        active = !state.showAllCategories && state.kind == MusicKind.Album,
                        onClick = { dismissKeyboard(); controller.selectKind(MusicKind.Album) },
                    )
                    if (wideFilters) {
                        FilterPill(
                            label = "Artistas",
                            active = !state.showAllCategories && state.kind == MusicKind.Artist,
                            onClick = { dismissKeyboard(); controller.selectKind(MusicKind.Artist) },
                        )
                        FilterPill(
                            label = "Listas",
                            active = !state.showAllCategories && state.kind == MusicKind.Playlist,
                            onClick = { dismissKeyboard(); controller.selectKind(MusicKind.Playlist) },
                        )
                    }
                    val overflowKindActive = !wideFilters && !state.showAllCategories &&
                        (state.kind == MusicKind.Artist || state.kind == MusicKind.Playlist)
                    val moreLabel = when {
                        wideFilters -> "Etiquetas ▾"
                        overflowKindActive -> "Más · ${state.kind.pluralLabel()} ▾"
                        else -> "Más ▾"
                    }
                    Box {
                        FilterPill(
                            label = moreLabel,
                            active = overflowKindActive,
                            onClick = { moreCategoriesOpen = true },
                        )
                        DropdownMenu(
                            expanded = moreCategoriesOpen,
                            onDismissRequest = { moreCategoriesOpen = false },
                            containerColor = Color(0xFF1F222C),
                        ) {
                            if (!wideFilters) {
                                DropdownMenuItem(
                                    text = { Text("Artistas") },
                                    onClick = {
                                        moreCategoriesOpen = false
                                        dismissKeyboard()
                                        controller.selectKind(MusicKind.Artist)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Listas") },
                                    onClick = {
                                        moreCategoriesOpen = false
                                        dismissKeyboard()
                                        controller.selectKind(MusicKind.Playlist)
                                    },
                                )
                                HorizontalDivider(color = p.muted.copy(alpha = 0.16f))
                            }
                            searchQuickTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text("✦ $tag") },
                                    onClick = {
                                        moreCategoriesOpen = false
                                        dismissKeyboard()
                                        controller.chooseSuggestion(tag)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Single lazy list container; bottom contentPadding ensures mini-player/nav never obscure last row.
        LazyColumn(
            modifier = Modifier.weight(1f).then(if (editing) Modifier.nestedScroll(dismissOnScroll) else Modifier),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.partialSourceWarning?.let { warning ->
                item("partial-warning") {
                    PartialSourceWarningBanner(
                        message = warning,
                        onRetry = controller::retrySources,
                    )
                }
            }

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
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(t.hairline, p.primary.copy(alpha = 0.22f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "Buscar de nuevo") { dismissKeyboard(); controller.chooseHistory(entry) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            TextButton(
                                onClick = { controller.removeHistory(entry) },
                                modifier = Modifier.semantics { contentDescription = "Quitar ${entry.query} del historial" },
                            ) { Text("Quitar") }
                        }
                    }
                }
            }

            items(state.suggestions, key = { "suggestion:$it" }) { suggestion ->
                TextButton(
                    onClick = { dismissKeyboard(); controller.chooseSuggestion(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                        "Buscá canciones, álbumes, artistas o pegá enlaces de YouTube, YT Music, SoundCloud o Bandcamp.",
                        color = p.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            if (state.showAllCategories && state.items.isNotEmpty()) {
                val topResult = state.items.first()
                item("section-top-title") {
                    SectionTitleRow(title = "Mejor resultado")
                }
                item("top:${topResult.id}", contentType = "top-result") {
                    CompactSearchResultRow(
                        item = topResult,
                        featured = true,
                        isFavorite = topResult.id in favoriteIds,
                        isDownloaded = topResult.id in downloadedIds,
                        downloadProgress = activeDownloads[topResult.id],
                        canDownload = playback != null && topResult.kind == MusicKind.Song,
                        onOpen = { openItem(topResult) },
                        onPlayNow = { dismissKeyboard(); play(topResult) },
                        onPlayNext = { dismissKeyboard(); playNext(topResult) },
                        onEnqueue = { dismissKeyboard(); enqueue(topResult) },
                        onToggleFavorite = { toggleFavorite(topResult) },
                        onDownload = { downloadTrack(topResult) },
                    )
                }

                val totalSongs = state.items.count { it.kind == MusicKind.Song }
                val songs = state.items.filter {
                    it.kind == MusicKind.Song && (totalSongs <= 1 || it.id != topResult.id)
                }.take(8)
                if (songs.isNotEmpty()) {
                    item("section-songs-title") {
                        SectionTitleRow(
                            title = "Canciones",
                            actionLabel = "Ver todo >",
                            onAction = { dismissKeyboard(); controller.selectKind(MusicKind.Song) },
                        )
                    }
                    items(songs, key = { "song:${it.id}" }, contentType = { "music-result" }) { item ->
                        CompactSearchResultRow(
                            item = item,
                            featured = false,
                            isFavorite = item.id in favoriteIds,
                            isDownloaded = item.id in downloadedIds,
                            downloadProgress = activeDownloads[item.id],
                            canDownload = playback != null,
                            onOpen = { openItem(item) },
                            onPlayNow = { dismissKeyboard(); play(item) },
                            onPlayNext = { dismissKeyboard(); playNext(item) },
                            onEnqueue = { dismissKeyboard(); enqueue(item) },
                            onToggleFavorite = { toggleFavorite(item) },
                            onDownload = { downloadTrack(item) },
                        )
                    }
                }

                val albums = state.items.filter { it.kind == MusicKind.Album && it.id != topResult.id }
                if (albums.isNotEmpty()) {
                    item("section-albums-title") {
                        SectionTitleRow(
                            title = "Álbumes",
                            actionLabel = "Ver todo >",
                            onAction = { dismissKeyboard(); controller.selectKind(MusicKind.Album) },
                        )
                    }
                    item("albums-row", contentType = "album-shelf") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(albums.take(10), key = { "album:${it.id}" }) { album ->
                                SearchAlbumCard(album = album, onClick = { openItem(album) })
                            }
                        }
                    }
                }

                val artists = state.items.filter { it.kind == MusicKind.Artist && it.id != topResult.id }
                if (artists.isNotEmpty()) {
                    item("section-artists-title") {
                        SectionTitleRow(
                            title = "Artistas",
                            actionLabel = "Ver todo >",
                            onAction = { dismissKeyboard(); controller.selectKind(MusicKind.Artist) },
                        )
                    }
                    items(artists.take(5), key = { "artist:${it.id}" }, contentType = { "music-result" }) { artist ->
                        CompactSearchResultRow(
                            item = artist,
                            featured = false,
                            isFavorite = artist.id in favoriteIds,
                            isDownloaded = false,
                            downloadProgress = null,
                            canDownload = false,
                            onOpen = { openItem(artist) },
                            onPlayNow = { openItem(artist) },
                            onPlayNext = { openItem(artist) },
                            onEnqueue = { openItem(artist) },
                            onToggleFavorite = { toggleFavorite(artist) },
                            onDownload = {},
                        )
                    }
                }

                val playlists = state.items.filter { it.kind == MusicKind.Playlist && it.id != topResult.id }
                if (playlists.isNotEmpty()) {
                    item("section-playlists-title") {
                        SectionTitleRow(
                            title = "Listas",
                            actionLabel = "Ver todo >",
                            onAction = { dismissKeyboard(); controller.selectKind(MusicKind.Playlist) },
                        )
                    }
                    items(playlists.take(5), key = { "playlist:${it.id}" }, contentType = { "music-result" }) { playlist ->
                        CompactSearchResultRow(
                            item = playlist,
                            featured = false,
                            isFavorite = playlist.id in favoriteIds,
                            isDownloaded = false,
                            downloadProgress = null,
                            canDownload = false,
                            onOpen = { openItem(playlist) },
                            onPlayNow = { openItem(playlist) },
                            onPlayNext = { openItem(playlist) },
                            onEnqueue = { openItem(playlist) },
                            onToggleFavorite = { toggleFavorite(playlist) },
                            onDownload = {},
                        )
                    }
                }
            } else {
                items(state.items, key = { "item:${it.id}" }, contentType = { "music-result" }) { item ->
                    CompactSearchResultRow(
                        item = item,
                        featured = false,
                        isFavorite = item.id in favoriteIds,
                        isDownloaded = item.id in downloadedIds,
                        downloadProgress = activeDownloads[item.id],
                        canDownload = playback != null && item.kind == MusicKind.Song,
                        onOpen = { openItem(item) },
                        onPlayNow = { dismissKeyboard(); play(item) },
                        onPlayNext = { dismissKeyboard(); playNext(item) },
                        onEnqueue = { dismissKeyboard(); enqueue(item) },
                        onToggleFavorite = { toggleFavorite(item) },
                        onDownload = { downloadTrack(item) },
                    )
                }
            }

            if (contextualPicks.isNotEmpty() && state.submitted && !editing) {
                item("contextual-recommendations") {
                    Surface(
                        color = p.elevated,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(p.primary.copy(alpha = 0.45f), p.secondary.copy(alpha = 0.45f)))),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "✦ Recomendado para «${state.query.trim()}»",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = p.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(contextualPicks, key = { "ctx:${it.item.id}" }) { pick ->
                                    Surface(
                                        color = p.surface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(t.hairline, p.secondary.copy(alpha = 0.35f)),
                                    ) {
                                        Row(
                                            Modifier.width(220.dp)
                                                .clickable { dismissKeyboard(); play(pick.item) }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            SearchCoverThumbnail(pick.item, size = 40.dp)
                                            Column(Modifier.weight(1f)) {
                                                Text(pick.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                Text(pick.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = p.muted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item("search-error") {
                    ErrorNotice(error, controller::retry)
                }
            }

            if (state.loading) {
                item("loading") {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics { contentDescription = "Buscando" },
                        color = p.primary,
                    )
                }
            }

            if (state.submitted && !state.loading && state.error == null && state.items.isEmpty()) {
                item("empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "No encontramos resultados para «${state.query.trim()}».",
                            color = p.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Probá cambiar el filtro, elegir Todas las fuentes o pegar un enlace directo.",
                            color = p.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (state.nextPage != null && !state.loading && state.error == null) {
                item("more") {
                    OutlinedButton(onClick = controller::loadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Cargar más")
                    }
                }
            }

            if (state.reachedLimit) {
                item("limit") {
                    Text(
                        "Mostrando hasta 300 resultados. Refiná tu búsqueda para ver más.",
                        color = p.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(
        shape = AestheticPetalShape,
        color = if (active) p.primary else Color(0xFF1D202A),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) p.primary else p.muted.copy(alpha = 0.22f),
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            color = if (active) Color(0xFF12101A) else p.text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionTitleRow(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                color = p.primary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SearchCoverThumbnail(
    item: MusicItem,
    size: androidx.compose.ui.unit.Dp,
) {
    val p = LocalPalette.current
    if (item.artworkUrl != null) {
        TrackArt(item, Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(Color(0xFF232635), RoundedCornerShape(10.dp))
                .semantics { contentDescription = item.kind.name },
            contentAlignment = Alignment.Center,
        ) {
            val glyph = when (item.kind) {
                MusicKind.Song -> "♪"
                MusicKind.Album -> "◉"
                MusicKind.Artist -> "★"
                MusicKind.Playlist -> "☰"
            }
            Text(glyph, color = p.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CompactSearchResultRow(
    item: MusicItem,
    featured: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    canDownload: Boolean,
    onOpen: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
) {
    val p = LocalPalette.current
    var menuOpen by remember(item.id) { mutableStateOf(false) }

    Surface(
        color = if (featured) Color(0xFF1A1C28) else Color(0xFF161822),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (featured) p.primary.copy(alpha = 0.28f) else p.muted.copy(alpha = 0.14f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Keep main clickable area separate from the trailing ⋮ action button so opening menu never triggers playback.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = if (item.kind == MusicKind.Song) "Reproducir canción" else "Abrir colección", onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchCoverThumbnail(item, size = if (featured) 56.dp else 48.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.title,
                        color = p.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle.ifBlank { item.kind.singularLabel() },
                        color = p.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SourceChipBadge(item.sourceLabel)
                        if (item.kind != MusicKind.Song) {
                            Text(
                                text = item.kind.singularLabel(),
                                color = p.muted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            item.durationSeconds?.let { duration ->
                Text(
                    text = formatTrackDuration(duration),
                    color = p.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable(onClick = onOpen),
                )
            }

            Box {
                ChromeIconButton(
                    icon = PlayerChromeIcon.MoreVert,
                    contentDesc = "Acciones de ${item.title}",
                    onClick = { menuOpen = true },
                    size = 36.dp,
                    iconSize = 18.dp,
                    tint = p.muted,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Color(0xFF1F222C),
                ) {
                    if (item.kind == MusicKind.Song) {
                        DropdownMenuItem(
                            text = { Text("Reproducir ahora") },
                            onClick = { menuOpen = false; onPlayNow() },
                        )
                        DropdownMenuItem(
                            text = { Text("Reproducir siguiente") },
                            onClick = { menuOpen = false; onPlayNext() },
                        )
                        DropdownMenuItem(
                            text = { Text("Agregar a la cola") },
                            onClick = { menuOpen = false; onEnqueue() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Abrir ${item.kind.singularLabel().lowercase()}") },
                            onClick = { menuOpen = false; onOpen() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos") },
                        onClick = { menuOpen = false; onToggleFavorite() },
                    )
                    if (canDownload) {
                        val downloadLabel = when {
                            isDownloaded -> "✓ Descargado offline"
                            downloadProgress != null -> "Descargando (${(downloadProgress * 100).toInt()}%)"
                            else -> "Descargar offline"
                        }
                        DropdownMenuItem(
                            text = { Text(downloadLabel) },
                            enabled = !isDownloaded && downloadProgress == null,
                            onClick = { menuOpen = false; onDownload() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChipBadge(label: String) {
    val p = LocalPalette.current
    Surface(
        shape = AestheticPetalShape,
        color = Color(0xFF252836),
        border = BorderStroke(0.8.dp, p.primary.copy(alpha = 0.28f)),
    ) {
        Text(
            text = "▶ $label",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = p.text.copy(alpha = 0.90f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SearchAlbumCard(
    album: MusicItem,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(
        color = Color(0xFF161822),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, p.muted.copy(alpha = 0.14f)),
        modifier = Modifier.width(148.dp).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchCoverThumbnail(album, size = 132.dp)
            Text(
                text = album.title,
                color = p.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.subtitle.ifBlank { album.sourceLabel },
                color = p.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PartialSourceWarningBanner(
    message: String,
    onRetry: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(
        color = Color(0xFF282132),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, p.secondary.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = p.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) {
                Text("Reintentar", color = p.primary, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ColumnScope.CollectionDetailView(
    collection: MusicItem,
    items: List<MusicItem>,
    loading: Boolean,
    error: SourceError?,
    listState: LazyListState,
    favoriteIds: Set<String>,
    downloadedIds: Set<String>,
    activeDownloads: Map<String, Float?>,
    playback: Playback?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayAll: () -> Unit,
    onOpenItem: (MusicItem) -> Unit,
    onPlay: (MusicItem) -> Unit,
    onPlayNext: (MusicItem) -> Unit,
    onEnqueue: (MusicItem) -> Unit,
    onToggleFavorite: (MusicItem) -> Unit,
    onDownload: (MusicItem) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack) {
            Text("← Volver", color = p.primary)
        }
        if (items.any { it.kind == MusicKind.Song }) {
            VividPillButton(
                text = "▶ Reproducir todo",
                active = true,
                accentColor = p.primary,
                onClick = onPlayAll,
            )
        }
    }

    Surface(
        color = Color(0xFF1A1C28),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, p.primary.copy(alpha = 0.25f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchCoverThumbnail(collection, size = 60.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = collection.title,
                    color = p.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${collection.kind.singularLabel()} · ${collection.subtitle.ifBlank { collection.sourceLabel }}",
                    color = p.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SourceChipBadge(collection.sourceLabel)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.weight(1f),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            item("collection-loading") {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().semantics { contentDescription = "Cargando colección" },
                    color = p.primary,
                )
            }
        }
        error?.let { err ->
            item("collection-error") {
                ErrorNotice(err, onRetry)
            }
        }
        if (!loading && error == null && items.isEmpty()) {
            item("collection-empty") {
                Text("No se encontraron temas en esta colección.", color = p.muted)
            }
        }
        items(items, key = { "col:${it.id}" }, contentType = { "music-result" }) { track ->
            CompactSearchResultRow(
                item = track,
                featured = false,
                isFavorite = track.id in favoriteIds,
                isDownloaded = track.id in downloadedIds,
                downloadProgress = activeDownloads[track.id],
                canDownload = playback != null && track.kind == MusicKind.Song,
                onOpen = { onOpenItem(track) },
                onPlayNow = { onPlay(track) },
                onPlayNext = { onPlayNext(track) },
                onEnqueue = { onEnqueue(track) },
                onToggleFavorite = { onToggleFavorite(track) },
                onDownload = { onDownload(track) },
            )
        }
    }
}

private fun formatTrackDuration(durationSeconds: Long): String {
    val safe = durationSeconds.coerceAtLeast(0L)
    val mins = safe / 60L
    val secs = (safe % 60L).toString().padStart(2, '0')
    return "$mins:$secs"
}

private fun MusicKind.pluralLabel(): String = when (this) {
    MusicKind.Song -> "Canciones"
    MusicKind.Album -> "Álbumes"
    MusicKind.Artist -> "Artistas"
    MusicKind.Playlist -> "Listas"
}

private fun MusicKind.singularLabel(): String = when (this) {
    MusicKind.Song -> "Canción"
    MusicKind.Album -> "Álbum"
    MusicKind.Artist -> "Artista"
    MusicKind.Playlist -> "Lista"
}

@Composable
private fun ErrorNotice(error: SourceError, retry: () -> Unit) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Column(verticalArrangement = Arrangement.spacedBy(t.unit)) {
        Text(error.userMessage(), color = p.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = retry) {
            Text(if (error == SourceError.InvalidRequest) "Buscar de nuevo" else "Reintentar")
        }
    }
}
