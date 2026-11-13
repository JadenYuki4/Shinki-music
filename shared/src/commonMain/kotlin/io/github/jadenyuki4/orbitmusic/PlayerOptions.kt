// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*
import io.github.jadenyuki4.orbitmusic.search.userMessage
import kotlinx.coroutines.launch

/**
 * SpotiFLAC 10-Band Graphic Equalizer Card & Curve ported from
 * `spotiflac-main/lib/screens/equalizer_screen.dart` & `equalizer_curve.dart` (Showcase Screen 4).
 */
@Composable
fun SpotiFlacEqualizerCard(
    activeProfile: SoundProfile,
    onSelectProfile: (SoundProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    var eqEnabled by remember { mutableStateOf(true) }
    var selectedPreset by remember(activeProfile) {
        mutableStateOf(
            when (activeProfile) {
                SoundProfile.BassBoost -> SpotiFlacEqPreset.BassBoost
                SoundProfile.HiFi -> SpotiFlacEqPreset.TrebleBoost
                SoundProfile.AnimeOST -> SpotiFlacEqPreset.Vocal
                SoundProfile.Flat -> SpotiFlacEqPreset.Flat
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1C1E26),
        border = BorderStroke(1.dp, p.primary.copy(alpha = 0.22f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top Row: Active Preset Title + Beta badge + 10 bands Preamp -6.0 dB + Switch
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = selectedPreset.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = Color(0xFF3E354B),
                        ) {
                            Text(
                                "Beta",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = p.highlight,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "10 bandas · Preamplificador ${SpotiFlacAppInfo.PREAMP_DEFAULT_DB} dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                        )
                    }
                }
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { eqEnabled = it },
                )
            }

            // 10-Band Frequency Response Curve (`equalizer_curve.dart`)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF15171E),
                border = BorderStroke(1.dp, p.muted.copy(alpha = 0.14f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(136.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left dB axis labels (+12, 0, -12)
                        Column(
                            Modifier.fillMaxHeight().padding(end = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text("+12", style = MaterialTheme.typography.labelSmall, color = p.muted)
                            Text("0", style = MaterialTheme.typography.labelSmall, color = p.muted)
                            Text("-12", style = MaterialTheme.typography.labelSmall, color = p.muted)
                        }
                        // Curve Canvas
                        Canvas(Modifier.weight(1f).fillMaxHeight()) {
                            val w = size.width
                            val h = size.height
                            val midY = h / 2f
                            val gridColor = p.muted.copy(alpha = 0.16f)

                            // Horizontal grid lines (+12, +6, 0, -6, -12 dB)
                            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                                val y = h * frac
                                drawLine(
                                    color = if (frac == 0.5f) p.muted.copy(alpha = 0.30f) else gridColor,
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            // Vertical decade lines (100Hz, 1kHz, 10kHz)
                            listOf(0.18f, 0.52f, 0.86f).forEach { xFrac ->
                                val x = w * xFrac
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, h),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }

                            val bands = if (eqEnabled) selectedPreset.bandsDb else SpotiFlacEqPreset.Flat.bandsDb
                            val pts = bands.mapIndexed { idx, db ->
                                val x = w * (idx.toFloat() / (bands.size - 1).coerceAtLeast(1))
                                val normalized = (db.coerceIn(-12f, 12f) / 12f)
                                val y = midY - normalized * (h * 0.42f)
                                Offset(x, y)
                            }

                            if (pts.size >= 2) {
                                val curvePath = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 0 until pts.lastIndex) {
                                        val p0 = pts[i]
                                        val p1 = pts[i + 1]
                                        val cx = (p0.x + p1.x) / 2f
                                        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                    }
                                }
                                val fillPath = Path().apply {
                                    addPath(curvePath)
                                    lineTo(pts.last().x, midY)
                                    lineTo(pts.first().x, midY)
                                    close()
                                }
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            p.primary.copy(alpha = if (eqEnabled) 0.32f else 0.10f),
                                            p.primary.copy(alpha = 0.03f),
                                        )
                                    ),
                                )
                                drawPath(
                                    path = curvePath,
                                    color = if (eqEnabled) p.primary else p.muted,
                                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                                )
                                pts.forEach { pt ->
                                    drawCircle(
                                        color = Color(0xFF15171E),
                                        radius = 4.5.dp.toPx(),
                                        center = pt,
                                    )
                                    drawCircle(
                                        color = if (eqEnabled) p.primary else p.muted,
                                        radius = 3.2.dp.toPx(),
                                        center = pt,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 28.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("100", style = MaterialTheme.typography.labelSmall, color = p.muted)
                        Text("1.0k", style = MaterialTheme.typography.labelSmall, color = p.muted)
                        Text("10k", style = MaterialTheme.typography.labelSmall, color = p.muted)
                    }
                }
            }

            // Presets section from `equalizer_screen.dart`
            Text("Preajustes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.text)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(t.unit),
                verticalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                SpotiFlacEqPreset.entries.forEach { preset ->
                    val isSelected = selectedPreset == preset
                    VividPillButton(
                        text = if (isSelected) "✓ ${preset.displayName}" else preset.displayName,
                        active = isSelected,
                        accentColor = p.primary,
                        onClick = {
                            selectedPreset = preset
                            onSelectProfile(preset.mappedProfile)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerOptions(player: Playback, item: MusicItem, compact: Boolean) {
    val t = LocalLayout.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by player.engine.state.collectAsState()
    val favorite by remember(item.id, player) { player.history.observeIsFavorite(item.id) }.collectAsState(false)
    val shuffle by player.shuffle.collectAsState()
    val repeat by player.repeat.collectAsState()
    val autoplay by player.autoplay.collectAsState()
    val privateMode by player.history.incognito.collectAsState()
    val minutes by player.sleepMinutes.collectAsState()
    val volume by player.volume.collectAsState()
    val muted by player.muted.collectAsState()
    val speed by player.speed.collectAsState()
    val quality by player.audioQuality.collectAsState()
    val format by player.audioFormat.collectAsState()
    val soundProfile by player.soundProfile.collectAsState()
    val streamInfo by player.currentStream.collectAsState()
    var sleepMenu by remember { mutableStateOf(false) }
    var speedsMenu by remember { mutableStateOf(false) }

    // 1. SpotiFLAC Now Playing Expressive Transport (`showcase-dark.png` Screen 3)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(t.small),
            verticalArrangement = Arrangement.spacedBy(t.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("CONTROLES DE REPRODUCCIÓN", style = MaterialTheme.typography.labelSmall, color = p.primary)
                SpotiFlacQualityBadge(
                    text = "✦ ${quality.spotiFlacBadge}",
                    highlighted = true,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VividPlayerIconButton(
                    icon = PlayerIconKind.Shuffle,
                    contentDesc = "Aleatorio",
                    active = shuffle,
                    onClick = { player.shuffle.value = !shuffle },
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Previous,
                    contentDesc = "Canción anterior",
                    size = 46.dp,
                    onClick = player::back,
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Rewind10,
                    contentDesc = "Retroceder 10 segundos",
                    onClick = { player.seekBy(-10) },
                )
                VividPlayPauseButton(
                    playing = state.playing,
                    onClick = { player.playOrToggle() },
                    size = 64.dp,
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Forward10,
                    contentDesc = "Adelantar 10 segundos",
                    onClick = { player.seekBy(10) },
                )
                VividPlayerIconButton(
                    icon = PlayerIconKind.Next,
                    contentDesc = "Siguiente canción",
                    size = 46.dp,
                    onClick = { player.next() },
                )
                VividPlayerIconButton(
                    icon = if (repeat == RepeatMode.One) PlayerIconKind.RepeatOne else PlayerIconKind.Repeat,
                    contentDesc = "Modo de repetición",
                    active = repeat != RepeatMode.Off,
                    onClick = { player.repeat.value = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size] },
                )
                VividPillButton(
                    text = if (favorite) "♥ Me gusta" else "♡ Me gusta",
                    active = favorite,
                    accentColor = p.primary,
                    onClick = { scope.launch { player.history.toggleFavorite(item) } },
                )
                TrackDownloadButton(
                    playback = player,
                    item = item,
                    compact = false,
                    onDownloadedClick = { player.removeDownload(item.id) },
                )
            }
            val downloadErr = player.downloadErrors.collectAsState().value[item.id]
            if (downloadErr != null) {
                Text(downloadErr, color = p.error, style = MaterialTheme.typography.bodySmall)
            }
            val isDownloaded by remember(player, item.id) { player.history.observeIsDownloaded(item.id) }.collectAsState(false)
            if (isDownloaded) {
                Text(
                    "✓ Disponible sin conexión · Tocá «✓ Guardada offline» para eliminarla de tus descargas.",
                    color = p.success,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(t.unit),
                verticalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                FilterChip(
                    selected = shuffle,
                    onClick = { player.shuffle.value = !shuffle },
                    label = { Text("⇄ Aleatorio") },
                )
                FilterChip(
                    selected = autoplay && !privateMode,
                    enabled = !privateMode,
                    onClick = { player.autoplay.value = !autoplay },
                    label = { Text("✦ Reproducción automática") },
                )
                TextButton(
                    onClick = { player.repeat.value = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size] },
                ) {
                    val repeatLabel = when (repeat) {
                        RepeatMode.Off -> "Apagado"
                        RepeatMode.All -> "Todo"
                        RepeatMode.One -> "Una"
                    }
                    Text("↻ Repetir · $repeatLabel")
                }
                Box {
                    TextButton(onClick = { sleepMenu = true }) {
                        Text(if (minutes == 0) "☾ Temporizador" else "☾ Apagar en ${minutes}m")
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        for (n in listOf(0, 5, 15, 30, 60)) DropdownMenuItem(
                            text = { Text(if (n == 0) "Desactivado" else "$n minutos") },
                            onClick = { player.sleep(n); sleepMenu = false },
                        )
                    }
                }
                Box {
                    TextButton(onClick = { speedsMenu = true }) { Text("◈ Velocidad · ${speed}×") }
                    DropdownMenu(expanded = speedsMenu, onDismissRequest = { speedsMenu = false }) {
                        listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("${rate}×") },
                                onClick = { player.setSpeed(rate); speedsMenu = false },
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. Volume Controls Card (`player_volume_control.dart` + Subir y Bajar Volumen)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = p.surface,
        border = BorderStroke(t.hairline, p.secondary.copy(alpha = t.outlineAlpha)),
    ) {
        Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("CONTROL DE VOLUMEN Y SALIDA", style = MaterialTheme.typography.labelSmall, color = p.secondary)
                Text(
                    if (muted || volume == 0f) "Silenciado (0%)" else "Volumen · ${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (muted) p.warning else p.text,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                OutlinedButton(
                    onClick = { player.volumeDown(0.1f) },
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("− Bajar (-10%)") }
                Slider(
                    value = if (muted) 0f else volume,
                    onValueChange = player::setVolume,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { player.volumeUp(0.1f) },
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("+ Subir (+10%)") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                TextButton(onClick = { player.toggleMute() }) {
                    Text(if (muted) "Restaurar sonido" else "Silenciar (Mute)")
                }
                listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1.0f to "100%").forEach { (presetVol, label) ->
                    FilterChip(
                        selected = !muted && kotlin.math.abs(volume - presetVol) < 0.04f,
                        onClick = { player.setVolume(presetVol) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    // 3. SpotiFLAC 10-Band Equalizer Studio (`showcase-dark.png` Screen 4)
    SpotiFlacEqualizerCard(
        activeProfile = soundProfile,
        onSelectProfile = player::setSoundProfile,
    )

    // 4. Audio Quality & Container Format Selector (`audio_quality_badges.dart`)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
    ) {
        Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
            Text("CALIDAD DE AUDIO Y FORMATO DE CÓDEC", style = MaterialTheme.typography.labelSmall, color = p.primary)

            val activeInfo = buildString {
                append("${quality.spotiFlacBadge} · Formato: ${streamInfo?.formatLabel ?: format.shortLabel}")
                streamInfo?.bitrateKbps?.let { append(" · ${it} kbps") }
                append(" (${quality.approxSizeLabel})")
            }
            Text(activeInfo, style = MaterialTheme.typography.bodyMedium, color = p.secondary)

            Text("Elegir nivel de calidad (Hi-Res 24-bit vs Menor peso):", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(t.micro)) {
                AudioQuality.entries.forEach { q ->
                    val selected = quality == q
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) p.elevated else p.background.copy(alpha = 0.5f),
                        border = BorderStroke(t.hairline, if (selected) p.primary else p.muted.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth().clickable { player.setAudioQuality(q) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = t.small, vertical = t.unit),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (selected) "✦ " else "○ ") + q.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) p.primary else p.text,
                                )
                                Text(q.description, style = MaterialTheme.typography.bodyMedium, color = p.muted)
                            }
                            SpotiFlacQualityBadge(
                                text = "${q.bitDepthLabel} · ${q.approxSizeLabel}",
                                highlighted = selected,
                            )
                        }
                    }
                }
            }

            Text("Formato de contenedor / códec de audio:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                AudioFormatPreference.entries.forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        onClick = { player.setAudioFormat(fmt) },
                        label = { Text(fmt.label) },
                    )
                }
            }
        }
    }

    if (compact) {
        Text("Cola de reproducción", style = MaterialTheme.typography.titleLarge)
        QueueRows(player)
        Lyrics(player, item)
    }
}

@Composable
fun QueueRows(player: Playback) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val queue by player.queue.collectAsState()
    val activeId = player.engine.state.collectAsState().value.track?.id
    if (queue.isEmpty()) Text("Agregá una canción desde Buscar.", color = p.muted)
    else if (queue.size > 1) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${queue.size} pistas en la cola", style = MaterialTheme.typography.labelSmall, color = p.muted)
            TextButton(onClick = { player.clearQueue() }) { Text("Vaciar cola") }
        }
    }
    queue.forEachIndexed { index, item ->
        val isCurrent = item.id == activeId
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isCurrent) p.elevated else p.surface,
            border = if (isCurrent) BorderStroke(t.hairline, p.primary) else null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Column(Modifier.padding(t.unit)) {
                TextButton(onClick = { player.select(item) }) {
                    Text(
                        (if (isCurrent) "▶ " else "${index + 1}. ") + item.title,
                        maxLines = 2,
                        color = if (isCurrent) p.primary else p.text,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = index > 0, onClick = { player.move(item, -1) }) { Text("↑ Subir") }
                    TextButton(enabled = index < queue.lastIndex, onClick = { player.move(item, 1) }) { Text("↓ Bajar") }
                    TextButton(onClick = { player.remove(item) }) { Text("✕ Quitar") }
                }
            }
        }
    }
}

@Composable
fun Discovery(player: Playback) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val searches by remember(player) { player.history.observeRecentSearches(8) }.collectAsState(emptyList())
    val plays by remember(player) { player.history.observeRecentPlays(30) }.collectAsState(emptyList())
    val favorites by remember(player) { player.history.observeFavorites() }.collectAsState(emptyList())
    val privateMode by player.history.incognito.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    if (privateMode) return
    val rules by player.taste.rules.collectAsState()
    val personalized = rules.any { it.mode == TasteMode.Favorite } || searches.isNotEmpty() || plays.isNotEmpty() || favorites.isNotEmpty()

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                if (personalized) "Recomendado para vos" else "Tendencias en ${SpotiFlacAppInfo.APP_NAME}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (personalized) {
                Text(
                    "Algoritmo adaptativo basado en tus búsquedas, reproducciones y favoritos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.muted,
                )
            }
        }
        TextButton(onClick = { refresh++ }) { Text("↻ Actualizar") }
    }

    var result by remember(player, searches, plays, favorites, refresh, rules) { mutableStateOf<SourceResult<List<Pick>>?>(null) }
    LaunchedEffect(player, searches, plays, favorites, refresh, rules) {
        result = recommendations(player.source, searches, plays, favorites, rules = rules)
    }
    when (val feed = result) {
        null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is SourceResult.Failure -> Text(feed.error.userMessage(), color = p.error)
        is SourceResult.Success -> {
            if (feed.value.isEmpty()) Text("No se encontraron canciones nuevas para tu actividad reciente.", color = p.muted)
            val items = feed.value.map { it.item }
            if (items.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
                    VividPillButton(
                        text = "▶ Reproducir mix recomendado (${items.size})",
                        active = true,
                        accentColor = p.primary,
                        onClick = { player.playFrom(items.first(), items) },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(t.small)) {
                items(feed.value, key = { it.item.id }) { pick ->
                    val song = pick.item
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = p.surface,
                        border = BorderStroke(t.hairline, p.muted.copy(alpha = 0.18f)),
                    ) {
                        Column(
                            Modifier.width(t.artwork)
                                .clickable(onClickLabel = "Reproducir canción") { player.playFrom(song, items) },
                        ) {
                            Box {
                                TrackArt(song, Modifier.fillMaxWidth().aspectRatio(1f))
                                SpotiFlacQualityBadge(
                                    text = "24-bit · ${pick.badge}",
                                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                ) {
                                    VividPlayPauseButton(
                                        playing = false,
                                        onClick = { player.playFrom(song, items) },
                                        size = 36.dp,
                                    )
                                }
                            }
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    song.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    song.subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = p.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (personalized) Text(
                                    "Basado en ${pick.reason}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = p.primary,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { player.enqueue(song) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) {
                                        Text("+ Cola", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TrackDownloadButton(playback = player, item = song, compact = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pantalla de Biblioteca en español con filtro de Descargadas Offline, Favoritas, Historial y Listas.
 */
@Composable
fun Library(player: Playback, onBrowseSearch: () -> Unit = {}) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val favorites by remember(player) { player.history.observeFavorites() }.collectAsState(emptyList())
    val plays by remember(player) { player.history.observeRecentPlays(30) }.collectAsState(emptyList())
    val downloads by remember(player) { player.history.observeDownloads() }.collectAsState(emptyList())
    val playlists by player.history.playlists.lists.collectAsState()
    val scope = rememberCoroutineScope()
    val privateMode by player.history.incognito.collectAsState()
    var selectedTab by remember { mutableStateOf("Songs") }

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.unit),
        verticalArrangement = Arrangement.spacedBy(t.unit),
    ) {
        val tabs = listOf(
            "Songs" to "♪ Canciones ${favorites.size + plays.size}",
            "Offline" to "↓ Offline ${downloads.size}",
            "Playlists" to "☰ Listas ${playlists.size}",
            "History" to "◷ Historial",
        )
        tabs.forEach { (id, label) ->
            VividPillButton(
                text = label,
                active = selectedTab == id,
                accentColor = p.primary,
                onClick = { selectedTab = id },
            )
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${favorites.size} favoritas · ${plays.distinctBy { it.item.id }.size} recientes · ${downloads.size} offline",
            style = MaterialTheme.typography.bodyMedium,
            color = p.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(t.unit)) {
            SpotiFlacQualityBadge("↓↑ Más recientes", highlighted = true)
            SpotiFlacQualityBadge("⧩ Filtros · 24-bit FLAC")
        }
    }

    if (selectedTab == "Offline") {
        OfflineDownloadsPanel(playback = player, onBrowseSearch = onBrowseSearch)
        return
    }

    if (selectedTab == "Songs" || selectedTab == "Playlists") {
        PlaylistPanel(player)
    }

    if (selectedTab == "Songs") {
        if (downloads.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Descargadas offline (${downloads.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { selectedTab = "Offline" }) { Text("Ver lista completa →") }
            }
            downloads.take(5).forEach { entry ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { player.playFrom(entry.item, downloads.map { it.item }) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("✓ ${entry.item.title} · ${entry.item.subtitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SpotiFlacQualityBadge("OFFLINE", highlighted = true)
                }
            }
        }
        Text("Favoritas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (favorites.isEmpty()) Text("Guardá canciones desde el reproductor expandido.", color = p.muted)
        favorites.take(100).forEach { song ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                TextButton(
                    onClick = { player.playFrom(song, favorites) },
                    modifier = Modifier.weight(1f),
                ) { Text("♥ ${song.title} · ${song.subtitle}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TrackDownloadButton(playback = player, item = song, compact = true)
            }
        }
    }

    if (selectedTab == "Songs" || selectedTab == "History") {
        Text("Historial de reproducción", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!privateMode) plays.distinctBy { it.item.id }.forEach { record ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                TextButton(
                    onClick = { player.select(record.item) },
                    modifier = Modifier.weight(1f),
                ) { Text("♪ ${record.item.title} · ${record.item.subtitle}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TrackDownloadButton(playback = player, item = record.item, compact = true)
            }
        }
        FilterChip(selected = privateMode, onClick = { player.history.setIncognito(!privateMode) }, label = { Text("Modo incógnito") })
        var confirm by remember { mutableStateOf(false) }
        TextButton(onClick = { confirm = true }) { Text("Borrar historial de búsquedas y reproducción") }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("¿Borrar historial?") },
            text = { Text("Esto elimina las búsquedas guardadas y el historial de reproducción de este dispositivo. Tus favoritas y descargas offline se conservan.") },
            confirmButton = { TextButton(onClick = {
                confirm = false
                scope.launch { player.history.clearSearchHistory(); player.history.clearPlayHistory() }
            }) { Text("Borrar") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
    }
}

@Composable
fun Lyrics(player: Playback, item: MusicItem) {
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var result by remember(item.id, attempt) { mutableStateOf<SourceResult<String?>?>(null) }
    if (attempt > 0) LaunchedEffect(item.id, attempt) { result = player.source.lyrics(item) }
    when (val text = result) {
        null -> if (attempt > 0) LinearProgressIndicator()
        is SourceResult.Success -> Text(text.value ?: "No hay letra disponible para esta canción.")
        is SourceResult.Failure -> Text(text.error.userMessage())
    }
    if (attempt == 0 || result is SourceResult.Failure) TextButton(onClick = { attempt++ }) { Text("Buscar letra en LRCLIB") }
}
