// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.theme.*

/**
 * Validador de URLs musicales compatibles con la aplicación (puro Kotlin, multiplatform).
 * Dominios admitidos: YouTube, YouTube Music, youtu.be, SoundCloud y Bandcamp.
 */
internal fun isValidSupportedMusicUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return false
    if (trimmed.contains(' ')) return false

    val withoutScheme = trimmed.substring(8)
    if (withoutScheme.isEmpty()) return false

    val hostEnd = withoutScheme.indexOfAny(charArrayOf('/', '?', '#', ':'))
    val host = (if (hostEnd == -1) withoutScheme else withoutScheme.substring(0, hostEnd)).lowercase().trim()
    if (host.isEmpty()) return false

    return isValidSupportedMusicHost(host)
}

internal fun isValidSupportedMusicHost(host: String): Boolean {
    return when (host) {
        "youtube.com",
        "www.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "soundcloud.com",
        "www.soundcloud.com",
        "bandcamp.com" -> true
        else -> host.endsWith(".bandcamp.com") && host.length > 13
    }
}

/**
 * Exporta una lista local al formato estándar M3U8 con metadatos extendidos.
 */
fun exportPlaylistToM3u8(playlist: LocalPlaylist): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#PLAYLIST:${playlist.name}")
    for (song in playlist.songs) {
        val duration = song.durationSeconds ?: -1L
        appendLine("#EXTINF:$duration,${song.subtitle} - ${song.title}")
        appendLine(song.id)
    }
}

/**
 * Parsea e importa una lista desde texto con formato M3U / M3U8.
 * Deduplica enlaces por ID, limita a un máximo de 100 canciones y valida dominios soportados.
 */
fun importPlaylistFromM3u8(defaultName: String, content: String): LocalPlaylist {
    var playlistTag: String? = null
    var pendingDuration: Long? = null
    var pendingTitle: String? = null
    var pendingSubtitle: String? = null

    val songs = mutableListOf<MusicItem>()
    val seenIds = mutableSetOf<String>()

    for (rawLine in content.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#")) {
            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                val tagValue = line.substring(10).trim()
                if (tagValue.isNotBlank()) {
                    playlistTag = tagValue
                }
            } else if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val raw = line.substring(8).trim()
                val commaIdx = raw.indexOf(',')
                val durationStr = if (commaIdx >= 0) raw.substring(0, commaIdx).trim() else raw
                val infoStr = if (commaIdx >= 0) raw.substring(commaIdx + 1).trim() else ""

                pendingDuration = durationStr.substringBefore(' ')
                    .toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
                    ?: durationStr.substringBefore(' ').toLongOrNull()?.takeIf { it > 0 }

                if (infoStr.contains(" - ")) {
                    pendingSubtitle = infoStr.substringBefore(" - ").trim().ifBlank { "Artista desconocido" }
                    pendingTitle = infoStr.substringAfter(" - ").trim().ifBlank { null }
                } else if (infoStr.isNotBlank()) {
                    pendingSubtitle = "Artista desconocido"
                    pendingTitle = infoStr
                } else {
                    pendingSubtitle = "Artista desconocido"
                    pendingTitle = null
                }
            }
        } else {
            val url = line
            if (isValidSupportedMusicUrl(url)) {
                if (seenIds.add(url)) {
                    val trackIndex = songs.size + 1
                    val title = pendingTitle ?: "Pista $trackIndex"
                    val subtitle = pendingSubtitle ?: "Artista desconocido"
                    songs.add(
                        MusicItem(
                            id = url,
                            title = title,
                            subtitle = subtitle,
                            kind = MusicKind.Song,
                            durationSeconds = pendingDuration,
                        )
                    )
                    if (songs.size >= 100) {
                        break
                    }
                }
            }
            // Se consumen o descartan los metadatos pendientes de la pista
            pendingDuration = null
            pendingTitle = null
            pendingSubtitle = null
        }
    }

    require(songs.isNotEmpty()) { "No se encontraron enlaces compatibles en el archivo M3U8." }

    val rawName = when {
        defaultName.isNotBlank() -> defaultName.trim()
        !playlistTag.isNullOrBlank() -> playlistTag.trim()
        else -> "Lista M3U8"
    }
    val resolvedName = rawName.take(64).trim().ifBlank { "Lista M3U8" }

    return LocalPlaylist(name = resolvedName, songs = songs)
}

/**
 * Panel de configuración y herramientas para respaldo JSON e importación/exportación M3U8.
 */
@Composable
fun BackupAndM3uPanel(
    player: Playback?,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val clipboard = LocalClipboardManager.current

    if (player == null) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = p.surface,
            border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(
                text = "El reproductor aún no está inicializado.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.muted,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    val playlists by player.history.playlists.lists.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var showJsonRestoreDialog by remember { mutableStateOf(false) }
    var jsonInputText by remember { mutableStateOf("") }
    var jsonDialogError by remember { mutableStateOf("") }

    var showM3uImportDialog by remember { mutableStateOf(false) }
    var m3uNameInput by remember { mutableStateOf("") }
    var m3uContentInput by remember { mutableStateOf("") }
    var m3uDialogError by remember { mutableStateOf("") }

    var showExportM3uPicker by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = p.surface,
        border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(t.small),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "RESPALDO E INTERCAMBIO",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Respaldo e Intercambio de Listas (.json / .m3u8)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = p.text,
                )
                Text(
                    text = "Colección local: ${playlists.size} de 32 listas guardadas (hasta 100 pistas c/u). Podés respaldar todo en JSON o compartir listas estándar M3U8.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }

            statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = p.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, p.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = p.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { statusMessage = null }) {
                            Text("Aceptar", style = MaterialTheme.typography.labelSmall, color = p.primary)
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VividPillButton(
                    text = "Copiar respaldo JSON",
                    onClick = {
                        try {
                            val json = player.history.playlists.export()
                            clipboard.setText(AnnotatedString(json))
                            statusMessage = "Respaldo completo copiado al portapapeles (${playlists.size} listas)."
                        } catch (e: Exception) {
                            statusMessage = "Error al exportar respaldo JSON: ${e.message}"
                        }
                    },
                )
                VividPillButton(
                    text = "Restaurar desde JSON",
                    onClick = {
                        jsonInputText = ""
                        jsonDialogError = ""
                        showJsonRestoreDialog = true
                    },
                )
                VividPillButton(
                    text = "Importar lista M3U8",
                    onClick = {
                        m3uNameInput = ""
                        m3uContentInput = ""
                        m3uDialogError = ""
                        showM3uImportDialog = true
                    },
                )
                if (playlists.isNotEmpty()) {
                    VividPillButton(
                        text = "Exportar lista a M3U8",
                        onClick = {
                            showExportM3uPicker = true
                        },
                    )
                }
            }

            if (playlists.isNotEmpty()) {
                HorizontalDivider(
                    color = p.muted.copy(alpha = 0.15f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    text = "Exportar lista individual a M3U8:",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.text,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playlists.forEach { list ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = p.elevated,
                            border = BorderStroke(1.dp, p.muted.copy(alpha = 0.2f)),
                            modifier = Modifier.clickable {
                                val m3u = exportPlaylistToM3u8(list)
                                clipboard.setText(AnnotatedString(m3u))
                                statusMessage = "Lista '${list.name}' exportada en formato M3U8 y copiada al portapapeles."
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "📄 ${list.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = p.text,
                                )
                                Text(
                                    text = "(${list.songs.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = p.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Cuadro de diálogo para restaurar desde JSON
    if (showJsonRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showJsonRestoreDialog = false },
            title = { Text("Restaurar desde JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pegá el código JSON exportado. Atención: esta acción reemplazará todas las listas locales actuales por las del archivo de respaldo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = jsonInputText,
                        onValueChange = { jsonInputText = it.take(500001) },
                        label = { Text("JSON de listas") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                        maxLines = 10,
                    )
                    if (jsonDialogError.isNotBlank()) {
                        Text(
                            text = jsonDialogError,
                            color = p.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = jsonInputText.trim()
                        if (trimmed.isEmpty()) {
                            jsonDialogError = "Por favor pegá el texto JSON del respaldo."
                            return@TextButton
                        }
                        try {
                            player.history.playlists.import(trimmed)
                            statusMessage = "Colección de listas restaurada exitosamente desde JSON."
                            showJsonRestoreDialog = false
                        } catch (e: Exception) {
                            jsonDialogError = e.message ?: "Documento de listas inválido o demasiado grande."
                        }
                    }
                ) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJsonRestoreDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Cuadro de diálogo para importar lista M3U8
    if (showM3uImportDialog) {
        AlertDialog(
            onDismissRequest = { showM3uImportDialog = false },
            title = { Text("Importar lista M3U8") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pegá el texto o enlaces de tu archivo .m3u o .m3u8. Compatible con canciones de YouTube, SoundCloud y Bandcamp.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = m3uNameInput,
                        onValueChange = { m3uNameInput = it.take(64) },
                        label = { Text("Nombre de la lista (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = m3uContentInput,
                        onValueChange = { m3uContentInput = it.take(100000) },
                        label = { Text("Contenido M3U8 (#EXTM3U...)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                        maxLines = 10,
                    )
                    if (m3uDialogError.isNotBlank()) {
                        Text(
                            text = m3uDialogError,
                            color = p.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (m3uContentInput.isBlank()) {
                            m3uDialogError = "Por favor pegá el contenido del archivo M3U8."
                            return@TextButton
                        }
                        try {
                            val imported = importPlaylistFromM3u8(defaultName = m3uNameInput, content = m3uContentInput)
                            player.history.playlists.saveQueue(imported.name, imported.songs)
                            statusMessage = "Lista '${imported.name}' importada correctamente (${imported.songs.size} canciones)."
                            showM3uImportDialog = false
                        } catch (e: Exception) {
                            m3uDialogError = e.message ?: "No se pudo importar la lista M3U8."
                        }
                    }
                ) {
                    Text("Guardar lista")
                }
            },
            dismissButton = {
                TextButton(onClick = { showM3uImportDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Cuadro de diálogo para seleccionar qué lista exportar a M3U8
    if (showExportM3uPicker) {
        AlertDialog(
            onDismissRequest = { showExportM3uPicker = false },
            title = { Text("Exportar lista a M3U8") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Seleccioná la lista que deseás exportar al formato .m3u8 para copiarla a tu portapapeles:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    playlists.forEach { list ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = p.elevated,
                            border = BorderStroke(1.dp, p.muted.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth().clickable {
                                val m3u = exportPlaylistToM3u8(list)
                                clipboard.setText(AnnotatedString(m3u))
                                statusMessage = "Lista '${list.name}' copiada al portapapeles como M3U8."
                                showExportM3uPicker = false
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = list.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = p.text,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${list.songs.size} canciones",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = p.muted,
                                    )
                                }
                                Text(
                                    text = "Copiar M3U8",
                                    color = p.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportM3uPicker = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}
