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

internal fun animeBackdropBytes(): ByteArray? = runCatching {
    Thread.currentThread().contextClassLoader?.getResourceAsStream("anime_shrine_bg.jpg")?.use { it.readBytes() }
        ?: object {}.javaClass.getResourceAsStream("/anime_shrine_bg.jpg")?.use { it.readBytes() }
        ?: listOf(
            "shared/src/commonMain/resources/anime_shrine_bg.jpg",
            "../shared/src/commonMain/resources/anime_shrine_bg.jpg",
            "src/commonMain/resources/anime_shrine_bg.jpg",
        ).map(::File).firstOrNull { it.isFile }?.readBytes()
}.getOrNull()

internal suspend fun artBytes(url: String): ByteArray? = slots.withPermit {
    synchronized(cache) { cache[url] }?.let { return@withPermit it }
    runInterruptible(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            val file = if (url.startsWith("file://", ignoreCase = true)) File(URI(url)) else File(url)
            val local = file.takeIf { it.isFile && it.length() in 1L..(2 * 1024 * 1024) }?.readBytes()
                ?: return@runInterruptible null
            return@runInterruptible local.also {
                synchronized(cache) {
                    cache[url] = it
                    while (cache.size > 16) cache.remove(cache.keys.first())
                }
            }
        }
        val uri = URI(url)
        if (uri.scheme != "https") return@runInterruptible null
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            if (connection.responseCode != 200) return@runInterruptible null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (Thread.interrupted()) throw InterruptedException()
                    if (output.size() + n > 256 * 1024) return@runInterruptible null
                    output.write(buf, 0, n)
                }
            }
            output.toByteArray().also { synchronized(cache) {
                cache[url] = it
                while (cache.size > 16) cache.remove(cache.keys.first())
            } }
        } finally { connection.disconnect() }
    }
}
