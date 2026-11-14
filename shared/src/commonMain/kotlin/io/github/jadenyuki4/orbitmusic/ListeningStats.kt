// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.DownloadedTrack
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.PlayEventRecord
import io.github.jadenyuki4.orbitmusic.theme.LocalLayout
import io.github.jadenyuki4.orbitmusic.theme.LocalPalette

data class ArtistStat(
    val artist: String,
    val playCount: Int,
    val totalSeconds: Long,
)

data class TrackStat(
    val item: MusicItem,
    val playCount: Int,
    val totalSeconds: Long,
)

data class ListeningStatsSummary(
    val totalPlays: Int,
    val totalListenSeconds: Long,
    val uniqueTracks: Int,
    val uniqueArtists: Int,
    val favoriteCount: Int,
    val offlineCount: Int,
    val topTracks: List<TrackStat>,
    val topArtists: List<ArtistStat>,
    val listenerTitle: String,
)

private fun normalizeArtistName(subtitle: String): String =
    subtitle.trim().removeSuffix(" - Topic").trim().ifBlank { "Artista desconocido" }

private fun formatListenDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0 min"
    if (totalSeconds < 60L) return "$totalSeconds s"
    val totalMinutes = totalSeconds / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours h $minutes min"
        hours > 0L -> "$hours h"
        else -> "$minutes min"
    }
}

private fun formatPlayCount(count: Int): String =
    if (count == 1) "1 reproducción" else "$count reproducciones"

fun computeListeningStats(
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    downloads: List<DownloadedTrack>,
): ListeningStatsSummary {
    val effectivePlays = plays.map { record ->
        val effectiveSeconds = if (record.playedSeconds > 0L) {
            record.playedSeconds
        } else {
            record.item.durationSeconds ?: 180L
        }
        record to effectiveSeconds.coerceAtLeast(0L)
    }

    val totalPlays = plays.size
    val totalListenSeconds = effectivePlays.sumOf { it.second }

    val byTrack = effectivePlays.groupBy { (record, _) -> record.item.id }
    val topTracks = byTrack.values
        .map { entries ->
            val representative = entries.maxByOrNull { it.first.playedAtEpochMillis }?.first?.item
                ?: entries.first().first.item
            TrackStat(
                item = representative,
                playCount = entries.size,
                totalSeconds = entries.sumOf { it.second },
            )
        }
        .sortedWith(
            compareByDescending<TrackStat> { it.playCount }
                .thenByDescending { it.totalSeconds },
        )
        .take(5)

    val byArtist = effectivePlays.groupBy { (record, _) ->
        normalizeArtistName(record.item.subtitle)
    }
    val topArtists = byArtist.entries
        .map { (artist, entries) ->
            ArtistStat(
                artist = artist,
                playCount = entries.size,
                totalSeconds = entries.sumOf { it.second },
            )
        }
        .sortedWith(
            compareByDescending<ArtistStat> { it.playCount }
                .thenByDescending { it.totalSeconds },
        )
        .take(5)

    val uniqueTracks = byTrack.size
    val uniqueArtists = byArtist.size
    val favoriteCount = favorites.size
    val offlineCount = downloads.size

    val listenerTitle = when {
        totalPlays >= 50 || totalListenSeconds >= 10_800L -> "Leyenda de Shinki music"
        totalPlays >= 20 || favoriteCount >= 10 -> "Melómano Hi-Res"
        offlineCount >= 5 -> "Coleccionista Offline"
        else -> "Explorador Musical"
    }

    return ListeningStatsSummary(
        totalPlays = totalPlays,
        totalListenSeconds = totalListenSeconds,
        uniqueTracks = uniqueTracks,
        uniqueArtists = uniqueArtists,
        favoriteCount = favoriteCount,
        offlineCount = offlineCount,
        topTracks = topTracks,
        topArtists = topArtists,
        listenerTitle = listenerTitle,
    )
}

@Composable
fun ListeningStatsPanel(player: Playback) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val plays by remember(player) { player.history.observeRecentPlays(200) }.collectAsState(emptyList())
    val favorites by remember(player) { player.history.observeFavorites() }.collectAsState(emptyList())
    val downloads by remember(player) { player.history.observeDownloads() }.collectAsState(emptyList())
    val incognito by player.history.incognito.collectAsState()

    val summary = remember(plays, favorites, downloads) {
        computeListeningStats(plays = plays, favorites = favorites, downloads = downloads)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(t.small),
            verticalArrangement = Arrangement.spacedBy(t.small),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "TU RESUMEN Y ESTADÍSTICAS DE ESCUCHA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = p.primary,
                    )
                    Text(
                        text = "Tu perfil musical en Shinki music",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                }
                SpotiFlacQualityBadge(
                    text = summary.listenerTitle,
                    highlighted = true,
                )
            }

            if (incognito) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = p.warning.copy(alpha = 0.14f),
                    border = BorderStroke(t.hairline, p.warning.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = "Modo incógnito activado: las reproducciones actuales no se suman a tus estadísticas.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.warning,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(t.unit),
                verticalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                StatMetricCard(
                    label = "Tiempo total",
                    value = formatListenDuration(summary.totalListenSeconds),
                    accent = p.primary,
                )
                StatMetricCard(
                    label = "Reproducciones",
                    value = summary.totalPlays.toString(),
                    accent = p.secondary,
                )
                StatMetricCard(
                    label = "Temas únicos",
                    value = summary.uniqueTracks.toString(),
                    accent = p.highlight,
                )
                StatMetricCard(
                    label = "Artistas únicos",
                    value = summary.uniqueArtists.toString(),
                    accent = p.primary,
                )
                StatMetricCard(
                    label = "Favoritas",
                    value = summary.favoriteCount.toString(),
                    accent = p.success,
                )
                StatMetricCard(
                    label = "Offline",
                    value = summary.offlineCount.toString(),
                    accent = p.secondary,
                )
            }

            if (summary.totalPlays == 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = p.background.copy(alpha = 0.55f),
                    border = BorderStroke(t.hairline, p.muted.copy(alpha = 0.2f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(t.small),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Todavía no hay reproducciones registradas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = p.text,
                        )
                        Text(
                            text = "Dale play a tus temas favoritos desde Inicio, Buscar o Biblioteca para empezar a ver tus canciones y artistas más escuchados.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.muted,
                        )
                    }
                }
            } else {
                if (summary.topTracks.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(t.unit),
                    ) {
                        Text(
                            text = "Canciones más escuchadas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = p.text,
                        )
                        summary.topTracks.forEachIndexed { index, stat ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = p.background.copy(alpha = 0.5f),
                                border = BorderStroke(t.hairline, p.muted.copy(alpha = 0.18f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(t.unit),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (index == 0) p.primary.copy(alpha = 0.22f) else p.elevated,
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (index == 0) p.primary else p.muted,
                                        )
                                    }
                                    TrackArt(
                                        item = stat.item,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = stat.item.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = p.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${normalizeArtistName(stat.item.subtitle)} · ${formatPlayCount(stat.playCount)} · ${formatListenDuration(stat.totalSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = p.muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    VividPillButton(
                                        text = "▶",
                                        active = true,
                                        accentColor = p.primary,
                                        onClick = { player.select(stat.item) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (summary.topArtists.isNotEmpty()) {
                    val maxArtistPlays = remember(summary.topArtists) {
                        summary.topArtists.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(t.unit),
                    ) {
                        Text(
                            text = "Artistas más escuchados",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = p.text,
                        )
                        summary.topArtists.forEachIndexed { index, stat ->
                            val fraction = (stat.playCount.toFloat() / maxArtistPlays.toFloat())
                                .coerceIn(0.06f, 1f)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = p.background.copy(alpha = 0.5f),
                                border = BorderStroke(t.hairline, p.muted.copy(alpha = 0.18f)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${stat.artist}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = p.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${formatPlayCount(stat.playCount)} · ${formatListenDuration(stat.totalSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = p.secondary,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(p.muted.copy(alpha = 0.18f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(percent = 50))
                                                .background(p.primary),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Surface(
        modifier = modifier.widthIn(min = 136.dp),
        shape = RoundedCornerShape(16.dp),
        color = p.elevated,
        border = BorderStroke(t.hairline, accent.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
