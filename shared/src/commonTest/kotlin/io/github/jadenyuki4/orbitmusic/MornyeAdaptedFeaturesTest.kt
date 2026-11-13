// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import com.russhwolf.settings.MapSettings
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MornyeAdaptedFeaturesTest {

    private fun testSong(id: String, title: String = id, artist: String = "Test Artist", duration: Long? = 200L) =
        MusicItem(
            id = id,
            title = title,
            subtitle = artist,
            kind = MusicKind.Song,
            durationSeconds = duration,
        )

    private val testSource = object : MusicSource {
        override suspend fun stream(songId: String) = SourceResult.Success(AudioStream(songId, "audio/test"))
        override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) =
            SourceResult.Success(AudioStream(songId, "audio/test"))
        override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
        override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) =
            SourceResult.Success(MusicPage(emptyList()))
        override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(emptyList()))
    }

    private class TestEngine : AudioEngine {
        override val state = MutableStateFlow(Transport())
        override fun load(track: MusicItem, stream: AudioStream) {
            state.value = Transport(track, playing = true, duration = 200)
        }
        override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
        override fun seek(seconds: Long) { state.value = state.value.copy(position = seconds) }
        override fun volume(value: Float) {}
        override fun speed(value: Float) {}
        override fun soundProfile(profile: SoundProfile) {}
    }

    @Test
    fun parseLrcLyricsAndActiveLyricIndex() {
        val lrcWithMetadataAndMultiTimestamps = """
            [ti:Sparkle]
            [ar:RADWIMPS]
            [al:Your Name.]
            [by:Shinki]
            [00:15.50]Mada kono sekai wa
            [00:30.00][01:10.00]Bokura o keshitai mitai da
            [02:05.123]Unmei da to ka mirai to ka
        """.trimIndent()

        val parsed = parseLrcLyrics(lrcWithMetadataAndMultiTimestamps)
        assertTrue(parsed.isSynced)
        assertEquals(4, parsed.syncedLines.size)

        // Verificamos orden cronológico ascendente
        assertEquals(15500L, parsed.syncedLines[0].timeMillis)
        assertEquals("Mada kono sekai wa", parsed.syncedLines[0].text)

        assertEquals(30000L, parsed.syncedLines[1].timeMillis)
        assertEquals("Bokura o keshitai mitai da", parsed.syncedLines[1].text)

        assertEquals(70000L, parsed.syncedLines[2].timeMillis)
        assertEquals("Bokura o keshitai mitai da", parsed.syncedLines[2].text)

        assertEquals(125123L, parsed.syncedLines[3].timeMillis)
        assertEquals("Unmei da to ka mirai to ka", parsed.syncedLines[3].text)

        // Verificamos activeLyricIndex para distintas posiciones en segundos
        assertEquals(-1, activeLyricIndex(parsed.syncedLines, 5L))
        assertEquals(0, activeLyricIndex(parsed.syncedLines, 15L))
        assertEquals(0, activeLyricIndex(parsed.syncedLines, 20L))
        assertEquals(1, activeLyricIndex(parsed.syncedLines, 30L))
        assertEquals(1, activeLyricIndex(parsed.syncedLines, 50L))
        assertEquals(2, activeLyricIndex(parsed.syncedLines, 70L))
        assertEquals(2, activeLyricIndex(parsed.syncedLines, 100L))
        assertEquals(3, activeLyricIndex(parsed.syncedLines, 126L))
        assertEquals(3, activeLyricIndex(parsed.syncedLines, 300L))

        // Letra en texto plano (sin etiquetas de tiempo)
        val plainTextRaw = """
            Verse 1:
            This is plain text lyrics
            Without any timestamp tag
        """.trimIndent()
        val plainParsed = parseLrcLyrics(plainTextRaw)
        assertFalse(plainParsed.isSynced)
        assertTrue(plainParsed.syncedLines.isEmpty())
        assertEquals(plainTextRaw.trim(), plainParsed.plainText)
        assertEquals(-1, activeLyricIndex(plainParsed.syncedLines, 10L))
    }

    @Test
    fun computeListeningStatsCalculationAndTopRankings() {
        val songA = testSong("track-1", "Zenzenzense", "RADWIMPS - Topic", duration = 240L)
        val songB = testSong("track-2", "Idol", "YOASOBI", duration = 200L)
        val songC = testSong("track-3", "Mukanjyo", "", duration = null) // Sin artista y sin duración fija

        val plays = listOf(
            PlayEventRecord(item = songA, playedSeconds = 120L, completionRatio = 0.5, skipped = false, playedAtEpochMillis = 1000L),
            PlayEventRecord(item = songA, playedSeconds = 240L, completionRatio = 1.0, skipped = false, playedAtEpochMillis = 2000L),
            PlayEventRecord(item = songA, playedSeconds = 180L, completionRatio = 0.75, skipped = false, playedAtEpochMillis = 3000L),
            PlayEventRecord(item = songB, playedSeconds = 200L, completionRatio = 1.0, skipped = false, playedAtEpochMillis = 4000L),
            PlayEventRecord(item = songC, playedSeconds = 0L, completionRatio = 0.0, skipped = false, playedAtEpochMillis = 5000L), // Fallback a 180L
        )
        val favorites = listOf(songA, songB)
        val downloads = listOf(
            DownloadedTrack(item = songA, localPath = "/tmp/a.mp3", mimeType = "audio/mpeg", fileSizeBytes = 15_000_000L, downloadedAtEpochMillis = 1000L),
        )

        val summary = computeListeningStats(plays, favorites, downloads)

        assertEquals(5, summary.totalPlays)
        assertEquals(120L + 240L + 180L + 200L + 180L, summary.totalListenSeconds)
        assertEquals(3, summary.uniqueTracks)
        assertEquals(3, summary.uniqueArtists)
        assertEquals(2, summary.favoriteCount)
        assertEquals(1, summary.offlineCount)

        // Verificamos normalización del artista quitando " - Topic" y fallback "Artista desconocido"
        assertEquals("RADWIMPS", summary.topArtists[0].artist)
        assertEquals(3, summary.topArtists[0].playCount)
        assertEquals(540L, summary.topArtists[0].totalSeconds)

        val artistNames = summary.topArtists.map { it.artist }
        assertTrue("YOASOBI" in artistNames)
        assertTrue("Artista desconocido" in artistNames)

        // Verificamos canción top 1
        assertEquals("track-1", summary.topTracks[0].item.id)
        assertEquals(3, summary.topTracks[0].playCount)
        assertEquals(540L, summary.topTracks[0].totalSeconds)

        // Título de escucha con pocos temas
        assertEquals("Explorador Musical", summary.listenerTitle)

        // Verificamos título de escucha con actividad alta (>= 20 temas o >= 10 favoritos)
        val highFavs = List(12) { idx -> testSong("fav-$idx") }
        val highSummary = computeListeningStats(plays, highFavs, downloads)
        assertEquals("Melómano Hi-Res", highSummary.listenerTitle)
    }

    @Test
    fun sessionStorePersistenceAndPlaybackRestore() = runTest {
        val settings = MapSettings()
        val store = SessionStore(settings)

        // Estado inicial vacío
        assertNull(store.load())

        val song1 = testSong("song-alpha", "Song Alpha", "Artist A", 180L)
        val song2 = testSong("song-beta", "Song Beta", "Artist B", 210L)
        val session = SavedPlaybackSession(
            queue = listOf(song1, song2),
            currentTrackId = "song-alpha",
            positionSeconds = 75L,
            shuffle = true,
            repeat = RepeatMode.One,
            quality = AudioQuality.High,
            format = AudioFormatPreference.M4A,
            profile = SoundProfile.HiFi,
        )

        store.save(session)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(2, loaded.queue.size)
        assertEquals("song-alpha", loaded.queue[0].id)
        assertEquals("song-beta", loaded.queue[1].id)
        assertEquals("song-alpha", loaded.currentTrackId)
        assertEquals(75L, loaded.positionSeconds)
        assertTrue(loaded.shuffle)
        assertEquals(RepeatMode.One, loaded.repeat)
        assertEquals(AudioQuality.High, loaded.quality)
        assertEquals(AudioFormatPreference.M4A, loaded.format)
        assertEquals(SoundProfile.HiFi, loaded.profile)

        // Limpiar store
        store.clear()
        assertNull(store.load())

        // Restaurar sesión en Playback
        val engine = TestEngine()
        val player = Playback(testSource, engine, backgroundScope)
        assertEquals(emptyList(), player.queue.value)
        assertFalse(player.shuffle.value)

        player.restoreSession(loaded)

        assertEquals(2, player.queue.value.size)
        assertEquals("song-alpha", player.queue.value[0].id)
        assertTrue(player.shuffle.value)
        assertEquals(RepeatMode.One, player.repeat.value)
        assertEquals(AudioQuality.High, player.audioQuality.value)
        assertEquals(AudioFormatPreference.M4A, player.audioFormat.value)
        assertEquals(SoundProfile.HiFi, player.soundProfile.value)
        assertFalse(player.engine.state.value.playing) // restoreSession no inicia reproducción de audio
    }

    @Test
    fun m3u8ExportAndImport() {
        val originalList = LocalPlaylist(
            name = "Anime OSTs",
            songs = listOf(
                testSong("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213L),
                testSong("https://soundcloud.com/artist/sample-track", "Sample Track", "Sound Artist", 195L),
            ),
        )

        // Exportar a M3U8
        val exported = exportPlaylistToM3u8(originalList)
        assertTrue(exported.contains("#EXTM3U"))
        assertTrue(exported.contains("#PLAYLIST:Anime OSTs"))
        assertTrue(exported.contains("#EXTINF:213,Rick Astley - Never Gonna Give You Up"))
        assertTrue(exported.contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(exported.contains("#EXTINF:195,Sound Artist - Sample Track"))
        assertTrue(exported.contains("https://soundcloud.com/artist/sample-track"))

        // Importar desde el texto M3U8 generado (defaultName en blanco para usar #PLAYLIST)
        val imported = importPlaylistFromM3u8(defaultName = "", content = exported)
        assertEquals("Anime OSTs", imported.name)
        assertEquals(2, imported.songs.size)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", imported.songs[0].id)
        assertEquals("Never Gonna Give You Up", imported.songs[0].title)
        assertEquals("Rick Astley", imported.songs[0].subtitle)
        assertEquals(213L, imported.songs[0].durationSeconds)
        assertEquals("https://soundcloud.com/artist/sample-track", imported.songs[1].id)
        assertEquals("Sample Track", imported.songs[1].title)
        assertEquals("Sound Artist", imported.songs[1].subtitle)
        assertEquals(195L, imported.songs[1].durationSeconds)

        // defaultName no en blanco sobreescribe la etiqueta #PLAYLIST
        val importedOverride = importPlaylistFromM3u8(defaultName = "Custom Overridden", content = exported)
        assertEquals("Custom Overridden", importedOverride.name)
        assertEquals(2, importedOverride.songs.size)

        // Importar usando nombre por defecto cuando no hay etiqueta #PLAYLIST
        val simpleM3u = """
            #EXTM3U
            https://music.youtube.com/watch?v=12345
        """.trimIndent()
        val importedSimple = importPlaylistFromM3u8(defaultName = "Mi Lista YT", content = simpleM3u)
        assertEquals("Mi Lista YT", importedSimple.name)
        assertEquals(1, importedSimple.songs.size)
        assertEquals("https://music.youtube.com/watch?v=12345", importedSimple.songs[0].id)

        // Error cuando no hay enlaces compatibles
        val invalidM3u = """
            #EXTM3U
            #EXTINF:180,Unknown - Unsupported
            https://sitio-no-soportado.com/audio.mp3
            http://inseguro.youtube.com/track
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            importPlaylistFromM3u8(defaultName = "Inválida", content = invalidM3u)
        }
        assertTrue(exception.message?.contains("No se encontraron enlaces compatibles") == true)
    }

    @Test
    fun mpvLavfiEqualizerFilterGeneration() {
        // Deshabilitado o bandas vacías devuelve string vacío
        assertEquals("", buildMpvLavfiEqualizerFilter(listOf(3.5f, 2.0f), enabled = false))
        assertEquals("", buildMpvLavfiEqualizerFilter(emptyList(), enabled = true))

        // Todas las bandas en 0 dB o por debajo del umbral de 0.05 dB devuelve string vacío
        assertEquals("", buildMpvLavfiEqualizerFilter(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), enabled = true))
        assertEquals("", buildMpvLavfiEqualizerFilter(listOf(0.04f, -0.04f, 0.01f), enabled = true))

        // Con ganancias activas genera filtro lavfi completo con 10 bandas y punto decimal
        val customBands = listOf(3.5f, -2.1f, 0f, 6.0f, -4.5f, 1.2f, 0f, 0f, 0f, 5.0f)
        val filter = buildMpvLavfiEqualizerFilter(customBands, enabled = true)

        assertTrue(filter.startsWith("lavfi=["))
        assertTrue(filter.endsWith("]"))
        assertEquals(10, filter.split("equalizer=").size - 1)

        // Formato numérico con punto decimal independiente de locale
        assertTrue(filter.contains("equalizer=f=31.25:t=q:w=1.2:g=3.5"))
        assertTrue(filter.contains("equalizer=f=62.5:t=q:w=1.2:g=-2.1"))
        assertTrue(filter.contains("equalizer=f=125.0:t=q:w=1.2:g=0.0"))
        assertTrue(filter.contains("equalizer=f=250.0:t=q:w=1.2:g=6.0"))
        assertTrue(filter.contains("equalizer=f=500.0:t=q:w=1.2:g=-4.5"))
        assertTrue(filter.contains("equalizer=f=1000.0:t=q:w=1.2:g=1.2"))
        assertTrue(filter.contains("equalizer=f=16000.0:t=q:w=1.2:g=5.0"))

        // Clamping a -12.0 dB y +12.0 dB
        val extremeBands = listOf(25.0f, -30.0f)
        val clampedFilter = buildMpvLavfiEqualizerFilter(extremeBands, enabled = true)
        assertTrue(clampedFilter.contains("equalizer=f=31.25:t=q:w=1.2:g=12.0"))
        assertTrue(clampedFilter.contains("equalizer=f=62.5:t=q:w=1.2:g=-12.0"))
    }
}
