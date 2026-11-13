package io.github.jadenyuki4.orbitmusic

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import kotlinx.coroutines.CancellationException

internal expect suspend fun loadArt(url: String): ImageBitmap?
internal expect fun loadAnimeBackdrop(): ImageBitmap?

@Composable
fun TrackArt(item: MusicItem?, modifier: Modifier, localArtPath: String? = null) {
    val url = localArtPath?.takeIf { it.isNotBlank() } ?: item?.artworkUrl
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url != null) image = try { loadArt(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val current = image
    if (current == null) SignalSleeve(modifier)
    else Image(current, null, modifier, contentScale = ContentScale.Crop)
}
