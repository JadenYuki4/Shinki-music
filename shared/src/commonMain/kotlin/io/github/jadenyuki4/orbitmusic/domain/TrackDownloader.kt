// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.domain

data class DownloadedAudioFile(
    val localPath: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val artworkLocalPath: String? = null,
)

interface TrackDownloader {
    val downloadsDirectoryPath: String
    val supportsOpenFolder: Boolean get() = false

    suspend fun downloadToLocal(
        item: MusicItem,
        stream: AudioStream,
        onProgress: (Float?) -> Unit = {},
    ): DownloadedAudioFile

    fun exists(localPath: String): Boolean
    fun delete(localPath: String)
    fun openFolder(): Boolean = false
}

object NoOpTrackDownloader : TrackDownloader {
    override val downloadsDirectoryPath: String = ""

    override suspend fun downloadToLocal(
        item: MusicItem,
        stream: AudioStream,
        onProgress: (Float?) -> Unit,
    ): DownloadedAudioFile = throw UnsupportedOperationException("Las descargas locales no están disponibles en este entorno.")

    override fun exists(localPath: String): Boolean = false
    override fun delete(localPath: String) {}
}
