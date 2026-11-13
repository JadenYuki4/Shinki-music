// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.MapSettings
import io.github.jadenyuki4.orbitmusic.db.OrbitDatabase
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SqlHistoryStoreTest {
    private var now = 1_000L

    private fun newStore(
        settings: MapSettings = MapSettings(),
        maxSearches: Long = 200L,
        maxPlays: Long = 1000L,
    ): SqlHistoryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitDatabase.Schema.create(driver)
        return SqlHistoryStore(
            driver = driver,
            settings = settings,
            ioDispatcher = UnconfinedTestDispatcher(),
            clockMillis = { now },
            maxSearchEntries = maxSearches,
            maxPlayEntries = maxPlays,
        )
    }

    @Test
    fun recordsDeduplicatesAndOrdersSearchHistoryByRecency() = runTest {
        val store = newStore()
        now = 1_000L
        store.recordSearch("  daft punk ", MusicKind.Song)
        now = 2_000L
        store.recordSearch("kavinsky", MusicKind.Artist)
        now = 3_000L
        store.recordSearch("daft punk", MusicKind.Song)

        val entries = store.observeRecentSearches(limit = 10).first()
        assertEquals(2, entries.size)
        assertEquals(SearchHistoryEntry("daft punk", MusicKind.Song, 3_000L), entries[0])
        assertEquals(SearchHistoryEntry("kavinsky", MusicKind.Artist, 2_000L), entries[1])
    }

    @Test
    fun trimsSearchHistoryToMaxEntries() = runTest {
        val store = newStore(maxSearches = 3L)
        for (i in 1..5) {
            now = i * 100L
            store.recordSearch("query-$i", MusicKind.Song)
        }
        val entries = store.observeRecentSearches(limit = 10).first()
        assertEquals(listOf("query-5", "query-4", "query-3"), entries.map { it.query })
    }

    @Test
    fun removesIndividualSearchAndClearsAll() = runTest {
        val store = newStore()
        now = 100L
        store.recordSearch("synth", MusicKind.Song)
        now = 200L
        store.recordSearch("synth", MusicKind.Album)

        store.removeSearch("synth", MusicKind.Song)
        val remaining = store.observeRecentSearches(limit = 10).first()
        assertEquals(listOf(SearchHistoryEntry("synth", MusicKind.Album, 200L)), remaining)

        store.clearSearchHistory()
        assertTrue(store.observeRecentSearches(limit = 10).first().isEmpty())
    }

    @Test
    fun incognitoPersistsInSettingsAndSuppressesSearchAndPlayRecording() = runTest {
        val settings = MapSettings()
        val store = newStore(settings = settings)
        assertFalse(store.incognito.value)

        store.setIncognito(true)
        assertTrue(store.incognito.value)
        assertTrue(settings.getBoolean(SqlHistoryStore.KEY_INCOGNITO, false))

        store.recordSearch("private search", MusicKind.Song)
        store.recordPlay(
            item = MusicItem("https://music.youtube.com/watch?v=1", "Track", "Artist", MusicKind.Song),
            playedSeconds = 120L,
            completionRatio = 0.8,
            skipped = false,
        )

        assertTrue(store.observeRecentSearches().first().isEmpty())
        assertTrue(store.observeRecentPlays().first().isEmpty())
    }

    @Test
    fun recordsPlayEventsAndTrimsToCapacity() = runTest {
        val store = newStore(maxPlays = 2L)
        val trackA = MusicItem("https://music.youtube.com/watch?v=a", "Alpha", "Artist A", MusicKind.Song, durationSeconds = 200L)
        val trackB = MusicItem("https://music.youtube.com/watch?v=b", "Beta", "Artist B", MusicKind.Song, durationSeconds = 180L)
        val trackC = MusicItem("https://music.youtube.com/watch?v=c", "Gamma", "Artist C", MusicKind.Song, durationSeconds = 240L)

        now = 10L
        store.recordPlay(trackA, playedSeconds = 20L, completionRatio = 0.1, skipped = true)
        now = 20L
        store.recordPlay(trackB, playedSeconds = 180L, completionRatio = 1.0, skipped = false)
        now = 30L
        store.recordPlay(trackC, playedSeconds = 120L, completionRatio = 0.5, skipped = false)

        val plays = store.observeRecentPlays(limit = 10).first()
        assertEquals(2, plays.size)
        assertEquals("Gamma", plays[0].item.title)
        assertEquals(0.5, plays[0].completionRatio)
        assertFalse(plays[0].skipped)
        assertEquals("Beta", plays[1].item.title)

        store.clearPlayHistory()
        assertTrue(store.observeRecentPlays(limit = 10).first().isEmpty())
    }

    @Test
    fun togglesAndClearsFavorites() = runTest {
        val store = newStore()
        val song = MusicItem("https://music.youtube.com/watch?v=fav1", "Nightcall", "Kavinsky", MusicKind.Song, durationSeconds = 258L)

        assertFalse(store.observeIsFavorite(song.id).first())
        assertTrue(store.toggleFavorite(song))
        assertTrue(store.observeIsFavorite(song.id).first())
        assertEquals(listOf(song), store.observeFavorites().first())

        assertFalse(store.toggleFavorite(song))
        assertFalse(store.observeIsFavorite(song.id).first())
        assertTrue(store.observeFavorites().first().isEmpty())
    }

    @Test
    fun savesRemovesAndClearsOfflineDownloads() = runTest {
        val store = newStore()
        val songA = MusicItem("https://music.youtube.com/watch?v=dl1", "Offline One", "Artist 1", MusicKind.Song, durationSeconds = 190L)
        val songB = MusicItem("https://music.youtube.com/watch?v=dl2", "Offline Two", "Artist 2", MusicKind.Song, durationSeconds = 210L)

        now = 1_000L
        store.saveDownload(
            DownloadedTrack(
                item = songA,
                localPath = "/tmp/one.m4a",
                mimeType = "audio/mp4",
                formatLabel = "M4A",
                bitrateKbps = 256,
                fileSizeBytes = 1024L,
            )
        )
        now = 2_000L
        store.saveDownload(
            DownloadedTrack(
                item = songB,
                localPath = "/tmp/two.webm",
                mimeType = "audio/webm",
                formatLabel = "OPUS",
                bitrateKbps = 160,
                fileSizeBytes = 2048L,
            )
        )

        assertTrue(store.observeIsDownloaded(songA.id).first())
        assertEquals(2000L, store.getDownload(songB.id)?.downloadedAtEpochMillis)
        val downloads = store.observeDownloads().first()
        assertEquals(listOf(songB.id, songA.id), downloads.map { it.item.id })

        store.removeDownload(songA.id)
        assertFalse(store.observeIsDownloaded(songA.id).first())
        assertNull(store.getDownload(songA.id))
        assertEquals(listOf(songB.id), store.observeDownloads().first().map { it.item.id })

        store.clearDownloads()
        assertTrue(store.observeDownloads().first().isEmpty())
    }

    @Test
    fun fileTrackDownloaderSavesAudioFileAndPersistsMetadata() = runTest {
        val tempRoot = Files.createTempDirectory("shinki-dl-test").toFile()
        try {
            val sourceAudio = File(tempRoot, "sample.m4a").apply {
                writeBytes(ByteArray(256) { it.toByte() })
            }
            val targetDir = File(tempRoot, "downloads")
            val downloader = FileTrackDownloader(targetDir)
            val song = MusicItem(
                id = "https://music.youtube.com/watch?v=local1",
                title = "Mi Canción: Test/01",
                subtitle = "Artista <Demo>",
                kind = MusicKind.Song,
                durationSeconds = 200L,
            )
            val saved = downloader.downloadToLocal(
                item = song,
                stream = AudioStream(
                    url = sourceAudio.toURI().toString(),
                    mimeType = "audio/mp4",
                    bitrateKbps = 320,
                    formatLabel = "M4A",
                ),
            )
            assertTrue(downloader.exists(saved.localPath))
            assertTrue(File(saved.localPath).name.endsWith(".m4a"))
            assertEquals(256L, saved.fileSizeBytes)

            val store = newStore()
            store.saveDownload(
                DownloadedTrack(
                    item = song,
                    localPath = saved.localPath,
                    mimeType = saved.mimeType,
                    formatLabel = "M4A",
                    bitrateKbps = 320,
                    fileSizeBytes = saved.fileSizeBytes,
                    artworkLocalPath = saved.artworkLocalPath,
                )
            )
            val persisted = store.getDownload(song.id)
            assertNotNull(persisted)
            assertEquals(saved.localPath, persisted.localPath)

            downloader.delete(saved.localPath)
            assertFalse(downloader.exists(saved.localPath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
