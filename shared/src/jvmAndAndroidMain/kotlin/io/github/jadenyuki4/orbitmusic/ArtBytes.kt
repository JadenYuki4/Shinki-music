package io.github.jadenyuki4.orbitmusic

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.HttpURLConnection

private val slots = Semaphore(2)
private val cache = LinkedHashMap<String, ByteArray>(16, .75f, true)

private fun bundledResourceBytes(fileName: String): ByteArray? = runCatching {
    Thread.currentThread().contextClassLoader?.getResourceAsStream(fileName)?.use { it.readBytes() }
        ?: object {}.javaClass.getResourceAsStream("/$fileName")?.use { it.readBytes() }
        ?: listOf(
            "shared/src/commonMain/resources/$fileName",
            "../shared/src/commonMain/resources/$fileName",
            "src/commonMain/resources/$fileName",
        ).map(::File).firstOrNull { it.isFile }?.readBytes()
}.getOrNull()

internal fun animeBackdropBytes(): ByteArray? = bundledResourceBytes("anime_shrine_bg.jpg")

internal fun playerAnimeGifBytes(): ByteArray? = bundledResourceBytes("player_anime_gif_bg.jpg")

internal fun upgradeArtworkUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.contains("googleusercontent.com", ignoreCase = true) || trimmed.contains("ggpht.com", ignoreCase = true)) {
        return trimmed
            .replace(Regex("=w\\d+-h\\d+"), "=w1080-h1080")
            .replace(Regex("=s\\d+"), "=s1080")
    }
    if (trimmed.contains("sndcdn.com", ignoreCase = true)) {
        return trimmed
            .replace("-large.", "-t500x500.")
            .replace("-t300x300.", "-t500x500.")
            .replace("-badge.", "-t500x500.")
    }
    if (trimmed.contains("ytimg.com", ignoreCase = true) || trimmed.contains("img.youtube.com", ignoreCase = true)) {
        val clean = if (trimmed.substringBefore("?").endsWith(".jpg", ignoreCase = true) ||
            trimmed.substringBefore("?").endsWith(".webp", ignoreCase = true)
        ) trimmed.substringBefore("?") else trimmed
        return clean
            .replace("/mqdefault.jpg", "/hqdefault.jpg")
            .replace("/default.jpg", "/hqdefault.jpg")
    }
    return trimmed
}

private fun fetchHttpsBytes(targetUrl: String): ByteArray? {
    val uri = URI(targetUrl)
    if (uri.scheme != "https") return null
    val connection = uri.toURL().openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        if (connection.responseCode != 200) return null
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                if (Thread.interrupted()) throw InterruptedException()
                if (output.size() + n > 4 * 1024 * 1024) return null
                output.write(buf, 0, n)
            }
        }
        output.toByteArray()
    } finally {
        connection.disconnect()
    }
}

internal suspend fun artBytes(url: String): ByteArray? = slots.withPermit {
    synchronized(cache) { cache[url] }?.let { return@withPermit it }
    runInterruptible(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            val file = if (url.startsWith("file://", ignoreCase = true)) File(URI(url)) else File(url)
            val local = file.takeIf { it.isFile && it.length() in 1L..(8 * 1024 * 1024) }?.readBytes()
                ?: return@runInterruptible null
            return@runInterruptible local.also {
                synchronized(cache) {
                    cache[url] = it
                    while (cache.size > 16) cache.remove(cache.keys.first())
                }
            }
        }
        val upgraded = upgradeArtworkUrl(url)
        val bytes = if (upgraded != url) fetchHttpsBytes(upgraded) ?: fetchHttpsBytes(url) else fetchHttpsBytes(url)
        bytes?.also {
            synchronized(cache) {
                cache[url] = it
                while (cache.size > 16) cache.remove(cache.keys.first())
            }
        }
    }
}
