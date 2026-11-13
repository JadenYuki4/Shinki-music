package io.github.jadenyuki4.orbitmusic

import com.grack.nanojson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun latestRelease(): String? = withContext(Dispatchers.IO) {
    val url = URL("https://api.github.com/repos/JadenYuki4/Shinki-music/releases/latest")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Shinki-music")
        if (connection.responseCode == 404) return@withContext null
        check(connection.responseCode == 200)
        connection.inputStream.use { stream ->
            requireNotNull(JsonParser.`object`().from(stream).getString("tag_name")).also { versionParts(it) }
        }
    } finally { connection.disconnect() }
}
