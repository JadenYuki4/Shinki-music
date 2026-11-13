// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*

data class Pick(
    val item: MusicItem,
    val reason: String,
    val score: Double = 1.0,
    val badge: String = "✦ Afinidad",
)

private val ignoredTokens = setOf(
    "official", "video", "audio", "lyrics", "lyric", "music", "mv", "hd", "hq",
    "feat", "ft", "the", "and", "for", "with", "from", "live", "version", "remastered", "topic"
)

internal fun extractKeywords(text: String): List<String> =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 4 && it !in ignoredTokens }
        .distinct()

/**
 * Multi-signal recommendation engine combining:
 * 1. Search intent (recent queries + recurring keywords across searches + optional active search query)
 * 2. Listening behavior (completion-weighted plays + skip penalties)
 * 3. Explicit user curation (favorites artist & track themes)
 * 4. Candidate affinity scoring & round-robin seed diversity
 */
internal suspend fun recommendations(
    source: MusicSource,
    searches: List<SearchHistoryEntry>,
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    activeQuery: String? = null,
    limit: Int = 12,
    rules: List<TasteRule> = emptyList(),
): SourceResult<List<Pick>> {
    val weights = linkedMapOf<String, Double>()
    val artistAffinity = mutableMapOf<String, Double>()
    val skippedArtists = mutableSetOf<String>()

    fun addSeed(term: String, weight: Double) {
        val key = term.removeSuffix(" - Topic").trim().lowercase()
        if (key.isNotBlank()) {
            weights[key] = (weights.getOrElse(key) { 0.0 } + weight).coerceAtLeast(0.05)
        }
    }

    rules.filter { it.mode == TasteMode.Favorite }.forEach { rule ->
        addSeed(rule.name, 6.0)
        artistAffinity[rule.name.lowercase()] = 5.0
    }

    // 0. Active live search intent (highest priority when provided)
    activeQuery?.trim()?.takeIf { it.isNotBlank() }?.let { live ->
        addSeed(live, 8.0)
        extractKeywords(live).take(2).forEach { token ->
            if (!token.equals(live, ignoreCase = true)) addSeed(token, 2.5)
        }
    }

    // 1. Recent search queries with recency decay + cross-query keyword reinforcement
    val tokenCounts = mutableMapOf<String, Int>()
    searches.take(10).forEachIndexed { i, entry ->
        val decay = 1.0 / (1 + i)
        addSeed(entry.query, 5.0 * decay)
        val tokens = extractKeywords(entry.query)
        if (tokens.size > 1) {
            tokens.forEach { token ->
                val count = (tokenCounts[token] ?: 0) + 1
                tokenCounts[token] = count
                if (count >= 2) addSeed(token, 1.8 * decay)
            }
        }
    }

    // 2. Listening history: reward high completion ratios, penalize repeated early skips
    plays.take(20).forEachIndexed { i, record ->
        val decay = 1.0 / (1 + i)
        val artist = record.item.subtitle.removeSuffix(" - Topic").trim().lowercase()
        if (!record.skipped) {
            val completionBoost = 1.0 + (record.completionRatio.coerceIn(0.0, 1.0) * 0.75)
            addSeed(record.item.subtitle, 2.0 * completionBoost * decay)
            if (artist.isNotBlank()) {
                artistAffinity[artist] = (artistAffinity[artist] ?: 0.0) + 1.5 * completionBoost * decay
            }
        } else if (record.completionRatio < 0.25 && artist.isNotBlank()) {
            skippedArtists += artist
            artistAffinity[artist] = (artistAffinity[artist] ?: 0.0) - 0.8 * decay
        }
    }

    // 3. Favorites: strong positive signal for favorite artists and multi-word song themes
    favorites.take(10).forEachIndexed { i, item ->
        val decay = 1.0 / (1 + i)
        addSeed(item.subtitle, 3.0 * decay)
        val artist = item.subtitle.removeSuffix(" - Topic").trim().lowercase()
        if (artist.isNotBlank()) {
            artistAffinity[artist] = (artistAffinity[artist] ?: 0.0) + 2.5 * decay
        }
        val titleTokens = extractKeywords(item.title)
        if (titleTokens.size >= 2 && !item.title.equals(item.id, ignoreCase = true)) {
            addSeed("${item.subtitle} ${titleTokens.take(2).joinToString(" ")}", 0.9 * decay)
        }
    }

    if (weights.isEmpty()) {
        return when (val trending = source.discover(null, limit)) {
            is SourceResult.Failure -> trending
            is SourceResult.Success -> SourceResult.Success(
                applyTaste(trending.value.items.map { Pick(it, "Tendencias musicales", score = 1.0, badge = "🔥 Tendencia") }, rules, limit)
            )
        }
    }

    val known = (plays.map { it.item.id } + favorites.map { it.id }).toMutableSet()
    val batches = mutableListOf<List<Pick>>()
    var error: SourceError? = null

    val topSeeds = weights.keys.sortedByDescending { weights[it] ?: 0.0 }.take(4)
    for (term in topSeeds) {
        val seedWeight = weights[term] ?: 1.0
        when (val result = source.search(term, MusicKind.Song, pageSize = limit)) {
            is SourceResult.Failure -> error = result.error
            is SourceResult.Success -> {
                val scored = result.value.items
                    .filter { it.kind == MusicKind.Song }
                    .mapIndexed { index, candidate ->
                        val candArtist = candidate.subtitle.removeSuffix(" - Topic").trim().lowercase()
                        val affinity = artistAffinity[candArtist] ?: 0.0
                        val durationBonus = candidate.durationSeconds?.let { d ->
                            if (d in 60L..540L) 0.4 else if (d < 30L) -0.5 else 0.0
                        } ?: 0.0
                        val skipPenalty = if (candArtist in skippedArtists && affinity < 0) -0.6 else 0.0
                        val totalScore = seedWeight + affinity + durationBonus + skipPenalty - (index * 0.05)
                        val badge = when {
                            activeQuery != null && term.equals(activeQuery.trim(), ignoreCase = true) -> "⌕ Coincidencia"
                            affinity >= 2.0 -> "♥ Artista fav."
                            seedWeight >= 4.0 -> "✦ Búsqueda top"
                            else -> "♪ Recomendado"
                        }
                        Triple(index, totalScore, Pick(candidate, term, totalScore, badge))
                    }
                    .sortedWith(
                        compareByDescending<Triple<Int, Double, Pick>> {
                            // Keep natural upstream rank unless candidate has strong positive/negative artist affinity
                            val candArtist = it.third.item.subtitle.removeSuffix(" - Topic").trim().lowercase()
                            (artistAffinity[candArtist] ?: 0.0).toInt()
                        }.thenBy { it.first }
                    )
                    .map { it.third }
                batches += scored
            }
        }
    }

    if (batches.isEmpty() && error != null) return SourceResult.Failure(error)

    val candidates = buildList {
        for (i in 0 until limit) {
            for (batch in batches) {
                val pick = batch.getOrNull(i) ?: continue
                if (known.add(pick.item.id)) add(pick)

            }
        }
    }
    return SourceResult.Success(applyTaste(candidates, rules, limit))
}

/**
 * Generates contextual song recommendations directly from an active search query and its current results.
 */
internal suspend fun searchContextualRecommendations(
    source: MusicSource,
    query: String,
    currentItems: List<MusicItem>,
    searches: List<SearchHistoryEntry>,
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    rules: List<TasteRule> = emptyList(),
): SourceResult<List<Pick>> {
    val clean = query.trim()
    if (clean.isBlank()) return recommendations(source, searches, plays, favorites, limit = 8, rules = rules)
    val syntheticPlays = currentItems.take(3).mapIndexed { idx, item ->
        PlayEventRecord(
            id = -idx.toLong(),
            item = item,
            playedSeconds = 180,
            completionRatio = 0.9,
            skipped = false,
            playedAtEpochMillis = 0L,
        )
    }
    val currentIds = currentItems.mapTo(mutableSetOf()) { it.id }
    return when (val res = recommendations(
        source = source,
        searches = listOf(SearchHistoryEntry(clean, MusicKind.Song, Long.MAX_VALUE)) + searches,
        plays = syntheticPlays + plays,
        favorites = favorites,
        activeQuery = clean,
        limit = 10, rules = rules,
    )) {
        is SourceResult.Failure -> res
        is SourceResult.Success -> SourceResult.Success(
            res.value.filterNot { it.item.id in currentIds }.ifEmpty { res.value }.take(8)
        )
    }
}
