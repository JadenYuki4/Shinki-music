package io.github.jadenyuki4.orbitmusic

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.*
import kotlinx.coroutines.*

private val cachedAnimeBackdrop: ImageBitmap? by lazy {
    runCatching {
        val bytes = animeBackdropBytes() ?: return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

internal actual fun loadAnimeBackdrop(): ImageBitmap? = cachedAnimeBackdrop

internal actual suspend fun loadArt(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
    val bytes = artBytes(url) ?: return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val size = maxOf(bounds.outHeight, bounds.outWidth)
    if (size !in 1..2048) return@withContext null
    val options = BitmapFactory.Options().apply { while (size / inSampleSize.coerceAtLeast(1) > 512) inSampleSize = inSampleSize.coerceAtLeast(1) * 2 }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
