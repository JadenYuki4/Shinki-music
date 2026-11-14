// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlin.math.roundToInt

data class Pick(
    val item: MusicItem,
    val reason: String,
    val score: Double = 1.0,
    val badge: String = "✦ Afinidad",
)

object RecommendationWeights {
    const val LAST_TRACK_SIMILARITY = 0.45
    const val HABITS_AFFINITY = 0.30
    const val FAVORITES_AFFINITY = 0.25
    const val REPETITION_PENALTY = 0.60
    const val SKIP_PENALTY = 0.50
    const val AFFINITY_SHARE = 0.80
    const val DISCOVERY_SHARE = 0.20
    const val MAX_CONSECUTIVE_SAME_ARTIST = 2
    const val BATCH_MIN_SIZE = 5
    const val BATCH_DEFAULT_SIZE = 8
    const val BATCH_MAX_SIZE = 10
    const val REFILL_THRESHOLD = 3
}

internal data class CandidateScoreBreakdown(
    val item: MusicItem,
    val lastTrackSimilarity: Double,
    val habitsAffinity: Double,
    val favoritesAffinity: Double,
    val repetitionPenalty: Double,
    val skipPenalty: Double,
    val totalScore: Double,
    val isDiscovery: Boolean,
)

private val ignoredTokens = setOf(
    "official", "video", "audio", "lyrics", "lyric", "music", "mv", "hd", "hq",
    "feat", "ft", "the", "and", "for", "with", "from", "live", "version", "remastered", "topic"
)

private val variantTokens = setOf(
    "official", "video", "lyrics", "lyric", "audio", "live", "remix", "cover",
    "remaster", "remastered", "sped", "up", "slowed", "reverb", "acoustic",
    "acustica", "acustico", "version", "ver", "feat", "ft", "featuring",
    "mv", "hd", "hq", "topic", "visualizer", "edit", "radio", "vivo"
)

private val bracketRegex = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
private val accentMap = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a', 'å' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o', 'ø' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
    'ñ' to 'n', 'ç' to 'c', 'ý' to 'y', 'ÿ' to 'y'
)

internal fun stripAccents(text: String): String = buildString(text.length) {
    for (ch in text.lowercase()) append(accentMap[ch] ?: ch)
}

internal fun normalizeCanonicalArtist(artist: String): String {
    val clean = stripAccents(artist.removeSuffix(" - Topic").removeSuffix(" - topic"))
        .replace(bracketRegex, " ")
        .replace(Regex("\\b(feat|ft|featuring)\\b.*"), " ")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    return clean
}

internal fun normalizeTrackTitle(title: String, artistHint: String = ""): String {
    val ascii = stripAccents(title)
    val withoutBrackets = ascii.replace(bracketRegex, " ")
    // Trim trailing "- Official Video" / "| Lyrics" variant suffixes before token cleanup.
    val splitParts = withoutBrackets.split(Regex("\\s+[-|]\\s+"))
    val basePart = buildString {
        splitParts.forEachIndexed { index, part ->
            val words = part.replace(Regex("[^a-z0-9\\s]"), " ")
                .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val isTrailingVariant = index > 0 && words.isNotEmpty() && words.any { it in variantTokens }
            if (!isTrailingVariant) {
                if (isNotEmpty()) append(' ')
                append(part)
            }
        }
    }
    var tokens = basePart.replace(Regex("[^a-z0-9\\s]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    val normArtist = normalizeCanonicalArtist(artistHint)
    if (normArtist.isNotBlank()) {
        val artistWords = normArtist.split(' ')
        if (tokens.size > artistWords.size && tokens.subList(0, artistWords.size) == artistWords) {
            tokens = tokens.drop(artistWords.size)
        }
    }
    val filtered = tokens.filterNot { it in variantTokens }
    return (if (filtered.isNotEmpty()) filtered else tokens).joinToString(" ")
}

private fun hasVariantMarker(title: String): Boolean {
    val ascii = stripAccents(title)
    if (bracketRegex.containsMatchIn(ascii)) return true
    val words = ascii.replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+"))
    return words.any { it in variantTokens }
}

internal fun extractYouTubeVideoId(idOrUrl: String): String? {
    val watchMatch = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(idOrUrl)
    if (watchMatch != null) return watchMatch.groupValues[1]
    val shortMatch = Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(idOrUrl)
    return shortMatch?.groupValues?.get(1)
}

internal fun canonicalTrackKeys(item: MusicItem): Set<String> {
    val keys = linkedSetOf<String>()
    val cleanId = item.id.trim().lowercase()
    if (cleanId.isNotEmpty()) keys += "id:$cleanId"
    item.isrc?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { keys += "isrc:$it" }
    item.musicBrainzId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { keys += "mbid:$it" }
    extractYouTubeVideoId(item.id)?.let { keys += "yt:$it" }

    val normTitle = normalizeTrackTitle(item.title, item.subtitle)
    val normArtist = normalizeCanonicalArtist(item.subtitle)
    if (normTitle.isNotEmpty()) {
        if (normArtist.isNotEmpty()) keys += "ta:$normTitle|$normArtist"
        else keys += "t:$normTitle"
    }
    return keys
}

internal fun isSameTrackVariant(first: MusicItem, second: MusicItem): Boolean {
    val firstKeys = canonicalTrackKeys(first)
    val secondKeys = canonicalTrackKeys(second)
    if (firstKeys.any { it in secondKeys }) return true

    val titleA = normalizeTrackTitle(first.title, first.subtitle)
    val titleB = normalizeTrackTitle(second.title, second.subtitle)
    if (titleA.isEmpty() || titleA != titleB) return false

    val artistA = normalizeCanonicalArtist(first.subtitle)
    val artistB = normalizeCanonicalArtist(second.subtitle)
    if (artistA.isEmpty() || artistB.isEmpty()) return true
    if (artistA == artistB || artistA.contains(artistB) || artistB.contains(artistA)) return true
    return hasVariantMarker(first.title) || hasVariantMarker(second.title)
}

internal fun extractKeywords(text: String): List<String> =
    stripAccents(text)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 4 && it !in ignoredTokens }
        .distinct()

private fun itemSemanticTokens(item: MusicItem): Set<String> = buildSet {
    addAll(extractKeywords(normalizeTrackTitle(item.title, item.subtitle)))
    item.genres.forEach { addAll(extractKeywords(it)) }
    item.tags.forEach { addAll(extractKeywords(it)) }
}

internal fun scoreAutoQueueCandidate(
    candidate: MusicItem,
    seedTrack: MusicItem?,
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    relatedIds: Set<String> = emptySet(),
    recentTrackKeys: Set<String> = emptySet(),
    rules: List<TasteRule> = emptyList(),
): CandidateScoreBreakdown {
    val candArtist = normalizeCanonicalArtist(candidate.subtitle)
    val candTokens = itemSemanticTokens(candidate)

    // 1. Similitud con el ultimo tema escuchado (0.0 .. 1.0)
    var lastSimilarity = 0.0
    if (seedTrack != null) {
        if (candidate.id in relatedIds) lastSimilarity += 0.55
        val seedArtist = normalizeCanonicalArtist(seedTrack.subtitle)
        if (candArtist.isNotEmpty() && seedArtist.isNotEmpty() &&
            (candArtist == seedArtist || candArtist.contains(seedArtist) || seedArtist.contains(candArtist))
        ) {
            lastSimilarity += 0.35
        }
        val seedTokens = itemSemanticTokens(seedTrack)
        val overlap = candTokens.intersect(seedTokens).size
        if (overlap > 0) lastSimilarity += (overlap * 0.15).coerceAtMost(0.30)
        val genreOverlap = candidate.genres.map { stripAccents(it) }
            .intersect(seedTrack.genres.map { stripAccents(it) }.toSet()).size
        if (genreOverlap > 0) lastSimilarity += 0.25
        val tagOverlap = candidate.tags.map { stripAccents(it) }
            .intersect(seedTrack.tags.map { stripAccents(it) }.toSet()).size
        if (tagOverlap > 0) lastSimilarity += (tagOverlap * 0.10).coerceAtMost(0.20)
    } else if (candidate.id in relatedIds) {
        lastSimilarity = 0.35
    }
    lastSimilarity = lastSimilarity.coerceIn(0.0, 1.0)

    // 2. Afinidad por habitos de escucha (0.0 .. 1.0) y 3. Penalizaciones
    val artistHabitWeights = mutableMapOf<String, Double>()
    val tokenHabitWeights = mutableMapOf<String, Double>()
    val skippedArtists = mutableMapOf<String, Double>()
    val skippedTrackKeys = mutableSetOf<String>()

    plays.take(40).forEachIndexed { index, record ->
        val decay = 1.0 / (1.0 + index * 0.25)
        val artist = normalizeCanonicalArtist(record.item.subtitle)
        val ratio = record.completionRatio.coerceIn(0.0, 1.0)
        val wasComplete = !record.skipped && (ratio >= 0.60 || record.playedSeconds >= 60L)
        if (record.skipped || (record.playedSeconds in 1L..25L && ratio < 0.25)) {
            skippedTrackKeys += canonicalTrackKeys(record.item)
            if (artist.isNotEmpty()) {
                skippedArtists[artist] = (skippedArtists[artist] ?: 0.0) + 0.45 * decay
            }
        } else if (wasComplete || ratio >= 0.25) {
            val weight = (0.4 + ratio * 0.6) * decay
            if (artist.isNotEmpty()) artistHabitWeights[artist] = (artistHabitWeights[artist] ?: 0.0) + weight
            itemSemanticTokens(record.item).forEach { tok ->
                tokenHabitWeights[tok] = (tokenHabitWeights[tok] ?: 0.0) + weight * 0.5
            }
        }
    }

    var habitsAffinity = 0.0
    if (candArtist.isNotEmpty()) habitsAffinity += (artistHabitWeights[candArtist] ?: 0.0) * 0.45
    habitsAffinity += candTokens.sumOf { tokenHabitWeights[it] ?: 0.0 } * 0.20
    habitsAffinity = habitsAffinity.coerceIn(0.0, 1.0)

    // 3. Afinidad por favoritos / Me gusta y reglas explicitas (0.0 .. 1.0)
    val favoriteArtists = mutableMapOf<String, Double>()
    val favoriteTokens = mutableSetOf<String>()
    favorites.take(30).forEachIndexed { index, fav ->
        val decay = 1.0 / (1.0 + index * 0.2)
        val artist = normalizeCanonicalArtist(fav.subtitle)
        if (artist.isNotEmpty()) favoriteArtists[artist] = (favoriteArtists[artist] ?: 0.0) + decay
        favoriteTokens += itemSemanticTokens(fav)
    }
    rules.filter { it.mode == TasteMode.Favorite }.forEach { rule ->
        val cleanRule = normalizeCanonicalArtist(rule.name)
        if (cleanRule.isNotEmpty()) favoriteArtists[cleanRule] = (favoriteArtists[cleanRule] ?: 0.0) + 1.0
    }

    var favoritesAffinity = 0.0
    if (candArtist.isNotEmpty()) favoritesAffinity += (favoriteArtists[candArtist] ?: 0.0) * 0.65
    val favTokenMatches = candTokens.intersect(favoriteTokens).size
    if (favTokenMatches > 0) favoritesAffinity += (favTokenMatches * 0.25).coerceAtMost(0.45)
    favoritesAffinity = favoritesAffinity.coerceIn(0.0, 1.0)

    val candKeys = canonicalTrackKeys(candidate)
    val repetitionPenalty = if (candKeys.any { it in recentTrackKeys }) {
        RecommendationWeights.REPETITION_PENALTY
    } else 0.0

    var skipPenalty = 0.0
    if (candKeys.any { it in skippedTrackKeys }) skipPenalty += RecommendationWeights.SKIP_PENALTY
    if (candArtist.isNotEmpty()) skipPenalty += ((skippedArtists[candArtist] ?: 0.0) * 0.5).coerceAtMost(0.35)
    if (rules.any {
            it.mode == TasteMode.Less &&
                (candArtist.contains(normalizeCanonicalArtist(it.name)) ||
                    stripAccents("${candidate.title} ${candidate.subtitle}").contains(stripAccents(it.name)))
        }
    ) {
        skipPenalty += 0.25
    }
    skipPenalty = skipPenalty.coerceIn(0.0, RecommendationWeights.SKIP_PENALTY)

    val totalScore = (RecommendationWeights.LAST_TRACK_SIMILARITY * lastSimilarity) +
        (RecommendationWeights.HABITS_AFFINITY * habitsAffinity) +
        (RecommendationWeights.FAVORITES_AFFINITY * favoritesAffinity) -
        repetitionPenalty -
        skipPenalty

    val isDiscovery = lastSimilarity < 0.30 && habitsAffinity < 0.20 && favoritesAffinity < 0.20

    return CandidateScoreBreakdown(
        item = candidate,
        lastTrackSimilarity = lastSimilarity,
        habitsAffinity = habitsAffinity,
        favoritesAffinity = favoritesAffinity,
        repetitionPenalty = repetitionPenalty,
        skipPenalty = skipPenalty,
        totalScore = totalScore,
        isDiscovery = isDiscovery,
    )
}

internal fun rankAndSelectAutoQueueBatch(
    candidates: List<MusicItem>,
    seedTrack: MusicItem?,
    queueItems: List<MusicItem>,
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    relatedIds: Set<String> = emptySet(),
    batchSize: Int = RecommendationWeights.BATCH_DEFAULT_SIZE,
    rules: List<TasteRule> = emptyList(),
): List<MusicItem> {
    val clampedBatch = batchSize.coerceIn(RecommendationWeights.BATCH_MIN_SIZE, RecommendationWeights.BATCH_MAX_SIZE)
    val recentPlays = plays.take(30)
    val recentTrackKeys = recentPlays.flatMapTo(mutableSetOf()) { canonicalTrackKeys(it.item) }

    val blockedArtists = rules.filter { it.mode == TasteMode.Block && it.kind == TasteKind.Artist }
        .map { normalizeCanonicalArtist(it.name) }.filter { it.isNotEmpty() }
    val blockedGenres = rules.filter { it.mode == TasteMode.Block && it.kind == TasteKind.Genre }
        .map { stripAccents(it.name) }.filter { it.isNotEmpty() }

    // Excluir tema semilla, cola actual, historial reciente y variantes duplicadas entre si.
    val excludedItems = buildList {
        if (seedTrack != null) add(seedTrack)
        addAll(queueItems)
        recentPlays.forEach { add(it.item) }
    }

    val deduplicated = mutableListOf<MusicItem>()
    for (candidate in candidates) {
        if (candidate.kind != MusicKind.Song) continue
        val candArtist = normalizeCanonicalArtist(candidate.subtitle)
        val candText = stripAccents("${candidate.title} ${candidate.subtitle} ${candidate.genres.joinToString(" ")}")
        if (blockedArtists.any { candArtist.contains(it) }) continue
        if (blockedGenres.any { candText.contains(it) }) continue
        if (excludedItems.any { isSameTrackVariant(it, candidate) }) continue
        if (deduplicated.any { isSameTrackVariant(it, candidate) }) continue
        deduplicated += candidate
    }

    if (deduplicated.isEmpty()) return emptyList()

    val scored = deduplicated
        .map { item ->
            scoreAutoQueueCandidate(
                candidate = item,
                seedTrack = seedTrack,
                plays = plays,
                favorites = favorites,
                relatedIds = relatedIds,
                recentTrackKeys = recentTrackKeys,
                rules = rules,
            )
        }
        .sortedByDescending { it.totalScore }

    // Mezcla ~80% afines y ~20% descubrimiento.
    val affinityPool = scored.filterNot { it.isDiscovery }.toMutableList()
    val discoveryPool = scored.filter { it.isDiscovery }.toMutableList()
    val affinityTarget = (clampedBatch * RecommendationWeights.AFFINITY_SHARE).roundToInt().coerceAtLeast(1)
    val discoveryTarget = (clampedBatch - affinityTarget).coerceAtLeast(1)

    val mixed = buildList {
        var addedAffinity = 0
        var addedDiscovery = 0
        while (size < clampedBatch && (affinityPool.isNotEmpty() || discoveryPool.isNotEmpty())) {
            val needDiscoverySlot = addedDiscovery < discoveryTarget &&
                discoveryPool.isNotEmpty() &&
                (addedAffinity >= 3 || affinityPool.isEmpty())
            if (needDiscoverySlot) {
                add(discoveryPool.removeFirst())
                addedDiscovery++
            } else if (affinityPool.isNotEmpty()) {
                add(affinityPool.removeFirst())
                addedAffinity++
            } else if (discoveryPool.isNotEmpty()) {
                add(discoveryPool.removeFirst())
                addedDiscovery++
            }
        }
    }

    return enforceArtistStreakLimit(mixed.map { it.item }, RecommendationWeights.MAX_CONSECUTIVE_SAME_ARTIST)
        .take(clampedBatch)
}

internal fun enforceArtistStreakLimit(items: List<MusicItem>, maxConsecutive: Int): List<MusicItem> {
    if (maxConsecutive <= 0 || items.size <= maxConsecutive) return items
    val remaining = items.toMutableList()
    val ordered = mutableListOf<MusicItem>()
    while (remaining.isNotEmpty()) {
        val tailArtist = if (ordered.size >= maxConsecutive) {
            val lastArtists = ordered.takeLast(maxConsecutive).map { normalizeCanonicalArtist(it.subtitle) }
            if (lastArtists.first().isNotEmpty() && lastArtists.all { it == lastArtists.first() }) {
                lastArtists.first()
            } else null
        } else null

        val nextIdx = if (tailArtist != null) {
            remaining.indexOfFirst { normalizeCanonicalArtist(it.subtitle) != tailArtist }
        } else 0

        if (nextIdx >= 0) {
            ordered += remaining.removeAt(nextIdx)
        } else {
            ordered += remaining.removeFirst()
        }
    }
    return ordered
}

internal suspend fun autoQueueBatch(
    source: MusicSource,
    seedTrack: MusicItem?,
    queueItems: List<MusicItem>,
    searches: List<SearchHistoryEntry>,
    plays: List<PlayEventRecord>,
    favorites: List<MusicItem>,
    batchSize: Int = RecommendationWeights.BATCH_DEFAULT_SIZE,
    rules: List<TasteRule> = emptyList(),
): SourceResult<List<MusicItem>> {
    val pool = mutableListOf<MusicItem>()
    val relatedIds = mutableSetOf<String>()
    var lastError: SourceError? = null

    if (seedTrack != null) {
        when (val rel = source.related(seedTrack.id)) {
            is SourceResult.Success -> {
                rel.value.forEach {
                    relatedIds += it.id
                    pool += it
                }
            }
            is SourceResult.Failure -> lastError = rel.error
        }
    }

    val searchQueries = linkedSetOf<String>()
    if (seedTrack != null) {
        val cleanArtist = seedTrack.subtitle.removeSuffix(" - Topic").trim()
        if (cleanArtist.isNotBlank()) searchQueries += cleanArtist.lowercase()
        seedTrack.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { searchQueries += it.lowercase() }
        val tokens = extractKeywords(normalizeTrackTitle(seedTrack.title, seedTrack.subtitle)).take(2)
        if (tokens.isNotEmpty() && cleanArtist.isBlank()) searchQueries += tokens.joinToString(" ")
    }

    val hasHistory = plays.isNotEmpty() || favorites.isNotEmpty()
    val skippedPlayIds = plays.filter { it.skipped }.mapTo(mutableSetOf()) { it.item.id }
    if (hasHistory) {
        plays.filter { !it.skipped && it.item.id !in skippedPlayIds }.take(5).forEach { play ->
            val artist = play.item.subtitle.removeSuffix(" - Topic").trim().lowercase()
            if (artist.isNotBlank()) searchQueries += artist
        }
        favorites.take(4).forEach { fav ->
            val artist = fav.subtitle.removeSuffix(" - Topic").trim().lowercase()
            if (artist.isNotBlank()) searchQueries += artist
        }
        searches.take(3).forEach { entry ->
            val q = entry.query.trim().lowercase()
            if (q.isNotBlank()) searchQueries += q
        }
    }

    for (query in searchQueries.take(4)) {
        when (val res = source.search(query, MusicKind.Song, pageSize = 15)) {
            is SourceResult.Success -> pool += res.value.items
            is SourceResult.Failure -> if (lastError == null) lastError = res.error
        }
    }

    // Siempre sumar lote de descubrimiento para sostener la mezcla 80/20 o fallback en frio.
    when (val disc = source.discover(null, 15)) {
        is SourceResult.Success -> pool += disc.value.items
        is SourceResult.Failure -> if (lastError == null) lastError = disc.error
    }

    if (pool.isEmpty() && lastError != null) return SourceResult.Failure(lastError)

    val selected = rankAndSelectAutoQueueBatch(
        candidates = pool,
        seedTrack = seedTrack,
        queueItems = queueItems,
        plays = plays,
        favorites = favorites,
        relatedIds = relatedIds,
        batchSize = batchSize,
        rules = rules,
    )
    return SourceResult.Success(selected)
}

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
    val knownItems = (plays.map { it.item } + favorites).toMutableList()
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
                if (known.add(pick.item.id) && knownItems.none { isSameTrackVariant(it, pick.item) }) {
                    knownItems += pick.item
                    add(pick)
                }
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
            res.value.filterNot { pick ->
                pick.item.id in currentIds || currentItems.any { isSameTrackVariant(it, pick.item) }
            }.ifEmpty { res.value }.take(8)
        )
    }
}
