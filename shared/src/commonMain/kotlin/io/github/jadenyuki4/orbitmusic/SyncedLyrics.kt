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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.search.userMessage
import io.github.jadenyuki4.orbitmusic.theme.*

/** Una línea de letra con su marca de tiempo en milisegundos. */
data class LyricLine(val timeMillis: Long, val text: String) {
    val timeSeconds: Long get() = timeMillis / 1000L
}

/** Resultado de interpretar una letra, sincronizada (LRC) o en texto plano. */
data class ParsedLyrics(val syncedLines: List<LyricLine>, val plainText: String) {
    val isSynced: Boolean get() = syncedLines.isNotEmpty()
}

private val LRC_TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
private val LRC_META_LINE = Regex("""^\[[A-Za-z#][^\]]*:[^\]]*\]$""")

/** Interpreta una letra en formato LRC (`[mm:ss.xx] texto`); si no tiene marcas de tiempo devuelve el texto plano. */
fun parseLrcLyrics(raw: String): ParsedLyrics {
    val synced = ArrayList<LyricLine>()
    val plain = ArrayList<String>()
    for (rawLine in raw.lineSequence()) {
        var rest = rawLine.trim()
        if (rest.isEmpty() || LRC_META_LINE.matches(rest)) continue
        val times = ArrayList<Long>()
        while (true) {
            val m = LRC_TIME_TAG.find(rest)?.takeIf { it.range.first == 0 } ?: break
            val minutes = m.groupValues[1].toLong()
            val seconds = m.groupValues[2].toLong()
            val fraction = m.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100L
                2 -> fraction.toLong() * 10L
                else -> fraction.toLong()
            }
            times += minutes * 60_000L + seconds * 1_000L + millis
            rest = rest.substring(m.value.length).trimStart()
        }
        if (times.isEmpty()) continue
        for (t in times) synced += LyricLine(t, rest)
        if (rest.isNotEmpty()) plain += rest
    }
    if (synced.isEmpty()) return ParsedLyrics(emptyList(), raw.trim())
    return ParsedLyrics(synced.sortedBy { it.timeMillis }, plain.joinToString("\n"))
}

/** Índice de la línea activa para la posición dada, o -1 si todavía no empezó ninguna. */
fun activeLyricIndex(lines: List<LyricLine>, positionSeconds: Long): Int =
    lines.indexOfLast { it.timeSeconds <= positionSeconds }

private fun formatLyricTime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun SyncedLyricsView(player: Playback, item: MusicItem) {
    val p = LocalPalette.current
    var attempt by remember(item.id) { mutableIntStateOf(1) }
    var result by remember(item.id, attempt) { mutableStateOf<SourceResult<String?>?>(null) }
    LaunchedEffect(item.id, attempt) { result = player.source.lyrics(item) }
    val state by player.engine.state.collectAsState()
    var showFull by remember(item.id) { mutableStateOf(false) }

    when (val res = result) {
        null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is SourceResult.Failure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(res.error.userMessage())
            TextButton(onClick = { attempt++ }) { Text("↻ Reintentar") }
        }
        is SourceResult.Success -> {
            val raw = res.value
            if (raw.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No hay letra disponible para esta canción.")
                    TextButton(onClick = { attempt++ }) { Text("↻ Buscar de nuevo") }
                }
            } else {
                val parsed = remember(raw) { parseLrcLyrics(raw) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parsed.isSynced) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SpotiFlacQualityBadge("✦ LETRA SINCRONIZADA", highlighted = true)
                            Spacer(Modifier.weight(1f))
                            VividPillButton(
                                text = if (showFull) "Vista sincronizada" else "Texto completo",
                                onClick = { showFull = !showFull },
                            )
                        }
                    }
                    if (parsed.isSynced && !showFull) {
                        val active = activeLyricIndex(parsed.syncedLines, state.position)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            parsed.syncedLines.forEachIndexed { idx, line ->
                                val isActive = idx == active
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { player.engine.seek(line.timeSeconds) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isActive) p.elevated else Color.Transparent,
                                    border = if (isActive) BorderStroke(1.dp, p.primary) else null,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            formatLyricTime(line.timeSeconds),
                                            color = if (isActive) p.primary else p.muted,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(
                                            line.text.ifEmpty { "♪" },
                                            color = if (isActive) p.primary else p.text,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(parsed.plainText, color = p.text)
                        TextButton(onClick = { attempt++ }) { Text("↻ Recargar letra") }
                    }
                }
            }
        }
    }
}
