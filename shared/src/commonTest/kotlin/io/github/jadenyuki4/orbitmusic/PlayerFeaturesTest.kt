package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerFeaturesTest {
    private fun song(id: String) = MusicItem(id, id, "Artist", MusicKind.Song)
    private val a = song("A")
    private val b = song("B")
    private val c = song("C")
    private val source = object : MusicSource {
        val streamRequests = mutableListOf<Triple<String, AudioQuality, AudioFormatPreference>>()
        override suspend fun stream(songId: String) = SourceResult.Success(AudioStream(songId, "audio/test"))
        override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference): SourceResult<AudioStream> {
            streamRequests += Triple(songId, quality, format)
            return SourceResult.Success(
                AudioStream(
                    url = "$songId?q=${quality.name}&fmt=${format.name}",
                    mimeType = format.mimeKeyword?.let { "audio/$it" } ?: "audio/webm",
                    bitrateKbps = quality.targetBitrateKbps,
                    formatLabel = format.shortLabel,
                )
            )
        }
        override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
        override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) =
            SourceResult.Success(MusicPage(listOf(a, song(query + "1"), song(query + "2"))))
        override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(listOf(c)))
    }

    private class Engine : AudioEngine {
        override val state = MutableStateFlow(Transport())
        val loads = mutableListOf<String>()
        var lastVolume = 1f
        var lastProfile = SoundProfile.AnimeOST
        override fun load(track: MusicItem, stream: AudioStream) {
            loads += track.id
            state.value = Transport(track, playing = true, duration = 200)
        }
        override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
        override fun seek(seconds: Long) { state.value = state.value.copy(position = seconds) }
        override fun volume(value: Float) { lastVolume = value }
        override fun speed(value: Float) {}
        override fun soundProfile(profile: SoundProfile) { lastProfile = profile }
    }

    @Test fun tasteRulesPersistReplaceAndUndo() {
        val settings = com.russhwolf.settings.MapSettings()
        val store = TasteStore(settings)
        store.set("Artist - Topic", TasteKind.Artist, TasteMode.Favorite)
        store.set("ARTIST", TasteKind.Artist, TasteMode.Block)
        val restored = TasteStore(settings)
        assertEquals(1, restored.rules.value.size)
        assertTrue(applyTaste(listOf(Pick(a, "rock")), restored.rules.value, 8).isEmpty())
        restored.remove(restored.rules.value.single())
        assertEquals(listOf(a), applyTaste(listOf(Pick(a, "rock")), TasteStore(settings).rules.value, 8).map { it.item })
    }

    @Test fun fewerGenreMatchesAndBlockedArtistAcrossRecommendationPaths() = runTest {
        val less = TasteRule("rock", TasteKind.Genre, TasteMode.Less)
        val preferred = TasteRule("Queen", TasteKind.Artist, TasteMode.Favorite)
        val picks = listOf(Pick(a, "rock"), Pick(b, "rock"), Pick(c.copy(subtitle = "Queen"), "pop"))
        assertEquals(listOf("C", "A"), applyTaste(picks, listOf(less, preferred), 8).map { it.item.id })
        val blocked = listOf(TasteRule("artist", TasteKind.Artist, TasteMode.Block))
        val cold = recommendations(source, emptyList(), emptyList(), emptyList(), rules = blocked)
        val context = searchContextualRecommendations(source, "rock", emptyList(), emptyList(), emptyList(), emptyList(), blocked)
        for (result in listOf(cold, context)) assertTrue(assertIs<SourceResult.Success<List<Pick>>>(result).value.isEmpty())
    }

    @Test fun stableReleaseTagsCompareNumerically() {
        assertTrue(newerRelease("v5.10.0", "5.2.8"))
        assertFalse(newerRelease("v5.2.8", "5.2.8"))
        assertFalse(newerRelease("v4.12.9", "5.2.8"))
        assertFails { newerRelease("v5.2.8/other", "5.2.8") }
    }

    @Test fun recommendationsMixSeedsAndExcludeKnownTracks() = runTest {
        val recent = SearchHistoryEntry("rock", MusicKind.Song, 1)
        val result = recommendations(source, listOf(recent), emptyList(), listOf(a))
        val picks = assertIs<SourceResult.Success<List<Pick>>>(result).value
        assertEquals(listOf("rock1", "artist1", "rock2", "artist2"), picks.map { it.item.id })
        val cold = recommendations(source, emptyList(), emptyList(), emptyList())
        assertEquals(c, assertIs<SourceResult.Success<List<Pick>>>(cold).value.single().item)
    }

    @Test fun contextualSearchRecommendationsUseActiveQueryAndExcludeDisplayedItems() = runTest {
        val displayed = listOf(song("anime opening1"))
        val res = searchContextualRecommendations(
            source = source,
            query = "anime opening",
            currentItems = displayed,
            searches = emptyList(),
            plays = emptyList(),
            favorites = emptyList(),
        )
        val picks = assertIs<SourceResult.Success<List<Pick>>>(res).value
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.none { it.item.id == "anime opening1" })
        assertTrue(picks.any { it.item.id == "anime opening2" })
    }

    @Test fun volumeButtonsMuteQualityFormatAndSoundProfileControlsWork() = runTest {
        val engine = Engine()
        val player = Playback(source, engine, backgroundScope)
        player.playFrom(a, listOf(a, b))
        runCurrent()

        // Volume down / up / mute
        assertEquals(1f, player.volume.value, 0.01f)
        player.volumeDown(0.2f)
        assertEquals(0.8f, player.volume.value, 0.01f)
        assertEquals(0.8f, engine.lastVolume, 0.01f)
        player.volumeUp(0.1f)
        assertEquals(0.9f, player.volume.value, 0.01f)
        player.toggleMute()
        assertTrue(player.muted.value)
        assertEquals(0f, player.volume.value, 0.01f)
        player.toggleMute()
        assertFalse(player.muted.value)
        assertEquals(0.9f, player.volume.value, 0.01f)

        // Seek relative (-10s / +10s)
        player.seekBy(30)
        assertEquals(30L, engine.state.value.position)
        player.seekBy(-10)
        assertEquals(20L, engine.state.value.position)

        // Switch Audio Quality to Low (lighter file size) and Format to M4A while playing
        player.setAudioQuality(AudioQuality.Low)
        runCurrent()
        assertEquals(AudioQuality.Low, player.audioQuality.value)
        assertEquals(20L, engine.state.value.position) // preserves position on quality switch
        assertEquals(AudioQuality.Low, source.streamRequests.last().second)

        player.setAudioFormat(AudioFormatPreference.M4A)
        runCurrent()
        assertEquals(AudioFormatPreference.M4A, player.audioFormat.value)
        assertEquals(AudioFormatPreference.M4A, source.streamRequests.last().third)

        // Sound profile DSP switch
        player.setSoundProfile(SoundProfile.BassBoost)
        assertEquals(SoundProfile.BassBoost, engine.lastProfile)
    }

    @Test fun pauseDoesNotAdvanceButEndFollowsEditedQueueAndRepeat() = runTest {
        val engine = Engine()
        val player = Playback(source, engine, backgroundScope)
        player.playFrom(a, listOf(a,b,c))
        runCurrent()
        engine.toggle()
        runCurrent()
        assertEquals(listOf("A"), engine.loads)
        player.move(c, -1)
        engine.state.value = engine.state.value.copy(ended = true)
        runCurrent()
        assertEquals(listOf("A","C"), engine.loads)
        player.repeat.value = RepeatMode.One
        engine.state.value = engine.state.value.copy(playing = false, ended = true)
        runCurrent()
        assertEquals(listOf("A","C","C"), engine.loads)
        player.remove(b)
        assertEquals(listOf(a,c), player.queue.value)
    }

    @Test fun autoplayUsesDiscoveryOnlyAfterNaturalEndAndRespectsIncognito() = runTest {
        val engine = Engine()
        val player = Playback(source, engine, backgroundScope)
        player.playFrom(a, listOf(a))
        player.autoplay.value = true
        runCurrent()
        engine.state.value = engine.state.value.copy(playing = false, ended = true)
        runCurrent()
        assertEquals("artist1", engine.state.value.track?.id)
        player.history.setIncognito(true)
        player.playFrom(b, listOf(b))
        runCurrent()
        engine.state.value = engine.state.value.copy(playing = false, ended = true)
        runCurrent()
        assertEquals("B", engine.state.value.track?.id)
    }

    @Test fun shuffleVisitsEverySongAndTimerCanBeReplaced() = runTest {
        val engine = Engine()
        val player = Playback(source, engine, backgroundScope)
        player.playFrom(a, listOf(a,b,c))
        runCurrent()
        player.shuffle.value = true
        repeat(3) { player.next(); runCurrent() }
        assertEquals(setOf("A","B","C"), engine.loads.toSet())
        assertEquals(3, engine.loads.size)
        player.sleep(1)
        player.sleep(2)
        advanceTimeBy(60_001)
        assertTrue(engine.state.value.playing)
        advanceTimeBy(60_000)
        assertFalse(engine.state.value.playing)
    }

    @Test fun downloadSavesLocallyAndSelectPlaysOfflineWithoutStreaming() = runTest {
        val engine = Engine()
        val storedPaths = mutableSetOf<String>()
        val deletedPaths = mutableListOf<String>()
        val fakeDownloader = object : TrackDownloader {
            override val downloadsDirectoryPath: String = "/offline"
            override suspend fun downloadToLocal(
                item: MusicItem,
                stream: AudioStream,
                onProgress: (Float?) -> Unit,
            ): DownloadedAudioFile {
                onProgress(0.5f)
                val path = "/offline/${item.id}.m4a"
                storedPaths.add(path)
                onProgress(1f)
                return DownloadedAudioFile(path, stream.mimeType, 4096L)
            }

            override fun exists(localPath: String): Boolean = localPath in storedPaths
            override fun delete(localPath: String) {
                storedPaths.remove(localPath)
                deletedPaths.add(localPath)
            }
        }
        val testSource = object : MusicSource {
            var streamCalls = 0
            override suspend fun stream(songId: String) = SourceResult.Success(AudioStream("https://cdn/$songId.m4a", "audio/mp4"))
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference): SourceResult<AudioStream> {
                streamCalls++
                return SourceResult.Success(
                    AudioStream(
                        url = "https://cdn/$songId.m4a",
                        mimeType = "audio/mp4",
                        bitrateKbps = 256,
                        formatLabel = "M4A",
                    )
                )
            }
            override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) = SourceResult.Success(MusicPage(emptyList()))
            override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(emptyList()))
        }
        val player = Playback(testSource, engine, backgroundScope, downloader = fakeDownloader)

        player.download(a)
        runCurrent()

        assertEquals(1, testSource.streamCalls)
        val saved = player.history.getDownload(a.id)
        assertNotNull(saved)
        assertEquals("/offline/A.m4a", saved.localPath)
        assertTrue(player.activeDownloads.value.isEmpty())
        assertTrue(player.downloadErrors.value.isEmpty())

        player.select(a)
        runCurrent()

        assertEquals(1, testSource.streamCalls)
        assertEquals(listOf("A"), engine.loads)
        assertEquals("/offline/A.m4a", player.currentStream.value?.url)
        assertTrue(player.currentStream.value?.formatLabel?.contains("OFFLINE") == true)

        player.removeDownload(a.id)
        runCurrent()

        assertNull(player.history.getDownload(a.id))
        assertEquals(listOf("/offline/A.m4a"), deletedPaths)
    }

    @Test fun seekOnRestoredSessionAndRetryPreserveTargetPosition() = runTest {
        val engine = object : AudioEngine {
            override val state = MutableStateFlow(Transport())
            val loadStarts = mutableListOf<Long>()
            val seekTargets = mutableListOf<Long>()
            override fun load(track: MusicItem, stream: AudioStream) = loadAt(track, stream, 0)
            override fun loadAt(track: MusicItem, stream: AudioStream, seconds: Long) {
                loadStarts += seconds
                state.value = Transport(track, playing = true, position = seconds, duration = 200)
            }
            override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
            override fun seek(seconds: Long) { seekTargets += seconds }
            override fun volume(value: Float) {}
            override fun speed(value: Float) {}
        }
        val player = Playback(source, engine, backgroundScope)
        val restoredTrack = a.copy(durationSeconds = 200)
        player.restoreSession(
            SavedPlaybackSession(
                queue = listOf(restoredTrack),
                currentTrackId = restoredTrack.id,
                positionSeconds = 45,
                shuffle = false,
                repeat = RepeatMode.Off,
                quality = AudioQuality.High,
                format = AudioFormatPreference.Auto,
                profile = SoundProfile.AnimeOST,
            )
        )

        player.seekTo(90)
        runCurrent()

        assertEquals(listOf(90L), engine.loadStarts)
        assertEquals(90L, engine.state.value.position)

        // Rapid seekBy calls accumulate before async engine state updates position.
        player.seekBy(10)
        player.seekBy(10)
        player.seekBy(10)
        assertEquals(listOf(100L, 110L, 120L), engine.seekTargets)

        engine.state.value = engine.state.value.copy(position = 77)
        player.retry()
        runCurrent()
        assertEquals(listOf(90L, 77L), engine.loadStarts)
    }

    @Test fun normalizeTrackTitleAndDeduplicateVariants() {
        val seed = MusicItem("yt:seed1", "Bohemian Rhapsody", "Queen", MusicKind.Song)
        val live = MusicItem("yt:var1", "Bohemian Rhapsody (Live at Wembley)", "Queen - Topic", MusicKind.Song)
        val officialVideo = MusicItem("yt:var2", "Bohemian Rhapsody - Official Video", "Queen", MusicKind.Song)
        val remix = MusicItem("yt:var3", "Bohemian Rhapsody [Remix 2024]", "Queen", MusicKind.Song)
        val accented = MusicItem("other:1", "Café & Tea (Sped Up)", "Yôasobi feat. Guest", MusicKind.Song)
        val accentedPlain = MusicItem("other:2", "Cafe & Tea [Acoustic]", "Yoasobi", MusicKind.Song)
        val sameIsrcA = MusicItem("srcA:1", "Night Dancer", "imase", MusicKind.Song, isrc = "JPB602200123")
        val sameIsrcB = MusicItem("srcB:9", "Night Dancer Nightcore", "imase", MusicKind.Song, isrc = "JPB602200123")

        assertEquals("bohemian rhapsody", normalizeTrackTitle(live.title, live.subtitle))
        assertEquals("bohemian rhapsody", normalizeTrackTitle(officialVideo.title, officialVideo.subtitle))
        assertEquals("bohemian rhapsody", normalizeTrackTitle(remix.title, remix.subtitle))
        assertTrue(isSameTrackVariant(seed, live))
        assertTrue(isSameTrackVariant(seed, officialVideo))
        assertTrue(isSameTrackVariant(seed, remix))
        assertTrue(isSameTrackVariant(accented, accentedPlain))
        assertTrue(isSameTrackVariant(sameIsrcA, sameIsrcB))

        val ranked = rankAndSelectAutoQueueBatch(
            candidates = listOf(live, officialVideo, remix, accented, accentedPlain, sameIsrcA, sameIsrcB),
            seedTrack = seed,
            queueItems = listOf(seed),
            plays = emptyList(),
            favorites = emptyList(),
            batchSize = 6,
        )
        assertTrue(ranked.none { isSameTrackVariant(seed, it) })
        assertEquals(1, ranked.count { it.isrc == "JPB602200123" })
        assertEquals(1, ranked.count { it.id == accented.id || it.id == accentedPlain.id })
    }

    @Test fun autoQueueScoringOrdersByWeightsLimitsStreakAndPreservesManualQueue() = runTest {
        val seed = MusicItem("seed", "Tracing Stars", "YOASOBI", MusicKind.Song, genres = listOf("jpop"))
        val candFavAndHabit = MusicItem("fav1", "Into The Night", "YOASOBI", MusicKind.Song, genres = listOf("jpop"))
        val candSameArtist2 = MusicItem("same2", "Idol", "YOASOBI", MusicKind.Song, genres = listOf("jpop"))
        val candSameArtist3 = MusicItem("same3", "Monster", "YOASOBI", MusicKind.Song, genres = listOf("jpop"))
        val candRelated = MusicItem("rel1", "Kaikai Kitan", "Eve", MusicKind.Song, genres = listOf("jpop"))
        val candSkippedArtist = MusicItem("skipArt", "Noise Track", "SkippedBand", MusicKind.Song, genres = listOf("jpop"))
        val candDiscovery = MusicItem("disc1", "Midnight Walk", "Indie Duo", MusicKind.Song, genres = listOf("indie"))

        val plays = listOf(
            PlayEventRecord(1, MusicItem("p1", "Brave", "YOASOBI", MusicKind.Song), 200, 0.95, false, 1000L),
            PlayEventRecord(2, MusicItem("p2", "Skipped Song", "SkippedBand", MusicKind.Song), 10, 0.05, true, 900L),
        )
        val favorites = listOf(MusicItem("f1", "Gunjou", "YOASOBI", MusicKind.Song))
        val candidates = listOf(candSkippedArtist, candDiscovery, candRelated, candFavAndHabit, candSameArtist2, candSameArtist3)

        val ordered = rankAndSelectAutoQueueBatch(
            candidates = candidates,
            seedTrack = seed,
            queueItems = listOf(seed),
            plays = plays,
            favorites = favorites,
            relatedIds = setOf(candRelated.id),
            batchSize = 6,
        )
        assertTrue(ordered.indexOf(candFavAndHabit) < ordered.indexOf(candSkippedArtist))
        for (i in 0..(ordered.size - 3)) {
            val streak = listOf(ordered[i], ordered[i + 1], ordered[i + 2]).map { normalizeCanonicalArtist(it.subtitle) }
            assertFalse(streak.all { it == streak.first() && it.isNotEmpty() }, "Found >2 consecutive same artist: $streak")
        }

        val coldStart = rankAndSelectAutoQueueBatch(
            candidates = listOf(candDiscovery, candRelated, candFavAndHabit),
            seedTrack = seed,
            queueItems = listOf(seed),
            plays = emptyList(),
            favorites = emptyList(),
            relatedIds = setOf(candRelated.id),
            batchSize = 5,
        )
        assertTrue(coldStart.isNotEmpty())
        assertEquals(listOf(candRelated.id, candFavAndHabit.id, candDiscovery.id), coldStart.map { it.id })

        val autoSource = object : MusicSource {
            private val catalog = listOf(
                candFavAndHabit,
                candRelated,
                candDiscovery,
                MusicItem("extra1", "Blue Horizon", "Artist One", MusicKind.Song, genres = listOf("jpop")),
                MusicItem("extra2", "Neon Rain", "Artist Two", MusicKind.Song, genres = listOf("jpop")),
                MusicItem("extra3", "Paper Plane", "Artist Three", MusicKind.Song, genres = listOf("jpop")),
                MusicItem("extra4", "Silver Moon", "Artist Four", MusicKind.Song, genres = listOf("jpop")),
            )
            override suspend fun stream(songId: String) = SourceResult.Success(AudioStream("https://cdn/$songId.m4a", "audio/mp4"))
            override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) =
                SourceResult.Success(MusicPage(catalog))
            override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(catalog))
            override suspend fun related(songId: String) = SourceResult.Success(listOf(candRelated, candFavAndHabit))
        }
        val engine = Engine()
        val player = Playback(autoSource, engine, backgroundScope)
        val variantOfSeed = MusicItem("seed-live", "Tracing Stars (Live)", "YOASOBI", MusicKind.Song)
        player.playFromSearch(seed)
        runCurrent()
        assertFalse(player.queue.value.any { it.id == variantOfSeed.id })
        assertTrue(player.queue.value.size in 6..11)

        val manual = MusicItem("manual-1", "User Pick", "Custom Artist", MusicKind.Song)
        player.playNext(manual)
        assertEquals(manual.id, player.queue.value[1].id)
        player.next()
        runCurrent()
        assertEquals(manual.id, engine.state.value.track?.id)
    }

    @Test fun volumeAndMutePersistWithDebounceAndRestoreBeforeFirstLoad() = runTest {
        val settings = com.russhwolf.settings.MapSettings()
        val store = SessionStore(settings)
        val volumeEvents = mutableListOf<String>()
        fun recordingEngine() = object : AudioEngine {
            override val state = MutableStateFlow(Transport())
            override fun load(track: MusicItem, stream: AudioStream) {
                volumeEvents += "load:${track.id}"
                state.value = Transport(track, playing = true, duration = 200)
            }
            override fun toggle() {}
            override fun seek(seconds: Long) {}
            override fun volume(value: Float) { volumeEvents += "vol:$value" }
            override fun speed(value: Float) {}
        }

        val firstPlayer = Playback(source, recordingEngine(), backgroundScope)
        firstPlayer.persistSession(backgroundScope, store)
        runCurrent()
        firstPlayer.setVolume(0.42f)
        advanceTimeBy(600)
        runCurrent()

        val restoredPlayer = Playback(source, recordingEngine(), backgroundScope)
        val saved = store.load()
        assertNotNull(saved)
        assertEquals(0.42f, saved.volume, 0.01f)
        assertFalse(saved.muted)
        volumeEvents.clear()
        restoredPlayer.restoreSession(saved)
        restoredPlayer.playFrom(a, listOf(a))
        runCurrent()
        val firstVolIdx = volumeEvents.indexOfFirst { it.startsWith("vol:0.42") }
        val firstLoadIdx = volumeEvents.indexOfFirst { it == "load:A" }
        assertTrue(firstVolIdx >= 0 && firstVolIdx < firstLoadIdx, "Expected restored volume before load: $volumeEvents")

        restoredPlayer.toggleMute()
        val mutedSnapshot = restoredPlayer.snapshotSession()
        assertTrue(mutedSnapshot.muted)
        assertEquals(0.42f, mutedSnapshot.volume, 0.01f)
        store.save(mutedSnapshot)

        val thirdPlayer = Playback(source, recordingEngine(), backgroundScope)
        thirdPlayer.restoreSession(store.load()!!)
        assertTrue(thirdPlayer.muted.value)
        assertEquals(0f, thirdPlayer.volume.value, 0.01f)
        thirdPlayer.toggleMute()
        assertFalse(thirdPlayer.muted.value)
        assertEquals(0.42f, thirdPlayer.volume.value, 0.01f)
    }
}
