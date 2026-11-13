package io.github.jadenyuki4.orbitmusic

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TasteMode(val label: String) {
    Favorite("Favorito"), Less("Recomendar menos"), Block("Bloquear")
}
internal enum class TasteKind(val label: String) { Artist("Artista o grupo"), Genre("Género") }
internal data class TasteRule(val name: String, val kind: TasteKind, val mode: TasteMode)

internal class TasteStore(private val settings: Settings) {
    private val key = "recommendation.rules.v1"
    private val state = MutableStateFlow(settings.getString(key, "").lineSequence().mapNotNull {
        val parts = it.split('\t')
        if (parts.size != 3) null else runCatching {
            TasteRule(parts[2], TasteKind.valueOf(parts[0]), TasteMode.valueOf(parts[1]))
        }.getOrNull()
    }.toList())
    val rules = state.asStateFlow()

    fun set(name: String, kind: TasteKind, mode: TasteMode) {
        val clean = name.replace(Regex("\\s+"), " ").trim().removeSuffix(" - Topic").trim()
        if (clean.length !in 1..80 || (kind == TasteKind.Genre && mode == TasteMode.Favorite)) return
        val updated = state.value.filterNot { it.kind == kind && it.name.equals(clean, true) }
        save(updated + TasteRule(clean, kind, mode))
    }
    fun remove(rule: TasteRule) = save(state.value - rule)
    private fun save(list: List<TasteRule>) {
        settings.putString(key, list.joinToString("\n") { "${it.kind}\t${it.mode}\t${it.name}" })
        state.value = list
    }
}

internal fun applyTaste(picks: List<Pick>, rules: List<TasteRule>, limit: Int): List<Pick> {
    if (rules.isEmpty()) return picks.take(limit)
    val patterns = rules.map { rule ->
        rule to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(rule.name) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }
    val preferred = mutableListOf<Pick>()
    val normal = mutableListOf<Pick>()
    val less = mutableListOf<Pick>()
    for (pick in picks) {
        val modes = patterns.filter { (rule, pattern) ->
            val text = if (rule.kind == TasteKind.Artist) pick.item.subtitle else pick.item.title + " " + pick.reason
            pattern.containsMatchIn(text)
        }.map { it.first.mode }
        when {
            TasteMode.Block in modes -> continue
            TasteMode.Less in modes -> less.add(pick)
            TasteMode.Favorite in modes -> preferred.add(pick.copy(badge = "♥ Artista favorito"))
            else -> normal.add(pick)
        }
    }
    return (preferred + normal + less.take(1)).take(limit)
}
