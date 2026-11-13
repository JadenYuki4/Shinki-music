package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.domain.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class LibraryPersistenceTest {
    @Test fun oldDesktopDatabasePreservesFavoritesAndRestoresPlaylists() = runBlocking {
        val file = Files.createTempDirectory("orbit-library").resolve("orbit.db").toFile()
        val song = MusicItem("https://www.youtube.com/watch?v=abc", "Track", "Artist", MusicKind.Song)
        val first = createDesktopHistoryStore(file)
        first.toggleFavorite(song)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { it.execute("DROP TABLE playlist_document"); it.execute("PRAGMA user_version=0") }
        }
        val store = createDesktopHistoryStore(file)
        assertEquals(listOf(song), store.observeFavorites().first())
        store.playlists.saveQueue("Night", listOf(song))
        val json = store.playlists.export()
        assertFails { store.playlists.import("bad json") }
        assertEquals(json, createDesktopHistoryStore(file).playlists.export())
        store.playlists.delete(store.playlists.lists.value.single())
        store.playlists.import(json)
        assertEquals(song, store.playlists.lists.value.single().songs.single())
    }
}
