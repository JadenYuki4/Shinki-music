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
}
