package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import com.grack.nanojson.*

internal actual fun encodePlaylists(lists: List<LocalPlaylist>): String =
    JsonWriter.string(mapOf("version" to 1, "playlists" to lists.map { list -> mapOf(
        "name" to list.name, "songs" to list.songs.map { song -> mapOf(
            "id" to song.id, "title" to song.title, "artist" to song.subtitle,
            "artwork" to song.artworkUrl, "seconds" to song.durationSeconds) }) }))

internal actual fun decodePlaylists(json: String): List<LocalPlaylist> {
    require(json.length <= 500000)
    val root = JsonParser.`object`().from(json)
    require(root.getInt("version") == 1)
    val array = requireNotNull(root.getArray("playlists"))
    require(array.size <= 32)
    val names = mutableSetOf<String>()
    return array.map { entry ->
        val list = entry as JsonObject
        val name = requireNotNull(list.getString("name")).trim()
        require(name.length in 1..64 && names.add(name.lowercase()))
        val songs = requireNotNull(list.getArray("songs"))
        require(songs.size <= 100)
        LocalPlaylist(name, songs.map { raw ->
            val row = raw as JsonObject
            val id = requireNotNull(row.getString("id"))
            val uri = java.net.URI(id)
            require(uri.scheme == "https" && uri.host in setOf("www.youtube.com","youtube.com","music.youtube.com","youtu.be"))
            val title = requireNotNull(row.getString("title"))
            require(title.isNotBlank())
            val artwork = row.getString("artwork")?.takeIf {
                val host = java.net.URI(it).host.orEmpty()
                it.startsWith("https://") && (host.endsWith(".ytimg.com") || host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com"))
            }
            MusicItem(id, title, row.getString("artist").orEmpty(), MusicKind.Song, artwork,
                (row["seconds"] as? Number)?.toLong()?.takeIf { it >= 0 })
        }.distinctBy { it.id })
    }
}
