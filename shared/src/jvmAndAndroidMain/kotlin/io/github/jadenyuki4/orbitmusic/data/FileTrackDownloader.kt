// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import io.github.jadenyuki4.orbitmusic.artBytes
import io.github.jadenyuki4.orbitmusic.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Downloads progressive HTTPS audio streams into a local directory for offline playback. */
class FileTrackDownloader(
    private val downloadsDir: File,
    private val onOpenFolder: (File) -> Boolean = { false },
    private val maxFileBytes: Long = 512L * 1024L * 1024L,
) : TrackDownloader {
    override val downloadsDirectoryPath: String
        get() = downloadsDir.absolutePath

    override val supportsOpenFolder: Boolean
        get() = true

    override suspend fun downloadToLocal(
        item: MusicItem,
        stream: AudioStream,
        onProgress: (Float?) -> Unit,
    ): DownloadedAudioFile = withContext(Dispatchers.IO) {
        if (stream.url.contains(".m3u8", ignoreCase = true) ||
            stream.mimeType.contains("mpegurl", ignoreCase = true)
        ) {
            throw IllegalStateException("Este tema solo usa transmisión segmentada (HLS) y no admite descarga directa.")
        }

        val uri = URI(stream.url)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "file") {
            "Solo se admiten descargas desde orígenes HTTPS seguros."
        }

        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("No se pudo crear la carpeta de descargas: ${downloadsDir.absolutePath}")
        }

        val ext = extensionForStream(stream)
        val target = File(downloadsDir, buildSafeAudioFilename(item, ext))
        val partFile = File(downloadsDir, "${target.name}.part")

        try {
            if (scheme == "file") {
                val src = File(uri)
                require(src.isFile && src.length() > 0L) { "El archivo de origen no existe." }
                src.copyTo(partFile, overwrite = true)
                onProgress(1f)
            } else {
                downloadHttpsToFile(uri, partFile, onProgress)
            }

            if (!partFile.isFile || partFile.length() <= 0L) {
                throw IOException("El archivo descargado está vacío.")
            }

            if (target.exists()) target.delete()
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }

            val localArt = saveArtworkBestEffort(item)
            DownloadedAudioFile(
                localPath = target.absolutePath,
                mimeType = stream.mimeType,
                fileSizeBytes = target.length(),
                artworkLocalPath = localArt,
            )
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    private suspend fun downloadHttpsToFile(
        uri: URI,
        dest: File,
        onProgress: (Float?) -> Unit,
    ) {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; OrbitMusic/0.2)")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Error HTTP $status al descargar el audio.")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            onProgress(if (totalBytes != null) 0f else null)

            BufferedInputStream(connection.inputStream, 32 * 1024).use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var checkedHeader = false
                    var lastReportedPct = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (!checkedHeader && read >= 7) {
                            checkedHeader = true
                            val head = String(buffer, 0, minOf(read, 16), Charsets.US_ASCII)
                            if (head.startsWith("#EXTM3U")) {
                                throw IllegalStateException("El flujo recibido es una lista HLS y no un archivo descargable.")
                            }
                        }
                        downloaded += read
                        if (downloaded > maxFileBytes) {
                            throw IOException("El archivo supera el límite máximo permitido (${maxFileBytes / (1024 * 1024)} MB).")
                        }
                        output.write(buffer, 0, read)
                        if (totalBytes != null) {
                            val ratio = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val pct = (ratio * 100f).toInt()
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(ratio)
                            }
                        }
                    }
                    output.flush()
                }
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun saveArtworkBestEffort(item: MusicItem): String? = runCatching {
        val url = item.artworkUrl ?: return@runCatching null
        val bytes = artBytes(url) ?: return@runCatching null
        val coversDir = File(downloadsDir, ".covers").apply { mkdirs() }
        val coverFile = File(coversDir, "${trackIdHash(item.id)}.img")
        coverFile.writeBytes(bytes)
        coverFile.absolutePath
    }.getOrNull()

    override fun exists(localPath: String): Boolean =
        localPath.isNotBlank() && File(localPath).let { it.isFile && it.length() > 0L }

    override fun delete(localPath: String) {
        if (localPath.isBlank()) return
        runCatching {
            val file = File(localPath)
            if (file.isFile) file.delete()
        }
    }

    override fun openFolder(): Boolean = onOpenFolder(downloadsDir)
}

internal fun extensionForStream(stream: AudioStream): String {
    val mime = stream.mimeType.lowercase()
    val fmt = stream.formatLabel.orEmpty().lowercase()
    return when {
        mime.contains("mp4") || mime.contains("m4a") || fmt.contains("m4a") -> "m4a"
        mime.contains("webm") || fmt.contains("webm") || fmt.contains("opus") -> "webm"
        mime.contains("mpeg") || mime.contains("mp3") || fmt.contains("mp3") -> "mp3"
        mime.contains("ogg") || fmt.contains("ogg") -> "ogg"
        mime.contains("flac") || fmt.contains("flac") -> "flac"
        mime.contains("wav") || fmt.contains("wav") -> "wav"
        else -> "m4a"
    }
}

internal fun trackIdHash(id: String): String =
    id.hashCode().toUInt().toString(16).padStart(8, '0')

internal fun buildSafeAudioFilename(item: MusicItem, extension: String): String {
    val artist = item.subtitle.removeSuffix(" - Topic").trim()
    val title = item.title.trim()
    val raw = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "pista" }
    val safe = raw
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(80)
        .ifBlank { "pista" }
    return "$safe [${trackIdHash(item.id)}].$extension"
}
