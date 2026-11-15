package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerFeaturesTest {
    private fun song(id: String) = MusicItem(id, id, "Artist", MusicKind.Song, durationSeconds = 200L)
    private val a = song("A")
    private val b = song("B")
    private val c = song("C")
    private val d = song("D")
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
        assertTrue(newerRelease("v5.3.1", "5.3.0"))
        assertFalse(newerRelease("v5.3.0", "5.3.1"))
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

        player.seekBy(30)
        assertEquals(30L, engine.state.value.position)
        player.seekBy(-10)
        assertEquals(20L, engine.state.value.position)

        player.setAudioQuality(AudioQuality.Low)
        runCurrent()
        assertEquals(AudioQuality.Low, player.audioQuality.value)
        assertEquals(20L, engine.state.value.position) // preserves position on quality switch
        assertEquals(AudioQuality.Low, source.streamRequests.last().second)

        player.setAudioFormat(AudioFormatPreference.M4A)
        runCurrent()
        assertEquals(AudioFormatPreference.M4A, player.audioFormat.value)
        assertEquals(AudioFormatPreference.M4A, source.streamRequests.last().third)

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
        thirdPlayer.restoreSession(assertNotNull(store.load()))
        assertTrue(thirdPlayer.muted.value)
        assertEquals(0f, thirdPlayer.volume.value, 0.01f)
        thirdPlayer.toggleMute()
        assertFalse(thirdPlayer.muted.value)
        assertEquals(0.42f, thirdPlayer.volume.value, 0.01f)
    }

    @Test fun fixedQueueLocalAndStreamingNextAndSeekBehaveDeterministically() = runTest {
        val queue = listOf(a, b, c, d)

        // 1) Local/offline files with async engine state update (simulating MpvEngine / AndroidEngine).
        val pendingLoads = ArrayDeque<Pair<MusicItem, AudioStream>>()
        val asyncLocalEngine = object : AudioEngine {
            override val state = MutableStateFlow(Transport())
            val loads = mutableListOf<String>()
            fun flushLoads() {
                while (pendingLoads.isNotEmpty()) {
                    val (t, _) = pendingLoads.removeFirst()
                    loads += t.id
                    state.value = Transport(t, playing = true, position = 0L, duration = 200L)
                }
            }
            override fun load(track: MusicItem, stream: AudioStream) {
                pendingLoads.addLast(track to stream)
            }
            override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
            override fun seek(seconds: Long) { state.value = state.value.copy(position = seconds) }
            override fun volume(value: Float) {}
            override fun speed(value: Float) {}
        }
        val localPaths = queue.associate { it.id to "/offline/${it.id}.wav" }
        val localDownloader = object : TrackDownloader {
            override val downloadsDirectoryPath: String = "/offline"
            override suspend fun downloadToLocal(item: MusicItem, stream: AudioStream, onProgress: (Float?) -> Unit) =
                DownloadedAudioFile("/offline/${item.id}.wav", stream.mimeType, 1024L)
            override fun exists(localPath: String): Boolean = localPath in localPaths.values
            override fun delete(localPath: String) {}
        }
        val localPlayer = Playback(source, asyncLocalEngine, backgroundScope, downloader = localDownloader)
        localPlayer.shuffle.value = false
        localPlayer.repeat.value = RepeatMode.Off
        localPlayer.autoplay.value = false
        queue.forEach { localPlayer.history.saveDownload(DownloadedTrack(it, localPaths.getValue(it.id), "audio/wav")) }

        localPlayer.playFrom(a, queue)
        runCurrent()
        asyncLocalEngine.flushLoads()
        runCurrent()
        assertEquals("A", asyncLocalEngine.state.value.track?.id)
        assertEquals(0L, asyncLocalEngine.state.value.position)
        assertEquals(listOf("A", "B", "C", "D"), localPlayer.queue.value.map { it.id })

        // Forward 10 seconds from start keeps A and advances position to 10s.
        localPlayer.seekBy(10)
        assertEquals("A", asyncLocalEngine.state.value.track?.id)
        assertEquals(10L, asyncLocalEngine.state.value.position)
        assertEquals(listOf("A", "B", "C", "D"), localPlayer.queue.value.map { it.id })

        // Single next from A -> plays B exactly once and preserves queue order.
        localPlayer.next()
        runCurrent()
        asyncLocalEngine.flushLoads()
        runCurrent()
        assertEquals("B", asyncLocalEngine.state.value.track?.id)
        assertEquals(listOf("A", "B"), asyncLocalEngine.loads)
        assertEquals(listOf("A", "B", "C", "D"), localPlayer.queue.value.map { it.id })

        // Intentional double next from A while B has not yet updated engine.state must reach C.
        localPlayer.playFrom(a, queue)
        runCurrent()
        asyncLocalEngine.flushLoads()
        runCurrent()
        localPlayer.next()
        runCurrent() // B is loaded into asyncLocalEngine queue, engine.state still shows A!
        localPlayer.next()
        runCurrent()
        asyncLocalEngine.flushLoads()
        runCurrent()
        assertEquals("C", asyncLocalEngine.state.value.track?.id, "Intentional double Next with async local engine must reach C")

        // 2) Streaming source with suspended resolution while B is still loading.
        val deferredStreams = mutableMapOf<String, CompletableDeferred<SourceResult<AudioStream>>>()
        val gatedSource = object : MusicSource {
            override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) = SourceResult.Success(MusicPage(queue))
            override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(emptyList()))
            override suspend fun stream(songId: String) =
                deferredStreams.getOrPut(songId) { CompletableDeferred() }.await()
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) = stream(songId)
        }
        val streamEngine = Engine()
        val streamPlayer = Playback(gatedSource, streamEngine, backgroundScope)
        streamPlayer.shuffle.value = false
        streamPlayer.repeat.value = RepeatMode.Off
        streamPlayer.autoplay.value = false

        streamPlayer.playFrom(a, queue)
        runCurrent()
        deferredStreams.getValue("A").complete(SourceResult.Success(AudioStream("https://cdn/A.m4a", "audio/mp4")))
        runCurrent()
        assertEquals("A", streamEngine.state.value.track?.id)
        streamPlayer.seekBy(10)
        assertEquals("A", streamEngine.state.value.track?.id)
        assertEquals(10L, streamEngine.state.value.position)

        // Two intentional Next presses while B is still loading (pending) must cancel B and land on C.
        streamPlayer.next()
        runCurrent()
        assertEquals("B", streamPlayer.pending.value?.id)
        streamPlayer.next()
        runCurrent()
        assertEquals("C", streamPlayer.pending.value?.id, "Second Next while B is resolving must advance pending to C")
        deferredStreams.getValue("C").complete(SourceResult.Success(AudioStream("https://cdn/C.m4a", "audio/mp4")))
        runCurrent()
        assertEquals("C", streamEngine.state.value.track?.id)
        assertEquals(listOf("A", "B", "C", "D"), streamPlayer.queue.value.map { it.id })

        // Single Next on a restored session starting on A advances to B (not re-selecting A).
        val restoredPlayer = Playback(source, Engine(), backgroundScope)
        restoredPlayer.restoreSession(
            SavedPlaybackSession(
                queue = queue,
                currentTrackId = "A",
                positionSeconds = 25L,
                shuffle = false,
                repeat = RepeatMode.Off,
                quality = AudioQuality.High,
                format = AudioFormatPreference.Auto,
                profile = SoundProfile.AnimeOST,
                autoplay = false,
            )
        )
        restoredPlayer.next()
        runCurrent()
        assertEquals("B", restoredPlayer.engine.state.value.track?.id)

        // Failed resolution on B followed by Next advances to C rather than retrying B.
        val failOnceSource = object : MusicSource by source {
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference): SourceResult<AudioStream> =
                if (songId == "B") SourceResult.Failure(SourceError.Network) else super.stream(songId, quality, format)
        }
        val retryAfterFailEngine = Engine()
        val retryAfterFailPlayer = Playback(failOnceSource, retryAfterFailEngine, backgroundScope)
        retryAfterFailPlayer.autoplay.value = false
        retryAfterFailPlayer.playFrom(a, queue)
        runCurrent()
        retryAfterFailPlayer.next()
        runCurrent()
        retryAfterFailPlayer.next()
        runCurrent()
        assertEquals("C", retryAfterFailEngine.state.value.track?.id)
    }

    @Test fun eofCoincidenceWithManualNextDoesNotSkipBAndPreservesModes() = runTest {
        val queue = listOf(a, b, c, d)

        // Case 1: Auto-advance A -> B on EOF coinciding with a manual Next does not skip B; a second manual Next goes to C.
        val engine1 = Engine()
        val p1 = Playback(source, engine1, backgroundScope)
        p1.shuffle.value = false
        p1.repeat.value = RepeatMode.Off
        p1.autoplay.value = false
        p1.playFrom(a, queue)
        runCurrent()

        engine1.state.value = engine1.state.value.copy(playing = false, ended = true)
        runCurrent()
        assertEquals("B", engine1.state.value.track?.id)

        // Single manual Next coinciding right after A ended stays on B.
        p1.next()
        runCurrent()
        assertEquals("B", engine1.state.value.track?.id, "Coincident manual Next at EOF of A must not skip B")

        // Second intentional manual Next advances to C.
        p1.next()
        runCurrent()
        assertEquals("C", engine1.state.value.track?.id)

        // Normal single manual Next once B has played past position > 1s advances to D immediately.
        engine1.state.value = engine1.state.value.copy(playing = false, ended = true)
        runCurrent()
        assertEquals("D", engine1.state.value.track?.id)
        engine1.state.value = engine1.state.value.copy(position = 5L)
        val p2 = Playback(source, Engine().also { }, backgroundScope)
        p2.autoplay.value = false
        p2.playFrom(a, queue)
        runCurrent()
        p2.engine.state.value.let { st ->
            (p2.engine as Engine).state.value = st.copy(playing = false, ended = true)
        }
        runCurrent()
        (p2.engine as Engine).state.value = p2.engine.state.value.copy(position = 4L)
        p2.next()
        runCurrent()
        assertEquals("C", p2.engine.state.value.track?.id)

        // Case 2: Manual Next triggered right before A emits ended = true does not skip B.
        val deferred = mutableMapOf<String, CompletableDeferred<SourceResult<AudioStream>>>()
        val gatedSource = object : MusicSource by source {
            override suspend fun stream(songId: String) = deferred.getOrPut(songId) { CompletableDeferred() }.await()
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) = stream(songId)
        }
        val engine3 = Engine()
        val p3 = Playback(gatedSource, engine3, backgroundScope)
        p3.autoplay.value = false
        p3.playFrom(a, queue)
        runCurrent()
        deferred.getValue("A").complete(SourceResult.Success(AudioStream("A", "audio/test")))
        runCurrent()
        p3.next() // Manual Next starts loading B
        engine3.state.value = engine3.state.value.copy(playing = false, ended = true) // A finishes simultaneously
        runCurrent()
        deferred.getValue("B").complete(SourceResult.Success(AudioStream("B", "audio/test")))
        runCurrent()
        assertEquals("B", engine3.state.value.track?.id, "EOF of A arriving while manual B loads must not skip B")

        // Case 3: Manual playNext preservation and RepeatMode.All / RepeatMode.One / Shuffle behavior.
        val engine4 = Engine()
        val p4 = Playback(source, engine4, backgroundScope)
        p4.autoplay.value = false
        val manual = song("M")
        p4.playFrom(a, listOf(a, b))
        runCurrent()
        p4.playNext(manual)
        assertEquals(listOf("A", "M", "B"), p4.queue.value.map { it.id })
        p4.next()
        runCurrent()
        assertEquals("M", engine4.state.value.track?.id)

        p4.repeat.value = RepeatMode.One
        p4.next() // Manual Next ignores RepeatMode.One and advances M -> B
        runCurrent()
        assertEquals("B", engine4.state.value.track?.id)
        engine4.state.value = engine4.state.value.copy(playing = false, ended = true) // Natural EOF repeats B
        runCurrent()
        assertEquals("B", engine4.state.value.track?.id)

        p4.repeat.value = RepeatMode.All
        p4.next() // Manual Next wraps B -> A
        runCurrent()
        assertEquals("A", engine4.state.value.track?.id)
    }
}
