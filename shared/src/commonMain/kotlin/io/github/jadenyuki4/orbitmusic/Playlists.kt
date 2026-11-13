package io.github.jadenyuki4.orbitmusic

import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.flow.MutableStateFlow

data class LocalPlaylist(val name: String, val songs: List<MusicItem>)
internal expect fun encodePlaylists(lists: List<LocalPlaylist>): String
internal expect fun decodePlaylists(json: String): List<LocalPlaylist>

/** Imports validate the entire bounded document before replacing the saved collection. */
class Playlists(private val save: (String) -> Unit = {}, read: () -> String? = { null }) {
    val lists = MutableStateFlow(read()?.let(::decodePlaylists).orEmpty())
    private fun replace(next: List<LocalPlaylist>) {
        require(next.size <= 32 && next.all { it.songs.size <= 100 }) { "Límite: 32 listas de hasta 100 canciones cada una." }
        val json = encodePlaylists(next)
        require(json.length <= 500000) { "La colección de listas es demasiado grande." }
        save(json)
        lists.value = next
    }
    fun saveQueue(name: String, songs: List<MusicItem>) {
        val clean = name.trim()
        require(clean.length in 1..64) { "Usá un nombre de entre 1 y 64 caracteres." }
        require(lists.value.none { it.name.equals(clean, true) }) { "Ya existe una lista con ese nombre." }
        require(songs.isNotEmpty()) { "Primero agregá una canción a la cola." }
        replace(lists.value + LocalPlaylist(clean, songs.distinctBy { it.id }))
    }
    fun delete(list: LocalPlaylist) = replace(lists.value - list)
    fun export() = encodePlaylists(lists.value)
    fun import(json: String) = replace(decodePlaylists(json))
}
