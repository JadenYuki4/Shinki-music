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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

val LocalOpenExpandedPlayer = compositionLocalOf<kotlinx.coroutines.flow.StateFlow<Int>?> { null }

/** Diseño adaptado de SpotiFLAC; referencias y atribuciones en NOTICE.md. */
@Composable
fun MusicShell(search: SearchViewModel, playback: Playback?, appearance: @Composable () -> Unit) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val effects = LocalEffects.current
    var destination by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Destination.Home) }
    var panel by remember { mutableStateOf("Cola") }
    var theater by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchMenuOpen by remember { mutableStateOf(false) }
    var useGifPlayerBg by remember(playback) {
        mutableStateOf(
            if (playback != null) {
                runCatching { com.russhwolf.settings.Settings().getBoolean("player.use_gif_bg", false) }.getOrDefault(false)
            } else {
                false
            }
        )
    }
    fun updateUseGifPlayerBg(enabled: Boolean) {
        useGifPlayerBg = enabled
        if (playback != null) {
            runCatching { com.russhwolf.settings.Settings().putBoolean("player.use_gif_bg", enabled) }
        }
    }
    var showEqualizerOnHome by remember { mutableStateOf(true) }
    var showDesktopEqualizer by remember { mutableStateOf(false) }
    var showQueueDialog by remember { mutableStateOf(false) }
    var mobileSheet by remember { mutableStateOf<MobilePlayerSheet?>(null) }
    val track = playback.displayTrack()
    val openExpandedSignal = LocalOpenExpandedPlayer.current?.collectAsState()?.value ?: 0
    var lastHandledOpenExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    if (openExpandedSignal > lastHandledOpenExpanded && track != null) {
        lastHandledOpenExpanded = openExpandedSignal
        theater = true
    }
    LaunchedEffect(openExpandedSignal, track) {
        if (openExpandedSignal > lastHandledOpenExpanded && track != null) {
            lastHandledOpenExpanded = openExpandedSignal
            theater = true
        }
    }

    var fallbackQuality by remember { mutableStateOf(AudioQuality.High) }
    var fallbackFormat by remember { mutableStateOf(AudioFormatPreference.Auto) }
    var fallbackProfile by remember { mutableStateOf(SoundProfile.BassBoost) }

    val activeQuality = playback?.audioQuality?.collectAsState()?.value ?: fallbackQuality
    val activeFormat = playback?.audioFormat?.collectAsState()?.value ?: fallbackFormat

    val searchingWithIme = isAndroid && destination == Destination.Search && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
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
        val mobileTheater = !desktop && theater && track != null

        Column(Modifier.fillMaxSize()) {
            if (!searchingWithIme && !mobileTheater) Surface(color = Color(0xFF15171D)) {
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
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = Color(0xFF1F222C),
                            border = BorderStroke(
                                1.dp,
                                if (destination == Destination.Search && !theater) p.primary.copy(alpha = 0.65f)
                                else p.muted.copy(alpha = 0.20f),
                            ),
                            modifier = Modifier.widthIn(min = 240.dp, max = 420.dp).fillMaxWidth()
                                .clickable { theater = false; destination = Destination.Search },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ChromeVectorIcon(
                                    kind = PlayerChromeIcon.SearchGlass,
                                    color = p.muted,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    "Buscar música",
                                    color = p.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ChromeIconButton(
                            icon = if (theater) PlayerChromeIcon.CollapseFull else PlayerChromeIcon.ExpandFull,
                            contentDesc = if (theater) "Cerrar reproductor" else "Expandir reproductor",
                            onClick = { theater = !theater },
                            size = 36.dp,
                            iconSize = 18.dp,
                            tint = if (theater) p.primary else p.text,
                            enabled = track != null || theater,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                        if (destination != Destination.Search) {
                            VividPillButton(
                                text = "⌕ Buscar",
                                accentColor = p.primary,
                                onClick = { theater = false; destination = Destination.Search },
                            )
                            if (track != null) {
                                VividPillButton(
                                    text = "▲ Reproduciendo",
                                    active = false,
                                    accentColor = p.primary,
                                    onClick = { theater = true },
                                )
                            }
                        } else {
                            Box {
                                ChromeIconButton(
                                    icon = PlayerChromeIcon.MoreVert,
                                    contentDesc = "Opciones",
                                    onClick = { searchMenuOpen = true },
                                    size = 34.dp,
                                    iconSize = 18.dp,
                                    tint = p.text,
                                )
                                DropdownMenu(
                                    expanded = searchMenuOpen,
                                    onDismissRequest = { searchMenuOpen = false },
                                    containerColor = Color(0xFF1F222C),
                                ) {
                                    if (track != null) {
                                        DropdownMenuItem(
                                            text = { Text("Abrir reproductor") },
                                            onClick = { searchMenuOpen = false; theater = true },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Descargar offline") },
                                        onClick = { searchMenuOpen = false; destination = Destination.Downloads },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ajustes") },
                                        onClick = { searchMenuOpen = false; destination = Destination.Settings },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!searchingWithIme && !mobileTheater) {
                HorizontalDivider(color = p.muted.copy(alpha = 0.14f))
            }

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
                                    if (label.contains("Ecualizador") && theater) {
                                        showDesktopEqualizer = true
                                    } else {
                                        theater = false
                                        destination = target
                                        if (label.contains("Ecualizador")) showEqualizerOnHome = true
                                    }
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
                                if (desktop) {
                                    DesktopExpandedPlayer(
                                        track = track,
                                        playback = playback,
                                        useGifPlayerBg = useGifPlayerBg,
                                        onToggleGifBg = ::updateUseGifPlayerBg,
                                        equalizerOpen = showDesktopEqualizer,
                                        onOpenEqualizer = { showDesktopEqualizer = true },
                                        onOpenQueue = if (!showPanel) { { showQueueDialog = true } } else null,
                                    )
                                } else {
                                    MobileExpandedPlayer(
                                        track = track,
                                        playback = playback,
                                        qualityBadge = "${activeQuality.spotiFlacBadge} · ${activeFormat.shortLabel}",
                                        useGifPlayerBg = useGifPlayerBg,
                                        onToggleGifBg = ::updateUseGifPlayerBg,
                                        onMinimize = { theater = false },
                                        onOpenSheet = { mobileSheet = it },
                                    )
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
                                    useGifPlayerBg = useGifPlayerBg,
                                    onToggleGifBg = ::updateUseGifPlayerBg,
                                    onOpenPlayer = { theater = true },
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
                        verticalArrangement = Arrangement.spacedBy(t.small),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                            listOf("Cola", "Letras").forEach { name ->
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
                    }
                }
            }
            if (!searchingWithIme && !mobileTheater) {
                HorizontalDivider(color = p.muted.copy(alpha = 0.16f))
                PlayerDock(
                    playback = playback,
                    theater = theater,
                    onOpenEqualizer = if (desktop) { { showDesktopEqualizer = !showDesktopEqualizer } } else null,
                    onOpenQueue = if (desktop && !showPanel) { { showQueueDialog = true } } else null,
                ) { theater = !theater }
            }
            if (!desktop && !searchingWithIme && !mobileTheater) NavigationBar(containerColor = Color(0xFF14161C)) {
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

        if (showDesktopEqualizer && desktop) {
            PlayerModalDialog(
                title = "Ecualizador de 10 bandas",
                onDismiss = { showDesktopEqualizer = false },
            ) {
                SpotiFlacEqualizerCard(
                    player = playback,
                    onSelectProfile = { prof ->
                        if (playback != null) playback.setSoundProfile(prof)
                        else fallbackProfile = prof
                    },
                )
            }
        }

        if (showQueueDialog && desktop && !showPanel) {
            PlayerModalDialog(
                title = "Cola y Letras",
                onDismiss = { showQueueDialog = false },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                    listOf("Cola", "Letras").forEach { name ->
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
            }
        }

        val currentMobileSheet = mobileSheet
        if (currentMobileSheet != null && track != null && !desktop) {
            val sheetTitle = when (currentMobileSheet) {
                MobilePlayerSheet.Device -> "Dispositivo y audio"
                MobilePlayerSheet.Lyrics -> "Letras"
                MobilePlayerSheet.Queue -> "Cola de reproducción"
                MobilePlayerSheet.Menu -> "Opciones del tema"
            }
            PlayerModalDialog(
                title = sheetTitle,
                onDismiss = { mobileSheet = null },
            ) {
                when (currentMobileSheet) {
                    MobilePlayerSheet.Device -> DeviceAudioSheetContent(
                        playback = playback,
                        fallbackQuality = fallbackQuality,
                        fallbackFormat = fallbackFormat,
                        onFallbackQuality = { fallbackQuality = it },
                        onFallbackFormat = { fallbackFormat = it },
                        onFallbackProfile = { fallbackProfile = it },
                    )
                    MobilePlayerSheet.Lyrics -> {
                        if (playback != null) Lyrics(playback, track)
                        else Text("Reproductor en vista previa sin letras cargadas.", color = p.muted)
                    }
                    MobilePlayerSheet.Queue -> {
                        if (playback != null) QueueRows(playback)
                        else Text("No hay pistas en la cola.", color = p.muted)
                    }
                    MobilePlayerSheet.Menu -> MobileMenuSheetContent(
                        track = track,
                        playback = playback,
                        quality = activeQuality,
                        format = activeFormat,
                        onOpenDeviceAudio = { mobileSheet = MobilePlayerSheet.Device },
                        onFallbackQuality = { fallbackQuality = it },
                        onFallbackFormat = { fallbackFormat = it },
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

private enum class MobilePlayerSheet {
    Device,
    Lyrics,
    Queue,
    Menu,
}

@Composable
private fun ExpandedVisualStage(
    track: MusicItem,
    playing: Boolean,
    useGifPlayerBg: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val neonPink = Color(0xFFFF4AE2)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0A0416),
        border = BorderStroke(
            width = 1.dp,
            color = if (useGifPlayerBg) neonPink.copy(alpha = 0.50f) else p.primary.copy(alpha = 0.24f),
        ),
    ) {
        if (useGifPlayerBg) {
            AnimeGifBackdropCanvas(
                playing = playing,
                modifier = Modifier.fillMaxSize(),
                cleanStage = true,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color(0xFF1B1230), Color(0xFF0A0416)))
                ),
                contentAlignment = Alignment.Center,
            ) {
                TrackArt(
                    item = track,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun EqualizerActionPill(
    active: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (active) p.primary else Color(0xFF20232D),
        border = BorderStroke(1.dp, if (active) p.primary else p.muted.copy(alpha = 0.24f)),
        modifier = Modifier
            .semantics {
                contentDescription = "Abrir ecualizador"
                role = Role.Button
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChromeVectorIcon(
                kind = PlayerChromeIcon.EqualizerBars,
                color = if (active) p.onAccent else p.text,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Ecualizador",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (active) p.onAccent else p.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DesktopExpandedPlayer(
    track: MusicItem,
    playback: Playback?,
    useGifPlayerBg: Boolean,
    onToggleGifBg: (Boolean) -> Unit,
    equalizerOpen: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: (() -> Unit)?,
) {
    val p = LocalPalette.current
    val transportState = playback.displayTransport()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactHeader = maxWidth < 680.dp
            if (compactHeader) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Reproduciendo ahora",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = p.text,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onOpenQueue != null) {
                                VividPillButton(text = "Cola", active = false, accentColor = p.secondary, onClick = onOpenQueue)
                            }
                            EqualizerActionPill(active = equalizerOpen, onClick = onOpenEqualizer)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VividPillButton(
                            text = "Carátula HD",
                            active = !useGifPlayerBg,
                            accentColor = p.primary,
                            onClick = { onToggleGifBg(false) },
                        )
                        VividPillButton(
                            text = "✦ Fondo animado",
                            active = useGifPlayerBg,
                            accentColor = Color(0xFFFF4AE2),
                            onClick = { onToggleGifBg(true) },
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Reproduciendo ahora",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VividPillButton(
                            text = "Carátula HD",
                            active = !useGifPlayerBg,
                            accentColor = p.primary,
                            onClick = { onToggleGifBg(false) },
                        )
                        VividPillButton(
                            text = "✦ Fondo animado",
                            active = useGifPlayerBg,
                            accentColor = Color(0xFFFF4AE2),
                            onClick = { onToggleGifBg(true) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onOpenQueue != null) {
                            VividPillButton(text = "Cola / Letras", active = false, accentColor = p.secondary, onClick = onOpenQueue)
                        }
                        EqualizerActionPill(active = equalizerOpen, onClick = onOpenEqualizer)
                    }
                }
            }
        }

        ExpandedVisualStage(
            track = track,
            playing = transportState.playing,
            useGifPlayerBg = useGifPlayerBg,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun MobileExpandedPlayer(
    track: MusicItem,
    playback: Playback?,
    qualityBadge: String,
    useGifPlayerBg: Boolean,
    onToggleGifBg: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onOpenSheet: (MobilePlayerSheet) -> Unit,
) {
    val p = LocalPalette.current
    val transportState = playback.displayTransport()
    var localPlaying by remember { mutableStateOf(false) }
    var localShuffle by remember { mutableStateOf(false) }
    var localRepeat by remember { mutableStateOf(RepeatMode.Off) }

    val shuffle = playback?.shuffle?.collectAsState()?.value ?: localShuffle
    val repeat = playback?.repeat?.collectAsState()?.value ?: localRepeat
    val isPlaying = if (playback != null) transportState.playing else localPlaying
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }

    val duration = (transportState.duration.takeIf { it > 0L } ?: track.durationSeconds ?: 0L).coerceAtLeast(0L)
    val currentPos = (dragging ?: transportState.position.coerceAtLeast(0L).toFloat()).toLong().coerceAtLeast(0L)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1D1030), Color(0xFF110A1E), Color(0xFF0A0612)))
        )
    ) {
        val narrow = maxWidth < 380.dp
        val compactHeight = maxHeight < 760.dp
        val hPad = if (narrow) 16.dp else 22.dp
        val vPad = if (compactHeight) 8.dp else 12.dp
        val vGap = if (compactHeight) 8.dp else 12.dp

        Column(
            Modifier.fillMaxSize().padding(horizontal = hPad, vertical = vPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(vGap),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ChromeIconButton(
                    icon = PlayerChromeIcon.MinimizeDown,
                    contentDesc = "Minimizar reproductor",
                    onClick = onMinimize,
                    size = 40.dp,
                    iconSize = 22.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Reproduciendo ahora",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    Text(
                        ShinkiAppInfo.APP_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                }
                ChromeIconButton(
                    icon = PlayerChromeIcon.MoreVert,
                    contentDesc = "Menú de pista",
                    onClick = { onOpenSheet(MobilePlayerSheet.Menu) },
                    size = 40.dp,
                    iconSize = 20.dp,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VividPillButton(
                    text = "Carátula HD",
                    active = !useGifPlayerBg,
                    accentColor = p.primary,
                    onClick = { onToggleGifBg(false) },
                )
                VividPillButton(
                    text = "✦ Fondo animado",
                    active = useGifPlayerBg,
                    accentColor = Color(0xFFFF4AE2),
                    onClick = { onToggleGifBg(true) },
                )
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight).coerceAtLeast(150.dp)
                ExpandedVisualStage(
                    track = track,
                    playing = isPlaying,
                    useGifPlayerBg = useGifPlayerBg,
                    modifier = Modifier.size(side),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = track.title,
                        style = if (narrow) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = p.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SpotiFlacQualityBadge(
                        text = qualityBadge,
                        highlighted = false,
                    )
                }
                TrackFavoriteButton(
                    playback = playback,
                    track = track,
                    size = 44.dp,
                    iconSize = 24.dp,
                )
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Slider(
                    value = if (duration > 0L) (dragging ?: transportState.position.coerceIn(0L, duration).toFloat()) else 0f,
                    onValueChange = { if (duration > 0L) dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { playback?.seekTo(it.toLong()) }
                        dragging = null
                    },
                    valueRange = 0f..(duration.coerceAtLeast(1L)).toFloat(),
                    enabled = duration > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = p.primary,
                        activeTrackColor = p.primary,
                        inactiveTrackColor = Color(0xFF2E2444),
                        disabledThumbColor = p.muted.copy(alpha = 0.45f),
                        disabledActiveTrackColor = p.muted.copy(alpha = 0.30f),
                        disabledInactiveTrackColor = Color(0xFF2E2444),
                    ),
                    modifier = Modifier.fillMaxWidth().height(26.dp)
                        .semantics { contentDescription = "Barra de progreso" },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(clock(currentPos), style = MaterialTheme.typography.labelSmall, color = p.muted)
                    Text(
                        if (duration > 0L) clock(duration) else "0:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.muted,
                    )
                }
            }

            val edgeBtn = if (narrow) 40.dp else 44.dp
            val skipBtn = if (narrow) 44.dp else 48.dp
            val playBtn = if (compactHeight || narrow) 58.dp else 64.dp
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VividPlayerIconButton(
                    icon = PlayerIconKind.Shuffle,
                    contentDesc = "Aleatorio",
                    active = shuffle,
                    size = edgeBtn,
                    bordered = false,
                    onClick = {
                        if (playback != null) playback.shuffle.value = !shuffle
                        else localShuffle = !localShuffle
                    },
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Previous,
                    contentDesc = "Canción anterior",
                    size = skipBtn,
                    bordered = false,
                    onClick = { playback?.back() },
                )
                VividPlayPauseButton(
                    playing = isPlaying,
                    size = playBtn,
                    onClick = {
                        if (playback != null) playback.playOrToggle()
                        else localPlaying = !localPlaying
                    },
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Next,
                    contentDesc = "Siguiente canción",
                    size = skipBtn,
                    bordered = false,
                    onClick = { playback?.next() },
                )
                VividPlayerIconButton(
                    icon = if (repeat == RepeatMode.One) PlayerIconKind.RepeatOne else PlayerIconKind.Repeat,
                    contentDesc = "Modo de repetición",
                    active = repeat != RepeatMode.Off,
                    size = edgeBtn,
                    bordered = false,
                    onClick = {
                        val nextMode = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]
                        if (playback != null) playback.repeat.value = nextMode
                        else localRepeat = nextMode
                    },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpandedSecondaryAction(
                    icon = PlayerChromeIcon.DeviceSpeaker,
                    label = "Dispositivo",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSheet(MobilePlayerSheet.Device) },
                )
                Box(Modifier.width(1.dp).height(26.dp).background(p.muted.copy(alpha = 0.25f)))
                ExpandedSecondaryAction(
                    icon = PlayerChromeIcon.LyricsLines,
                    label = "Letras",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSheet(MobilePlayerSheet.Lyrics) },
                )
                Box(Modifier.width(1.dp).height(26.dp).background(p.muted.copy(alpha = 0.25f)))
                ExpandedSecondaryAction(
                    icon = PlayerChromeIcon.QueueList,
                    label = "Cola",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSheet(MobilePlayerSheet.Queue) },
                )
            }
        }
    }
}

@Composable
private fun ExpandedSecondaryAction(
    icon: PlayerChromeIcon,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChromeVectorIcon(
            kind = icon,
            color = p.muted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerModalDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp),
            shape = RoundedCornerShape(24.dp),
            color = p.surface,
            border = BorderStroke(1.dp, p.primary.copy(alpha = 0.28f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.small)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.text)
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                HorizontalDivider(color = p.elevated)
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(t.small),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun DeviceAudioSheetContent(
    playback: Playback?,
    fallbackQuality: AudioQuality,
    fallbackFormat: AudioFormatPreference,
    onFallbackQuality: (AudioQuality) -> Unit,
    onFallbackFormat: (AudioFormatPreference) -> Unit,
    onFallbackProfile: (SoundProfile) -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    var localVolume by remember { mutableFloatStateOf(0.8f) }
    var localMuted by remember { mutableStateOf(false) }
    val volume = playback?.volume?.collectAsState()?.value ?: localVolume
    val muted = playback?.muted?.collectAsState()?.value ?: localMuted
    val quality = playback?.audioQuality?.collectAsState()?.value ?: fallbackQuality
    val format = playback?.audioFormat?.collectAsState()?.value ?: fallbackFormat

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = p.elevated.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, p.secondary.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Volumen de salida", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.text)
                Text(
                    if (muted || volume == 0f) "Silenciado" else "${kotlin.math.round(volume * 100).toInt()}%",
                    color = if (muted) p.warning else p.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Slider(
                value = if (muted) 0f else volume,
                onValueChange = {
                    if (playback != null) playback.setVolume(it)
                    else { localVolume = it; localMuted = it == 0f }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ajustar volumen" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                VividPillButton(
                    text = if (muted) "Activar sonido" else "Silenciar",
                    active = muted,
                    accentColor = p.secondary,
                    onClick = { playback?.toggleMute() ?: run { localMuted = !localMuted } },
                )
                VividPillButton(
                    text = "− 10%",
                    accentColor = p.primary,
                    onClick = { playback?.volumeDown(0.1f) ?: run { localMuted = false; localVolume = (localVolume - 0.1f).coerceIn(0f, 1f) } },
                )
                VividPillButton(
                    text = "+ 10%",
                    accentColor = p.primary,
                    onClick = { playback?.volumeUp(0.1f) ?: run { localMuted = false; localVolume = (localVolume + 0.1f).coerceIn(0f, 1f) } },
                )
            }
        }
    }

    Text("Calidad y formato de audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        AudioQuality.entries.forEach { q ->
            VividPillButton(
                text = q.shortLabel,
                active = q == quality,
                accentColor = p.primary,
                onClick = { if (playback != null) playback.setAudioQuality(q) else onFallbackQuality(q) },
            )
        }
        AudioFormatPreference.entries.forEach { fmt ->
            VividPillButton(
                text = fmt.shortLabel,
                active = fmt == format,
                accentColor = p.secondary,
                onClick = { if (playback != null) playback.setAudioFormat(fmt) else onFallbackFormat(fmt) },
            )
        }
    }

    SpotiFlacEqualizerCard(
        player = playback,
        onSelectProfile = { prof ->
            if (playback != null) playback.setSoundProfile(prof) else onFallbackProfile(prof)
        },
    )
}

@Composable
private fun MobileMenuSheetContent(
    track: MusicItem,
    playback: Playback?,
    quality: AudioQuality,
    format: AudioFormatPreference,
    onOpenDeviceAudio: () -> Unit,
    onFallbackQuality: (AudioQuality) -> Unit,
    onFallbackFormat: (AudioFormatPreference) -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val autoplay = playback?.autoplay?.collectAsState()?.value ?: true
    val privateMode = playback?.history?.incognito?.collectAsState()?.value ?: false
    val speed = playback?.speed?.collectAsState()?.value ?: 1f
    val sleepMinutes = playback?.sleepMinutes?.collectAsState()?.value ?: 0

    Text("${track.title} · ${track.subtitle}", style = MaterialTheme.typography.bodyMedium, color = p.muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        TrackDownloadButton(
            playback = playback,
            item = track,
            compact = false,
            onDownloadedClick = { playback?.removeDownload(track.id) },
        )
        VividPillButton(
            text = "Ecualizador y volumen",
            accentColor = p.primary,
            onClick = onOpenDeviceAudio,
        )
        if (playback != null) {
            FilterChip(
                selected = autoplay && !privateMode,
                enabled = !privateMode,
                onClick = { playback.autoplay.value = !autoplay },
                label = { Text("Reproducción automática") },
            )
        }
    }

    if (playback != null) {
        Text("Velocidad (${speed}×)", style = MaterialTheme.typography.titleSmall, color = p.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit), verticalArrangement = Arrangement.spacedBy(t.unit)) {
            listOf(.75f, 1f, 1.25f, 1.5f).forEach { rate ->
                VividPillButton(
                    text = "${rate}×",
                    active = speed == rate,
                    accentColor = p.primary,
                    onClick = { playback.setSpeed(rate) },
                )
            }
        }
        Text("Temporizador", style = MaterialTheme.typography.titleSmall, color = p.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit), verticalArrangement = Arrangement.spacedBy(t.unit)) {
            listOf(0, 15, 30, 60).forEach { mins ->
                VividPillButton(
                    text = if (mins == 0) "Apagado" else "${mins}m",
                    active = sleepMinutes == mins,
                    accentColor = p.secondary,
                    onClick = { playback.sleep(mins) },
                )
            }
        }
    }

    Text("Calidad (${quality.shortLabel}) · Formato (${format.shortLabel})", style = MaterialTheme.typography.titleSmall, color = p.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit), verticalArrangement = Arrangement.spacedBy(t.unit)) {
        AudioQuality.entries.forEach { q ->
            VividPillButton(
                text = q.shortLabel,
                active = q == quality,
                accentColor = p.primary,
                onClick = { if (playback != null) playback.setAudioQuality(q) else onFallbackQuality(q) },
            )
        }
        AudioFormatPreference.entries.forEach { fmt ->
            VividPillButton(
                text = fmt.shortLabel,
                active = fmt == format,
                accentColor = p.secondary,
                onClick = { if (playback != null) playback.setAudioFormat(fmt) else onFallbackFormat(fmt) },
            )
        }
    }
}

@Composable
private fun ContinueListeningCard(
    playback: Playback?,
    track: MusicItem?,
    useGifPlayerBg: Boolean,
    onToggleGifBg: (Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    onBrowse: () -> Unit,
) {
    val p = LocalPalette.current
    val transportState = playback.displayTransport()
    val gifActive = useGifPlayerBg && track != null
    val neonPink = Color(0xFFFF4AE2)
    val neonViolet = Color(0xFF9D4EDD)
    val duration = transportState.duration.takeIf { it > 0L } ?: track?.durationSeconds ?: 0L
    val prog = if (duration > 0L) {
        (transportState.position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (gifActive) Color(0xFF0A0416) else Color(0xFF2F4578),
        border = BorderStroke(
            width = if (gifActive) 1.3.dp else 1.dp,
            color = if (gifActive) neonPink.copy(alpha = 0.72f) else p.primary.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth().clickable {
            if (track != null) onOpenPlayer() else onBrowse()
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (gifActive) {
                AnimeGifBackdropCanvas(
                    playing = transportState.playing,
                    modifier = Modifier.matchParentSize(),
                    compactCard = true,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!gifActive) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF1F2D4F),
                            modifier = Modifier.size(84.dp),
                        ) {
                            TrackArt(track, Modifier.fillMaxSize())
                        }
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (track != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(percent = 50),
                                    color = if (gifActive) Color(0xCC210C42) else Color(0xFF1F2D4F),
                                    border = BorderStroke(1.dp, (if (gifActive) neonPink else p.primary).copy(alpha = 0.55f)),
                                    modifier = Modifier.clickable { onToggleGifBg(!useGifPlayerBg) },
                                ) {
                                    Text(
                                        text = if (gifActive) "✦ Fondo animado · Carátula HD" else "Carátula HD · Fondo animado",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (gifActive) Color(0xFFFFD8FC) else Color(0xFFD8E4FF),
                                    )
                                }
                            }
                        }
                        Text(
                            text = track?.title ?: "Sin reproducción",
                            style = if (gifActive) {
                                MaterialTheme.typography.titleLarge.copy(
                                    shadow = Shadow(color = neonPink, offset = Offset.Zero, blurRadius = 14f),
                                    letterSpacing = 0.3.sp,
                                )
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (gifActive) Color(0xFFFFF2FF) else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track?.subtitle ?: "Elegí una canción para empezar",
                            style = if (gifActive) {
                                MaterialTheme.typography.bodyMedium.copy(
                                    shadow = Shadow(color = neonViolet, offset = Offset.Zero, blurRadius = 8f),
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = if (gifActive) Color(0xFFF3A6FF) else Color(0xFFD0DCFA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                LinearProgressIndicator(
                    progress = { prog },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(percent = 50)),
                    color = if (gifActive) neonPink else p.primary,
                    trackColor = if (gifActive) Color(0x6649217A) else Color(0xFF1E2D52),
                )
            }
        }
    }
}
