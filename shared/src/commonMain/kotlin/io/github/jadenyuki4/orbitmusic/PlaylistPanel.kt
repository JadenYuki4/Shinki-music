package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.jadenyuki4.orbitmusic.theme.*

@Composable
fun PlaylistPanel(player: Playback) {
    val collection = player.history.playlists
    val lists by collection.lists.collectAsState()
    var dialog by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<LocalPlaylist?>(null) }
    Text("Listas locales", style = MaterialTheme.typography.titleLarge)
    lists.forEach { list ->
        Row {
            TextButton(enabled = list.songs.isNotEmpty(), onClick = { player.playFrom(list.songs.first(), list.songs) }) {
                Text("${list.name} · ${list.songs.size} canciones")
            }
            TextButton(onClick = { deleting = list }) { Text("Eliminar") }
        }
    }
    val queue by player.queue.collectAsState()
    FlowRow {
        for (action in listOf("save" to "Guardar cola", "import" to "Importar JSON", "export" to "Exportar JSON")) TextButton(
            enabled = action.first != "save" || queue.isNotEmpty(), onClick = {
                input = if (action.first == "export") collection.export() else ""
                error = ""
                dialog = action.first
            }) { Text(action.second) }
    }
    if (dialog.isNotEmpty()) {
        val dialogTitle = when (dialog) {
            "save" -> "Guardar cola"
            "import" -> "Importar JSON"
            else -> "Exportar JSON"
        }
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(dialogTitle) },
            text = { Column {
                if (dialog == "import") Text("Importar reemplaza las listas guardadas en este dispositivo.")
                OutlinedTextField(input, { input = it.take(500001) }, readOnly = dialog == "export",
                    label = { Text(if (dialog == "save") "Nombre de la lista" else "JSON de listas") }, maxLines = 8)
                if (error.isNotBlank()) Text(error, color = LocalPalette.current.error)
            } }, confirmButton = { TextButton(onClick = {
                try {
                    when (dialog) {
                        "save" -> collection.saveQueue(input, queue)
                        "import" -> collection.import(input)
                    }
                    dialog = ""
                } catch (e: Exception) {
                    error = if (dialog == "save") e.message ?: "No se pudo guardar la lista." else "Documento de listas inválido o demasiado grande."
                }
            }) { Text(if (dialog == "export") "Listo" else "Guardar") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cerrar") } })
    }
    deleting?.let { list ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("¿Eliminar ${list.name}?") },
            confirmButton = { TextButton(onClick = { collection.delete(list); deleting = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } })
    }
}
