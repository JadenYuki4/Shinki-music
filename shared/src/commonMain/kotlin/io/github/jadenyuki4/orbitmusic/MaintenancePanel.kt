package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import io.github.jadenyuki4.orbitmusic.domain.ShinkiAppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal expect suspend fun latestRelease(): String?
internal expect fun platformDiagnostic(): String
internal const val RELEASES = "https://github.com/JadenYuki4/Shinki-music/releases"

internal fun versionParts(tag: String): List<Int> {
    val parts = tag.removePrefix("v").split(".")
    require(parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) })
    return parts.map(String::toInt)
}

internal fun newerRelease(tag: String, installed: String): Boolean {
    val available = versionParts(tag)
    val local = versionParts(installed)
    val first = local.indices.firstOrNull { available[it] != local[it] } ?: return false
    return available[first] > local[first]
}

@Composable
internal fun MaintenancePanel() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var status by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Versión y diagnóstico", style = MaterialTheme.typography.titleLarge)
    FlowRow {
        Button(enabled = !busy, onClick = {
            busy = true
            release = null
            status = "Consultando GitHub…"
            scope.launch {
                try {
                    val tag = latestRelease()
                    status = when {
                        tag == null -> "Todavía no hay una versión pública disponible."
                        newerRelease(tag, ShinkiAppInfo.VERSION) -> {
                            release = tag
                            "Actualización disponible · $tag"
                        }
                        else -> "Tenés la versión actual."
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "No se pudo consultar GitHub. Volvé a intentarlo." }
                finally { busy = false }
            }
        }) { Text("Buscar actualizaciones") }
        release?.let { tag -> TextButton(onClick = {
            runCatching { uri.openUri("$RELEASES/tag/$tag") }.onFailure { status = "Abrí $RELEASES desde tu navegador." }
        }) { Text("Ver descarga") } }
        TextButton(onClick = {
            val info = "${ShinkiAppInfo.APP_NAME} de ${ShinkiAppInfo.AUTHOR} · ${ShinkiAppInfo.EDITION} ${ShinkiAppInfo.VERSION} (${ShinkiAppInfo.BUILD_NUMBER})\n" + platformDiagnostic()
            runCatching { clipboard.setText(AnnotatedString(info)) }
                .onSuccess { status = "Diagnóstico copiado." }.onFailure { status = "Portapapeles no disponible." }
        }) { Text("Copiar diagnóstico") }
    }
    if (status.isNotEmpty()) Text(status)
}
@Composable
internal fun TastePanel(player: Playback?) {
    if (player == null) return
    val rules by player.taste.rules.collectAsState()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TasteKind.Artist) }
    val current by player.engine.state.collectAsState()
    Text("Preferencias de recomendaciones", style = MaterialTheme.typography.titleLarge)
    Text("Tus Me gusta de canciones se guardan en Favoritos. Podés cambiar estas reglas en cualquier momento.")
    FlowRow { TasteKind.entries.forEach { value ->
        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(value.label) })
    } }
    if (kind == TasteKind.Genre) Text("Se aplica cuando el género figura en el título o la búsqueda que generó la recomendación.")
    OutlinedTextField(name, { if (it.length <= 80) name = it }, singleLine = true, label = { Text(kind.label) }, modifier = Modifier.fillMaxWidth())
    current.track?.subtitle?.takeIf { it.isNotBlank() }?.let { artist ->
        TextButton(onClick = { kind = TasteKind.Artist; name = artist.take(80) }) { Text("Usar artista actual · $artist") }
    }
    FlowRow { TasteMode.entries.filter { kind == TasteKind.Artist || it != TasteMode.Favorite }.forEach { mode ->
        TextButton(enabled = name.isNotBlank(), onClick = { player.taste.set(name, kind, mode); name = "" }) { Text(mode.label) }
    } }
    for (rule in rules) {
        Row(Modifier.fillMaxWidth()) {
            Text("${rule.name} · ${rule.kind.label} · ${rule.mode.label}", Modifier.weight(1f))
            TextButton(onClick = { player.taste.remove(rule) }) { Text("Quitar") }
        }
    }
}
