// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.DownloadedTrack
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.MusicKind
import io.github.jadenyuki4.orbitmusic.theme.LocalLayout
import io.github.jadenyuki4.orbitmusic.theme.LocalPalette

@Composable
fun TrackDownloadButton(
    playback: Playback?,
    item: MusicItem,
    compact: Boolean = true,
    onDownloadedClick: (() -> Unit)? = null,
) {
    if (playback == null || item.kind != MusicKind.Song) return
    val p = LocalPalette.current
    val isDownloaded by remember(playback, item.id) { playback.history.observeIsDownloaded(item.id) }
        .collectAsState(false)
    val activeDownloads by playback.activeDownloads.collectAsState()
    val downloadErrors by playback.downloadErrors.collectAsState()

    val isDownloading = activeDownloads.containsKey(item.id)
    val progress = activeDownloads[item.id]
    val error = downloadErrors[item.id]

    when {
        isDownloading -> {
            val pctText = progress?.let { "${(it * 100f).toInt()}%" } ?: "…"
            VividPillButton(
                text = if (compact) "⏳ $pctText" else "⏳ Descargando $pctText (Cancelar)",
                active = false,
                accentColor = p.secondary,
                onClick = { playback.cancelDownload(item.id) },
            )
        }
        isDownloaded -> {
            VividPillButton(
                text = if (compact) "✓ Offline" else "✓ Guardada offline",
                active = true,
                accentColor = p.success,
                onClick = { onDownloadedClick?.invoke() },
            )
        }
        error != null -> {
            VividPillButton(
                text = if (compact) "↻ Reintentar ↓" else "↻ Reintentar descarga",
                active = false,
                accentColor = p.error,
                onClick = { playback.download(item) },
            )
        }
        else -> {
            VividPillButton(
                text = if (compact) "↓ Descargar" else "↓ Descargar offline",
                active = false,
                accentColor = p.primary,
                onClick = { playback.download(item) },
            )
        }
    }
}

@Composable
fun OfflineDownloadsPanel(
    playback: Playback,
    onBrowseSearch: () -> Unit = {},
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val downloads by remember(playback) { playback.history.observeDownloads() }.collectAsState(emptyList())
    val activeDownloads by playback.activeDownloads.collectAsState()
    val downloadErrors by playback.downloadErrors.collectAsState()
    val queue by playback.queue.collectAsState()
    val trackById = remember(queue, downloads) {
        (queue + downloads.map { it.item }).associateBy { it.id }
    }

    var filter by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = remember(downloads, filter) {
        val clean = filter.trim()
        if (clean.isEmpty()) downloads
        else downloads.filter {
            it.item.title.contains(clean, ignoreCase = true) ||
                it.item.subtitle.contains(clean, ignoreCase = true)
        }
    }
    val totalBytes = remember(downloads) { downloads.sumOf { it.fileSizeBytes } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.small)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = p.surface,
            border = BorderStroke(1.dp, p.primary.copy(alpha = 0.28f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Canciones descargadas offline",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = p.text,
                        )
                        val folder = playback.downloadFolderPath()
                        if (folder.isNotBlank()) {
                            Text(
                                "Carpeta local: $folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = p.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    SpotiFlacQualityBadge(
                        text = "${downloads.size} pistas · ${formatBytes(totalBytes)}",
                        highlighted = downloads.isNotEmpty(),
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(t.unit),
                    verticalArrangement = Arrangement.spacedBy(t.unit),
                ) {
                    if (filtered.isNotEmpty()) {
                        VividPillButton(
                            text = "▶ Reproducir todo (${filtered.size})",
                            active = true,
                            accentColor = p.primary,
                            onClick = {
                                val items = filtered.map { it.item }
                                playback.playFrom(items.first(), items)
                            },
                        )
                        VividPillButton(
                            text = "⇄ Aleatorio offline",
                            active = false,
                            accentColor = p.secondary,
                            onClick = {
                                val items = filtered.map { it.item }
                                playback.shuffle.value = true
                                playback.playFrom(items.random(), items)
                            },
                        )
                    }
                    if (playback.canOpenDownloadsFolder()) {
                        VividPillButton(
                            text = "📂 Abrir carpeta",
                            active = false,
                            accentColor = p.primary,
                            onClick = { playback.openDownloadsFolder() },
                        )
                    }
                    if (downloads.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("🗑 Vaciar descargas", color = p.error)
                        }
                    }
                }
            }
        }

        if (activeDownloads.isNotEmpty() || downloadErrors.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = p.surface,
                border = BorderStroke(1.dp, p.secondary.copy(alpha = 0.25f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(t.small), verticalArrangement = Arrangement.spacedBy(t.unit)) {
                    Text(
                        "Estado de descargas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = p.primary,
                    )
                    activeDownloads.forEach { (songId, progress) ->
                        val title = trackById[songId]?.title ?: songId
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Descargando: $title",
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { playback.cancelDownload(songId) }) {
                                    Text("Cancelar")
                                }
                            }
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    downloadErrors.forEach { (songId, err) ->
                        val title = trackById[songId]?.title ?: songId
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$title: $err",
                                Modifier.weight(1f),
                                color = p.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { playback.dismissDownloadError(songId) }) {
                                Text("Cerrar")
                            }
                        }
                    }
                }
            }
        }

        if (downloads.isNotEmpty()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(percent = 50),
                label = { Text("Filtrar canciones descargadas…") },
            )
        }

        if (downloads.isEmpty() && activeDownloads.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = p.surface,
                border = BorderStroke(1.dp, p.muted.copy(alpha = 0.2f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(t.medium),
                    verticalArrangement = Arrangement.spacedBy(t.unit),
                ) {
                    Text(
                        "Todavía no tenés canciones descargadas offline.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Buscá cualquier canción y tocá «↓ Descargar» para guardarla localmente en tu dispositivo y reproducirla sin conexión a internet.",
                        color = p.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    VividPillButton(
                        text = "⌕ Ir a Buscar",
                        active = true,
                        accentColor = p.primary,
                        onClick = onBrowseSearch,
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            Text(
                "Ninguna canción descargada coincide con «$filter».",
                color = p.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val playlistItems = filtered.map { it.item }
            filtered.forEach { entry ->
                OfflineTrackRow(
                    entry = entry,
                    onPlay = { playback.playFrom(entry.item, playlistItems) },
                    onEnqueue = { playback.enqueue(entry.item) },
                    onDelete = { playback.removeDownload(entry.item.id) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Vaciar descargas offline?") },
            text = { Text("Se eliminarán los archivos de audio descargados de este dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    playback.clearDownloads()
                }) { Text("Eliminar todo", color = p.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun OfflineTrackRow(
    entry: DownloadedTrack,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    Surface(
        shape = AestheticGemShape,
        color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = 0.25f)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Reproducir offline", onClick = onPlay)
                .padding(t.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.small),
        ) {
            TrackArt(
                item = entry.item,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                localArtPath = entry.artworkLocalPath,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpotiFlacQualityBadge(text = "OFFLINE", highlighted = true)
                    Text(
                        entry.item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    entry.item.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = p.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val fmt = entry.formatLabel ?: "AUDIO"
                    val br = entry.bitrateKbps?.let { " · $it kbps" } ?: ""
                    Text(
                        "$fmt$br · ${formatBytes(entry.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.secondary,
                    )
                    entry.item.durationSeconds?.let { dur ->
                        Text(
                            "· ${dur / 60}:${(dur % 60).toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = p.muted,
                        )
                    }
                }
            }
            VividPillButton(
                text = "▶ Reproducir",
                active = true,
                accentColor = p.primary,
                onClick = onPlay,
            )
            VividPillButton(
                text = "+ Cola",
                accentColor = p.secondary,
                onClick = onEnqueue,
            )
            TextButton(onClick = onDelete) {
                Text("Eliminar", color = p.error)
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        val tenths = ((mb * 10.0).toLong() / 10.0)
        "$tenths MB"
    } else {
        "${kb.toLong().coerceAtLeast(1L)} KB"
    }
}
