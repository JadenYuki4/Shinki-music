// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
internal class SessionQueuePlayer(
    player: Player,
    private val playback: Playback,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ForwardingSimpleBasePlayer(player) {
    private data class ActiveMetaKey(
        val id: String,
        val title: String,
        val subtitle: String,
        val artworkUrl: String?,
        val artSize: Int,
        val existing: MediaMetadata?,
    )

    private var lastNextSkipMs = 0L
    private var lastPreviousSkipMs = 0L
    private var lastActiveMetaKey: ActiveMetaKey? = null
    private var lastActiveMeta: MediaMetadata? = null

    private val artCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 4
    }
    private val artLoading = mutableSetOf<String>()

    fun queueChanged() = invalidateState()

    override fun getState(): State {
        val base = super.getState()
        val current = player.currentMediaItem
        val activeFallback = playback.engine.state.value.track
            ?: playback.pending.value
            ?: playback.queue.value.firstOrNull()
        val currentId = current?.mediaId ?: activeFallback?.id
        if (currentId == null) {
            return base.buildUpon().setAvailableCommands(withSessionCommands(base.availableCommands)).build()
        }

        val queue = playback.queue.value
        val playlist = queue.map { track ->
            val active = track.id == currentId
            val baseItem = if (active && current != null) current else null
            val metadata = track.toMetadata(baseItem?.mediaMetadata, includeArtworkBytes = active)
            val item = (baseItem?.buildUpon() ?: MediaItem.Builder().setMediaId(track.id))
                .setMediaMetadata(metadata)
                .build()
            val duration = if (active && player.duration > 0) player.duration else track.durationSeconds?.times(1000)
            MediaItemData.Builder(track.id)
                .setMediaItem(item)
                .setMediaMetadata(metadata)
                .setIsSeekable(active && current != null && player.isCurrentMediaItemSeekable)
                .setDurationUs(duration?.times(1000) ?: C.TIME_UNSET)
                .build()
        }.toMutableList()

        var index = queue.indexOfFirst { it.id == currentId }
        if (index < 0) {
            // Keep the active or pending track visible while its replacement stream is resolving.
            val syntheticItem = if (current != null) {
                val trackMeta = activeFallback?.takeIf { it.id == current.mediaId }
                    ?.toMetadata(current.mediaMetadata, includeArtworkBytes = true)
                    ?: current.mediaMetadata
                val item = current.buildUpon().setMediaMetadata(trackMeta).build()
                MediaItemData.Builder(current.mediaId)
                    .setMediaItem(item)
                    .setMediaMetadata(trackMeta)
                    .setIsSeekable(player.isCurrentMediaItemSeekable)
                    .setDurationUs(player.duration.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
                    .build()
            } else {
                val fallback = activeFallback!!
                val metadata = fallback.toMetadata(includeArtworkBytes = true)
                val item = MediaItem.Builder().setMediaId(fallback.id).setMediaMetadata(metadata).build()
                MediaItemData.Builder(fallback.id)
                    .setMediaItem(item)
                    .setMediaMetadata(metadata)
                    .setIsSeekable(false)
                    .setDurationUs(fallback.durationSeconds?.times(1000)?.times(1000) ?: C.TIME_UNSET)
                    .build()
            }
            playlist.add(0, syntheticItem)
            index = 0
        }

        return base.buildUpon()
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setAvailableCommands(withSessionCommands(base.availableCommands))
            .build()
    }

    private fun withSessionCommands(commands: Player.Commands): Player.Commands = commands.buildUpon().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME,
        Player.COMMAND_ADJUST_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
    ).build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED)) {
            if (player.playbackState == Player.STATE_ENDED && playback.engine.state.value.track != null) {
                player.seekTo(0)
                player.play()
            } else if (playback.pending.value == null) {
                playback.playOrToggle()
            }
            return Futures.immediateVoidFuture()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> = super.handleStop()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                if (allowNextSkip()) playback.next()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                if (allowPreviousSkip()) playback.back()
            }
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val id = getMediaItemAt(mediaItemIndex).mediaId
                if (id == player.currentMediaItem?.mediaId) player.seekTo(positionMs.coerceAtLeast(0))
                else playback.queue.value.firstOrNull { it.id == id }?.let { playback.select(it, positionMs.coerceAtLeast(0) / 1000) }
            }
            else -> return super.handleSeek(player.currentMediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val max = player.deviceInfo.maxVolume
        if (max > 0) {
            val normalized = (deviceVolume.toFloat() / max).coerceIn(0f, 1f)
            (playback.engine as? AndroidEngine)?.reportDeviceVolume(normalized, playback)
                ?: playback.setVolume(normalized)
        }
        val future = super.handleSetDeviceVolume(deviceVolume, flags)
        invalidateState()
        return future
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val max = player.deviceInfo.maxVolume
        if (max > 0) {
            val nextLevel = (player.deviceVolume + 1).coerceAtMost(max)
            val normalized = (nextLevel.toFloat() / max).coerceIn(0f, 1f)
            (playback.engine as? AndroidEngine)?.reportDeviceVolume(normalized, playback)
                ?: playback.setVolume(normalized)
        } else {
            playback.volumeUp()
        }
        val future = super.handleIncreaseDeviceVolume(flags)
        invalidateState()
        return future
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val max = player.deviceInfo.maxVolume
        if (max > 0) {
            val nextLevel = (player.deviceVolume - 1).coerceAtLeast(0)
            val normalized = (nextLevel.toFloat() / max).coerceIn(0f, 1f)
            (playback.engine as? AndroidEngine)?.reportDeviceVolume(normalized, playback)
                ?: playback.setVolume(normalized)
        } else {
            playback.volumeDown()
        }
        val future = super.handleDecreaseDeviceVolume(flags)
        invalidateState()
        return future
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        val max = player.deviceInfo.maxVolume
        val normalized = if (muted || max <= 0) 0f else (player.deviceVolume.toFloat() / max).coerceIn(0f, 1f)
        (playback.engine as? AndroidEngine)?.reportDeviceVolume(normalized, playback)
            ?: playback.setVolume(normalized)
        val future = super.handleSetDeviceMuted(muted, flags)
        invalidateState()
        return future
    }

    private fun allowNextSkip(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNextSkipMs < 250L) return false
        lastNextSkipMs = now
        return true
    }

    private fun allowPreviousSkip(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviousSkipMs < 250L) return false
        lastPreviousSkipMs = now
        return true
    }

    private fun cachedArtBytes(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val shouldFetch: Boolean
        synchronized(artCache) {
            artCache[url]?.let { return it }
            shouldFetch = artLoading.add(url)
        }
        if (shouldFetch) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { fetchArtBytes(url) }
                synchronized(artCache) {
                    artLoading.remove(url)
                    if (loaded != null && loaded.isNotEmpty()) {
                        artCache[url] = loaded
                    }
                }
                if (loaded != null && loaded.isNotEmpty()) {
                    invalidateState()
                }
            }
        }
        return null
    }

    private fun MusicItem.toMetadata(
        existing: MediaMetadata? = null,
        includeArtworkBytes: Boolean = false,
    ): MediaMetadata {
        val bytes = if (includeArtworkBytes) cachedArtBytes(artworkUrl) else null
        if (includeArtworkBytes) {
            val key = ActiveMetaKey(id, title, subtitle, artworkUrl, bytes?.size ?: 0, existing)
            if (key == lastActiveMetaKey) {
                lastActiveMeta?.let { return it }
            }
            val builder = (existing?.buildUpon() ?: MediaMetadata.Builder())
                .setTitle(title)
                .setArtist(subtitle)
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
            if (bytes != null) {
                builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            return builder.build().also {
                lastActiveMetaKey = key
                lastActiveMeta = it
            }
        }
        return (existing?.buildUpon() ?: MediaMetadata.Builder())
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .build()
    }

    private fun fetchArtBytes(url: String): ByteArray? = runCatching {
        val uri = Uri.parse(url)
        val raw = if (uri.scheme.equals("file", ignoreCase = true) || url.startsWith("/")) {
            val file = java.io.File(uri.path ?: url)
            if (file.exists() && file.length() in 1L..768_000L) file.readBytes() else null
        } else {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    var exceeded = false
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > 768_000) {
                            exceeded = true
                            break
                        }
                        out.write(buf, 0, n)
                    }
                    if (exceeded) null else out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        raw?.let(::compressArtwork)
    }.getOrNull()

    private fun compressArtwork(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) {
            return raw.takeIf { it.size <= 120_000 }
        }
        var sampleSize = 1
        while (maxDim / sampleSize > 512) {
            sampleSize *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return raw.takeIf { it.size <= 120_000 }
        return try {
            val out = java.io.ByteArrayOutputStream()
            decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            decoded.recycle()
        }
    }
}
