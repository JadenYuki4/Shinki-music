// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.jadenyuki4.orbitmusic.domain.MusicItem

@UnstableApi
internal class SessionQueuePlayer(player: Player, private val playback: Playback) : ForwardingSimpleBasePlayer(player) {
    fun queueChanged() = invalidateState()

    override fun getState(): State {
        val base = super.getState()
        val current = player.currentMediaItem
            ?: return base.buildUpon().setAvailableCommands(withSessionCommands(base.availableCommands)).build()
        val queue = playback.queue.value
        val playlist = queue.map { track ->
            val active = track.id == current.mediaId
            val item = if (active) current else track.sessionItem()
            val duration = if (active && player.duration > 0) player.duration else track.durationSeconds?.times(1000)
            MediaItemData.Builder(track.id)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setIsSeekable(active && player.isCurrentMediaItemSeekable)
                .setDurationUs(duration?.times(1000) ?: C.TIME_UNSET)
                .build()
        }.toMutableList()
        var index = queue.indexOfFirst { it.id == current.mediaId }
        if (index < 0) {
            // Keep the playing track visible while its replacement stream is resolving.
            playlist.add(0, MediaItemData.Builder(current.mediaId).setMediaItem(current)
                .setMediaMetadata(current.mediaMetadata).setIsSeekable(player.isCurrentMediaItemSeekable)
                .setDurationUs(player.duration.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET).build())
            index = 0
        }
        return base.buildUpon().setPlaylist(playlist).setCurrentMediaItemIndex(index)
            .setAvailableCommands(withSessionCommands(base.availableCommands)).build()
    }

    private fun withSessionCommands(commands: Player.Commands): Player.Commands = commands.buildUpon().addAll(
        Player.COMMAND_PLAY_PAUSE,
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

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playback.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playback.back()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val id = getMediaItemAt(mediaItemIndex).mediaId
                if (id == player.currentMediaItem?.mediaId) player.seekTo(positionMs.coerceAtLeast(0))
                else playback.queue.value.firstOrNull { it.id == id }?.let { playback.select(it, positionMs.coerceAtLeast(0) / 1000) }
            }
            else -> return super.handleSeek(player.currentMediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    private fun MusicItem.sessionItem(): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle)
            .setArtworkUri(artworkUrl?.let(Uri::parse)).build())
        .build()
}
