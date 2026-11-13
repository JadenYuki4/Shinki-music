package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.theme.*

@Composable
fun PlaylistPanel(player: Playback) {
    val collection = player.history.playlists
    val lists by collection.lists.collectAsState()
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var m3uName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<LocalPlaylist?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    Text("Listas locales", style = MaterialTheme.typography.titleLarge)

    feedbackMessage?.let { msg ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = LocalPalette.current.primary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, LocalPalette.current.primary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { feedbackMessage = null }) {
                    Text("OK", color = LocalPalette.current.primary)
                }
            }
        }
    }

    lists.forEach { list ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                enabled = list.songs.isNotEmpty(),
                onClick = { player.playFrom(list.songs.first(), list.songs) },
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text("${list.name} · ${list.songs.size} canciones")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = list.songs.isNotEmpty(),
                    onClick = { player.downloadBatch(list.songs) },
                ) {
                    Text("↓ Descargar")
                }
                TextButton(
                    onClick = {
                        val m3u = exportPlaylistToM3u8(list)
                        clipboard.setText(AnnotatedString(m3u))
                        feedbackMessage = "Lista '${list.name}' exportada a M3U8 y copiada al portapapeles."
                    },
                ) {
                    Text("M3U8")
                }
                TextButton(onClick = { deleting = list }) { Text("Eliminar") }
            }
        }
    }

    val queue by player.queue.collectAsState()
    val actions = listOf(
        "save" to "Guardar cola",
        "import" to "Importar JSON",
        "export" to "Exportar JSON",
        "import_m3u8" to "Importar M3U8",
    )
    FlowRow {
        for (action in actions) {
            TextButton(
                enabled = action.first != "save" || queue.isNotEmpty(),
                onClick = {
                    input = if (action.first == "export") collection.export() else ""
                    m3uName = ""
                    error = ""
                    dialog = action.first
                },
            ) {
                Text(action.second)
            }
        }
    }

    if (dialog.isNotEmpty()) {
        val dialogTitle = when (dialog) {
            "save" -> "Guardar cola"
            "import" -> "Importar JSON"
            "export" -> "Exportar JSON"
            "import_m3u8" -> "Importar M3U8"
            else -> ""
        }
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(dialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dialog == "import") Text("Importar reemplaza las listas guardadas en este dispositivo.")
                    if (dialog == "import_m3u8") {
                        Text("Pegá el texto o enlaces de tu lista M3U8:")
                        OutlinedTextField(
                            value = m3uName,
                            onValueChange = { m3uName = it.take(64) },
                            label = { Text("Nombre de la lista (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(500001) },
                        readOnly = dialog == "export",
                        label = {
                            Text(
                                when (dialog) {
                                    "save" -> "Nombre de la lista"
                                    "import_m3u8" -> "Contenido M3U8 (#EXTM3U...)"
                                    else -> "JSON de listas"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                    )
                    if (error.isNotBlank()) Text(error, color = LocalPalette.current.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            when (dialog) {
                                "save" -> collection.saveQueue(input, queue)
                                "import" -> collection.import(input)
                                "import_m3u8" -> {
                                    val pl = importPlaylistFromM3u8(m3uName, input)
                                    collection.saveQueue(pl.name, pl.songs)
                                    feedbackMessage = "Lista '${pl.name}' importada (${pl.songs.size} canciones)."
                                }
                            }
                            dialog = ""
                        } catch (e: Exception) {
                            error = when (dialog) {
                                "save" -> e.message ?: "No se pudo guardar la lista."
                                "import_m3u8" -> e.message ?: "No se pudo importar la lista M3U8."
                                else -> "Documento de listas inválido o demasiado grande."
                            }
                        }
                    }
                ) {
                    Text(if (dialog == "export") "Listo" else "Guardar")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cerrar") } },
        )
    }

    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar ${list.name}?") },
            confirmButton = { TextButton(onClick = { collection.delete(list); deleting = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}
