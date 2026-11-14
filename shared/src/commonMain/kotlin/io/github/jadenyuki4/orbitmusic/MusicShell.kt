// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import io.github.jadenyuki4.orbitmusic.domain.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import io.github.jadenyuki4.orbitmusic.theme.*
import io.github.jadenyuki4.orbitmusic.search.*
import androidx.compose.ui.input.key.*

private enum class Destination(val label: String, val navLabel: String, val symbol: String) {
    Home("Inicio", "Inicio", "⌂"),
    Library("Biblioteca", "Biblioteca", "▤"),
    Downloads("Descargas offline", "Offline", "↓"),
    Search("Buscar", "Fuentes", "⊞"),
    Settings("Ajustes", "Ajustes", "≡"),
}

private data class ShowcaseAlbumCard(
    val title: String,
    val artist: String,
    val badge: String,
    val query: String,
    val accent: Color,
)

private val spotiFlacShowcaseAlbums = listOf(
    ShowcaseAlbumCard("Die With A Smile", "Lady Gaga, Bruno Mars", "24-bit", "Lady Gaga Bruno Mars Die With A Smile", Color(0xFF2F4578)),
    ShowcaseAlbumCard("LOST CORNER", "Kenshi Yonezu", "24-bit", "Kenshi Yonezu LOST CORNER", Color(0xFF4A3552)),
    ShowcaseAlbumCard("THE BOOK 3", "YOASOBI", "24-bit", "YOASOBI Idol", Color(0xFF244D4E)),
    ShowcaseAlbumCard("Random Access", "Daft Punk", "16-bit", "Daft Punk Instant Crush", Color(0xFF3B3654)),
)

/** Diseño adaptado de SpotiFLAC; referencias y atribuciones en NOTICE.md. */
@Composable
fun MusicShell(search: SearchViewModel, playback: Playback?, appearance: @Composable () -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val effects = LocalEffects.current
    var destination by remember { mutableStateOf(Destination.Home) }
    var panel by remember { mutableStateOf("Cola") }
    var theater by remember { mutableStateOf(false) }
    var showEqualizerOnHome by remember { mutableStateOf(true) }
    val track = playback.displayTrack()

    var fallbackQuality by remember { mutableStateOf(AudioQuality.High) }
    var fallbackFormat by remember { mutableStateOf(AudioFormatPreference.Auto) }
    var fallbackProfile by remember { mutableStateOf(SoundProfile.BassBoost) }

    val activeQuality = playback?.audioQuality?.collectAsState()?.value ?: fallbackQuality
    val activeFormat = playback?.audioFormat?.collectAsState()?.value ?: fallbackFormat
    val activeProfile = playback?.soundProfile?.collectAsState()?.value ?: fallbackProfile

    BoxWithConstraints(
        Modifier.fillMaxSize().background(p.background).safeDrawingPadding()
            .onPreviewKeyEvent { event ->
                handleGlobalKeyboardShortcut(
                    event = event,
                    playback = playback,
                    isTheater = theater,
                    onCloseTheater = { theater = false },
                    onOpenSearch = {
                        theater = false
                        destination = Destination.Search
                    },
                )
            }
    ) {
        val desktop = maxWidth >= t.wideBreakpoint
        val showPanel = maxWidth >= t.panesBreakpoint && destination != Destination.Settings

        Column(Modifier.fillMaxSize()) {
            Surface(color = Color(0xFF15171D)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = t.small, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShinkiBrandBadge(size = 28.dp)
                    Text(
                        ShinkiAppInfo.APP_NAME,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    if (desktop) {
                        SpotiFlacQualityBadge(
                            text = "✦ ${activeQuality.spotiFlacBadge} · ${activeFormat.shortLabel} · ${activeProfile.label}",
                            highlighted = true,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (destination != Destination.Search) {
                        VividPillButton(
                            text = "⌕ Buscar / Fuentes",
                            accentColor = p.primary,
                            onClick = { theater = false; destination = Destination.Search },
                        )
                    }
                    if (theater) {
                        VividPillButton(
                            text = "⌄ Cerrar reproductor",
                            active = true,
                            accentColor = p.primary,
                            onClick = { theater = false },
                        )
                    } else if (track != null) {
                        VividPillButton(
                            text = "▲ Reproduciendo",
                            active = true,
                            accentColor = p.primary,
                            onClick = { theater = true },
                        )
                    }
                }
            }
            HorizontalDivider(color = p.muted.copy(alpha = 0.14f))

            Row(Modifier.weight(1f)) {
                if (desktop) Surface(
                    Modifier.width(t.sidebar).fillMaxHeight(),
                    color = Color(0xFF15171D),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "SHINKI MUSIC",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = p.muted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Destination.entries.forEach { page ->
                            val active = destination == page && !theater
                            VividNavItem(
                                symbol = page.symbol,
                                label = page.label,
                                active = active,
                                onClick = { theater = false; destination = page },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.elevated)
                        Text(
                            "BIBLIOTECA",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = p.muted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        listOf(
                            "◷ Agregado recientemente" to Destination.Library,
                            "↓ Descargas offline" to Destination.Downloads,
                            "♪ Canciones" to Destination.Library,
                            "◉ Álbumes" to Destination.Library,
                            "♟ Artistas" to Destination.Library,
                            "◈ Ecualizador (10 bandas)" to Destination.Home,
                        ).forEach { (label, target) ->
                            TextButton(
                                onClick = {
                                    theater = false
                                    destination = target
                                    if (label.contains("Ecualizador")) showEqualizerOnHome = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = p.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = p.elevated)
                        Text(
                            "LISTAS",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = p.muted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val lists = playback?.history?.playlists?.lists?.collectAsState()?.value.orEmpty()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            if (lists.isEmpty()) TextButton(onClick = { theater = false; destination = Destination.Library }) { Text("Abrir biblioteca") }
                            lists.forEach { list ->
                                TextButton(
                                    enabled = list.songs.isNotEmpty(),
                                    onClick = { playback?.playFrom(list.songs.first(), list.songs) },
                                ) { Text("☰ ${list.name}") }
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val viewKey = if (theater && track != null) "theater" else destination.name
                    Crossfade(
                        targetState = viewKey,
                        animationSpec = tween(durationMillis = effects.durationMillis.coerceAtLeast(0)),
                    ) { currentView ->
                        when (currentView) {
                            "theater" -> if (track != null) {
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.large),
                                    verticalArrangement = Arrangement.spacedBy(t.small),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        IconButton(onClick = { theater = false }) {
                                            Text("⌄", style = MaterialTheme.typography.headlineSmall, color = p.text)
                                        }
                                        Text(
                                            "Reproduciendo ahora",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = p.text,
                                        )
                                        SpotiFlacQualityBadge("⋮", highlighted = false)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(28.dp),
                                        color = p.elevated,
                                        border = BorderStroke(1.dp, p.primary.copy(alpha = 0.25f)),
                                        modifier = Modifier.widthIn(max = 360.dp),
                                    ) {
                                        TrackArt(track, Modifier.fillMaxWidth().aspectRatio(1f))
                                    }
                                    Text(
                                        track.title,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = p.text,
                                    )
                                    Text(
                                        track.subtitle,
                                        color = p.muted,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    SpotiFlacQualityBadge(
                                        text = "✦ ${activeQuality.spotiFlacBadge}",
                                        highlighted = true,
                                    )
                                    if (playback != null) PlayerOptions(playback, track, !showPanel)
                                }
                            }
                            Destination.Settings.name -> appearance()
                            Destination.Downloads.name -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                                verticalArrangement = Arrangement.spacedBy(t.medium),
                            ) {
                                if (playback != null) {
                                    OfflineDownloadsPanel(
                                        playback = playback,
                                        onBrowseSearch = { destination = Destination.Search },
                                    )
                                } else {
                                    Text(
                                        "Reproductor no inicializado.",
                                        color = p.muted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Destination.Search.name -> SearchScreen(
                                model = search,
                                enqueue = { playback?.enqueue(it) },
                                playNext = { playback?.playNext(it) },
                                playback = playback,
                                play = { playback?.playFromSearch(it) },
                            )
                            Destination.Library.name -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                                verticalArrangement = Arrangement.spacedBy(t.medium),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Biblioteca",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SpotiFlacQualityBadge("⊞ Cuadrícula", highlighted = true)
                                        SpotiFlacQualityBadge("+ Agregar")
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(percent = 50),
                                    color = Color(0xFF1F222C),
                                    border = BorderStroke(1.dp, p.muted.copy(alpha = 0.16f)),
                                    modifier = Modifier.fillMaxWidth().clickable { destination = Destination.Search },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Buscá en tu biblioteca", color = p.muted, style = MaterialTheme.typography.bodyLarge)
                                        Text("⌕", color = p.muted, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (playback != null) {
                                    Library(playback) { destination = Destination.Search }
                                } else {
                                    SpotiFlacAlbumShelf(
                                        albums = spotiFlacShowcaseAlbums,
                                        onPlayAlbum = { card ->
                                            search.controller.chooseSuggestion(card.query)
                                            destination = Destination.Search
                                        },
                                    )
                                }
                            }
                            else -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.medium),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Hora dorada",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = p.text,
                                    )
                                    Text(
                                        "Encontrá tu próximo álbum favorito",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = p.muted,
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(percent = 50),
                                    color = Color(0xFF1F222C),
                                    border = BorderStroke(1.dp, p.muted.copy(alpha = 0.18f)),
                                    modifier = Modifier.fillMaxWidth().clickable { destination = Destination.Search },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(percent = 50),
                                            color = Color(0xFF2B2F3D),
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                ShinkiBrandBadge(size = 18.dp)
                                                Text("▾", color = p.muted, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        Text(
                                            "Buscá en Shinki music, YT Music, SoundCloud o pegá un enlace...",
                                            modifier = Modifier.weight(1f),
                                            color = p.muted,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text("⌕", color = p.muted, style = MaterialTheme.typography.titleMedium)
                                    }
                                }

                                Text(
                                    "Seguir escuchando",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = p.text,
                                )
                                ContinueListeningCard(
                                    playback = playback,
                                    track = track,
                                    onBrowse = { destination = Destination.Search },
                                )

                                Text(
                                    "Accesos directos",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = p.text,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        SpotiFlacShortcutCard(
                                            title = "Aleatorio",
                                            iconSymbol = "⇄",
                                            containerColor = p.primary,
                                            badgeColor = Color(0xFF283655),
                                            badgeTextColor = p.primary,
                                            titleColor = Color(0xFF141B2C),
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                if (playback != null) {
                                                    playback.shuffle.value = true
                                                    playback.playOrToggle()
                                                } else {
                                                    destination = Destination.Search
                                                }
                                            },
                                        )
                                        SpotiFlacShortcutCard(
                                            title = "Ecualizador",
                                            iconSymbol = "◈",
                                            containerColor = Color(0xFF523B4E),
                                            badgeColor = Color(0xFFF3B2E5),
                                            badgeTextColor = Color(0xFF382034),
                                            titleColor = Color(0xFFFCE8F8),
                                            modifier = Modifier.weight(1f),
                                            onClick = { showEqualizerOnHome = !showEqualizerOnHome },
                                        )
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        SpotiFlacShortcutCard(
                                            title = "Descargas offline",
                                            iconSymbol = "↓",
                                            containerColor = Color(0xFF22252F),
                                            badgeColor = Color(0xFF323646),
                                            badgeTextColor = p.primary,
                                            titleColor = p.text,
                                            modifier = Modifier.weight(1f),
                                            onClick = { destination = Destination.Downloads },
                                        )
                                        SpotiFlacShortcutCard(
                                            title = "Fuentes en red",
                                            iconSymbol = "Red",
                                            containerColor = Color(0xFF22252F),
                                            badgeColor = Color(0xFF323646),
                                            badgeTextColor = p.secondary,
                                            titleColor = p.text,
                                            modifier = Modifier.weight(1f),
                                            onClick = { destination = Destination.Search },
                                        )
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Agregado recientemente",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = p.text,
                                    )
                                    TextButton(onClick = { destination = Destination.Library }) {
                                        Text("Ver todo →", color = p.primary)
                                    }
                                }
                                val favorites = remember(playback) { playback?.history?.observeFavorites() }
                                    ?.collectAsState(emptyList())?.value.orEmpty()
                                if (favorites.isEmpty()) {
                                    Text("Acá aparecerán las canciones que marques con Me gusta.", color = p.muted)
                                } else {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(t.small)) {
                                        favorites.take(12).forEach { song ->
                                            Column(Modifier.width(t.artwork).clickable { playback?.playFrom(song, favorites) }) {
                                                TrackArt(song, Modifier.size(t.artwork))
                                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(song.subtitle, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }

                                if (showEqualizerOnHome) {
                                    SpotiFlacEqualizerCard(
                                        player = playback,
                                        onSelectProfile = { prof ->
                                            if (playback != null) playback.setSoundProfile(prof)
                                            else fallbackProfile = prof
                                        },
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = p.surface,
                                    border = BorderStroke(1.dp, p.muted.copy(alpha = 0.16f)),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            "SELECTOR DE CALIDAD Y FORMATO HI-RES",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = p.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(t.unit),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AudioQuality.entries.forEach { q ->
                                                VividPillButton(
                                                    text = "${q.shortLabel} (${q.approxSizeLabel})",
                                                    active = activeQuality == q,
                                                    accentColor = p.primary,
                                                    onClick = {
                                                        if (playback != null) playback.setAudioQuality(q)
                                                        else fallbackQuality = q
                                                    },
                                                )
                                            }
                                        }
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(t.unit),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("Formato:", style = MaterialTheme.typography.labelSmall, color = p.muted)
                                            AudioFormatPreference.entries.forEach { fmt ->
                                                VividPillButton(
                                                    text = fmt.shortLabel,
                                                    active = activeFormat == fmt,
                                                    accentColor = p.secondary,
                                                    onClick = {
                                                        if (playback != null) playback.setAudioFormat(fmt)
                                                        else fallbackFormat = fmt
                                                    },
                                                )
                                            }
                                            Text("· Explorar:", style = MaterialTheme.typography.labelSmall, color = p.muted)
                                            MusicKind.entries.forEach { kind ->
                                                val kindLabel = when (kind) {
                                                    MusicKind.Song -> "Canciones"
                                                    MusicKind.Album -> "Álbumes"
                                                    MusicKind.Artist -> "Artistas"
                                                    MusicKind.Playlist -> "Listas"
                                                }
                                                VividPillButton(
                                                    text = kindLabel,
                                                    accentColor = p.primary,
                                                    onClick = {
                                                        search.controller.selectKind(kind)
                                                        destination = Destination.Search
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                if (playback != null) Discovery(playback)

                                val store = playback?.history
                                val records = remember(store) { store?.observeRecentPlays(12) }?.collectAsState(emptyList())?.value.orEmpty()
                                val recent = if (store?.incognito?.collectAsState()?.value == true) emptyList() else records.map { it.item }.distinctBy { it.id }.take(6)
                                if (recent.isNotEmpty()) {
                                    Text("Escuchadas recientemente", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    recent.forEach { item ->
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = p.surface,
                                            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Reproducir canción") { playback?.select(item) },
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = t.small, vertical = t.unit),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(t.small),
                                            ) {
                                                Text("♪", color = p.primary)
                                                Column(Modifier.weight(1f)) {
                                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                                    Text(item.subtitle, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                                                }
                                                SpotiFlacQualityBadge("24-bit")
                                                TrackDownloadButton(playback = playback, item = item, compact = true)
                                                TextButton(onClick = { playback?.enqueue(item) }) { Text("+ Cola") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showPanel) Surface(
                    Modifier.width(t.detailPane).fillMaxHeight(),
                    color = Color(0xFF15171D),
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(t.small),
                        verticalArrangement = Arrangement.spacedBy(t.medium),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                            listOf("Cola", "Letra").forEach { name ->
                                VividPillButton(
                                    text = name,
                                    active = name == panel,
                                    accentColor = p.primary,
                                    onClick = { panel = name },
                                )
                            }
                        }

                        if (panel == "Cola" && playback != null) QueueRows(playback)
                        else if (track != null && playback != null) Lyrics(playback, track)
                        else Text("Elegí una canción en Buscar.", color = p.muted)

                        SpotiFlacEqualizerCard(
                            player = playback,
                            onSelectProfile = { prof ->
                                if (playback != null) playback.setSoundProfile(prof)
                                else fallbackProfile = prof
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = p.muted.copy(alpha = 0.16f))
            PlayerDock(playback) { theater = !theater }
            if (!desktop) NavigationBar(containerColor = Color(0xFF14161C)) {
                Destination.entries.forEach { page ->
                    NavigationBarItem(
                        selected = destination == page && !theater,
                        onClick = { theater = false; destination = page },
                        icon = { Text(page.symbol) },
                        label = { Text(page.navLabel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotiFlacShortcutCard(
    title: String,
    iconSymbol: String,
    containerColor: Color,
    badgeColor: Color,
    badgeTextColor: Color,
    titleColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(92.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = iconSymbol,
                    color = badgeTextColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpotiFlacAlbumShelf(
    albums: List<ShowcaseAlbumCard>,
    onPlayAlbum: (ShowcaseAlbumCard) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        albums.forEach { album ->
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF1C1E26),
                border = BorderStroke(1.dp, p.muted.copy(alpha = 0.14f)),
                modifier = Modifier.width(148.dp).clickable { onPlayAlbum(album) },
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(128.dp).background(album.accent)) {
                        SignalSleeve(Modifier.fillMaxSize())
                        SpotiFlacQualityBadge(
                            text = album.badge,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                        ) {
                            VividPlayPauseButton(
                                playing = false,
                                onClick = { onPlayAlbum(album) },
                                size = 34.dp,
                            )
                        }
                    }
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            album.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = p.text,
                        )
                        Text(
                            album.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VividNavItem(
    symbol: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.96f
            hovered -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            active -> p.primary.copy(alpha = 0.20f)
            hovered -> Color(0xFF232632)
            else -> Color.Transparent
        },
        animationSpec = tween(160),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            active -> p.primary
            hovered -> p.text
            else -> p.muted
        },
        animationSpec = tween(160),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
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
        color = bgColor,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(symbol, color = if (active) p.primary else p.muted, style = MaterialTheme.typography.titleMedium)
            Text(
                label,
                color = textColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ContinueListeningCard(
    playback: Playback?,
    track: MusicItem?,
    onBrowse: () -> Unit,
) {
    val p = LocalPalette.current
    val transportState = playback.displayTransport()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF2F4578),
        border = BorderStroke(1.dp, p.primary.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1F2D4F),
                modifier = Modifier.size(84.dp),
            ) {
                TrackArt(track, Modifier.fillMaxSize())
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = track?.title ?: "Sin reproducción",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track?.subtitle ?: "Elegí una canción para empezar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD0DCFA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val duration = transportState.duration.takeIf { it > 0L } ?: track?.durationSeconds ?: 0L
                val prog = if (duration > 0L) {
                    (transportState.position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { prog },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(percent = 50)),
                    color = p.primary,
                    trackColor = Color(0xFF1E2D52),
                )
            }
            SpotiFlacScallopPlayButton(
                playing = transportState.playing,
                size = 56.dp,
                onClick = {
                    if (playback != null && track != null) playback.playOrToggle()
                    else onBrowse()
                },
            )
        }
    }
}
