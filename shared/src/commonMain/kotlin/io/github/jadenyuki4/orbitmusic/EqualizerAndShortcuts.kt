// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.ShinkiAppInfo
import io.github.jadenyuki4.orbitmusic.theme.LocalLayout
import io.github.jadenyuki4.orbitmusic.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Construye la cadena de filtro de audio `lavfi` de 10 bandas para `mpv` a partir de las ganancias en dB.
 * Devuelve `""` si el ecualizador está desactivado o todas las bandas están prácticamente en 0 dB.
 */
fun buildMpvLavfiEqualizerFilter(bandsDb: List<Float>, enabled: Boolean = true): String {
    if (!enabled || bandsDb.isEmpty()) return ""
    val frequencies = ShinkiAppInfo.EQ_FREQUENCIES_HZ
    val clampedGains = frequencies.indices.map { index ->
        bandsDb.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
    }
    if (clampedGains.all { abs(it) < 0.05f }) return ""

    val parts = frequencies.indices.map { index ->
        val freq = frequencies[index]
        val gain = formatGainOneDecimal(clampedGains[index])
        "equalizer=f=${freq}:t=q:w=1.2:g=${gain}"
    }
    return "lavfi=[${parts.joinToString(",")}]"
}

private fun formatGainOneDecimal(value: Float): String {
    val clamped = value.coerceIn(-12f, 12f)
    val tenths = (clamped * 10f).roundToInt()
    if (tenths == 0) return "0.0"
    val sign = if (tenths < 0) "-" else ""
    val absTenths = abs(tenths)
    return "$sign${absTenths / 10}.${absTenths % 10}"
}

private fun formatSignedDbText(value: Float): String {
    val clamped = value.coerceIn(-12f, 12f)
    val tenths = (clamped * 10f).roundToInt()
    if (tenths == 0) return "0.0 dB"
    val sign = if (tenths > 0) "+" else "-"
    val absTenths = abs(tenths)
    return "$sign${absTenths / 10}.${absTenths % 10} dB"
}

/**
 * Controles deslizantes interactivos para ajustar manualmente cada una de las 10 bandas del ecualizador.
 */
@Composable
fun InteractiveEqualizerSliders(
    bandsDb: List<Float>,
    onBandChange: (Int, Float) -> Unit,
    onReset: () -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    val labels = ShinkiAppInfo.EQ_FREQUENCY_LABELS

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Ajuste manual por banda",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                )
                Text(
                    text = "Deslizá cada frecuencia entre -12.0 dB y +12.0 dB",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            VividPillButton(
                text = "Restablecer 0 dB",
                active = false,
                accentColor = p.secondary,
                onClick = onReset,
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = p.background.copy(alpha = 0.55f),
            border = BorderStroke(t.hairline, p.muted.copy(alpha = 0.18f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                labels.forEachIndexed { index, freqLabel ->
                    val currentDb = bandsDb.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = freqLabel,
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = p.text,
                        )
                        Slider(
                            value = currentDb,
                            onValueChange = { newValue ->
                                val rounded = ((newValue.coerceIn(-12f, 12f) * 10f).roundToInt() / 10f)
                                onBandChange(index, rounded)
                            },
                            valueRange = -12f..12f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatSignedDbText(currentDb),
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (abs(currentDb) >= 0.05f) p.primary else p.muted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Manejador global de atajos de teclado para escritorio.
 */
fun handleGlobalKeyboardShortcut(
    event: KeyEvent,
    playback: Playback?,
    isTheater: Boolean,
    onCloseTheater: () -> Unit,
    onOpenSearch: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.Escape && isTheater -> {
            onCloseTheater()
            true
        }
        event.isCtrlPressed && event.key == Key.K -> {
            onOpenSearch()
            true
        }
        event.isCtrlPressed && event.key == Key.Spacebar -> {
            playback?.playOrToggle()
            true
        }
        event.isCtrlPressed && event.key == Key.DirectionRight -> {
            playback?.seekBy(10)
            true
        }
        event.isCtrlPressed && event.key == Key.DirectionLeft -> {
            playback?.seekBy(-10)
            true
        }
        event.isCtrlPressed && event.key == Key.DirectionUp -> {
            playback?.volumeUp(0.05f)
            true
        }
        event.isCtrlPressed && event.key == Key.DirectionDown -> {
            playback?.volumeDown(0.05f)
            true
        }
        event.isCtrlPressed && event.key == Key.M -> {
            playback?.toggleMute()
            true
        }
        else -> false
    }
}

private data class KeyboardShortcutEntry(
    val keysLabel: String,
    val actionLabel: String,
)

private val desktopShortcutsList = listOf(
    KeyboardShortcutEntry("Ctrl + K", "Buscar"),
    KeyboardShortcutEntry("Ctrl + Espacio", "Reproducir/Pausar"),
    KeyboardShortcutEntry("Ctrl + ← / →", "Retroceder/Adelantar 10 s"),
    KeyboardShortcutEntry("Ctrl + ↑ / ↓", "Subir/Bajar volumen"),
    KeyboardShortcutEntry("Ctrl + M", "Silenciar"),
    KeyboardShortcutEntry("Esc", "Cerrar reproductor expandido"),
)

/**
 * Panel para la pantalla de Ajustes con gestión de almacenamiento/caché offline y guía de atajos de teclado.
 */
@Composable
fun CacheAndShortcutsPanel(playback: Playback?) {
    val p = LocalPalette.current
    val t = LocalLayout.current
    var confirmClearOffline by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.small),
    ) {
        if (playback != null) {
            val downloads by remember(playback) { playback.history.observeDownloads() }
                .collectAsState(emptyList())
            val totalBytes = remember(downloads) { downloads.sumOf { it.fileSizeBytes } }
            val folderPath = remember(playback) { playback.downloadFolderPath() }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = p.surface,
                border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(t.small),
                    verticalArrangement = Arrangement.spacedBy(t.unit),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Almacenamiento y caché offline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = p.primary,
                            )
                            Text(
                                text = "${downloads.size} canciones offline · ${formatBytes(totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = p.text,
                            )
                        }
                        SpotiFlacQualityBadge(
                            text = formatBytes(totalBytes),
                            highlighted = downloads.isNotEmpty(),
                        )
                    }

                    if (folderPath.isNotBlank()) {
                        Text(
                            text = "Ubicación: $folderPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(t.unit),
                        verticalArrangement = Arrangement.spacedBy(t.unit),
                    ) {
                        if (playback.canOpenDownloadsFolder()) {
                            VividPillButton(
                                text = "Abrir carpeta",
                                active = false,
                                accentColor = p.primary,
                                onClick = { playback.openDownloadsFolder() },
                            )
                        }
                        VividPillButton(
                            text = "Limpiar descargas offline",
                            active = false,
                            accentColor = p.error,
                            onClick = { confirmClearOffline = true },
                        )
                    }
                }
            }

            if (confirmClearOffline) {
                AlertDialog(
                    onDismissRequest = { confirmClearOffline = false },
                    title = { Text("¿Limpiar descargas offline?") },
                    text = {
                        Text("Se eliminarán todas las canciones guardadas en el almacenamiento local (${downloads.size} pistas, ${formatBytes(totalBytes)}).")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmClearOffline = false
                                playback.clearDownloads()
                            }
                        ) {
                            Text("Limpiar descargas offline", color = p.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearOffline = false }) {
                            Text("Cancelar")
                        }
                    },
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = p.surface,
            border = BorderStroke(t.hairline, p.primary.copy(alpha = t.outlineAlpha)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(t.small),
                verticalArrangement = Arrangement.spacedBy(t.unit),
            ) {
                Text(
                    text = "Atajos de teclado (Escritorio)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.primary,
                )
                Text(
                    text = "Combinaciones rápidas disponibles en cualquier pantalla de la aplicación:",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
                desktopShortcutsList.forEach { shortcut ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = shortcut.actionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.text,
                        )
                        SpotiFlacQualityBadge(
                            text = shortcut.keysLabel,
                            highlighted = false,
                        )
                    }
                }
            }
        }
    }
}
